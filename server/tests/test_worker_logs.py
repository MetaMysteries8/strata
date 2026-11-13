import sys
from pathlib import Path

from strata_server.pipeline import ProcessRunner


def test_engine_output_reaches_console_without_a_stat_per_line(tmp_path, capsys):
    class Log:
        stats=0
        def __init__(self):self.path=tmp_path/'engine.log'
        def open(self,*args,**kwargs):return self.path.open(*args,**kwargs)
        def stat(self):self.stats+=1;return self.path.stat()
    log=Log()
    ProcessRunner(lambda:False,log).run([sys.executable,'-c',"for i in range(500):print('iteration',i)"])
    output=capsys.readouterr().out
    assert '[Strata] Starting' in output and 'iteration 499' in output and '[Strata] Finished' in output
    assert 'iteration 0' in log.path.read_text() and log.stats==0


def test_log_budget_is_bounded_without_changing_a_successful_engine_result(tmp_path):
    log=tmp_path/'engine.log'
    log.write_bytes(b'x'*7_999_990)
    ProcessRunner(lambda:False,log).run([sys.executable,'-c',"print('long output '*100)"])
    assert log.stat().st_size==8_000_000


def test_silent_engine_emits_an_honest_process_heartbeat(tmp_path,capsys):
    ProcessRunner(lambda:False,tmp_path/'engine.log',heartbeat_interval=.05).run([sys.executable,'-c','import time;time.sleep(.45)'])
    text=capsys.readouterr().out
    assert 'process still running' in text and 'not a completion estimate' in text


def test_modal_state_updates_bypass_negative_cancel_cache(tmp_path, monkeypatch):
    import modal_app
    ident='00000000-0000-0000-0000-000000000001'
    table={'job:'+ident:dict(id=ident,phase='running'),'cancel:'+ident:True}
    monkeypatch.setattr(modal_app,'table',table)
    monkeypatch.setattr(modal_app,'root',tmp_path)
    class CachedJobs(modal_app.ModalJobs):
        def cancelled(self,ident):return False
    result=CachedJobs().update(ident,phase='complete',artifacts=['splat'])
    assert result['phase']=='cancelled'


def test_process_heartbeat_reports_engine_liveness_without_turning_telemetry_outage_into_engine_failure(tmp_path):
    reports=[]
    def publish(engine,elapsed):
        reports.append((engine,elapsed))
        raise OSError('test telemetry outage')
    ProcessRunner(lambda:False,tmp_path/'engine.log',heartbeat_interval=.05,on_heartbeat=publish).run([sys.executable,'-c','import time;time.sleep(.45)'])
    assert reports[0][1]==0 and len(reports)>=2
    assert all(engine==Path(sys.executable).name for engine,_ in reports)


def test_modal_dataset_loading_failure_publishes_failed_state_before_reraising(tmp_path,monkeypatch):
    from types import SimpleNamespace
    import modal_app
    import pytest
    ident='00000000-0000-0000-0000-000000000001'
    table={'job:'+ident:dict(id=ident,phase='queued')}
    loading=[]
    def reload():
        loading.append(dict(table['job:'+ident]))
        raise OSError('test dataset volume unavailable')
    monkeypatch.setattr(modal_app,'table',table)
    monkeypatch.setattr(modal_app,'root',tmp_path)
    monkeypatch.setattr(modal_app,'volume',SimpleNamespace(reload=reload,commit=lambda:None))
    with pytest.raises(OSError,match='dataset volume'):
        modal_app.reconstruct.local(ident)
    assert loading[0]['stage']=='loading' and loading[0]['worker_started']>0
    assert table['job:'+ident]['phase']=='failed'
    assert 'storage failed' in table['job:'+ident]['message']
