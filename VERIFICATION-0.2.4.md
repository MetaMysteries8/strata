# Verification: Strata Preview 0.2.4

Baseline server suite: 30 tests passed before changes. New checks cover cancellation read budgets, fresh records with throttled reconciliation, concurrent probes, transient versus terminal errors, restart without package installation, expiry parsing, adaptive polling, retries, stage models, and runtime version compatibility. 

Completed checks:

- PASS: Android assembleDebug and testDebugUnitTest: 32 tests, zero failures/errors/skips, including the real generated setup/restart command syntax checks and polling/retry policies.
- PASS: Node suite: 12 tests covering themes, actual Gaussian loader bytes, stage descriptions, clock skew, and runtime version compatibility.
- PASS: Python suite including monitoring, restart, expiry, real subprocess output/byte-budget/heartbeat checks, and cancellation transitions; 45 tests passed, with no failures/skips.
- PASS: 390-pixel browser fixture: queued progress ages, combined mesh/splat checklist, Copy job ID control, Dark theme, separate restart section, stopped-runtime guidance, reconnect state, expiry display, and Renew action changing to approval-waiting state. The clearly marked synthetic bridge does not authenticate or deploy. It is excluded from deliverables.
- Server/provider integration uses test doubles and local Modal SDK declaration imports only. No cloud request was made by these tests.


Phone progress is stage-based; no GPU ETA, global queue position, or server training percentage is invented. Real Android/Termux ARM64 installation, authenticated Modal renewal/deployment, and CUDA training were not executed this release. The user's screenshots establish that their earlier deployed API responds and calls are queued; they do not validate this update. No current job was modified or redeployed.

Native NaN scan fidelity remains unproven on the S25. See historical verification files and PERFORMANCE.md for limitations.

Final totals: 32 Android + 45 Python + 12 Node = 89 checks passed. APK v2 signature, ZIP alignment, preview package/versionCode 7/versionName 0.2.4, matching prior Preview certificate, embedded latest runtime/start.sh/progress assets, and fixture exclusion passed after final assembly.
