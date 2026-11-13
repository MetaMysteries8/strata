from __future__ import annotations
import os
import json
import queue
import shutil
import signal
import subprocess
import threading
import time
from collections import deque
from pathlib import Path
from .data import ARTIFACTS, inspect_ply


class Cancelled(Exception): pass


class ProcessRunner:
    """Bounded, cancellable process execution without invoking a shell."""
    def __init__(self, cancelled, log: Path, timeout=7200, heartbeat_interval=30, on_heartbeat=None):
        self.cancelled, self.log, self.timeout = cancelled, log, timeout
        self.heartbeat_interval=heartbeat_interval
        self.on_heartbeat=on_heartbeat

    def run(self, args):
        if self.cancelled(): raise Cancelled()
        env = {**os.environ, "QT_QPA_PLATFORM": "offscreen", "PYTHONUNBUFFERED": "1", "COLUMNS": "120"}
        process = subprocess.Popen([str(a) for a in args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", env=env, start_new_session=os.name != "nt")
        output = queue.Queue()
        def read():
            for line in process.stdout: output.put(line)
            output.put(None)
        threading.Thread(target=read, daemon=True).start()
        start, lines = time.monotonic(), deque(maxlen=20)
        print(f"[Strata] Starting {Path(str(args[0])).name}", flush=True)
        def report(elapsed):
            if self.on_heartbeat:
                try:self.on_heartbeat(Path(str(args[0])).name,elapsed)
                except Exception:print('[Strata] Worker heartbeat could not be published; engine continues.',flush=True)
        try:
            report(0)
            with self.log.open("ab") as out:
                remaining=max(0,8_000_000-out.tell())
                def append(text):
                    nonlocal remaining
                    if remaining<=0:return
                    data=text.encode('utf-8',errors='replace')[:remaining]
                    if data:out.write(data);remaining-=len(data)
                append("\n$ " + " ".join(str(a) for a in args) + "\n")
                last_flush=last_console=start
                last_emitted=None
                next_heartbeat=start+self.heartbeat_interval
                done = False
                while not done or process.poll() is None:
                    if self.cancelled(): raise Cancelled()
                    if time.monotonic() - start > self.timeout: raise TimeoutError("Reconstruction stage exceeded its time limit")
                    now=time.monotonic()
                    if now>=next_heartbeat:
                        print(f"[Strata] {Path(str(args[0])).name} process still running; {int(now-start)}s elapsed (not a completion estimate)",flush=True)
                        report(int(now-start))
                        next_heartbeat=now+self.heartbeat_interval
                    try: line = output.get(timeout=.2)
                    except queue.Empty: continue
                    if line is None: done = True; continue
                    append(line)
                    lines.append(line.rstrip())
                    now=time.monotonic()
                    if now-last_flush>=1:out.flush();last_flush=now
                    if now-last_console>=1:
                        print('[Strata] '+line.rstrip(),flush=True)
                        last_console=now;last_emitted=line.rstrip()
                if lines and lines[-1]!=last_emitted:print('[Strata] '+lines[-1],flush=True)
            if process.returncode:
                raise RuntimeError(f"{Path(str(args[0])).name} failed ({process.returncode}): " + "\n".join(lines)[-2200:])
            report(int(time.monotonic()-start))
            print(f"[Strata] Finished {Path(str(args[0])).name}",flush=True)
        finally:
            if process.poll() is None:
                if os.name == "nt": process.terminate()
                else: os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    if os.name == "nt": process.kill()
                    else: os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            process.stdout.close()


def capabilities():
    have = {name: bool(shutil.which(name)) for name in ("ns-train", "ns-export", "ns-process-data", "colmap")}
    return {"splat": have["ns-train"] and have["ns-export"], "mesh": have["ns-process-data"] and have["colmap"], "tools": have}


def train_command(data: Path, output: Path, preset: str):
    steps = {"quick": 7000, "detail": 30000, "max": 50000}[preset]
    method = "splatfacto-big" if preset == "max" else "splatfacto"
    # Flags checked against the pinned 1.1.5 source, not a different docs version.
    return ["ns-train", method, "--data", str(data), "--output-dir", str(output), "--experiment-name", "strata", "--max-num-iterations", str(steps), "--vis", "tensorboard", "--pipeline.model.cull-alpha-thresh", "0.005", "--pipeline.model.use-scale-regularization", "True", "--pipeline.model.camera-optimizer.mode", "SO3xR3"]


def prepare_splat_input(source: Path, target: Path):
    """Keep original raw depth, but do not misrepresent its calibration as RGB-aligned depth supervision."""
    manifest=json.loads((source / "transforms.json").read_text())
    for frame in manifest["frames"]:
        frame.pop("depth_file_path",None)
        frame.pop("depth_confidence_file_path",None)
    target.mkdir(parents=True,exist_ok=True)
    for child in source.iterdir():
        if child.name=="transforms.json":continue
        dest=target / child.name
        if dest.exists() or dest.is_symlink():continue
        if os.name!="nt":dest.symlink_to(child.resolve(),target_is_directory=child.is_dir())
        elif child.is_dir():shutil.copytree(child,dest)
        else:shutil.copyfile(child,dest)
    from .data import atomic_json
    atomic_json(target / "transforms.json",manifest)
    return target


def convert_mesh(mesh_path: Path, cloud_path: Path, output: Path):
    import numpy as np
    import trimesh
    from scipy.spatial import cKDTree
    mesh = trimesh.load(mesh_path, force="mesh", process=False)
    if not isinstance(mesh, trimesh.Trimesh) or len(mesh.faces) == 0: raise ValueError("Reconstruction did not produce a triangle mesh")
    mesh.remove_unreferenced_vertices()
    cloud = trimesh.load(cloud_path, process=False)
    colors = getattr(getattr(cloud, "visual", None), "vertex_colors", None)
    if colors is not None and len(colors) == len(cloud.vertices):
        _, ids = cKDTree(np.asarray(cloud.vertices)).query(mesh.vertices, workers=2)
        mesh.visual.vertex_colors = np.asarray(colors)[ids]
    output.mkdir(parents=True, exist_ok=True)
    mesh.export(output / ARTIFACTS["glb"])
    # Vertex-colored OBJ is supported by Blender and many tools; STL stores geometry only.
    mesh.export(output / ARTIFACTS["obj"])
    mesh.export(output / ARTIFACTS["stl"])
    return {"vertices": len(mesh.vertices), "faces": len(mesh.faces), "color": "vertex colors; no texture atlas"}


def run_job(jobs, ident: str, runner_factory=ProcessRunner):
    record = jobs.get(ident)
    directory = jobs.directory(ident)
    output = directory / "artifacts"; output.mkdir(exist_ok=True)
    runner = runner_factory(lambda: jobs.cancelled(ident), directory / "engine.log")
    runner.on_heartbeat=lambda engine,elapsed: jobs.update(ident,worker_heartbeat=time.time(),engine=engine,engine_elapsed=elapsed)
    mode, preset = record["mode"], record["preset"]
    dataset = directory / "dataset"
    artifacts, metrics = [], {}
    def stage(name, message):
        if jobs.cancelled(ident): raise Cancelled()
        jobs.update(ident, phase="running", stage=name, stage_started=time.time(), worker_heartbeat=time.time(), engine="", engine_elapsed=0, message=message)
        print(f"[Strata {ident}] {name}: {message}",flush=True)
    try:
        if mode in {"mesh", "both"}:
            if not capabilities()["mesh"]: raise RuntimeError("Install Nerfstudio and CUDA-enabled COLMAP to generate meshes")
            stage("alignment", "Matching photos and refining cameras with COLMAP")
            aligned = directory / "aligned"
            source_photos=directory / "sfm-images";source_photos.mkdir(exist_ok=True)
            manifest=json.loads((dataset / "transforms.json").read_text())
            for i,frame in enumerate(manifest["frames"]):
                source=dataset / frame["file_path"]
                shutil.copyfile(source,source_photos / f"frame_{i:06d}{source.suffix.lower()}")
            # Exhaustive matching avoids a vocabulary-tree network download.
            runner.run(["ns-process-data", "images", "--data", source_photos, "--output-dir", aligned, "--sfm-tool", "colmap", "--matching-method", "exhaustive", "--num-downscales", "0"])
            sparse = aligned / "colmap/sparse/0"
            if not sparse.exists(): raise RuntimeError("COLMAP did not register a sparse model")
            dense = directory / "dense"
            stage("undistorting", "Preparing calibrated images for dense depth estimation")
            runner.run(["colmap", "image_undistorter", "--image_path", aligned / "images", "--input_path", sparse, "--output_path", dense, "--output_type", "COLMAP", "--max_image_size", str({"quick": 1400, "detail": 2200, "max": 3200}[preset])])
            stage("depth", "Computing multi-view stereo depth maps on the GPU")
            runner.run(["colmap", "patch_match_stereo", "--workspace_path", dense, "--workspace_format", "COLMAP", "--PatchMatchStereo.geom_consistency", "true"])
            stage("fusion", "Fusing depth maps into a dense colored point cloud")
            cloud = dense / "fused.ply"
            runner.run(["colmap", "stereo_fusion", "--workspace_path", dense, "--workspace_format", "COLMAP", "--input_type", "geometric", "--output_path", cloud])
            stage("meshing", "Reconstructing a triangle surface and transferring vertex colors")
            mesh = dense / "mesh.ply"
            runner.run(["colmap", "poisson_mesher", "--input_path", cloud, "--output_path", mesh])
            metrics["mesh"] = convert_mesh(mesh, cloud, output)
            shutil.copyfile(cloud, output / ARTIFACTS["points"])
            artifacts += ["glb", "obj", "stl", "points"]
            dataset = aligned
        if mode in {"splat", "both"}:
            if not capabilities()["splat"]: raise RuntimeError("Install Nerfstudio with gsplat/CUDA to train server splats")
            stage("training", "Optimizing Gaussian splats with Splatfacto; see server log for actual iterations")
            trained = directory / "trained"
            runner.run(train_command(prepare_splat_input(dataset,directory / "splat-input"), trained, preset))
            configs = list(trained.rglob("config.yml"))
            if not configs: raise RuntimeError("Training produced no Nerfstudio checkpoint configuration")
            stage("exporting", "Exporting and validating the Gaussian PLY")
            exports = directory / "exports"
            runner.run(["ns-export", "gaussian-splat", "--load-config", max(configs, key=lambda p: p.stat().st_mtime), "--output-dir", exports])
            files = list(exports.rglob("*.ply"))
            if not files: raise RuntimeError("No PLY was exported")
            source = max(files, key=lambda p: p.stat().st_mtime)
            metrics["splat"] = inspect_ply(source, require_splat=True)
            shutil.copyfile(source, output / ARTIFACTS["splat"])
            artifacts.append("splat")
        if jobs.cancelled(ident): raise Cancelled()
        jobs.update(ident, phase="complete", stage="complete", message="Reconstruction complete", artifacts=artifacts, metrics=metrics)
        print(f"[Strata {ident}] Reconstruction complete; {len(artifacts)} artifacts published",flush=True)
    except Cancelled:
        jobs.cancel(ident)
        print(f"[Strata {ident}] Cancelled",flush=True)
    except Exception as exc:
        jobs.update(ident, phase="failed", stage="failed", message=str(exc)[-2600:])
        print(f"[Strata {ident}] Reconstruction failed: {str(exc)[-2600:]}",flush=True)
