from __future__ import annotations

import json
import math
import os
import stat
import struct
import uuid
import zipfile
from pathlib import Path, PurePosixPath

from PIL import Image

MAX_UPLOAD = 600 * 1024**2
MAX_EXPANDED = 2 * 1024**3
ARTIFACTS = {"splat": "scene.ply", "glb": "mesh.glb", "obj": "mesh.obj", "stl": "mesh.stl", "points": "points.ply"}


def job_id(value: str) -> str:
    if str(uuid.UUID(value)) != value:
        raise ValueError("Invalid job ID")
    return value


def atomic_json(path: Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    with temp.open("w", encoding="utf-8") as out:
        json.dump(data, out, allow_nan=False)
        out.flush()
        os.fsync(out.fileno())
    temp.replace(path)


def extract_dataset(archive: Path, target: Path) -> dict:
    """Validate before extracting. Only referenced images and optional seed points are copied."""
    if archive.stat().st_size > MAX_UPLOAD:
        raise ValueError("Dataset exceeds 600 MiB")
    with zipfile.ZipFile(archive) as z:
        members = z.infolist()
        if len(members) > 2500 or sum(m.file_size for m in members) > MAX_EXPANDED:
            raise ValueError("ZIP expands beyond the dataset limit")
        by_name = {}
        for m in members:
            path = PurePosixPath(m.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in m.filename or ":" in m.filename or stat.S_ISLNK(m.external_attr >> 16):
                raise ValueError("Unsafe archive path")
            name = str(path)
            if name in by_name:
                raise ValueError("Duplicate normalized ZIP path")
            by_name[name] = m
        manifest = by_name.get("transforms.json")
        if not manifest or manifest.file_size > 8_000_000:
            raise ValueError("ZIP needs transforms.json at its root")
        data = json.loads(z.read(manifest), parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON number")))
        frames = data.get("frames", [])
        if not isinstance(frames, list) or not 12 <= len(frames) <= 600:
            raise ValueError("Use 12–600 posed images")
        wanted = set()
        for frame in frames:
            name = frame.get("file_path", "").removeprefix("./")
            if name not in by_name or PurePosixPath(name).suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
                raise ValueError("Missing or unsupported source image")
            frame["file_path"] = name
            matrix = frame.get("transform_matrix", [])
            if len(matrix) != 4 or any(len(row) != 4 or any(not isinstance(v, (int, float)) or not math.isfinite(v) for v in row) for row in matrix):
                raise ValueError("Invalid 4×4 camera-to-world matrix")
            for key in ("w", "h", "fl_x", "fl_y"):
                value = frame.get(key, data.get(key, 0))
                if not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
                    raise ValueError("Missing or invalid image calibration")
            if max(frame.get("w", data.get("w")), frame.get("h", data.get("h"))) > 12000:
                raise ValueError("Source image exceeds 12,000 pixels")
            wanted.add(name)
            for field in ("depth_file_path", "depth_confidence_file_path"):
                extra = frame.get(field)
                if extra:
                    extra = extra.removeprefix("./")
                    if extra not in by_name or not extra.endswith(".png"): raise ValueError("Missing depth image")
                    frame[field] = extra
                    wanted.add(extra)
        seed = data.get("ply_file_path")
        if seed:
            seed = seed.removeprefix("./")
            if seed not in by_name or not seed.endswith(".ply"):
                raise ValueError("Missing seed point cloud")
            data["ply_file_path"] = seed
            wanted.add(seed)
        target.mkdir(parents=True, exist_ok=True)
        for name in wanted:
            destination = target / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with z.open(by_name[name]) as source, destination.open("wb") as out:
                import shutil
                shutil.copyfileobj(source, out, 1024 * 1024)
        # Decode headers to reject mislabeled image bytes without loading all pixels into RAM.
        for frame in frames:
            with Image.open(target / frame["file_path"]) as image:
                w, h = image.size
                if (w, h) != (frame.get("w", data.get("w")), frame.get("h", data.get("h"))):
                    raise ValueError("Image dimensions do not match camera calibration")
                image.verify()
        atomic_json(target / "transforms.json", data)
    return {"frames": len(frames), "images": len({f["file_path"] for f in frames})}


def inspect_ply(path: Path, require_splat=False) -> dict:
    """Inspect the schema and fixed-width payload, not just the .ply filename."""
    sizes = {"char": 1, "uchar": 1, "int8": 1, "uint8": 1, "short": 2, "ushort": 2, "int16": 2, "uint16": 2, "int": 4, "uint": 4, "int32": 4, "uint32": 4, "float": 4, "float32": 4, "double": 8, "float64": 8}
    with path.open("rb") as f:
        if f.readline().strip() != b"ply":
            raise ValueError("Not a PLY file: missing ply header")
        properties, vertices, faces, fmt, current = [], 0, 0, "", ""
        while f.tell() < 1024 * 1024:
            raw = f.readline()
            if not raw:
                raise ValueError("Truncated PLY header")
            parts = raw.decode("ascii", errors="strict").strip().split()
            if not parts:
                continue
            if parts[0] == "format": fmt = parts[1]
            if parts[0] == "element":
                current = parts[1]
                count = int(parts[2])
                if count < 0: raise ValueError("Negative PLY element count")
                if current == "vertex": vertices = count
                if current == "face": faces = count
            if parts[0] == "property" and current == "vertex":
                if len(parts) != 3 or parts[1] not in sizes:
                    raise ValueError("Unsupported vertex property layout")
                properties.append((parts[2], sizes[parts[1]]))
            if parts[0] == "end_header": break
        else: raise ValueError("PLY header is too large")
        names = {name for name, _ in properties}
        if not 0 < vertices <= 10_000_000 or not {"x", "y", "z"} <= names or len(names)!=len(properties):
            raise ValueError("PLY has no valid vertices")
        splat = {"opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"} <= names
        if require_splat and not splat:
            raise ValueError("PLY is a point cloud or mesh, not a Gaussian splat")
        if fmt in {"binary_little_endian", "binary_big_endian"}:
            if path.stat().st_size - f.tell() < vertices * sum(size for _, size in properties):
                raise ValueError("Truncated PLY vertex payload")
            if splat:
                import numpy as np
                scalar = {"char":"i1","int8":"i1","uchar":"u1","uint8":"u1","short":"i2","int16":"i2","ushort":"u2","uint16":"u2","int":"i4","int32":"i4","uint":"u4","uint32":"u4","float":"f4","float32":"f4","double":"f8","float64":"f8"}
                # Re-read property type names; offsets are preserved in the packed structured dtype.
                f.seek(0); types=[]; current=""
                while True:
                    parts=f.readline().decode("ascii").strip().split()
                    if not parts: continue
                    if parts[0]=="element": current=parts[1]
                    if parts[0]=="property" and current=="vertex": types.append((parts[2], ("<" if fmt=="binary_little_endian" else ">")+scalar[parts[1]]))
                    if parts[0]=="end_header": break
                data=np.memmap(path, dtype=np.dtype(types), mode="r", offset=f.tell(), shape=(vertices,))
                bad=0; lo=np.full(3,np.inf);hi=np.full(3,-np.inf)
                for start in range(0,vertices,65536):
                    block=data[start:start+65536];valid=np.ones(len(block),dtype=bool)
                    for name in data.dtype.names: valid &= np.isfinite(block[name])
                    norm=sum(block[f"rot_{i}"].astype(np.float64)**2 for i in range(4))
                    valid &= (norm>1e-12) & np.isfinite(norm);bad+=int((~valid).sum())
                    if valid.any():
                        for axis,name in enumerate(("x","y","z")):
                            lo[axis]=min(lo[axis],float(block[name][valid].min()));hi[axis]=max(hi[axis],float(block[name][valid].max()))
                del data
                if bad: raise ValueError(f"Invalid Gaussian export: {bad}/{vertices} vertices contain non-finite parameters or zero rotations")
                if float((hi-lo).max())<=1e-6: raise ValueError("Gaussian positions collapse to one point")
        elif fmt == "ascii":
            indices={name:i for i,(name,_) in enumerate(properties)}
            lo=[math.inf]*3;hi=[-math.inf]*3
            for _ in range(vertices):
                line = f.readline().split()
                if len(line) < len(properties): raise ValueError("Truncated ASCII PLY payload")
                values=[float(v) for v in line[:len(properties)]]
                if any(not math.isfinite(v) for v in values): raise ValueError("Non-finite PLY vertex")
                if splat:
                    norm=sum(values[indices[f"rot_{i}"]]**2 for i in range(4))
                    if not math.isfinite(norm) or norm<=1e-12: raise ValueError("Gaussian has zero or invalid rotation")
                    for axis,name in enumerate(("x","y","z")):
                        lo[axis]=min(lo[axis],values[indices[name]]);hi[axis]=max(hi[axis],values[indices[name]])
            if splat and max(b-a for a,b in zip(lo,hi))<=1e-6: raise ValueError("Gaussian positions collapse to one point")
        else: raise ValueError("Unsupported PLY encoding")
    return {"vertices": vertices, "faces": faces, "format": fmt, "kind": "splat" if splat else "mesh" if faces else "points"}
