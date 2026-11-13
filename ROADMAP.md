# Strata feature backlog

## Video capture streamed to a GPU server

Requested by Doxi, 2026-11-13. Future work only; not implemented in 0.2.4.

Capture a full video while sending it to a configured Modal or local GPU server. Avoid retaining the entire recording on the phone. Use a bounded RAM/temporary-disk buffer and acknowledge uploaded chunks before discarding them. Android camera/encoding and reliable upload still require some temporary storage; do not promise literal zero local storage.

Acceptance goals:

- Explicit consent and a connected server before recording/upload starts; show destination, live transfer state, bytes buffered, network interruptions, and estimated server storage use.
- Resumable chunk uploads with sequence IDs, integrity validation, acknowledgements, and idempotent finalization. Cancel safely; do not silently discard chunks not yet accepted by the server.
- Bound phone storage. Pause recording or ask the user to choose a fallback when upload throughput falls behind, connectivity is lost, storage runs low, or Android suspends the app.
- Preserve synchronized ARCore poses/calibration/depth metadata where available. Support video-only server reconstruction via camera alignment when poses are unavailable.
- Server-side decoding, blur/overlap/keyframe selection, COLMAP alignment, Gaussian reconstruction, and optional meshes. Document image-quality trade-offs versus full-resolution still capture.
- Stream into durable server storage through a CPU ingestion service; do not allocate an idle GPU for the whole recording. Trigger compute only after explicit finalization/processing consent.
- Retention/deletion controls for partial uploads and originals, background/thermal behavior, authentication, and optional local backup. Modal usage and network/storage charges stay visible.

This is the requested video workflow concept, not a claim about Polycam's internal implementation.
