# M3 gate: Scan → Gallery (Final Reviewer + Judge)

Judge, 2026-11-05. Gate tree: the uncommitted work on top of **ccebb01** (Builder A's scan flow, Builder B's gallery, and both fix rounds). I checked it against `docs/PLAN.md` (M3 row, §4 budgets), `HANDOFF.md`, `docs/research/ui-ux.md` §3.3 A–C and §5.2, `docs/reviews/M3-scan-review.md` and `docs/reviews/M3-gallery-review.md`. No source was edited.

## Verdict: **PASS WITH CONDITIONS**

All 1 BLOCKER and 5 MAJOR findings from the two reviews are fixed in the code, and each has a test that would have caught it. I found no new BLOCKER or MAJOR.

- The front photo now captures on repeated SceneView frames.
- The front face is decided once and travels with the outline.
- No photo is taken against corners left unanchored by a pause.
- The diagonal ± is a fully correlated upper bound on both screens.
- A dimension edit can no longer split the model from its row.
- **Place** never shows an ad.

The per-scan budget holds against real encoder bytes and is now a hard check. The APK grew by about 0.15 MB.

Two conditions remain:
- **R1.** The Gallery labels a TV's outer (bezel) diagonal as "Diagonal" (scan m10, half fixed).
- **R2.** The device checklist must carry the M3 items, especially the v1→v2 upgrade on a phone that already has M2 History rows. The migration has never run on a device.

Both are small. No new review round is needed.

### How this was verified
- **Reading.** I read every file changed or added since ccebb01 that the review findings and cross-cutting checks touch:
  - `:ar`: `FrontCaptureController`, `FrontCaptureGate`, `ScanComposer`, `ImageProjection`, `Nv21`, and the `MeasureSession` / `MeasurementComposer` / `FrameReaders` diffs.
  - `app/scan`: all files.
  - `app/gallery`: `GalleryFormatter`, plus the parts of `GalleryScreen` and `GalleryItemScreen` that matter here.
  - `data/gallery`: `ScanDraft`, `GalleryItemEntity`, the `GalleryRepository` save / edit / delete / share paths.
  - `SpaceViewDatabase` and the migration test.
  - `AppNavHost`, `AdsManager`, `MeasureVisitTracker`, `AdScreenEffects`.
  - The Measure diffs: `MeasureViewModel`, `MeasureHud`, `FreezeOverlay`, `MeasureScreen`, `DimensionPainter`.
  - The tests behind each fix.
- **Schema.** A script compared `data/schemas/…/2.json` with `MIGRATION_1_2`.
  - The `gallery_items` `createSql` is **identical** to the migration string.
  - The index matches.
  - The four `measurements` columns are nullable with no default, in both the entity and the `ALTER`s.
  - `identityHash` `3968e687…` in `2.json` equals the one in the generated `SpaceViewDatabase_Impl` (debug and release). So the JSON was re-exported after the in-place v2 change, not edited by hand.
- **Strings.** A script compared `values/` with `values-hi/`:
  - EN 295 keys, HI 294. The missing one is `app_name`, which is untranslatable.
  - No key is missing, and no format argument differs.
  - Four entries are identical on purpose (format-only or licence strings).
- **Sizes.**
  - The scan sizes are the numbers printed by `GalleryRepositoryTest` in the last green run (`data/build/test-results/.../GalleryRepositoryTest.xml`).
  - The APK is the orchestrator's release build (14:06).
  - I estimated the download with deflate-9 over the APK without `armeabi-v7a`/`x86`, the same method as at the M2 gate.
- **Orchestrator build, green:**
  - Unit tests: engine 163, ar 83, data 48, app 97, with 0 failures.
  - `koverVerify`, `assembleDebug`, `assembleRelease`, and `lintDebug` for app, ar and data.
  - I did not run Gradle.

---

## 1. Review items (BLOCKER and MAJOR)

