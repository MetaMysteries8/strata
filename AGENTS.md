# Strata development

Preserve dev.doxi.strata.preview package/signing identity for Preview upgrades. The published AOSP test certificate is development material, never a production key. Never commit real account tokens, endpoint keys, captures, local runtime state or build caches.

Release versions live in release.json; increase versionName and versionCode for a new published release. Published GitHub release assets remain immutable. Run npm ci/build in viewer-src and python scripts/prepare.py before Android builds; generated AAR/viewer/runtime assets are gitignored. Do not vendor the 41 MB native engine: preparation downloads a pinned commit and verifies SHA-256.

Checks: server requirements plus android_runtime/requirements.txt, pytest/httpx; PYTHONPATH=server python -m pytest -q server/tests; node --test ui-tests/*.test.mjs viewer-src/tests/*.test.mjs; bash gradlew :app:assembleDebug :app:testDebugUnitTest (JDK 21, SDK 35). Package with python scripts/package_release.py and ANDROID_HOME set.

Keep capture/viewing local unless the user explicitly submits a remote scan. Public release checks never receive Modal credentials and never trigger a cloud deployment. Preserve active-job checks, protected loopback operations, explicit deployment confirmation, safe ZIP/checksum validation and endpoint health verification. Test cloud orchestration with mocks unless the user explicitly requests a real deployment. Do not claim Scaniverse parity or that a JVM build proves physical GPU correctness.

Requested video streaming remains future scope in ROADMAP.md.
