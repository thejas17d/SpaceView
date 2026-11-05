# M3 review: scan capture flow (Builder A, uncommitted, on top of ccebb01)

Reviewer: Reviewer agent, 2026-11-05. Scope: Builder A's M3 files only.
- `:ar`: the new `ScanComposer`, `ImageProjection`, `FrontCaptureGate` and `Nv21`, plus changes to `MeasurementComposer` (BOX), `MeasureSession` (scan and front-capture API), `FrameReaders` and `DisplayedBoundTest`.
- `app/scan`: `ScanRoute`, `ScanArScreen`, `ScanReviewScreen`, `ScanViewModel` and `ScanDraftBuilder`.
- Changed in `app`: `measure/*`, `ads/MeasureVisitTracker` and `AdsManager` (`leaveWithoutAd`), `nav/AppNavHost` (SCAN), `HomeScreen`, and the strings.

Builder B's gallery, data and imaging code is reviewed in `M3-gallery-review.md`. Here I only checked how A calls it.

No source was edited.

**Verdict: 1 BLOCKER, 2 MAJOR, 16 MINOR.**

The measurement side is honest:
- BOX W × D comes from the rectangle path, with its geometry checks and tier floor.
- H comes from the height path, with its own calibration scope.
- Every ± is rounded up.
- Calibration cannot reach a scan.
- Edits become TYPED estimates with no ±.

The ads wiring meets ui-ux §5.2. The Measure flow is not regressed.

The problems are all in the front photo:
- The auto-capture **never fires** on a real phone, because SceneView delivers repeated frames.
- For a box, the face that is photographed can differ from the face whose W × H the outline reported.
- After a pause, the capture projects corners that are no longer anchored.

## How the findings were checked

- **Reading.** I read every file in scope (`git diff`, plus the new files). I also read the unchanged code each one depends on: `MeasureSession` lifecycle, `SessionSetup`, `Shapes.rectangle` and its build step, `Homography.rectify`, `FrontPhotoRectifier`/`RectifyMath`, `GalleryRepository.save`/`shareCopy`, and `ScanDraft`.
- **SceneView 4.65.0 bytecode** (`javap` on the cached AAR):
  - `ARSceneKt$ARSceneView$43$1` drives the frame loop with `withFrameNanos`, so it runs at display rate.
  - Each tick it calls `ARSession.updateOrNull()`, which only drops frames with timestamp **0**, then `onARFrame`, then `notifyHostOfFrame`.
  - `notifyHostOfFrame` calls `onSessionUpdated` **unconditionally**. The new-frame flag (`lastTs.getAndSet(ts) != ts`) only feeds `FrameRateGate.shouldRender`.
  - SpaceView configures `UpdateMode.LATEST_CAMERA_IMAGE` (`SessionSetup.kt:82`). With a 30 fps camera on a display of 60 Hz or more, at least half of the `onFrame` calls therefore repeat the previous frame's timestamp. M2's `TrackingGate.kt:161` (`dt <= 1e-4 → return`) and `FrameReaders.pointCloud` already allow for this.
- **Probe test** (`ar/src/test/.../TmpScanReviewProbeTest.kt`, deleted after the run; lock taken and released):

| Probe | Result |
|---|---|
| `FrontCaptureGate`, steady and parallel (5°), face inside, 30 fps camera fed at 60 Hz (each timestamp twice), 10 s | **captured = false**, hints `HOLD_STILL=301, HOLDING=299` (alternating every call) |
| `ScanComposer.frontEdge`, wardrobe 2.0 × 0.6 m, camera at (1.0, 1.4, 1.0) | edge 0 (long front) ✓ |
| same, camera at (1.6, 1.4, 0.6) | edge 0 ✓ |
| same, camera at (1.8, 1.4, 0.4), i.e. in front of the long face, 20 cm from its end | **edge 1 (short side)** ✗ |
| same, camera at (1.9, 1.4, 0.5) | **edge 1** ✗ |

- **Unit tests** in the current tree, all green:
  - `:ar:testDebugUnitTest`: 70 tests.
  - `:app` `scan.*` and `ads.*`: 57 tests, of which ScanDraftBuilder 12, RectifyMath 12 and MeasureVisitTracker 14.

---

## BLOCKER

### B1. The front photo is never taken: repeated frames reset the hold every other call