| # | Finding | Status | Evidence |
|---|---|---|---|
| Scan B1 | Auto-capture never fires: SceneView repeats camera timestamps at display rate and the gate reset the hold | **FIXED** | `FrontCaptureController.kt:84` returns early when `timestampNs == lastTs`. `FrontCaptureGate.kt:120` also ignores `dt ≤ 1e-4` without touching motion or the hold, the same rule as `TrackingGate`. `MeasureSession.kt:405` feeds the controller only while armed. Tests: `FrontCaptureControllerTest.capturesOnRepeatedFramesAt60And120Hz` (each timestamp 1×, 2× and 4×; captured within `HOLD_NS + 4` frames) and `FrontCaptureGateTest.repeatedCameraFramesStillCapture`. |
| Scan M1 | Box front face chosen twice, with the wrong (midpoint) rule | **FIXED** | **Rule:** `ScanComposer.frontEdge` (`ScanComposer.kt:143-162`) takes the argmax of the outward normal · direction to the camera. **Decided once:** `MeasureSession.scanOutline` (`:521-530`) decides `FaceChoice` once per point generation (anchor refinement does not bump the generation). The choice is stored in `ScanOutline.choice`. `startFrontCapture(outline)` (`:540-546`) arms with that outline, and `FrontCaptureController` only ever builds `ScanComposer.face(shape, points, o.choice)`. FLAT keeps its `up` vector from outline time. **Tests:** `ScanComposerTest.wardrobeLongFaceWinsNearItsEnd` (all four probe positions → edge 0), `faceChoiceIsStableWhenTheCameraMoves`, `horizontalFlatFaceKeepsItsOrientationFromTheOtherSide`, `FrontCaptureControllerTest.photographsTheOutlinesFace`, and the end-to-end `FrontPhotoOrientationTest` (landscape, portrait and oblique views come out upright and unmirrored). |
| Scan M2 | Photo taken against corners left unanchored by a pause | **FIXED** | **Arming:** `MeasureSession.kt:543` arms with `pointsAnchored = endpoints.all { it.anchor != null }`, and `FrontCaptureController.arm` (`:49`) refuses with `REMEASURE`. **While armed:** losing anchors disarms (`:86`). **Screen:** `ScanArScreen.kt:350-399` shows "The outline was lost when the app paused…" with **Measure again** (clears every point) and **Skip photo**. Pause also disarms (`MeasureSession.onSessionPaused`) and drops any conversion in flight. **Tests:** `refusesUnanchoredPoints`, `losingAnchorsWhileArmedStopsTheCapture`. |
| Gallery M1 | TV diagonal ± understated by 20–27%; a ± shown on a typed diagonal | **FIXED** | `GalleryFormatter.kt:71-79`: b_d = (w·b_w + h·b_h)/d (fully correlated, so an upper bound). σ = 0 and "~" when W or H is an estimate. Tests: `GalleryFormatterTest.diagonalBoundCoversTheEngineDiagonalForTheReviewCases` and `diagonalOnAnEstimateHasNoPlusMinus`. |
| Gallery M2 | Edit writes the GLB before the row, so the two can diverge | **FIXED** | `GalleryRepository.kt:244-288`: the new model is written under a new name (`nextModelName`), then **one** `UPDATE` sets the dimensions and the model path, then the old file is deleted. All of this is `NonCancellable`, and the new file is deleted on failure. `cleanUp` sweeps unreferenced models and regenerates a missing one from the row. Tests: `failedDimensionWriteKeepsModelAndRowInAgreement`, `deathBeforeTheRowUpdateLeavesOldPairAndSweepsTheNewModel`, `deathAfterTheRowUpdateSweepsTheOldModel`, `repeatedEditsInTheSameMillisecondGetDistinctModels`. |
| Gallery M3 | **Place** on the Saved screen would use up the after-save interstitial | **FIXED** (by Builder A) | `AppNavHost.kt:141-146`: Place calls `ads.onVisitLeftWithoutAd(entry.id)`, which goes to `MeasureVisitTracker.leaveWithoutAd` (`:68-74`). That closes the visit without arming and drops any pending chance. `AdsManager.onVisitLeftWithoutAd` also cancels a pending show job. Tests: `MeasureVisitTrackerTest` "scan saved then Place in my room never arms an interstitial" and "leaving without an ad also drops an already armed chance". |

