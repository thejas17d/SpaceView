# PLAN v3 (2D v1) review: commit 26dd340 against 756b2bd (W1–W7, Builders A and B, orchestrator wiring)

**Reviewer:** read-only, as in the previous reviews. This file is the only write.
**Scope:** `git diff 756b2bd 26dd340` (153 files, +5 505 / −13 221), checked against `docs/PLAN-v3-2D.md`. Context came from the last ~60 lines of `HANDOFF.md` (what the builders say they built, and the deviations they declared) and from `docs/reviews/SM1-M5-review.md` (format).
**Method:**
- **Read in full:**
  - `engine/range/*` and their tests;
  - `RangefinderCore`, `RangefinderSession`, `app/range/*`;
  - `VerticalPlaneAim`, `HeightAim` and `HeightAimTest`;
  - the `MeasureSession` diff;
  - `CutoutPixels`, `PngEncoder`, `GlbWriter.writeCutout`, the `GalleryRepository` diff, `Cutouts.kt` and `SubjectSegmenter.kt`;
  - the `ScanViewModel`, `PlaceViewModel`, gallery UI, pro/billing/settings, `AppNavHost` and `HomeScreen` diffs;
  - `AdsManager` (the guard, consent and show paths), CI `android.yml`, and TESTING §22/§23.
- **Commands:** `git` plus one read-only Python script, which checked:
  - EN/HI key parity;
  - placeholder parity;
  - `R.string` references with no definition.
- **No Gradle task was run** (as instructed), so no build, test or lint result here is mine.

## Verdict: **APPROVED WITH WARNINGS. No BLOCKER. Fix the 5 MAJORs before the Pixel 10a APK goes to the user; MAJOR 1 breaks a hard product rule.**

**Counts:** BLOCKER 0, MAJOR 5, MINOR 11. The nits at the end are not counted.

---

## BLOCKER

None. I found no crash path in the new code that normal use reaches; MINOR 7 is a defensive gap.

## MAJOR

**1. The Distance screen has no `ArScreenAdGuard`, so the UMP consent form (and, in a race, an interstitial) can appear over the AR camera:** `app/src/main/kotlin/com/spaceview/app/nav/AppNavHost.kt:152-155`; `app/src/main/kotlin/com/spaceview/app/ads/AdsManager.kt:200-203`, `:333-350`, `:387`.

- **Problem:**
  - The route comment says "no ads and no visit tracker". The visit tracker is correctly left out.
  - But `ArScreenAdGuard` is not an ad: it is the mechanism that keeps ads away from AR. Every other AR destination calls it (Measure `:212`, Calibrate `:235`, Scan, Place). Distance does not.
  - Without it, `visits.arInForeground` stays false while Distance is on screen. Three things then go wrong:
    - `gatherConsent` passes `canShowForm = { !visits.arInForeground }`, so the SM1-M5 gate G1/R2 fix ("re-check AR at form-show time") is bypassed.
    - `maybeShowAfterSave` decides with `AdScreen.NON_CAMERA` instead of `CAMERA_AR` (`:387`), so `AdPolicy`'s `BLOCKED_CAMERA_SCREEN` never fires.
    - `preloadIfUseful` and `preloadInterstitial` are no longer suppressed.
- **Scenario A (consent):**
  1. A first-run EEA user, or a user whose earlier consent request was deferred, lands on Home. `onNonArScreenResumed` → `gatherConsent` → the UMP request goes out over the network.
  2. The user taps "How far?" at once.
  3. The consent form arrives about 1–2 s later. `canShowForm()` is true, and the form opens as a dialog over the running AR session.
- **Scenario B (interstitial):**
  1. Save a measurement and close Measure. Home resumes, waits up to 1.5 s for AR to leave, then `maybeShowAfterSave` starts its own `scope.launch`. Cancelling `pendingShowJob` on Home's pause does **not** cancel that inner job.
  2. Tap "How far?" inside that window.
  3. The decision returns SHOW with `screen = NON_CAMERA`, the activity is still resumed, and the ad covers the camera.