**Where**
- `ar/.../FrontCaptureGate.kt:108-117`: `if (prev == null || dt <= 0 || dt > MAX_GAP_S) { angular = NaN; linear = NaN }`.
- `FrontCaptureGate.kt:88,91-93`: `!isSteady()` gives `HOLD_STILL`, which sets `holdingSinceNs = -1`.
- `ar/.../MeasureSession.kt:400,740-752`: the gate is fed on every `onFrame`, with no timestamp check.

**What happens**
- `onSessionUpdated` runs once per display vsync, and in `LATEST_CAMERA_IMAGE` mode a call without a new camera image returns the same `Frame` timestamp (see above).
- On each repeated frame `dt == 0`, so the motion estimate becomes NaN. The gate then reports `HOLD_STILL` and restarts the 600 ms hold.
- The next real frame starts the hold again, and the repeat after it resets it. `held >= HOLD_NS` is never reached.

**Failure scenario**
- On any phone with a 60/90/120 Hz display, step 3 never captures. The probe shows this over 10 s of a perfectly still, parallel hold.
- The card flickers between "Hold still." and "Hold still. Taking the photo…" about 30 times a second, which also makes `frontCapture` emit every frame.
- The text is in a polite live region, so TalkBack keeps re-announcing it.
- The user's only way forward is *Skip photo*, so **no scan ever gets its front photo**, which is the core of M3. The existing tests feed strictly increasing timestamps, so they pass.

**Fix**
1. In `MeasureSession.processFrontCapture`, skip the gate when `frame.timestamp == lastCaptureTs`, and remember the last timestamp. This also halves the per-frame work.
2. In `FrontCaptureGate.updateMotion`, treat `dt <= 1e-4` like `TrackingGate` does: return **without** touching `angular`, `linear`, `lastTs`, `lastPose` or the hold.
3. Add a test: a 30 fps camera fed at 60 Hz and at 120 Hz, with each timestamp repeated, captures within `HOLD_NS + 3` camera frames.
4. Add to the device checklist: "photo is auto-taken within about 1 s of holding still, on a 90/120 Hz phone".

---

## MAJOR

### M1. For a box, the photographed face is chosen separately from the outline's W/D. The rule picks the short side while the user stands in front of the long face.

**Where**
- `app/.../scan/ScanArScreen.kt:272`: `remember(finished) { outlineOf(finished) }` → `MeasureSession.scanOutline` (`:514-518`). This decides W vs D with `frontEdge(points, pose at that moment)` (`ScanComposer.kt:90-93`).
- `MeasureSession.kt:531`: `startFrontCapture` **recomputes** `captureEdge` with the pose at arming time, and that edge picks the photographed face (`ScanComposer.kt:104-110,153-165`).
- `ScanComposer.kt:116-129`: `frontEdge` = the base edge whose **midpoint** is horizontally nearest the camera.
- `ScanViewModel.kt:142`: the photo is warped to `outline.width / outline.height`.
- **Same root cause for a horizontal flat face** (a poster on a table): `faceUp` (`ScanComposer.kt:173-178`) takes "up" from the camera direction once at outline time and again at every face refresh. W/H is fixed at outline time, but the photo's orientation follows wherever the user stands at capture.

**What happens**
1. The edge-midpoint rule is wrong for long objects. In the probe, standing 0.4–0.5 m in front of a 2 m wardrobe, 10–20 cm inside its right end, selects the 0.6 m side.
2. The decision is made twice, at different moments: when the reading finishes (and again whenever the reading changes as anchors refine), and when the photo step arms.
3. If the two decisions differ:
   - The outline says W = front edge A.
   - The capture photographs face B, of width D.
   - The view model stretches face B's photo to A's W:H.
   - The gallery GLB puts the side panel on the front.

**Failure scenario**
- A user taps the top of a 120 × 40 cm cabinet while standing near its front-right corner, which is the natural place to see the top.
- The Outline card shows W 120, D 40.
- After *Next*, `startFrontCapture` picks the side edge. The guidance says "Turn to face the object" until the user faces the 40 cm side panel.
- The 40 × 85 cm panel is then warped into a 120 × 85 texture, a 3× horizontal stretch, and saved without warning.
- If the user doesn't move, the Outline card can also swap W and D while it is on screen, because the outline is recomputed for each new reading.