### MINORs spot-checked

| # | Item | Status |
|---|---|---|
| Scan m1 | Stuck on "Got it." after a conversion fails | FIXED: the failure disarms, clears the status and re-arms (`MeasureSession.kt:798-805`). |
| Scan m2 | Late photo after Back or Undo | FIXED: every arm, disarm and capture bumps a generation, and the conversion `Job` is cancelled in `cancelFrontCapture`. The photo is published only if the generation still matches. `ScanViewModel.onFrontPhoto` also recycles a photo that arrives when the step is no longer PHOTO. |
| Scan m3 | Photo step on a new session | FIXED: `ScanArScreen.kt:155-157` goes back to the outline. |
| Scan m4 | Back or Measure again during a save; discarding work silently | FIXED: both are ignored while saving (`ScanViewModel.remeasure`, `backToType`), with a confirm dialog when there is review work. Tested (`remeasureAndBackAreIgnoredWhileSaving`, `reviewWorkNeedsConfirmation`). |
| Scan m5 | Process death; a missed `Saved` event | FIXED: `SavedStateHandle` keeps the kind and the saved id. A flow that was saved comes back on Saved; one still measuring restarts its outline (anchors cannot survive). `onScanSaved` is called from the save itself (`AppNavHost.kt:117`). Tested (`processDeathOnSavedRestoresSaved`, `processDeathWhileMeasuringRestartsTheOutline`, `doubleSaveStoresOnceAndCountsOnce`). |
| Scan m6 | Bitmap lifecycle | MOSTLY FIXED: `close()` recycles an untaken photo, and the view model uses `invokeOnCompletion` plus `finally`. One path remains: see N1. |
| Scan m7 | Box top below or at the base | FIXED: `MeasurementComposer.boxTopTooLow` (2 cm) → `CommitFailure.BOX_TOP_TOO_LOW`, with an EN and HI message. |
| Scan m8 | Three base references | FIXED: one `MeasurementComposer.boxFoot` is used by the reading, the overlay and the face. |
| Scan m9 | BOX readouts mislabelled | PARTIAL, accepted. Freeze-frame now shows H. The live label is `scan_box_value` "%1$s × %2$s × %3$s", unlabelled and in tap order. The Outline card is labelled and authoritative. |
| Scan m10 | TV "Diagonal" is the outer diagonal | PARTIAL: Review says "Outer diagonal (incl. bezel)" (`ScanReviewScreen.kt:106`). The Gallery item, card and share text still say "Diagonal". → **R1** |
| Scan m11 | Review and Gallery diagonal ± differ | FIXED: `ScanDraftBuilder.diagonalOf` (`:129-133`) uses the same formula as `GalleryFormatter.diagonal` (`:71-79`). See N3 (two copies). |
| Scan m12 / m13 / m14 / m16 | Copy; per-shape angle; accessibility; NV21 tests | FIXED: "a back corner… left or right", "floor or table"; BOX 25° (`FrontCaptureGate.MAX_ANGLE_DEG_BOX`); "Edit width / height / depth" descriptions, progress description, no angle in the live region; `Nv21Test` covers odd sizes, padded strides, and interleaved and planar chroma (`Nv21.fromPlanes` extracted). |
| Gallery m1 | Cancelled save cleans up mid-transaction | FIXED: `ensureActive()` then `withContext(NonCancellable) { commit }` (`GalleryRepository.kt:165-167`). Tested (`cancellingDuringTheTransactionNeverLeavesARowWithoutFiles`, `cancellingBeforeTheCommitLeavesNothing`). |
| Gallery m3 | Measured value with ± = 0 | FIXED: `validate(forSave = true)` requires `bound95 > 0` for MEASURED. Tested. |
| Gallery m4 | Provenance is a single boolean | FIXED: `DimensionSource` MEASURED / PRESET / TYPED, plus `measured_*` columns that edits never overwrite. |
| Gallery m5 | History canonical fields never filled | FIXED: `MeasureViewModel.save` (`:107-125`) passes the tier, the ARCore version, the factor actually applied (1.0 when the reading is not calibrated) and `rawPrimary`. `calibration_factor` is nullable, so legacy rows read NULL ("unknown"). |
| Gallery m6 | Budget not a hard limit | FIXED: `check(bytes <= MAX_SCAN_BYTES)` on the exact staged bytes (`:186`). Tested with an 80-character Devanagari label and a 16:1 photo. |
| Gallery m7 | CPU work on IO | FIXED: `cpu = Dispatchers.Default.limitedParallelism(2)`. |
| Gallery m10 / m11 | Share copies; FileProvider scope | FIXED: versioned `share/<id>-<updatedAt>/` copies, pruned after 1 day and deleted with the item. Tested. |
| Gallery m12 / m13 | Bad rows hidden; late delete pop | FIXED: enum fallbacks and an error state (`gallery_error_load`); delete navigates only while RESUMED (`GalleryItemScreen.kt:100`). |
| Gallery m16 | Banner next to the grid | FIXED in the code (divider, 24 dp, fixed slot). **Not recorded** in ui-ux.md §5.3 (N8). |
| Gallery m18 | The empty state cannot start a scan | FIXED: `onScan` is wired (`AppNavHost.kt` GALLERY). |

