# T + D0 review: furniture templates (Builder B) and the 3D-scan spike harness + N1/N3/N4/N5 (Builder A)

**Reviewer:** FRIDAY §1, §4.B (standard + thermonuclear), §4.E for the harness metrics. Read-only; this file is the only write.
**Scope:** the uncommitted work on top of `de63706` (`git status`, `git diff HEAD`, all untracked files listed there).
**Method:** I read the tests first, then the diff, then the call sites (GalleryRepository save and placement paths, SessionSetup, TsdfVolume conventions, AdsManager visit API, GallerySaveGate, ReconMemoryTest). Evidence is `file:line` from this session. **I ran no Gradle task** (as instructed: the orchestrator's build is running and RAM is low). The only commands I ran were `git` and two read-only scripts: Hindi key parity, and placeholder parity across every `strings*.xml` in `main` and `debug`.

## Verdict: **FAIL for the spike run until BLOCKER 1 and MAJOR 2 are fixed. T is APPROVED WITH WARNINGS (MINORs only).**

**Counts:** BLOCKER 1, MAJOR 1, MINOR 7. There are also 3 nits, which are not counted.

All findings are regressions in new code; none is a pre-existing defect. Both serious findings are in the spike harness and would corrupt the numbers the user brings back. Builder B's templates work is clean apart from MINORs.

---

## BLOCKER

**1. The governor's frame-time rule fires on every 30 fps session, so integration falls to 2 Hz within about 3 s:** `ar/src/main/kotlin/com/spaceview/ar/ShapeScanGovernor.kt:132` and `:80`, fed by `DepthCaptureListener.kt:295`.

- **Problem:** The rule compares the p90 of *camera-timestamp intervals* against `MAX_FRAME_P90_NS = 33_000_000`. The camera is locked at 30 fps (`SessionSetup.kt:41`, `TARGET_FPS_30`), so the nominal interval is 33.33 ms. Even a perfect session has a p90 above 33.0 ms. The unit test feeds exactly `33 * ms` (`ShapeScanCaptureTest.kt`, `governorStepsDownOnSlowFramesButNotOnOneHiccupAndNeverStepsUp`), so it passes on a value no real camera produces.
- **Scenario:** The user starts a carton scan on any phone.
  1. After 20 intervals (about 0.7 s), the first evaluation steps the rate from 5 Hz to 3 Hz (`FRAME_TIME`).
  2. Two seconds later (the cooldown) it steps to 2 Hz, and the rate never steps back up.
- **Effect on the spike:**
  - **Criterion 2** ("p95 ≤ 33 ms at ≥ 3 Hz integration") can't be tested: `c2_rate_ge_3hz` is false on every run.
  - **Criterion 1:** a 90 s capture integrates at most about 180 frames instead of about 450, so coverage and the extents get worse.
  - **Misleading cause:** `governor_events` reads `FRAME_TIME@700ms FRAME_TIME@2700ms`, so the spike would blame frame time for a cost that isn't there. The likely conclusion would be "lower N_MAX and the rate" (§7.1.10's "If 2 or 3 fail" branch) when nothing is slow.
- **Fix (smallest correct change):** Measure *missed* camera frames instead of the raw interval.
  - Step down when the p90 interval exceeds about 1.5× the nominal period. That is about 50 ms at 30 fps, meaning frames are being dropped.
  - Alternatively, use the wall-clock `updateIntervalNs` against the same 1.5× bound.
  - Name the constant relative to the locked fps.
  - Change the test to feed 33.4 ms intervals (with ±1 ms jitter) and assert **no** step-down, then feed 66.7 ms intervals (one frame dropped in two) and assert a step-down.
  - Correct ARCH §7.1.6's wording ("p90 exceeds 33 ms (from frame timestamps)") to match, because the spec carries the same trap.
- **If skipped:** every spike run reports the wrong integration rate and a false frame-time cause, and the go/no-go decision for D is made on corrupted data.

