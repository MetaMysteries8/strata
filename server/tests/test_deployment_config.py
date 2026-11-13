import importlib.util
import os
import sys
from pathlib import Path

import modal


CONFIG={
    'STRATA_MODAL_APP':'strata-audit-custom',
    'STRATA_MODAL_VOLUME':'strata-audit-volume',
    'STRATA_MODAL_DICT':'strata-audit-jobs',
    'STRATA_MODAL_SECRET':'strata-audit-secret',
}


def load(monkeypatch,name):
    file=Path(__file__).parents[1]/'modal_app.py'
    spec=importlib.util.spec_from_file_location(name,file)
    module=importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules,name,module)
    spec.loader.exec_module(module)
    return module


def test_both_remote_images_keep_only_the_deployment_identity(monkeypatch):
    captured=[]
    original=modal.Image.env
    def capture(image,values):
        captured.append(dict(values))
        return original(image,values)
    monkeypatch.setattr(modal.Image,'env',capture)
    for k,v in CONFIG.items():monkeypatch.setenv(k,v)
    monkeypatch.setenv('STRATA_API_KEY','test-secret-never-in-image')
    monkeypatch.setenv('MODAL_TOKEN_SECRET','test-account-secret-never-in-image')
    local=load(monkeypatch,'strata_audit_local')
    assert captured==[CONFIG,CONFIG]
    assert 'test-secret' not in str(captured)
    # Simulate a worker's fresh import using only the explicitly configured image environment.
    for k in list(os.environ):
        if k.startswith('STRATA_') or k.startswith('MODAL_TOKEN_'):monkeypatch.delenv(k,raising=False)
    for k,v in captured[0].items():monkeypatch.setenv(k,v)
    remote=load(monkeypatch,'strata_audit_remote')
    assert remote.app.name==local.app.name==CONFIG['STRATA_MODAL_APP']
    assert repr(remote.table)==repr(local.table)=="modal.Dict.from_name('strata-audit-jobs')"
    assert repr(remote.volume)==repr(local.volume)=="modal.Volume.from_name('strata-audit-volume')"


def test_startup_has_a_separate_timeout_from_reconstruction(monkeypatch):
    specs=[]
    original=modal.App.function
    def capture(app,**kwargs):
        specs.append(kwargs)
        return original(app,**kwargs)
    monkeypatch.setattr(modal.App,'function',capture)
    load(monkeypatch,'strata_audit_timeouts')
    worker,api=specs
    assert worker.get('startup_timeout')==300
    assert worker['timeout']==7200
    assert api.get('startup_timeout')==300
