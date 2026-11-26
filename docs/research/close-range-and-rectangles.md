# SpaceView: Close-range accuracy and automatic rectangles

Status: research only, no app code. Prepared 2026-11-25 for PLAN v3.1 (§2 accuracy contract, §4 item 1; workstreams W9 and W10).
Builds on `docs/research/measurement-engine.md` (the "engine research") and does not repeat it. Sources are linked inline and listed at the end.

> **Correction to the engine research.** Its §1.1 table and Sources cite arXiv 2204.01693 as an "ARCore depth evaluation". That paper is in fact *Monitoring social distancing with single image depth estimation* (Mingozzi et al.). It is not an ARCore depth study. The "~7–30 cm per-scene depth error" claim should rest on the ISPRS 2022 paper alone, or be removed.

---

## 0. Summary

**Why the menu was inaccurate.** It was probably several things at once, and most of them are about distance:

1. **The phone was closer than either platform supports.**
   - Apple's own guide says Measure works best on objects **0.5 to 3 m** from the iPhone.
   - Google says ARCore depth is most accurate at **0.5 to 5 m**.
   - SpaceView applies no minimum range anywhere. It only drops depth pixels nearer than 0.2 m.
2. **An A4 menu does not fit on screen at 0.3 m.** On a Pixel 10a held upright, the visible width is only about **0.6 × the distance**, so about 19 cm at 0.3 m. The user has to pan from corner to corner. That brings in hand drift, and a corner can be dropped on a different part of the scene.
3. **Hands-free corners are wherever the reticle happens to be, not where the corner is.**
   - The dwell trigger lets the target drift **1 cm** during the hold.
   - The stabiliser averages anything within **8 mm** of the running estimate.
   - Neither tolerance scales down at close range. At 0.3 m, 1 cm is over 3 % of an A4 side.
4. **Absolute thresholds tuned for rooms are too loose at arm's length.**
   - A depth or feature hit must be **3 cm** nearer than the table plane before it wins.
   - The fitted plane may disagree with the raw hit by **2 cm**.
   - The depth band is **±4 cm**.
   - At 0.3 m each of these is 7–13 % of the range. A thick or padded menu is therefore always measured on the table plane underneath it, and that table plane carries **no plane-error term**.
5. **Glossy laminate, autofocus hunting and motion blur at close range.**
   - The motion gate allows 0.30 m/s of camera movement. At 0.3 m with a 1/30 s exposure, that is about 30 px of blur.
   - The session also uses autofocus, which Google advises against for tracking. Focus changes are not detected, and the intrinsics are read only once.

**What Apple actually does.** Non-LiDAR iPhones have no depth sensor and no depth-from-motion for world tracking. Their Measure app relies on:
- visual-inertial tracking;
- planes, both detected and "estimated";
- per-frame camera intrinsics, with autofocus allowed;
- a UX that keeps users in a good regime: coaching, the 0.5–3 m advice, snapping, and a white box that appears automatically around rectangles.

LiDAR-only extras: edge guide lines, the ruler view, person height and measurement history. These are not reachable on a Pixel 10a, and we must not claim them.

**Recommendation.**
- **W10 first, in this order:**
  1. a range guide and refusal: hint below 0.35 m, refuse below 0.25 m, and require the object to fit the frame;
  2. range-relative tolerances;
  3. a blur and focus gate driven by the camera's own exposure and focus metadata;
  4. raycasting onto the plane the object lies on, using the infinite plane when depth agrees;
  5. a scale warm-up sweep.
- **W9: a pure-Kotlin classical detector.** It finds line segments, builds quads, scores them on 3-D metric rectangularity, and refines corners to sub-pixel accuracy by line intersection. The 3-D lift goes through the same plane, and corners are fused over several frames.
- **Cost:** about 0 MB of APK and a permissive licence (our own code).
- **ML (W9 hybrid) comes only if the classical detector fails our recall target.** Use ML Kit Subject Segmentation (already shipped, 0 MB) to propose the region, or a ≤ 2 MB keypoint model on LiteRT from Play services.
- **Not usable:** ML Kit Document Scanner, because it owns the camera UI. Full OpenCV is also out, because it would break the size budget.

**Honest expectations for an A4 menu flat on a textured table, good light, Pixel 10a with Depth on.** These are engineering estimates to be confirmed by §6:

| Distance | Today (hands-free dwell, 3 corners) | After W9 + W10 |
|---|---|---|
| 0.3 m | Unreliable: the A4 doesn't fit the frame, ±5–15 mm or worse | **Refused / "move back"** for A4. A credit card still measures to ~1–2 mm |
| 0.5 m | ~4–10 mm median | **~2–4 mm median, P90 ≈ 6–8 mm** (≈1–1.3 %) |
| 1 m | ~5–12 mm median | **~3–5 mm median, P90 ≈ 8–10 mm** |

