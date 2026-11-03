# SpaceView: Measurement Engine Research

Status: research / planning only (no app code). Prepared 2026-11-02.
Scope: how accurately phones can measure, how to get the most accuracy out of them, how to capture objects for the "AR Gallery", which stack to use, how many Indian phones are covered, and what the measurement engine should look like.

> **Bottom line.** On a typical Indian mid-range ARCore phone without a depth sensor, an honest promise for lengths of 0.5–3 m is **about ±2% of the length, and never better than ±1 cm** (roughly ±1–2 cm at 1 m, ±3–6 cm at 3 m) under good conditions. Error gets worse with distance, featureless walls, dim light and fast motion. "±1 cm at 1 m" is a fair claim only for **LiDAR iPhones/iPads** (and possibly ToF Android phones), on textured surfaces, with careful technique. Do not use it in Android marketing until our own test protocol (section 6) proves it on specific devices.
>
> For v1, use **native Kotlin + ARCore SDK, rendering with SceneView/ARSceneView (Filament)**. Isolate the measurement core so it does not depend on the renderer. Capture objects with a **"measured proxy"**: measured W×H×D plus a perspective-corrected photo texture, built into a textured box/plane glTF in metres. Do **not** use photogrammetry for v1. Later, iOS should be native Swift ARKit + RealityKit (built on GitHub Actions macOS runners), with LiDAR and Object Capture as an optional "high-fidelity" mode.

---

## 1. Realistic accuracy: ARCore vs ARKit

### 1.1 What the evidence actually says

The evidence is thin. **No peer-reviewed study** directly compares ARCore and ARKit tape-measure length error in mm over 0.5–3 m. Many figures online are vendor or marketing claims. The best numbers available:

| Source | Platform / sensor | Finding | Relevance |
|---|---|---|---|
| Luetzenburg et al., *Scientific Reports* 2021 (iPhone 12 Pro LiDAR) | ARKit + LiDAR | Small objects (>10 cm side): **±1 cm absolute accuracy**. 130 m cliff: ±10 cm | Best published support for "±1 cm" on LiDAR at close range |
| Remote Sensing Letters 2026 (iPhone 12 Pro vs 15 Pro) | ARKit + LiDAR | ~**2 cm** mean cloud-to-primitive accuracy on both models. LiDAR has not improved across generations | Surface accuracy, not tape length |
| PMC10939328 (2024), iPhone 13 Pro distance estimation | LiDAR / ARKit | Mean abs. error of central-pixel distance: LiDAR 1.36 / 0.87 / 0.81 cm at 1/2/3 m. ARKit-rear 1.37 / 0.48 / 1.40 cm. Average across methods rose from 1.3 cm at 1 m to 2.4 cm at 3 m | Point-distance (range) accuracy. LiDAR error does **not** grow over 1–3 m, but RGB/ML methods do |
| Benchmark of 4 proprietary VIO systems, *Sensors* 2022 (OptiTrack ground truth) | ARKit, ARCore, T265, ZED2 | ARKit most accurate and consistent, ARCore close second. Drift ≈ 0.02 m/s of motion. ARKit final drift 0.19 m in xy over a long trajectory | Drift grows with **path length/time**, so keep measurements local and short |
| Feigl et al., GRAPP 2020 (large industrial hall) | ARCore, ARKit, HoloLens | Worst case: ~17 m drift over 120 m, **scale error up to 14.4 cm per metre** | Shows the failure mode in large, dynamic, low-feature spaces |
| RealityCheck (ISM 2021) | ARCore | ~1.5 cm average virtual-object error vs ground truth over 22 trials | Rough hint at ARCore's local consistency |
| ARCore depth evaluations (arXiv 2204.01693, ISPRS 2022) | ARCore depth-from-motion (OnePlus 6 etc.) | Per-scene depth errors of **~7–30 cm** in room-scale scenes | Monocular depth maps are **not** good enough to measure from directly. Use them only for refinement and plane fitting |
| Google's own Measure app | ARCore | Pulled from Play Store June 2021. Reviews complained of stability and inaccuracy | Reputational warning: a mediocre Android measure app gets punished |
| RoomPlan informal (it-jim) | ARKit + LiDAR | 6.45 m room measured as 6.821 m (+37 cm, ~5.8%). Forum reports ~15 cm. Apple publishes only detection precision/recall (91%/90% at 3D IoU 0.3 for objects; 95% walls/windows), **no cm accuracy** | RoomPlan is for floor plans, not certified dimensions |
| iOS 13 Pro scanning apps vs TLS (MDPI 2025) | LiDAR apps | Big differences between apps on the same device. None met survey specs. Position tracking was the main weakness | App pipeline matters as much as the sensor |
| Reviews / blogs | Apple Measure, ARCore apps | Typical "±2–5 cm", rectangle auto-detect ~3% off (non-LiDAR) | Unverified, but consistent with the above |

