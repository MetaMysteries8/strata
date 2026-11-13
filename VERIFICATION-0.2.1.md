# Verification: Strata Preview 0.2.1

This preview adds a persistent Dark/Light/System selector and a phone-local Termux/Ubuntu companion for the official Modal CLI browser login and deployment. It uses a separate application ID to retain the user's existing scans in Strata 0.2.0.

## Completed checks

- Android APK assembled successfully with the restored full JDK 21 / SDK 35 toolchain. All **25 Android JVM tests passed**, including the original 23 cases and two new Modal login/endpoint URL tests. This was a fresh build executing all 43 Gradle tasks.

- APK v2 signature and ZIP alignment verified. Packaged ID is dev.doxi.strata.preview, version 0.2.1/code 4; launcher resolves to dev.doxi.strata.MainActivity. Public development certificate SHA-256: a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc.
- APK contains the theme script and runtime ZIP, without a QA bridge or proprietary ScanKit library.
- 21 Python tests pass: the 14 existing reconstruction-server checks plus seven companion cases covering authentication/Origin rejection, actual subprocess login waiting and approval gating, cancellation, login URL allowlist, private credential file permissions/access, and healthy/unhealthy deployment publication. The login/deployment tests use explicit synthetic CLI/SDK fixtures, not a real signed-in Modal account.
- 7 Node tests pass: four theme tests for saved Dark, persistence, System changes, invalid/unavailable storage; three existing real GaussianSplats3D loader regressions.
- Shell syntax and Python syntax checks pass for the runtime installer/controller/deployer.
- Browser inspection at 390×844 verified Dark and Light rendering, saved Dark after reload, the Modal setup disclosure, disabled deployment before sign-in, and unavailable-runtime guidance. The browser fixture cannot authenticate or deploy. It is excluded from all deliverables.
- Modal 1.6.4 official CLI/SDK interfaces were inspected. The companion runs the actual `modal token new --expires-in 7d`, verifies with `modal token info`, and deploys through `modal deploy`; endpoint/proxy management uses public SDK methods. No custom private gRPC authentication is implemented.
- An anonymous live CLI challenge attempt did not return a login URL within its 15-second check and was cancelled. No account approval, real sign-in, or cloud deployment was completed here.

## Installation identity

The previous development signing key was lost when the transient workspace reset during the pause. This build uses application ID `dev.doxi.strata.preview`, label Strata Preview, version 0.2.1/code 4, and published AOSP development test signing material. It installs alongside the original. Keep Strata 0.2.0 and export/import original dataset ZIPs to move scans. It cannot install as an in-place update signed by the original certificate.

## Hardware and account limits

No physical Android phone/emulator, Termux ARM64 installation, authenticated Modal account, CUDA reconstruction, or cloud deployment has been exercised. Android browser handoff, Keystore storage, Termux background survival, the Ubuntu installation on Android 17, and full account login/deployment need a real device/account check. The implemented path is intended to work from the phone without a PC; passing mocked orchestration tests does not establish that end-to-end result.

The earlier native NaN reconstruction remains mitigated and strictly detected, not proven fixed on the S25. The inherited 0.2.0 verification is retained in VERIFICATION-0.2.0.md as historical evidence and does not describe this preview's application ID/certificate.