**Fix**
- Decide the front face **once**, when the user taps *Next*. Store it in `ScanOutline` (`frontEdge: Int`, and for FLAT the `up` vector used) and pass it to `startFrontCapture(shape, outline)`. The capture then never re-decides.
- Choose the edge by facing, not by midpoint distance. For example, take argmax over k of `n_k · normalize(horizontal(camera − mid_k))`, where `n_k` is the outward horizontal normal of edge k. That is the face the camera is most squarely in front of.
- Tests:
  - the probe positions, which should all give edge 0;
  - an outline/capture round trip where the camera moves between *Next* and arming, and W/D and the face must not change;
  - a horizontal flat face captured from the other side, which must keep the W/H orientation.

### M2. After a pause, the photo is taken against frozen, unanchored corners

**Where**
- `MeasureSession.kt:321-325`: `onSessionPaused` detaches every anchor. A complete outline keeps its last positions, unanchored, by M2 design.
- `MeasureSession.kt:385-400`: unanchored points are skipped, so `allTracking` stays **true**.
- `MeasureSession.kt:527-539`: `startFrontCapture` does not check for unanchored points.
- `ScanArScreen.kt:142-148`: the photo step re-arms automatically once `sessionRunning` is true again.

**What happens**
- M2 deliberately treats points left by a pause as frozen. `freeze()` refuses them (`:555`) and `undo()` clears them all (`:460`), because ARCore may not relocalise to the same world frame on resume.
- The front capture ignores this. It projects those frozen world positions into the post-resume camera and gives "step back / turn" guidance towards wherever they now land.
- `trackingOk` is true, so it captures and saves whatever the camera sees there.

**Failure scenario**
- In step 3 the user presses the power button or takes a call, then comes back. Pausing to grab a tape measure is natural in a scan flow.
- ARCore resumes with a shifted origin. The capture now guides the user to a patch of wall 30 cm to the left of the frame, and auto-captures it.
- The Review shows a straightened photo of plain wall, which is then saved as the item's front.
- The same happens if the pause occurs on the Outline step and the user taps *Next* afterwards.

**Fix**
- In `startFrontCapture` (and in `processFrontCapture`), refuse when any endpoint has `anchor == null`. Expose this as a hint, for example `CaptureHint.REMEASURE`.
- In `ScanArScreen`, show "The outline was lost when the app paused. Measure again or skip the photo", with [Measure again] (Undo clears all) and [Skip photo].
- Test this with a fake: mark the endpoints unanchored and assert that the capture never arms.
- Add to the device checklist: "pause in step 3 (power button), resume → the photo is refused, not taken".

---

## MINOR

