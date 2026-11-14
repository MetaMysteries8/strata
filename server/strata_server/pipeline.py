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


class MeshReconstructionError(ValueError): pass


def dense_cloud_info(path: Path):
    import numpy as np
    from plyfile import PlyData
    cloud = PlyData.read(path)
    vertices = cloud["vertex"].data
    if len(vertices) < 3: raise MeshReconstructionError(f"Dense fusion produced only {len(vertices)} points; no surface can be reconstructed")
    if not all(name in vertices.dtype.names for name in ("x", "y", "z")): raise MeshReconstructionError("Dense cloud has no XYZ coordinates")
    if not all(np.isfinite(vertices[name]).all() for name in ("x", "y", "z")): raise MeshReconstructionError("Dense cloud contains invalid coordinates")
    normals = all(name in vertices.dtype.names for name in ("nx", "ny", "nz"))
    valid_normals = 0
    if normals:
        squared = sum(vertices[name].astype(np.float64)**2 for name in ("nx", "ny", "nz"))
        valid_normals = int(np.count_nonzero(np.isfinite(squared) & (squared > 0)))
    info = {"points": len(vertices), "valid_normals": valid_normals}
    print(f"[Strata] Dense fusion: {info['points']} points, {valid_normals} finite nonzero normals", flush=True)
    return info


def triangle_mesh(path: Path):
    import numpy as np
    import trimesh
    try: mesh = trimesh.load(path, force="mesh", process=False)
    except Exception as exc: raise MeshReconstructionError(f"Cannot read reconstructed mesh: {exc}") from exc
    if not isinstance(mesh, trimesh.Trimesh) or len(mesh.faces) == 0:
        raise MeshReconstructionError("Reconstruction did not produce a triangle mesh (0 triangles)")
    if not np.isfinite(mesh.vertices).all() or mesh.faces.min() < 0 or mesh.faces.max() >= len(mesh.vertices):
        raise MeshReconstructionError("Reconstructed mesh has invalid coordinates or face indices")
    mesh.update_faces(np.isfinite(mesh.area_faces) & (mesh.area_faces > 0))
    if len(mesh.faces) == 0: raise MeshReconstructionError("Reconstructed mesh contains only degenerate triangles")
    mesh.remove_unreferenced_vertices()
    return mesh


def reconstruct_surface(runner, dense: Path, output: Path, stage, info=None):
    cloud = dense / "fused.ply"
    info = info if info is not None else dense_cloud_info(cloud)
    failures = []
    # Delaunay takes the complete dense workspace, including visibility data,
    # rather than just the fused point PLY. Supported by COLMAP 3.9.1+.
    for method in ("poisson", "delaunay"):
        stage("meshing", "Reconstructing a triangle surface with " + method.capitalize())
        path = dense / ("mesh-" + method + ".ply")
        try:
            runner.run(["colmap", method + "_mesher", "--input_path", cloud if method == "poisson" else dense, "--output_path", path])
            result = convert_mesh(path, cloud, output)
            return {**result, "method": method, "dense_cloud": info, "fallback_reasons": failures}
        except (MeshReconstructionError, RuntimeError) as exc:
            failures.append(method + ": " + str(exc)[-800:])
            print("[Strata] Surface attempt failed: " + failures[-1], flush=True)
    raise MeshReconstructionError("No valid triangle surface after Poisson and Delaunay. " + f"Dense fusion: {info['points']} points, {info['valid_normals']} valid normals. " + " | ".join(failures))


def convert_mesh(mesh_path: Path, cloud_path: Path, output: Path):
    import numpy as np
    import trimesh
    from scipy.spatial import cKDTree
    mesh = triangle_mesh(mesh_path)
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
    artifacts, metrics, outcomes = [], {}, {}
    warnings = []
    def stage(name, message):
        if jobs.cancelled(ident): raise Cancelled()
        jobs.update(ident, phase="running", stage=name, stage_started=time.time(), worker_heartbeat=time.time(), engine="", engine_elapsed=0, message=message)
        print(f"[Strata {ident}] {name}: {message}",flush=True)
    try:
        if mode in {"mesh", "both"}:
            try:
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
                if not (aligned / "transforms.json").is_file(): raise RuntimeError("COLMAP did not produce calibrated camera transforms")
                dataset = aligned
                dense = directory / "dense"
                stage("undistorting", "Preparing calibrated images for dense depth estimation")
                runner.run(["colmap", "image_undistorter", "--image_path", aligned / "images", "--input_path", sparse, "--output_path", dense, "--output_type", "COLMAP", "--max_image_size", str({"quick": 1400, "detail": 2200, "max": 3200}[preset])])
                stage("depth", "Computing multi-view stereo depth maps on the GPU")
                runner.run(["colmap", "patch_match_stereo", "--workspace_path", dense, "--workspace_format", "COLMAP", "--PatchMatchStereo.geom_consistency", "true"])
                stage("fusion", "Fusing depth maps into a dense colored point cloud")
                cloud = dense / "fused.ply"
                runner.run(["colmap", "stereo_fusion", "--workspace_path", dense, "--workspace_format", "COLMAP", "--input_type", "geometric", "--output_path", cloud])
                # Retain independently useful dense points even if surface reconstruction fails.
                metrics["dense_cloud"] = dense_cloud_info(cloud)
                shutil.copyfile(cloud, output / ARTIFACTS["points"])
                artifacts.append("points")
                metrics["mesh"] = reconstruct_surface(runner, dense, output, stage, metrics["dense_cloud"])
                artifacts += ["glb", "obj", "stl"]
                outcomes["mesh"] = {"status": "complete"}
            except Cancelled: raise
            except Exception as exc:
                outcomes["mesh"] = {"status": "failed", "stage": jobs.get(ident).get("stage", "meshing"), "message": str(exc)[-1800:]}
                warnings.append("Mesh failed: " + str(exc)[-1800:])
                if mode != "both" and not artifacts: raise
                jobs.update(ident, outcomes=outcomes, warnings=warnings, message="Mesh failed; continuing requested splat training")
                print("[Strata] " + warnings[-1], flush=True)
        if mode in {"splat", "both"}:
            try:
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
                outcomes["splat"] = {"status": "complete"}
            except Cancelled: raise
            except Exception as exc:
                if mode != "both": raise
                outcomes["splat"] = {"status": "failed", "stage": jobs.get(ident).get("stage", "training"), "message": str(exc)[-1800:]}
                warnings.append("Splat failed: " + str(exc)[-1800:])
                print("[Strata] " + warnings[-1], flush=True)
        if jobs.cancelled(ident): raise Cancelled()
        if not artifacts: raise RuntimeError(" | ".join(warnings) or "Reconstruction produced no usable artifacts")
        partial = bool(warnings)
        message = "Partial reconstruction: " + "; ".join(warnings) if partial else "Reconstruction complete"
        jobs.update(ident, phase="complete", stage="complete", message=message, partial=partial, artifacts=artifacts, metrics=metrics, outcomes=outcomes, warnings=warnings)
        print(f"[Strata {ident}] Reconstruction complete; {len(artifacts)} artifacts published",flush=True)
    except Cancelled:
        jobs.cancel(ident)
        print(f"[Strata {ident}] Cancelled",flush=True)
    except Exception as exc:
        jobs.update(ident, phase="failed", stage="failed", message=str(exc)[-2600:], artifacts=artifacts, metrics=metrics, outcomes=outcomes, warnings=warnings)
        print(f"[Strata {ident}] Reconstruction failed: {str(exc)[-2600:]}",flush=True)
