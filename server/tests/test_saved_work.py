import json,sys,time,uuid,zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest
import trimesh
from fastapi.testclient import TestClient
from strata_server.api import create_app
from strata_server.jobs import FileJobs
from strata_server import recovery,pipeline
from strata_server.budget import BudgetExpired,SOFT_SECONDS,WORKER_SECONDS
from test_mesh_recovery import write_splat

KEY='test-saved-work-server-key-at-least-24'


def saved_job(tmp_path,*,mesh=True,checkpoint=True):
    jobs=FileJobs(tmp_path);ident=str(uuid.uuid4())
    jobs.put(dict(id=ident,mode='both',preset='detail',phase='failed',created=0,artifacts=[],message='worker hit timeout of 7200s'))
    directory=jobs.directory(ident)
    data=directory/'splat-input';data.mkdir();(data/'image.jpg').write_bytes(b'fixture')
    (data/'transforms.json').write_text(json.dumps(dict(frames=[dict(file_path='image.jpg')])))
    output=directory/'artifacts';output.mkdir()
    if mesh:
        surface=trimesh.creation.icosphere(subdivisions=1)
        for name in ['mesh.glb','mesh.obj','mesh.stl']:surface.export(output/name)
    if checkpoint:write_checkpoint(directory,9999)
    return jobs,ident,directory


def write_checkpoint(directory,step):
    run=directory/'trained/strata/splatfacto/test-time';run.mkdir(parents=True,exist_ok=True)
    (run/'config.yml').write_text('server-generated config fixture')
    folder=run/'nerfstudio_models';folder.mkdir(exist_ok=True)
    file=folder/f'step-{step:09d}.ckpt'
    with zipfile.ZipFile(file,'w') as z:z.writestr('archive/data.pkl',b'fixture - must never be deserialized by recovery')
    return file


def client(jobs,dispatch,**kwargs):
    app=create_app(jobs.root,KEY,jobs=jobs,dispatch=dispatch.append,available=dict(splat=True,mesh=True),**kwargs)
    return TestClient(app,headers={'X-Strata-Key':KEY})


def test_cpu_recovery_returns_valid_mesh_and_checkpoint_without_dispatching_a_gpu(tmp_path):
    jobs,ident,directory=saved_job(tmp_path);calls=[];c=client(jobs,calls)
    assert TestClient(c.app).post(f'/v1/jobs/{ident}/recover').status_code==401
    assert TestClient(c.app).post(f'/v1/jobs/{ident}/resume').status_code==401
    assert c.get(f'/v1/jobs/{ident}').json()['phase']=='failed' and not calls
    response=c.post(f'/v1/jobs/{ident}/recover');assert response.status_code==200
    record=response.json()
    assert record['phase']=='complete' and record['partial'] and record['recovery']['resume_ready']
    assert record['recovery']['checkpoint_step']==9999
    assert set(record['artifacts'])=={'glb','obj','stl'} and not calls
    assert c.get(f'/v1/jobs/{ident}/artifacts/glb').status_code==200
    assert '7200s' in record['failure_before_recovery']
    assert c.get(f'/v1/jobs/{ident}/artifacts/splat').status_code==404


@pytest.mark.parametrize('phase',['running','queued','cancelled'])
def test_recovery_and_continuation_never_touch_active_or_cancelled_jobs(tmp_path,phase):
    jobs,ident,_=saved_job(tmp_path);jobs.update(ident,phase=phase);calls=[];c=client(jobs,calls,recovery_hook=lambda _:pytest.fail('Must not inspect active/cancelled storage'))
    assert c.post(f'/v1/jobs/{ident}/recover').status_code==409
    assert c.post(f'/v1/jobs/{ident}/resume').status_code==409
    assert not calls


def test_pending_old_call_or_control_plane_failure_cannot_start_a_second_worker(tmp_path):
    jobs,ident,_=saved_job(tmp_path);calls=[];c=client(jobs,calls,idle_hook=lambda _:False)
    assert c.post(f'/v1/jobs/{ident}/recover').status_code==409
    assert c.post(f'/v1/jobs/{ident}/resume').status_code==409
    def unavailable(_):raise OSError('test control plane unavailable')
    c=client(jobs,calls,idle_hook=unavailable)
    assert c.post(f'/v1/jobs/{ident}/resume').status_code==503 and not calls


