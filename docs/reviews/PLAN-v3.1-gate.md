# PLAN v3.1 gate: precision round (W8 adjust corners, W9 smart rectangles, W10 close range) — combined Review + Final Judge

FRIDAY, 2026-11-26. Gate commit: **1e27120** ("PLAN v3.1 precision … (pre-review)"), against **0df92e2** (the v3 gate tree). Docs, HTML, `docs/privacy`, `docs/terms` and store assets were excluded from the code review.

I checked it against:
- `docs/PLAN-v3.1-precision.md` (W8, W9, W10, §2 accuracy contract);
- `docs/research/close-range-and-rectangles.md` §4.3 and §5;
- TESTING §24 and §25;
- the last ~40 lines of `HANDOFF.md` (the builders' claims and declared follow-ups).

No source was edited and no Gradle task was run (low RAM). Test, Kover, assemble, lint and release-dex results are the orchestrator's: engine 266 / ar 174 / data 70 / app 350, 0 failures. This file is the only write.

## Verdict: **FAIL for closed testing as-is → PASS WITH CONDITIONS once R1–R4 land**

No BLOCKER. **Four MAJORs**, all small to fix (each a few lines plus a JVM test):

- **V1:** the rectangle finder's commit bypasses the W10 refusals (too close < 25 cm, scale warm-up). The HUD says "Too close" while the finder commits a confident result.
- **V2:** in hands-free Rectangle/Scan-flat mode the old dwell dropper still runs and usually drops the first corner at the crosshair (the object's centre) before the finder can lock, which switches the finder off. W9 is then mostly unreachable.
- **V3:** "Adjust corners" drops the scale calibration factor but keeps the "calibrated" flag. A calibrated scan jumps by the calibration factor (typically 1–4 %) as soon as any corner moves, still labelled calibrated.
- **V4:** detected quads are lifted onto the plane of the single **centre** hit, with no check that the quad contains that hit. A rectangle on a parallel surface at another depth passes the metric-rectangle check and is committed with a small ±.

The engine geometry itself (FaceReprojection, QuadDetector, CornerRefiner, QuadLock) is **correct**: conventions, pixel-centre maths, corner ordering, intrinsics scaling and the ± algebra all check out (§4). Every MAJOR is in the wiring around it.

Build 2 (versionCode 2) may go to closed testing once **R1–R4** are in and green. MINORs can ride along or follow in the RC.

---

## 1. MAJOR findings

| # | Where | Problem and concrete failure scenario | Fix |
|---|---|---|---|
| **V1** | `ar/RectangleFinder.kt:112-161` (no range/warm-up check before detect or commit); `ar/MeasureSession.kt:691-711` (`commitDetectedRectangle` checks mode, points, tracking only). Compare `:635` and `processCommitRequest` (`TOO_CLOSE`, `WARMING_UP`). | **The W10 refusals are bypassed by W9.** `commitDetectedRectangle` checks neither `warmUpDone` nor the range guide. `RectangleFinder` uses `latestCentreHit` at any distance. **Scenario A (too close):** a menu on a table, phone at 0.20 m, table plane already tracked (its hit is valid at any range). The HUD shows "Too close. Move back to at least 25 cm." (`closeRangeHintRes` outranks the finder hint). Meanwhile the finder locks in about 1 s and commits a "Good" A4 result. This is exactly the "confident result where it can't measure" that PLAN §2 forbids, and TESTING §25 item 1 ("0.20 m refuses") fails in Rectangle mode. **Scenario B (warm-up):** open Measure → Rectangle with hands-free on and point at a frame. The tracking gate warm-up is 0.5 s and the lock is ≥ 0.8 s, so the result lands with no 20 cm / 10° sweep, while every other path refuses with "First move your phone slowly side to side." | **(a)** In `commitDetectedRectangle`, refuse (`false`) when `!warmUpDone`, or when any corner's `rangeM < RangeGuide.REFUSE_BELOW_M`. Better: when the mean corner range is TOO_CLOSE per `RangeGuide.advise`. **(b)** In `RectangleFinder.onFrame`, skip detection and publish no rectangle hint while `st.warmingUp` or `RangeGuide.advise(hit.distance) == TOO_CLOSE` (the HUD guide already explains why). **Test:** extract a pure predicate (e.g. `DetectedRectangleGate.mayCommit(warmUpDone, cornerRanges)`) and table-test it: warm-up not done → false; a corner at 0.22 m → false; 0.5 m after warm-up → true. |
| **V2** | `app/measure/MeasureScreen.kt:139-144`, `app/scan/ScanArScreen.kt:128-132` (`rememberHandsFree(… suspended = …)` ignores the finder); `ar/MeasureSession.kt:626-637` (`autoDropTarget`); `ar/RectangleFinder.kt:116-119` (the finder stops at `pointCount > 0`) | **The dwell races the finder, and the dwell usually wins.** With hands-free on, both the `HandsFreeDropper` (dwell 700 ms after the stabiliser locks) and the finder are active in RECTANGLE mode, and in Scan FLAT OUTLINE. To frame a menu the user aims at its middle and holds still. The dwell fires after about 0.7–1.2 s. The finder needs 8 agreeing detections at ≥ 100 ms each, and only on frames with blur ≤ 1 px, so ≥ 0.8 s and usually more. **Scenario:** Measure → Rectangle, hands-free on, A4 at 0.5 m, hold steady. The first "corner" drops in the middle of the sheet. `pointCount` = 1, so the finder clears its outline and stops. The user is back in point-by-point mode with a wrong first point. PLAN W9 ("no point-by-point dwell needed") and TESTING §25 items 5 and 7 fail, and the outcome is timing-dependent. **Note:** after V1's fix the warm-up blocks the dwell's first point too, so the race moves to right after the warm-up. It does not go away. | Suspend the dwell for the **first** point while the finder is enabled and has not given up. For example, pass `suspended = … \|\| (finderEnabled && pointCount == 0 && !finderTimedOut)` to `rememberHandsFree` in both screens. **Spec fallback ("falls back to the normal guided flow with a hint"):** after N s (e.g. 6 s) with no lock, set `finderTimedOut`, re-enable the dwell and show a hint such as "No rectangle found: aim at each corner". **Test:** a JVM test of the gate function (finder enabled, 0 points → dwell suspended; timed out → active); TESTING §25 item 5 adds "the first point is never dropped by the dwell while the outline is searching". |
| **V3** | `app/scan/CornerAdjust.kt:59-77` | **The adjusted W×H loses the calibration but keeps the badge.** `auto.width`/`height` come from `ScanComposer.outline` → `reading.primary/secondary`, which `Shapes` multiplies by `UncertaintyModel.scaleFactor` with the calibrated scale σ (`Uncertainty.kt:208-216`). `FaceReprojection.measure` returns **raw** geometry with the tier's uncalibrated scale σ. `CornerAdjust` still copies `calibrated = auto.calibrated`. **Scenario:** the user calibrates with a bank card (factor 0.97) and scans a 500 mm frame, so Auto shows 500 ± 4 mm, calibrated. In Adjust corners they nudge one corner by a pixel and tap Done. The width becomes about 515 mm, still labelled calibrated: a 15 mm jump that has nothing to do with the corners. `CornerAdjustTest.autoCornersReprojectToTheMeasuredSize` uses an uncalibrated auto outline, so it cannot catch this. | **Carry the scale** into the adjusted outline. Compute `k = auto.width.value / FaceReprojection.measure(autoCorners…).width.value`, or better, store the calibration factor and its effective σ in `ScanOutline` and in the `ReviewSnapshot` (bump to version 2). Multiply W and H by k, and use the calibrated scale σ instead of `tier.scaleSigmaRel` (add a `scaleSigmaRel` parameter to `measure`). If that is not done, set `calibrated = false` and say so. **Test:** a calibrated auto outline (factor 0.97) with unchanged corners gives the same W within 0.1 %, and the flag stays true only when the factor was applied. |
| **V4** | `ar/RectangleFinder.kt:124-139` (plane = `hit.position`/`hit.normal` of the centre hit); `engine/rect/QuadLock.kt:158-174` (`pickRectangle`/`detectRectangle`: outermost metric rectangle, anywhere in the image) | **A quad is lifted onto a surface it is not on.** Manual corners are hit-tested where they are. The finder lifts all 4 detected corners onto the plane of **one** hit at the screen centre, and keeps the outermost candidate that is a metric rectangle on that plane. Any rectangle on a surface **parallel** to that plane, but at another depth, lifts to a perfect, uniformly scaled rectangle. It passes the 4°/3 % squareness check, locks and commits with a ± of about 2 %. **Scenario 1:** crosshair on a coffee-table top at 1.0 m, with the floor visible beyond it; a floor tile or rug edge 0.45 m lower is detected (outermost first) and committed about 30 % too small. **Scenario 2:** a TV on a wall bracket (8 cm proud) at 1.5 m. The screen is black, so the centre hit falls through to the wall plane or interpolated depth, and the bezel quad is lifted onto the wall: about 5 % too large, against a ± of about 2.3 %. **Related:** `SupportPlane.resolve` (thickness/DISAGREES) is built and tested but **not wired** (HANDOFF lists it as a follow-up). A DEPTH_POINT hit brings an ARCore depth normal, noisy at close range, and no normal σ enters the ±. | **Minimum for build 2:** require the crosshair to lie **inside** the detected quad. Either the centre hit, projected with the same `CaptureCamera`, is inside the image quad, or the hit point is inside the lifted 3-D quad. Otherwise treat it as "no rectangle". Change `rect_no_plane` (EN/HI) to "Aim at the rectangle itself". **Prefer** a PLANE-kind hit's normal, or the `LocalSurfaceModel` fit normal, over a raw depth-point normal. **RC:** wire `SupportPlane.resolve` with the depth/feature points inside the quad (refuse on DISAGREES, shift on SHIFTED), and add a normal-tilt σ term. **Test:** a `SyntheticScene` pair (the true rectangle on z = −0.45 with the lift plane z = 0 and the crosshair outside the quad) → `detectRectangle` + the containment check returns null. |

---

## 2. MINOR findings

| # | Where | Problem / scenario | Fix |
|---|---|---|---|
| **v5** | `ar/FrameReaders.kt:122-126`, `:323-325`; TESTING §25 item 4 | **The "8 mm, so a 5–10 mm menu wins" claim is false beyond about 0.27 m.** The tolerance is max(8 mm, **3 %** × range): 15 mm at 0.5 m and 12 mm at 0.4 m. A 5–10 mm padded menu at 0.4–0.6 m still loses to the table plane, so §25 item 4 ("points land on the menu, not on the table") will fail on the device. The ± still covers it: the bias is 1–2.5 % against the 1 cm floor and the 2 % scale. The unit test only checks 10 mm at 0.30 m, where 3 % is 9 mm. | Use a close-range REL: e.g. max(8 mm, 1.5 % × r) below 1 m, keeping 3 cm / 3 % from 1 m. Or fix the comment and §25 item 4. Test: an 8 mm hit at 0.5 m wins; a 2 cm hit at 2 m still loses. |
| **v6** | `ar/RectangleFinder.kt:157`; `engine/rect/QuadLock.kt:119-126` | **Ghost outline, and the "consecutive" frames are not consecutive.** On `found == null` the lock is not told, so `lastCorners` and `stable` survive. After the user pans away, the old outline stays drawn (world-anchored) under "Hold the whole rectangle on the screen". `reject()` also keeps `lastCorners`. Detections interleaved with misses still count towards the 8. | Add `QuadLock.miss()`: before the lock, reset `stable`/`last`; clear `lastCorners` after 2–3 misses. Test it. |
| **v7** | `ar/RectangleFinder.kt:131`, `CloseRange.kt:324`; `TrackingGate.kt:156-158` | **The blur gates may starve detection, and they tighten room-scale sampling.** At f ≈ 920 px and 33 ms exposure, the detection gate of 1 px allows only ω + v/Z ≤ 0.033 rad/s (≈ 1.9°/s), close to handheld tremor indoors: the finder may sit on "Hold steady". The sample gate of 2.5 px now applies in **every** mode. At 1–3 m it caps ω at about 0.08 rad/s (4.7°/s), against the old 0.35 rad/s. This is stricter than "identical from 1 m on", so Height/Distance lock times can rise in dim rooms. `CONTROL_AF_STATE_PASSIVE_SCAN` counts as "lens moving", and with `FocusMode.AUTO` that happens on every refocus. | Device: log `blurPx` and the AF state in the `dbg` line (TESTING §25 item 9 already asks for that, but the line has no blur field; see D1). Record lock times against §23. Tune `MAX_BLUR_DETECT_PX` to 1.5–2 if the finder rarely runs. |
| **v8** | `app/scan/ScanViewModel.kt:659-668` (`onCleared` → `forgetPhoto`); TESTING §24 item 11 | **"Don't keep activities" deletes the kept photo.** With that developer option the Activity is destroyed without a configuration change, so the nav-entry ViewModel is cleared and `onCleared` deletes the cache JPEG. The SavedState bundle, already written, still holds the key, so the Review returns with "The photo was lost…". A real low-memory process kill works. But §24 item 11 names "Don't keep activities" first, so the tester will log a failure. | Change §24 item 11 to use only `adb shell am kill` (or the "Background process limit"). Optionally, do not delete in `onCleared`, and prune the folder on the next `put` or Scan entry. |
| **v9** | `app/scan/ScanViewModel.kt:94-108`, `:360-369` | **A race can leave a user photo in the cache.** `put` runs asynchronously on `Default`. If Save, Back or Measure again (`forgetPhoto` → `remove`) runs before the write finishes, the JPEG is written afterwards and stays until the next scan or "Delete my data". It is privacy-minor (app-private cache). | Have `keepPhoto` check `photoKey == key` after writing, and delete otherwise; or serialise `put`/`remove` on one dispatcher. |
| **v10** | `ar/RectangleFinder.kt:164-179` | **The detected corners' ± is mostly floor.** Corner σ = max(fit σ, 0.3 px) × range / f with `includesLocalisation = true`, so no AimModel term, no per-frame VIO jitter, no plane-normal term and no unlocalised stand-in. Coverage now rests on the tier floor (1 cm) and the 2 % scale. That is fine today, and my budgets for A4 at 0.5–1 m stay covered. **But:** research C13 (lowering the floor at 0.3–0.5 m) must not be done for detected corners without adding these terms back. | A condition on C13 (R5). Optionally add a 0.5–1 px "corner definition" term and the plane-normal term (V4) now. |
| **v11** | `ar/MeasurementComposer.kt:187-188`, `engine/rect/QuadLock.kt:194-201` | **W and H appear swapped in Measure.** Detected corners are ordered in the **sensor** image (landscape), so on a portrait phone "W" (the first edge) is the edge that runs vertically on the screen. A portrait A4 reads "W 297 × H 210". Scan is not affected: `ScanComposer.firstEdgeHorizontal` re-orients. | Rotate the image order to display orientation before `orderCorners` (`Frame.transformCoordinates2d` or the display rotation), or start the order at the screen top-left. |
| **v12** | `app/scan/ScanArScreen.kt` (ScanHud) | **Scan FLAT shows the finder's outline but none of its hints:** "Face the rectangle more squarely", "Aim at the surface…" and "Rectangle found. Hold steady." never appear in Scan. | Pass `finderState.hintRes()` to ScanHud, as MeasureHud does. |
| **v13** | `ar/FrameReaders.kt:275-281` called from `RectangleFinder.kt:135` | **Main-thread cost:** every 100 ms the full YUV → NV21 conversion of a 1280×720 frame (≈ 1.4 MB) runs on the AR/main thread, although only the Y plane is used. | Copy the Y plane only (row by row, respecting rowStride), then close the image. |
| **v14** | `app/scan/CornerAdjust.kt:31-35` | **The loupe zoom stands in for the drag scale.** `pixelSigma` passes `LOUPE_ZOOM` (3) as view px per image px, but the drag is applied at the fit scale `s` (≈ 0.8 on a phone), so a 1 view-px finger step is about 1.25 image px. The pixel term is therefore understated by about 25 %. It is negligible against the floor, so this is a nit. | Pass the editor's actual `s` (or max(1, 1/s)), or document 1 px as the bench-tuned value. |
| **v15** | `app/scan/CornerEditor.kt:94-133` | **TalkBack cannot adjust corners:** dragging is the only way to edit (no semantics actions). Auto remains usable, so this is not blocking. | Add custom accessibility actions (select a corner; nudge it 1 px or 5 px up/down/left/right), or note it as a known limitation. |
| **v16** | `ar/CloseRange.kt:338-342`; `ar/MeasureSession.kt:505-508` | **The warm-up docs and behaviour differ.** The KDoc says "reset on loss of tracking", but the session resets only on resume or a new session. Per-frame VIO jitter also accumulates into `path`, so holding still reaches 20 cm in a few seconds and only the 10° turn really gates. That is acceptable (not a trap), but TESTING §25 item 2's "about 20 cm side to side" overstates it. | Fix the KDoc; optionally add path only for steps > 2 mm. |

### DOC
- **D1:** TESTING §25 item 9 asks to "record the blur estimate in the `dbg` line", but `surfaceDebugText` has no blur field. Add it (debug only; the CI grep already covers `dbg track`), or drop the sentence.
- **D2:** HANDOFF's "1 % of range still rules from 0.3 m" and "8 mm so a 5–10 mm thick object wins at arm's length" (FrameReaders KDoc) must be corrected with v5.

---

## 3. Focus areas: what I verified OK

**(1) Geometry and ± (engine)**
- **Conventions:** `FaceReprojection.project` and `Ray.fromPixel` are exact inverses: camera −Z forward, +Y up, image v down, `cy − fy·y/z`. ARCore's `Camera.getPose()` is defined relative to the CPU image readout (+X right, +Y up), the same convention the v3 front-photo projection (`ImageProjection`) already validates on the device. `RectangleFinder` and the front capture both pair `camera.pose` with `imageIntrinsics` scaled to the acquired image (`CpuImageCamera.scaledTo`). There is no display-oriented pose mix-up.
- **Intrinsics per frame (W10 C11):** the front photo's `CaptureCamera` uses this frame's intrinsics at the bitmap's own size, so the W8 photo and its camera match. `ReviewSnapshot` round-trips all 13 numbers.
- **The ± algebra:**
  - W = mean of the two opposite edges, so the corner variance is Σσ²/4 (correct).
  - The plane-offset term is L·σ_plane/range (correct: a plane shift scales the lift by (Z+δ)/Z).
  - Scale and floor are in quadrature.
  - The obliquity 1/cos is conservative.
  - The ± is not understated for W8, whose plane σ is max(side σ/√2, planarity) and so includes the floor and scale: generous.
- **QuadDetector maths:**
  - pyramid centres (quarter i → 4i + 1.5; half → 2i + 0.5) and their inverses;
  - Sobel/NMS diagonal directions with y down;
  - the opposite-polarity pairing;
  - the per-side polarity support;
  - convexity with angle limits;
  - outermost-first ordering.
- **CornerRefiner:**
  - the parabola sub-pixel peak and its bounds;
  - TLS with Tukey re-weighting;
  - side k = corners k→k+1 and corner k = side k−1 ∩ side k;
  - σ = end σ in quadrature / sin(angle), with a 0.15 px floor, then 0.3 px in the commit and 0.5 px in the W8 snap.
- **QuadLock:**
  - `orderCorners` (atan2 with y down gives screen-clockwise, starting at min x + y) matches `FaceReprojection.measure`'s TL, TR, BR, BL;
  - squareness is checked in 3-D;
  - the lock rule is 8 agreeing frames, with outliers skipped after the lock;
  - the fused value never gets a smaller ± than one frame.
- **Tests are not tautological:** `SyntheticScene` renders with 3×3 supersampling, blur, noise, wood/text texture, rounded corners, a finger and nested frames. Detection is image-based; only the projection convention is shared with the code under test, and that convention is independently device-validated (above).

**(2) Threading and lifecycle (RectangleFinder)**
- `busy`, `lock`, `committed` and `_state` are touched only on Main (the scope is `Main.immediate`; `withContext(worker)` returns to Main).
- The detector runs on a `limitedParallelism(1)` worker; the image bytes are copied and the `Image` is closed on Main.
- A stale result is dropped when `lock` was replaced (pause, restart) or the finder was disabled or closed.
- `close()` is idempotent and tied to `DisposableEffect`.
- The pose and the plane come from the same frame as the image. The anchors are created from fused world points, so no stale `Frame` is used.
- A refused commit is retried on the next detection.

**(3) Tolerance changes at 1–3 m**
- DwellTrigger drift max(3 mm, 1 % r), SampleStabilizer max(3 mm, 0.8 % r), SurfaceChooser max(6 mm, 3 % r), LocalSurfaceModel inlier/RMS (capped at the room values), the depth band (5 % r ≥ 4 cm from 0.8 m), and NEARER (3 % rules from 0.27 m) are all **identical at ≥ 1 m**.
- **Intended changes at 1–3 m:**
  - ARCore plane hits now carry `planeBiasRel` = 0.5 %, so ± widens by about 10 % at room scale. That is honest, but expect slightly larger ± than in the §23 notes.
  - The blur gate (v7).
- `HeightAim` keeps `planeBiasRel` = 0.

**(4) W8**
- `QuarterTurns` matches `Matrix.postRotate(90·k)` with bounds translation for k = 0..3, and `toSensor`∘`toDisplay` is the identity. `upright` picks the turn that puts the top edge up.
- Snapping runs in sensor pixels on the un-rotated luma.
- A dragged corner resets its snap σ to NaN; the snap σ floor is 0.5 px (`CornerSnap.MIN_SIGMA_PX`, enforced again in `CornerAdjust`).
- Refusals: a crossed or degenerate quad, or a ray missing or grazing the plane (cos < 0.1), keeps the editor open with an EN/HI message in a live region.
- Unchanged corners mean Auto.
- **SavedState:**
  - the auto outline, the auto and adjusted corners, the snap σ, the camera, the tier, edits, label, tip, hands-free and cutout-undone are all saved;
  - the adjusted outline is recomputed deterministically on restore;
  - the photo comes back from `cache/scan-review`, which "Delete my data" also clears.
- The front-photo bitmap ownership is safe: `takeFrontPhoto()` nulls the flow, so the session never recycles the VM's `source`.

**(5) Strings, ads and logging**
- All 11 new `strings.xml` keys and 10 `strings_precision.xml` keys have HI twins with matching numbers (25 cm, 40–70 cm).
- No ad code changed. The editor is a Dialog on Review, not over AR.
- The timing `Log.d` is behind `logTiming = BuildConfig.DEBUG`, and the `dbg` HUD line is still behind `BuildConfig.DEBUG`. The orchestrator reports a clean release dex.

---

## 4. Conditions for build 2 (versionCode 2) → closed testing

| # | Owner | What | Check |
|---|---|---|---|
| **R1** | Builder A | **V1:** the detected-rectangle commit respects TOO_CLOSE and the warm-up; the finder pauses while the guide refuses. A pure predicate and its test. | Test green; TESTING §25 item 1 adds "Rectangle mode, hands-free, 0.20 m: never measured". |
| **R2** | Builder A + B (screens) | **V2:** the dwell is suspended for the first point while the finder searches; after a timeout, a fallback hint and the dwell come back. Gate test. | Test green; §25 items 5 and 7 add "no dwell drop while the outline searches". |
| **R3** | Builder B | **V3:** the calibration factor and σ are carried into adjusted corners (or the flag is dropped honestly); a test with a calibrated outline. | Test green; §24 adds "calibrate with a card, scan, nudge a corner: W moves only by the geometry". |
| **R4** | Builder A | **V4 minimum:** the crosshair must be inside the quad; prefer the plane or fitted normal over a raw depth normal; `rect_no_plane` copy (EN/HI). A synthetic parallel-plane test. | Test green; §25 item 6 adds "crosshair on a table, a tile or rug in view beyond: never locks". |
| **R5** | Orchestrator | **Batch with R1–R4:** v5 (the close-range NEARER rule, or a corrected §25 item 4), v6, v8 (the §24 item 11 wording), v12, D1, D2. HANDOFF line; `python docs/build_html.py`; commit; a **green CI run** on that commit; record v10 as a condition on any C13 floor change. | CI log; the HANDOFF line. |
| **R6** | User (device) | TESTING §24 and §25 on the Pixel 10a, especially §25 items 5 and 8. **Record:** time to lock, how often "Hold steady" blocks detection (v7), and Height/Distance lock times against §23. Tune `MAX_BLUR_DETECT_PX` from the results. | HANDOFF numbers. |

**May follow in the RC:** v7 (tuning), v9, v10 (if C13 is not touched), v11, v13–v16, and wiring `SupportPlane.resolve` (V4, full fix).

**Accuracy contract (PLAN §2):** the side-by-side iPhone benchmark (§25 item 10) has not been run. Build 2 must not claim "as accurate as ARKit" in the listing or release notes until it passes. `store-assets/listing-en-US.txt` should be checked for any such claim before submission.

---

## Summary
- **Verdict:** FAIL as-is; **PASS WITH CONDITIONS** after R1–R4, which are small, local and testable.
- **Findings:** 0 BLOCKER, **4 MAJOR**, 12 MINOR, 2 DOC.
  - **MAJORs:**
    - V1: the finder bypasses the too-close and warm-up refusals;
    - V2: the dwell races the finder and drops a centre point;
    - V3: adjusted corners lose the calibration but keep the badge;
    - V4: quads are lifted onto the centre-hit plane without a containment check, so a parallel surface gives a confident wrong size.
  - **Most relevant MINORs:**
    - v5: the "8 mm" menu rule is ineffective beyond 0.27 m, so §25 item 4 will fail;
    - v6: ghost outline;
    - v7: the blur gates may starve detection and tighten room-scale sampling;
    - v8: "Don't keep activities" deletes the kept photo.
- **Verified sound:**
  - the engine geometry and ± algebra;
  - the detector, refiner and lock maths, with non-tautological synthetic tests;
  - finder threading and lifecycle;
  - tolerance invariance at ≥ 1 m;
  - QuarterTurns and SavedState restore;
  - EN/HI parity, no ads over AR, debug-only logging.

---

## Re-check (fix round)

FRIDAY, 2026-11-26. Working tree against **c0b6ecd** (SpaceView.html excluded). Only the changed hunks and the tests they name were read; no Gradle task was run. The orchestrator reports engine 269 / ar 179 / data 70 / app 354, 0 failures.

| # | Status | Where (fix) | Covering test |
|---|---|---|---|
| **V1** | **FIXED** | `ar/CloseRange.kt` `DetectedRectangleRules.mayCommit/maySearch`; `MeasureSession.kt:695` (commit refuses before the warm-up or with any corner < 0.25 m); `RectangleFinder.kt:143` (no search while warming up or too close) | `CloseRangeTest.aDetectedRectangleObeysTheWarmUpAndTheMinimumRange` |
| **V2** | **FIXED** (one residual, N1) | `CloseRange.kt` `FinderPriority` (6 s); `RectangleFinder.kt:134-139` publishes `dwellSuspended`; `MeasureScreen.kt:145`, `ScanArScreen.kt:131` OR it into `suspended`. The finder is created before the dwell, and `enabled` → `restart()` clears the flag, so leaving Rectangle mode never leaves the dwell stuck. | `CloseRangeTest.theDwellWaitsForTheFinderThenComesBack` |
| **V3** | **FIXED** | `CornerAdjust.kt:112-116,136-141`: factor k = Auto W×H ÷ the raw geometry of the auto corners (band 0.8–1.25), applied to W, H and σ; without it, `calibrated = false`. Passed in by `ScanViewModel` (apply, restore, preview). The ± still uses the uncalibrated tier scale σ, which is conservative. | `CornerAdjustTest.calibratedScanKeepsItsFactorWhenCornersMove` |
| **V4** | **FIXED (minimum, accepted)** | `QuadLock.detectRectangle(…, mustContain)` + `contains` (coarse and refined); `RectangleFinder.kt:164` projects the hit with the same `CaptureCamera`; `snapNormal` uses the PLANE or FITTED normal, or a depth normal snapped within 10°. `rect_no_plane` EN/HI changed. SupportPlane is deferred to the RC, as accepted. | `QuadLockTest.aQuadMustContainTheCrosshairHitOfItsPlane`, `CloseRangeTest.onlyPlaneOrSnappedNormalsAreLiftedOnto` |
| v5 | FIXED | `FrameReaders.kt:521-525`: 1.5 % below 1 m, 3 % from 1 m; KDoc and §25 item 4 corrected | `CloseRangeTest.aCentimetrePaddedMenuWinsAtArmsLength` |
| v6 | FIXED | `QuadLock.miss()` (the streak resets; the outline clears after 3 misses); called at `RectangleFinder.kt:184` | `QuadLockTest.missesBreakTheStreakAndClearTheGhostOutline` |
| v7 | FIXED (pending device tuning) | `MAX_BLUR_DETECT_PX` = 2.0; the sample blur limit applies below 1 m only (`TrackingGate.kt:157-159`) | `CloseRangeTest.theSampleBlurLimitOnlyAppliesUpClose` |
| v8 | FIXED | `onCleared` no longer deletes; `prune(keep)` runs at VM init; §24 item 11 uses `am kill` | `ScanViewModelTest.keptPhotoSurvivesUntilTheNextScanAndARestoredReviewKeepsIt` |
| v9 | FIXED | `keepIfCurrent` (write, then remove if the key moved on); store methods `@Synchronized` | `ScanViewModelTest.aLateWriteNeverLeavesAPhoto` |
| v10 | OPEN (a condition, not code) | Still a condition on any C13 floor change | — |
| v11 | FIXED | `RectangleFinder.commit` orders the corners by view projection (`QuadLock.orderIndices`) | `QuadLockTest.orderIndicesFollowTheScreen` |
| v12 | FIXED | `ScanHud(rectHint = …)`, ranked after the range guide | — (UI) |
| v13 | FIXED | `FrameReaders.cpuLuma` copies the Y plane only, honouring row and pixel stride | — (device) |
| v14 | NOT FIXED (RC, nit) | `LOUPE_ZOOM` is still passed as the drag scale | — |
| v15 | FIXED | `CornerEditor`: custom actions (next corner, move by 2 px) and a polite live-region status; size spoken in cm (accepted) | `CornerAdjustTest.talkBackHelpers` |
| v16 | NOT FIXED (RC) | Warm-up KDoc and path jitter unchanged | — |
| D1 | FIXED | The §25 item 9 blur sentence was dropped | — |
| D2 | FIXED | The KDoc was corrected; no "8 mm / 1 % from 0.3 m" claim remains in HANDOFF | — |

### New issues introduced by the fixes
- **N1 (MINOR):** the V2 fallback clock starts during the scale warm-up.
  - **Cause:** `FinderPriority.dwellSuspended` starts counting on the first finder frame in Rectangle mode with 0 points, and the warm-up is still running then. During that time `maySearch` is false and the finder is not searching.
  - **Scenario:** the user opens Measure → Rectangle (hands-free) and holds still for more than 6 s. The 10° turn is not done, so the warm-up does not finish. Then they sweep, and the warm-up finishes at about 8 s. The dwell is already back, so the original V2 race (a centre point dropped before the lock) returns.
  - **Fix:** feed `active && maySearch(...)` (or `!st.warmingUp`) into `FinderPriority`, so the 6 s counts only search time.
- **N2 (MINOR):** the spec's fallback hint is not shown.
  - **Cause:** `FinderPriority.timedOut` has no caller.
  - **Effect:** when the dwell returns after 6 s, the HUD gives no "No rectangle found: aim at each corner" message.

### Verdict: **PASS WITH CONDITIONS**
R1–R4 are met: V1–V4 are fixed and tested (V4 at the accepted minimum). Build 2 may go to closed testing.
- **Should land with build 2:** N1, a one-line change.
- **RC:** N2, v10 (condition), v14, v16 and the SupportPlane wiring.
- **Device check:** R6 (the user runs TESTING §24/§25) still applies.
