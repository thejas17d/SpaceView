# Plan v3: 2D focus for v1 (from Pixel 10a device feedback, 2026-11-24)

The user tested the app on a **Google Pixel 10a** (ARCore Depth API supported, no ToF) and decided: **v1 removes the 3D features and focuses on 2D measurement; a real 3D engine is the next update.** This plan replaces the 3D parts of `ARCHITECTURE-v2.md` for v1. Everything else in ARCHITECTURE-v2 (accuracy honesty, ads policy, Pro, quality bar PLAN §9) still applies.

## 1. User feedback (verbatim points, Pixel 10a)
1. Finding a surface isn't tuned well; it takes patience.
2. New feature: the distance to a point based on where it is in the camera view.
3. Objects far away aren't recognised well, for height and measurements.
4. After an image is saved, the background must be erased; only the true-to-size image is saved.
5. Distance to a far point in metres, **maximum 500 m**. No measuring tools in this mode: it only finds the true ± distance to a point.
6. While measuring height, if the phone is tilted too much the crosshair loses its point instead of stretching the frame.
7. The "3D" scan works like a ruler and doesn't walk the user around the object, so it feels like a gimmick.
8. Pro "only asks for payment": **we can't test Pro**.

## 2. User decisions (2026-11-24)
| # | Decision |
|---|---|
| U1 | Remove the on-device 3D scan + 3D Scan Lab and the furniture templates from v1. **Keep Place in room, as a flat, background-free, true-size cutout** (stands on the floor or hangs on a wall). All 3D code is parked on branch **`next/3d-engine`** (= commit 756b2bd) for the next update. |
| U2 | Rangefinder: **automatic method + 2-sight triangulation**, up to 500 m, always with ±. |
| U3 | Pro testing: a visible "Pro (test)" switch on debug builds; licence testers on Play for release builds. |
| U4 | Height: **vertical measuring plane** through the base point. |

## 3. Workstreams

### W1. Remove 3D from v1 (Builder B)
- **Delete from main:**
  - `app/furniture`, `strings_furniture*`, the Home "Furniture" row and its route;
  - the debug `scan3d` Lab and `strings_scan3d*`;
  - `:ar` shape-scan pieces used only by the Lab: DepthCaptureListener, ShapeScanGovernor, ShapeScanPipeline, and DepthCapture if Lab-only;
  - `engine/recon` and `engine/furniture`, together with their tests.
- **Keep FloorEstimator**: the rangefinder needs it (W4).
- **Room schema stays at v3**: no v4 and no migration. The unused mesh/template columns stay.
- **Legacy gallery rows** (box, template or mesh models saved by earlier test builds) must still open and place from their stored GLB file. Nothing may crash if a template id can't be regenerated.
- **ProCatalog:** remove `FURNITURE_TEMPLATES` and `DEVICE_SCAN` (and the 3D-scan quota counter wiring added on 2026-11-23). Update the paywall benefits to what v1 really has.
- **Size:** report the release size change. CI debug-only-absent and dependency-guard baselines are updated.

### W2. True-size cutout instead of a 3D box (Builder B)
- **Flat** objects (frames, TVs, posters): the rectified front photo already *is* the object (its corners are the measured corners). Save it as a WebP **with alpha**, cropped exactly to the corners.
- **Box / Other** objects:
  - run **ML Kit Subject Segmentation** (Play-services, unbundled: no model in the APK) on the front photo, then crop to the subject mask;
  - the mask's bounding box maps to the measured W × H;
  - if segmentation is unavailable or unsure, fall back to the rectangle crop and say so in Review.
- **Review** shows the cutout on a checkerboard, so the user sees that the background is gone. An "Undo cutout" option keeps the rectangle.
- **Model:** a flat GLB (an unlit, double-sided textured quad with alpha, at true W × H; `GlbWriter.writeCutout`). Depth stays a measured number on the item; it is not rendered.
- **Gallery thumbnail** = the cutout.
- **Data safety:** update ML Kit subject segmentation in the Data safety notes and the privacy text (on-device).

### W3. Place in room: flat (Builder B)
- Floor: the cutout stands upright, with its bottom edge on the floor. Wall: it hangs flat on the wall.
- Move, rotate and nudge work as now. **Check the fit** uses the measured W × H (and D if measured).
- The alpha must render correctly, with no black halo.

### W4. Rangefinder: distance to a point, ≤ 500 m (Builder A)
- **New mode "Distance"**:
  - Home row + its own screen. Crosshair only, no measuring tools.
  - Result: **line-of-sight distance from the phone to the aimed point, in metres (ft optional by units setting), with ±**, a method badge and the Good/Fair/Rough quality.
  - **Range cap 500 m**: beyond that, it shows "out of range", never a number.
