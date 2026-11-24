# PLAN v3 gate: the review fix round on 2D v1 (Final Reviewer + Judge)

FRIDAY, 2026-11-24. Gate commit: **6afb9e3** ("PLAN v3 review fixes"), against **26dd340** (the reviewed tree). The working tree is clean.

It covers:
- the orchestrator's MAJOR 1 fix (the ad guard on Distance);
- Builder A's round: MAJORs 2–4 and the range/height MINORs 1–7, plus TESTING §23;
- Builder B's round: MAJOR 5 and MINORs 8–11, plus TESTING §22.

I checked it against:
- `docs/reviews/PLAN-v3-review.md` (0 BLOCKER, 5 MAJOR, 11 MINOR);
- `docs/PLAN-v3-2D.md` (W2, W4, W5, W6, §5);
- the last ~45 lines of `HANDOFF.md` (the builders' claims);
- TESTING §22 and §23.

No source was edited and no Gradle task was run. This file is the only write.

## Verdict: **PASS WITH CONDITIONS**

**All 5 MAJORs are FIXED** and verified in the code. MAJORs 2–5 each have a JVM test that fails on the old code. MAJOR 1 is Compose wiring: I verified it by reading the code, and a device item covers it.

**All 11 MINORs are closed.** MINOR 2 (the slope σ) and MINOR 9 (the ML Kit disclosure) are closed by tuning or documentation, and each keeps a device or submission check.

**The fix round adds no BLOCKER and no MAJOR.** It does add:
- **five MINORs (G1–G5);**
- **one performance warning (W1);**
- **two DOC items.**

**The two that matter most:**
- **G1: the interval-based 500 m cap hides the walk guidance for targets of about 250–500 m.**
  - The app keeps asking for a 7 m walk.
  - A sight 2 taken after a moderate walk is then reported as "Out of range: may be over 500 m". That happens even for a 300–350 m target that a 30 m walk would measure.
  - No number is wrong; the cap is honest. But the message points the user the wrong way.
- **G2: any hit beyond 8 m now produces "Something is in the way before the ground".** That includes a hit on the open ground itself, so TESTING §23 item 7 ("At 12 m it usually says Too far for one sight") will not match on the device.

**The Pixel 10a APK may go to the user.** No MAJOR is open. I recommend that **R1** (G1 + G2, a few lines each) lands first, so the device pass of §23 items 7–10 tests the behaviour that will ship. If it does not, amend TESTING §23 items 7 and 8 before the pass.

**The user still has to sign off the honest limits** (G4). The effective limits are:
- 2-sight: a number up to about **350–400 m**, and only with a ~30 m walk;
- Ground: about **8.5–8.7 m** at hand height, barely beyond Near's 8 m.

Home still says "Distance to any point you aim at, up to 500 m", and PLAN §5 still expects "30 m (Ground) inside ±".

### How this was verified
- **Reading:**
  - the full `git diff 26dd340 6afb9e3`, tests first;
  - all of `RangefinderCore.kt`;
  - `RangefinderSession.onFrame`;
  - `RayTriangulation.solve` and `requiredBaseline`;
  - `RangeUncertainty.inverseLaw`;
  - `HeightAim.sideFace` and `groundBase`;
  - in `MeasureSession`: `onFrame`, `processCommitRequest`, the resume and new-session paths, the worker loop and `farBase`;
  - `HandsFreeDropper.onFrame`;
  - `FrameReaders.centreHit`;
  - `SurfaceChooser.choose`;
  - `CutoutPixels`;
  - `CutoutMaker`;
  - `ScanViewModel.onFrontPhoto`, `onPictures` and `chosenPicture`;
  - in `GalleryRepository`: `save`, `encodeCutout`, `encodeTransparentCutout`, `cutoutFromPhoto`, `updateDimensions` and `shareCopy`;
  - `AdsManager.maybeShowAfterSave`;
  - the Rangefinder screen's `Readout`, `phaseHint` and `Controls`;
  - the EN and HI `strings_range.xml`;
  - TESTING §22 and §23, and the PLAN W2 diff.
- **Hand calculations** with the default `RangeErrorModel`:
  - aim σ 2/480 rad;
  - heading σ 0.1° + 0.005°/s;
  - `sigmaMargin` 1.05;
  - a camera 1.45 m above the floor;
  - a floor σ of 2 cm;
  - a VIO scale σ of 1%.
- **Test results on disk** (a script over the result XMLs):

  | Module | Tests | Failures | Errors | Skipped | Results written |
  |---|---|---|---|---|---|
  | engine | 236 | 0 | 0 | 0 | 11-24 15:57 |
  | ar | 160 | 0 | 0 | 0 | 11-24 15:57 |
  | data | 70 | 0 | 0 | 1 | 11-24 13:14 |
  | app | 341 | 0 | 0 | 1 | 11-24 16:01 |

  These agree with the orchestrator's run. No source file under any module's `src` is newer than its results.
- **Not mine:** Kover, `assembleDebug`/`assembleRelease`, lint (0 errors) and the release-dex debug-string check come from the orchestrator. **CI on 6afb9e3 is not recorded in HANDOFF** (see R5).

---

## 1. MAJORs from the review

| # | Finding | Status | Evidence | Test |
|---|---|---|---|---|
| M1 | Distance had no `ArScreenAdGuard` (consent form or interstitial over AR) | **FIXED** | `AppNavHost.kt:155`: `ArScreenAdGuard(ads)` is the first call in the `Routes.DISTANCE` composable, the same pattern as Measure (`:215`) and Calibrate (`:238`). `visits.arInForeground` is now true on Distance, so the consent path's `canShowForm`, the CAMERA_AR decision and the preload suppression all apply. The route comment is corrected (`:39`). **Residual:** the optional re-check just before `ad.show` (`AdsManager.kt:387-405`) was not added. A suspend point (`policy.decide`) remains between reading `arInForeground` and `show`. It is a window of milliseconds, the same for every AR screen and pre-existing (nit). | Compose wiring, verified by reading only. Device: TESTING §23 item 12 (new sub-bullet: "tap How far? straight after Home on a first launch; no consent form over the camera"). |
| M2 | Ground read off-ground points; a Near/Ground conflict was settled by the smaller ± | **FIXED** (with residuals W2, G2, G3) | **Raw hit kept at any range:** `RangefinderCore.kt:210-211`. **Ground blocked** when the hit is above the floor by more than max(10 cm, 2% of range), when it is nearer than Ground's `low95` (`:235-239`), or when the averaged Near point is above the floor (`:240-245`). **The block is sticky** for the steady aim (`DirectionWindow.generation`, `:246`, `:459-485`). **Conflicts:** Near wins on disagreement, and when the two agree the smaller ± wins (`:249-256`, `agree` `:277-281`). **New issue and copy:** `RangeIssue.OFF_GROUND` with "Something is in the way before the ground…" (EN/HI). The badge now says "Assumes the point is on level ground" (EN/HI). | `RangefinderCoreTest.aHitAboveTheFloorBeyondNearRangeBlocksGround` (the review's 9 m wall-foot case; the block survives the hit dropping out), `aNearPointAboveTheFloorIsShownAsNearNeverAsGround` (the 0.3 m box top at 3 m), `aHitOnTheFloorItselfAgreesWithGround` (no false block from a floor-plane hit). The first two fail on 26dd340. |
| M3 | A tracking loss during RESULT was not recorded, so Retake could triangulate across it | **FIXED** | The 2-sight bookkeeping now runs in every phase whenever `sight1 != null` (`RangefinderCore.kt:199-206`), so a loss sets `lostSinceSight1` and the walk in RESULT is added to `pathLength`. `retakeSight2()` goes through `canRetake` (`:284-289`, `:323-324`). `reset()` still marks the old sight 1 as lost (`:357`). | `RangefinderCoreTest.trackingLostWhileTheResultIsShownForbidsARetake`: INCONSISTENT_SIGHTS, then PAUSED, then TRACKING gives `canRetake == false` and the phase stays RESULT. It fails on 26dd340. |
| M4 | The box-top fallback chose the wrong corner's vertical plane | **FIXED** | `HeightAim.sideFace` (`HeightAim.kt:75-112`) intersects the ray with the **four** vertical side faces, including the implicit `c0 + c2 − c1` (`:78`). It takes the nearest crossing with t > 0 inside the face width ± `FACE_MARGIN_M` = 3 cm (`:95`, `:163`). It refuses beyond 75° of grazing, and its σ carries the corners' σ across the face leaking through the ray slope (`:105`). `cornerUnderRay` is deleted, and `boxTop` no longer takes `forward`. **Exact for a box**, as the review asked. Aiming slightly *past* a top edge reads the front plane a little high: (overshoot) × tan(depression), about 2 cm for 5 cm at 19°. That is inherent in any vertical-plane top and acceptable. | `HeightAimTest.aimingAtTheTopFrontEdgeOffCentreLandsOnTheFrontFaceNotABackCorner` (the review's 10 cm case: 0.80 m, not 0.45 m), `aDiagonalViewOfTheImplicitFourthCornerUsesItsSideFace`, `aTallWardrobeTopIsReadOnItsFrontFaceWhenTheTopCannotBeSeen`, `aRayMissingEveryFaceGivesNoPointAndAGrazingFaceIsTooSteep`. |
| M5 | Cutouts were cropped to the mask bbox and stretched to W × H (up to 2×) | **FIXED** (with residual G5) | `CutoutMaker.choose` returns the **full masked canvas** (`Cutouts.kt:55`); there is no crop. The rectified photo is made at the measured W:H (`ScanViewModel.kt:218`, `:226`), so the Review, the gallery (`aspectRatio(b.width / b.height)`) and the GLB quad (W × H) now agree. The bbox is only a trust check, raised to **0.85** per axis (`:50-53`, `:62`), and it counts any visible alpha (`minAlpha = 1`, i.e. confidence > 0.35), so faint legs keep a chair "whole". Colour under the alpha is kept (`CutoutPixels.applyMask`). PLAN W2 is corrected. | `CutoutMakerTest.trustedMaskKeepsTheWholeCanvasWithATransparentBackground` (same w × h as the photo), `faintThinPartsKeepTheScaleAndStayInThePicture` (the review's chair-legs scenario), `aMaskThatLeavesMuchOfTheOutlineEmptyKeepsTheRectangle`. The first two fail on 26dd340 (it cropped to 28 × 28). |

## 2. MINORs from the review

| # | Item | Status | Evidence / test |
|---|---|---|---|
| 1 | The 500 m cap was on the point estimate | **FIXED**, but see **G1** | **Interval cap:** a number only when `high95 ≤ 500` (`RayTriangulation.kt:140-142`; Ground `GroundRange.kt:82`). Because `inverseLaw` caps a < 0.5, `high95 = d/(1−a) < 2d`, so targets under 250 m are never affected. **Copy:** "Out of range: may be over 500 m" (EN/HI, a11y too). **Tests:** `monteCarloCoverageIsAtLeast95PercentFrom2To300MetresWithTheCapOn` (cap ON; coverage ≥ 95%; ≤ 2% refused or out of range), `atAndBeyondTheLimitTheCapIsHonest` (500 m: ≥ 90% out of range, ≤ 5% wrong numbers; 600 m: ≤ 0.5% numbers), `aNumberIsShownOnlyWhenItsWholeIntervalIsWithin500Metres`. **Effective limit:** my hand calculation gives about 390–410 m with the required 30 m walk, depending on the walk time. The builders' "350–380 m" is slightly conservative. That is fine. |
| 2 | Level-ground σ 0.15° was optimistic | **FIXED (tuning)** | `RangeErrorModel.LEVEL_GROUND_SLOPE_DEG = 0.3` (1 : 190). My calculation: Ground passes its 10% gate up to **≈ 8.7 m** at 1.45 m camera height. TESTING §23 item 7 adds a slope check and a "raise towards 0.4°" rule. A device check is still owed. |
| 3 | No "assumes level floor" note in Measure; the stabilisers reset each other every frame | **FIXED** | `Reading.assumesLevelFloor` is set when any endpoint is `HitKind.GROUND` (`MeasurementComposer.kt:175`, `:193`) and shown in `ResultCards.kt:146-148` (EN/HI). **Flicker:** the far base is used only after 5 frames with nothing under the crosshair (`MeasureSession.kt:1059-1064`, `FAR_BASE_MIN_MISSES`); the other stabiliser is reset once after 5 frames of one kind, never per frame (`:1084-1087`). **Test:** `HeightAimTest.aHeightOnAFarFloorBaseSaysItAssumesALevelFloor`. The flicker part is worker code with no JVM test; I verified it by reading. |
| 4 | Vertical-plane top had no offset term | **FIXED** | A 2 cm `BASE_OFFSET_SIGMA_M` is added in quadrature to the base σ before the slope leak (`HeightAim.kt:44`, `:169`). The hints are now "Put the base point exactly below the top…" and "Aim at the top, straight above the base point…" (`MeasureHud.kt` `instructionRes`, EN/HI); the old keys are deleted. **Test:** `HeightAimTest.theBaseDepthErrorLeaksIntoASteepTop` (updated expectation). TESTING §23 item 3 documents the 10 cm → 17 cm case as expected. |
| 5 | Retake was offered when the core would refuse it | **FIXED** | `RangeState.canRetake` is published from the core (`RangefinderSession.kt:193`) and the button shows only then (`RangefinderScreen.kt:367`). **Tests:** `aPausedSessionLosesAPendingTwoSight` (now asserts `canRetake == false`), `trackingLostWhileTheResultIsShownForbidsARetake`, and the main 2-sight test asserts `canRetake` before a retake. Dead code: `RangeUi.canRetake` (see D2). |
| 6 | W6: repeated frames still cost the main thread; `sessionStartMs` not reset on resume | **FIXED** | **Skip on main:** `MeasureSession.kt:444-448` skips repeated SceneView frames before any native read. It still calls `processCommitRequest(session, lastTracking)`, so a **pending "+" keeps its timing**: the commit uses the worker's fresh `candidate`, `framePointsOk` from the last real frame, and anchors from `session`/`Plane`, never from the stale `Frame`. **Hands-free is unaffected:** `HandsFreeDropper` is its own `ArFrameListener`, time-based, and only calls `requestCommit()`, which the next `onFrame` serves whether or not that frame is a repeat. `latestCentreHit` is kept on a repeat, so PlacementController still sees this camera frame's hit. Freeze and front capture wait for the next real frame, which is correct (a new image is needed). **Resume:** `sessionStartMs` is reset and `sessionEpoch` bumped on every resume (`:390-393`), so the worker resets its gate, stabilisers and surface model (`:1021-1029`). That is reasonable after a pause, because the post-loss warm-up applies. The "tune" items remain device-decided (§23 items 1–2). No JVM test is possible (ARCore types); I verified it by reading. |
| 7 | `require` could throw on the AR main thread | **FIXED** | `Sight.of` returns null on any non-finite or degenerate input (`RayTriangulation.kt:24-33`), and the core uses it for sights (`RangefinderCore.kt:393-398`, a failed sight becomes NOT_STEADY) and for the preview (`:439`). `DirectionWindow.add` drops non-finite rays (`:468`), and `meanDirection` cannot normalise a zero sum (`:487-490`). **Test:** `RayTriangulationTest.badInputBecomesNoSightInsteadOfACrash`. |
| 8 | Cutout texture budget | **FIXED** (by a decision) | A transparent cutout gets its own **1 MB** budget (`GalleryRepository.kt:928`), checked against the exact staged bytes (`:275`, `Staged.budget`). The texture ladder runs 1024 → 256 px (`:975`). If nothing transparent fits, the item is saved **opaque, with the full rectangle**, never as a plain panel (`:670-672`). In practice this cannot happen: a 256 px RGBA PNG plus the thumbnail is far below 1 MB. Edits and repairs keep the budget per kind (`cutoutFromPhoto`, `:729-745`). **Storage:** 20 free items ≤ 20 MB; Pro is about 1 MB per transparent item, with no cap concern; Delete my data clears it. **Share size:** share copies only `cutout.webp` (≤ 1024 px, `shareCopy`), never the GLB, so share size is not driven by the 1 MB. **Tests:** `GalleryPlacementTest.transparentCutoutTextureKeepsAtLeast640PxForAPhotoLikeSubject` (≥ 640 px for photo-like subjects; never a plain panel), `transparentCutoutBlendsAPngTextureWithinItsOwnBudget`. Save cost: see **W1**. |
| 9 | ML Kit Subject Segmentation (beta) and the Data safety notes | **FIXED (doc)** | `android-performance.md` §6.3 records the 2026-11-24 read: no segmentation-specific section, but the "all features" table covers it, so no new Data safety row. **Still owed:** a re-check at submission, and TESTING §21 (the naming chip) on the device after the ML Kit common bump. |
| 10 | The CI debug grep missed the W6 HUD | **FIXED** | `android.yml:157-158` adds `dbg track`. The literal is at `MeasureHud.kt:560` (behind `BuildConfig.DEBUG`). The orchestrator reports 0 hits in the release dex. |
| 11 | TESTING described behaviour the code didn't have | **FIXED**, except two lines | TESTING §22 items 12, 14 and 17 match the code (whole canvas, 85% trust rule, 1 MB, opaque fallback, "Undo cutout" hidden when NOT_SURE: `hasCutout` = `REMOVED`). TESTING §23 items 3, 4, 5, 9, 10 and 12 match. **Exceptions:** §23 item 7's "12 m: Too far for one sight" (see **G2**) and item 8's "the app asks for … about 30 m at 300 m" (true only after about 10 m walked; see **G1**). |

---

## 3. New issues from the fix round

| # | Severity | Where | Problem and concrete failure scenario | Fix |
|---|---|---|---|---|
| **G1** | MINOR | `RayTriangulation.kt:140-142`; `RangefinderCore.kt:438-445` (`walk`), `:405` | **The interval cap hides the walk guidance.** The walk the app asks for is raised only by an **Ok** preview (`:445`). With the cap, the preview stays null until `high95 ≤ 500`, so for a far target the request stays at the value set at sight 1. Live is null beyond Near/Ground, so that value comes from `DEFAULT_GUESS_M` = 50 m: **"Walk about 7 m"**. Below a = 0.5 the solve says TOO_UNCERTAIN ("walk further"). Between a = 0.5 and the cap, it now says **OutOfRange**, where before the fix it showed a preview and asked for 30 m. **Scenario (a true 350 m target, default model):** a ≈ 4.7 / walk. The user walks the requested 7 m and gets "Not accurate enough yet. Walk further sideways and retake sight 2". The screen still says "Walk about 7 m", so they walk to about 12 m and retake. The result is **"Out of range: may be over 500 m"** for a 350 m target that a 30 m walk measures. For 300 m the same thing happens for sights taken after about 8–10 m. No number is wrong, but the effective range is lower than "350–380 m with a 30 m walk", because the app never asks for that walk until the user has made about 16 m (350 m) or about 22 m (380 m) of it unprompted. | **(a)** In `solve`, when `distance ≤ maxRangeM` but `high95 > maxRangeM` and the walk is under `MAX_BASELINE_M`, return `Rejected(TOO_UNCERTAIN)` ("walk further"). Keep `OutOfRange` for a point estimate beyond 500 m or a walk already ≥ 30 m. **(b)** In `walk()`, raise `requiredBaselineM` from the triangulated point's distance whenever `solve` gives a consistent point (`Triangulation.point`, outcome ≠ INCONSISTENT/BEHIND/NEAR_PARALLEL), not only from an Ok preview. **Tests:** at 350 m a 12 m walk gives TOO_UNCERTAIN; the guidance reaches 30 m before 16 m have been walked. → **R1** |
| **G2** | MINOR | `RangefinderCore.kt:260`; TESTING §23 item 7 | **OFF_GROUND for clear ground.** `liveIssue` reports OFF_GROUND whenever `rawHit.distance > NEAR_MAX_M`, even when the hit *is* on the ground and Ground is simply too uncertain. **Scenario:** with depth ON by default, aim at open paving 12 m away. ARCore returns a depth or feature point on the paving at about 12 m, which is not above the floor and not blocking. Ground is TOO_UNCERTAIN beyond about 8.7 m, so the screen says **"Something is in the way before the ground…"**. TESTING §23 item 7 expects "Too far for one sight". Both messages say "use 2 sights", so the action is right, but the stated reason is false, and the device tester will log a failure. | Report OFF_GROUND only when `groundBlocked`, and let a far floor hit fall through to GROUND_TOO_FAR or OUT_OF_RANGE. Test: a floor hit at 12 m gives GROUND_TOO_FAR; the 9 m wall-foot case still gives OFF_GROUND. → **R1** |
| **G3** | MINOR (device-gated) | `RangefinderCore.kt:221`, `:235-246`, `:472` | **How long the Ground block lasts depends only on the aim window restarting.** It clears only when the window restarts, i.e. on a turn of more than 1° from the window *mean* or a non-TRACKING frame. **(a) Slow drift never clears it:** with the 15-frame window full, the newest ray leads the mean by about 7 frames × the rate, so a pan slower than about 4°/s never restarts the window. **Scenario:** aim at a kerb or a car wheel (blocked), then lower the crosshair slowly (about 2°/s) onto the open paving in front. The screen keeps "Something is in the way" on a real ground point until the user jerks the phone. **(b) One noisy frame blocks the whole aim.** At 7 m a single depth or feature point more than about 0.6 m short (below `low95`), or more than 14 cm high, blocks Ground for as long as the aim is held. That matters only when Near does not lock on the same noisy hits, i.e. roughly 6–8.7 m. **(c) A block can carry over to the next aim:** a block set during a LIMITED frame (the aim window is empty, so the generation is not bumped) carries into the next steady aim. | Store the mean direction at which the block was set, and clear it when the current mean is more than `AIM_MOVE_DEG` from it, independently of the window restarts. Require 2 of the last 3 frames to agree before blocking on a raw hit beyond Near (keep one frame for an above-floor *Near* estimate). Bump the generation on `clear()` even when the window is empty. **Tests:** block, then drift 0.07°/frame for 2° onto the floor, and Ground returns; a single outlier hit among 30 floor frames does not block. → **R2** |
| **G4** | MINOR (copy + product decision) | `strings_range.xml:6` (EN), `values-hi/strings_range.xml:5`; `PLAN-v3-2D.md:10`, `:19`, `:58`, `:112` | **The copy overstates the effective limits.** Home says "Distance to any point you aim at, **up to 500 m**, always with ±". **The effective limits are:** about 8.5–8.7 m for one sight on the ground (Near ≤ 8 m), and a 2-sight number only up to about 350–400 m, with a ~30 m walk (and in practice less, until G1 is fixed). Beyond that the answer is "may be over 500 m". **The plan is stale too:** PLAN §5's "30 m (Ground) inside ±" cannot pass (TESTING now says 12 m is "too far"), and U2 and W4 still say "up to 500 m". The review's deviation note asked for the user's sign-off; it is still outstanding. | **User decision** (B-list): accept the limits, or fund the zoomed-aim / longer-walk work. **Then:** the Home detail (EN/HI), e.g. "Distance to a point: close up, on the ground nearby, or far away with 2 sights (several hundred metres), always with ±". **Then:** amend PLAN §1/U2/W4/§5 to the as-built limits, and add them to the store "known limitations". → **R4** |
| **G5** | MINOR (low) | `Cutouts.kt:50-53` | **A single stray pixel defeats the 85% trust rule.** The rule is a bounding box of **any** alpha ≥ 1 (confidence > 0.35). One stray pixel near each opposite edge makes the bbox span the outline, so the "a mask that lost a large part of the object keeps the rectangle" protection is defeated. Examples: a 0.4-confidence speck on the floor at the bottom, or on the wall at the top. **Scenario:** a chair whose legs come out at 0.1 (invisible), plus one floor speck at the bottom edge. The rule says REMOVED, and Review shows a legless chair at the correct scale. The damage is bounded: there is no distortion any more, the user sees it on the checkerboard, and "Undo cutout" is there. But this is exactly the case the rule exists for. Pre-fix the bbox needed alpha ≥ 128 (confidence ≥ 0.5), so specks had to be stronger. | Use a robust extent: per axis, the span from the 1st to the 99th percentile of visible-alpha mass, or ignore rows and columns with fewer than about 0.5% of the opposite dimension visible. Test: legless mask + one speck → NOT_SURE; the faint-legs test still passes. → **R3** |

### Warnings (accepted, device-checked)

- **W1 (performance):** Saving a transparent cutout now starts its PNG ladder at **1024 px** (previously 512). Each rung re-runs `downscale` + `bleedEdges` + a full PNG deflate (`GalleryRepository.kt:750-753`):
  - `bleedEdges` builds a boxed `List<Int>` of every transparent pixel and runs up to 8 passes;
  - photo-like subjects end at 640–768 px, i.e. 3–4 rungs on a ~0.7 MP picture;
  - the same ladder runs on every dimension edit (under the gallery write lock) and on a `cleanUp` repair.

  Saving is off the main thread (`withContext(cpu)`), so there is no ANR risk, but the Save time is unmeasured. **Device check (R3):** time Save of a chair cutout, with a target of ≤ 2 s on the Pixel 10a. **Cheap fix if it is slow:** estimate bytes per pixel from the first rung and jump straight to the fitting side.
- **W2 (MAJOR 2 residual, by design):** Ground can only be refused when ARCore returns *some* hit on the obstacle. A featureless car side, depth off, or a target beyond depth range still gives Ground for the floor behind it, labelled "Assumes the point is on level ground". The test `aHitAboveTheFloorBeyondNearRangeBlocksGround` itself shows Ground before the first hit arrives. Device item: TESTING §23 item 7 (9 m wall foot; record how often a number appears before the message).
- **W3:** a millisecond window remains between the AR check and `ad.show` in `maybeShowAfterSave` (see M1 residual). It is common to all AR screens and pre-existing.

### DOC

- **D1:** `CutoutPixels.kt:30` still documents step 2 as "crop to the subject's bounding box, which then maps to the measured W × H". That is the behaviour MAJOR 5 removed. → R5
- **D2:** dead code left by the fixes:
  - `RangeUi.canRetake` (`RangeUi.kt:49`) is used only by `RangeUiTest:29-31`;
  - `CutoutPixels.crop` is used only by `CutoutTest:115`.

  Remove both with their tests, so nobody re-wires the old rule. → R5

**Checked and found clean:**
- **Real floor points are not blocked by a plane hit.** `centreHit` prefers a tracked plane hit inside its polygon (`FrameReaders.kt:78-82`). On the floor it lies within about 0.1 m of Ground's distance, well inside `low95` (about 0.6 m short at 7 m). `aHitOnTheFloorItselfAgreesWithGround` pins this.
- **In Measure, the W5 far base cannot reach the floor behind an obstacle.** `SurfaceChooser.choose` returns any hit at any range, and the far base is used only after 5 frames with **no** hit (`MeasureSession.kt:1059-1064`). So the MAJOR 2 failure mode does not exist in Measure.
- **`canRetake` after an Ok or OutOfRange result is true,** which is correct: a retake after walking further is the right next step.
- **Strings:** EN/HI parity holds for every new key: `range_issue_off_ground`, the three `measure_*` keys and the changed `range_assumes_level`/`range_out_of_range*`. The deleted `measure_aim_base`/`measure_aim_top` have no remaining reference (they were replaced in `instructionRes`).

---

## 4. Conditions

| # | Owner | What | When | Check |
|---|---|---|---|---|
| **R1** | Builder A | **G1:** TOO_UNCERTAIN instead of OutOfRange while the walk is under 30 m and the point estimate is ≤ 500 m, and walk guidance from the triangulated point. **G2:** OFF_GROUND only when blocked. Three JVM tests (§3). | **Before the Pixel 10a pass of TESTING §23 items 7–10.** Otherwise, amend §23 item 7 (a 12 m ground hit says "Something is in the way") and item 8 (the 7 m request until the preview fits) to the current behaviour. | Tests green; the HANDOFF line. |
| **R2** | Builder A | **G3:** clear the block by displacement from the blocking direction, a 2-of-3 rule for raw hits beyond Near, and the empty-window generation bump. Two tests. | Before the RC | Tests green; §23 item 7 adds "aim at a kerb, then lower slowly onto open paving: Ground comes back". |
| **R3** | Builder B | **G5:** a robust trust extent plus a test. **W1:** log the cutout encode time (debug), and record Save time and texture size in TESTING §22 item 17. | Before the RC | Test green; the device numbers in HANDOFF. |
| **R4** | Orchestrator + **user** | **G4:** the user signs off the as-built limits: Ground ≈ 8.5–8.7 m; 2-sight numbers to about 350–400 m with a ~30 m walk; "may be over 500 m" beyond. Or the user decides to fund zoomed aim and longer walks. Then the Home detail EN/HI, PLAN §1/U2/W4/§5, and the known-limitations draft. | Before any store listing or screenshot copy | The PLAN diff, the strings diff, the user's line in HANDOFF. |
| **R5** | Orchestrator | **D1, D2.** HANDOFF update; `python docs/build_html.py`; commit; a **green CI run on that commit**. CI for 6afb9e3 is not recorded in HANDOFF: record it, or the run for the R1–R3 commit, with the bundletool arm64 size. | With R1 | CI log; the HANDOFF line. |

**Carried from the review (not new):**
- re-check the ML Kit disclosure page at submission (MINOR 9);
- the TESTING §21 naming-chip run after the ML Kit common bump;
- the §23 slope check that decides 0.3° vs 0.4° (MINOR 2).

---

## 5. Release gate (FRIDAY §6.2) for PLAN v3 as of this gate

| Line | Status |
|---|---|
| All BLOCKER and MAJOR closed and verified in code | ✅ 5/5 FIXED with tests (M1 by reading plus a device item). G1–G5 are MINORs. |
| CI green on the release commit (unit, lint, coverage, size, alignment, debug-absent) | ⏳ local: tests 236/160/70/341 with 0 failures, Kover, both assembles, lint 0 errors, release dex clean (orchestrator). CI on 6afb9e3 or the R1 commit is owed (R5). |
| Migrations tested, including an on-device upgrade | ⏳ Room stays v3 (no schema change in this round). Install over the previous build: TESTING §22 preamble (device). |
| Critical journeys on a low-end and a mid-range device | ⏳ the Pixel 10a pass of §22/§23 (user); a low-end phone is still to be named. |
| Security pass; Data safety and privacy match behaviour | ⏳ code ✅ (no upload path; segmentation on-device; the ML Kit row is covered). The live-page re-check is at submission. |
| Performance budgets on a release build | ⏳ main-thread repeat skip ✅ (code); first-hit/lock times §23 items 1–2; cutout Save time W1/R3. |
| Accessibility: TalkBack, 200% font, contrast | ⏳ new strings have a11y twins (`range_out_of_range_a11y`); device §23 item 11. |
| Known limitations and release notes written | ❌ R4 (the range limits must be in them). |
| Rollback plan: staged rollout, previous AAB kept | ❌ unchanged from the SM1-M5 gate (A6 there). |

---

## Summary

- **Verdict:** PASS WITH CONDITIONS.
- **MAJORs:** all five FIXED.
  - **M1:** ad guard on Distance.
  - **M2:** off-ground Ground blocked; Near wins on disagreement.
  - **M3:** loss tracked in RESULT; `canRetake`.
  - **M4:** four side faces, including the implicit corner.
  - **M5:** full W × H canvas, never stretched; 85% trust.
- **MINORs:** all 11 closed.
- **New:** 0 BLOCKER, 0 MAJOR, 5 MINOR, 3 warnings, 2 DOC.
  - **G1:** the interval cap hides the walk guidance and reports "may be over 500 m" for 250–500 m targets after a moderate walk.
  - **G2:** a far hit on open ground says "Something is in the way", which contradicts TESTING §23 item 7.
  - **G3:** the sticky Ground block survives slow drift and a single noisy hit.
  - **G4:** Home and PLAN still say "up to 500 m" and "30 m by Ground".
  - **G5:** one stray pixel defeats the 85% trust rule.
  - **W1:** cutout Save cost is unmeasured.
- **Conditions:**
  - **R1:** G1 + G2, before the §23 device pass, or amend TESTING.
  - **R2:** G3.
  - **R3:** G5 + W1 timing.
  - **R4:** the user's sign-off on the limits, then the copy and PLAN.
  - **R5:** docs, commit, green CI.
