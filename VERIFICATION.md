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
