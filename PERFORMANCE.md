# Monitoring cost checks (0.2.4)

These measurements are deterministic request-count simulations, not a GPU throughput or phone battery benchmark.

| Workload | Previous policy | Updated policy |
| --- | ---: | ---: |
| One unchanged queued job, inclusive 0–3600 second window | 1,201 status reads (fixed 3 seconds) | 242 status reads (5, 10, then 15 seconds) |
| 60 active-job HTTP status reads at 3-second intervals | 60 provider result probes, with two job-record reads per request | 12 provider probes, one fresh job-record read per pending response |
| 600 seconds, 5 cancellation checks per second | 3,000 remote flag reads | 600 remote flag reads |
| 600 seconds, 50 cancellation checks per second | 30,000 remote flag reads | 600 remote flag reads |

The first workload is about 80% fewer status requests. Node/native/Python tests check the exact counts with fake clocks; they do not claim measured production latency improvements. Status changes reset the phone interval. Unchanged running stages poll at 3–10 seconds; unchanged queued stages at 5–15 seconds. Completion notification may therefore lag up to the current polling interval plus network time; exceptional worker failures may take another reconciliation interval to surface.

Cancellation caching applies only to the worker's frequent flag checks, caches negative results for one second, and latches a positive result. Record updates bypass it, including completion after artifact commit. API cancellation still requests termination of the Modal call. Control-plane/network delays can add latency beyond the cache interval.

Read-only status requests retry up to six transient failures (2, 4, 8, 16, 30, 30 seconds between attempts; 30-second read timeout). Authentication, missing jobs, validation errors, and uploads are not automatically retried. After exhaustion, monitoring pauses and the saved job ID remains available for Resume/download.

Heartbeat timestamps stay in service memory between polls; unchanged status does not rewrite the state file or repost notifications. Updated stages/transfers are persisted. Modal L4 selection, CPU/memory requests, container cap, reconstruction presets, and export validation are unchanged. No idle GPU was requested, no deployment was triggered, and these optimizations cannot guarantee earlier GPU allocation.

Relevant checks: server/tests/test_monitoring.py and app/src/test/java/dev/doxi/strata/RemotePollPolicyTest.kt. Original policies are recorded in VERIFICATION-0.2.3.md and prior release source.

Engine logs now use a running byte budget and buffered writes, removing per-line file stat/flush work. A 500-line real subprocess regression observes zero log-path stat calls, retains its first and last lines, and publishes start/final output to the console. A near-limit file test stays within 8,000,000 bytes. Console output is throttled to about one line per second plus stage/start/final messages, with a process-alive heartbeat every 30 seconds for silent engines. These heartbeats do not assert GPU utilization or completed iterations. No reconstruction command, preset, or output validation was removed.