- **Methods**, chosen automatically and shown as a badge:
  1. **Near (AR hit, ≤ ~8 m):** the existing plane/depth/feature hit pipeline and its ±.
  2. **Ground (point on the ground, beyond the hit range):** camera height above the tracked floor (FloorEstimator) and the ray's depression angle → distance.
     - ± comes from σh, σθ (the aim and attitude model in AimModel), plus a level-ground term.
     - Shown with "assumes level ground".
     - Used only while its relative ± ≤ ~10%; otherwise the app offers 2-sight.
  3. **2-sight triangulation (up to 500 m):**
     - Aim and lock sight 1. The app tells the user how far to walk sideways; the target baseline comes from the distance estimate and the target ±, about d/30 to d/50, capped at about 30 m. Then aim and lock sight 2.
     - The point = the closest approach of the two rays (world frame from ARCore VIO).
     - ± by propagation: σθ per sight, VIO baseline error (about 1% of path + drift), and the ray miss-distance as a consistency check.
     - **Rejected if:** tracking was lost between sights, the rays are near-parallel (intersection angle < ~0.5°), or the miss-distance is far beyond the ±.
- **Pure maths** lives in a new `engine/range` package (RayTriangulation, GroundRange, RangeUncertainty), with JVM tests:
  - synthetic scenes at 2, 10, 50, 150, 300 and 500 m;
  - Monte-Carlo coverage of the ± ≥ 95% at 1σ-model inputs;
  - rejection rules.
- **Honesty:** the UI never shows more digits than the ± supports (500 m ± 20 m shows "500 m ± 20 m", not "503.27 m").

### W5. Height that keeps stretching when tilted (Builder A)
- After the base point is set, the second point comes from **intersecting the crosshair ray with a vertical plane through the base point**. The plane faces the camera (its normal is the horizontal projection of the camera's forward direction at lock time, re-oriented each frame), so tilting the phone just slides the top along a vertical line.
- **± grows** as the ray grazes the plane. It is shown honestly, and refused beyond about 75° grazing.
- **Far base:** if the base point is beyond the hit range, use the W4 distance (Ground or 2-sight) to place the base, then the vertical plane.
- Applies to Measure height and to the Scan outline's top point (box height).
- Add a test for the "tilt up 60° keeps the point" case.

### W6. Faster, steadier surface finding (Builder A)
- **Diagnose first:**
  - log time-to-first-usable-hit and the hit source (plane, depth or feature) in a debug HUD line;
  - check that on a Depth-capable phone (Pixel 10a) depth hits are ON by default and used before planes exist;
  - check that the plane-required gates are not too strict (TrackingGate, SampleStabilizer windows, SurfaceChooser).
- **Then tune:**
  - depth-hit points usable immediately with their (larger) ±;
  - a clear coaching line ("move the phone slowly side to side") until the first hit;
  - no wait for a fitted plane unless the mode needs one.
- **Target:** first usable point within about 2 s on a textured floor and about 4 s on a plain wall, on the Pixel 10a.
- Record the numbers in TESTING.

### W7. Pro testing (Builder B)
- **Debug builds:**
  - a clearly visible **"Pro (test)" switch** in Settings → Developer (the existing FakeBillingControls), plus a "Test: unlock Pro" button on the paywall.
  - Both are debug-only and checked absent from release by CI.
- **Release / Play:** a TESTING section on adding licence testers (Play Console → Settings → License testing), so test purchases are free.

## 4. Ownership and order
- **Builder A:** W5 → W6 → W4 (`:ar`, `:engine` range, `app/measure`, a new `app/range` screen). It exposes `RangefinderRoute(onBack)`, and the orchestrator wires the nav and Home.
- **Builder B:** W1 → W7 → W2 → W3 (removals, `app/scan`, `app/place`, `:data` gallery, `GlbWriter.writeCutout`, pro/paywall/settings, Home/nav for removals).
- **Orchestrator:** the Home/nav wiring for Distance, the docs, the build lock, the review → FRIDAY gate → commit → CI → APK for the Pixel 10a.
- **Builds:** one Gradle build at a time (lock `.build.lock`); the PC has very little free RAM.

## 5. Acceptance (device, Pixel 10a)
- **Surface:** first point ≤ 2 s on a floor and ≤ 4 s on a plain wall.
- **Height:** measuring a 2 m door from 3 m away while tilting up to 60° keeps the point; the result lies inside its ±.
- **Distance:**
  - 5 m tape check inside ±;
  - 30 m (Ground) inside ±;
  - 100 m and 300 m (2-sight) against a map, GPS or known distance, inside ±;
  - 600 m shows "out of range".
- **Cutout:** a photo frame and a chair save without a background; Place shows them at true size, with no halo.
- **Pro:** a debug build can switch Pro on and off without paying; the release build has no test switch.