| # | Where | Problem → failure | Fix |
|---|---|---|---|
| m1 | `MeasureSession.kt:776-783`, `ScanArScreen.kt:363` | If the bitmap conversion fails (OOM) while the session is paused, `startFrontCapture` returns false. `_frontCapture` stays `CAPTURED` ("Got it."), *Skip* is disabled, and the `LaunchedEffect` never re-arms because `capture != null`. The user is stuck until Back. | On failure, set `_frontCapture.value = null` before calling `startFrontCapture`, so the screen's effect re-arms on resume. |
| m2 | `ScanArScreen.kt:152-154`, `MeasureSession.kt:542-547,786` | `cancelFrontCapture` (Back to the outline, Undo, pause) does not cancel a conversion that is already running. A photo arriving 50–200 ms later is still handed to `onPhoto`, which jumps to Review from the Outline step, or attaches a photo of an outline that was just undone. | Keep the conversion `Job` plus a capture generation number. Cancel it in `cancelFrontCapture`, and publish only if the generation still matches. |
| m3 | `ScanArScreen.kt:142-148,184`; `MeasureSession.kt:283-296` | The Photo step on a **new** session (*Try again*, or SceneView re-creating the session) has no endpoints. `startFrontCapture` fails forever while the card says "Move the phone slowly…". | When `step == PHOTO && state.pointCount < shape.mode.requiredPoints`, call `backToOutline()` with a short message. |
| m4 | `ScanRoute.kt:103`, `ScanReviewScreen.kt:83`, `ScanViewModel.kt:188-191` | Back or *Measure again* on Review silently discards the outline, photo, edits and name, with no confirmation. It also works **during a save**: the step goes to OUTLINE (camera starts), then the save completes and jumps to SAVED. | Disable both while `saving`, and confirm when there are edits, a label or a photo. |
| m5 | `ScanViewModel.kt` (no `SavedStateHandle`); `:220` | (a) Process death on Saved brings the user back to step 1 ("What are you scanning?") for an item that is already saved. (b) `ScanEvent.Saved` is sent with `tryEmit` on a no-replay `SharedFlow`. If no collector is active at that instant, `recordCompletedScan` is skipped. That errs towards fewer ads, but the cap count drifts. | Save `savedId` and the step in `SavedStateHandle`, and restore to SAVED. Call `recordCompletedScan` from the view model's save path (injected callback), or use a `Channel`. |
| m6 | `MeasureSession.kt:611-626` (`close`), `:770-787`; `ScanViewModel.kt:143-160` | Bitmaps are left for the GC on three paths: a published `_frontPhoto` that was never taken; a conversion that finishes after `scope.cancel()`; and an `onFrontPhoto` job cancelled before its `withContext` block starts, where the `finally` never runs. With minSdk 24, API 24–25 bitmaps live on the Java heap (about 5 MB each). | Recycle `_frontPhoto.value` in `close()`. Use `try/finally` around the conversion result. In the view model, use `invokeOnCompletion { if (cancelled) recycle }`. |
| m7 | `MeasurementComposer.kt:321-338` | Nothing requires the BOX top point to be above the base. A top point tapped on the floor next to the box gives "H 1.2 cm ± 1" as a *measured* box. If it is below the base, `boxFace` builds the face downwards. | Reject the 4th commit when its height above the base is under about 2 cm, or negative, with a "aim at the top" `CommitFailure`. |
| m8 | `MeasurementComposer.kt:330`; `DimensionPainter.kt:66`; `ScanComposer.kt:161-162` | Three different base references: H is measured from the nearest *tapped* corner; the painter draws it from the nearest of four corners, including the inferred one; the photo face is lifted from the *mean* of the three. On an uneven floor the drawn H, measured H and photo height disagree slightly. | Use one helper (for example `boxFoot`) for all three. |
| m9 | `MeasureHud.kt:306-313`, `FreezeOverlay.kt:134-137` | BOX readouts are mislabelled. With 3 points the live label uses the W × H format for W × D. With 4 points it shows first edge × second edge × H, while the Outline card may swap W and D (front edge). In freeze-frame, while the top point is being adjusted, H, the value being adjusted, is not shown. | Use the BOX branch in `FreezeOverlay`, use D wording for 3 points, and order the values as the Outline card will. |
| m10 | `ScanReviewScreen.kt:100-103`, `scan_type_tv` | For a TV the user outlines the outer bezel, but the Review shows it as **"Diagonal"**, in inches with a ±. TVs are sold by visible screen diagonal, so a "55-inch" set reads about 56–57 in. The ± itself is fine. | Label it "Outer diagonal (with bezel)", or tell TV users to outline the screen glass. |
| m11 | `ScanDraftBuilder.kt:117-124` and `ScanDraft` | The Review shows the engine's rectangle diagonal ±, but `ScanDraft` has no diagonal, so the Gallery recomputes its own (gallery MAJOR 1). The same item shows two different ± for the diagonal. | Add `diagonal: Dimension?` to `ScanDraft`, or share one `diagonalOf(w, h)` that both sides call. Coordinate with B's fix. |
| m12 | `values*/strings.xml:276-278` (`scan_aim_base_*`) | "Back-right corner on the floor": that corner is hidden for an appliance against a wall, and "floor" is wrong for a carton on a table. The engine accepts any L-shaped three corners (`threeCornerOrder`). | Change the text to "…a back corner (left or right)" and "on the floor or table" (EN + HI). |
| m13 | `FrontCaptureGate.kt:126` | One 15° parallel limit for every shape. For a 40 cm carton on the floor, the user must crouch to bring the face within 15°. The homography corrects perspective, and `TOO_SMALL` already guards resolution. | Use a per-shape limit (for example 25–30° for BOX) as a tunable, and add it to the bench test. |
| m14 | `ScanArScreen.kt:337-350`; `ScanReviewScreen.kt:200-202`; `ScanRoute.kt:157-159` | Accessibility: the TILT text (with the angle) is in a polite live region and is re-announced at every 5° step. There are three identical "Edit" buttons with no dimension name. The progress bar has no description. "Something else" appears as both card title and button. | Announce on hint changes only, without the angle. Label the buttons "Edit width / height / depth". Add `progressBarRangeInfo` or a description. Drop the duplicate button text. |
| m15 | `MeasureSession.kt:754-760`, `ImageProjection.kt:58-66,88-94` | Hot path, small issues. Capture copies about 1.4 MB of NV21 on the main/render thread, with per-element chroma `get` (an estimated 5–20 ms hitch once per scan, acceptable). While armed, each frame allocates a quaternion inverse per corner, a `withIndex` iterator and two status objects. | Optional: compute the inverse rotation once per frame. The B1 duplicate skip already halves the calls. |
| m16 | `Nv21.kt`, `FrameReaders.kt:252-278` | Odd widths and heights are handled by clamping, and `toNv21` honours row and pixel strides. Neither is tested: `Nv21Test` uses even sizes only, and `toNv21` (pixelStride 2, padded `rowStride`) has no test. | Add `Nv21.toArgb` tests for odd sizes. Extract `toNv21` over `(ByteBuffer, rowStride, pixelStride)` so it can be JVM-tested with padded, interleaved planes. |

