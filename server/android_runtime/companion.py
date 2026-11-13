from __future__ import annotations
import argparse,hmac,json,os,re,secrets,signal,subprocess,sys,threading,time
from pathlib import Path
from urllib.parse import urlparse
from fastapi import Depends,FastAPI,Header,HTTPException,Request
from .support import workspace_identity, failure_hint, token_expiry
from strata_server.version import VERSION
ANSI=re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")

def private_json(path,value):
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    temp=path.with_suffix('.tmp')
    with temp.open('w') as f:
        os.chmod(temp,0o600);json.dump(value,f);f.flush();os.fsync(f.fileno())
    temp.replace(path)

def modal_url(value):
    try:
        u=urlparse(value)
        return u.scheme=='https' and u.hostname in {'modal.com','www.modal.com'} and not u.username and not u.password and u.port in {None,443}
    except ValueError:return False

class Controller:
    def __init__(self,root,source,cli=None,restore=True):
        self.root,self.source=Path(root),Path(source);self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.cli=cli or [sys.executable,'-m','modal'];self.lock=threading.RLock();self.process=None;self.worker=None
        self.auth={'phase':'signed_out','message':'Sign in with Modal to continue.'};self.deployment={'phase':'idle','message':'No deployment started.'}
        self.login_url='';self.login_code='';self.cancelled=threading.Event()
        self.last_verified=0.0
        self.env={k:v for k,v in os.environ.items() if not k.startswith('MODAL_')}
        self.env.update(MODAL_CONFIG_PATH=str(self.root/'modal.toml'),BROWSER='/bin/false',NO_COLOR='1',TERM='dumb',COLUMNS='500',PYTHONUNBUFFERED='1')
        if restore and (self.root/'modal.toml').exists():
            self.worker=threading.Thread(target=self.verify,daemon=True);self.worker.start()
    def saved_connection(self):
        try: connection=json.loads((self.root/'connection.json').read_text())
        except (OSError,ValueError): return None
        current=self.auth.get('workspace_id')
        if current and connection.get('workspace_id')!=current:return None
        return connection
    def state(self):
        with self.lock:
            deployment=dict(self.deployment)
            if deployment.get('phase')=='deploying':
                try:
                    progress=json.loads((self.root/'progress.json').read_text())
                    if progress.get('stage') in {'account','credentials','image_build','endpoint_auth','health','complete','update_check','update_download','update_validate'}:
                        deployment['stage']=progress['stage']
                except (OSError,ValueError):pass
            return dict(service='strata-modal-runtime',protocol=1,version=VERSION,auth=dict(self.auth),deployment=deployment,loginReady=bool(self.login_url),code=self.login_code,hasProfile=(self.root/'modal.toml').exists(),hasConnection=self.saved_connection() is not None)
    def maybe_verify(self):
        with self.lock:
            if self.occupied() or not (self.root/'modal.toml').exists() or time.monotonic()-self.last_verified<300:return
            self.cancelled.clear()
            self.worker=threading.Thread(target=self.verify,daemon=True);self.worker.start()
    def occupied(self):return self.worker is not None and self.worker.is_alive()
    def recheck(self):
        with self.lock:
            if self.occupied():raise ValueError('A login or deployment is already running.')
            if not (self.root/'modal.toml').exists():raise ValueError('Sign in with Modal first.')
            self.cancelled.clear()
            self.worker=threading.Thread(target=self.verify,daemon=True);self.worker.start()
    def verify(self):
        with self.lock:self.auth={'phase':'checking','message':'Verifying your Modal account…'}
        process=None
        try:
            process=subprocess.Popen(self.cli+['token','info'],env=self.env,cwd=self.source,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
            with self.lock:
                self.process=process
                if self.cancelled.is_set():self._terminate(process)
            output,_=process.communicate(timeout=45)
            if process.returncode:raise ValueError(output or 'Authentication failed')
            identity=workspace_identity(output)
            with self.lock:
                if self.cancelled.is_set():return
                self.auth={'phase':'signed_in',**identity,'expiresAt':token_expiry(output),'message':'Modal verified your account.'}
        except Exception as e:
            with self.lock:self.auth={'phase':'signed_out','message':failure_hint(e)}
        finally:
            if process:self._terminate(process)
            with self.lock:
                if self.process is process:self.process=None
                self.last_verified=time.monotonic()
                if self.cancelled.is_set():self.auth={'phase':'cancelled','message':'Account verification cancelled.'}
    def login(self):
        with self.lock:
            if self.occupied():raise ValueError('A login or deployment is already running.')
            self.cancelled.clear();self.login_url='';self.login_code='';self.auth={'phase':'starting','message':'Requesting Modal browser login…'}
            self.worker=threading.Thread(target=self._login,daemon=True);self.worker.start()
    def _login(self):
        process=None
        try:
            process=subprocess.Popen(self.cli+['token','new','--expires-in','7d'],env=self.env,cwd=self.source,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1,start_new_session=True)
            with self.lock:
                self.process=process
                if self.cancelled.is_set():self._terminate(process)
            timer=threading.Timer(600,lambda:self._terminate(process));timer.daemon=True;timer.start()
            try:
                for raw in process.stdout:
                    line=ANSI.sub('',raw)
                    for url in re.findall(r'https://[^\s\x1b<>]+',line):
                        url=url.rstrip(').,]')
                        if modal_url(url):
                            with self.lock:self.login_url=url;self.auth={'phase':'waiting','message':'Approve login in your browser, then return to Strata.'}
                    m=re.search(r'Enter this code:\s*(\S+)',line)
                    if m:
                        with self.lock:self.login_code=m.group(1)[:100]
                code=process.wait(timeout=5)
            finally:timer.cancel()
            if code or self.cancelled.is_set():
                with self.lock:self.auth={'phase':'failed','message':'Modal login stopped or timed out. Try signing in again.'}
            else:self.verify()
        except Exception:
            with self.lock:self.auth={'phase':'failed','message':'Could not run the Modal CLI. Check the Linux runtime and network.'}
        finally:
            if process:
                self._terminate(process)
                if process.stdout:process.stdout.close()
            with self.lock:
                self.process=None;self.login_url='';self.login_code=''
                if self.cancelled.is_set():self.auth={'phase':'cancelled','message':'Login cancelled.'}
    @staticmethod
    def _terminate(process):
        if process.poll() is None:
            try:os.killpg(process.pid,signal.SIGTERM)
            except ProcessLookupError:pass
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError:pass
                process.wait(timeout=2)
    def cancel(self):
        self.cancelled.set()
        with self.lock:
            if self.process:self._terminate(self.process)
            self.login_url='';self.login_code=''
    def deploy(self,update=False):
        with self.lock:
            if self.occupied():raise ValueError('A login or deployment is already running.')
            if self.auth['phase']!='signed_in':raise ValueError('Sign in with Modal first.')
            (self.root/'deploy-error.json').unlink(missing_ok=True)
            private_json(self.root/'progress.json',dict(stage='account'))
            self.cancelled.clear();self.deployment={'phase':'deploying','message':'Deploying your protected scan endpoint. The first image build can take several minutes.'}
            self.worker=threading.Thread(target=lambda:self._deploy(update),daemon=True);self.worker.start()
    def _deploy(self,update=False):
        try:
            command=[sys.executable,'-m','android_runtime.deploy_endpoint','--state',str(self.root),'--source',str(self.source)]
            if update:command.append('--release')
            p=subprocess.Popen(command,env=self.env,cwd=self.source,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
            with self.lock:
                self.process=p
                if self.cancelled.is_set():self._terminate(p)
            try:code=p.wait(timeout=3600)
            except subprocess.TimeoutExpired:self._terminate(p);raise ValueError('Deployment timed out; check Modal before retrying.')
            if self.cancelled.is_set():raise ValueError('Cancelled')
            if code:
                try: hint=json.loads((self.root/'deploy-error.json').read_text()).get('hint')
                except (OSError,ValueError):hint=None
                raise ValueError(hint or 'Deployment failed. Check the private runtime deployment log.')
            if self.saved_connection() is None:raise ValueError('No verified endpoint connection for this workspace was returned.')
            with self.lock:self.deployment={'phase':'ready','message':'Your Modal scan endpoint is ready. Connect it to Strata.'}
        except Exception as e:
            with self.lock:self.deployment={'phase':'cancelled' if self.cancelled.is_set() else 'failed','message':'Deployment cancelled. Check Modal for resources already created.' if self.cancelled.is_set() else str(e)[:300]}
        finally:
            with self.lock:self.process=None
    def logout(self):
        with self.lock:
            if self.occupied():raise ValueError('Cancel the current task first.')
            (self.root/'modal.toml').unlink(missing_ok=True)
            self.auth={'phase':'signed_out','message':'Signed out on this phone. Deployments and endpoint keys remain available.'};self.login_url='';self.login_code=''

def create_app(c,key):
    if len(key)<24:raise ValueError('Invalid runtime pairing key')
    app=FastAPI(docs_url=None,redoc_url=None,openapi_url=None)
    async def auth(request:Request,x_strata_key:str=Header(default='')):
        if request.headers.get('origin'):raise HTTPException(403,'Browser requests are not accepted')
        if not hmac.compare_digest(x_strata_key.encode(),key.encode()):raise HTTPException(401,'Invalid phone runtime key')
    @app.get('/v1/health',dependencies=[Depends(auth)])
    def health():c.maybe_verify();return c.state()
    @app.post('/v1/auth/start',dependencies=[Depends(auth)])
    def login():
        try:c.login();return c.state()
        except ValueError as e:raise HTTPException(409,str(e))
    @app.get('/v1/auth/browser',dependencies=[Depends(auth)])
    def browser():
        with c.lock:
            if not modal_url(c.login_url):raise HTTPException(409,'No login URL is ready yet')
            return {'url':c.login_url}
    @app.post('/v1/auth/verify',dependencies=[Depends(auth)])
    def verify():
        try:c.recheck();return c.state()
        except ValueError as e:raise HTTPException(409,str(e))
    @app.post('/v1/cancel',dependencies=[Depends(auth)])
    def cancel():c.cancel();return c.state()
    @app.post('/v1/deploy',dependencies=[Depends(auth)])
    def deploy():
        try:c.deploy();return c.state()
        except ValueError as e:raise HTTPException(409,str(e))
    @app.post('/v1/release/update-deploy',dependencies=[Depends(auth)])
    def update_deploy():
        try:c.deploy(update=True);return c.state()
        except ValueError as e:raise HTTPException(409,str(e))
    @app.get('/v1/connection',dependencies=[Depends(auth)])
    def connection():
        with c.lock:connection=c.saved_connection()
        if connection is None:raise HTTPException(404,'Deploy an endpoint for the signed-in workspace first')
        return connection
    @app.get('/v1/diagnostics',dependencies=[Depends(auth)])
    def diagnostics():
        import platform
        state=c.state()
        return dict(service=state['service'],version=state['version'],python=platform.python_version(),authPhase=state['auth']['phase'],deploymentPhase=state['deployment']['phase'],stage=state['deployment'].get('stage',''),hasEndpoint=state['hasConnection'])
    @app.post('/v1/auth/logout',dependencies=[Depends(auth)])
    def logout():
        try:c.logout();return c.state()
        except ValueError as e:raise HTTPException(409,str(e))
    return app

def main():
    import uvicorn
    p=argparse.ArgumentParser();p.add_argument('--state',type=Path,default=Path.home()/'.local/share/strata-modal');p.add_argument('--port',type=int,default=8788);a=p.parse_args()
    os.umask(0o077);a.state.mkdir(parents=True,exist_ok=True,mode=0o700);file=a.state/'runtime.key'
    if not file.exists():
        fd=os.open(file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f:f.write(secrets.token_urlsafe(32))
    key=file.read_text().strip()
    print('\nStrata phone runtime is ready. Copy this pairing key into Strata Settings:\n'+key+'\nKeep Termux running during login/deployment.\n',flush=True)
    uvicorn.run(create_app(Controller(a.state,Path(__file__).resolve().parent.parent),key),host='127.0.0.1',port=a.port,access_log=False)
if __name__=='__main__':main()