---

## 2. Cross-cutting consistency (Builder A ↔ Builder B)

| Check | Result |
|---|---|
| **`ScanDraft` / `Dimension` / `DimensionSource` contract** | Consistent. `ScanDraftBuilder.measured` stores the value to 0.1 mm and ±95 **rounded up** to 0.1 mm, so it is never below the engine's (`:84-88`). An edit gives `Dimension.typed` (± 0). A frame or TV depth gives `Dimension.preset`. `GalleryRepository.normalised` only zeroes the ± of estimates. `validate` refuses a measured side with ± 0. `calibrationFactor = 1.0` because a scan never applies a calibration (`setCalibration(null)`, `ScanArScreen.kt:127`). The Review badge is the AR badge when nothing is edited, otherwise the worst of the measured sides (ROUGH if the geometry was inconsistent). The gallery uses the same `Quality.worst` and never upgrades. |
| **Diagonal ± formula** | Identical on both screens: (w·b_w + h·b_h)/d on the stored W and H, and an estimate with no ± when either side is not measured. The Review computes it from the same `Dimension`s it saves, so after save the Gallery recomputes exactly the same number. Two copies of one formula: see N3. |
| **Ad flow, Place** | No ad. `onVisitLeftWithoutAd`, then navigate to the gallery item. That item's `NonArScreenAdEffect` finds no pending chance and only preloads. Deferred placement (§5.2 rule 6) is not implemented, which errs towards fewer ads. |
| **Ad flow, Share** | No ad. Share stays on Saved and arms nothing. The share sheet pauses the destination, and the return finds no pending chance. |
| **Ad flow, Done or Back after a save** | At most one capped ad, on a non-AR screen. `recordCompletedScan`, then `visits.onScanSaved` marks the visit. Done or Back calls `onMeasureLeaving`, which arms one 5 s chance. Gallery (Done) or the previous screen (Back) uses it once it is RESUMED and `arForeground` is false, then `AdPolicy` caps it. Before a save, Back arms nothing. The AR steps carry `ArScreenAdGuard`. Saved's own `NonArScreenAdEffect` can never show an ad, because `onMeasureEntered` cleared any earlier pending chance and this visit's chance is armed only on leaving. It preloads, which is what §5.2 rule 4 wants. |
| **Ad flow, Measure unchanged** | Yes. The Measure route only gains the `arcoreVersion` argument, and the BOX mode is not in Measure's mode list (`MeasureHud.kt:354`). |
| **Room v2 schema ↔ entities ↔ `MIGRATION_1_2`** | Match (see "How this was verified"). `addMigrations(*MIGRATIONS)` is registered, and there is no destructive fallback. History and Gallery share one instance (`SpaceViewDatabase.get`). **`SpaceViewMigrationTest` (two tests) has never run on a device** → R2. |
| **History's new fields** | Populated from `MeasureScreen` → `vm.save(reading, label, state.capabilities?.tier)`. The tier is null only while capabilities are unknown, which cannot happen after a commit. The factor and raw length are as described above. |