### 1.2 Sensor-by-sensor expectations (our working estimates for 0.5–3 m)

These are **engineering estimates** that combine the sources above with first-principles error budgets (1.4). They are **hypotheses for our test protocol to confirm or reject**, not published facts.

| Configuration | Typical length error, good conditions | Notes |
|---|---|---|
| ARKit + LiDAR (iPhone 12 Pro+ Pro models, iPad Pro) | ~0.5–1 cm up to 2 m; ~1–2 cm at 3 m (≈0.5–1%) | LiDAR range ~5 m. Works on plain walls. Weaker on glass, mirrors, black glossy surfaces (TV screens) |
| ARKit VIO, no LiDAR | ~1–2% (1–2 cm at 1 m) | Scale from IMU+camera. Plane hit-tests are good on textured surfaces |
| ARCore + hardware ToF (few, older Samsung S10 5G / S20+ / Note10+, LG V60; almost none current in India) | ~1–1.5% | Depth API fuses ToF. Also works on low-texture surfaces |
| ARCore + Depth API (depth-from-motion), no ToF | ~1.5–2.5% | Depth helps where there is no plane, but raw depth error at room scale is cm-to-dm. Use it only for robust plane fitting near the target |
| ARCore VIO + plane hit-tests only | ~2–3%; worse on white walls | Vertical planes on plain painted walls (common in Indian homes and cafés) often fail to detect at all |
| ARCore Instant Placement | **Do not use for measurement** | Scale-less until it switches to full tracking. Distance is an *assumed* value (sample uses 2.0 m) |
| RoomPlan (LiDAR) whole-room | ~2–6% of wall length (anecdotal) | Good for layout. Use direct point-to-point for exact numbers |

### 1.3 How error grows with distance

There are three separate effects:

1. **Pixel / endpoint localisation**, which grows linearly with distance Z. One image pixel covers about Z / f_px. With ARCore's default 640×480 CPU image and ~65° horizontal FOV, f ≈ 500 px, so 1 px ≈ **2 mm at 1 m, 4 mm at 2 m, 6 mm at 3 m**. A finger tap on a phone screen is accurate to several camera pixels at best. This is why we need a magnifier loupe, edge snapping, and a high-resolution CPU image stream (CameraConfig) where the device supports one.
2. **Depth / plane-distance bias**, which acts as a proportional error. If the surface is estimated δZ too far away, every length on it is scaled by (1 + δZ/Z). A 1 cm plane bias at 1 m means 1% length error. Monocular depth bias grows roughly with Z², so it gets worse fast beyond 2 m. LiDAR is roughly constant within 0.3–3 m (see the PMC table).
3. **VIO scale error and drift**, which grow with path length and time, not with Z directly. A short, still measurement taken from one viewpoint has little drift. Walking 4 m along a wall to measure it builds up drift (Sensors 2022: ~2 cm/s of movement in hard cases).

**Rule of thumb:** relative error is roughly constant (≈1–3%) when the whole object is in view from one spot. It rises when (a) you are > 2.5–3 m away (pixel and depth limits), (b) you must walk to see both endpoints (drift), or (c) the surface is low-texture.

### 1.4 Worked error budget: 60 × 40 cm photo frame on a café wall, 1.5 m, mid-range ARCore phone, no ToF

| Term | Estimate (1σ) | Effect on 60 cm width |
|---|---|---|
| Endpoint localisation, 2 ends, edge-snapped to ~1 px (VGA, f≈500) | 3 mm/endpoint | √2 × 3 ≈ 4.2 mm |
| Wall-plane distance bias (fitted plane, textured wall) | ~1–1.5 cm at 1.5 m (0.7–1%) | 4–6 mm |
| VIO metric scale error | ~1% (estimate) | 6 mm |
| **RSS total** | | **≈ 9–10 mm (≈1.6%)**. 95% band ≈ ±2 cm |

With a high-resolution CPU stream (e.g. 1920×1080, f ≈ 1500 px) the localisation term drops to ~1.5 mm. With reference-object scale calibration (2.6) the scale term can fall to ~0.3–0.5%. That brings the total to ~0.8–1%, which is the realistic ceiling for non-LiDAR Android.

### 1.5 Honest claims we can make

- Android (non-ToF): "Typically within ±2% (about ±1–2 cm at arm's length) in good light on textured surfaces. Each measurement shows its own confidence range." Promise nothing better until our protocol shows it per device tier.
- LiDAR iPhone: "Typically within ±1 cm up to ~2 m" is supported by Luetzenburg 2021 and PMC10939328, but still must be verified for our own pipeline.
- Never claim "laser-accurate". Laser distance meters are ±1.5–2 mm. Apple itself calls Measure "approximate", and Google withdrew its own Measure app.
- Always display **value ± uncertainty** plus a quality badge, and refuse to save "precise" records when the uncertainty is above a threshold.

