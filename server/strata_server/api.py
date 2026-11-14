from __future__ import annotations
import asyncio
import hmac
import os
import secrets
import shutil
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse

from .data import ARTIFACTS, MAX_UPLOAD, extract_dataset, job_id
from .jobs import FileJobs, TERMINAL
from .pipeline import capabilities, run_job
from .version import VERSION


def create_app(root: Path, key: str, *, jobs=None, dispatch=None, cancel_hook=None, storage_hook=None, artifact_hook=None, status_hook=None, provider="lan", available=None, recovery_hook=None, idle_hook=None):
    if len(key) < 24: raise ValueError("Use a server key of at least 24 characters")
    root.mkdir(parents=True, exist_ok=True)
    jobs = jobs or FileJobs(root)
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="strata-gpu")
    dispatch = dispatch or (lambda ident: executor.submit(run_job, jobs, ident))
    def shutdown():
        for record in jobs.records():
            if record["phase"] not in TERMINAL: jobs.cancel(record["id"])
        executor.shutdown(wait=False, cancel_futures=True)
    @asynccontextmanager
    async def lifespan(_):
        try: yield
        finally:
            if provider=="lan": shutdown()
    application = FastAPI(title="Strata GPU Server", version=VERSION, docs_url=None, redoc_url=None, lifespan=lifespan)
    application.state.jobs = jobs
    application.state.executor = executor

    async def auth(x_strata_key: str = Header(default="")):
        if not hmac.compare_digest(x_strata_key.encode(), key.encode()): raise HTTPException(401, "Invalid server access key")

    def get(ident):
        try:
            job_id(ident)
            record = jobs.get(ident)
            return status_hook(ident, record) if status_hook else record
        except (ValueError, KeyError): raise HTTPException(404, "Job not found")

    @application.get("/v1/health", dependencies=[Depends(auth)])
    def health():
        have = available if available is not None else capabilities()
        have={**have,"recovery":True,"resume_saved":True}
        return {"service": "strata", "protocol": 1, "version": VERSION, "provider": provider, "capabilities": have, "max_upload_bytes": MAX_UPLOAD, "warning": "Modal GPU execution may incur charges" if provider == "modal" else "Processing stays on your configured server"}

    @application.post("/v1/jobs", status_code=202, dependencies=[Depends(auth)])
    async def submit(request: Request, mode: Literal["splat", "mesh", "both"] = "splat", preset: Literal["quick", "detail", "max"] = "detail", x_request_id: str = Header(default="")):
        have = available if available is not None else capabilities()
        required = ["splat", "mesh"] if mode == "both" else [mode]
        if any(not have.get(kind) for kind in required): raise HTTPException(503, "Requested engine is not installed. Check server setup.")
        try: ident = job_id(x_request_id) if x_request_id else str(uuid.uuid4())
        except ValueError: raise HTTPException(400, "Invalid request ID")
        try:
            existing = jobs.get(ident)
            if existing["mode"] != mode or existing["preset"] != preset: raise HTTPException(409, "Request ID already used for different settings")
            if existing["phase"] == "uploading": raise HTTPException(409, "This upload is still in progress")
            return existing
        except KeyError: pass
        if sum(r["phase"] not in TERMINAL for r in jobs.records()) >= 16: raise HTTPException(429, "Server queue is full")
        length = request.headers.get("content-length")
        if length and (not length.isdigit() or int(length) > MAX_UPLOAD): raise HTTPException(413, "Dataset exceeds 600 MiB")
        created = time.time()
        record = {"id": ident, "mode": mode, "preset": preset, "phase": "uploading", "stage": "uploading", "message": "Receiving original dataset", "created": created, "updated": created, "artifacts": []}
        jobs.put(record)
        temp = Path(tempfile.mkdtemp(prefix="strata-upload-"))
        archive = temp / "dataset.zip"
        try:
            size = 0
            with archive.open("wb") as out:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size > MAX_UPLOAD: raise HTTPException(413, "Dataset exceeds 600 MiB")
                    out.write(chunk)
            if storage_hook:
                await asyncio.to_thread(storage_hook, ident, archive)
            else:
                directory = jobs.directory(ident); directory.mkdir(exist_ok=True)
                shutil.copyfile(archive, directory / "dataset.zip")
                stats = await asyncio.to_thread(extract_dataset, directory / "dataset.zip", directory / "dataset")
                jobs.update(ident, **stats)
            jobs.update(ident, phase="queued", stage="queued", message="Waiting for a GPU worker")
            if jobs.cancelled(ident): return jobs.get(ident)
            await asyncio.to_thread(dispatch, ident)
            return jobs.get(ident)
        except HTTPException as exc:
            jobs.update(ident, phase="failed", message=str(exc.detail)); raise
        except Exception as exc:
            jobs.update(ident, phase="failed", message=str(exc)[-1000:])
            raise HTTPException(400, str(exc)[-1000:])
        finally: shutil.rmtree(temp, ignore_errors=True)

    @application.get("/v1/jobs/{ident}", dependencies=[Depends(auth)])
    def status(ident: str): return get(ident)

    @application.post("/v1/jobs/{ident}/cancel", dependencies=[Depends(auth)])
    def cancel(ident: str):
        record = get(ident)
        if record["phase"] in TERMINAL: return record
        jobs.cancel(ident)
        if cancel_hook: cancel_hook(ident)
        return jobs.get(ident)

    def stopped_record(ident):
        record=get(ident)
        if record['phase'] not in {'failed','complete'}:raise HTTPException(409,'Finish or cancel active work before inspecting saved results; cancelled jobs cannot be resumed.')
        if idle_hook:
            try:idle=idle_hook(ident)
            except Exception:raise HTTPException(503,'Could not verify that the old worker stopped; retry later.')
            if not idle:raise HTTPException(409,'The previous worker has not stopped yet. Retry later.')
        return record

    @application.post('/v1/jobs/{ident}/recover',dependencies=[Depends(auth)])
    def recover(ident:str):
        with jobs.lock:
            stopped_record(ident)
            if recovery_hook:return recovery_hook(ident)
            from .recovery import recover_saved
            return recover_saved(jobs,ident)

    @application.post('/v1/jobs/{ident}/resume',status_code=202,dependencies=[Depends(auth)])
    def resume(ident:str,checkpoint_step:int=-1):
        with jobs.lock:
            stopped_record(ident)
            if recovery_hook:record=recovery_hook(ident)
            else:
                from .recovery import recover_saved
                record=recover_saved(jobs,ident)
            if checkpoint_step < -1 or checkpoint_step>=1_000_000:raise HTTPException(400,'Invalid checkpoint expectation')
            if checkpoint_step>=0 and record.get('recovery',{}).get('checkpoint_step',-1)<checkpoint_step:raise HTTPException(409,'The expected saved checkpoint is no longer available. Inspect saved files again before continuing; no fresh training was started.')
            if not record.get('recovery',{}).get('resume_ready'):raise HTTPException(409,'No resumable saved splat work was found. Recover existing outputs first; a new reconstruction is a separate action.')
            have=available if available is not None else capabilities()
            if not have.get('splat'):raise HTTPException(503,'Splat training is not installed on this server; saved exports remain recoverable.')
            if sum(r['phase'] not in TERMINAL for r in jobs.records())>=16:raise HTTPException(429,'Server queue is full')
            record=jobs.update(ident,phase='queued',stage='queued',message='Waiting for a GPU worker to continue saved training',resume_requested=True,resume_min_checkpoint_step=checkpoint_step,budget_paused=False,worker_started=0,worker_heartbeat=0,worker_deadline=0,engine='',engine_elapsed=0,attempt=record.get('attempt',1)+1)
            try:dispatch(ident)
            except Exception:
                jobs.update(ident,phase='failed',message='Could not start continued training; saved files are kept.')
                raise HTTPException(503,'Could not start continued training; saved files are kept.')
            return jobs.get(ident)

    @application.get("/v1/jobs/{ident}/artifacts/{kind}", dependencies=[Depends(auth)])
    def artifact(ident: str, kind: str):
        record = get(ident)
        if kind not in ARTIFACTS or kind not in record["artifacts"] or record["phase"] != "complete": raise HTTPException(404, "Completed artifact not found")
        file = artifact_hook(ident, kind) if artifact_hook else jobs.directory(ident) / "artifacts" / ARTIFACTS[kind]
        if not file.is_file(): raise HTTPException(404, "Artifact is unavailable")
        return FileResponse(file, filename=ARTIFACTS[kind], media_type="model/gltf-binary" if kind == "glb" else "application/octet-stream")

    return application


def load_key(root: Path) -> str:
    configured = os.environ.get("STRATA_API_KEY")
    if configured: return configured
    file = root / "server.key"
    if not file.exists():
        root.mkdir(parents=True, exist_ok=True)
        fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as out: out.write(secrets.token_urlsafe(32))
    return file.read_text().strip()
