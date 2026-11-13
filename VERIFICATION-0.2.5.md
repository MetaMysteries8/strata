# Verification: Strata Preview 0.2.5

## Reproduction and checks

- Baseline Python suite: 45 tests passed before edits.
- Actual 0.2.4 worker function loaded from the released source ZIP, invoked with a fake volume raising during reload: reproduced a job left queued.
- Updated Python suite: 47 tests passed, including real subprocess callback publication failure without engine failure, and local Modal SDK invocation verifying loading stage/start time then failed job state on a volume error. The SDK warns that local invocation has no mounted cloud volume; all storage in this test is explicitly replaced by a test double.
- PASS: Final Android assembleDebug and testDebugUnitTest: 34 tests with zero failures/errors/skips.
- PASS: 13 Node theme, real viewer-loader, progress and heartbeat tests. JavaScript and shell syntax checks passed.
- PASS: APK v2 signature and ZIP alignment. Package dev.doxi.strata.preview, versionCode 8/versionName 0.2.5, certificate SHA-256 a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc matches earlier Preview releases.
- PASS: Exact embedded updated runtime, startup-error/heartbeat source, progress UI and fixture exclusion checked.

Total: 94 tests passed (34 Android, 47 Python, 13 Node).

## Scope and limits

No authenticated Modal request, deployment, live cloud job, CUDA/GPU training, physical Android device, or Termux ARM64 runtime was exercised. This patch does not prove the cause of the user's earlier apparent stall. Earlier browser fixture QA is retained in VERIFICATION-0.2.4.md; the new heartbeat text is covered by data/model tests, not a fresh browser session.

A worker heartbeat proves execution reached a reporting point, not GPU utilization or training completion. Reports may be delayed by network/control-plane outages; stale telemetry does not automatically fail or cancel a job. Existing running workers need no intervention and do not receive new source until a later deployment. Original on-device NaN failures remain unproven on the S25.
