import io,json,sys,time
from pathlib import Path
from types import SimpleNamespace
import pytest
from fastapi.testclient import TestClient
from android_runtime.companion import Controller,create_app,modal_url,private_json
from android_runtime import deploy_endpoint
KEY='test-runtime-key-at-least-24-characters'
def wait(predicate):
 end=time.monotonic()+5
 while time.monotonic()<end:
  if predicate():return
  time.sleep(.02)
 raise AssertionError('State timeout')
def runtime(tmp_path):
 source=tmp_path/'source';source.mkdir();root=tmp_path/'private';script=source/'fake_cli.py'
 script.write_text('''import os,sys,time
from pathlib import Path
p=Path(os.environ['MODAL_CONFIG_PATH'])
if sys.argv[1:3]==['token','info']:
 if not p.exists():sys.exit(1)
 print('Token: test-only-secret')
 print('Workspace: Test workspace (ac-test)')
 print('Expires at: 2026-11-20 12:00:00 UTC')
else:
 print('https://modal.com/token-flow/test-fixture',flush=True)
 print('Enter this code: TEST-123',flush=True)
 while not (p.parent/'approved').exists():time.sleep(.02)
 p.write_text('synthetic profile')
''')
 c=Controller(root,source,[sys.executable,str(script)],restore=False)
 return c,TestClient(create_app(c,KEY),headers={'X-Strata-Key':KEY})
def test_auth_before_actions_and_no_browser_access(tmp_path):
 c,client=runtime(tmp_path)
 assert TestClient(client.app).post('/v1/auth/start').status_code==401
 assert client.post('/v1/auth/start',headers={'Origin':'https://evil.test'}).status_code==403
 assert c.worker is None
 assert client.get('/v1/health').json()['auth']['phase']=='signed_out'
def test_cli_approval_is_required_and_credentials_are_not_returned(tmp_path):
 c,client=runtime(tmp_path);client.post('/v1/auth/start')
 try:
  wait(lambda:c.state()['loginReady'])
  assert client.get('/v1/auth/browser').json()['url']=='https://modal.com/token-flow/test-fixture'
  assert c.state()['auth']['phase']=='waiting'
  assert client.post('/v1/deploy').status_code==409
  assert client.post('/v1/auth/start').status_code==409
  (c.root/'approved').touch();wait(lambda:c.state()['auth']['phase']=='signed_in');c.worker.join(2)
  assert 'test-only-secret' not in json.dumps(c.state())
  assert client.post('/v1/auth/logout').status_code==200
  assert not (c.root/'modal.toml').exists()
 finally:c.cancel()
def test_cancel_stops_real_cli_subprocess(tmp_path):
 c,client=runtime(tmp_path);client.post('/v1/auth/start');wait(lambda:c.state()['loginReady']);client.post('/v1/cancel');c.worker.join(4)
 assert not c.worker.is_alive() and c.state()['auth']['phase']!='signed_in'
def test_login_allowlist():
 assert modal_url('https://modal.com/token-flow/test?code=abc')
 for url in ('http://modal.com/a','https://modal.com.evil.test','https://user@modal.com/a','https://evil.test','https://modal.com:123/a'):assert not modal_url(url)
def test_private_endpoint_credentials_require_auth(tmp_path):
 c,client=runtime(tmp_path);secret={'url':'https://test.modal.run','key':'test-only-secret','proxy':'test-only-proxy'};private_json(c.root/'connection.json',secret)
 assert (c.root/'connection.json').stat().st_mode & 0o777==0o600
 assert client.get('/v1/connection').json()==secret
 assert 'test-only-secret' not in json.dumps(client.get('/v1/health').json())
 assert TestClient(client.app).get('/v1/connection').status_code==401
@pytest.mark.parametrize('healthy',[True,False])
def test_only_verified_deployments_publish_connection(tmp_path,monkeypatch,healthy):
 import modal
 from modal.workspace import Workspace
 root=tmp_path/'private';root.mkdir();source=tmp_path/'source';source.mkdir();calls=[]
 monkeypatch.setattr(modal,'Secret',SimpleNamespace(objects=SimpleNamespace(create=lambda *a,**kw:calls.append('secret'))))
 monkeypatch.setattr(Workspace,'from_context',lambda:SimpleNamespace(proxy_tokens=SimpleNamespace(create=lambda **kw:SimpleNamespace(token_id='wk-test',token_secret='ws-test'),list=lambda:[])))
 monkeypatch.setattr(modal.Function,'from_name',lambda *a:SimpleNamespace(get_web_url=lambda:'https://test.modal.run'))
 def run(args,**kw):
  calls.append(args)
  return SimpleNamespace(stdout='Workspace: Test workspace (ac-test)\n',returncode=0)
 monkeypatch.setattr(deploy_endpoint.subprocess,'run',run)
 monkeypatch.setattr(deploy_endpoint.urllib.request,'urlopen',lambda request,**kw:io.BytesIO(json.dumps({'service':'strata' if healthy else 'wrong','protocol':1}).encode()))
 if healthy:
  deploy_endpoint.deploy(root,source);assert json.loads((root/'connection.json').read_text())['proxy']=='wk-test.ws-test';assert len(calls)==3
 else:
  with pytest.raises(ValueError,match='health'):deploy_endpoint.deploy(root,source)
  assert not (root/'connection.json').exists()