def test_explicit_resume_keeps_job_identity_and_never_uploads_or_dispatches_twice(tmp_path):
    jobs,ident,directory=saved_job(tmp_path);calls=[];c=client(jobs,calls)
    response=c.post(f'/v1/jobs/{ident}/resume?checkpoint_step=9999')
    assert response.status_code==202 and response.json()['id']==ident and calls==[ident]
    assert response.json()['resume_requested'] and response.json()['worker_started']==0 and response.json()['resume_min_checkpoint_step']==9999
    assert c.post(f'/v1/jobs/{ident}/resume').status_code==409 and calls==[ident]
    assert (directory/'artifacts/mesh.glb').exists()


def test_missing_and_truncated_work_does_not_trigger_a_fresh_reconstruction(tmp_path):
    jobs,ident,directory=saved_job(tmp_path,mesh=False,checkpoint=False);calls=[];c=client(jobs,calls)
    bad=write_checkpoint(directory,2000);bad.write_bytes(b'partial archive')
    recovered=c.post(f'/v1/jobs/{ident}/recover').json()
    assert recovered['phase']=='failed' and not recovered['recovery']['resume_ready']
    assert c.post(f'/v1/jobs/{ident}/resume').status_code==409 and not calls


def test_snapshot_survives_source_removal_and_ignores_a_newer_incomplete_checkpoint(tmp_path):
    jobs,ident,directory=saved_job(tmp_path)
    saved=recovery.snapshot_checkpoint(directory);assert saved['step']==9999
    original=next((directory/'trained').glob('**/*.ckpt'));original.unlink()
    bad=write_checkpoint(directory,20000);bad.write_bytes(b'truncated')
    chosen=recovery.checkpoint(directory)
    assert chosen['step']==9999 and chosen['path'].parent.name=='saved-training'
    for step in [11000,12000]:
        write_checkpoint(directory,step);recovery.snapshot_checkpoint(directory)
    recovery.prune_snapshots(directory)
    assert len(list((directory/'saved-training').glob('*.ckpt')))==2


def test_recovery_does_not_follow_paths_outside_the_job(tmp_path):
    jobs,ident,directory=saved_job(tmp_path,mesh=False,checkpoint=False)
    outside=tmp_path/'outside.glb';trimesh.creation.box().export(outside)
    (directory/'artifacts/mesh.glb').symlink_to(outside)
    saved=recovery.inspect_saved(directory,jobs.get(ident))
    assert saved['artifacts']==[] and not saved['resume_ready']


def test_soft_budget_stops_a_real_subprocess_before_the_provider_ceiling(tmp_path):
    runner=pipeline.ProcessRunner(lambda:False,tmp_path/'engine.log')
    runner.deadline=time.monotonic()+.2
    start=time.monotonic()
    with pytest.raises(BudgetExpired):runner.run([sys.executable,'-c','import time;time.sleep(60)'])
    assert time.monotonic()-start<6 and SOFT_SECONDS==WORKER_SECONDS-300


def test_continued_training_skips_colmap_and_runs_only_remaining_iterations(tmp_path,monkeypatch):
    jobs,ident,directory=saved_job(tmp_path);jobs.update(ident,resume_requested=True)
    monkeypatch.setattr(pipeline,'capabilities',lambda:dict(splat=True,mesh=False))
    commands=[]
    class Runner:
        def __init__(self,*a):pass
        def run(self,args):
            args=list(map(str,args));commands.append(args)
            if args[0]=='ns-train':
                assert args[args.index('--max-num-iterations')+1]=='20000'
                assert '--load-checkpoint' in args
                write_checkpoint(directory,29999)
            elif args[0]=='ns-export':
                out=Path(args[args.index('--output-dir')+1]);out.mkdir();write_splat(out/'scene.ply')
            else:pytest.fail('Resume must not run COLMAP: '+str(args))
    pipeline.run_job(jobs,ident,Runner)
    result=jobs.get(ident)
    assert result['phase']=='complete' and not result['partial'] and 'splat' in result['artifacts']
    assert not result['recovery']['resume_ready']
    assert [c[0] for c in commands]==['ns-train','ns-export']


