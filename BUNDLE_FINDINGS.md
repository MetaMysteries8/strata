# Supplied Scaniverse 5.2.7 bundle: static observations

The APKMirror split bundle contains a Flutter app layer plus native ARM64 ScanKit, Lightship, ARCore, and graphics libraries. No app execution, account access, or proprietary code integration was performed.

The ScanKit strings identify feature matching, camera pose refinement, depth mapping/filtering, fusion, Gaussian training, depth-assisted frame tracking, and keyframe relocation. Vulkan compute selection and OpenCL fallback are present. Those are included capabilities; they are not a verified runtime trace, exact stage order, or a performance result for a particular phone.

See SCAN_PIPELINE.md for detailed evidence, public documentation, uncertainty, and the gap between those capabilities and Strata. Strata 0.2 adds independent raw-depth capture and a point overlay; server reconstruction uses separately installed open-source Nerfstudio/COLMAP. No supplied Scaniverse binary or asset is included in the APK or source deliverable.
