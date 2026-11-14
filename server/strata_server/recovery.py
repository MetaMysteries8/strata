"""Inspect only this job's saved files. Never load Torch pickle or start GPU work here."""
from __future__ import annotations
import json,re,shutil,zipfile
from pathlib import Path
from .data import ARTIFACTS,atomic_json,inspect_ply

MAX_ARTIFACT=256*1024**2


def contained(file:Path,root:Path):
    return file.is_file() and file.resolve().is_relative_to(root.resolve())


def valid_checkpoint(path:Path):
    try:
        if not 0<path.stat().st_size<=4*1024**3:return False
        with zipfile.ZipFile(path) as z:
            entries=z.infolist()
            return len(entries)<10000 and sum(e.file_size for e in entries)<=4*1024**3 and any(e.filename.endswith('/data.pkl') for e in entries)
    except (OSError,ValueError,zipfile.BadZipFile):return False


def checkpoint(directory:Path):
    candidates=[]
    for path in list((directory/'trained').glob('**/step-*.ckpt'))[:1000]:
        match=re.fullmatch(r'step-(\d{9})\.ckpt',path.name)
        config=path.parent.parent/'config.yml'
        if match and contained(path,directory) and contained(config,directory):candidates.append((int(match[1]),path,config))
    for path in list((directory/'saved-training').glob('step-*.ckpt'))[:1000]:
        try:
            value=json.loads(path.with_suffix('.json').read_text());config=directory/value['config']
            match=re.fullmatch(r'step-(\d{9})\.ckpt',path.name)
            if match and contained(path,directory) and contained(config,directory):candidates.append((int(match[1]),path,config))
        except (OSError,ValueError,KeyError,TypeError):pass
    pointer=directory/'saved-training/checkpoint.json'
    if pointer.is_file():
        try:
            value=json.loads(pointer.read_text())
            path=directory/value['path'];config=directory/value['config']
            match=re.fullmatch(r'step-(\d{9})\.ckpt',path.name)
            if match and contained(path,directory) and contained(config,directory):candidates.append((int(match[1]),path,config))
        except (OSError,ValueError,KeyError,TypeError):pass
    for step,path,config in sorted(candidates,key=lambda x:x[0],reverse=True):
        if step<1_000_000 and valid_checkpoint(path):return dict(step=step,path=path,config=config)
    return None


def snapshot_checkpoint(directory:Path):
    """Keep a stable closed checkpoint even when Nerfstudio replaces its latest file."""
    found=checkpoint(directory)
    if not found:return None
    destination=directory/'saved-training';destination.mkdir(exist_ok=True)
    target=destination/f"step-{found['step']:09d}.ckpt"
    if found['path']!=target and not target.exists():
        pending=target.with_suffix('.pending')
        try:
            shutil.copyfile(found['path'],pending)
            if not valid_checkpoint(pending):return None
            pending.replace(target)
        except OSError:return None
        finally:pending.unlink(missing_ok=True)
    if not valid_checkpoint(target):return None
    metadata=dict(path=str(target.relative_to(directory)),config=str(found['config'].relative_to(directory)),step=found['step'])
    atomic_json(target.with_suffix('.json'),metadata)
    atomic_json(destination/'checkpoint.json',metadata)
    return {**found,'path':target}


def prune_snapshots(directory:Path):
    # Called only after a new complete snapshot has been committed.
    paths=sorted((directory/'saved-training').glob('step-*.ckpt'),key=lambda p:p.name,reverse=True)
    for file in paths[2:]:file.unlink(missing_ok=True);file.with_suffix('.json').unlink(missing_ok=True)


def training_dataset(directory:Path,mode:str):
    choices=['splat-input','aligned'] if mode=='both' else ['splat-input','dataset']
    for name in choices:
        source=directory/name
        try:
            manifest=json.loads((source/'transforms.json').read_text())
            frames=manifest['frames']
            if 0<len(frames)<=600 and all(contained(source/frame['file_path'],directory) for frame in frames):return source
        except (OSError,ValueError,KeyError,TypeError):pass
    return None


def inspect_saved(directory:Path,record:dict):
    from .pipeline import triangle_mesh,dense_cloud_info
    artifacts=[];metrics={};errors=[]
    for kind,name in ARTIFACTS.items():
        file=directory/'artifacts'/name
        if not contained(file,directory):continue
        try:
            limit=1024*1024**2 if kind in {'splat','points'} else MAX_ARTIFACT
            if file.stat().st_size>limit:raise ValueError(f'exceeds the {limit//1024**2} MiB recovery validation limit')
            if kind=='splat':info=inspect_ply(file,require_splat=True)
            elif kind=='points':info=dense_cloud_info(file)
            else:
                mesh=triangle_mesh(file);info=dict(vertices=len(mesh.vertices),faces=len(mesh.faces));del mesh
            artifacts.append(kind);metrics[kind]=info
        except Exception as exc:errors.append(kind+': '+str(exc)[-300:])
    saved=checkpoint(directory);data=training_dataset(directory,record['mode'])
    ready=record['mode'] in {'both','splat'} and 'splat' not in artifacts and data is not None and (saved is not None or 'glb' in artifacts)
    return dict(artifacts=artifacts,metrics=metrics,errors=errors,checkpoint=saved,dataset=data,resume_ready=ready,checkpoint_step=saved['step'] if saved else -1)


def public_summary(saved):
    return dict(artifacts=saved['artifacts'],resume_ready=saved['resume_ready'],checkpoint_step=saved['checkpoint_step'],errors=saved['errors'])


def recover_saved(jobs,ident):
    record=jobs.get(ident);saved=inspect_saved(jobs.directory(ident),record)
    requested=['splat','glb'] if record['mode']=='both' else ['splat'] if record['mode']=='splat' else ['glb']
    partial=any(kind not in saved['artifacts'] for kind in requested)
    outcomes={}
    for kind,artifact in [('mesh','glb'),('splat','splat')]:
        if artifact in requested:outcomes[kind]=dict(status='complete' if artifact in saved['artifacts'] else 'paused' if kind=='splat' and saved['resume_ready'] else 'failed',stage='meshing' if kind=='mesh' else 'training')
    message='Recovered saved outputs without running reconstruction.' if saved['artifacts'] else 'No valid saved export was found.'
    if saved['resume_ready']:message+=' Saved training can be continued explicitly; that uses GPU time.'
    elif not saved['artifacts']:message+=' The original capture is kept.'
    return jobs.update(ident,phase='complete' if saved['artifacts'] else 'failed',stage='complete' if saved['artifacts'] else 'failed',partial=partial,message=message,artifacts=saved['artifacts'],metrics=saved['metrics'],outcomes=outcomes,recovery=public_summary(saved),failure_before_recovery=record.get('failure_before_recovery') or record.get('message',''))