def test_budget_pause_keeps_saved_outputs_downloadable_without_dispatching_new_work(tmp_path,monkeypatch):
    jobs,ident,directory=saved_job(tmp_path);jobs.deadline=time.monotonic()-1
    calls=[];pipeline.run_job(jobs,ident)
    result=jobs.get(ident)
    assert result['budget_paused'] and result['phase']=='complete' and result['partial']
    assert set(result['artifacts'])=={'glb','obj','stl'} and result['recovery']['resume_ready']
    assert result['checkpoint_step']==9999
    c=client(jobs,calls);assert c.get(f'/v1/jobs/{ident}/artifacts/glb').status_code==200 and not calls


def test_mesh_is_committed_before_training_starts(tmp_path,monkeypatch):
    # Reuse the full fake-engine regression to observe the persistence boundary.
    from test_mesh_recovery import fixture
    commits=[]
    monkeypatch.setattr(FileJobs,'persist',lambda self:commits.append(self.get(next(iter(self.records()))['id']).copy()),raising=False)
    jobs,ident,_=fixture(tmp_path,monkeypatch,mesh='poisson',splat=False)
    assert commits and commits[0]['stage']=='meshing' and jobs.get(ident)['outcomes']['mesh']['status']=='complete'
    assert (jobs.directory(ident)/'artifacts/mesh.glb').is_file()


def test_completed_checkpoint_can_be_exported_without_another_training_loop(tmp_path,monkeypatch):
    jobs,ident,directory=saved_job(tmp_path);write_checkpoint(directory,29999)
    jobs.update(ident,resume_requested=True)
    monkeypatch.setattr(pipeline,'capabilities',lambda:dict(splat=True,mesh=False))
    commands=[]
    class Runner:
        def __init__(self,*a):pass
        def run(self,args):
            args=list(map(str,args));commands.append(args)
            if args[0]=='ns-export':
                out=Path(args[args.index('--output-dir')+1]);out.mkdir();write_splat(out/'scene.ply')
            elif args[1:3]==['-m','strata_server.export_checkpoint']:
                assert args[-2].endswith('step-000029999.ckpt')
                Path(args[-1]).write_text('trusted export fixture')
            else:pytest.fail('A complete checkpoint must not restart training')
    pipeline.run_job(jobs,ident,Runner)
    assert jobs.get(ident)['phase']=='complete' and not any(c[0]=='ns-train' for c in commands)


def test_recovery_can_inspect_outputs_without_engines_but_continuation_requires_splat_support(tmp_path):
    jobs,ident,_=saved_job(tmp_path);calls=[]
    app=create_app(jobs.root,KEY,jobs=jobs,dispatch=calls.append,available=dict(splat=False,mesh=False))
    c=TestClient(app,headers={'X-Strata-Key':KEY})
    assert c.post(f'/v1/jobs/{ident}/recover').status_code==200
    assert c.post(f'/v1/jobs/{ident}/resume').status_code==503 and not calls


def test_expected_checkpoint_disappearing_never_falls_back_to_fresh_training(tmp_path,monkeypatch):
    jobs,ident,directory=saved_job(tmp_path,checkpoint=False);calls=[];c=client(jobs,calls)
    assert c.post(f'/v1/jobs/{ident}/resume?checkpoint_step=9999').status_code==409 and not calls
    jobs.update(ident,resume_requested=True,resume_min_checkpoint_step=9999)
    class NoProcess:
        def __init__(self,*a):pass
        def run(self,*a):pytest.fail('No fresh training is allowed when the expected checkpoint vanished')
    pipeline.run_job(jobs,ident,NoProcess)
    assert jobs.get(ident)['phase']=='failed'
    assert 'refusing' in jobs.get(ident)['message']