---

## Verified correct

| Area | Evidence |
|---|---|
| **BOX maths** | W × D come from `rectangleReading` on the three base corners (`MeasurementComposer.kt:321-338`): convexity, squareness and planarity checks, ROUGH when inconsistent, and the tier floor in quadrature. H uses `lengthReading(HEIGHT, foot, top)`, the vertical component with its own floor. The badge is the worse of the two. `calibrated` is true only when both are in scope. `ScanComposerTest.floorCalibrationDoesNotCoverTheHeight` shows that a floor calibration never scales H. |
| **Level-base assumption** | H is vertical from a base corner. W and D are the base-plane edge lengths. The face is a vertical extrusion of the front base edge. Acceptable for v1 (but see m8). |
| **± never shrinks** | The composer rounds σ up (gate R1). `ScanDraftBuilder.measured` takes the ceiling of ±95 to 0.1 mm (`:84-88`). `DisplayedBoundTest` now covers `boxHeight` across contexts and preferences. The Review and Outline cards format from the stored ±95. `GalleryRepository.normalised` only zeroes estimates. |
| **Tier floor** | Every BOX dimension goes through `UncertaintyModel.length` or `height` (floor in quadrature). The test asserts H ≥ `absFloor95`. |
| **No calibration in scans** | `ScanArScreen.kt:124` calls `setCalibration(null)`. A new session gets a new UUID. `reviewTip` maps CALIBRATE to MEASURE_AGAIN. The draft has `calibrationFactor = 1.0`, and `calibrated` is always false. |
| **Flat W/H, any tap order** | `ScanComposer.flatGeometry` runs `Shapes.rectangle` on the same three positions, so `threeCornerOrder` picks the same middle corner as the reading. W is the edge most aligned with world-up × n. Tests cover clockwise taps and taps that go down first. |
| **Diagonal ±** | The rectangle's own diagonal is used. Its random part is propagated from W and H; the noise of the shared corner s1 is included, which is conservative. Scale and floor are applied once. An edit gives a TYPED or PRESET diagonal with no ± (`ScanDraftBuilder.kt:117-124`). |
| **Edits and presets** | An edit becomes `Dimension.typed` (± 0, "Edited by you"). Preset depth is labelled "estimate", with `scan_depth_note`. *Use measured* resets. The badge rates measured values only and is ROUGH on bad geometry. `gallery_badge_measured_only` is shown once anything is edited. |
| **Projection** | `ImageProjection` uses `camera.pose` (the sensor pose, +X along image rows) and `imageIntrinsics` (CPU-image pixels). These are the same conventions as `DepthPatch`, so display rotation never enters. `scaledTo` matches the acquired image size. TL, TR, BR, BL as seen from the front land clockwise in the non-mirrored image, so `Homography.rectify` keeps corner 0: no mirroring, and no rotation for vertical faces. Tests include round trips with `Ray.fromPixel` and `DepthPatch`. |
| **NV21** | V then U (NV21), from `planes[2]` and `planes[1]` with their `rowStride` and `pixelStride`. Full-range BT.601 fixed-point coefficients, clamped. Odd sizes are clamped safely. |
| **Memory** | The camera config is limited to a CPU long side ≤ 1280 (`SessionSetup.kt:44,90`), so the bitmap is ≤ about 5 MB. The rectifier caps its window at 4 MP. Output is ≤ 1024 px. |
| **ARCore threading** | All `Frame`, `Camera` and `Image` use is inside `onFrame` (main/session thread). `acquireCameraImage` is closed with `use {}` on every path. Only `ByteArray`, `Bitmap` and `FloatArray` leave the thread. Conversion runs on the worker, rectify and JPEG on `Dispatchers.Default`, and the gallery write on I/O. |
| **Lifecycle** | Capture is disarmed in `onSessionPaused`, `onSessionCreated`, undo, clear and close. The new code never detaches an anchor and never touches the session after `close()`. `closed` is checked after the conversion. |
| **Compose throttling** | `FrontCaptureStatus` is a data class (5° angle steps, progress in quarters) and is set only when it changes. This breaks only under B1. |
| **Ads** | Place calls `onVisitLeftWithoutAd`, which never arms a chance and also drops a pending one. Share stays on Saved and arms nothing. Done and Back after a save call `onMeasureLeaving`, which arms one chance consumed by Gallery or Home once RESUMED and once no AR screen is composed. Leaving without a save arms nothing. `isCurrent()` guards double taps. `ArScreenAdGuard` covers both AR steps, and Saved is non-AR. `recordCompletedScan` is called once per stored scan: Save is guarded synchronously by `saving`/`savedId` on the main thread. Tracker tests: 14. |
| **Measure regression** | `ArCameraView` passes the same parameters as before. The `MeasureSession.onFrame` changes do nothing unless a capture is armed (pose is computed once and reused). The `cancelFrontCapture` calls set an already-null StateFlow. The BOX branches are reachable only from Scan. The M2 tests still pass. |
| **Strings** | 45 `scan_*` keys plus 4 `home_*` keys in both EN and HI (EN has only `app_name` extra, which is untranslatable). The format arguments match (`%1$d/%2$d`, `%1$s × %2$s × %3$s`, `%1$d°`). |
| **Gallery contract use** | `ScanDraft`, `DimensionSource` (MEASURED, PRESET, TYPED), `GalleryRepository.save` (exceptions caught and shown as `saveFailed`), `shareCopy`, `FrontPhotoRectifier` (corner 0 = top-left, aspect = measured W/H) and `isValidSide` are all used as documented. |