Apple has published **no** controlled accuracy figures for Measure on non-LiDAR iPhones. One informal test found its automatic rectangles "almost consistently 3 % unreliable", and that was on a LiDAR iPhone 14 Pro ([SlashGear](https://www.slashgear.com/1884872/how-accurate-iphone-measuring-app/)).
- Matching a non-LiDAR iPhone is realistic.
- Matching LiDAR is not.
- Getting below about 1 % needs a reference object (the existing calibration in `engine/Calibration.kt`).

---

## 1. How Apple gets its measuring accuracy

This is a design-level study of public material. No Apple code is used or reproduced.

### 1.1 ARKit: raycasting rather than hit-testing

- **Hit-testing** (`ARFrame.hitTest`) was the original API. It was deprecated after iOS 14 ([docs](https://developer.apple.com/documentation/arkit/arframe/hittest(_:types:))). It returned every surface along the ray, nearest first.
- **Raycasting** (iOS 13+, introduced in [WWDC19 "Introducing ARKit 3"](https://developer.apple.com/videos/play/wwdc2019/604)) replaces it with a *query* that says which kinds of surface may be hit ([`ARRaycastQuery.Target`](https://developer.apple.com/documentation/arkit/arraycastquery/target-swift.enum)):
  - **existing plane geometry**: a detected plane, and only inside its measured shape. This is ARCore's `isPoseInPolygon`.
  - **existing plane infinite**: a detected plane "regardless of its size and shape", meaning its full mathematical plane. This is the key one for a menu: the table plane is valid under the menu even before the table's polygon has grown to cover the menu area.
  - **estimated plane**: "nonplanar surfaces, or planes about which ARKit can only estimate", a local surface guessed from feature points. This is ARCore's `Point` with `ESTIMATED_SURFACE_NORMAL`, or our `LocalSurfaceModel`.
- **Alignment filter.** The query can be restricted to horizontal, vertical or any alignment.
- **Tracked raycast** ([`ARTrackedRaycast`](https://developer.apple.com/documentation/arkit/artrackedraycast)): "a raycast query that ARKit repeats in succession to give you refined results over time". The app keeps one query alive per point, and the 3-D position updates as the map improves.
  - The Android equivalent is to re-intersect each corner's *pixel ray* every frame with the current plane estimate, and to keep anchors (§3, item 6).

**Design lesson.** Apple separates *what surface to trust* from *where the ray is*. It lets the app use the infinite plane of a known surface when the polygon has not caught up.

### 1.2 Plane anchors

- [`ARPlaneAnchor`](https://developer.apple.com/documentation/arkit/arplaneanchor) carries several kinds of data:
  - a centre, an extent and a coarse polygon mesh (`geometry`);
  - an alignment;
  - on supported devices, a classification such as table or floor.
- Anchors are refined over time as ARKit sees more of the surface. Apps are expected to read the current pose rather than a cached one.
- SpaceView already does this through anchors (`MeasureSession` anchor refinement loop). Nothing new to adopt here, except **plane classification as a hint**. ARCore has no direct classification. A horizontal upward plane at table height is a good stand-in.

### 1.3 Scene depth: LiDAR only

- `sceneDepth` and `smoothedSceneDepth` come from the LiDAR scanner. ARKit "supports scene depth only on LiDAR-capable devices" ([docs](https://developer.apple.com/documentation/arkit/arconfiguration/framesemantics-swift.struct/scenedepth)).
- A per-pixel [confidence map](https://developer.apple.com/documentation/arkit/ardepthdata/confidencemap) has three levels, and confidence drops on reflective or light-absorbing surfaces.
- **A non-LiDAR iPhone gets no world-tracking depth at all.** It measures from visual-inertial tracking, planes and estimated planes only.
- So in information terms, a **Pixel 10a with the ARCore Depth API has more geometric input than a non-LiDAR iPhone**, since depth-from-motion adds per-pixel depth that the iPhone lacks.
- The iPhone's advantages are elsewhere:
  - tight factory camera–IMU calibration and per-frame intrinsics;
  - one hardware family to tune for;
  - a polished UX that keeps users in a good regime.

### 1.4 Camera handling

- ARKit enables **autofocus by default** since iOS 11.3 ([`isAutoFocusEnabled`](https://developer.apple.com/documentation/arkit/arworldtrackingconfiguration/isautofocusenabled)).
- It reports `ARCamera.intrinsics` **per frame**, and developers observe that fx/fy change between frames. One report saw about 36 px in one session, cause unconfirmed ([Apple forum](https://developer.apple.com/forums/thread/827789)). Focus breathing is a known cause of intrinsics changing with focus ([arXiv 2510.23589](https://arxiv.org/pdf/2510.23589v1)).
- **High-resolution frame capture** (iOS 16, [`captureHighResolutionFrame`](https://developer.apple.com/documentation/arkit/arsession/capturehighresolutionframe(completion:))) delivers a full-quality still with the same pose and anchors, out of band. Apps use it for detail work like corner finding and texture.

### 1.5 RealityKit

RealityKit's `ARView` wraps the same ARKit raycasts (`makeRaycastQuery`, `trackedRaycast`).
- Its *scene understanding* mesh, occlusion and physics against the real world need LiDAR.
- For non-LiDAR measuring accuracy, RealityKit adds nothing beyond ARKit. It is a renderer and convenience layer.

### 1.6 The Measure app's behaviour

From Apple's [user guide](https://support.apple.com/guide/iphone/measure-dimensions-iphd8ac2cfea/ios), its iOS 14/15 versions, and reviews:

| Behaviour | Devices | What it does | Why it helps accuracy |
|---|---|---|---|
| "Slowly scan nearby objects" before measuring | All | Coaching until tracking and planes are ready | Visual-inertial scale needs motion, and planes need views |
| **"For best results … 0.5 to 3 meters"** | All | Written guidance | Stays where tracking, depth and pixel scale are good |
| Centre dot + Add | All | Point-to-point measuring | Same as our reticle |
| **Auto rectangle**: "When iPhone detects the edges of a rectangular object, a white box frames the object"; tap to get its size | All | Rectangle found in the camera image, lifted to the surface | Removes human aim error on the corners |
| Snapping to edges and points, with haptic feedback | All (reviews say it is weaker on non-LiDAR) | The dot jumps to a nearby edge or endpoint | Same |
| **Edge guides**: "a guide line appears" along a straight edge | "Supported models": LiDAR Pro ([iOS 14 guide](https://support.apple.com/en-asia/guide/iphone/measure-iphd8ac2cfea/14.0)) | Line along a 3-D edge | Uses LiDAR geometry |
| **Ruler view** when moving closer | LiDAR Pro ([iOS 15 guide](https://support.apple.com/en-asia/guide/iphone/measure-iphd8ac2cfea/15.0)) | Tick marks along the line | Visual check only |
| **Person height** ([guide](https://support.apple.com/guide/iphone/measure-a-persons-height-iph341d4a993/ios)) | LiDAR Pro ([MacRumors](https://www.macrumors.com/how-to/iphone-pro-measure-height/)) | Body detection + floor + LiDAR depth to the head | |
| Measurement history | LiDAR Pro | | |
| "Measurements are approximate" | All | Disclaimer | |

**What reviews say about accuracy.**
- [SlashGear](https://www.slashgear.com/1884872/how-accurate-iphone-measuring-app/), using an iPhone 14 Pro plus an iPhone 8:
  - distance errors "never more than 3–5 %";
  - auto rectangles on a card and a TV "almost consistently 3 % unreliable";
  - the non-LiDAR iPhone 8 showed "more errors … snapping to edges";
  - large objects that don't fit the frame, busy backgrounds, poor light and a blurry lens all hurt.
- An ITU Kaleidoscope 2022 study tested Samsung phones on ARCore against an iPad Pro, which has LiDAR, at 10–100 cm ([proceedings p.121–122](https://www.itu.int/en/publications/Documents/tsb/2022-Kaleidoscope-Academic-Conference-Proceedings/files/basic-html/page122.html)). It found ARKit more accurate. It also found that devices "seem to improve in terms of accuracy the further away they are": **10 cm was worse, not better**.
- No peer-reviewed study gives non-LiDAR Measure errors in mm. That is why PLAN §2 asks for our own side-by-side test (§6).

### 1.7 Vision rectangle detection (`VNDetectRectanglesRequest`)

[Apple docs](https://developer.apple.com/documentation/vision/vndetectrectanglesrequest) describe it as finding "projected rectangular regions" such as "credit cards, business cards, documents, and signs". Parameters:

| Parameter | Meaning (Apple's wording) | Default (third-party listing, not on Apple's page) |
|---|---|---|
| `minimumAspectRatio` / `maximumAspectRatio` | shorter side ÷ longer side, range 0–1 | 0.5 / (listed as 0.5, doubtful; probably 1.0) |
| `quadratureTolerance` | "number of degrees a rectangle corner angle can deviate from 90°", range 0–45 | 30° |
| `minimumSize` | "minimum size … as a proportion of the smallest dimension" | 0.2 |
| `minimumConfidence` | minimum acceptable confidence | 0.0 |
| `maximumObservations` | how many rectangles to return | 1 |

(Defaults are from a developer's [gist](https://gist.github.com/jbadger3/f743e9b389c698f5949a29696df8cf05) and the [objc2-vision bindings](https://docs.rs/objc2-vision/0.3.2/objc2_vision/struct.VNDetectRectanglesRequest.html). Treat them as indicative.)

**How Apple pairs it with ARKit.** Its sample [Tracking and altering images](https://developer.apple.com/documentation/arkit/tracking-and-altering-images) shows the pattern:
- run detection on the AR camera image **at most 10 times a second**;
- run requests **serially**, so a new one starts only when the last has finished;
- crop with a perspective correction from the 4 corners;
- then hand the 3-D work to ARKit.

**Two observations.**
- The 30° quadrature tolerance is applied **in the image**, because perspective bends right angles. Vision has no 3-D knowledge.
- We do. We can lift the 4 candidate corners onto the measured plane and check that the angles are **90° ± a few degrees in metres**. That is a much stronger filter than any 2-D tolerance.

**A second, ML-based detector.** Since iOS 15 Apple also ships [`VNDetectDocumentSegmentationRequest`](https://developer.apple.com/documentation/vision/vndetectdocumentsegmentationrequest), which returns a document's 4 corners plus a saliency mask. So Apple itself uses both kinds:
- a classical-style rectangle detector, tunable by geometry;
- an ML document detector, robust on clutter.

This supports the hybrid option in §4.

### 1.8 LiDAR vs non-LiDAR: what accuracy each gets (published numbers only)

| Configuration | Published figure | Source |
|---|---|---|
| LiDAR iPhone, small objects (>10 cm) | ±1 cm absolute | Luetzenburg 2021 (engine research §1.1) |
| LiDAR iPhone 13 Pro, centre-pixel range | MAE 0.8–1.4 cm at 1–3 m | PMC10939328 (engine research §1.1) |
| LiDAR iPhone 14 Pro, Measure auto rectangle | "~3 %" (informal) | SlashGear |
| Non-LiDAR iPhone, Measure | **No controlled data.** Informal: more snapping errors than LiDAR | SlashGear (iPhone 8) |
| ARKit (iPad Pro, LiDAR) vs ARCore (Samsung) at 10–100 cm | ARKit better; both worse at 10 cm | ITU Kaleidoscope 2022 |

**Takeaway.** The PLAN target is right: compare against a **non-LiDAR iPhone**, with our own measurements.

---

## 2. ARCore close-range limits, and what in our code makes the menu case worse

### 2.1 Platform facts

**Depth API range.**
- Google: depth is estimated from 0 to 65 m. "The most accurate results come when the device is **half a meter to about five meters** away" ([Depth guide](https://developers.google.com/ar/develop/depth)).
- Depth needs the device to move first ("only available after the user has started moving their device around").
- Featureless surfaces give "imprecise depth".
- No minimum range is documented. Below 0.5 m we are outside the stated band and must assume worse, sparser depth.

**Raw depth confidence** ([raw depth guide](https://developers.google.com/ar/develop/java/depth/raw-depth)):
- 0–255. Pixels without an estimate get 0. Textureless areas usually get 0.
- Raw depth is sparser but more accurate than full depth, and costs about half as much.
- On **glossy laminated paper**, specular highlights move with the viewpoint. Depth-from-motion triangulates the reflection, not the paper, so expect holes or outliers exactly where the glare is.

**Plane detection** ([fundamentals](https://developers.google.com/ar/develop/fundamentals)):
- Planes are built from clusters of feature points.
- "Flat surfaces without texture … may not be detected properly."
- A menu on a textured table usually *inherits* the table plane. The menu itself, glossy and with sparse text, contributes few points.
- When the phone is close and the menu fills the view, the table polygon may not yet extend under the menu.

**Focus mode** ([`Config.FocusMode`](https://developers.google.com/ar/reference/java/com/google/ar/core/Config.FocusMode)):
- The default is **FIXED**, "optimized for AR tracking" according to the SDK release notes.
- Google recommends AUTO only "while capturing pictures or video", and the default "for optimal AR tracking performance".
- Fixed focus on phones is set for mid or far distances, so at 0.3 m text and edges can be soft. AUTO helps sharpness but makes the lens move, and with it the field of view (focus breathing).
- ARCore exposes per-frame **`ImageMetadata`** (via `Frame.getImageMetadata()`) with these keys:
  - `LENS_FOCUS_DISTANCE`, `LENS_STATE`, `CONTROL_AF_STATE`;
  - `SENSOR_EXPOSURE_TIME`;
  - `LENS_INTRINSIC_CALIBRATION`.

  Keys can be missing per device ([ImageMetadata](https://developers.google.com/ar/reference/java/com/google/ar/core/ImageMetadata)). These keys let us *detect* focus moves and compute blur rather than guess.

**Motion blur.** Blur in pixels ≈ exposure time × focal length in pixels × (angular speed + linear speed ÷ distance).
- The **linear** part grows as 1/distance, so it dominates up close.
- Example: indoors at about 1/30 s, f ≈ 920 px (1280-wide CPU image), 0.3 m away, 0.3 m/s ⇒ ~30 px of blur.
- Even 0.1 m/s gives ~10 px.

**CPU image and field of view.**
- The Pixel 10a's main camera has an **82° field of view** ([Google Store spec](https://store.google.com/product/pixel_10a_specs)).
- With our 1280×720 CPU image (f ≈ 900–950 px; read the real value from `getImageIntrinsics`) on a 20:9 portrait screen, the view shows only about 570 of the 720 short-side pixels.
- So the **visible width ≈ 0.62 × distance**: 19 cm at 0.3 m, 31 cm at 0.5 m.
- An A4 page (21 × 29.7 cm, upright) fits with margin only from about **0.4 m**. A 25 × 35 cm menu needs about 0.5 m.

**A sensible minimum distance.**
- Hard minimum: **0.25 m**. Below this, depth is unsupported and blur and focus dominate.
- Soft minimum: **0.35 m** ("move back a little").
- Recommended band for flat objects up to A4: **0.4–0.7 m**. Larger objects: fit to frame, up to 3 m.
- These match Apple's 0.5–3 m and Google's 0.5–5 m within reason.

### 2.2 Our code path at close range (line numbers: working tree, 2026-11-25)

| # | Where | What it does | Why it hurts at 0.2–0.5 m |
|---|---|---|---|
| C1 | *(nowhere)* | No minimum range or "move back" for measuring. `FrontCaptureGate` has STEP_BACK only for the photo (`FrontCaptureGate.kt` CornerFit.OUTSIDE) | Users go as close as they like, where depth, focus, blur and frame-fit are all worst |
| C2 | `DwellTrigger.kt:176` `DRIFT_M = 0.01` (`DRIFT_REL = 0.01`) | A hands-free hold tolerates 1 cm of target drift at any range | At 0.3 m, 1 cm is 3.4 % of an A4 width. The dropped corner can be 1 cm from where the hold began |
| C3 | `SampleStabilizer.kt:31` `moveAbsM = 0.008` (`moveRel = 0.008`) | Samples within 8 mm of the estimate are averaged as "the same spot" | Slow hand drift up close becomes a smeared, biased corner, while the ± shrinks as more samples arrive |
| C4 | `FrameReaders.kt:81–82, 281–282` `NEARER_ABS_M = 0.03` | A depth or feature hit must be 3 cm nearer than the plane to beat it | At 0.3 m that is 10 % of range. A padded menu 5–10 mm thick always loses to the table plane, giving **1.7–3 % scale bias** at 0.3 m (thickness ÷ height) |
| C5 | `SurfaceChooser.kt:60–62` | An ARCore plane hit is returned as-is with `planeBiasRel = 0.0` | No plane-offset uncertainty for ARCore planes, so the ± is not honest about the menu-thickness and plane-height error |
| C6 | `FrameReaders.kt:63` `isPoseInPolygon` | Plane hits only inside the polygon | When the menu fills the view, the table polygon may not cover the menu yet. We then drop to sparse feature or depth hits on glossy paper (Apple would use the *infinite* table plane here) |
| C7 | `SurfaceChooser.kt:52,55` `CONSISTENT_ABS_M = 0.02`; `LocalSurfaceModel.kt:56–65` cone ≥ 4 cm, `maxRms` 8 mm, RANSAC inlier 1 cm, depth band ≥ 4 cm, `minPoints` 24 | Room-scale tolerances | At 0.3 m a 1 cm inlier band and 8 mm RMS let a tilted plane pass. With only 24 points needed in a 4 cm cone on glossy paper, fits are often refused, or noisy when they pass |
| C8 | `DepthPatch.kt:40` `minDepthM = 0.2`; `DepthPatch.kt:73` confidence ≥ 200; `FrameReaders.kt:285` 21×21 patch | Depth used from 0.2 m, near the reticle only | Fine as a filter, but below 0.5 m depth will be sparse. The patch covers only ±2.6 cm at 0.3 m, not the object's whole face |
| C9 | `TrackingGate.kt:69–70` `sampleMaxAngular 0.35`, `sampleMaxLinear 0.30` | Range-independent motion gate | ~30 px of linear blur allowed at 0.3 m (see 2.1). Corners, features and depth all degrade |
| C10 | `SessionSetup.kt:84` `focusMode = AUTO` | Continuous autofocus for the whole session | Google recommends the default (FIXED) for tracking. Lens moves are not detected, and samples taken during a focus sweep are averaged in |
| C11 | `MeasureSession.kt:474–475` | Intrinsics read **once** per session | With AUTO focus the effective focal length can change by a few %. For W8/W9 corner rays, the capture frame's own intrinsics must be stored |
| C12 | `TrackingGate.kt:73` `warmUpNanos = 0.5 s` | Half a second after tracking starts | Visual-inertial scale needs translation, not time. Walking up to a table and measuring at once gives a poorly scaled map |
| C13 | `Uncertainty.kt:127,130` tier floor `absFloor95 = 1 cm` | Every length gets ±1 cm at 95 % | At 0.3–0.5 m the floor dominates (±1 cm on a 21 cm side = 4.8 %). It may be too pessimistic up close once W9 is in, but **only change it on bench data** (coverage ≥ 90 %) |
| C14 | `AimModel.kt:42` `AIM_PIXELS_VGA = 2.0` | 2 VGA-px aim σ (≈1.2 mm at 0.3 m) | Fine for a reticle on detail. It **under-states** hands-free *corner* error, which is "how far the reticle is from the true corner" (several mm), not pixel aim |
| C15 | `MeasurementComposer.kt:27, 308`; `ScanComposer.kt:85` | Rectangle from 3 corners (4th inferred) via `Shapes.rectangle` | One bad corner moves two sides. A detected quad gives 4 corners plus a squareness check, and can use `Shapes.rectangleFromRays` (no lateral leak) |

**Most likely story for the user's menu test.** The phone was at 0.2–0.35 m, so the menu was wider than the screen. The user panned between corners while the dwell dropped points within a 1 cm drift window (C2, C3), with blur (C9) and focus hunting (C10). The corners landed on the table plane under a menu of some thickness (C4–C6). Each corner carried several mm of aim error, unmodelled (C14), and the 4th corner was inferred (C15).

---

## 3. Android-feasible equivalents, ranked by expected gain ÷ effort

"Gain" is expected accuracy and robustness gain for flat objects at 0.3–1 m. Effort: S < 1 day, M 1–3 days, L > 3 days.

| Rank | Apple technique | Android / SpaceView equivalent | Gain | Effort | Concrete parameters (starting values, tune on bench) |
|---|---|---|---|---|---|
| 1 | "0.5–3 m" advice, coaching | **Range guide + refusal** from the live hit range, plus a "fits in frame" check | High (removes the worst regime) | S | Refuse commit below **0.25 m**. Amber hint "Move back a little" for 0.25–0.35 m. Rectangle and scan modes also need the projected object (or detected quad) inside the view with ≥ 5 % margin, else STEP_BACK. Show the band "Hold 40–70 cm away" for small flats |
| 2 | Raycast to **existing plane infinite / estimated plane** | **Plane-first lifting for flat objects on a support surface.** Intersect each corner ray with the tracked ARCore plane's *full plane*, even outside its polygon, when depth or feature points under the object agree. Otherwise fit our own plane to depth + features **over the whole object region**. Estimate object thickness as the median offset of points on the object from the support plane | High | M | Agreement test: median signed offset of points inside the quad vs plane < max(2 mm, 0.5 % × range) ⇒ use plane. Otherwise shift the plane by the median offset when ≥ 30 points and MAD < 2 mm. Add plane σ ≈ max(1 mm, 0.5 % × range) to `planeBiasRel` (fixes C5) |
| 3 | Range-relative tolerances (implicit in Apple's design) | Make C2, C3, C4, C7 **scale with range** | High at close range | S | `DRIFT` = max(3 mm, 1 % × range); stabiliser move = max(3 mm, 0.8 % × range); `NEARER` = max(8 mm, 3 % × range); `CONSISTENT` = max(6 mm, 3 % × range); depth band = max(1.5 cm, 5 % × range); RANSAC inlier = max(3 mm, 1 % × range); `maxRms` = max(3 mm, 0.8 % × range) |
| 4 | Auto rectangle (Vision) + tap / hold | **W9 detector** (§4) + 3-D lift (item 2) + metric squareness check | Very high for hands-free rectangles | L | Run at ≤ 10 Hz, serially, on the worker. Corners to sub-pixel by line intersection. Lock when stable over **N = 8 frames** |
| 5 | Edge and point snapping | **Snap the reticle** to the nearest detected line or corner (same detector, small ROI). Haptic tick on snap | Medium–high for manual points | M | ROI 96×96 px around the centre. Snap radius **12 px** (CPU image) for corners and 8 px for lines. Only when the edge-fit residual is < 0.7 px |
| 6 | Tracked raycast | **Multi-frame fusion per corner.** Each frame, re-detect the corner near its predicted reprojection (pose known), re-lift on the current plane, and feed `PointEstimator`. At commit, also re-lift from anchors | Medium | M | Window 15–30 frames. Reject a frame if the corner moved > 1.5 px from its prediction. Natural hand motion gives a few mm of parallax, so 15 frames already average plane noise |
| 7 | Autofocus + per-frame intrinsics | **Focus- and blur-aware gating** with `ImageMetadata`. Drop samples while `LENS_STATE` = MOVING or AF is scanning. Compute blur = t_exp·f·(ω + v/Z). Read intrinsics **per frame** at capture (fixes C10, C11) | Medium–high up close | M | Accept a frame for corner detection when blur ≤ **1.0 px**, and for reticle samples when ≤ **2.5 px**. If the exposure key is missing, use 1/30 s. Keep AUTO focus, but A/B test FIXED vs AUTO on the bench (§6) and pick by tracking-loss rate and error |
| 8 | Scale initialisation (coaching overlay, "move iPhone") | **Scale warm-up.** Before the first commit, require ≥ **0.20 m** cumulative camera translation, with ≥ 10° of rotation change, since tracking began (or since a reset). Plus a tracked plane, or a fitted plane under the reticle | Medium (worst case large) | S | Reuse `SteadinessFilter` / `TrackingGate` pose deltas. Hint "Move your phone slowly side to side" |
| 9 | High-res frame capture | Higher CPU image only where pixels matter | Low (once corners are sub-pixel) | S | Keep 1280 (`SessionSetup.kt:90`). Revisit 1920 only for > 1.5 m captures |
| 10 | LiDAR features (edge guides, ruler, person height) | Not reachable without depth hardware. Edge guides can be imitated in 2-D (item 5). Person height exists as our Height mode | — | — | Don't claim parity |
| 11 | (Ours, beyond Apple) | **Reference-object scale** (A4, ID-1 card) on the same plane, already in `Calibration.kt`. With W9 the card's corners are detected automatically | Very high (scale 1 % → ~0.3–0.5 %) | S–M on top of W9 | Offer "Precision: place a card next to it" when the user wants < 1 % |
| 12 | (Ours) | **Glare hint.** If > 5 % of the quad interior is saturated (Y ≥ 250) or depth confidence inside the quad is mostly 0, say "Tilt the phone slightly to avoid glare" | Low–medium on laminated menus | S | |
| 13 | ARKit image scale estimation | ARCore **Augmented Images** can estimate an image's physical size: "If no physical size is provided, ARCore estimates the size and refines this estimate over time" ([docs](https://developers.google.com/ar/develop/augmented-images)). A runtime database built from the rectified crop gives an independent size estimate | Unknown. Experimental cross-check only | M | Needs ≥ 25 % frame fill and a quality score ≥ 75, plus a session reconfigure. Not for v3.1 |

---

## 4. Rectangle detector options for W9

### 4.1 Requirements that decide the choice

- **Corner accuracy matters more than recall.** One pixel at 1 m with f ≈ 920 is about 1.1 mm. We want ≤ 0.5 px σ on sharp edges.
- **Must work on our inputs:** NV21 Y plane, 1280×720, at 5–10 Hz, on the measurement worker, within about 15 ms.
- **Objects:** menus (often rounded corners, glossy), photo frames (glass glare, inner and outer borders), TVs (black on dark walls), boxes.
- **Size:** arm64 download currently about 10.5 MiB, target ≤ 15 MB, CI fails above 18 MiB. Installed size fails above 30 MiB.
- **Licence:** permissive for commercial use. Algorithms from papers are fine, but copying AGPL or GPL code is not.

### 4.2 Options

| Option | How it works | Corner accuracy | Robustness | APK impact (arm64 download) | Licence | Verdict |
|---|---|---|---|---|---|---|
| **A. Classical, pure Kotlin** (recommended) | Sobel gradients → edge points (Canny-style non-max suppression) or LSD-style line-support regions → line segments → merge collinear → build quads from 4 lines → score → **sub-pixel edge fit + line intersection** | **Best**: 0.2–0.5 px on sharp straight edges. Line intersection also handles **rounded corners** (gives the bounding corner, which is what W×H means) | Good on contrasty borders. Weaker on low-contrast objects (white menu on white cloth) and cluttered backgrounds | **~0 MB** (~50–100 KB of dex) | Ours. Implement from papers: Canny 1986; [LSD, von Gioi et al. 2012](https://www.ipol.im/pub/art/2012/gjmr-lsd/) (**reference code is AGPL, so do not copy it; implement from the paper**); EDLines (Akinlar & Topal 2011); Devernay 1995 sub-pixel non-max suppression | **Build first** |
| B. OpenCV (Java SDK) | Canny, HoughLinesP, `findContours` + `approxPolyDP`, `cornerSubPix`, LSD (restored under MIT-licensed NFA code from 4.5.4 per [OpenCV docs](https://docs.opencv.org/3.4/javadoc/org/opencv/imgproc/LineSegmentDetector.html) / [issue #2524](https://github.com/opencv/opencv_contrib/issues/2524)) | Good (`cornerSubPix` ~0.1–0.3 px on true corners; poor on rounded corners) | Same as A | Full `libopencv_java4.so` arm64 is about **13–14 MB** uncompressed in shipped apps ([F-Droid diffoscope](https://verification.f-droid.org/org.cimbar.camerafilecopy_10.apk.diffoscope.html)), so roughly +5–7 MB download: **over the 15 MB target, at risk of the 18 MiB gate**. A custom core+imgproc build is reported at ~1.5–8 MB uncompressed ([forum](https://answers.opencv.org/question/227945/build-opencv-library-from-source-for-android), [LearnOpenCV](https://learnopencv.com/install-opencv-on-android-tiny-and-optimized/)), but needs an NDK build in CI and has known dlopen/TBB pitfalls | Apache-2.0 (4.5+) | **No.** Size and build cost for algorithms we can write in under 1000 lines |
| C. ML Kit Document Scanner | Google's full scan flow through Play services | — | Excellent for documents | ~300 KB | Google APIs ToS | **Unusable in our AR flow.** The SDK provides "a dedicated viewfinder screen and preview screen" and returns JPEG/PDF results, with no corner coordinates documented. It cannot read ARCore frames ([docs](https://developers.google.com/ml-kit/vision/doc-scanner/android)) |
| D. ML Kit Subject Segmentation (**already a dependency**) | Foreground mask of the salient subject, run on a `Bitmap` of the CPU frame | Mask edges ~ a few px, so not usable directly for corners | Helps on clutter and low contrast, *if* the object reads as "the subject". Doubtful for a menu lying flat on a table | **0 MB** extra (Play-services model, already shipped) | Google APIs ToS (already accepted) | **Hybrid candidate:** ML proposes the region, A refines. Test before committing. Beta API |
| E. Document-corner keypoint model (e.g. [DocAligner](https://github.com/DocsaidLab/DocAligner), heatmap regression) on LiteRT | Small CNN outputs 4 corner heatmaps | Authors say upscaling shifts corners by **5–10 px**, so refinement is needed anyway ([arXiv 2306.05749](https://arxiv.org/abs/2306.05749) / project notes) | Strong on documents and clutter. Unproven on frames, TVs and boxes | Model ~1–5 MB (estimate). LiteRT runtime: **0 MB via [Play services](https://developers.google.com/edge/litert/android/play_services)** (`play-services-litert`; Google says it saves "up to ~8 MB" vs bundling), or ~1–1.5 MB bundled CPU runtime (estimate) | Repo listed as Apache-2.0. **Verify the LICENSE and the weights' training-data terms** (e.g. SmartDoc/MIDV datasets have their own terms) | Later, only if A + D miss the recall target on our test set. Needs ONNX → TFLite conversion and our own evaluation |
| F. Hybrid (D or E proposes, A refines) | Region or approximate corners from ML; line fit within ±12 px bands around each proposed side | Same as A | Best overall | 0 MB (D) or 1–5 MB (E) | as above | The planned upgrade path |

### 4.3 Recommended classical pipeline (design, not code)

1. **Input.** The Y plane of the CPU image (`FrameReaders.cpuImage` already copies NV21). Search at half resolution (640×360) within the central 80 % of the view. Refine at full resolution.
2. **Edges.** 3×3 Sobel, gradient magnitude and angle, non-max suppression, and a hysteresis threshold set from the image's gradient histogram (e.g. 80th and 95th percentiles). This adapts to low light.
3. **Segments.** Group edge pixels into line-support regions by gradient angle (LSD idea, tolerance 22.5°). Fit segments, keep those ≥ 4 % of the image diagonal, and merge collinear ones (angle < 2°, gap < 2 % of the diagonal).
4. **Quad hypotheses.**
   - Take pairs of roughly opposite segments with angle difference ≤ 30°, matching Apple's quadrature tolerance in 2-D, then close each pair with another pair.
   - Keep a quad only if:
     - it is convex;
     - every image-space corner angle is within 90° ± 45°;
     - its short side is ≥ 20 % of the view's short side (Apple's `minimumSize` 0.2);
     - its aspect ratio is ≥ 0.1.
5. **Score.** Edge support is the fraction of each side's length with aligned gradient. All 4 sides must be ≥ 60 %. Add contrast across the border and how centred the quad is.
6. **3-D check (our advantage over Vision).** Lift the 4 corners through the plane (§3 item 2), then:
   - require 3-D corner angles of 90° ± **4°** and opposite sides equal within **3 %**;
   - otherwise reject the quad, or mark the result ROUGH.

   This is what kills false quads such as table edges, shadows, and the menu's inner print border vs its outer border.
7. **Sub-pixel corners.**
   - For each side, sample edge positions across the side at full resolution. Fit a parabola to the gradient magnitude across the edge to get a sub-pixel peak.
   - Fit a robust line (Tukey weights), skipping 8 % at each end so that rounded corners and fingers don't bias it.
   - Intersect adjacent lines. Keep each line's residual: it becomes the corner σ fed to `AimModel`/`Uncertainty` (replacing C14 for detected corners).
8. **Tracking and lock.**
   - Predict next-frame corners by re-projecting the lifted 3-D corners with the new pose, and search ±12 px.
   - Lock after **8** consecutive frames that pass the blur gate (≤ 1 px), with a corner spread < 1 px and a 3-D spread < max(1 mm, 0.3 % × range).
   - Then fuse 15–30 frames per corner in `PointEstimator`.
9. **Choose between nested rectangles** (frame vs picture, menu vs print border). Prefer the outermost quad that passes 6, and show it. The user can still switch to "Adjust corners" (W8).
10. **Output.** Pixel corners + capture intrinsics + pose + plane (the same capture record W8 needs). Then `Shapes.rectangleFromRays` with 4 rays.

**Cost.** A 640×360 Sobel + non-max suppression pass is about 0.23 M pixels. A Pixel-class CPU core should handle the whole search in about 5–15 ms (estimate; profile on device). At 10 Hz that is about 10 % of one core.

---

## 5. Recommendation for W9 and W10

### 5.1 Build order (each item has a test)

**W10 (close range): do first. Small changes, high payoff, and they also help W9.**

1. **Range guide and refusal** (§3.1).
   - *Test:* a gate table test. 0.20 m → refused with hint MOVE_BACK. 0.30 m → allowed with hint. 0.50 m → OK.
   - *Test:* a rectangle whose projected quad exceeds the view → STEP_BACK.
2. **Range-relative tolerances** (§3.3; C2–C4, C7).
   - *Test:* at range 0.3 m, a depth hit 8 mm above a plane wins (today it loses).
   - *Test:* the dwell drift limit at 0.3 m is 3 mm.
   - *Test:* the existing room-scale tests stay green at 1–3 m.
3. **Blur and focus gate from `ImageMetadata`** (§3.7; C9, C10, C11).
   - *Test:* given t_exp = 33 ms, f = 920, v = 0.1 m/s, Z = 0.3 m ⇒ 10 px ⇒ LIMITED.
   - *Test:* LENS_STATE MOVING ⇒ LIMITED.
   - *Test:* a missing key ⇒ fallback exposure.
   - *Test:* intrinsics are stored with each captured still.
4. **Plane-first lifting with thickness estimate and plane σ** (§3.2; C4–C6, C8).
   - *Test:* synthetic points of a 6 mm-thick slab on a plane ⇒ shifted plane, with W error < 0.5 %.
   - *Test:* an ARCore plane hit gets `planeBiasRel` > 0.
   - *Test:* infinite-plane use only when interior points agree.
5. **Scale warm-up** (§3.8; C12).
   - *Test:* there is no commit before 0.20 m of cumulative path. The hint is shown.
6. **(Bench-gated) range-dependent tier floor** (C13). Only if §6 shows ≥ 90 % coverage with the smaller floor at 0.3–0.5 m.

**W9 (smart hands-free rectangles), after W10 items 1–4.**

1. **Detector core in `:engine`** (pure Kotlin, JVM-testable): edges → segments → quads → sub-pixel corners. Put the **sub-pixel line-intersection refiner** in one shared place so that W8's `CornerSnap` (currently whole-pixel Shi–Tomasi, `app/.../imaging/CornerEditing.kt`) can use it as well.
2. **3-D validation and lift** using W10 item 4's plane, with 4 rays into `Shapes.rectangleFromRays`.
3. **Temporal lock + fusion** (§4.3 item 8), and the UI: outline appears → holds → locks → measured. Fall back to the guided flow with a hint when:
   - the quad is unstable;
   - the view is > 45° off the plane normal for the lock (60° for detection only);
   - the plane is unknown;
   - the quad fails the 3-D squareness check.
4. **Reticle snapping** for manual points and Distance mode (§3.5), reusing the same detector in a small ROI.
5. **(Only if recall < target) hybrid proposal** with ML Kit Subject Segmentation, and then a LiteRT keypoint model.

### 5.2 W9 acceptance tests (synthetic, JVM)

Render a textured rectangle (an A4 with text-like texture; a frame with glass glare; a dark TV on a grey wall) on a textured or plain background. Use a pinhole camera with f = 920 at 1280×720.

**Conditions:**
- distance 0.3–1.5 m;
- tilt 0–60°;
- Gaussian blur σ 0–2 px, linear motion blur 0–5 px;
- noise σ 2–6 grey levels;
- border contrast 10–120 levels;
- corner radius 0–10 mm;
- one corner occluded by a "finger" blob.

| Metric | Target |
|---|---|
| Detection recall (contrast ≥ 30, tilt ≤ 45°, blur σ ≤ 1) | ≥ 95 % |
| Corner RMS error (blur σ ≤ 1) / (blur σ = 2) | ≤ 0.5 px / ≤ 1.0 px |
| Rounded corners (r ≤ 10 mm): virtual-corner error | ≤ 1 px |
| W/H error after lift on an exact plane, 0.5 m, tilt ≤ 30° | ≤ 0.3 % |
| False lock on scenes with no rectangle (table edges, tiles, shadows) | ≤ 1 per 200 sequences |
| Nested borders: picks the outer one | ≥ 95 % |
| Rejects tilt > 60°, quad partly out of view, < 8 stable frames | 100 % |
| Runtime per frame (measured on device, logged) | ≤ 15 ms median |

### 5.3 Expected error after W9 + W10 (Pixel 10a, Depth on, good light, textured table)

This is an error budget for a length L at range Z. The terms below are 1σ estimates:

- **visual-inertial scale:** σ_s ≈ 1 %;
- **plane distance bias:** σ_b ≈ 0.5 % at 0.5 m, 1 % at 1 m;
- **corner localisation:** 0.5 px × Z / f, per corner;
- **thickness after correction:** ≤ 1 mm.

| Object, distance | Main terms | Expected median abs. error | P90 |
|---|---|---|---|
| A4 long side (297 mm), 0.3 m | Doesn't fit the frame, so **refused / guided back** | — | — |
| ID-1 card (85.6 mm), 0.3 m | scale 0.9 mm, plane 0.9 mm, corners 0.2 mm | ~1–2 mm | ~3 mm |
| A4 long side, 0.5 m | scale 3 mm, plane 1.5 mm, corners 0.4 mm | **~2–4 mm** | ~6–8 mm |
| A4 long side, 1 m | scale 3 mm, plane 3 mm, corners 0.8 mm | **~3–5 mm** | ~8–10 mm |
| 60 × 40 cm frame, 1.5 m (engine research §1.4) | | ~5–8 mm | ~12–15 mm |

**Honest framing.**
- Up close, the error is **mostly proportional** (scale), so moving closer than about 0.4 m does not make an A4 more accurate. It only adds risk.
- Below about 1 %, you need the reference-object calibration.
- These numbers are hypotheses for §6, not claims.

---

## 6. Side-by-side benchmark protocol vs Apple Measure (non-LiDAR iPhone)

This extends engine research §6.3 and is meant to become the TESTING section that PLAN §2 asks for.

**Devices**
- Pixel 10a (SpaceView release build, Depth on).
- One **non-LiDAR** iPhone on current iOS (any non-Pro model, e.g. iPhone 16e/15/14), running Apple Measure.
- Record the model, iOS version and Measure's displayed resolution (note whether it shows whole cm).

**Ground truth.** Steel rule or tape (±0.5 mm) for objects up to 1 m, and a laser meter for distances. Measure each truth 3× and use the mean.

**Objects**
1. A4 sheet, matte (297 × 210 mm).
2. Laminated restaurant menu (glossy; measure its own size).
3. Padded or leather menu folder, 5–10 mm thick.
4. ID-1 card (85.60 × 53.98 mm).
5. 30 × 40 cm photo frame with glass.
6. Matte cardboard box face, about 40 × 30 cm.

**Placements**
- flat on a textured wooden table;
- flat on a plain white tablecloth;
- hung on a wall (frame).

**Distances** (lens to object centre, checked with tape): **0.3, 0.5, 1, 2, 3 m**. Use 0.3 m only for objects that fit, and use 2–3 m only for the frame and the box.

**View angle:** 0° and 30° from the surface normal. Add 45° on a subset.

**Light:** about 500 lux (bright) and about 100 lux (restaurant). Measure with a lux meter app on a third phone.

**Modes compared**
- Measure auto-rectangle (tap the white box) vs SpaceView hands-free rectangle (W9).
- Measure manual 2-point vs SpaceView manual Distance (reticle + snapping).
- Note when Measure shows no white box or refuses to measure.

**Procedure**
- **10 trials per cell and per device.** Alternate the device order (A-B-B-A).
- Restart each app's session every trial (leave the app and come back, or use a fresh measurement), so that map reuse doesn't flatter later trials.
- Hold the phone by hand for all trials. Add one tripod subset at 0.5 m to separate algorithm error from hand error.
- Use one main operator, plus a second operator for one subset (reproducibility).

**Record per trial**
- the displayed value(s);
- SpaceView's ± and badge;
- refusals and hints shown;
- time to result;
- whether the AR tracking warm-up was needed.

**Metrics**
- per cell: signed bias, median |error|, P90 |error|, relative error;
- SpaceView ± coverage (truth inside ±);
- abstain rate;
- time.

Compare both raw values and values rounded to Measure's display resolution, because rounding alone adds up to ±5 mm at whole-cm display.

**Statistics.** Use a paired Wilcoxon signed-rank test on |error| per object across cells (n = 10 per cell is small, so pool cells per object). Report the median difference with a bootstrap 95 % interval.

**Pass rule (PLAN §2)**, applied per object and distance group:
- SpaceView median |error| ≤ iPhone median;
- SpaceView ± covers the truth in ≥ 90 % of trials;
- **no confident result** (green or amber badge) below the minimum range, on untextured views or at steep angles. The app guides or refuses instead.

**Also log** (to decide C10 and C13): run a FIXED vs AUTO focus A/B on the menu at 0.4 and 0.6 m, comparing tracking-loss rate and error. Collect the ± coverage at 0.3–0.5 m before touching the tier floor.

---

## 7. Technique adoption register (PLAN §2 "record each adopted technique and its source")

| Technique | Source (public) | Adopted as |
|---|---|---|
| Optimal-range advice 0.5–3 m | [Apple Measure guide](https://support.apple.com/guide/iphone/measure-dimensions-iphd8ac2cfea/ios); [ARCore Depth 0.5–5 m](https://developers.google.com/ar/develop/depth) | W10.1 range guide + refusal |
| Raycast targets: existing plane infinite / estimated plane | [ARRaycastQuery.Target](https://developer.apple.com/documentation/arkit/arraycastquery/target-swift.enum) | W10.4 plane-first lifting |
| Tracked raycast (refined over time) | [ARTrackedRaycast](https://developer.apple.com/documentation/arkit/artrackedraycast) | W9.3 multi-frame corner fusion + anchors |
| Auto rectangle ("white box frames the object") | [Measure guide](https://support.apple.com/guide/iphone/measure-dimensions-iphd8ac2cfea/ios) | W9 hands-free rectangle |
| Rectangle-detector parameters (aspect, quadrature 30°, min size 0.2) | [VNDetectRectanglesRequest](https://developer.apple.com/documentation/vision/vndetectrectanglesrequest) | W9 hypothesis filters (§4.3 item 4) |
| Detection ≤ 10 Hz, serial requests | [ARKit sample: Tracking and altering images](https://developer.apple.com/documentation/arkit/tracking-and-altering-images) | W9 scheduling |
| ML document detector beside classical rectangles | [VNDetectDocumentSegmentationRequest](https://developer.apple.com/documentation/vision/vndetectdocumentsegmentationrequest) | W9 hybrid path (later) |
| Edge / point snapping | Measure behaviour ([SlashGear](https://www.slashgear.com/1884872/how-accurate-iphone-measuring-app/)) | W9.4 reticle snapping |
| Autofocus + per-frame intrinsics | [isAutoFocusEnabled](https://developer.apple.com/documentation/arkit/arworldtrackingconfiguration/isautofocusenabled); [ARCamera.intrinsics](https://developer.apple.com/documentation/arkit/arcamera/intrinsics) | W10.3 focus/blur gate, per-capture intrinsics |
| Coaching before measuring | [Measure guide](https://support.apple.com/guide/iphone/measure-dimensions-iphd8ac2cfea/ios) ("slowly scan"), [ARCoachingOverlayView](https://developer.apple.com/documentation/arkit/arcoachingoverlayview) | W10.5 scale warm-up |
| Physical-size estimation of a flat image | [ARCore Augmented Images](https://developers.google.com/ar/develop/augmented-images) | Not adopted (possible cross-check) |

---

## Sources

**Apple (developer documentation, guides, samples)**
- VNDetectRectanglesRequest: https://developer.apple.com/documentation/vision/vndetectrectanglesrequest
- VNDetectDocumentSegmentationRequest: https://developer.apple.com/documentation/vision/vndetectdocumentsegmentationrequest
- ARRaycastQuery.Target: https://developer.apple.com/documentation/arkit/arraycastquery/target-swift.enum
- ARTrackedRaycast: https://developer.apple.com/documentation/arkit/artrackedraycast
- ARFrame.hitTest (deprecated): https://developer.apple.com/documentation/arkit/arframe/hittest(_:types:)
- ARPlaneAnchor: https://developer.apple.com/documentation/arkit/arplaneanchor
- sceneDepth (LiDAR only): https://developer.apple.com/documentation/arkit/arconfiguration/framesemantics-swift.struct/scenedepth
- ARDepthData.confidenceMap: https://developer.apple.com/documentation/arkit/ardepthdata/confidencemap
- isAutoFocusEnabled: https://developer.apple.com/documentation/arkit/arworldtrackingconfiguration/isautofocusenabled
- ARCamera.intrinsics: https://developer.apple.com/documentation/arkit/arcamera/intrinsics
- captureHighResolutionFrame: https://developer.apple.com/documentation/arkit/arsession/capturehighresolutionframe(completion:)
- ARCoachingOverlayView: https://developer.apple.com/documentation/arkit/arcoachingoverlayview
- Sample, Tracking and altering images: https://developer.apple.com/documentation/arkit/tracking-and-altering-images
- WWDC19 "Introducing ARKit 3" (raycasting): https://developer.apple.com/videos/play/wwdc2019/604
- Measure user guide: https://support.apple.com/guide/iphone/measure-dimensions-iphd8ac2cfea/ios ; iOS 14: https://support.apple.com/en-asia/guide/iphone/measure-iphd8ac2cfea/14.0 ; iOS 15: https://support.apple.com/en-asia/guide/iphone/measure-iphd8ac2cfea/15.0 ; person height: https://support.apple.com/guide/iphone/measure-a-persons-height-iph341d4a993/ios
- Apple forum, intrinsics changing per frame: https://developer.apple.com/forums/thread/827789

**Google (ARCore, ML Kit, LiteRT)**
- Depth API overview: https://developers.google.com/ar/develop/depth
- Raw depth: https://developers.google.com/ar/develop/java/depth/raw-depth
- Fundamentals (planes, feature points): https://developers.google.com/ar/develop/fundamentals
- Config.FocusMode: https://developers.google.com/ar/reference/java/com/google/ar/core/Config.FocusMode
- ImageMetadata: https://developers.google.com/ar/reference/java/com/google/ar/core/ImageMetadata
- Camera (intrinsics): https://developers.google.com/ar/reference/java/com/google/ar/core/Camera
- Camera configs: https://developers.google.com/ar/develop/java/camera-configs
- Augmented Images: https://developers.google.com/ar/develop/augmented-images
- ML Kit Document Scanner: https://developers.google.com/ml-kit/vision/doc-scanner/android
- LiteRT in Google Play services: https://developers.google.com/edge/litert/android/play_services ; blog: https://developers.googleblog.com/en/smaller-apps-and-faster-gpu-inference-with-litert-in-google-play-services/
- Pixel 10a specs: https://store.google.com/product/pixel_10a_specs ; PetaPixel: https://petapixel.com/2026/02/18/google-says-new-pixel-10a-has-best-camera-under-500/

**Papers and tests**
- ITU Kaleidoscope 2022, ARCore vs ARKit at 10–100 cm: https://www.itu.int/en/publications/Documents/tsb/2022-Kaleidoscope-Academic-Conference-Proceedings/files/basic-html/page121.html (and p.122)
- SlashGear, "How accurate is the iPhone's measuring app": https://www.slashgear.com/1884872/how-accurate-iphone-measuring-app/
- Intrinsics change with autofocus: https://arxiv.org/pdf/2510.23589v1
- LSD (von Gioi et al., IPOL 2012; reference code AGPL, so implement from the paper only): https://www.ipol.im/pub/art/2012/gjmr-lsd/
- OpenCV LSD licence history: https://docs.opencv.org/3.4/javadoc/org/opencv/imgproc/LineSegmentDetector.html ; https://github.com/opencv/opencv_contrib/issues/2524
- OpenCV Android size reports: https://verification.f-droid.org/org.cimbar.camerafilecopy_10.apk.diffoscope.html ; https://answers.opencv.org/question/227945/build-opencv-library-from-source-for-android ; https://learnopencv.com/install-opencv-on-android-tiny-and-optimized/
- DocAligner: https://github.com/DocsaidLab/DocAligner ; https://arxiv.org/abs/2306.05749
- VNDetectRectanglesRequest defaults (third-party): https://gist.github.com/jbadger3/f743e9b389c698f5949a29696df8cf05 ; https://docs.rs/objc2-vision/0.3.2/objc2_vision/struct.VNDetectRectanglesRequest.html
- Miscited paper in the engine research (actual topic): https://arxiv.org/abs/2204.01693

*Numbers marked as estimates are engineering hypotheses (blur arithmetic, field-of-view coverage, error budgets, runtimes, model sizes). §6 is how they get confirmed or rejected on the Pixel 10a.*
