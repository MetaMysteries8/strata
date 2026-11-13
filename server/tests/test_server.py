import io
import json
import struct
import sys
import threading
import time
import uuid
import zipfile
from pathlib import Path

import numpy as np
import pytest
import trimesh
from fastapi.testclient import TestClient
from PIL import Image
from strata_server.api import create_app
from strata_server.data import extract_dataset, inspect_ply
from strata_server.jobs import FileJobs
from strata_server.pipeline import Cancelled, ProcessRunner, convert_mesh, train_command, run_job, prepare_splat_input

KEY="test-strata-key-not-a-real-credential"


def dataset(extra=None, missing=False):
    image=io.BytesIO();Image.new("RGB",(8,8),(90,150,60)).save(image,format="JPEG")
    frames=[{"file_path":f"images/{i}.jpg","transform_matrix":[[1,0,0,i*.1],[0,1,0,0],[0,0,1,0],[0,0,0,1]]} for i in range(12)]
    data={"w":8,"h":8,"fl_x":6,"fl_y":6,"frames":frames}
    blob=io.BytesIO()
    with zipfile.ZipFile(blob,"w") as z:
        z.writestr("transforms.json",json.dumps(data))
        for i in range(11 if missing else 12):z.writestr(f"images/{i}.jpg",image.getvalue())
        if extra:z.writestr(extra,b"bad")
    return blob.getvalue()


def client(tmp_path, scheduled=None):
    app=create_app(tmp_path,KEY,dispatch=(scheduled if scheduled is not None else []).append,available={"splat":True,"mesh":True})
    return app,TestClient(app,headers={"X-Strata-Key":KEY})


def test_auth_is_required_before_upload(tmp_path):
    app,c=client(tmp_path)
    assert TestClient(app).get("/v1/health").status_code==401
    assert TestClient(app).post("/v1/jobs",content=b"not a zip").status_code==401
    assert not list(tmp_path.glob("*/dataset.zip"))
    assert c.get("/v1/health").json()["service"]=="strata"


def test_upload_persists_and_idempotency_does_not_schedule_twice(tmp_path):
    scheduled=[];app,c=client(tmp_path,scheduled);ident=str(uuid.uuid4())
    response=c.post("/v1/jobs?mode=both&preset=detail",content=dataset(),headers={"X-Request-ID":ident})
    assert response.status_code==202,response.text
    assert response.json()["phase"]=="queued" and scheduled==[ident]
    assert c.get(f"/v1/jobs/{ident}").json()["frames"]==12
    assert (tmp_path/ident/"dataset/images/0.jpg").exists()
    repeat=c.post("/v1/jobs?mode=both&preset=detail",content=dataset(),headers={"X-Request-ID":ident})
    assert repeat.status_code==202 and scheduled==[ident]
    assert c.post("/v1/jobs?mode=mesh",content=dataset(),headers={"X-Request-ID":ident}).status_code==409


@pytest.mark.parametrize("extra,missing",[("../escape",False),("/escape",False),("images\\escape",False),(None,True)])
def test_rejects_unsafe_or_incomplete_archive(tmp_path,extra,missing):
    _,c=client(tmp_path)
    r=c.post("/v1/jobs",content=dataset(extra,missing));assert r.status_code==400
    assert not (tmp_path.parent/"escape").exists()


def test_cancel_is_durable_and_blocks_artifact_access(tmp_path):
    app,c=client(tmp_path);ident=c.post("/v1/jobs",content=dataset()).json()["id"]
    assert c.post(f"/v1/jobs/{ident}/cancel").json()["phase"]=="cancelled"
    app.state.jobs.update(ident,phase="complete",artifacts=["glb"])
    assert c.get(f"/v1/jobs/{ident}").json()["phase"]=="cancelled"
    assert c.get(f"/v1/jobs/{ident}/artifacts/glb").status_code==404


def test_downloads_only_advertised_completed_artifacts(tmp_path):
    app,c=client(tmp_path);ident=c.post("/v1/jobs?mode=mesh",content=dataset()).json()["id"]
    out=tmp_path/ident/"artifacts";out.mkdir();(out/"mesh.glb").write_bytes(b"test-only-artifact")
    app.state.jobs.update(ident,phase="complete",artifacts=["glb"])
    assert c.get(f"/v1/jobs/{ident}/artifacts/glb").content==b"test-only-artifact"
    assert c.get(f"/v1/jobs/{ident}/artifacts/stl").status_code==404
    assert c.get("/v1/jobs/not-a-job").status_code==404


