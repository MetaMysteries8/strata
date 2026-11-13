# What Scaniverse does with points in space

Research date: 2026-11-13. Scope: the Classic/local Scaniverse workflow and the user-supplied Android 5.2.7 split bundle. Current Niantic pages also describe a separate business/cloud workflow; those should not be treated as proof that Classic needs cloud processing.

## Findings and confidence

Scaniverse's quality depends on establishing camera motion and consistent scene geometry, then fitting appearance. A sparse point cloud alone does not reproduce that pipeline. The supplied bundle contains native capabilities for tracking, relocation, feature matching, pose refinement, multi-view depth, filtering/fusion, and Gaussian optimization. The public SDK documents a reconstruction processor and separate live coverage visualization. This supports a substantially richer pipeline than Strata 0.1's direct ARCore sparse seeds → Brush training. Included capabilities are evidence, not a complete runtime trace.

| Evidence | What it establishes | What it does not establish |
| --- | --- | --- |
| Official scan technique guide [1] | Captured images, 3D device position/orientation, optional LiDAR depth, mesh and splat outputs | Exact proprietary algorithms or Classic's complete implementation |
| Classic support material [2] | Local processing and offline use in that workflow | Cloud-free operation of every current Niantic product |
| Native strings in supplied ScanKit binary | Named feature matching, pose refinement, depth, fusion, training, compute and relocation capabilities | Which branch runs on an S25, stage order, thresholds, quality, speed |
| Official reconstruction guide [3] | Public GaussianSplatProcessor, full-resolution 15 FPS recording guidance, Enhance-style additional passes | An account-free replacement native Kotlin API or permission to redistribute extracted APK code |
| Official visualization guide [4] | Live depth/raycast coverage textures and stripes marking incomplete regions | That a simple landmark overlay measures equivalent surface coverage |

## 1. Camera motion and persistent landmarks

A textured corner on your Mii can appear in many photos. Matching it across views lets a reconstruction estimate its 3D position relative to the cameras. A stationary real point moves across the screen as you move; a world-space map lets the app project it back into the right image location. The coordinate origin is generally a local scan frame; absolute GPS coordinates are unnecessary for this task.

ARCore documents motion tracking that combines visual feature points with inertial sensors and estimates the camera's position and orientation [5]. That gives Strata an initial camera trajectory and sparse points. The Scaniverse bundle also contains FrameFrameTracker depth-egomotion settings and MapBuilder relocation-to-keyframe-with-depth settings. Those names support tracking and revisiting mapped views. They do not prove that Scaniverse delegates all tracking to ARCore or exactly how it corrects a loop.

Relocalization means finding the camera's pose against previously mapped visual structure. Global pose refinement means adjusting camera estimates so the same physical feature lines up consistently across images. Without those corrections, small errors can accumulate and produce doubled surfaces or fuzzy splats. This is an explanation of the geometry problem; the bundle does not reveal a verified end-to-end optimizer configuration.

## 2. Depth gives many more 3D samples

A sparse tracking map has useful landmarks but few samples on broad smooth surfaces. Dense or semi-dense depth adds distance estimates for image pixels. Multi-view stereo uses overlapping images and camera geometry to estimate those distances, rejects inconsistent estimates, and merges useful observations across views.

In the supplied binary, named capabilities include `SplatPipeline::runFeatureMatching`, `runPoseRefinement`, `runDepthMapping`, `runDepthFiltering`, `runFusion`, and `runTraining`. Other strings reference a DiffusionDepthMapper and COLMAP triangulation code. These are direct static observations. They suggest a geometry-building stage before appearance training; exact execution order and implementation remain unverified.

A dedicated depth sensor is optional on compatible Android devices. Google's Depth API estimates depth using camera motion, selectively uses machine learning, and incorporates hardware depth where available [6]. Raw Depth supplies sparse estimates with a confidence image, allowing an app to discard unreliable samples [7]. It should not be described as an accurate measurement for every pixel.

For an ideal pinhole camera with a valid optical-axis depth z, a pixel (u,v) and calibration (fx,fy,cx,cy) give camera coordinates:

- x = (u − cx) × z / fx
- y = (v − cy) × z / fy
- z = the depth sample

Then the camera-to-world pose places that sample in the shared map. OpenGL flips image-down Y and camera-forward Z relative to this image convention. Depth calibration must be scaled to the depth image, not copied blindly from the CPU photo. Google's raw-depth codelab explicitly uses texture intrinsics scaled to depth resolution [8].

Filtering and fusion matter: noisy depth projected with inaccurate poses creates inconsistent geometry. Strata 0.2 now saves confident raw-depth samples and merges them into spatial seed cells; it does not implement Niantic's proprietary depth mapper, globally corrected surface fusion, or a dense TSDF map.

## 3. Live scan coverage is a separate subsystem

Niantic's visualization guide describes raycast data and striped overlays for regions that have not been sufficiently captured [4]. That is geometry/coverage feedback while recording. The final optimized Gaussian scene is a different output.

Strata now projects actual stored sparse/depth seed points over the live camera. This gives useful feedback about where observations exist. A cluster of projected dots does not establish sufficient view diversity, consistent surface depth, or that every side of the object was captured. Calling it full Scaniverse-equivalent coverage would be misleading.

## 4. Gaussian optimization and splitting