---

## 2. Techniques to maximise accuracy

Listed in rough order of payoff:

1. **Tracking-quality gating (mandatory).** Accept samples only when `Camera.trackingState == TRACKING` and `trackingFailureReason == NONE`. Also gate on gyro angular velocity (reject motion blur, e.g. > ~0.3 rad/s), on scene brightness (ARCore light estimate / mean pixel intensity), and on feature count in the ROI. Make an initialisation phase mandatory: a "move the phone side to side" prompt until tracking is stable and a plane is found, because parallax gives VIO its scale.
2. **Hit-test priority and filtering.** Prefer, in order: (a) `Plane` hits with `isPoseInPolygon` on a TRACKING plane that is not subsumed; (b) `DepthPoint` hits (Depth API enabled, raw-depth confidence above threshold); (c) feature `Point` hits with a surface normal. Never accept `InstantPlacementPoint` for measurements. Hit results come back sorted by distance; take the first valid one of the preferred type.
3. **Own plane fitting near the target.** Don't trust ARCore's plane polygon alone. Collect raw-depth points (`acquireRawDepthImage16Bits` / `acquireRawDepthImageMeters` from ARCore 1.56, with confidence ≥ ~200/255) plus ARCore feature points inside the region of interest. Fit a plane with RANSAC + least squares and project tapped or snapped 2D points onto it by ray–plane intersection. This is the key technique for wall-hung frames and TVs. It also gives a plane-fit residual that feeds the uncertainty estimate.
4. **Multi-sample averaging per endpoint.** While the reticle is held on a point, collect N ≈ 15–30 samples over ~0.5–1 s, ideally with a small parallax sweep. Use a robust estimator (median or trimmed mean). The sample spread gives a per-endpoint covariance. Report σ and reject if it is above a threshold.
5. **Anchor endpoints and recompute at save time.** Create an `Anchor` per endpoint. ARCore refines anchor poses as its map improves, so compute final distances from current anchor poses, and recompute when the user taps "save". Show the value converging ("stabilising…").
6. **Edge and corner snapping (computer vision).** Detect line segments and quadrilaterals in the camera image around the reticle: OpenCV LSD/Canny + Hough, or a contour-quad detector for frames and TVs. Snap to sub-pixel corners (`cornerSubPix`). Ask for the high-resolution CPU stream via `Session.getSupportedCameraConfigs()` / `setCameraConfig()` (default is VGA 640×480). Rectangle mode: detect the 4 corners of a frame or TV, then intersect each corner ray with the fitted wall plane to get W and H in one shot. Enforce right angles and parallel sides as a consistency check (deviation feeds uncertainty).
7. **Distance coaching.** Show live guidance for the target's best distance: "move closer" when > 2 m for objects under 1 m; "step back" when the object doesn't fit in the frame. Get the whole object into one view so there is no walking drift. Recommend a near-perpendicular view to the surface (< 30° obliquity), because oblique views increase depth bias.
8. **Scale-drift handling.** For spans that need walking (room dimensions, long walls), use an anchor chain: drop intermediate anchors, keep the path short, and encourage a loop back to the start to trigger loop closure. Show a larger uncertainty. Above ~5 m, recommend a laser meter or "measure in segments".
9. **Reference-object calibration (optional "precision mode").** The user puts a known object **on the same plane** as the target and measures it: A4 sheet 297 × 210 mm, or ISO/IEC 7810 ID-1 card 85.60 × 53.98 mm. The scale factor s = true/measured corrects VIO scale for that plane and session. Apply it only if 0.9 < s < 1.1, otherwise flag a tracking problem. Caveats: a card is too small at distance (at 1.5 m it spans ~30 VGA px, so a 1 px error ≈ 3%). Use A4 for anything beyond ~50 cm, and use the high-res stream. The same reference supports a **single-photo homography mode** (no AR needed): with a full-res still (f ≈ 3000 px), the A4's 4 corners define the plane's metric homography, and the target's corners on that plane are measured directly. Error grows when extrapolating to objects much larger than the reference, so give it an uncertainty from corner-localisation error propagation. This mode is also the **fallback for non-ARCore phones** (section 5).
10. **Uncertainty estimation shown to the user.** Propagate per-endpoint covariance, plane-fit residual, a distance-dependent pixel term, a device-tier scale prior (from our test data), and a tracking-quality penalty. Show "60.4 cm ± 0.9 cm". Colour-code: green ≤1%, amber ≤3%, red >3%. **Calibrate the displayed ±** in the test protocol so that ~90% of true values really fall inside the band. A ± that is honest is worth more than one that is small.
11. **Repeat-and-agree.** For "save to Gallery", require 2 independent measurements (e.g. re-aim) that agree within the shown uncertainty. Save the mean.
12. **Environment hints.** Warn on low light, plain white walls (low feature count), glass, mirrors and glossy black TV screens. For TVs, snap to the bezel or outer frame, not the screen reflection.