def test_cancellation_stops_a_silent_child_process(tmp_path):
    cancelled=threading.Event();timer=threading.Timer(.3,cancelled.set);timer.start();start=time.monotonic()
    try:
        with pytest.raises(Cancelled):ProcessRunner(cancelled.is_set,tmp_path/"engine.log").run([sys.executable,"-c","import time;time.sleep(60)"])
    finally:timer.cancel()
    assert time.monotonic()-start<6


def test_real_triangle_mesh_exports_are_valid(tmp_path):
    mesh=trimesh.creation.icosphere(subdivisions=1);source=tmp_path/"surface.ply";mesh.export(source)
    cloud=trimesh.points.PointCloud(mesh.vertices,colors=np.tile([220,80,60,255],(len(mesh.vertices),1)));points=tmp_path/"points.ply";cloud.export(points)
    metrics=convert_mesh(source,points,tmp_path/"artifacts")
    glb=trimesh.load(tmp_path/"artifacts/mesh.glb",force="mesh")
    stl=trimesh.load(tmp_path/"artifacts/mesh.stl",force="mesh")
    assert len(glb.faces)==len(mesh.faces)==metrics["faces"] and len(stl.faces)>0
    assert len(glb.visual.vertex_colors)==len(glb.vertices)


def splat(file,bad=False):
    names=["x","y","z","f_dc_0","f_dc_1","f_dc_2","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3"]
    with file.open("wb") as f:
        f.write(("ply\nformat binary_little_endian 1.0\nelement vertex 161\n"+"".join(f"property float {n}\n" for n in names)+"end_header\n").encode())
        for i in range(161):f.write(struct.pack("<14f",*(([0,0,0]+[float("nan")]*7+[0]*4) if bad else [i*.01,0,0]+[0]*3+[-2]*4+[1,0,0,0])))


def test_rejects_the_reported_nan_export(tmp_path):
    file=tmp_path/"broken.ply";splat(file,True)
    with pytest.raises(ValueError,match="161/161"):inspect_ply(file,True)
    splat(file);assert inspect_ply(file,True)["vertices"]==161
    file.write_bytes(file.read_bytes()[:-4])
    with pytest.raises(ValueError,match="Truncated"):inspect_ply(file,True)


def test_training_options_are_typed_and_missing_engine_fails_truthfully(tmp_path,monkeypatch):
    cmd=train_command(tmp_path/"a path",tmp_path/"out","max")
    assert cmd[1]=="splatfacto-big" and "50000" in cmd and str(tmp_path/"a path") in cmd
    jobs=FileJobs(tmp_path);ident=str(uuid.uuid4());jobs.put({"id":ident,"mode":"splat","preset":"quick","phase":"queued","created":0,"artifacts":[]})
    monkeypatch.setattr("strata_server.pipeline.capabilities",lambda:{"splat":False,"mesh":False})
    run_job(jobs,ident)
    assert jobs.get(ident)["phase"]=="failed" and not jobs.get(ident)["artifacts"]


def test_ascii_splats_reject_collapsed_positions_and_zero_rotations(tmp_path):
    file=tmp_path/"ascii.ply"
    names=["x","y","z","opacity","scale_0","scale_1","scale_2","rot_0","rot_1","rot_2","rot_3"]
    header="ply\nformat ascii 1.0\nelement vertex 2\n"+"".join(f"property float {n}\n" for n in names)+"end_header\n"
    file.write_text(header+"0 0 0 -2 -2 -2 -2 1 0 0 0\n0 0 0 -2 -2 -2 -2 1 0 0 0\n")
    with pytest.raises(ValueError,match="collapse"):inspect_ply(file,True)
    file.write_text(header+"0 0 0 -2 -2 -2 -2 0 0 0 0\n1 0 0 -2 -2 -2 -2 1 0 0 0\n")
    with pytest.raises(ValueError,match="rotation"):inspect_ply(file,True)


def test_partial_raw_depth_is_retained_without_mislabeling_training_supervision(tmp_path):
    source=tmp_path/"original";source.mkdir();(source/"images").mkdir();(source/"images/a.jpg").write_bytes(b"test")
    manifest={"frames":[{"file_path":"images/a.jpg","depth_file_path":"depth/a.png","depth_intrinsics":{"w":160}},{"file_path":"images/a.jpg"}]}
    (source/"transforms.json").write_text(json.dumps(manifest))
    target=prepare_splat_input(source,tmp_path/"training")
    assert "depth_file_path" not in json.loads((target/"transforms.json").read_text())["frames"][0]
    assert json.loads((source/"transforms.json").read_text())==manifest
    assert (target/"images/a.jpg").read_bytes()==b"test"