## 3. New issues (introduced or left by the fix rounds)

None is a BLOCKER or MAJOR.

| # | Severity | Where | Problem | Fix |
|---|---|---|---|---|
| N1 | MINOR | `MeasureSession.kt:792-797` | If the capture job is cancelled while `Nv21 → Bitmap` runs on the worker, `withContext` throws `CancellationException` after the block has built the bitmap. `bitmap` is still null in the `finally`, so it is left to the GC (0.3–1.2 MB at ≤ 1280 px). Rare (Back or pause within about 50 ms of the shot) and not a leak. | Build and return the bitmap inside `withContext(worker + NonCancellable)`, then check `isActive`/generation and recycle. |
| N2 | MINOR | `MeasureViewModel.kt:108`, `ScanViewModel.save` (`arcoreVersion()`), `AppContainer.arcoreVersion` | `PackageManager.getPackageInfo` (a Binder call) runs on the main thread on every save. | Read it once, lazily on IO, or inside the `launch` with `withContext(io)`. |
| N3 | MINOR | `ScanDraftBuilder.diagonalOf` and `GalleryFormatter.diagonal` | The same formula exists in two copies that can drift. Also, the Review shows a diagonal for FRAME while the Gallery shows one only for TV. | Make `GalleryFormatter.diagonal` call `ScanDraftBuilder.diagonalOf`, or move both into one helper. Add one test that the same W and H give the same ± through both. Decide whether frames show a diagonal. |
| N4 | MINOR (UX) | `MeasureSession.scanOutline` | The front face is fixed at the first `scanOutline` call, i.e. when the last point lands, from wherever the user stands then. That is consistent everywhere (the card shows W/D), but the only way to pick another face is to measure again. | M4: an "Use the other side as front" toggle on the Outline card (swap the edge, then recompute W/D). |
| N5 | MINOR | `Routes.parseMode` | `measure/BOX` would open Measure in BOX mode. It is not reachable from the UI. | Restrict `parseMode` to DISTANCE, HEIGHT and RECTANGLE. |
| N6 | MINOR (UX) | `ScanUiState.hasReviewWork` | Back on Review with no photo, no edit and no name throws away the measured outline without asking. A box outline is about 30 s of work. | Treat an existing outline as review work, or make Back on Review return to the photo step. |
| N7 | MINOR (perf) | `DimensionPainter.kt` BOX branch | It allocates per draw (`take(3)`, `rectangleOutline`, `Vec3`). This only happens in scan BOX mode; Measure's hot path is unchanged. | Fold into the M6 per-frame allocation pass (M2 minor 8). |
| N8 | DOC | `docs/research/ui-ux.md` §5.3 | The Gallery banner decision (an anchored bottom slot, not inline every 12 items) is not recorded. | One line in §5.3, or in a decisions log. |

**Areas I checked in particular and found clean:**
- **Measure regressions:**
  - `onFrame` computes the camera pose once (it was computed for `FrameSample` anyway).
  - The capture costs one boolean check when not armed.
  - `cancelFrontCapture` in Undo, Clear and pause only sets an already-null flow.
  - `ArCameraView` passes the same SceneView parameters.
  - The Measure HUD changes add a BOX branch only.
- **`SavedStateHandle`:** the kind and saved id are written on every `setState`. Restore gives SAVED when an id exists, otherwise OUTLINE.
- **Threading:**
  - `FrontCaptureController` and all `Frame`/`Image` use stay on the main thread.
  - `acquireCameraImage` is closed with `use {}`.
  - The NV21 conversion runs on the single worker.
  - Rectify and JPEG run on Default.
  - Gallery encoding is limited to two threads, and files and Room run on IO.
  - Every `catch` rethrows `CancellationException`.
