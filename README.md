# strata
vibe coded app cause bored

# Strata Preview 0.3.2

An independent Android scanner with local RGB/pose/depth capture, experimental on-device Gaussian training, an offline splat/mesh viewer, and optional reconstruction on your own GPU server. No Scaniverse libraries or assets are included.

## Server redirect handling (0.3.2)

Previous APKs rejected every HTTP redirect with a final-URL error. The client now follows up to 20 redirects on the same origin (same protocol, hostname and effective port), including Modal-style 303 result URLs and relative/query-only locations. Credentials remain scoped to that origin. Redirects to another host, a different protocol/port, embedded user credentials, fragments and malformed destinations are blocked before the next request. Error messages do not include redirect query tokens. The screenshot did not include its Location header, so its exact redirect type/destination is unconfirmed.

After a 303 the client retrieves the result with GET without uploading again. Upload 307/308 redirects preserve the streamed bytes and X-Request-ID; at most three POST replays are allowed. Ambiguous POST 301/302 redirects are rejected. Downloads use the same redirect checks and keep their existing size/truncation checks and atomic save. Default response timeouts are three minutes to permit Modal's documented 150-second result redirect; status polling retains its shorter timeout/backoff. Redirect URLs are temporary request state and do not replace the saved server endpoint.

Install this APK, then tap Resume / download server job for the existing job. This client fix needs no server redeployment or Termux reinstall. It does not start a new reconstruction automatically. Your existing local splat remains available. A blocked cross-origin redirect still needs investigation of the configured endpoint rather than automatic forwarding of account credentials.