- **Fix:**
  - Add `ArScreenAdGuard(ads)` at the top of the `Routes.DISTANCE` composable (or inside `RangefinderArScreen`, as Place does).
  - Optionally re-check `visits.arInForeground` immediately before `ad.show(...)` in `maybeShowAfterSave`.
  - TESTING §23 item 12 should add "first launch: tap How far? straight after Home; no consent form over the camera".

**2. Distance "Ground" is shown for points that are not on the ground. Evidence that the ray hits something first is thrown away, and a Near/Ground conflict is settled by the smaller ±, so the shown ± can miss the truth by a wide margin:** `ar/src/main/kotlin/com/spaceview/ar/RangefinderCore.kt:201`, `:214-221`; `app/src/main/res/values/strings_range.xml` (`range_assumes_level`).

- **Problem:**
  - `hit` is dropped when it is beyond `NEAR_MAX_M` (8 m), even though a hit at 9 m proves the ray is blocked before the floor.
  - Ground is then computed for the floor *behind* the object.
  - When both readings exist, `minByOrNull { bound95 }` picks one without checking that they agree, or that the Near hit is actually on the floor (`hit.y − floorY`).
  - The badge says "Assumes level ground", but nothing tells the user that the aimed point must *be* on the ground.
- **Scenario (camera 1.45 m, defaults, aim σ ≈ 0.004 rad):**
  1. The user aims at the foot of a wall or a parked car, 0.2 m above the paving, 9 m away. ARCore returns a depth hit at 9.0 m, which is discarded (> 8 m).
  2. The ray's depression is atan(1.25/9) = 7.9°, so Ground gives 1.45 / sin 7.9° = **10.5 m**.
  3. Its rel95 is about 8.6 %, inside the 10 % gate, so the screen shows about **"10.5 m ± 0.9 m · Ground"**. The truth (9.0 m) is outside the ±.
  4. A near case: a box top 0.3 m high, 3 m away, with no Near hit that frame. Ground says 3.8 m ± 0.15 m.
- **Fix:**
  - Keep the raw hit regardless of range.
  - Refuse Ground when any hit along the ray is nearer than the Ground distance minus its ±95.
  - Refuse Ground when the Near point is above `floorY` by more than a few σ.
  - Rename the badge to "Assumes the point is on level ground".
  - Add a `RangefinderCoreTest` with an off-floor hit at 9 m.

**3. Tracking loss during the RESULT phase is not recorded, so "Retake sight 2" can triangulate across a tracking loss:** `RangefinderCore.kt:191-198`, `:272-277`; `RangeUi.kt:48`; `RangefinderScreen.kt:367`.

- **Problem:**
  - Loss detection and path accumulation run only while `phase != RESULT`.
  - `retakeSight2()` checks `lostSinceSight1`, but nothing sets that flag during RESULT.
  - The spec's rejection rule (W4: "Rejected if tracking was lost between sights") is therefore not enforced on the retake path.
- **Scenario:**
  1. Sight 2 is refused ("different things"), and the retake button is offered.
  2. The user lowers the phone. The camera is covered, or points at a blank sky, so ARCore goes PAUSED. It then recovers, and the world frame may have shifted.
  3. The user taps Retake, walks and takes sight 2. `walk()` sees `lostSinceSight1 == false`, and `solve(..., trackingLostBetween = false)` returns a number from two frames that may not match.
  4. `pathLength` also missed the walk made during RESULT, so σB is understated.
- **Fix:**
  - Run the bookkeeping whenever `sight1 != null`, including RESULT. Alternatively, record a loss in every phase.
  - Add a test: result → NOT_TRACKING frames → `retakeSight2()` returns false, or the next result is TRACKING_LOST.

**4. The scan box-top fallback chooses the wrong corner's vertical plane in ordinary poses, giving a large, systematic height error that the ± does not cover:** `ar/src/main/kotlin/com/spaceview/ar/HeightAim.kt:150-155`, `:178-194`; `ar/src/test/kotlin/com/spaceview/ar/HeightAimTest.kt:92-107`.