def test_account_verification_can_be_cancelled_even_if_cli_ignores_term(tmp_path):
 import threading
 c,client=runtime(tmp_path)
 script=c.source/'ignore.py'
 script.write_text("import signal,time,os\nfrom pathlib import Path\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nPath(os.environ['MODAL_CONFIG_PATH']).with_suffix('.started').touch()\ntime.sleep(60)\n")
 c.cli=[sys.executable,str(script)]
 c.worker=threading.Thread(target=c.verify,daemon=True);c.worker.start()
 wait(lambda:(c.root/'modal.started').exists())
 start=time.monotonic();client.post('/v1/cancel');c.worker.join(4)
 assert not c.worker.is_alive() and time.monotonic()-start<5
 assert c.state()['auth']['phase']=='cancelled'

def test_health_rechecks_stale_credentials_without_a_retry_storm(tmp_path,monkeypatch):
 # A fresh VM/phone can have less than five minutes of monotonic uptime.
 from android_runtime import companion
 monkeypatch.setattr(companion,'time',SimpleNamespace(monotonic=lambda:100.0))
 c,client=runtime(tmp_path);(c.root/'modal.toml').touch()
 script=c.source/'expired.py';script.write_text('import sys\nsys.exit(1)\n');c.cli=[sys.executable,str(script)]
 c.auth={'phase':'signed_in','workspace_id':'ac-old'};c.last_verified=0
 client.get('/v1/health');wait(lambda:not c.occupied())
 assert c.state()['auth']['phase']=='signed_out'
 worker=c.worker;client.get('/v1/health');assert c.worker is worker

def test_workspace_connections_and_diagnostics_do_not_leak_keys(tmp_path):
 c,client=runtime(tmp_path)
 private_json(c.root/'connection.json',dict(workspace_id='ac-other',url='https://test.modal.run',key='sensitive-test-key',proxy='sensitive-test-proxy'))
 c.auth={'phase':'signed_in','workspace_id':'ac-current'}
 assert not c.state()['hasConnection']
 assert client.get('/v1/connection').status_code==404
 result=client.get('/v1/diagnostics')
 assert result.status_code==200 and 'sensitive-test' not in result.text
 assert TestClient(client.app).get('/v1/diagnostics').status_code==401

def test_deploy_configs_are_separate_for_each_workspace(tmp_path):
 w=SimpleNamespace(proxy_tokens=SimpleNamespace(list=lambda:[]))
 a,config_a=deploy_endpoint.deployment_config(tmp_path,{'workspace_id':'ac-a','workspace':'A'},w)
 b,config_b=deploy_endpoint.deployment_config(tmp_path,{'workspace_id':'ac-b','workspace':'B'},w)
 assert a!=b and config_a['key']!=config_b['key'] and config_a['app']!=config_b['app']
 _,again=deploy_endpoint.deployment_config(tmp_path,{'workspace_id':'ac-a','workspace':'A'},w)
 assert again==config_a

@pytest.mark.parametrize('owned',[True,False])
def test_legacy_endpoint_is_only_reused_when_provider_confirms_ownership(tmp_path,owned):
 old=dict(app='legacy',key='old-test-key',proxy='wk-test.ws-test')
 private_json(tmp_path/'deployment.json',old)
 w=SimpleNamespace(proxy_tokens=SimpleNamespace(list=lambda:[SimpleNamespace(token_id='wk-test')] if owned else []))
 _,config=deploy_endpoint.deployment_config(tmp_path,{'workspace_id':'ac-current','workspace':'Current'},w)
 assert (config['app']=='legacy') is owned
 assert json.loads((tmp_path/'deployment.json').read_text())==old

def test_cancelled_deploy_does_not_report_an_old_connection_as_new_success(tmp_path,monkeypatch):
 c,_=runtime(tmp_path);c.auth={'phase':'signed_in','workspace_id':'ac-test'}
 private_json(c.root/'connection.json',{'workspace_id':'ac-test'})
 class Process:
  def wait(self,timeout):c.cancelled.set();return 0
 monkeypatch.setattr('android_runtime.companion.subprocess.Popen',lambda *a,**kw:Process())
 c._deploy()
 assert c.state()['deployment']['phase']=='cancelled'

def test_error_hints_do_not_repeat_tokens_or_provider_output():
 from android_runtime.support import failure_hint,workspace_identity
 for value in ['Unauthorized ak-PRIVATE as-PRIVATE','Unknown problem with key=PRIVATE','TLS timeout key=PRIVATE']:
  assert 'PRIVATE' not in failure_hint(value)
 assert workspace_identity('Workspace: My workspace (ac-example)')['workspace_id']=='ac-example'
 with pytest.raises(ValueError):workspace_identity('Token: secret; no workspace identity')

def test_explicit_recheck_can_retry_immediately_after_a_failure(tmp_path):
 c,client=runtime(tmp_path)
 assert client.post('/v1/auth/verify').status_code==409
 (c.root/'modal.toml').write_text('synthetic profile')
 c.last_verified=time.monotonic();c.auth={'phase':'signed_out'}
 assert client.post('/v1/auth/verify').status_code==200
 wait(lambda:c.state()['auth']['phase']=='signed_in')
 assert c.state()['auth']['workspace_id']=='ac-test'

def test_runtime_reports_saved_profile_and_only_a_formatted_token_expiry(tmp_path):
 from android_runtime.support import token_expiry
 c,client=runtime(tmp_path)
 assert not c.state()['hasProfile']
 (c.root/'modal.toml').write_text('synthetic profile')
 client.post('/v1/auth/verify');wait(lambda:c.state()['auth']['phase']=='signed_in')
 state=c.state()
 assert state['hasProfile'] and state['auth']['expiresAt']=='2026-11-20 12:00:00 UTC'
 assert 'test-only-secret' not in json.dumps(state)
 assert token_expiry('Expires at: secret-token')==''
 assert token_expiry('Expires at: 2026-11-20 12:00:00 UTC\nToken: private')=='2026-11-20 12:00:00 UTC'