## MAJOR

**2. Criterion 3's processing number leaves out the TSDF, so "peak extra PSS" under-reports by up to about 12.6 MB:** `app/src/debug/kotlin/com/spaceview/app/scan3d/LabViewModel.kt:241`; `LabMetrics.kt:177`.

- **Problem:**
  - `processingBaseline` is read after `c.finish()` but **before** the pipeline drops the volume. `holder.volume` is cleared only at `ShapeScanPipeline.kt:123`, so the baseline already contains the TSDF.
  - `extraProcessing = peakProcessing − processingBaseline` is therefore only the meshing working set *on top of* the volume.
  - The criterion, and the JVM bound it is meant to confirm, count the volume plus marching cubes together. `ReconMemoryTest` gives "marching cubes (volume alive) 18.9 MB = volBytes + aMc".
  - `peak_extra_pss_mb = max(extraCapture, extraProcessing)` never adds the two.
- **Scenario:** A real noisy sofa scan whose marching cubes adds 30 MB on top of a 12.4 MB volume.
  - True peak extra: about 42 MB, which **fails** the 40 MB criterion.
  - The lab reports `max(≈13, 30) = 30 MB`, so `c3_extra_pss_le_40mb = true`.
- **Aggravating factor (debug build, which the spike uses):** In `ShapeScanPipeline.process`, the local `val vol` (`:111`) still references the volume after `holder.volume = null`. A debuggable ART runtime keeps locals live to the end of the method, so the debug run measures the "volume kept to the end" pipeline, not the one release ships.
  - This makes later stages *pessimistic*, but it doesn't repair the marching-cubes under-count above.
- **Fix:**
  - Compute `extraProcessing = peakProcessing − processingBaseline + volumeBytes`, or report a separate `pss_extra_processing_incl_volume_mb`.
  - Take the processing baseline only after the AR session's teardown has settled (it is closed asynchronously when the PROCESSING step replaces the AR screen). Otherwise the camera and GL release during processing pushes the deltas down further.
  - Move "noise + marching cubes" into a helper that reads `holder.volume` itself, so no caller-frame local outlives the release.
  - Add a `LabMetricsTest` case: baseline including the volume, plus a stage peak, must report extra ≥ the volume.
- **If skipped:** criterion 3 can PASS on a phone where D would actually exceed the 40 MB budget.

## MINOR

**3. N1: a qualifying point-only bin below the real floor still beats a plane-backed floor:** `ar/src/main/kotlin/com/spaceview/ar/FloorEstimator.kt:94-110`.

- **Problem:** `estimate()` returns the **lowest** qualifying bin. A bin qualifies through one plane hit, *or* through ≥ 5 point hits spread over ≥ 15 cm. A plane-backed floor bin gets no priority over a point-only bin below it.
- **Scenario:** On a glossy vitrified floor, the reflected top edge of a 45 cm TV unit is a horizontal line of mirrored features. Sweeping the reticle across it can give ≥ 5 point hits with an estimated horizontal normal, spread over more than 15 cm, in one 2 cm bin at −0.45 m.
  - That bin becomes "the floor".
  - Every genuine wall–floor tap is then rejected (more than 3 cm off the floor).
  - The wall centre-height readout reads 45 cm high.

  The new test covers only a *single-spot* reflection. Whether reflections give horizontal-normal point hits at all is a device question (TESTING §18 item 4).
- **Fix:** Use the lowest **plane-backed** bin whenever one exists. Fall back to point-only bins only when no bin has a plane vote. Add a test with a spread point-only cluster 45 cm below a plane floor.

**4. Template immutability is a convention only, with no test guarding shipped geometry:** `engine/src/main/kotlin/com/spaceview/engine/furniture/FurnitureTemplate.kt:118-124`; shared constants `CUSHION_GAP` (`SofaTemplates.kt:189`), `Board.*` (`CabinetTemplates.kt:16`), `SofaParams.LEG_SIZE`/`MIN_SEAT_W`, `cornerLegs`.

