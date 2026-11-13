# Verification: Strata Preview 0.2.2

## Changes checked

- Account verification uses a registered, cancellable process group. SIGTERM-resistant processes are forcibly stopped after a bounded wait; cancellation cannot subsequently become a successful verification/deployment.
- Cached account verification is rechecked after five minutes, with bounded retry frequency after a failure.
- Modal deployment configuration is isolated by provider-reported workspace ID. An older 0.2.1 endpoint is reused only when Modal confirms that its proxy token belongs to the current workspace. Other workspace credentials are not returned through the runtime connection endpoint.
- Automatic endpoint connection validates its health before replacing the saved server profile. Endpoint credentials are validated before use.
- Copy setup diagnostics includes only whitelisted runtime/phase/version information. Provider error responses are converted to safe categories; raw CLI logs stay in private runtime storage. Actual stages and cancelled-deployment messages are displayed.

## Completed checks

- Baseline: all 21 Python tests from 0.2.1 passed before changes.
- Updated Python suite: 30 tests pass, including a real subprocess that ignores SIGTERM, stale verification/retry limits and explicit manual retries, workspace config isolation, provider-confirmed legacy migration, connection/diagnostic auth boundaries, cancellation over an existing endpoint, and safe error messages. Provider APIs use explicit test doubles; no authenticated Modal account was exercised.
- Final Android assembleDebug and testDebugUnitTest passed: 27 tests, zero failures/errors/skips. The rebuilt APK embeds the exact updated phone runtime ZIP and the Recheck account control.
- APK v2 signature, ZIP alignment, package/version, and signer continuity with Preview 0.2.1 verified. Certificate SHA-256: a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc.
- All seven Node checks passed: four theme checks and three real GaussianSplats3D loader regressions. JavaScript and shell syntax checks passed.
- An earlier Python run during the cold Android build exceeded the fake-login fixture’s five-second deadline. The focused case then passed in 0.61 seconds, and the complete unchanged suite passed in 5.96 seconds after the build completed. No timeout or assertion was weakened.
- Browser inspection in an isolated synthetic fixture confirmed stage text, the new diagnostic control, cancelled-deployment feedback, and Dark theme at phone width. It does not establish account sign-in or cloud execution. The fixture is excluded from deliverables.

## Installation and limits

Application ID remains dev.doxi.strata.preview. Version is 0.2.2/code 5. It uses the same published AOSP development test certificate as Preview 0.2.1, allowing an in-place update of that preview. The original Strata 0.2.0 remains a separate app with a different certificate; keep it until its scans are backed up/migrated.

Phone runtime changes require updating Strata-Modal-Android.zip and restarting its setup script after stopping the old runtime with Ctrl+C. The app warns when an older runtime is connected. CLI credentials and data remain in private Termux/Ubuntu storage.

No physical Android device, emulator, Termux ARM64 environment, authenticated Modal account, deployment, or CUDA training was exercised. Full browser handoff/account approval and cloud reconstruction still require a device/account test. The original native NaN failure remains mitigated/detected rather than proven fixed on the S25. Historical checks are in VERIFICATION-0.2.1.md and VERIFICATION-0.2.0.md.