---

## 3. Object capture: photogrammetry vs "measured proxy"

### 3.1 Options

| Option | Platform | Requirements / status | Dimensional fidelity |
|---|---|---|---|
| Apple Object Capture (`ObjectCaptureSession` + `PhotogrammetrySession`, RealityKit) | iOS 17+ on device, macOS | On iOS: **LiDAR devices with a strong GPU** (A14+ per WWDC23 sample; Apple DTS says LiDAR is effectively required). macOS: GPU ≥4 GB + ray tracing. Output USDZ | Metric scale from LiDAR/ARKit. Good for 3D objects with texture; ~1–2 cm class. Weak on glossy, transparent, thin, featureless objects. iOS 26 / iPhone 17 Pro failures reported by developers (unverified) |
| Android native equivalent | none | **No Google equivalent of Object Capture** | n/a |
| Third-party cloud photogrammetry (KIRI Engine API, Polycam API; RealityScan app has no public API) | Upload photos, then server | KIRI: up to 200 photos/call, ~3–5 min processing. Cost per scan, needs network, privacy considerations | Photo-only reconstruction is **scale-ambiguous**. It needs a scale bar or ARCore camera poses to be metric. Vendors' "sub-cm" claims are for LiDAR modes |
| Self-hosted server (COLMAP / OpenMVS / Meshroom, or Gaussian splatting) with ARCore poses as priors | Android, then our server | Server infra, GPU cost, long processing | Can be metric if ARCore poses/anchors are used. Fiddly. Same reflective-surface weaknesses |
| **Measured proxy** (our recommendation) | Any ARCore phone; also the non-AR fallback | On device, instant, offline | **Exactly as good as the measurement** (±1–2%). The texture is a perspective-rectified photo |

### 3.2 Why the proxy wins for frames, TVs and appliances

- These objects are **planar or box-shaped**. A box with exact W×H×D and good front-face texture is visually convincing when placed against a wall, and it is dimensionally exact by construction.
- Photogrammetry is weakest on exactly these objects: glass in frames, glossy black TV panels, uniform white appliance panels. These give holes and wobbly edges, so the mesh's bounding box differs from the true size by more than the measurement error. Cropping a mesh and estimating a bounding box adds more error.
- What the user cares about is "does it fit my wall / cabinet". That is the bounding box. A proxy gives the bounding box directly; photogrammetry gives it only indirectly.
- It needs no server, no per-scan cost, and works offline (good for Indian connectivity), with an instant result.

### 3.3 Recommended capture pipeline (v1 Android)

1. Detect the rectangle (CV quad + AR plane) and measure W×H on the fitted plane, as in section 2.
2. Measure depth D:
   - Wall-hung (frame, TV): offer presets (thin frame 2 cm, canvas 4 cm, TV ~ model lookup) or a side-view 2-point measurement. Mark D as "low confidence", because a 2–5 cm protrusion is comparable to the error on non-depth phones.
   - Free-standing (appliance): 3-point box. Two base points on the floor plane give W and D, and a vertical height point gives H.
3. Grab a high-res still (the ARCore CPU image at max config, or a CameraX still if switching is acceptable). Unwarp the front face with the homography from the 4 detected corners into a straight-on texture (e.g. 1024 px on the long side). Optionally capture side and top textures, or use solid colour sampled from the edges.
4. Build the model. Front face = texture, sides = edge colour, optional bevel. Real-world units 1 unit = 1 m. Save as glTF 2.0 / **GLB** (Scene Viewer / Filament native). Keep it under 10 MB and under 50k triangles.
5. Save the **canonical record**, not just the mesh: `{dims_m, uncertainty_m, per-dim confidence, device model, ARCore version, tracking stats, calibration used, textures, capture photos, timestamps}`. Meshes are generated from this record per platform (GLB on Android, USDZ on iOS), so later re-export or upgrade is possible.

### 3.4 Later

- iOS LiDAR "High-fidelity scan" mode with Object Capture for real 3D objects (vases, chairs). Snap its mesh's bounding box to the measured proxy dims, or show both.
- Android: optional cloud photogrammetry for 3D objects, using ARCore poses to fix scale. Only after the proxy flow is proven.

---

## 4. Stack recommendation

### 4.1 Android (v1)