- **Problem:** The KDoc says "never change the geometry of a shipped id". The determinism tests compare two builds *within one run*. Nothing pins `sofa_v1` (or any other id) across commits, and several constants are shared between templates.
- **Scenario:**
  - **A look change:** a later tweak to `CUSHION_GAP` for a new `sofa_v2` silently changes every stored `sofa_v1`/`lsofa_v1` the next time a `glbVersion` bump regenerates them. No test fails.
  - **A range change:** tightening `MIN_SEAT_W` makes some stored rows invalid. `partsOrNull` then returns null, and the user's saved sofa shows as a plain box.
- **Fix:** Add a golden test per template id: for fixed parameter maps (the defaults, each preset, and one boundary map), assert a SHA-256 of `GlbWriter.writeParts(build(p))` and that `validate` stays empty. The test's message should say "ship a new `_vN` instead".

**5. Thermal SEVERE at capture start gives a 90 s dead capture:** `LabViewModel.kt:197-199`.

- **Problem:** `thermal.start()` immediately forwards the current status to `capture.onThermal()` **before** `c.start(vol)`.
  - At SEVERE, `governor.stopped` becomes true, but `stop()` is a no-op because the capture isn't running yet.
  - `start()` then sets `running = true`, and `governor.due()` stays false for the rest of the run.
- **Scenario:** The phone is already SEVERE (a repeat run without cooling). The HUD shows a running capture that integrates nothing until the 90 s `TIME_CAP`. Processing then reports "no depth frames were integrated".
- **Fix:** Call `thermal.start()` after `c.start(vol)` succeeds, or make `start()` return false (or stop at once) when `governor.stopped`.

**6. Two per-run checks don't mean what §7.1.10 says:** `LabMetrics.kt:217-218`.

- **(a) `c3_no_severe`:** On API < 29, `ThermalMonitor` records −1, so `thermalMax = -1 < 3` gives **true**, a pass with no data.
  - **Fix:** Null when every status is negative.
- **(b) `c2_rate_ge_3hz`:** It uses the *achieved average* (`framesIntegrated / capture_s`). That folds in the blur and view-diversity gates and the seconds before the user starts walking. Criterion 2 is about the governor's integration rate.
  - **Scenario:** A careful slow walk (the protocol asks for one) with the governor at 5 Hz can still average below 3 integrated frames per second, which reads as a false FAIL.
  - **Fix:** Also log the time-weighted governor rate, and key `c2_rate_ge_3hz` on that rate. Append the new columns; don't reorder.

**7. Depth intrinsics come from the texture and colour from the CPU image, but neither image's size is logged:** `DepthCaptureListener.kt:339-345`, `:438-458`.

- **Problem:**
  - The intrinsics are `textureIntrinsics` scaled independently in x and y to the depth size. That is correct only if the depth image covers the texture's field of view.
  - Colour is decimated from the CPU image, whose field of view can differ: `selectCameraConfig` picks the largest texture with a CPU image ≤ 1280 px, so a 16:9 texture next to a 4:3 CPU image is plausible.
  - The metrics record only `depth_w`/`depth_h`.
- **Scenario:** On such a config, vertex colours are offset or stretched. If the depth image is 4:3 while the texture is 16:9, the anisotropic `sx ≠ sy` scaling distorts geometry too. Criterion 1 would then fail, and the spike would blame depth noise, not intrinsics.
- **Fix:** Append `texture_w/h`, `cpu_w/h` and an `aspect_mismatch` flag to the CSV and JSON. This is cheap, and it makes a criterion-1 failure diagnosable before anyone concludes "depth is bad".

**8. A Back press during the ~100 ms "starting" window leaks the recorders into the next run:** `LabViewModel.kt:149, 193`; `LabPerf.kt:27`.

