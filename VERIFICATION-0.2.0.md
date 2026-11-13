# Verification: Strata 0.2.0

## Completed checks

- Android debug APK built with Gradle 8.11.1 / AGP 8.9.2 / Kotlin 2.2.0 / SDK 35 / JDK 21.
- **23 Android JVM tests passed**, including the prior capture/import/YUV/atomic-save tests and Android native-graphics JPEG fallback. New tests reject a synthetic reproduction of the reported 161-NaN-splat result, collapsed geometry and truncated PLY; verify finite Gaussian seed conversion and lossless 16-bit depth PNG; validate server address restrictions; exercise real HttpURLConnection authentication, streamed ZIP upload/idempotency header and exact artifact download against a loopback HTTP server; ensure dense point-cloud artifacts cannot replace the selected Gaussian model.
- **3 Node tests passed** using the real GaussianSplats3D loader: reproduce query-string format inference failure, parse genuine test Gaussian PLY bytes over HTTP with an explicit SceneFormat, and reject invalid IDs. This is a CPU loader test, not a GPU rendering test.
- **14 Python server tests passed**: auth before body handling, actual validated ZIP extraction, request idempotency, unsafe/missing images rejection, durable cancellation, completed-artifact access, cancellation of a real silent subprocess, actual trimesh surface conversion/export and GLB/STL reload, binary/ASCII NaN/rotation/extent/truncation checks, missing-engine error handling, and original-preserving raw-depth training-manifest preparation.
- Server CLI actually started on loopback and answered authenticated HTTP health with protocol 1. It accurately reported absent reconstruction executables in this environment; no synthetic GPU job was reported as complete.
- Python compile checks, app JavaScript syntax checks, and viewer bundling succeeded.
- Modal 1.6.4 imports the deployment definition without account authentication, deployment, or resource creation. Secret creation/cancellation signatures were checked against installed SDK and official documentation. Nerfstudio options were checked against the pinned v1.1.5 source; an option absent from that version was removed.
- Browser inspection of a separate, explicitly synthetic UI fixture at a 390×844 content size: scan grid, invalid-result recovery, phone/server output gating, mesh filter, model actions, endpoint pairing form, viewer error state, and return to library. Fixture data/QA bridge are excluded from APK/source ZIP. The browser could not create a WebGL context, so real rendering was not verified.
- Packaged manifest: `dev.doxi.strata`, versionCode 3 / versionName 0.2.0, ARM64, minimum API 31 / target 35. Internet permission is present for the optional native server client. UI assets remain local.
- APK v2 signature and ZIP alignment verified. Signing SHA-256 matches 0.1.1: `35f56a473d982a67477eac225adb36ddecb9e93e11781ceaadda5d0c5fcd02a1`.
- APK contains ARCore/Brush native libraries and local UI/viewer assets; no ScanKit/Lightship binary, QA bridge, or demonstration model.

- The source ZIP was extracted into a clean directory and built offline against cached dependencies: all 43 Gradle tasks executed successfully, including all 23 Android tests.

## Meaning of the numeric fix

The original broken PLY was not attached for inspection. Tests reproduce the user's supplied diagnosis rather than claiming an examination of that original file. The viewer URL problem was independently reproduced. Explicit finite seed initialization and a conservative preset mitigate one potential native initialization path; the exact on-phone NaN cause is unconfirmed. Strict output validation prevents that failure from being represented as a successful scan. It does not recover erased geometry or prove every finite scan is high quality.

## Hardware/account limits

No physical Android device or Android emulator was connected. ARCore raw-depth behavior, native live point projection, Android WebView 3D rendering, Keystore pairing, foreground-service lifecycle under Android background limits, Vulkan shader execution, actual training completion/fidelity, thermal behavior, and cancellation on the user's S25 remain unverified on hardware.

No CUDA GPU, Docker GPU image build, real COLMAP/Splatfacto reconstruction, or real Modal deployment was run. Installing API requirements alone is not a reconstruction backend. Modal code is delivered for the user's own account; no cloud resources or paid compute have been deployed. The server's math/toolchain is independently implemented and no Scaniverse quality or speed parity is claimed.