- **Hindi:** complete (§ "How this was verified").

## 4. Budgets

| Budget | Status | Evidence |
|---|---|---|
| **Per scan ≤ 0.5 MB** (PLAN M3 gate) | **Met, and enforced** | **Test output, last green run (JDK JPEG as the stand-in, thumbnail as JPEG rather than WebP, so it overstates):** typical 1024×768 **211,717 B** (42%); detailed poster **455,992 B** (91%); worst-case noise (stepped down to q66, 768 px) **455,147 B**; noise with an 80-character Devanagari label **455,539 B**. **Hard check:** `check(bytes <= 500_000)` on the exact staged bytes, so an item over budget is never stored. **Detailed scans are close to the limit** because the photo is stored twice (in the GLB and as `front.jpg`). See M4 recommendation 5. |
| **Download ≤ 15 MB arm64** (PLAN §4) | **Met (estimate)** | Release universal APK: **19,648,326 B** (M2: 19,500,230 B, so **+148 KB**). `classes.dex` 8.05 MB (M2: 7.78 MB). No new dependencies. Deflate-9 estimate without `armeabi-v7a`: **10.08 MiB** (M2: 9.94 MiB). The bundletool / CI gate is still owed (no remote). |
| **AR per-frame allocations** | **No regression in Measure** | While the photo capture is armed (Scan step 3 only), each frame allocates a few small objects: a `Decision`, a `FrontCaptureStatus`, the `::currentPoints` reference and the `fitCorners` temporaries. The face is rebuilt every 10 camera frames. Repeated frames are now skipped before any work. The NV21 copy (about 0.5–1.4 MB) happens once per scan on the main thread, an estimated 5–20 ms hitch, which is acceptable. |
| **30 fps** | Device-only (M4 gate) | n/a |

---

## 5. Conditions (close before commit)

| # | Where | Fix | Check |
|---|---|---|---|
| **R1** | `app/.../gallery/GalleryItemScreen.kt:250` (diagonal row label); `GalleryFormatter.diagonalShort` callers (`GalleryViewModels.kt:55`, `GalleryItemScreen.kt:184`, `ScanRoute.kt:254`, through `gallery_diagonal_short`) | For `ObjectKind.TV`, label the row with `scan_diagonal_outer` ("Outer diagonal (incl. bezel)"). Change the card and share text to "%1$s outer diagonal" (a new key `gallery_diagonal_outer_short`, EN and HI). A user comparing "56.6 in diagonal" with a 55″ box label would otherwise read a correct measurement as a 1.6 in error. | Gallery item, card and Saved share text for a TV, in EN and HI. |
| **R2** | `docs/TESTING.md` | Add the device items in §6 below. **The first item is mandatory before the user installs the M3 APK:** install it **over** the M2-final APK on a phone that has History rows. History must still list them, and Gallery must open empty. If a USB phone is available, also run `:data:connectedDebugAndroidTest` (`SpaceViewMigrationTest`). | n/a |

After R1, rebuild the debug and release APKs from the clean worktree as planned.

## 6. Device-checklist items to add to `docs/TESTING.md`

**Upgrade and data**
1. **v1 → v2 upgrade:** install the M3 APK over M2-final with saved History rows. The rows survive with the same values, and Gallery opens empty. No crash on Home, History or Gallery.

**Front photo**

2. **Auto photo** is taken within about 1 s of holding still and parallel. Test on a 60 Hz phone **and** a 90/120 Hz phone (scan B1).
3. **The photographed face is the face whose width is shown:**
   - Box: finish a long cabinet standing near one end of its long face. The Outline card shows W = the long side, and the photo step guides you to that face (scan M1).
   - TV or frame: the Review photo is upright and unmirrored in portrait and in landscape.