- **Problem:**
  - `backToIntro()` detaches, but it doesn't null `capture`, so `if (capture !== c)` doesn't catch the abandoned start.
  - `c.start(vol)` then runs with no session. No frames arrive, so the time cap never fires, and `frames`, `thermal` and `pssJob` keep running.
  - On the next `beginAr` → `startCapture`, `FrameIntervalRecorder.start()` returns early because it is still `running` and doesn't clear. The new run's `ar_frame_ms_*` then mixes in the old samples, and two PSS jobs append to the list.
- **Fix:** In `backToIntro()`, set `capture = null` (or a generation counter) and stop `frames`, `thermal` and `pssJob`.

**9. Gate §8 D8 / K2 for D are not exercised.** The lab is not the D flow, so this is a note for D integration, not a defect in the harness. The lab's direct save calls neither `ads.recordCompletedScan()` nor the visit tracker. That is fine, because the lab is debug-only with no ads. When D integrates, the save path must move to `GatedGalleryStore` (HANDOFF says so) **and** record the completed scan, and `MeasureVisitTrackerTest` must gain the D case (gate D7). I'm counting this as a MINOR only so it isn't lost: **D integration must not copy the lab's save path.**

### Nits (not counted)
- **N-a:** `GlbWriter.kt:436`: the KDoc `/** Extents (W, H, D) of [pos] without moving it. */` now sits above `aabbCentre`'s KDoc, so it dangles and `extents()` has none.
- **N-b:** `AppNavHost` `startScan` (N4): a second Scan tap on the *new* screen during the ≤ 1 s check is swallowed, because the guard says "active" and the first check then drops its result. The user taps again; no harm.
- **N-c:** ARCH §6.2 says "Gallery FAB → Add furniture". The build has the Home row plus the **empty**-gallery button only, so a non-empty gallery has no furniture entry. This is a spec deviation to note, not a bug.

---

## Gate M4-P1 §8 requirements: were they met?

### T (furniture templates)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| T1 | Fix the rule conflict and flip `shipped` | **Met** | `ProCatalog.kt:122` (`Allowance.Only(FREE_TEMPLATE_IDS)`, `shipped = true`); `FeatureGate.kt` `Only` branch; `FeatureGateTest` covers the core ids, Pro ids, an unknown id, no id, Pro and Pending. |
| T2 | `saveTemplate` through one `:app` store, gated at the picker before AR | **Met** | `GatedGalleryStore.kt` (template gate, then `checkItem`); the only production caller is `FurnitureViewModel.kt:56`; `pick()` gates before `OPTIONS`/`MEASURE`. The Save backstop keeps the draft (`GatedSave.NeedsPro` → `needsPro`, inputs untouched; `FurnitureViewModelTest` "needs Pro at Save keeps the draft"). A gate read error never blocks, and Save re-checks. |
| T3 | Wire `TemplateModelSource` and test that stale models regenerate | **Met** | `AppContainer` `GalleryRepository.create(appContext, templates = FurnitureTemplateRegistry)`; `GalleryRepository.regenerate` passes `palette()`. `FurnitureRegenerationTest` bumps `glbVersion` and asserts a byte-identical rebuild (parts **and** palette), not a box; the no-registry case falls back to a box. |
| T4 | Union exactly W×H×D; property-tested | **Met** | `build()` checks the union to 1e-9 (`FurnitureTemplate.kt:179-185`). The property test samples 500 maps per template with fixed seeds, including 10% boundary values. Every option combination at the 8 W/D/H corners passes, and every length is refused 1e-6 outside its range and accepted on it. The GLB is checked to 0.1 mm. I also checked every template's closed-form maths by hand: no empty part over the stated ranges, every range non-empty, and the extremes come from `±W/2`, `0`/`H`, `±D/2` directly. |
| T5 | Verdict on the bounding box; "overall box" on the card | **Met** | `PlaceVerdict.kt:114` (`boundingBox = kind == FURNITURE`). The `place_fit_bounding` string is now "Checked against the overall box (width × depth × height)" in EN and HI. Review shows `furniture_overall_box_note`. This is honest for L-sofas: the footprint is conservative. |
| T6 | Budget, `MAX_PARTS` | **Met** | ≤ 40 parts asserted; the sofa GLB is under 16 KB. |

