# Plan v3.1: precision round (Pixel 10a feedback, 2026-11-25)

Follows `PLAN-v3-2D.md`; everything there still applies.

## 1. User feedback (Pixel 10a, internal-testing build 1 (0.9.0))
1. After a frame or object is scanned, the user must be able to choose **hands-free (automatic)** or **manually adjust the rectangle's sides/corners**.
2. Hands-free doesn't capture rectangles accurately. Make it smart: a small on-device detector for **automatic angle/edge detection and auto-crop** in hands-free mode.
3. Measuring an object up close (a restaurant **menu**) was inaccurate. Use Apple ARKit as the reference and imitate its logic. It must be **as accurate as ARKit**, and that accuracy should carry over to the other features where needed.

## 2. What "as accurate as ARKit" means (accuracy contract)
- **Hardware reality:** Apple's best numbers come from **LiDAR** (iPhone Pro models). The Pixel 10a has no ToF or LiDAR, so LiDAR-class accuracy is physically out of reach on it. Never claim it.
- **Target:** match or beat **Apple's Measure app on a non-LiDAR iPhone** at the same distances and objects. This is a **side-by-side benchmark**: same objects, a tape-measured truth, 10 trials each, 0.3–3 m, including close-range flat objects (an A4 menu, a photo frame).
- **Pass rule:**
  - SpaceView's median |error| ≤ the iPhone's;
  - SpaceView's ± covers the truth in ≥ 90 % of trials (PLAN §9);
  - SpaceView never shows a confident result where it can't measure (too close, too steep, untextured): it guides or refuses instead.
- **Imitate behaviour and design only** ("REA" rule: study how top apps behave; never copy code or assets). Use public sources: Apple developer docs, WWDC sessions, Apple's Measure app user guide, papers. Record each adopted technique and its source in `docs/research/close-range-and-rectangles.md`.

## 3. Workstreams

### W8. Manual or automatic rectangle after a scan (Builder B)
- **After capture, Review offers "Auto" or "Adjust corners".**
  - **Auto** = the detected and measured corners (W9 when hands-free).
  - **Adjust corners:** on the frozen front photo, the user drags the 4 corners (or the 4 sides). There is a magnifier loupe under the finger, and corners snap to strong image edges within a few px, with an option to turn snapping off.
- **W×H is recomputed from geometry, never from pixel ratios.** Each corner pixel → a camera ray (capture intrinsics + pose) → intersected with the measured plane of the object's face → metres. The ± comes from the existing AimModel per corner plus the plane σ.
  - If the capture didn't keep pose, intrinsics and plane, add them to the capture/draft. That is an in-memory and SavedState field, **not a Room schema change** (v3 stays).
- **Downstream:** the cutout, rectification, gallery and Place all use the adjusted corners. "Reset to auto" is always available.
- **Tests:**
  - reprojection round-trip;
  - a 2 px drag changes W by the expected amount;
  - the adjusted rectangle is honoured by the cutout;
  - process death keeps the adjusted corners.

### W9. Smart hands-free rectangles: auto edges, angle and crop (Builder A, after research)
- In hands-free rectangle and scan modes, **detect the rectangle in the camera image** and propose its 4 corners, with no point-by-point dwell needed. The user holds steady → the outline locks → measured.
- **Detector:** choose by measured accuracy on our test images, not by fashion.
  - Options: classical line-segment + quad fitting with sub-pixel corner refinement; a small on-device model (≤ ~1–2 MB, or Play-services-delivered, so the APK size gate is respected); or a hybrid (ML proposes the region, geometry refines the corners).
  - Apple's Vision rectangle detection is the behaviour reference.
- **3D lift:** detected corners → rays → the AR plane (depth/plane hits), with the same ± as manual corners.
- **Rejection:** if the quad isn't stable over N frames, perspective is too steep, or the plane is unknown, it falls back to the normal guided flow with a hint.
- **Tests:** synthetic perspective images (angles 0–60°, blur, low contrast); corner error in px; W/H error after lifting.

### W10. Close-range accuracy (Builder A, after research)
- **Diagnose the "menu" case:**
  - **minimum range:** ARCore depth/plane behaviour under about 0.3–0.4 m;
  - **focus and motion:** the camera's focus distance, and motion blur;
  - **detection:** feature starvation on glossy or laminated paper, and plane detection on a small object lying on a table;
  - **aim maths:** whether the AimModel/tier floors are right at 0.2–0.5 m.
- **Then adopt ARKit-style techniques** where they help, each with a test:
  - an **optimal range guide** ("move back a little", "hold 30–60 cm away") that refuses below a minimum distance;
  - **raycasting against the estimated plane of the surface the object lies on**, with edge snapping (Apple Measure snaps to edges and corners);
  - **multi-frame fusion** of depth and plane hits;
  - **auto-focus lock** or a continuous-focus check;
  - small-object **plane fitting** from the local depth patch.
- **Apply the same improvements** to Measure, Rectangle, Scan and Height where relevant.
- **Report:** the before/after expected error vs distance, and a device benchmark protocol (TESTING section) for the side-by-side iPhone comparison.

## 4. Order and ownership
1. **Research agent:** `docs/research/close-range-and-rectangles.md`. It covers how Apple ARKit, RealityKit, Vision and the Measure app achieve close-range accuracy and rectangle detection (public sources), ARCore close-range limits, Android-feasible equivalents, detector options with size and accuracy trade-offs, and a recommendation for W9/W10.
2. **Builder B:** W8 now. It owns Review, imaging, the gallery and the capture-draft fields. If it needs `:ar` capture data, it coordinates through the orchestrator.
3. **Builder A:** W9 + W10 after the research lands. It owns `:ar`, `engine`, measure and ScanArScreen.
4. **Then:** review → FRIDAY gate → commit → CI → internal-testing build 2 (versionCode 2).