4. **Pause in step 3** (power button or a call), then resume. "The outline was lost…" appears, no photo is taken, and **Measure again** and **Skip photo** both work (scan M2).
5. **A low carton on the floor** can be captured at 25° without crouching. If it cannot, note the angle you needed (`MAX_ANGLE_DEG_BOX`).
6. **Steadiness:** a normal hand-held hold reaches HOLDING. Tune `STEADY_MAX_*` and `HOLD_NS` from what you see.
7. **Box top tapped on the floor** next to the box: rejected with "aim at the top" (scan m7).

**Review, Gallery and edits**

8. **The Review number equals the Gallery number:** W/H/D and the TV diagonal ± are identical on Review, Saved, the item screen and the share text.
9. **Edit a side** in the Gallery: the value shows "~" and no ±, and the diagonal loses its ±. Kill the app right after the edit (swipe away): the item still opens, and **Place** (M4) later uses the edited size.
10. **Share** from Saved and from the item screen to WhatsApp: the photo and the size text arrive. Delete the item: its share copy is gone.
11. **Thumbnail and photo quality:** note the Review photo's sharpness on this phone (the CPU image is often 640×480). This is input for M6's high-resolution still.

**Ads**

12. **Ads (scan flow):**
    - Save, then **Place**: no ad.
    - Save, then **Share**, then **Done**: at most one ad, over the Gallery.
    - Save, then system **Back**: at most one ad, over the screen you came from.
    - Back without a save: never an ad.
    - An ad never appears over the camera or on the Saved screen itself.

**Process death and Hindi**

13. **Process death:** on Saved, enable "Don't keep activities" and switch apps, then return. You land back on Saved for the same item.
14. **Hindi:** run the whole scan flow (type picker → Saved) in Hindi. No English left, and no truncated buttons.

## 7. Recommendations for M4 (place in room, fit verdict, wall fallback)

1. **Place from the stored model only.** Load `GalleryItem.modelFile` (a versioned name) and check the GLB `extras.widthM/heightM/depthM` against the row before placing. Regenerate the model if they differ; `cleanUp` already knows how. Show the "1:1 True size" chip, and lock the scale, with no pinch-scale.
2. **Fit verdict honesty.**
   - **Combining bounds:** the space is measured live, with its own ±95. Combine it with the item's ± conservatively; the linear sum is the honest default, as the diagonal shows.
   - **Fits:** only when spare > combined ±.
   - **Tight:** whenever the margin is within the combined ±.
   - **Typed or preset sides:** treat them as exact for the verdict, but say "using your typed size".
   - **Tests:** cover all three verdicts at their boundaries, including one with a typed side.
3. **Wall fallback (§3.5):** use the same `MeasureSession` plus a two-tap floor–wall line to build a vertical plane. Reuse `ScanComposer.canonicalOrder` and the `FaceChoice` idea, so that the "up" and "front" of a placed frame match its photo. Add a TESTING item for plain painted walls.
4. **Deferred ad (§5.2 rule 6):** if the product wants it, carry the owed chance from Saved → Place to closing the placement screen, at most once. That screen is AR, so the chance must be used only by the next RESUMED non-AR screen; reuse `MeasureVisitTracker` with a "carried" flag and test it. Otherwise document that Place never owes an ad.
5. **Headroom in the size budget.** A detailed photo uses about 91% of 0.5 MB because the JPEG is stored twice. If M4 adds a side photo (ui-ux §3.3 A.4 "Add side photo"), do one of these:
   - reference `front.jpg` as an external URI from the GLB (gltfio can load a sidecar), or
   - drop `front.jpg` and extract the image from the GLB for display and sharing.
6. **Front face choice (N4):** add an "Other side is the front" toggle before the photo step. It matters most when placing, because the photo faces the room.
7. **30 fps gate:** profile Place on the mid-range phone with the textured GLB plus the plane renderer. Settle the per-frame allocations (N7, M2 minor 8) and the SceneView keep rules and unused materials in the same pass.
8. **Carry over:**
   - N2 (the ARCore version on main) and N3 (one diagonal helper).
   - The HANDOFF M2 gate items still open: the first CI run (lint for every module, the bundletool size gate, the 16 KB check), and the bench-test tuning.
