"""Optional Modal deployment. Nothing deploys or runs a GPU merely by importing this file."""
from pathlib import Path
import os
import shutil
import threading
import modal
from strata_server.budget import SOFT_SECONDS,WORKER_SECONDS

# Local deployment shell variables are not automatically present during remote imports.
# Persist only resource names, never account/endpoint keys, in both container images.
runtime_env = {
    "STRATA_MODAL_APP": os.environ.get("STRATA_MODAL_APP","strata-gpu"),
    "STRATA_MODAL_VOLUME": os.environ.get("STRATA_MODAL_VOLUME","strata-data"),
    "STRATA_MODAL_DICT": os.environ.get("STRATA_MODAL_DICT","strata-jobs"),
    "STRATA_MODAL_SECRET": os.environ.get("STRATA_MODAL_SECRET","strata-server-auth"),
}
app = modal.App(runtime_env["STRATA_MODAL_APP"])
volume = modal.Volume.from_name(runtime_env["STRATA_MODAL_VOLUME"], create_if_missing=True)
table = modal.Dict.from_name(runtime_env["STRATA_MODAL_DICT"], create_if_missing=True)
root = Path("/data/jobs")
source = str(Path(__file__).parent / "strata_server")
api_image = (modal.Image.debian_slim(python_version="3.11")
    .pip_install("fastapi>=0.115,<1", "pillow>=10,<13", "numpy>=1.26,<3", "trimesh>=4.4,<5", "plyfile>=1,<2")
    .env(runtime_env)
    .add_local_dir(source, "/root/strata_server"))
gpu_image = (modal.Image.from_registry("ghcr.io/nerfstudio-project/nerfstudio:1.1.5")
    .pip_install("fastapi>=0.115,<1", "pillow>=10,<13", "trimesh>=4.4,<5", "scipy>=1.11,<2", "plyfile>=1,<2")
    .env(runtime_env)
    .add_local_dir(source, "/root/strata_server"))


class ModalJobs:
    def __init__(self): self.lock = threading.RLock()
    def directory(self, ident):
        from strata_server.data import job_id
        return root / job_id(ident)
    def get(self, ident):
        self.directory(ident)
        return table["job:" + ident]
    def put(self, record): table["job:" + record["id"]] = record
    def cancelled(self, ident):
        try: return bool(table["cancel:" + ident])
        except KeyError: return False
    def update(self, ident, **changes):
        import time
        with self.lock:
            record = self.get(ident)
            # State transitions must bypass the worker's negative cancellation cache.
            if record.get("phase") == "cancelled" or ModalJobs.cancelled(self, ident): changes.update(phase="cancelled", message="Cancelled by client")
            record.update(changes, updated=time.time()); self.put(record); return record
    def cancel(self, ident):
        table["cancel:" + ident] = True
        return self.update(ident, phase="cancelled", message="Cancelled by client")
    def records(self): return [value for key, value in table.items() if key.startswith("job:")]


@app.function(image=gpu_image, gpu="L4", cpu=8, memory=32768, timeout=WORKER_SECONDS, startup_timeout=300, max_containers=1, volumes={"/data": volume})
def reconstruct(ident: str):
    print(f"[Strata {ident}] GPU worker entered; loading committed dataset",flush=True)
    from strata_server.monitoring import CancellationFlag
    import time
    class WorkerJobs(ModalJobs):
        def __init__(self):
            super().__init__()
            self.deadline=time.monotonic()+SOFT_SECONDS
            self.cancel_flag = CancellationFlag(lambda: ModalJobs.cancelled(self, ident))
        def cancelled(self, job):
            return self.cancel_flag.check() if job == ident else super().cancelled(job)
        def persist(self):volume.commit()
        def update(self, ident, **changes):
            # Publish completion only after the artifacts are durable and visible to the API container.
            if changes.get("phase") == "complete":
                volume.commit()
            return super().update(ident, **changes)
    jobs=WorkerJobs()
    try:
        import time
        jobs.update(ident,phase="running",stage="loading",message="GPU worker allocated; loading capture data",worker_started=time.time(),worker_heartbeat=time.time(),worker_limit=WORKER_SECONDS,worker_deadline=time.time()+SOFT_SECONDS)
        if jobs.cancelled(ident):return
        volume.reload()
        print(f"[Strata {ident}] Dataset volume reloaded; starting reconstruction",flush=True)
        from strata_server.pipeline import run_job
        run_job(jobs,ident)
    except Exception as exc:
        jobs.update(ident,phase="failed",stage="failed",message="Worker startup or storage failed: "+str(exc)[-1200:])
        print(f"[Strata {ident}] Worker failed before completing reconstruction",flush=True)
        raise
    finally:
        try:volume.commit()
        except Exception:print(f"[Strata {ident}] Final volume commit failed; check job state and storage",flush=True)


@app.function(image=api_image, cpu=2, memory=4096, startup_timeout=300, max_containers=1, volumes={"/data": volume}, secrets=[modal.Secret.from_name(runtime_env["STRATA_MODAL_SECRET"])])
@modal.concurrent(max_inputs=16)
@modal.asgi_app(requires_proxy_auth=True)
def api():
    from strata_server.api import create_app
    from strata_server.data import ARTIFACTS, extract_dataset
    from strata_server.monitoring import CallMonitor
    jobs = ModalJobs()
    files_lock = threading.Lock()
    def store(ident, archive):
        # Validate outside the shared Volume, then commit before spawning the GPU call.
        import tempfile
        temp = Path(tempfile.mkdtemp(prefix="strata-validate-"))
        try:
            stats = extract_dataset(archive, temp / "dataset")
            with files_lock:
                volume.reload()
                destination = jobs.directory(ident); destination.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(archive, destination / "dataset.zip")
                shutil.copytree(temp / "dataset", destination / "dataset", dirs_exist_ok=True)
                volume.commit()
            jobs.update(ident, **stats)
        finally: shutil.rmtree(temp, ignore_errors=True)
    def dispatch(ident): table["call:" + ident] = reconstruct.spawn(ident).object_id
    def cancel(ident):
        try: modal.FunctionCall.from_id(table["call:" + ident]).cancel(terminate_containers=True)
        except KeyError: pass
    def poll_call(ident):
        modal.FunctionCall.from_id(table["call:" + ident]).get(timeout=0)
    reconcile = CallMonitor(jobs, poll_call, terminal_errors=(
        modal.exception.FunctionTimeoutError, modal.exception.RemoteError,
        modal.exception.ExecutionError, modal.exception.InternalFailure))
    def idle(ident):
        try:poll_call(ident);return True
        except TimeoutError:return False
        except (modal.exception.FunctionTimeoutError,modal.exception.RemoteError,modal.exception.ExecutionError,modal.exception.InternalFailure):return True
    def recover(ident):
        from strata_server.recovery import recover_saved
        with files_lock:
            volume.reload()
            return recover_saved(jobs,ident)
    def artifact(ident, kind):
        # Responses stream a local copy; no open Volume handle blocks subsequent reloads.
        with files_lock:
            volume.reload()
            original = jobs.directory(ident) / "artifacts" / ARTIFACTS[kind]
            cache = Path("/tmp/strata-downloads") / ident / ARTIFACTS[kind]
            cache.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, cache)
            return cache
    return create_app(root, os.environ["STRATA_API_KEY"], jobs=jobs, dispatch=dispatch, cancel_hook=cancel, storage_hook=store, artifact_hook=artifact, status_hook=reconcile, recovery_hook=recover, idle_hook=idle, provider="modal", available={"splat": True, "mesh": True, "tools": {"worker": "Nerfstudio + COLMAP"}})
