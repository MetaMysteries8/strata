"""Bounded control-plane work; never cache completed job/artifact records."""
import threading
import time
from collections import OrderedDict


class CancellationFlag:
    """Cache negative reads briefly; a cancellation, once observed, stays set."""
    def __init__(self, read, interval=1.0, clock=time.monotonic):
        self.read, self.interval, self.clock = read, interval, clock
        self.value, self.next_check = False, float('-inf')
        self.lock = threading.Lock()

    def check(self, force=False):
        with self.lock:
            if self.value:
                return True
            now = self.clock()
            if force or now >= self.next_check:
                self.value = bool(self.read())
                self.next_check = self.clock() + self.interval
            return self.value


class CallMonitor:
    """Job records stay fresh; expensive provider result reconciliation is bounded."""
    def __init__(self, jobs, poll, terminal_errors=(), interval=15.0, clock=time.monotonic):
        self.jobs, self.poll, self.terminal_errors = jobs, poll, terminal_errors
        self.interval, self.clock = interval, clock
        self.checks = OrderedDict()
        self.lock = threading.Lock()

    def __call__(self, ident, record):
        with self.lock:
            if record['phase'] not in {'running', 'queued'}:
                self.checks.pop(ident, None)
                return record
            now = self.clock()
            if now < self.checks.get(ident, float('-inf')):
                return record
            self.checks[ident] = now + self.interval
            self.checks.move_to_end(ident)
            while len(self.checks) > 128:
                self.checks.popitem(last=False)
        try:
            self.poll(ident)
        except (KeyError, TimeoutError):
            return record
        except self.terminal_errors as exc:
            return self.jobs.update(ident, phase='failed', message='Modal worker ended: ' + str(exc)[-1200:])
        except Exception:
            # A control-plane outage is not evidence that reconstruction failed.
            return {**record, 'monitorWarning': 'Worker status check unavailable; retrying.'}
        return self.jobs.get(ident)