**Also checked (T):**
- **± and TYPED honesty:**
  - Measured lengths go through `ScanDraftBuilder.measured` (± rounded up).
  - Typed lengths are `Dimension.typed`, with no ±.
  - Defaults show as "typical" (`Dimension.preset`).
  - W/D/H must be MEASURED or TYPED before Save, so a preset or default is never saved as the size.
  - Measured extras keep only their value in the JSON, which is fine because only W/H/D feed the verdict.
- **Calibration:** never applied; the row records `calibrated = false`.
- **Process death:** step, template, lengths with ±, options, preset, palette, name, saved id and back targets are all restored. Tests cover mid-measure, Review, after Save, and a corrupt id.
- **K2 (ads):**
  - The visit is keyed by the back-stack entry.
  - `onSaved` records exactly one completed scan.
  - Place leaves without an ad, and Done/Back arm a chance only after a save.
  - `ArScreenAdGuard` is on the AR step; Saved is the non-AR preload point.
  - This is the same as the scan flow, and the new tracker test covers it.
- **No Room schema change.** The `:data` diff touches only `PlacementModel` (not an entity) and an interface default method. No schema JSON changed.
- **Size edits for templates:** hidden on the item screen, matching the repository's refusal.

### D (spike harness only, as scoped)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| D1 | Spike first; no D screens | **Met** | Debug source set only. No user-facing D route. |
| D2 | Pipeline order exactly as in `ReconMemoryTest` | **Met** | `ShapeScanPipeline.kt`: noise and coverage from the volume → marching cubes → `holder.volume = null` → floor clip `max(1 cm, v)` → `keepLargestComponent(insideBox = local)` → decimate → `MeshExtent` + cross-check → `writeMesh(keepInputOrigin = true)`. This matches `ReconMemoryTest` line for line. The pipeline decimates to 15k, as D2 says, where the test uses `DEFAULT_TARGET_TRIANGLES = 20_000`. The caller-local `vol` caveat is in MAJOR 2. |
| D3 | Budget retry, then save as a box | **Partly met (lab)** | One retry at 12k triangles on a byte-budget `IllegalArgumentException`. The lab has no "save as box" fallback, which is acceptable for a harness. The D flow owes it. |
| D4 | One gated insert path; gate before capture | **Lab: gated direct save, as allowed** | `LabViewModel.save()` calls `gallerySaveGate.checkDeviceScan()` (`checkItem` then the DEVICE_SCAN count) before `saveMesh`, and keeps the result on `NeedsPro`. The production `GatedGalleryStore` is template-only: D owes the mesh path (MINOR 9). |
| D6 / N3 | Mesh bounding-box centre | **Met** | Reserved `aabbCx/Cy/Cz` (from written floats) → `GlbInfo.centre` → `PlacementModel.centre` → `PlacedItem(aabbCentre)`. Walls use the real centre (`attachOffset`, `centreHeight`); floor and table use the declared base (`declaredCentre`). The reserved keys can't be forged (test). `phase0.md` is updated. Proxies and templates are unchanged (centre null). |
| N1 | Robust floor | **Met, with MINOR 3** | `FloorEstimator`, plane-aware, with tests for a single-spot reflection, a bin edge and confirm/reset. |
| N4 | Navigation race | **Met** | `AppNavHost` captures `origin = currentBackStackEntry` and navigates only if it is still `===` the top entry. A Home→Settings→Back return keeps the same entry instance, so a still-wanted scan proceeds. |
| N5 | Per-frame allocations | **Partly met, as HANDOFF claims** | The anchor pose is cached until it moves, `placedCache` keeps the same `Pose`/state instance, and `NodeHolder.show` skips identical poses (test `aSteadyPlacedItemKeepsItsPoseInstance`). Every command (`drop`, `moveWorld`, `rotate90`, `twist`, `clearPlacement`, a new anchor pose) invalidates the cache. The camera pose → engine conversion and the Preview state still allocate per frame. HANDOFF's "N5 partial" is accurate. |

