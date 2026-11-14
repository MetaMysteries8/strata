# Same-origin redirects: 0.3.2

ServerHttp now follows bounded same-origin redirects for status, upload results and artifact downloads. 303 changes POST to GET; 307/308 preserve streamed bytes/idempotency keys with bounded replays. Cross-origin/unsafe destinations remain blocked without leaking credentials/query tokens. Existing saved endpoint/job identity is retained. Response timeouts allow Modal's 150-second result handoff; status polling keeps its own shorter timeout. This APK fix needs no cloud redeploy and is tested with synthetic local servers/connection fixtures.

# Mesh failure recovery: 0.3.1

The reported empty-triangle failure is now recoverable where COLMAP's alternate Delaunay mesher produces a valid surface. Dense point/normal diagnostics and finite/nondegenerate mesh checks were added. Both mode handles mesh/splat errors independently; usable outputs and dense points remain downloadable with partial warnings and per-output outcomes. Android preserves those warnings and exposes dense-point PLY export. Cancellation still stops further work. No live scan retry or cloud deployment was performed.

# Repository release work: 0.3.0

Implemented GitHub Actions builds/releases, one version source in release.json, generated asset preparation with a pinned native dependency checksum, verified APK/runtime manifests, and Android update checking/download/install controls. Preview package/signature is retained. Updates require user download/install actions; APK checks do not change cloud deployments.

The authenticated Termux companion can stage and verify the latest server runtime from GitHub, check current-table active jobs, deploy the existing Modal resource names, verify endpoint version, and remember successful source for subsequent deployments. Existing companions need one refreshed ZIP/setup; no full Linux reinstall is needed. Restart and token-renewal controls remain available.

Local verification: 112 automated tests plus APK build. Physical device and live Modal/GPU execution remain untested. Video streaming is a future feature recorded in ROADMAP.md.

# Strata Preview 0.2.6 handoff

Audited the apparently pending Modal call. Confirmed per-install resource names were passed only to the local deploy process and not preserved in remote image environments. Fixed both images with an explicit four-name mapping; credentials are excluded. Dict namespace fallback is the strongest finding because Modal rehydrates explicitly declared volume dependencies. Added separate 300-second initialization timeout instead of inheriting the 7200-second reconstruction timeout.

The exact cause of the user's pending call remains unproven. No live deployment, cancellation, GPU request or account mutation was made. Old default-table records are not automatically migrated, and no resources are deleted. Handle old calls before deploying the correction; keep original captures and submit a fresh reconstruction afterward. The 0.2.5 app remains compatible with the external fixed runtime ZIP. See DEPLOYMENT-AUDIT.md and VERIFICATION.md.
