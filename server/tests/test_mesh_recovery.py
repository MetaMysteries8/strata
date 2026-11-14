import json,struct,uuid
from pathlib import Path

import numpy as np
import pytest
import trimesh
from fastapi.testclient import TestClient

from strata_server import pipeline
from strata_server.api import create_app
from strata_server.jobs import FileJobs


def write_splat(path):
    names=['x','y','z','f_dc_0','f_dc_1','f_dc_2','opacity','scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3']
    with path.open('wb') as f:
        f.write(('ply\nformat binary_little_endian 1.0\nelement vertex 161\n'+''.join('property float '+n+'\n' for n in names)+'end_header\n').encode())
        for i in range(161):f.write(struct.pack('<14f',i*.01,0,0,0,0,0,-2,-2,-2,-2,1,0,0,0))


def fixture(tmp_path,monkeypatch,*,mode='both',mesh='empty',alignment=True,splat=True,cancel=False):
    jobs=FileJobs(tmp_path);ident=str(uuid.uuid4())
    jobs.put(dict(id=ident,mode=mode,preset='detail',phase='queued',created=0,artifacts=[]))
    directory=jobs.directory(ident);data=directory/'dataset';data.mkdir();(data/'image.jpg').write_bytes(b'fixture')
    (data/'transforms.json').write_text(json.dumps(dict(frames=[dict(file_path='image.jpg')],original=True)))
    monkeypatch.setattr(pipeline,'capabilities',lambda:dict(mesh=True,splat=True))
    commands=[]
    class Runner:
        def __init__(self,*a):pass
        def run(self,args):
            args=list(map(str,args));commands.append(args)
            def flag(name):return Path(args[args.index(name)+1])
            if args[0]=='ns-process-data':
                if not alignment:raise RuntimeError('Alignment failed')
                aligned=flag('--output-dir');(aligned/'colmap/sparse/0').mkdir(parents=True)
                (aligned/'transforms.json').write_text(json.dumps(dict(frames=[],aligned=True)))
            elif args[0]=='ns-train':
                if not splat:raise RuntimeError('Training failed')
                out=flag('--output-dir');out.mkdir();(out/'config.yml').write_text('fixture')
            elif args[0]=='ns-export':
                out=flag('--output-dir');out.mkdir();write_splat(out/'scene.ply')
            elif args[1]=='image_undistorter':flag('--output_path').mkdir()
            elif args[1]=='stereo_fusion':
                cloud=trimesh.points.PointCloud(trimesh.creation.icosphere(subdivisions=1).vertices)
                cloud.export(flag('--output_path'))
            elif args[1] in {'poisson_mesher','delaunay_mesher'}:
                good=mesh=='poisson' or mesh=='delaunay' and args[1]=='delaunay_mesher'
                value=trimesh.creation.icosphere(subdivisions=1) if good else trimesh.points.PointCloud([[0,0,0],[1,0,0],[0,1,0]])
                value.export(flag('--output_path'))
                if cancel:jobs.cancel(ident)
    pipeline.run_job(jobs,ident,Runner)
    return jobs,ident,commands


def test_empty_poisson_surface_falls_back_to_real_delaunay_triangles(tmp_path,monkeypatch):
    jobs,ident,commands=fixture(tmp_path,monkeypatch,mesh='delaunay')
    result=jobs.get(ident)
    assert result['phase']=='complete' and not result['partial']
    assert result['metrics']['mesh']['method']=='delaunay' and result['metrics']['mesh']['faces']>0
    command=next(c for c in commands if c[:2]==['colmap','delaunay_mesher'])
    assert Path(command[command.index('--input_path')+1])==jobs.directory(ident)/'dense'
    assert len(trimesh.load(jobs.directory(ident)/'artifacts/mesh.glb',force='mesh').faces)>0


def test_mesh_failure_does_not_discard_requested_splats_and_available_points_are_downloadable(tmp_path,monkeypatch):
    jobs,ident,commands=fixture(tmp_path,monkeypatch)
    result=jobs.get(ident)
    assert result['phase']=='complete' and result['partial']
    assert result['outcomes']['mesh']['status']=='failed' and result['outcomes']['splat']['status']=='complete'
    assert set(result['artifacts'])=={'splat','points'} and result['metrics']['dense_cloud']['points']==42
    assert '0 triangles' in result['message']
    key='fixture-server-pairing-key-at-least-24'
    client=TestClient(create_app(tmp_path,key,jobs=jobs,dispatch=lambda *a:None),headers={'X-Strata-Key':key})
    assert client.get(f'/v1/jobs/{ident}/artifacts/splat').status_code==200
    assert client.get(f'/v1/jobs/{ident}/artifacts/points').status_code==200
    assert client.get(f'/v1/jobs/{ident}/artifacts/glb').status_code==404


def test_mesh_only_failure_retains_dense_points_without_claiming_mesh_success(tmp_path,monkeypatch):
    jobs,ident,commands=fixture(tmp_path,monkeypatch,mode='mesh')
    result=jobs.get(ident)
    assert result['partial'] and result['artifacts']==['points'] and result['outcomes']['mesh']['status']=='failed'
    assert not any(c[0]=='ns-train' for c in commands)


def test_successful_mesh_survives_splat_training_failure(tmp_path,monkeypatch):
    jobs,ident,_=fixture(tmp_path,monkeypatch,mesh='poisson',splat=False)
    result=jobs.get(ident)
    assert result['phase']=='complete' and result['partial'] and 'glb' in result['artifacts']
    assert 'splat' not in result['artifacts'] and result['outcomes']['splat']['status']=='failed'


def test_alignment_failure_uses_original_posed_capture_for_requested_splats(tmp_path,monkeypatch):
    jobs,ident,commands=fixture(tmp_path,monkeypatch,alignment=False)
    result=jobs.get(ident)
    assert result['phase']=='complete' and result['partial'] and result['artifacts']==['splat']
    original=json.loads((jobs.directory(ident)/'splat-input/transforms.json').read_text())
    assert original['original']


def test_no_successful_outputs_still_fail_truthfully(tmp_path,monkeypatch):
    jobs,ident,_=fixture(tmp_path,monkeypatch,alignment=False,splat=False)
    assert jobs.get(ident)['phase']=='failed' and jobs.get(ident)['artifacts']==[]


def test_cancellation_never_triggers_a_fallback_or_splat_training(tmp_path,monkeypatch):
    jobs,ident,commands=fixture(tmp_path,monkeypatch,cancel=True)
    assert jobs.get(ident)['phase']=='cancelled'
    assert not any(c[0]=='ns-train' or c[:2]==['colmap','delaunay_mesher'] for c in commands)


def test_dense_preflight_rejects_empty_cloud_and_surface_validation_rejects_degenerate_faces(tmp_path):
    cloud=tmp_path/'empty.ply';cloud.write_text('ply\nformat ascii 1.0\nelement vertex 0\nproperty float x\nproperty float y\nproperty float z\nend_header\n')
    with pytest.raises(pipeline.MeshReconstructionError,match='only 0 points'):pipeline.dense_cloud_info(cloud)
    mesh=tmp_path/'degenerate.ply';trimesh.Trimesh(vertices=[[0,0,0],[1,0,0],[2,0,0]],faces=[[0,1,2]],process=False).export(mesh)
    with pytest.raises(pipeline.MeshReconstructionError,match='degenerate'):pipeline.triangle_mesh(mesh)