---

## Harness threading and correctness: checked and clean
- **ARCore objects stay on the session thread (main):**
  - Every `Session`/`Frame`/`Camera`/`Image` call is inside the `ArFrameListener` callbacks.
  - `start`/`stop`/`onThermal` are `@MainThread`, called from `viewModelScope` (Main) or the main executor (thermal listener).
  - The fusion worker touches only the slot and the volume.
- **Image close on every path:** depth, confidence and the CPU image are each in `use {}`. The early returns inside `use` (repeated, stale, size mismatch, no slot, no confidence) all close the image. The no-confidence path also releases the slot.
- **Slot-reuse race:**
  - `DepthSlot.busy` is `@Volatile`. The worker clears it only after `integrate` returns, and main acquires only `!busy` slots.
  - The channel send gives happens-before for the slot contents. Capacity 2 equals the ring size, so `trySend` can't fail on capacity, and a failed send releases the slot.
- **Worker join:** `finish()` joins the worker before processing, which gives happens-before for the volume and for `fusionNs`.
- **Camera pose:**
  - It is `Camera.getPose()` (not the display-oriented pose), and it is read on the frame whose depth timestamp equals `frame.timestamp`.
  - The conventions match `DepthFrame` and `TsdfVolume`: −Z forward, +Y up, rows downward (`TsdfVolume.kt:182`).
- **Intrinsics:** texture intrinsics scaled to the depth resolution (see MINOR 7 for the aspect caveat).
- **Depth source:**
  - RAW when `RAW_DEPTH_ONLY` is supported, under the session's AUTOMATIC mode (raw images are available there).
  - HARDWARE from the active camera config's `REQUIRE_AND_USE`.
  - Otherwise SMOOTHED, with confidence filled to 255 and `sampleWeight` from `DepthSource`.
  - It falls back to SMOOTHED once, after 10 non-`NotYetAvailable` raw failures before the first success.
- **Timestamps:**
  - A repeated frame timestamp returns before touching any state.
  - A stale or repeated depth image is skipped and counted, without consuming the "due" slot, so the next frame retries.
- **Stops:**
  - USER, 90 s cap, SEVERE, session pause or recreate (a new world frame), and screen left.
  - SEVERE stops at once, and MODERATE steps down without a cooldown.
  - The rate never steps up.
- **Metrics schema:**
  - The CSV columns are a fixed append-only list, and the header is re-checked: a different header rotates the old file.
  - Quoting follows RFC 4180, numbers are formatted with `Locale.ROOT`, and the JSON has `schema: 1` with typed text columns.
  - `LabMetricsTest` exists (`testDebug`).
- **Choreographer metric:** it measures the UI and AR render cadence that `gfxinfo` would, with no allocation per frame. `p95 ≤ 33 ms` matches criterion 2.
- **PSS sampling:**
  - `Debug.getPss` runs on IO, every 1 s during capture and after each processing stage.
  - Sampling at stage boundaries can miss a peak inside a stage, but ART rarely gives heap pages back within a stage, so the reading leans high. The baseline error is MAJOR 2.
- **Lab absent from release (source-level):**
  - Lab classes, layouts, strings and the `<activity>` exist only in `app/src/debug` and `app/src/testDebug`.
  - `build.gradle.kts` has only `debug` and `release` build types, with no `initWith(debug)`.
  - The Settings link is behind `if (BuildConfig.DEBUG)` (constant-folded) and names the activity by string only.
  - `main` has one string, `settings_scan3d_lab`, with a Hindi copy; it is harmless in release.
  - The activity is not exported.
  - The share path uses the existing `cache/share/` FileProvider root.

