import hashlib,io,json,stat,zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from android_runtime import release_client as releases,deploy_endpoint
from android_runtime.companion import Controller,create_app


def bundle(extra=None):
    out=io.BytesIO()
    with zipfile.ZipFile(out,'w') as z:
        for name in ['modal_app.py','strata_server/api.py','android_runtime/deploy_endpoint.py']:z.writestr('strata-modal/'+name,'# test source')
        if extra:z.writestr(extra,b'bad')
    return out.getvalue()


def manifest(data):
    return dict(schema=1,version='0.3.0',versionCode=10,runtimeProtocol=1,minCompanion='0.3.0',runtime=dict(name='Strata-Modal-Android.zip',size=len(data),sha256=hashlib.sha256(data).hexdigest()))


def test_verified_runtime_is_staged_without_changing_the_active_source(tmp_path,monkeypatch):
    data=bundle();m=manifest(data);urls=[]
    monkeypatch.setattr(releases,'read',lambda url,limit:urls.append(url) or data)
    source=releases.install(tmp_path,m)
    assert (source/'modal_app.py').is_file()
    assert not (tmp_path/'active-source.json').exists()
    assert releases.install(tmp_path,m)==source and len(urls)==1
    assert releases.active_source(tmp_path,Path('/fallback'))==Path('/fallback')


@pytest.mark.parametrize('path',['../outside','/outside','strata-modal/../outside','strata-modal\\evil','strata-modal/./modal_app.py'])
def test_unsafe_runtime_archives_cannot_escape_or_overwrite(tmp_path,monkeypatch,path):
    data=bundle(path);monkeypatch.setattr(releases,'read',lambda *args:data)
    with pytest.raises(ValueError,match='Unsafe'):releases.install(tmp_path,manifest(data))
    assert not (tmp_path/'releases/v0.3.0').exists()


def test_bad_hash_and_incompatible_runtime_are_rejected_before_deployment(tmp_path,monkeypatch):
    data=bundle();m=manifest(data);m['runtime']['sha256']='a'*64
    monkeypatch.setattr(releases,'read',lambda *args:data)
    with pytest.raises(ValueError,match='checksum'):releases.install(tmp_path,m)
    m=manifest(data);m['minCompanion']='99.0.0'
    with pytest.raises(ValueError,match='phone runtime'):releases.validate(m,'v0.3.0')
    for url in ['http://github.com/a','https://github.com.evil.test','https://user@github.com/a','https://github.com:123/a']:assert not releases.allowed(url)


def test_source_pointer_cannot_select_arbitrary_local_code(tmp_path):
    (tmp_path/'active-source.json').write_text(json.dumps({'path':'../elsewhere'}))
    with pytest.raises(ValueError,match='Invalid saved'):releases.active_source(tmp_path,Path('/fallback'))


def test_release_deploy_endpoint_requires_pairing_and_explicit_post(tmp_path,monkeypatch):
    c=Controller(tmp_path,tmp_path,restore=False);calls=[];key='test-release-pairing-key-at-least-24'
    monkeypatch.setattr(c,'deploy',lambda update=False:calls.append(update))
    app=create_app(c,key)
    assert TestClient(app).post('/v1/release/update-deploy').status_code==401
    client=TestClient(app,headers={'X-Strata-Key':key})
    assert client.get('/v1/health').status_code==200 and not calls
    assert client.post('/v1/release/update-deploy').status_code==200 and calls==[True]


def test_active_cloud_jobs_block_update_before_credentials_or_release_download(tmp_path,monkeypatch):
    import modal
    from modal.workspace import Workspace
    source=tmp_path/'source';source.mkdir()
    monkeypatch.setattr(deploy_endpoint.subprocess,'run',lambda *a,**k:SimpleNamespace(stdout='Workspace: Test (ac-test)',returncode=0))
    monkeypatch.setattr(Workspace,'from_context',lambda:SimpleNamespace(proxy_tokens=SimpleNamespace(list=lambda:[])))
    monkeypatch.setattr(modal.Dict,'from_name',lambda *a,**k:SimpleNamespace(items=lambda:[('job:test',{'phase':'running'})]))
    monkeypatch.setattr(releases,'latest',lambda:pytest.fail('Release download must not happen while jobs are active'))
    with pytest.raises(ValueError,match='Active Strata jobs'):deploy_endpoint.deploy(tmp_path,source,update=True)


@pytest.mark.parametrize('healthy',[True,False])
def test_release_deploy_selects_verified_source_and_only_switches_saved_connection_after_health(tmp_path,monkeypatch,healthy):
    import modal
    from modal.workspace import Workspace
    from android_runtime.companion import private_json
    original=tmp_path/'original';original.mkdir()
    selected=tmp_path/'releases/v0.3.0/strata-modal';selected.mkdir(parents=True)
    (selected/'modal_app.py').write_text('# verified test source')
    config=dict(app='existing-app',secret='existing-secret',key='existing-key',volume='existing-volume',table='existing-table',proxy='existing-proxy',workspace='Test',workspace_id='ac-test')
    private_json(tmp_path/'connection.json',dict(url='https://old.modal.run'))
    commands=[]
    def run(args,**kwargs):
        commands.append((args,kwargs))
        return SimpleNamespace(stdout='Workspace: Test (ac-test)',returncode=0)
    monkeypatch.setattr(deploy_endpoint,'deployment_config',lambda *args:(tmp_path/'deployment.json',config))
    monkeypatch.setattr(deploy_endpoint.subprocess,'run',run)
    monkeypatch.setattr(Workspace,'from_context',lambda:SimpleNamespace())
    monkeypatch.setattr(modal.Dict,'from_name',lambda *a,**k:SimpleNamespace(items=lambda:[]))
    monkeypatch.setattr(modal,'Secret',SimpleNamespace(objects=SimpleNamespace(create=lambda *a,**k:None)))
    monkeypatch.setattr(modal.Function,'from_name',lambda *a,**k:SimpleNamespace(get_web_url=lambda:'https://new.modal.run'))
    monkeypatch.setattr(releases,'latest',lambda:dict(version='0.3.0'))
    monkeypatch.setattr(releases,'install',lambda *a:selected)
    health=io.BytesIO(json.dumps(dict(service='strata' if healthy else 'unexpected',protocol=1,version='0.3.0')).encode())
    monkeypatch.setattr(deploy_endpoint.urllib.request,'urlopen',lambda *a,**k:health)
    if healthy:
        deploy_endpoint.deploy(tmp_path,original,update=True)
        assert json.loads((tmp_path/'connection.json').read_text())['url']=='https://new.modal.run'
        assert releases.active_source(tmp_path,original)==selected
        assert json.loads((tmp_path/'progress.json').read_text())['stage']=='complete'
    else:
        with pytest.raises(ValueError,match='health verification'):deploy_endpoint.deploy(tmp_path,original,update=True)
        assert json.loads((tmp_path/'connection.json').read_text())['url']=='https://old.modal.run'
        assert not (tmp_path/'active-source.json').exists()
    command,options=commands[-1]
    assert command[-1]==str(selected/'modal_app.py') and options['cwd']==selected
    assert options['env']['STRATA_MODAL_APP']=='existing-app'
    assert options['env']['STRATA_MODAL_DICT']=='existing-table'
