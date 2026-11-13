import argparse,hashlib,json,os,secrets,subprocess,sys,urllib.request
from pathlib import Path
from urllib.parse import urlparse
from android_runtime.companion import private_json
from android_runtime.support import workspace_identity,failure_hint

def deployment_config(root,identity,workspace):
    directory=root/'workspaces'/hashlib.sha256(identity['workspace_id'].encode()).hexdigest()[:24]
    file=directory/'deployment.json'
    if file.exists():
        config=json.loads(file.read_text())
        if config.get('workspace_id')!=identity['workspace_id']:raise ValueError('Mismatched deployment workspace')
        return file,config
    legacy=root/'deployment.json'
    if legacy.exists():
        previous=json.loads(legacy.read_text())
        token_id=previous.get('proxy','').split('.')[0]
        # Reuse a 0.2.1 deployment only if the provider confirms ownership in this workspace.
        try:owned=token_id and any(t.token_id==token_id for t in workspace.proxy_tokens.list())
        except Exception:owned=False
        if owned:
            previous.update(identity);private_json(file,previous);return file,previous
    ident=secrets.token_hex(5)
    config=dict(app='strata-phone-'+ident,key=secrets.token_urlsafe(32),secret='strata-phone-auth-'+ident,volume='strata-phone-data-'+ident,table='strata-phone-jobs-'+ident,**identity)
    private_json(file,config)
    return file,config

def deploy(root,source,update=False):
    import modal
    from modal.workspace import Workspace
    def stage(name):private_json(root/'progress.json',dict(stage=name))
    stage('account')
    info=subprocess.run([sys.executable,'-m','modal','token','info'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=45)
    if info.returncode:raise RuntimeError(info.stdout or 'Authentication failed')
    identity=workspace_identity(info.stdout)
    workspace=Workspace.from_context()
    file,config=deployment_config(root,identity,workspace)
    from android_runtime.release_client import active_source
    source=active_source(root,source)
    release=None
    if update:
        stage('update_check')
        try:
            records=modal.Dict.from_name(config['table']).items()
            if any(key.startswith('job:') and value.get('phase') not in {'complete','failed','cancelled'} for key,value in records):
                raise ValueError('Active Strata jobs exist. Finish or cancel them before updating the server.')
        except modal.exception.NotFoundError:pass
        from android_runtime.release_client import latest,install
        release=latest()
        stage('update_download')
        source=install(root,release)
        stage('update_validate')
    stage('credentials')
    modal.Secret.objects.create(config['secret'],{'STRATA_API_KEY':config['key']},allow_existing=True)
    env={**os.environ,'STRATA_MODAL_APP':config['app'],'STRATA_MODAL_SECRET':config['secret'],'STRATA_MODAL_VOLUME':config['volume'],'STRATA_MODAL_DICT':config['table']}
    stage('image_build');log=root/'deploy.log'
    with log.open('w') as out:
        os.chmod(log,0o600)
        subprocess.run([sys.executable,'-m','modal','deploy',str(source/'modal_app.py')],env=env,cwd=source,stdout=out,stderr=subprocess.STDOUT,check=True)
    stage('endpoint_auth')
    if not config.get('proxy'):
        token=workspace.proxy_tokens.create(name=config['app']);config['proxy']=token.token_id+'.'+token.token_secret;private_json(file,config)
    url=modal.Function.from_name(config['app'],'api').get_web_url();u=urlparse(url or '')
    if u.scheme!='https' or not (u.hostname or '').endswith('.modal.run'):raise ValueError('No supported HTTPS Modal endpoint returned')
    stage('health')
    req=urllib.request.Request(url.rstrip('/')+'/v1/health',headers={'X-Strata-Key':config['key'],'Authorization':'Bearer '+config['proxy']})
    import time
    deadline=time.monotonic()+120
    while True:
        with urllib.request.urlopen(req,timeout=120) as r:health=json.load(r)
        if health.get('service')!='strata' or health.get('protocol')!=1:raise ValueError('Endpoint did not pass Strata health verification')
        if not release or health.get('version')==release['version']:break
        if time.monotonic()>=deadline:raise ValueError('Updated release API is not ready yet; retry after checking Modal')
        time.sleep(1)
    if release:private_json(root/'active-source.json',dict(path=str(source.relative_to(root)),version=release['version']))
    private_json(root/'connection.json',dict(url=url.rstrip('/'),key=config['key'],proxy=config['proxy'],**identity))
    stage('complete')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--state',type=Path,required=True);p.add_argument('--source',type=Path,required=True);p.add_argument('--release',action='store_true');a=p.parse_args();os.umask(0o077)
    try:deploy(a.state,a.source,a.release)
    except Exception as error:
        import traceback
        a.state.mkdir(parents=True,exist_ok=True,mode=0o700)
        with (a.state/'deploy-error.log').open('w') as out:
            os.chmod(a.state/'deploy-error.log',0o600)
            traceback.print_exc(file=out)
        details=str(error)
        log=a.state/'deploy.log'
        if log.exists():
            with log.open('rb') as f:
                f.seek(0,2);f.seek(max(0,f.tell()-8192));details+='\n'+f.read(8192).decode(errors='replace')
        private_json(a.state/'deploy-error.json',dict(hint=failure_hint(details)))
        raise SystemExit(1)