Standard 3D Gaussian splatting starts from geometry and represents appearance with many 3D ellipsoids. Each has a position, size/orientation, opacity, and color coefficients. During training, a GPU renders the ellipsoids from recorded camera views, compares those renders with the photos, and adjusts the parameters. Density control adds, clones/splits, or removes Gaussians to allocate detail [9].

That is the general meaning of the “Gaussian splitting” you mentioned. The ScanKit binary contains Gaussian rendering/gradient kernels, updater and statistics names. That supports iterative optimization; it does not reveal enough to claim that Scaniverse uses the original paper's exact split/prune rules. Its custom training schedule, stability measures, and scene initialization remain important unknowns.

The binary includes Vulkan compute selection, OpenCL fallback, and a Gaussian shader-library name. This explains a plausible on-phone GPU implementation without desktop CUDA. Which backend actually runs on the user's phone requires runtime evidence. Neither a strings listing nor a successful Android build proves GPU correctness.

Niantic's SDK guidance recommends full-resolution recording at 15 FPS, appropriate near/far depths, and additional quality passes corresponding to Enhance [3]. Those are public integration recommendations, not confirmed internal thresholds for APK 5.2.7. Its use of “iteration” here describes a processing/enhancement pass; it should not be read as one gradient-descent step.

## 5. Splats and meshes have different jobs

A splat represents how a scene looks from different viewpoints. A mesh explicitly stores connected triangle surfaces, useful for editing, collisions, and downstream fabrication preparation. A Gaussian PLY may contain perfectly valid splat properties while lacking mesh faces. A generic PLY-to-STL converter may therefore reject it even when the splat is healthy.

The reported Mii export has an additional, decisive defect: all 161 positions reportedly collapse and its scale/opacity/color values are NaN. That diagnosis was supplied by the user; the actual broken file has not been inspected in this workspace. No recoverable shape can be inferred from those values. Strata's new regression fixture reproduces that reported failure and is rejected. The original dataset is the recovery source.

The new server mesh path computes camera alignment, multi-view depth, fusion, and a Poisson surface using COLMAP [10], then transfers vertex colors and exports GLB/OBJ/STL. It does not create triangles by renaming a splat, nor guarantee a watertight printable result. Server Splatfacto optionally optimizes camera poses; Both mode uses the COLMAP-aligned dataset.

## Practical gap assessment

| Component | Strata 0.2 implementation | Remaining gap |
| --- | --- | --- |
| Initial camera trajectory | ARCore image/IMU tracking | No independent on-phone global bundle adjustment/loop closure |
| Spatial points | Sparse points plus confident raw depth; separate calibration saved | No robust dense surface fusion or geometry-aware outlier refinement |
| Capture feedback | Actual mapped-point projection | No calibrated surface coverage/striped raycast assessment |
| Recording | Selected quality/motion-gated photos, ≤240 frames | Lower recording density than Niantic's 15 FPS recommendation |
| Phone splats | Brush, explicit finite initialization, conservative preset, strict validation | Reported device failure not traced; GPU completion unverified |
| Server splats | Splatfacto with camera optimization; COLMAP alignment in Both | GPU runtime/quality unverified; different engine from Scaniverse |
| Meshes | Server COLMAP dense stereo/fusion/Poisson + colored exports | No phone mesh backend or texture atlas; arbitrary SfM scale |

The highest-value next work for a fully independent local app is a validated geometry backend with pose refinement, depth consistency/outlier rejection and surface fusion, followed by calibrated coverage feedback and mobile GPU testing. Raising splat limits cannot substitute for those steps.

## Public SDK route

Niantic now publishes a `GaussianSplatProcessor` reconstruction example in its Unity Scan Reconstruction package [3], plus Kotlin scanning configuration and integration documentation. The current download/setup pages require a business account/access token [11]. The reconstruction example is Unity-specific; the existence of Kotlin capture APIs does not establish an equivalent account-free Kotlin reconstruction processor. It could be evaluated as an optional licensed backend, but it has not been integrated or represented as an independent fully offline solution. The supplied APK's proprietary libraries are not distributed in Strata.

## Primary sources

1. https://www.nianticspatial.com/docs/scaniverse/techniques/
2. https://scaniverse.com/support?trk=public_post_main-feed-card-text (Classic support material surfaced by search; the plain support URL currently redirects to Niantic's newer Capture product.)
3. https://www.nianticspatial.com/docs/nsdk/how-to/ar/gaussian_splat/
4. https://www.nianticspatial.com/docs/nsdk/how-to/ar/scan_visualization/
5. https://developers.google.com/ar/develop/fundamentals
6. https://developers.google.com/ar/develop/depth
7. https://developers.google.com/ar/develop/java/depth/raw-depth
8. https://codelabs.developers.google.com/codelabs/arcore-rawdepthapi
9. https://repo-sam.inria.fr/fungraph/3d-gaussian-splatting/
10. https://colmap.github.io/cli.html
11. https://www.nianticspatial.com/docs/nsdk/downloads/ and https://www.nianticspatial.com/docs/nsdk/setup/?platform=kotlin

Static evidence: supplied `com.nianticlabs.scaniverse_5.2.7-190_2arch_7dpi_24lang_831869d0ae7fc0dfa71871802ba441b6_apkmirror.com.apkm`; inspection only, no execution or proprietary code incorporation. The symbol names are architectural clues rather than a recovered implementation.