References: [Modal request timeouts](https://modal.com/docs/guide/webhook-timeouts), [RFC 9110 redirect semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.4).

## Mesh recovery and partial results (0.3.1)

An exit code of zero from COLMAP does not guarantee a usable surface. The reported failure occurred when the exported surface had no triangles. New workers check finite dense-cloud coordinates, report point/normal counts, validate nondegenerate triangle geometry, and try COLMAP Delaunay meshing using the full dense workspace if Poisson returns an invalid surface or exits with an error. Delaunay needs a COLMAP build with CGAL; an unavailable or unsuccessful fallback is reported explicitly. This does not prove the cause of the reported capture's empty surface or guarantee a high-quality mesh.

In Both mode, a handled mesh failure now permits the requested splat training to continue; successful COLMAP camera alignment is reused, or original posed input is retained when alignment fails. A later splat failure keeps completed mesh exports. Valid dense points are retained independently and exportable as PLY. Available outputs finish with an explicit partial-result warning and per-output statuses; no available output still fails. Cancellation stops the pipeline without starting a fallback. Mesh-only failures can return dense points as partial results, without claiming a mesh exists. The app labels failed/skipped stages and preserves the failure summary after download.

Install the APK through App updates. Finish/cancel active server jobs, then use Modal account → Update server from GitHub (requires the existing 0.3.0+ companion). The APK alone does not change deployed code. The failed job is not automatically retried; start a new reconstruction from its retained original capture after deploying. No account login, live deployment, GPU execution, or automatic scan upload was performed to develop this fix.

## Get updates on your phone

Download the APK from [Latest release](https://github.com/MetaMysteries8/strata/releases/latest) and install over Strata Preview. Starting with 0.3.0, Settings → App updates checks this public repository at most every 12 hours when opened/resumed, with a manual check and an automatic-check toggle. Download and installation each require your action. Android may ask you to allow installs from Strata; return and tap Install update afterward. Finish local training first. Checksums and installed signing identity are verified; Android provides the final install confirmation. A public development certificate is not production key security.

For an existing phone Modal runtime, stop it with Ctrl+C in Termux, download the new `Strata-Modal-Android.zip` to Downloads (same exact filename), then run the app's setup command once. This keeps Ubuntu, credentials, deployment names and data. Return to Settings → Modal account → Check connection. Runtime 0.3.0 adds **Update server from GitHub**: explicitly confirm it to fetch and verify the published runtime ZIP, stage private source, deploy the existing Modal app, and verify endpoint health/version. A failed check does not switch the saved endpoint/source pointer. Already-applied cloud changes cannot be rolled back automatically.

Later server updates use that button without manually unpacking ZIPs. Routine **Deploy and connect** uses the last successful source. Restart runtime service remains available after closing Termux/rebooting; Renew Modal token repeats official browser approval when needed. Companion updates that require a newer CLI/protocol can still require the one-time setup procedure. Finish/cancel active jobs first; updates check the current workspace's configured job table, but cannot detect legacy jobs in another table or account. Existing 0.2.x generic-table jobs require manual review as described in DEPLOYMENT-AUDIT.md. No cloud deployment is triggered by checking app updates or installing an APK.

## Deployment configuration correction (0.2.6)

The audit found that generated resource names were only set in the local deploy shell. Both container images now preserve those names explicitly, so a remote import retains the correct App/Dict/Volume/Secret-name configuration. Only resource names go into the image; credentials do not. GPU initialization now has a separate 300-second startup timeout while reconstruction keeps its 7200-second execution limit. Scheduling wait is not bounded by that startup timeout.

Read DEPLOYMENT-AUDIT.md before updating an existing active scan. Older records may live in the generic fallback Dict; there is no automatic active-job migration or deletion. Handle an old call before deploying this correction, retain original captures, and use a fresh reconstruction afterward. The existing 0.2.5 app can use the external fixed runtime ZIP; 0.2.6 bundles it for the Save runtime ZIP button. No full Ubuntu reinstall is necessary.

This confirmed defect is not proof of the cause of a particular pending call. No live account or GPU action was performed. Hardware request sizes and the one-worker cap are unchanged.

## Worker liveness and loading failures (0.2.5)

The dashboard distinguishes Last server contact (the HTTP API replied) from Worker last reported (worker execution published a heartbeat). Running engines report their command name and elapsed process runtime at startup and about every 30 seconds. Stage transitions refresh the worker timestamp. A heartbeat is not GPU utilization or a training percentage. After 90 seconds without a report, the app suggests checking provider logs/metrics and does not auto-cancel. Older servers show that heartbeat data is unavailable.

The worker publishes a Loading capture stage before volume reload. Dataset load, pipeline import, and storage exceptions inside this worker boundary are recorded as failed rather than leaving a queued record indefinitely. Final best-effort volume commit errors are logged without replacing the already-recorded failure. Telemetry publication failures leave the actual engine running.

Reproduced the released 0.2.4 failure using its actual worker source with a simulated volume error; the saved phase remained queued. The new regression uses a local Modal SDK invocation with a fake volume and verifies a failed record. This is not a diagnosis of the user's live scan.

APK updates retain Preview data/signing identity. Server-side fixes need updated runtime source and a later deployment; already-running old workers are unaffected. Check active jobs before redeploying. The future video-streaming workflow remains in ROADMAP.md.

## Progress and runtime maintenance (0.2.4)

Processing and scan details now show named server stages, submitted age, last successful server contact, a stage checklist, and a copyable job ID. Queue/training stages have no invented percentage or ETA; byte transfers and native training steps retain actual progress. A Modal dashboard button opens the provider's site. Updated server workers also print entry/stage/engine messages and process-alive heartbeats to Modal logs; older workers only captured engine output in a private file. A startup license banner by itself cannot diagnose a hang.

Settings → Modal account has a dedicated **Restart runtime service** section: Copy restart command → Open Termux → paste → return and Check connection. The new start.sh reuses installed Ubuntu/Python and private data, without running package installation itself. The Linux launcher can repair/update Python dependencies if its version marker is missing. Older runtime installs fall back to their existing setup script. Keep the Termux session open while using account/deployment controls. Already-deployed server scans and direct app/server communication do not need the phone companion to remain running.

**Modal token and account access** offers Renew Modal token and Recheck account. Renew opens the official CLI's browser approval again, requesting up to seven days subject to workspace policy. It neither redeploys nor uploads scans, and does not automatically revoke older tokens. Where reported by the CLI, the verified expiry date appears. The account token, loopback pairing key, server access key, and proxy token are distinct.

Status reads back off while unchanged and recover from temporary network/HTTP failures without resubmitting uploads. Modal worker cancellation reads are bounded to one per second in the hot loop; stage/completion transitions perform fresh checks. Reconciliation probes are limited to every 15 seconds per active job while job records remain fresh. See PERFORMANCE.md for measured request counts and limits.

Install the APK over an existing Preview. Let an in-flight scan finish before updating the app or redeploying the server. To update server-side behavior later, stop the phone companion with Ctrl+C, save the new Strata-Modal-Android.zip into Downloads, rerun one-time setup to replace code, then use Deploy and connect after checking existing cloud jobs. No deployment was performed during development.

ROADMAP.md records the requested future video-to-server capture workflow; it is not implemented in this release.

## Setup command hotfix (0.2.3)

The Copy command button in previous builds omitted the final double quote, leaving Termux at a `>` continuation prompt before any setup ran. This release constructs the command with explicit shell quotes and checks the actual generated string with Bash syntax validation. At the stuck prompt, press Ctrl+C, update this APK, then copy and paste the command again. No runtime reinstall or scan deletion is required; runtime version 0.2.2 remains current.

The APK updates Preview 0.2.1 or 0.2.2 in place using the same package and signing identity.

## Update from Preview 0.2.1

Install this APK over **Strata Preview 0.2.1**; it retains the preview package ID and signing certificate. Saved scans and theme settings can remain in place. For the phone Modal fixes, stop the old runtime with Ctrl+C in Termux, replace the Downloads ZIP with the new Strata-Modal-Android.zip, and rerun the setup command. This reuses private runtime credentials/data. There is no need to delete the Ubuntu environment.

Version 0.2.2 adds cancellable account verification, stale-token rechecks plus an explicit Recheck account control, workspace-isolated deployment credentials, health checks before automatic server replacement, stage feedback, and Copy setup diagnostics. A copied diagnostic contains no account/endpoint keys. Signing out of the CLI still leaves previously paired endpoint credentials available; the separate server settings determine where scans upload.

## Install and retain existing scans

This build installs **alongside** Strata 0.2.0 as **Strata Preview**, application ID `dev.doxi.strata.preview`. The original development signing key did not survive the temporary workspace reset. Keep the old app installed; export Original dataset ZIP there, then import it into Preview. Do not uninstall the old app before backing up its scans.

The preview uses published AOSP development test signing material for a stable development identity; see development-signing/README.md. Production builds should use an owner's retained release key.

## Dark mode and Modal on the phone

Settings → Appearance offers **Dark**, **Light**, and **Use system setting**. The choice persists, applies before the library renders, and System follows device theme changes.

Settings → Modal account contains phone runtime setup, **Sign in with Modal**, and **Deploy and connect**. It uses Termux plus an Ubuntu userland to run the official Modal CLI. The phone only orchestrates login/deployment; CUDA reconstruction runs in your Modal account. A PC is unnecessary, but Termux is a separate one-time install. See server/android_runtime/README.md.

The reported export has zero positions and NaN parameters. Its shape cannot be recovered from that PLY. Retraining requires the saved photos and poses; this update keeps them. Existing invalid results are detected and remain available as diagnostic exports.

The viewer had a separate bug: GaussianSplats3D 0.4.7 treated `/model.ply?id=...` as an unsupported extension. This release supplies the explicit PLY format and uses `/scans/{id}/model.ply` routes. Fixing a URL does not repair invalid model numbers.

## Capture and processing

- ARCore tracking, calibrated RGB images, OpenGL camera-to-world poses, and selected keyframes. Camera session runs at 30 FPS; selected photo commits are limited to about 3.3 FPS by time/motion/quality gates, with at most 240 frames. This is **not** Niantic's documented full-resolution 15 FPS recording pipeline.
- Raw Depth and confidence when available, preserved as lossless 16-bit millimeter and 8-bit confidence PNGs with separate depth calibration/pose. Depth works on supported ARCore phones without a dedicated LiDAR sensor.
- Sparse landmarks and confident depth samples merged into 4 mm spatial cells, up to 120,000 colored seed points. Up to 8,000 stored points are projected over the live camera. This shows observed geometry, not a complete coverage estimate or a final dense surface.
- Atomic RGB/pose saves and independent optional depth saving. A depth failure does not discard a committed photo. Original dataset export remains available after failed training.
- A white scan library with thumbnails, Splats/Meshes filters, Processing, Guide, Settings, project sheets, reconstruction targets, and explicit export choices.
- Local Gaussian viewer and GLB triangle mesh viewer, with orbit, fit, backdrop, up-axis, point display or mesh wireframe. No CDN assets are required.

## Phone training

The separately licensed Brush AAR uses the phone GPU. It remains experimental, especially because the reported device produced a numeric failure. This release creates finite Gaussian seed scales/colors/opacities/identity rotations before native training, adds a conservative preset, and validates every exported parameter, quaternion, payload length, and position extent. It never labels an invalid or collapsed result as successful. These measures are a mitigation; the precise native GPU failure has not been traced or proven fixed on a phone.

| Phone preset | Iterations | Maximum splats | Long edge | SH degree |
| --- | ---: | ---: | ---: | ---: |
| Compatibility | 5,000 | 150,000 | 768 px | 0 |
| Quick | 4,000 | 150,000 | 768 px | 2 |
| Detail | 12,000 | 400,000 | 1,024 px | 3 |
| Maximum | 24,000 | 800,000 | 1,440 px | 3 |

Compatibility disables SSIM/mean noise and lowers optimization rates. The other presets retain their previous budgets. These are caps, not quality or memory guarantees. The mobile viewer renders up to SH degree 2; exported PLY retains all coefficients. Training cancellation releases the separate native process; originals and completed exports are retained. A critical thermal state stops training.

## Optional GPU server and actual 3D models

See [server/README.md](server/README.md). The included FastAPI service has authenticated upload, a persistent serial job queue, cancellation, status, and downloads. Pair its URL and access key in Settings, then explicitly confirm uploading the original dataset. Credentials are encrypted with Android Keystore and are not stored in JavaScript.

- **Splat:** Nerfstudio Splatfacto using recorded poses/seed points, with camera pose optimization. Quick/Detail/Maximum run 7,000/30,000/50,000 iterations; Maximum uses Splatfacto-big.
- **Mesh:** COLMAP photo matching and bundle adjustment, dense multi-view stereo, depth fusion, Poisson surface reconstruction, and vertex colors transferred from the dense cloud. Export GLB, OBJ, STL, and dense point PLY.
- **Both:** mesh reconstruction followed by splat training from the COLMAP-aligned dataset. Its coordinate scale is arbitrary; measurement accuracy and alignment to the original ARCore world are not guaranteed.

Raw depth is retained in the dataset and contributes phone seed points. Server Splatfacto is RGB supervised; its training manifest omits optional raw-depth references because their calibration differs from the RGB images. The mesh path computes its own multi-view depth. No depth-supervised loss or direct TSDF surface fusion is claimed.

The Modal option is real deployment source with host-side Modal browser login, a protected API, background L4 worker, durable artifacts, and reconnect/download. It requires your own account and deployment. The phone sign-in button opens the genuine Modal CLI browser approval flow through the loopback companion. No account login, deployment, billing action, or GPU reconstruction was completed during development. GPU/server storage may incur charges when you deploy/use it.

The APK now has Internet permission for optional server requests. Capture, phone training, and viewing still use local data. Plain HTTP is limited to numeric private LAN/loopback addresses; remote services require HTTPS. Use HTTP only on a trusted network. Deleting a phone scan does not delete its copies on a server; server cleanup is managed by its owner.

## Dataset import

Import a posed Nerfstudio ZIP with root `transforms.json` and 12–600 calibrated frames. Limit: 600 MiB compressed, 2 GiB expanded, 2,500 entries. Referenced images must exist. Unposed photos/video are not supported by the phone importer.

The manifest uses OpenGL camera-to-world 4×4 row-major matrices: +X right, +Y up, −Z forward; per-image or global `w`, `h`, `fl_x`, `fl_y`, `cx`, `cy`; image `file_path`; optional `ply_file_path`. Strata exports per-frame RGB calibration. Optional depth has its own `depth_intrinsics`, `depth_transform_matrix`, and `depth_unit_scale_factor=0.001`; do not assume a raw-depth pixel shares the RGB pixel coordinates.

## Build and tests

Use JDK 21, Python 3.12, Node 24, Android SDK Platform 35 and Build Tools 35.0.0. Set `ANDROID_HOME`, then:

```sh
npm ci --prefix viewer-src
npm run build --prefix viewer-src
python scripts/prepare.py
bash gradlew :app:assembleDebug :app:testDebugUnitTest
```

The preparation script downloads the pinned Brush AAR and verifies SHA-256, stamps versions from `release.json`, and builds the bundled phone runtime ZIP. Generated AAR/viewer/runtime assets are excluded from git. Output: `app/build/outputs/apk/debug/app-debug.apk`.

Server tests: install `server/requirements.txt`, `server/android_runtime/requirements.txt`, pytest and httpx, then `PYTHONPATH=server python -m pytest -q server/tests`. Viewer/UI tests: `node --test ui-tests/*.test.mjs viewer-src/tests/*.test.mjs`. `python scripts/package_release.py` checks the APK identity and produces APK/runtime/manifest/checksums in `dist/`.

The GitHub workflow runs these checks for PRs and main pushes. A successful main build publishes a new version from `release.json` only when that tag is absent; published assets are not overwritten. Increment both version fields before releasing changed code. No Modal credentials belong in GitHub or CI. Preview builds use public AOSP test signing material; use an owner's retained private key for a production app with a different package identity.

## Provenance and limits

- App source: Kotlin platform Activities, restricted local WebView UI, native camera preview, isolated phone trainer and native HTTP foreground service.
- ARCore 1.48.0; Brush AAR from `SuleMareVientu/opensplat-android` commit `1ea9bbb829d0a4d4a5e4783d3fec1eeb71834d90`, Apache 2.0. Source/API reference: `SuleMareVientu/brush-android` branch `aar`, commit `dddf54a102f72f56a21aebce6d102c739135d3cf`. The upstream prebuilt binary is not claimed bit-for-bit reproducible.
- GaussianSplats3D 0.4.7, Three.js 0.170.0, esbuild 0.25.0; license texts in `third-party/`. New Strata source is MIT. Server dependencies keep their own licenses.
- No on-phone global bundle adjustment, loop closure, dense surface fusion, texture atlas generation, automatic watertight mesh repair, or Scaniverse parity claim.
- No physical Android device, CUDA GPU, Docker GPU runtime, or authenticated Modal account was exercised. Browser WebGL was unavailable. Builds/unit tests do not prove hardware performance or scan fidelity.

[SCAN_PIPELINE.md](SCAN_PIPELINE.md) explains what is known about Scaniverse, what the supplied binary reveals, and the remaining gaps. [VERIFICATION.md](VERIFICATION.md) records the completed checks.