- **Problem:**
  - `cornerUnderRay` picks the corner whose vertical line has the smallest *lateral* offset from the ray. It does not prefer the nearest corner, and it sets no maximum offset.
  - It also ignores the implicit 4th corner, `c0 + c2 − c1`. That corner is diagonal to `c1`, so in a diagonal view the nearest corner is often missing and `c1`, the *far* corner, sits exactly on the ray line.
  - The fallback runs whenever the hit is not on the top face. This is the common case when aiming at a top *edge*: the hit lands on the front face just below it.
  - The test passes only on a tie: all three offsets are 0.5 m, and index 0 wins.
- **Scenario (the test's own box: front edge z = −2, back edge z = −3, 0.8 m tall, camera 1.5 m):**
  1. The user aims at the top front edge 10 cm right of centre. The ray passes 0.40 m from the front-right corner and 0.35 m from the back-right corner.
  2. The back corner is chosen, so the plane is 1 m too far.
  3. The ray is pitched down about 19°, so the top lands at 1.5 − 3·tan 19.3° ≈ **0.45 m instead of 0.8 m**.
  4. For a wardrobe taller than the eye (TESTING §23 item 5), the same mistake overstates the height.
- **Fix:**
  - Intersect the ray with the four vertical side faces of the measured prism (all 4 corners) and take the first face hit in front of the camera, within the face's span plus a margin. That point is exact for a box.
  - Failing that, consider all 4 corners and choose the *nearest along the ray* among those within a lateral tolerance (for example, max(5 cm, 2 % of range)); refuse otherwise.
  - Add tests for the off-centre and diagonal cases.

**5. A cutout is cropped to the mask's bounding box and then stretched to the measured W × H, so a loose or partial mask distorts the object by up to 2× and the Review shows a different shape from Place:** `app/src/main/kotlin/com/spaceview/app/scan/imaging/Cutouts.kt:41-54`; `ScanViewModel.kt:253-258`; `data/.../GalleryRepository.kt` (`encodeCutout`, the GLB quad at `w.meters × h.meters`); `GalleryImages.kt` (`aspectRatio(b.width / b.height)`).

- **Problem:**
  - The rectified front photo is already metrically W × H: its edges are the measured outline. Cropping it further to the subject's bbox and mapping that crop onto W × H stretches it.
  - `MIN_BOUNDS_SHARE = 0.5` allows a 2× stretch on each axis.
  - Thin parts below confidence 0.5 (chair legs, table legs, cables) fall out of the bbox. The seat is then stretched down to the floor line.
  - The Review and the gallery draw the cutout at the *bbox* aspect, but the GLB in Place uses W × H, so "what the Review shows is what is stored" does not hold for the geometry.
  - The Review then says "only the object is saved, at its measured size", which is not true of the picture.
- **Scenario:**
  1. Scan a chair (Other). The legs come out at 0.3–0.45 confidence, so the bbox covers the top 65 % of the outline.
  2. Review shows a squat, legless chair on the checkerboard.
  3. Place shows the seat and back stretched 1.54× vertically, standing on the floor, at "true size".
- **Fix:** don't crop. Keep the full rectified canvas, with alpha from the mask, so the quad stays exactly W × H and the picture stays undistorted. Keep the bbox only for the trust check, and raise it to about 0.85 on each axis. If the plan's "bbox maps to W × H" wording is kept, it is the plan that needs correcting: the rectified photo already is the mapping.

---

## MINOR

1. **The 500 m cap and TESTING §23 promise more than a noisy estimate can deliver:** `RayTriangulation.kt:125`; TESTING §23 items 9 and 10; `RayTriangulationTest.kt:45`, `:75`.
   - The cap is applied to the point estimate.
     - A true 600 m target with a 30 m walk has a parallax σ of about 13 %, so about 7 % of results show a number (e.g. "480 m ± 180 m"). That is honest, but it breaks "never a number".
     - A true 480 m target reads "out of range" in about 40 % of runs.
   - "Walk 1 m on a 200 m target → near-parallel" can instead give "Not accurate enough yet", because the measured angle is noisy (σ ≈ 0.33°).
   - The Monte-Carlo test lifts the cap (`Double.MAX_VALUE`), so how often a real target near 500 m is reported as "out of range" is never measured.
   - **Fix:** either word TESTING as "usually" and accept, or use a cap rule on the interval. For example: out of range when the low end is > 500; a "> 500 m?" hint when the interval straddles 500.

2. **The Ground level-ground σ (0.15°, a 1 : 380 slope) is optimistic outdoors:** `RangeErrorModel.kt:230`.
   - Paving with a 1 % cross-fall (0.57°) at 12 m is off by about 8 %, against a shown ±95 of about 9.5 %. A 2 % fall is outside the ±.
   - The badge covers this in words, but the ± is the product's promise.
   - **Fix:** take this to the device test (TESTING §23 item 7) and be ready to raise the value to about 0.4°.

3. **The W5 far height base is Ground only (declared deviation; acceptable), but Measure never tells the user it assumes a level floor, and the source can flicker:** `MeasureSession.kt:1034-1058`.
   - A committed `HitKind.GROUND` base has no "assumes level floor" note in the result. Distance has the equivalent badge.
   - When the base aim alternates between a real hit and GROUND, each frame resets the other stabiliser (`:1058`), so the lock may never complete.
   - **Fix:** show a note for GROUND endpoints, and stop the stabilisers from resetting each other on a single-frame switch.

4. **The vertical-plane top assumes the aimed top is directly above the base, and the ± has no term for an offset:** `VerticalPlaneAim.kt:68-90`.
   - If the base is 10 cm in front of the door line, the height is off by 10 cm × tan(pitch): 17 cm at 60°.
   - **Fix:** the Measure hint and the Help should say "base exactly below the top". Consider adding a small offset term to the ±.

5. **The Retake button does nothing after a pause during RESULT:** `RangefinderScreen.kt:367`; `RangefinderCore.kt:306`.
   - `reset()` sets `lostSinceSight1 = true`, so `retakeSight2()` returns false.
   - For an Ok result (`rejected == null`), the UI still offers Retake.
   - **Fix:** expose `canRetake` from the core in `RangeState`.

6. **W6 tuning is mostly diagnosis:** `MeasureSession.kt:453-512`.
   - The plan's "tune" items were not done: TrackingGate, SampleStabilizer and SurfaceChooser are unchanged, and the "depth points usable immediately" path is not visible. The diagnosis may justify that, but TESTING §23 items 1–2 must decide it.
   - The main thread still does the hit test and the depth-patch copy for every repeated SceneView frame; only the worker skips them. Skipping those frames at the main thread, as `RangefinderSession.onFrame:103` does, saves CPU.
   - `sessionStartMs` is not reset on pause/resume within one session, so after a resume the diagnostics times are not measured from the resume.
   - The repeated-frame skip itself is correct:
     - `lastTs` is the last *processed* timestamp, so DROP_OLDEST cannot starve sampling;
     - `TrackingGate` ignores dt ≤ 1e-4;
     - `DwellTrigger` is time-based (ns), so hands-free timing is unaffected.

7. **`Sight(...)` validates with `require` on the AR main thread:** `RangefinderCore.kt:341`, `:383`; `RayTriangulation.kt:21`.
   - A non-finite ray or σ from a bad frame would throw from `onFrame` and crash.
   - It is unlikely with the current inputs, but the rule is "never crash".
   - **Fix:** check `isFinite` first, or wrap the construction in `runCatching` and skip the frame.

8. **Cutout texture budget:** `GalleryRepository.CUTOUT_TEXTURE_SIDES`.
   - An RGBA PNG of a photo at 512 px is roughly 400–600 KB, so with `MAX_SCAN_BYTES = 500 000` most cutouts end at 320–384 px (declared "may look soft").
   - If even 256 px doesn't fit, `encodeCutout` silently saves a plain panel after the Review showed a cutout.
   - **Fix:** record the size actually chosen in TESTING §22 item 17. Consider `alphaMode MASK` with a JPEG + separate alpha, or a per-item budget exception for cutouts.

9. **ML Kit Subject Segmentation is a beta library (16.0.0-beta1), and it bumps ML Kit common/vision-common:** `gradle/libs.versions.toml`, `ci/release-dependencies.txt`.
   - The Data safety notes defer the key check: "check that subject segmentation is covered by the same disclosure".
   - **Before release:**
     - re-run TESTING §21 (the naming chip);
     - confirm on Google's live ML Kit disclosure page that subject segmentation is covered, and record the date read.
   - The EN/HI privacy text is accurate: on-device, diagnostics through Play services, no "everything stays on your phone".

10. **The CI debug-absent grep does not cover the W6 debug HUD:** `.github/workflows/android.yml:157`.
    - The HUD is behind `BuildConfig.DEBUG`, a constant, so R8 strips it. A cheap regression guard is to add `dbg track` to the grep list.
    - W7 itself is sound:
      - `FAKE_BILLING` is a constant `false` in release (`app/build.gradle.kts:145`), and release is minified;
      - the switch and the button sit only inside `if (BuildConfig.FAKE_BILLING)`;
      - their texts are unlocalised literals, so the dex grep is meaningful.

11. **TESTING §23 item 5 ("aim above its front corner") and §22 item 12 ("at the measured W:H") describe behaviour the code does not have yet** (MAJOR 4 and MAJOR 5). Update them with the fixes.

## Deviations declared in HANDOFF: judgement

- **Ground works only to about 13 m, and 2-sight at 500 m ends near ±160 m:** acceptable *as honest engineering*. It is a product decision, though:
  - PLAN §5 acceptance "30 m (Ground) inside ±" cannot pass by Ground;
  - a 500 m reading is "Rough" (±32 %).
  - The user must sign off on it, or on the proposed zoomed-aim and longer-walk work, before a store listing says "up to 500 m".
- **W5 far base Ground only (no 2-sight):** acceptable for v1. See MINOR 3.
- **Depth ON by default (orchestrator):** correct and consistently wired: `MeasureSettingsRepository.kt:93`, `:218`, plus `MeasureUiSettings`, `ScanSettings`, `RangeUiSettings` and the Help copy. Delete my data returns it to the new default.
- **Cutout texture ≤ 512 px and the help key name:** acceptable (MINOR 8, nit).

## What I verified OK

- **Range maths:**
  - Ground: `d = h / sin θ`, with `(σd/d)² = (σh/h)² + cot²θ·σθ²`.
  - Closest approach of two rays: the s/t formulas are correct.
  - The expected miss is projected on the common normal, one axis per error source.
  - The 7-source central-difference Jacobian is measured from sight 2.
  - The inverse-law bound `d·a/(1−a)` is the correct upper half-width for d ∝ 1/angle, using σ evaluated at the measured parallax; it is conservative on the low side.
  - The refusal order is correct: tracking lost → parallel → behind → angle < 0.5° → miss > 3.5σ → unbounded.
- **Monte-Carlo tests:** not tautological. The truth is independent of the estimator, and the inputs are drawn from the model (aim on both sights, heading drift on sight 2, baseline σ, VIO scale). They test linearisation and coverage. They cannot test whether the model matches reality; that is the device test's job.
- **`RangeFormat`:** reuses `Units`' ± rounded up and value-to-leading-place rule ("500 m ± 18 m", decimal feet). It never shows more digits than the ±.
- **`RangefinderSession`:**
  - it is a proper ADR-001 primary listener (configures the session, depth only when wanted and supported);
  - it skips repeated frames and runs on the main thread only;
  - pause and new-session reset the core, and a pending 2-sight becomes TRACKING_LOST;
  - events go through `tryEmit` into a buffered flow;
  - `close()` never touches the session.
- **`VerticalPlaneAim`:**
  - the sign and geometry are correct;
  - the 75° grazing refusal works;
  - the σ is the aim × 1/cos g plus the base-depth leak;
  - with the plane re-oriented every frame, the point does not jump while the aimed top is above the base: that line lies in every such plane;
  - HEIGHT composes the vertical difference.
- **W1 removal:**
  - no dangling code reference;
  - `R.string` references all resolve, and EN/HI key and placeholder parity is complete (only the non-translatable `app_name` is EN-only);
  - furniture, scan3d and the pro strings were removed in both locales;
  - Room stays v3;
  - legacy TEMPLATE and MESH rows place from their stored GLB, or from a cache box (no crash); a template is not editable (the UI hides Edit and the repository refuses it);
  - `GLB_VERSION` is unchanged, so legacy GLBs stay "usable";
  - `ProCatalog` / `GallerySaveGate` API trimmed cleanly; `PaywallDestination` maps a removed feature name to NO_ADS (tested);
  - "Delete my data" no longer keeps a 3D quota key.
- **W2/W3:**
  - pixels are straight alpha end to end, and the PNG encoder is correct (filters, CRC, IHDR RGBA8);
  - edges are bled before the PNG, so there is no dark halo from black transparent pixels;
  - the alpha-weighted downscale is correct;
  - the GLB quad winding and UVs are correct (unlit, double-sided, BLEND only with PNG);
  - the segmenter is null-safe: a 10 s timeout, cancellation propagates, the client closes when the task completes, `LinkageError` and missing Play services fall back;
  - share sends `image/webp` with a `.webp` name;
  - Place: CENTER origin with 2 mm thickness, so the bottom edge rests on the floor and the quad sits 1 mm off the wall; the fit check uses the row's W/H/D, never a zero depth.
- **Orchestrator:** `Routes.DISTANCE` and the Home row are wired; there is no visit tracker (correct). The guard is missing: see MAJOR 1.

## Nits (not counted)

- Home now has both "Distance" (measure) and "How far?" (rangefinder). Make sure the detail lines tell them apart in Hindi too.
- The live `RangeIssue.OUT_OF_RANGE` from Ground is unreachable for a camera below about 2.2 m, because the 0.25° depression check comes first. It is harmless.
- `help_templates_title` / `help_templates_body` are now about cutouts; rename the keys when convenient.
- `place_mesh_fallback` says "3D shape unavailable" for any fallback box; for a legacy template "model unavailable" would read better.

---

## Summary

- **Counts:** BLOCKER 0 · MAJOR 5 · MINOR 11. Verdict: approved with warnings; fix the MAJORs before the device APK.
- **Top risks:**
  1. **Ads/consent over AR:** Distance lacks `ArScreenAdGuard` (MAJOR 1). This is a one-line fix and a hard product rule.
  2. **Wrong numbers with a confident ±:**
     - Ground accepts off-ground points (MAJOR 2);
     - the scan box top uses the wrong corner plane (MAJOR 4);
     - a retake after a tracking loss is triangulated anyway (MAJOR 3).
  3. **Cutouts distorted by up to 2×** because the mask's bbox is stretched to W × H (MAJOR 5). Don't crop; keep the full rectified canvas with alpha.
- **Verified OK:**
  - the W4 maths and error propagation, including the inverse-law bound and non-tautological Monte-Carlo tests;
  - the honest display rounding;
  - the `RangefinderSession` lifecycle and threading;
  - the vertical-plane geometry, the grazing refusal and the leak term;
  - the repeated-frame skip (no starvation, dwell unaffected);
  - depth ON wired everywhere;
  - W1 removal with no dangling references, full EN/HI parity, legacy rows placing without a crash, Room v3;
  - W7 test unlock unreachable in release;
  - straight-alpha and PNG correctness;
  - flat-Place geometry;
  - an accurate privacy text (pending the live ML Kit disclosure check, MINOR 9).
- **Deviations:**
  - The declared range limits (Ground ~13 m, ±160 m at 500 m) are honest but need the user's sign-off against PLAN §5.
  - The Ground-only far base is acceptable for v1.
