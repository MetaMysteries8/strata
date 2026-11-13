import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from strata_server.api import create_app
from strata_server.monitoring import CancellationFlag, CallMonitor


@pytest.mark.parametrize('checks_per_second', [5, 50])
def test_remote_cancel_reads_are_bounded_under_silent_or_verbose_output(checks_per_second):
    reads = []
    now = [0]
    flag = CancellationFlag(lambda: reads.append(now[0]) or False, clock=lambda: now[0])
    for second in range(600):
        now[0] = second
        for _ in range(checks_per_second):
            assert not flag.check()
    assert len(reads) == 600


def test_cancel_cache_latches_true_and_force_bypasses_a_negative_read():
    now, value, reads = [0], [False], []
    def read():
        reads.append(now[0])
        return value[0]
    flag = CancellationFlag(read, clock=lambda: now[0])
    assert not flag.check()
    value[0] = True
    assert not flag.check()  # Bounded negative cache, under one second old.
    assert flag.check(force=True)
    value[0] = False
    assert flag.check() and len(reads) == 2


def test_cancel_cache_refreshes_at_deadline_and_does_not_swallow_read_failures():
    now, value = [0], [False]
    def read():
        if value[0] == 'error':
            raise OSError('control plane unavailable')
        return value[0]
    flag = CancellationFlag(read, clock=lambda: now[0])
    assert not flag.check()
    now[0] = 1
    value[0] = 'error'
    with pytest.raises(OSError):
        flag.check()
    value[0] = True
    assert flag.check()


def test_concurrent_cancel_checks_share_one_remote_read():
    reads = []
    flag = CancellationFlag(lambda: reads.append(1) or False, clock=lambda: 0)
    with ThreadPoolExecutor(max_workers=16) as pool:
        assert not any(pool.map(lambda _: flag.check(), range(100)))
    assert len(reads) == 1


def test_status_route_reads_a_fresh_record_once_and_throttles_only_provider_probe(tmp_path):
    reads, probes, now = [], [], [0]
    record = dict(id='00000000-0000-0000-0000-000000000001', phase='queued', artifacts=[])
    jobs = SimpleNamespace(get=lambda ident: reads.append(ident) or dict(record))
    def poll(ident):
        probes.append(ident)
        raise TimeoutError()
    monitor = CallMonitor(jobs, poll, clock=lambda: now[0])
    app = create_app(tmp_path, 'test-key-at-least-24-characters', jobs=jobs, status_hook=monitor)
    client = TestClient(app, headers={'X-Strata-Key': 'test-key-at-least-24-characters'})
    for i in range(60):
        now[0] = i * 3
        assert client.get('/v1/jobs/' + record['id']).json()['phase'] == 'queued'
    assert len(reads) == 60 and len(probes) == 12
    # Terminal records bypass the probe throttle and are immediately visible.
    record.update(phase='complete', artifacts=['splat'])
    assert client.get('/v1/jobs/' + record['id']).json()['artifacts'] == ['splat']
    assert len(probes) == 12


def test_monitor_distinguishes_transient_control_plane_errors_from_terminal_worker_failures():
    now, failures = [0], []
    record = {'phase': 'running'}
    jobs = SimpleNamespace(update=lambda ident, **changes: failures.append(changes) or changes, get=lambda ident: {'phase': 'complete'})
    class WorkerFailed(Exception): pass
    def transient(ident): raise OSError('network disconnected')
    monitor = CallMonitor(jobs, transient, terminal_errors=(WorkerFailed,), clock=lambda: now[0])
    assert 'monitorWarning' in monitor('job', record) and not failures
    now[0] = 15
    def failed(ident): raise WorkerFailed('test worker failure')
    monitor.poll = failed
    assert monitor('job', record)['phase'] == 'failed' and len(failures) == 1
    now[0] = 30
    monitor.poll = lambda ident: None
    assert monitor('job', record)['phase'] == 'complete'


def test_monitor_throttle_is_bounded_per_job_and_duplicate_requests_share_a_probe():
    started, release, probes = threading.Event(), threading.Event(), []
    def poll(ident):
        probes.append(ident)
        started.set()
        assert release.wait(3)
        raise TimeoutError()
    monitor = CallMonitor(None, poll, clock=lambda: 0)
    record = {'phase': 'queued'}
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(monitor, 'job', record)
        assert started.wait(3)
        assert monitor('job', record) == record
        release.set()
        assert first.result() == record
    assert probes == ['job']
    monitor.poll = lambda ident: (_ for _ in ()).throw(TimeoutError())
    for i in range(200):
        monitor(str(i), record)
    assert len(monitor.checks) == 128