## Tests missing

1. `FrontCaptureGate` with repeated timestamps (B1), and a gap-then-resume sequence.
2. The front face decided once: outline → capture with the camera moving in between; long-object edge choice (M1); horizontal flat face seen from the other side.
3. No capture with unanchored points (M2). This needs a small seam: a fake endpoint provider, or extracting the capture state machine from `MeasureSession` into a JVM class.
4. **No `ScanViewModelTest`**:
   - double Save → one `gallery.save` and one `Saved` event;
   - `remeasure` during a save (m4);
   - `onFrontPhoto` cancelled, then the bitmap is recycled (m6);
   - `onFrontPhoto` with no outline;
   - `skipPhoto` leads to a draft with no JPEG.
   These are JVM-testable with a fake `GalleryRepository`, using an interface or a test double.
5. An end-to-end orientation test across `:ar` and `app`: project a known textured world quad with `ImageProjection` (portrait and landscape sensor poses), rectify it, and check that the output is upright and not mirrored.
6. `Nv21` odd sizes, and `toNv21` with padded, interleaved planes (m16).
7. A BOX case in `DisplayedBoundTest` with a realistic floor base. The current one puts the base on the wall plane and only exercises the height path.

## Add to `docs/TESTING.md` (device)

- **Auto photo:** taken within about 1 s of holding still. Test on a 60 Hz phone and on a 90/120 Hz phone (B1).
- **Box front choice:**
  - Finish a 120 cm cabinet standing near its corner, then face the long side: the photo is the long face and W is 120 (M1).
  - The Review photo of a TV or frame is upright in portrait and in landscape.
- **Pause in step 3** (power button), then resume: no photo is taken from stale corners (M2).
- **Low carton on the floor:** can it be captured without crouching? Tune `MAX_ANGLE_DEG` (m13).
- **Steadiness thresholds:** with a normal hand-held hold, `HOLDING` is reached. Tune `STEADY_MAX_*`.
- **Ads:**
  - Save, then Place: no ad.
  - Save, then Share, then Done: at most one ad, over the Gallery.
  - Save, then Back: at most one ad, over Home.
  - Back without a save: never an ad.
  - The interstitial never appears over the camera.