## Cross-cutting
- **Shared-file merges are correct:**
  - **`AppNavHost`:** the N4 guard and the furniture destination and entries are both present, and they don't conflict.
  - **`strings_place.xml`:** one change, in EN and HI.
  - **`GalleryRepository`:** A's `centre = info.centre` and B's palette regeneration are independent hunks.
  - **`GalleryDrafts`:** `PlacementModel.centre` and the `TemplateModelSource.palette()` default sit side by side.
- **Hindi parity (script):**
  - Every `values/strings*.xml` in `main` and `debug` has the same keys in `values-hi`. The only exception is the pre-existing untranslatable `app_name`.
  - The format placeholders match for every key. The script's three hits were regex artefacts (a full stop after `%1$s`).
- **Room:** no entity or schema change, so v3 stands (the amendment window is closed, per HANDOFF).

## Risks to the user's spike run (summary)
1. **BLOCKER 1:** every run drops to 2 Hz within about 3 s and logs a false `FRAME_TIME` cause. Fix it before the user captures anything.
2. **MAJOR 2:** `c3_extra_pss_le_40mb` can pass when the true peak fails. Fix it, or at least read `pss_peak_processing_mb − pss_processing_baseline_mb + volume_mb` by hand.
3. **Debug build (orchestrator decision):** pure-Kotlin fusion and marching cubes on a debuggable runtime can be several times slower than release. `c2_fusion_le_15ms` and `c4_processing_le_5s` FAIL results on debug are not conclusive; that is what the planned release-signed `lab` build type is for. The volume also stays live after marching cubes on debug (MAJOR 2), which makes PSS pessimistic.
4. **MINOR 7:** if criterion 1 fails, check whether the texture and CPU aspect ratios differ before blaming depth. The current CSV can't show it.
5. **MINORs 5 and 8:** they cost runs, not correctness. Start each run cool, and don't press Back while "Starting…" is shown.

---

## FRIDAY report

**Decision:** CHANGES REQUESTED for the D0 harness (BLOCKER 1, MAJOR 2). T: APPROVED WITH WARNINGS.

**Evidence:**
- Code read at the `file:line` references above.
- `git diff HEAD` and every untracked file in scope.
- Hindi key and placeholder parity, from a script I ran.
- The camera's 30 fps lock (`SessionSetup.kt:41`) against the governor's 33.0 ms threshold and its 33 ms test input.

**Findings:** BLOCKER 1, MAJOR 1, MINOR 7 (3 nits not counted).

**Not checked:**
- **No Gradle task was run** (per instructions): no compile, unit tests, Kover, lint or `assembleRelease`. HANDOFF's test counts (engine 281, ar 125, data 65, app 282) and its "no lab code in release (dex + usage.txt)" are **claims I didn't verify**.
- My release-absence verdict is at the source-set, manifest and guard level only.
- No device run: ARCore timestamp behaviour for smoothed depth on a real phone, the raw-depth availability rate, `Debug.getPss` cost, Choreographer cadence under SceneView, thermal listener delivery, and whether glossy-floor reflections produce horizontal-normal point hits (MINOR 3).
- `LabScreens.kt` beyond the AR step and wiring (lines 1–240 read; the result screen and mesh viewer were skimmed only for the save state), and `FurnitureVisuals.kt` / `FurnitureLabels.kt` / most of `FurnitureScreens.kt` (I read Review only).
- `TemplateParamsCodec` JSON edge cases (pre-existing, M4-reviewed); `PaywallScreen` copy for the newly advertised template benefit, beyond the string text.
- Accessibility (TalkBack order, 48 dp targets) of the new screens, beyond what is visible in the code I read.
- `SpaceView.html` regeneration and `docs/TESTING.md` updates for T and the lab.