**Recommended: native Kotlin + ARCore SDK (`com.google.ar:core`, current 1.57.0, Oct 2026) + SceneView `arsceneview` (current ~4.65.0; Jetpack Compose + Filament; Apache-2.0; Android "Stable").**

- **Sceneform is deprecated** (Google archived it in 2020). SceneView is its community successor and the de facto current choice. It needs minSdk 24, compileSdk 37, AGP 9.4+, Kotlin 2.4, JVM target 21. This matches the dev's JDK 21 CLI + Gradle + GitHub Actions setup with no Android Studio.
  - Risk: SceneView is largely **one maintainer** (Thomas Gorisse) and has fast-moving versions. Mitigation: keep the **measurement engine renderer-agnostic**. It talks to the ARCore `Session`/`Frame` directly, and SceneView is only the view layer. If SceneView stalls, swap to raw Filament or OpenGL ES.
- **Raw ARCore + OpenGL ES** (Google's `hello_ar_kotlin` sample pattern) gives full control and has no third-party dependency, but means writing your own rendering, glTF loading and occlusion. It is a viable fallback, but slower to build for the Gallery/placement features.
- **ARCore for Jetpack XR** (`androidx.xr.arcore`, still alpha) runs on phones through Play Services for AR, but lacks some features and is aimed at Android XR headsets. **Not for v1.**
- **Unity AR Foundation** is ruled out (no Unity, heavy licence and tooling), and has no accuracy advantage: it wraps the same ARCore/ARKit.
- **Scene Viewer** (Google's intent-based AR viewer, glTF/GLB, `resizable=false` locks true scale, `mode=ar_only`) is a **zero-code way to "place in room at true scale"** for prototyping or as a fallback viewer. However, it needs ARCore and the Google app, and only offers basic UX. Use it for the early demo, then build in-app placement with SceneView.
- Computer vision: OpenCV Android SDK (LSD, contours, `cornerSubPix`, homography) inside a pure-Kotlin/JNI module.

### 4.2 iOS (later)

**Native Swift + ARKit + RealityKit**, built and signed on **GitHub Actions macOS runners** (no Mac locally). Use:
- `ARWorldTrackingConfiguration` with `.sceneDepth` / `.smoothedSceneDepth` on LiDAR devices, and raycasts for non-LiDAR devices.
- Vision `VNDetectRectanglesRequest` for frame and TV quads.
- RoomPlan for room outline (with the caveat about its accuracy).
- `ObjectCaptureSession` for the LiDAR high-fidelity mode.
- Quick Look / RealityKit to place USDZ.

The math core (filters, plane fit, uncertainty, record schema) could be shared through **Kotlin Multiplatform** (KMP builds an XCFramework on a macOS runner). Alternatively, port it as a small, well-tested Swift module. Either way, the **test vectors** (recorded inputs and expected outputs) are what must be shared.
- SceneView's iOS port is still Alpha, so don't rely on it for iOS.

### 4.3 What "REA" probably means

There is no mainstream AR framework called "REA". Plausible readings, most likely first:
1. **Apple's "Reality" stack: RealityKit / Reality Composer (Pro) / `.reality` files.** This is the most natural reading of "use REA if needed" in an AR app that will later go to iOS. **Best guess.** It maps to "use RealityKit on iOS".
2. **React Native AR**, i.e. ViroReact (ReactVision, maintained in 2025, New-Architecture support, ARCore + ARKit), or "REA" as the class prefix of React Native **Reanimated**. That would imply a cross-platform JS UI. For a precision measurement engine this adds a bridge layer and hides ARCore/ARKit details we need (raw depth, camera configs, anchors). It is acceptable only for non-AR UI screens.
3. Google's **Scene Viewer / `<model-viewer>`** (web AR viewer), if "REA" was a typo for a viewer component.
4. University of Ulm's "AREA" research framework (location-based AR). Unlikely.

**Action: confirm with the user.** Whatever it means, the measurement engine should stay native.

---

## 5. ARCore device coverage in India and fallback

- **Market:** Android was **89.6%** of Indian smartphone shipments in Q3 2025 (IDC). The market was ~152 M units in 2025 (flat). ~1 in 5 phones shipped was "premium" (Counterpoint). iPhone share is rising, but **LiDAR is only on iPhone Pro / iPad Pro**, so LiDAR-class accuracy reaches a small single-digit % of the market.
- **ARCore certified list (Google Play):** Google certifies each model for motion tracking (camera + IMU quality, calibration, CPU). The live list has hundreds of models from the brands that dominate India: Xiaomi/Redmi/POCO (100+), Vivo/iQOO (150+), Oppo/OnePlus (150+), Samsung incl. Galaxy A/M/F (100+), Motorola (~110), Realme (~60), Tecno/Infinix/itel (~50 each), Nothing (~10). **Lava is absent.** Many 2024–2025 mid-rangers are marked "Supports Depth API", e.g. Redmi Note 14, POCO X7 Pro, realme 15 Pro, iQOO Z10, vivo T4 Pro, Oppo F29, Galaxy A56/M36, moto g84, Nord 5, Nothing Phone (3a). Older and budget models (e.g. Realme 5/6/7 Pro) often lack Depth. Hardware **ToF** entries are old Samsung/LG flagships and practically irrelevant in India now.
- **No public figure for "% of Indian active devices that are ARCore-certified."** Estimate: most ₹12k+ phones from 2022 onward are covered, and sub-₹10k models and some regional brands are spotty. **Measure it ourselves:** Play Console device-catalogue reach for the AR-required feature, plus in-app telemetry of `ArCoreApk.checkAvailability()` results.
- **Requirements:** Android 7.0+ (API 24), Google Play Services for AR (auto-installed / updated via Play).

**Fallback design (declare ARCore as "AR Optional" in the manifest so the app installs on every phone):**
1. **Reference-photo measure (no AR):** the user places an A4 sheet or card on the same plane, takes a photo, and the app computes a homography to measure flat objects. Accuracy can be ~1% for objects up to ~2× the reference size. Show a larger uncertainty beyond that.
2. **Manual entry:** type dimensions from a tape or the product spec (e.g. a TV model lookup), plus a photo, to create a Gallery proxy.
3. **Gallery 3D viewer** (Filament / SceneView 3D mode, no ARCore) and **"place on a photo of your room"** 2D compositing, using a reference object in the room photo for scale.
4. Uncertified phones that still run ARCore via sideloaded services: **don't support them**. Accuracy is unknown, and Google notes uncertified devices may give a "far from perfect" experience.

---

## 6. Proposed measurement-engine architecture

### 6.1 Modules

```
:measure-core        (pure Kotlin/JVM, no Android deps; unit-testable on CI; KMP-ready)
  geometry/          ray-plane intersection, RANSAC plane fit, homography, quad fitting
  estimation/        robust averaging, covariance, uncertainty propagation, scale calibration
  model/             MeasuredObject record schema (JSON), units, versioning
  quality/           gating rules, quality score, thresholds per device tier

:measure-arcore      (Android; adapts ARCore Session/Frame to core types)
  FrameSampler       pulls pose, intrinsics, tracking state, light estimate, depth+confidence, CPU image
  HitResolver        hit-test priority (Plane > DepthPoint > Point; never InstantPlacement)
  DepthPlaneFitter   raw depth (meters API, conf>=thr) + feature points -> local plane
  AnchorStore        endpoint anchors; recompute on map refinement
  CameraConfigPicker highest CPU-image resolution that keeps 30 fps

:measure-vision      (OpenCV via JNI or Java bindings)
  EdgeSnapper        LSD / Canny near reticle, sub-pixel corner refine
  QuadDetector       frame/TV rectangle detection + tracking across frames
  Rectifier          homography unwarp -> front-face texture

:measure-modes       (state machines, UI-agnostic)
  PointToPoint, Rectangle (frame/TV), Box (appliance), Height (floor->point),
  RoomSpan (anchor chain), ReferenceCalibration, PhotoReference (non-AR)

:capture-proxy       builds textured box/plane GLB from MeasuredObject (1 unit = 1 m)
:gallery-store       Room DB + files: records, textures, GLB; export/import
:app-ui              Compose + SceneView ARSceneView (reticle, loupe, live value ± unc, badges)
:testkit             recorded-session replay harness, ground-truth catalogue, metrics reports
```

### 6.2 Data flow (one measurement)

```
ARCore Frame ──► FrameSampler ──► QualityGate ──(reject: coach user)──┐
                      │                 │ pass                        │
                      ▼                 ▼                             │
               CPU image (hi-res)   HitResolver ◄── reticle / snapped px ◄── EdgeSnapper/QuadDetector
                      │                 │
                      │                 ▼
                      │          DepthPlaneFitter (local plane, residual)
                      │                 │
                      ▼                 ▼
                 Rectifier       Ray-plane intersection ─► sample buffer (N≈15–30)
                      │                 │
                      │                 ▼
                      │          RobustEstimator ─► endpoint + covariance ─► Anchor
                      │                 │
                      │                 ▼
                      │          Measurement (value, σ, quality) ◄── ScaleCalibration (optional)
                      │                 │
                      ▼                 ▼
               texture.png      MeasuredObject record ─► ProxyBuilder ─► model.glb ─► Gallery
```

Placement later: load GLB at scale 1.0. Scaling gestures are **locked** by default (true scale is the product promise). Anchor it on a detected plane, and show the dims ± uncertainty as a label.

### 6.3 Accuracy test protocol

**Ground truth**
- Reference instrument: laser distance meter (±1.5 mm class) plus a steel tape / 1 m steel rule. Measure each GT object 3×, use the mean, and record temperature-independent values.
- Object set (keep it physically in a "test kit" box):
  - A4 sheet 297×210 mm; ID-1 card 85.60×53.98 mm
  - 2 photo frames (e.g. 30×40 cm and 50×70 cm), one glossy glass, one matte
  - A TV (e.g. 43", ~96×56 cm outer) switched off (glossy black)
  - A box appliance (microwave ~45×26×35 cm) and a cardboard box with known dims
  - A door (~0.9×2.1 m) and 2 wall lengths (~3 m, ~5 m) for room-span mode
  - Height: a person/pole of known height (~1.7 m)

**Conditions matrix** (subset per release, full matrix per milestone)
- Distance: 0.5, 1, 2, 3 m. View angle: 0°, 30°, 45°.
- Light: bright (>500 lux), home evening (~100–150 lux), mixed or backlit (window).
- Surface: textured wall, **plain white/painted wall**, glossy surface.
- Devices (tiers): T0 reference Pixel; T1 Depth-API mid-rangers (Redmi Note, Galaxy A5x, realme, vivo/iQOO, moto g); T2 non-Depth budget ARCore phone; T3 LiDAR iPhone Pro (iOS phase); T4 non-LiDAR iPhone (iOS phase).
- Repetitions: 10 per condition × 3 operators (including one first-time user, to capture UX-driven error).

**Metrics**
- Signed error (bias), absolute error (mm), relative error (%), median, **P95**, repeatability (SD of repeats), between-operator reproducibility.
- **Uncertainty calibration:** fraction of trials where GT lies inside the displayed ± band (target 85–95%; too high means the band is too wide to be useful).
- Reject/abstain rate: the share of attempts where the engine refused or warned. Low error achieved by refusing everything is a fail.
- Time-to-measure.

**Pass/fail targets for v1 (Android), conditions: 0.5–2 m, good light, textured surface**

| Tier | Median abs. error | P95 abs. error | Uncertainty coverage | Abstain rate |
|---|---|---|---|---|
| T0 / T1 (Depth API) | ≤ 1.0% or ≤ 6 mm (whichever larger) | ≤ 2.5% | 85–95% | ≤ 15% |
| T2 (no Depth) | ≤ 1.5% or ≤ 8 mm | ≤ 3.5% | 85–95% | ≤ 25% |
| Any tier, 3 m or plain wall or dim | report only; must **warn** and widen ± | — | ≥ 80% | — |
| T3 LiDAR (iOS phase) | ≤ 0.6% or ≤ 4 mm | ≤ 1.5% | 85–95% | ≤ 10% |
| Rectangle mode, frames/TVs (all tiers) | W and H each meet the tier targets above, and the corner right-angle deviation is ≤ 2° | | | |
| Depth D of wall-hung objects | not gated in v1 (labelled "estimate") | | | |

Release gate: no regression of more than 10% in median or P95 against the previous release on the replay set.

**Regression automation**
- Use the **ARCore Recording & Playback API** to record sessions (MP4 with camera, IMU and depth tracks) for every GT trial. Replay them through the engine on a test device in CI-like runs and compare against stored GT. ARCore playback needs a physical device, so run on a dev phone or a device-farm phone. Pure `:measure-core` logic gets JVM unit tests with synthetic data in GitHub Actions.
- Keep a versioned GT catalogue (JSON) and generate an HTML/CSV report per build.

### 6.4 Open risks

1. **Plain painted walls** (very common): vertical planes may not be detected, and depth-from-motion is weak there. This hits the core "frame on café wall" use case. Mitigations: rely on the frame's own texture and edges for depth points, 3-tap plane, reference-sheet mode.
2. **Scale error on budget phones** with poor IMU calibration. Is the certified list's quality enough for 1.5%? Unknown until tested. Calibration mode helps.
3. **Depth (D) of flat objects** cannot be measured reliably without LiDAR/ToF.
4. **SceneView bus factor** (single maintainer, very frequent breaking upgrades of AGP/Kotlin/compileSdk). Isolate it and pin versions.
5. **Unknown ARCore coverage share in India.** Instrument from day 1, and keep the non-AR fallback first-class.
6. **Glossy and transparent surfaces** (glass frames, TV screens) cause bad hits. Snap to bezels and frames, and warn.
7. **iOS without a Mac:** signing, provisioning and on-device testing via GitHub Actions + TestFlight only; slow iteration. LiDAR test devices are needed for T3 validation.
8. **Expectation management:** competitor apps claim "1 cm accuracy". Our honest ± display may look worse in screenshots, but it is the defensible position. Google withdrew its own Measure app over accuracy complaints.

---

## Sources

- Luetzenburg, Kroon, Bjørk (2021), *Evaluation of the Apple iPhone 12 Pro LiDAR for an application in geosciences*, Scientific Reports 11:22221. https://doaj.org/article/05f6da2bb22e4343bec5db6fcf0ecf88 ; https://lidarnews.com/apple-iphone-12-pro-lidar-evaluated/
- *A comparison of lidar accuracy across iPhone models* (2026), Remote Sensing Letters. https://www.tandfonline.com/doi/full/10.1080/2150704X.2026.2720055
- *Accuracy and Usability of Smartphone-Based Distance Estimation Approaches for Visual Assistive Technology Development* (2024). https://pmc.ncbi.nlm.nih.gov/articles/PMC10939328/
- *A Benchmark Comparison of Four Off-the-Shelf Proprietary Visual–Inertial Odometry Systems*, Sensors 2022. https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9785098/
- Feigl et al. (2020), *Localization Limitations of ARCore, ARKit, and Hololens in Dynamic Large-scale Industry Environments*. https://www.scitepress.org/Papers/2020/89899/89899.pdf
- RealityCheck (ISM 2021). https://www.cs.ucr.edu/~jiasi/pub/RealityCheck_ism21.pdf
- Hologram stability in markerless smartphone AR. https://arxiv.org/pdf/2109.14757
- ARCore depth evaluations: https://arxiv.org/pdf/2204.01693 ; https://isprs-archives.copernicus.org/articles/XLIII-B2-2022/201/2022/isprs-archives-XLIII-B2-2022-201-2022.pdf
- MDPI 2023/2025 iPhone 13 Pro LiDAR scanning apps comparison. https://www.mdpi.com/2673-7418/3/4/30
- ISPRS 2024 LiDAR apps on Apple devices. https://isprs-archives.copernicus.org/articles/XLVIII-2-W8-2024/431/2024/
- Apple ML Research, *3D Parametric Room Representation with RoomPlan*. https://machinelearning.apple.com/research/roomplan
- it-jim, *RoomPlan is Awful and it's Great!* https://www.it-jim.com/blog/roomplan-framework-by-apple/ ; Apple forum on RoomPlan accuracy: https://developer.apple.com/forums/thread/712500
- SlashGear Apple Measure test. https://www.slashgear.com/1884872/how-accurate-iphone-measuring-app/ ; Next Reality Apple vs Google Measure: https://mobile-ar.reality.news/news/tale-tape-does-apples-measure-app-measure-up-against-googles-version-0185472/
- Google Measure shutdown (June 2021). https://9to5google.com/2021/06/08/google-measure-ar-app-sunset/ ; https://androidcentral.com/rip-google-measure-short-term-solution-long-term-problem
- ARCore Depth API. https://developers.google.com/ar/develop/depth ; Raw Depth: https://developers.google.com/ar/develop/java/depth/raw-depth
- ARCore hit-test guide. https://developers.google.com/ar/develop/java/hit-test/developer-guide ; Instant Placement: https://developers.google.com/ar/develop/java/instant-placement/developer-guide
- ARCore camera configs (VGA default CPU image, higher-res stream). https://developers.google.com/ar/develop/java/camera-configs
- ARCore Android SDK releases (1.54–1.57). https://github.com/google-ar/arcore-android-sdk/releases
- ARCore supported devices. https://developers.google.com/ar/devices
- ARCore for Jetpack XR on mobile. https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/mobile
- SceneView. https://github.com/sceneview/sceneview ; https://sceneview.github.io/
- Scene Viewer. https://developers.google.com/ar/develop/scene-viewer
- Apple Object Capture requirements. https://developer.apple.com/forums/thread/769221 ; https://wwdcnotes.com/notes/wwdc23/10191 ; https://developer.apple.com/documentation/realitykit/creating-3d-objects-from-photographs
- KIRI Engine API. https://kiriengine.com/api ; RealityScan on Android: https://cgpress.org/archives/realityscan-now-available-for-android.html
- ViroReact / ReactVision. https://github.com/ReactVision/viro
- India market: IDC Q3 2025 (Android 89.6%) https://www.idc.com/?p=104301 ; Counterpoint premium share https://www.gsmarena.com/counterpoint_1_in_5_smartphones_shipped_in_india_in_2025_were_premium_models-news-71366.php
- ARCore Recording & Playback API. https://developers.google.com/ar/develop/java/recording-and-playback/developer-guide

*Estimates marked as such (sections 1.2, 1.4 and the pass/fail targets) are engineering hypotheses to be validated by the section 6.3 protocol. They are not published measurements.*
