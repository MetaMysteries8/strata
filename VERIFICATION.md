# Verification: Preview 0.3.3

Local server checks: 85 tests pass. Local viewer/UI checks: 16 tests pass. New saved-work regressions exercise CPU-only recovery, authenticated artifact downloads, auth and active/cancelled-job rejection, provider-idle checks, unchanged job identity, no duplicate dispatch, unreadable/escaped checkpoints, atomic snapshots surviving original deletion, bounded snapshot retention, a real subprocess stopped by the soft deadline, mesh commit before training, continuation with only remaining iterations and no COLMAP, export-only continuation, engine availability, and refusing fresh training when an expected checkpoint disappears. Synthetic Torch ZIP fixtures are never deserialized by CPU recovery.

New native/JavaScript checks cover deadline/checkpoint telemetry, partial recovery metadata, and paused versus failed stages. The release workflow runs Android build/JVM tests and verifies the retained APK signature/alignment. Nerfstudio resume/export flags and additional-iteration semantics were checked against pinned 1.1.5 source; actual CUDA checkpoint loading/export, device installation, live Modal file survival and recovery remain untested. No live GPU request, deployment or original user data was accessed. Completed-output recovery depends on committed storage; it is not guaranteed to restore this user's timed-out job.

Prior verification follows as historical evidence.

# Verification: Preview 0.3.2

Eight new Android JVM regressions exercise the actual ServerHttp client: query-only GET 303 retaining the job path/auth headers, upload POST 303 switching to GET without replay, upload 307/308 preserving exact 130,000-byte input and request ID, redirected artifact byte equality, unsafe-origin/malformed destination rejection without query-secret exposure, no second connection for blocked hosts, repeated-result redirect bounds and cancellation, and POST replay bounds. The streaming cases use local TCP HTTP servers; pure HTTPS policy/bounds cases use injected HttpURLConnection fixtures. They do not contact the user's Modal endpoint.

The release workflow builds/tests the APK with its existing Preview signature and runs server/viewer checks. The reported screenshot confirms a redirect response but not its status/Location: actual provider redirect type and real phone reconnection are unverified until tested on-device. No live Modal account deployment, scan upload or GPU task was performed for this client fix.

Prior verification follows as historical evidence.

# Verification: Preview 0.3.1

Local Python checks: 69 passed, including eight mesh-recovery regressions. Local UI checks: 11 passed. The unchanged viewer loader checks and Android build/JVM tests are run by the release workflow. One new JVM test checks retained partial-result warnings; one new UI test checks failed/skipped stages.

Recovery tests use synthetic real PLY point clouds/triangle surfaces and mock external commands. They reproduce an empty surface, validate Delaunay workspace arguments, parse exported GLB triangles, check mesh-fails/splat-succeeds and mesh-succeeds/splat-fails, permit authenticated partial artifact downloads while rejecting nonexistent meshes, fall back to original poses after alignment failure, preserve dense-only results, retain truthful failure with no artifacts, and stop after cancellation. The reported user's cloud/workspace was not accessible: actual empty-surface cause, CGAL availability, real GPU behavior, quality, and phone installation remain unverified here.

Prior verification follows as historical evidence.

# Verification: Preview 0.3.0

Local checks: 38 Android JVM tests, 61 Python tests and 13 JavaScript tests pass (112 total). Android debug APK builds with SDK 35/JDK 21. The packaging script checks the APK package/version and retained Preview certificate before generating release hashes.

New regressions exercise release metadata validation, signer/package/version mismatch, HTTPS host restrictions, safe/checksummed runtime staging, traversal/duplicate ZIP paths, pairing-required update POST, active-job blocking, existing resource-name reuse, and saved endpoint/source changes only after verified health. Deployment tests mock provider operations; they do not deploy cloud resources. Viewer tests load actual Gaussian bytes with the query-string regression.

GitHub Actions reproduces preparation, tests, APK build/signature/alignment checks and release packaging. Publication occurs only on a passing main build and only for a new version; a draft from an interrupted upload can be completed. No Modal secrets or real account credentials are uploaded. Published releases are not overwritten.

Not exercised here: physical Android installation/update confirmation, camera/training quality on S25, authenticated Modal deployment, GPU reconstruction, queue allocation time or billing. Unit/build results do not establish those outcomes. Existing pending calls are not modified. The companion must be refreshed once to 0.3.0 before its GitHub deployment-update endpoint is available.

Prior checks are retained below as historical evidence; their version labels and limits remain specific to that release.

# Verification: Strata Preview 0.2.6

- Before patch: two new deployment regressions failed, 47 existing Python tests passed.
- After patch: all 49 Python tests passed. Actual pinned SDK Image.env and App.function methods are wrapped to inspect configuration; a simulated fresh import preserves the configured resource identities, and secret values are excluded. The tests inspect actual App.function keyword arguments through the pinned SDK.
- Separate subprocess imports of the released configuration reproduced generated names in the deploy process versus generic fallback names without the environment.
- PASS: Final Android assembleDebug and testDebugUnitTest: 34 tests, no failures/errors/skips.
- PASS: 13 Node theme, real-loader, and progress tests.
- PASS: APK v2 signature, ZIP alignment, package dev.doxi.strata.preview/versionCode 9/versionName 0.2.6, same AOSP Preview certificate, exact embedded runtime with both env mappings/startup timeout, and QA fixture exclusion.

Total: 96 automated tests passed (34 Android, 49 Python, 13 Node). The APK changes only version metadata and the bundled runtime; no app interaction logic was changed.

No authenticated Modal access, deployment, cancellation, GPU execution, live image toolchain, Android device, or Termux ARM64 run was performed. There is no claim that the specific user's scheduling delay was diagnosed. New behavior requires redeployment; old records may be in the default job table and are not automatically migrated. No volume, job table or application is deleted by this change. Original NaN capture fidelity remains unproven on the S25.
