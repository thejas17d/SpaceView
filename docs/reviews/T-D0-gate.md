# T + D0 gate: furniture templates, 3D Scan Lab spike harness, N1/N3/N4/N5 (Final Reviewer + Judge)

FRIDAY, 2026-11-17. Gate tree: the uncommitted work on top of **de63706**. It covers:
- **T**: furniture templates (Builder B), with the MINOR 4 fix round (`TemplateGoldenTest`, per-template constants objects);
- **D step 1**: the debug-only 3D Scan Lab spike harness, plus carry-over MINORs N1, N3, N4 and N5 (Builder A), with the B1 / M2 / MINOR 3, 5, 6, 7, 8, 9 fix round.

I checked it against `HANDOFF.md` (Phase 2 entries), `docs/ARCHITECTURE-v2.md` §4.3, §4.5, §6 (with the new immutability rule), §7.1.6 (updated governor rule), §7.1.10, `docs/reviews/M4-P1-gate.md` §8 and `docs/reviews/T-D0-review.md`. No source was edited and no Gradle task was run; this file is the only write.

## Verdict: **PASS WITH CONDITIONS**

**T is APPROVED.** All six gate §8 T requirements hold, and review MINOR 4 is closed by a 39-case golden test.

**D step 1 is APPROVED for the spike, after one fix.** BLOCKER 1 and MAJOR 2 are fixed in the code, and each has a test that fails on the old code. The fixes introduce no new BLOCKER or MAJOR. However, I found one **MAJOR the review missed (G1)**, which is pre-existing in the harness rather than a regression:
- **The problem:** the lab's criterion-2 frame check, `c2_ar_p95_le_33ms`, compares Choreographer *intervals* against 33.0 ms. Those intervals are whole multiples of the vsync period, so a session holding a steady 30 fps reads 33.33 ms and is flagged **FAIL**. This is the same trap as BLOCKER 1, moved from the governor to the metrics.
- **What survives:** the raw `ar_frame_ms_p95` is logged, so no data is lost.
- **The risk:** the flag would push the spike towards the "if 2 fails" branch for no reason. It is a two-constant fix plus one test (R1), and it must land before the spike APK is built.

**Decision on the orchestrator's call (debug build for the spike): upheld, with a rule.**
- **Conclusive from a debug build:** criterion 1 + R2 (accuracy), and any **PASS** of criteria 2–4.
- **Not conclusive:** a **FAIL** of fusion time, AR frame time, thermal or processing time. Each of those must be re-run on a release-signed `lab` build before anyone acts on it (§4).

There are three conditions (§6): R1 is code and R2 is documentation, both small, and R3 is the APK hand-off.

### How this was verified
- **Reading** (tests first, then code, then call sites):
  - **Spike harness:** `ShapeScanCaptureTest` (governor), `LabMetricsTest`, `FloorEstimatorTest`, `TemplateGoldenTest`; `ShapeScanGovernor`, `DepthCaptureListener`, `ShapeScanPipeline`, `FloorEstimator`, `SessionSetup.selectCameraConfig`, and the `PlacementCore` fallback and centre-height paths.
  - **Lab app:** `LabViewModel`, `LabMetrics`, `LabPerf` (FrameIntervalRecorder, ThermalMonitor, Pss, LabStore), and `LabScreens` (the AR step, the HUD and the result screen).
  - **T:** `GatedGalleryStore`, the `ProCatalog` rule, the furniture constants objects, the size assertions in `FurnitureTemplatesTest`, and the `GlbWriter` nit N-a.
  - **Build:** `app/build.gradle.kts` (build types) and `app/src/debug/AndroidManifest.xml`.
- **Test results on disk** (script over the result XMLs):

  | Module | Tests | Failures | Skipped | Results written |
  |---|---|---|---|---|
  | engine | 282 | 0 | 0 | 11-16 22:42 |
  | ar | 129 | 0 | 0 | 11-17 10:23 |
  | data | 65 | 0 | 1 | 11-17 10:23 |
  | app | 286 | 0 | 1 | 11-16 22:41 |

  These agree with the orchestrator's run.
- **Freshness:**
  - No `app`, `ar`, `data` or `engine` source file is newer than its module's latest results.
  - The only file changed after 11-16 22:43 is `ShapeScanCaptureTest.kt` (10:21), and the ar results (10:23) postdate it.
- **APK contents** (my `unzip -p … | grep -a` over the dex files):
  - **The debug APK (11-16 22:43) contains the fixes.** Each of the strings `dropThresholdNs`, `pss_extra_processing_incl_volume_mb`, `aspect_mismatch`, `TOO_WARM`, `c2_achieved_rate_ge_3hz` and `settledPss` is present.
  - **The release APK (11-17 10:30) contains no lab code.** There are 0 hits for `Scan3dLab`, `scan3d_lab`, `c3_extra_pss_le_40mb`, `SV.depthCopy`, `SV.fusion` and `marching_cubes`. R8 also dropped the unused capture and pipeline classes. The template ids (`sofa_v1`, `lsofa_v1`) are present.
- **Orchestrator build, 2026-11-17, EXIT 0:**
  - The four unit-test tasks above, plus `koverVerify`, `assembleDebug`, `assembleRelease`, and `lintDebug` for app, ar and data.
  - App lint shows 0 errors and 6 warnings, the same count as at M4. I did not run Gradle.

---

## 1. BLOCKER and MAJOR from the review

| # | Finding | Status | Evidence |
|---|---|---|---|
| B1 | The governor's 33.0 ms frame rule fired on every 30 fps session, so integration fell to 2 Hz | **FIXED** | **Rule:** `ShapeScanGovernor.kt:93` steps down only when the p90 of the camera intervals is above `dropThresholdNs` = `framePeriodNs × DROP_FACTOR (1.5)` (`:34`, `:169`), which is 50 ms at 30 fps. The period defaults to `FRAME_PERIOD_30FPS_NS` = 33,333,333 (`:166`) and is set from the session's camera config before capture (`DepthCaptureListener.kt:192-194`). **Healthy case:** `governorStaysAt5HzOnAHealthy30fpsCamera` (`ShapeScanCaptureTest.kt:33`) feeds 90 s of 33.33 ms ± 1 ms intervals with a 2.5% rate of single drops, and asserts 5 Hz with 0 events. The old 33.0 ms rule fails it, because every p90 is about 34 ms. **Real drop:** `governorStepsDownOnDroppedFramesWithACooldownAndNeverStepsUp` (`:47`). A 200 ms hiccup keeps 5 Hz; alternating 33.3 / 66.7 ms intervals (p90 66.7 ms) step down to 3 Hz, then to 2 Hz after the 2 s cooldown; healthy frames never step back up. **60 fps:** `governorDropThresholdFollowsTheCameraRate` (`:67`) gives a 25 ms threshold. **Spec:** ARCH §7.1.6 now states the 1.5× rule. |
| M2 | Processing PSS left out the volume (an under-count of up to 12.6 MB) | **FIXED** | **Formula:** `LabMetrics.kt:192-194` computes the processing extra as (stage peak − processing baseline) + `volumeBytes`, and `peak_extra_pss_mb` is the larger of capture and processing (`:197`). **Baseline:** taken after the AR screen has gone, by `settledPss()` (`LabViewModel.kt:264`, `:300-310`): two readings 500 ms apart within 1 MB, after at least 1 s and at most about 4 s. **No caller-frame volume local:** steps 1–2 moved into `noiseAndMesh` (`ShapeScanPipeline.kt:167-179`), which sets `holder.volume = null` before returning. `process` holds only `noise`, `coverage`, `voxelM` and the mesh. **Test:** `processingExtraCountsTheVolumeHeldInItsBaseline` (`LabMetricsTest.kt:140`) has a 12.4 MB volume plus 30 MB of meshing. It expects 41.83 MB in `pss_extra_processing_incl_volume_mb`, and `c3_extra_pss_le_40mb = false`. The old formula gives max(24.4, 30) = 30 MB, which passes, so the test fails on the old code. Order D2 is unchanged: noise → marching cubes → release → floor clip → components → decimate → extent → GLB. |

### MINORs spot-checked

| # | Item | Status |
|---|---|---|
| 3 | A point-only bin below a plane floor won | **FIXED**: the lowest plane-backed bin first, then point bins (`FloorEstimator.kt:96-97`). Test: `aPlaneBackedFloorWinsOverASpreadPointClusterBelowIt` (12 spread hits 45 cm below a plane floor). **This creates the opposite trade-off; see G2.** |
| 4 | Template immutability was a convention only | **FIXED**: `TemplateGoldenTest` pins a SHA-256 of the canonical parts (names, materials, faces to 1e-7 m, yaw) for 39 cases: defaults, every preset, and W/D/H min and max for all 9 ids. It also asserts `validate` stays empty, and its failure message says "ship a new _vN". The constants are per-template objects (`SofaV1`, `LSofaV1`, `WardrobeV1`, `TvUnitV1`, `BookshelfV1`, `StudyTableV1`, `BedRules`), and the shared helpers are documented as frozen (`FurnitureTemplate.kt:156`). **Residual:** optional-parameter ranges are pinned only at the sampled maps (see §8, T-1). Whether the hashes were "captured before the refactor" cannot be checked, because the files are untracked. It doesn't matter: no T build has left the team, so these hashes define v1. |
| 5 | Thermal SEVERE at start gave a dead 90 s capture | **FIXED, three layers**: `startCapture` refuses with `TOO_WARM` (`LabViewModel.kt:193`); `DepthCaptureListener.start` refuses when `governor.stopped` (`:226`); and `thermal.start()` now runs after a successful `c.start` (`LabViewModel.kt:226-227`). Code-verified only, since the view model and listener need Android. |
| 6 | `c3_no_severe` passed with no data; `c2_rate` used the achieved rate | **FIXED**: negative statuses are ignored, so no data gives `"unknown"` (`LabMetrics.kt:175, 242-246`), and SEVERE always fails. `c2_rate_ge_3hz` reads the time-weighted governor rate (`ShapeScanGovernor.meanRateHz`, `:146`), and the achieved rate is appended as `c2_achieved_rate_ge_3hz`. Tests: `thermalWithoutAnApiIsUnknown`, `rateCheckUsesTheGovernorRateAndLogsTheAchievedOne`, `governorMeanRateIsTimeWeighted`. |
| 7 | Image sizes not logged | **FIXED**: `texture_w/h`, `cpu_w/h` and `aspect_mismatch` are appended (tolerance 1%). Test: `imageSizesAndAspectMismatch`. |
| 8 | Back during "Starting…" leaked the recorders | **FIXED**: `backToIntro` nulls `capture`, stops the frame, thermal and PSS recorders, and drops the volume (`LabViewModel.kt:154-169`). The pending start then sees `capture !== c` (`:215`), and `FrameIntervalRecorder.start` clears even while running (`LabPerf.kt:27-33`). Code-verified only. |
| 9 | D integration must not copy the lab save path | **FIXED (KDoc)**: `LabViewModel.kt:393-396`. Carried to D step 2 (§8, item D-2). |
| Nit N-a | Dangling KDoc in `GlbWriter` | FIXED (`GlbWriter.kt:436`, `:450`). |
| Nit N-c | No furniture entry in a non-empty gallery | OPEN: Home row plus the empty-gallery button only. The spec deviation goes into R2. |

---

## 2. Gate M4-P1 §8 requirements (what was in scope)

### T (furniture templates): all met

| # | Requirement | Status | Evidence |
|---|---|---|---|
| T1 | Rule conflict fixed; `shipped` flipped | **Met** | `ProCatalog.kt:122` (`Allowance.Only(FREE_TEMPLATE_IDS)`, `shipped = true`). Re-read this session. |
| T2 | One gated `:app` store; gate at the picker before AR | **Met** | `GatedGalleryStore.kt:29-41`: the template gate, then `checkItem`, at `templateBlocker` (picker) and again at `saveTemplate`. `NeedsPro` writes nothing, and the draft is kept. Confirmed in the review; unchanged since. |
| T3 | `TemplateModelSource` wired; stale models regenerate | **Met** | Review evidence (`FurnitureRegenerationTest`). Not touched by the fix round. |
| T4 | Union exactly W×H×D, property-tested | **Met** | Review evidence. The golden test adds cross-commit pinning (MINOR 4). |
| T5 | Verdict on the bounding box; "overall box" on the card | **Met** | Review evidence. |
| T6 | Budget, `MAX_PARTS` | **Met** | ≤ 40 parts, against a `GlbWriter.MAX_PARTS` of 512 (`FurnitureTemplatesTest.kt:355-360`). The sofa GLB is < 16,000 B (`:294`). |
| ADR-005 rule 2 | A lapse never hides data | **Holds by construction** | Placement regenerates through the registry with no gate, and the gates sit only at the picker and at Save. Device item 10 in §7. |

### D (scoped to step 1: spike harness only)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| D1 | Spike first; no D screens | **Met** | `app/src/debug` only. Release has 0 lab strings (verified above). No user-facing D route. `DEVICE_SCAN` is still `shipped = false` (`ProCatalog.kt:124`). |
| D2 | Pipeline order exactly as in `ReconMemoryTest` | **Met** | `ShapeScanPipeline.kt:113-157`. The helper split keeps the order and drops the volume immediately after marching cubes. |
| D3 | Retry, then save as a box | **Lab: retry only** (acceptable) | One retry at 12k on a byte-budget refusal (`LabViewModel.kt:419-428`). D step 2 owes "Save as box". |
| D4 | One gated insert path; gate before capture | **Lab: gated direct save** (as allowed) | `checkDeviceScan()` before `saveMesh` (`:410`). `GatedGalleryStore` has no mesh path yet; D step 2 owes it. |
| D5 | Settle the counting rule | **OPEN (user decision)** | "3 saved at a time" (as built) or "3 lifetime". |
| D6 / N3 | Mesh bounding-box centre | **Met** | Review evidence. Unchanged by the fixes. |
| D7 | K2 for D | **Owed by D step 2** | MINOR 9 KDoc. |
| D8 / D9 | Process death and pause; Beta label | **Owed by D step 2** | Not in scope for the harness. |
| N1 | Robust floor | **Met, with G2** | See §3. |
| N4 | Navigation race | **Met** | Review evidence. |
| N5 | Per-frame allocations | **Partial** (as HANDOFF says) | Carried to the 30 fps profile (TESTING §18 item 12). |

---

## 3. New issues

One MAJOR (G1) that the review missed, plus MINORs. G1 is pre-existing in the D0 harness; G2 to G4 come from, or sit next to, the fixes.

**G1. MAJOR: the criterion-2 frame check fails a steady 30 fps session:** `LabMetrics.kt:137, :236`; `LabViewModel.kt:481` (`JANK_NS`).
- **Problem:**
  - `ar_frame_ms_*` are `Choreographer` `frameTimeNanos` *intervals* (`LabPerf.kt:41-45`). Those are whole multiples of the vsync period: 16.67, 33.33 or 50 ms on a 60 Hz panel, and 8.33, 16.67, 25 or 33.33 ms on a 120 Hz one.
  - `c2_ar_p95_le_33ms` tests `p95 ≤ 33.0`, so one missed vsync in more than 5% of frames reads **33.33 ms → FAIL**. In effect the check demands about 60 fps (or about 40 fps on a 120 Hz panel). Criterion 2 is "30 fps AR", and its `gfxinfo` reference measures frame *duration*, not the interval.
  - `JANK_NS = 33_000_000` counts every 30 Hz frame as janky in the same way.
  - The test input `choreographerMs = Quantiles(16.7, 30.1, …)` (`LabMetricsTest.kt:33`) uses a p95 that Choreographer cannot produce. That is the same blind spot as B1's 33 ms test input.
- **Scenario:**
  - A mid-range phone runs the lab on debug. SceneView, MeasureSession and the capture copy-out take 17–30 ms of main-thread work per frame, so the screen holds a steady 30 fps.
  - `ar_frame_ms_p95 = 33.333` and `c2_ar_p95_le_33ms = false`, and the result screen lists it as false.
  - Per the orchestrator's rule, a debug c2 FAIL triggers the release-signed `lab` build. That build may hold 30 fps too and still read "false", which leads to the §7.1.10 "If 2 fails → lower N_MAX and the rate" branch with nothing wrong.
- **Fix (smallest correct change):**
  - Set `MAX_AR_P95_MS = 1000.0 / 30 + 0.5` (33.83) and `JANK_NS = 33_833_333L`.
  - With that, an exact 30 Hz cadence passes, and a missed 30 Hz slot fails (41.7 ms at 120 Hz, 44.4 ms at 90 Hz, 50 ms at 60 Hz).
  - Add a `LabMetricsTest` case: p95 = 33.333 gives `true`, and p95 = 50.0 gives `false`.
  - Don't rename the column. Note the definition in the KDoc.
- **If skipped:** the go/no-go for criterion 2 is decided on a flag that fails healthy phones. The raw p95 is still in the CSV, so the damage can be undone by hand, but only if someone notices.

| # | Severity | Where | Problem and scenario | Fix |
|---|---|---|---|---|
| G2 | MINOR (regression of the MINOR 3 fix) | `FloorEstimator.kt:96-97` | **Problem:** "any plane bin wins wherever it is" also lets a **higher** plane beat a real point-backed floor below it. **Scenario:** a glossy plain vitrified floor that ARCore never turns into a plane, with a textured coffee table that it does. Depth and point hits on the floor qualify (≥ 5 hits, ≥ 15 cm spread), but the table plane at +0.45 m is the floor. Every wall–floor tap is then rejected by `verticalThroughFloorLine`, and the wall centre-height reads 45 cm low (`PlacementCore.kt:321, 405`). **Context:** the old rule handled this case and failed MINOR 3's mirrored-floor case; the new one is the reverse. Height alone cannot tell the two apart, and which one happens on real floors is a device question. | **Decide from device item 22 (§7) before the M6 polish.** Candidate fix: a point-only bin below the lowest plane may still win when its spread is ≥ 0.5 m in **both** x and z, because a floor patch is 2-D while a mirrored edge is a line. Add a test for each case. |
| G3 | MINOR | `LabViewModel.kt:299-310` | **Problem:** `settledPss()` gives up after about 4 s (1 s + 6 × 500 ms, plus the `Debug.getPss` calls, about 0.1–0.5 s on a low-end phone) and returns the last reading **whether or not it settled**. No column says which. **Scenario:** on a slow phone the camera and Filament teardown is still releasing memory when the baseline is read. The stage PSS readings then fall below the baseline, and the processing extra under-reports. That is the M2 direction, though bounded. **Not affected:** `processing_ms`, which sums stage times taken outside the probe (`ShapeScanPipeline.kt:107-109`). | **Analysis rule now (no rebuild):** in `metrics.json`, the `noise` stage's `pss_mb` should be ≥ the processing baseline − 1 MB. If it is lower, PSS was still falling, so discard that run's c3 and re-run. **Optional append** while R1 is open: `pss_settle_ms` and `pss_settled`. |
| G4 | MINOR | `DepthCaptureListener.kt:192-194` | **Problem:** the drop threshold comes from `fpsRange.upper`, and the range is not logged. **(a) A 60 fps config** (reachable only if the TARGET_FPS_30 filter falls back to `session.cameraConfig`) gives 25 ms. An app rendering a steady 30 Hz would then step down for `FRAME_TIME` while AR meets its 30 fps budget. **(b) A variable range such as [15, 30]** (whether ARCore reports one is unverified): in dim light, auto-exposure halves the camera rate, which gives `FRAME_TIME` step-downs that are not app load. | Use `framePeriodNs = max(1e9 / upper, FRAME_PERIOD_30FPS_NS)`, since the governor protects the 30 fps AR budget. Append `camera_fps_min` and `camera_fps_max`. **Analysis rule now:** a `FRAME_TIME` event with a healthy `ar_frame_ms_p95` and `camera_frame_ms_p95` ≈ 66 ms means a camera-side drop (light), not app load. Don't count it against criterion 2; re-run in better light. |
| G5 | NIT | `LabMetrics.kt:106-133, :278` | **Positions:** the 104 original columns are untouched and 8 are appended, as required (112 now). **Meanings:** three existing columns changed meaning in place: `c2_rate_ge_3hz` (now the governor rate), `peak_extra_pss_mb` (now includes the volume), and `c3_no_severe` (can be `"unknown"`). This is safe today: the header changed, so `LabStore.save` rotates the old `runs.csv` (`LabPerf.kt:123-127`), and no lab build has left the team. **But:** nothing pins the header, and the JSON still says `"schema": 1`. | Pin the first 104 names in a literal golden list in `LabMetricsTest`, and bump the JSON to `"schema": 2`. Can ride with R1. |
| G6 | DOC | ARCH §7.1.10, §7.1.6, §4.3, §6.2 | **§7.1.10** says "release build", but the orchestrator's decision (HANDOFF) is debug, plus the lab build on a timing FAIL. **§7.1.6** says "a 1-slot channel with DROP_OLDEST"; the code has 2 pooled slots, `trySend`, and a backlog step-down. **§4.3** `Allowance` lacks `Only`. **§6.2** says "Gallery FAB → Add furniture"; the build has the Home row plus the empty-gallery button (review N-c). | → R2. |

**Answers to the specific checks:**
- **Governor `framePeriodNs`:**
  - At **30 fps** (the normal case, given `SessionSetup.kt:41`) the threshold is correct: 50 ms.
  - At **60** it is too strict for a 30 Hz renderer, and with a **variable** range it misattributes exposure drops.
  - Both are G4, and both are unlikely under the TARGET_FPS_30 filter.
  - **Robust in either case:** the 128-entry ring holds the 2 s window up to 60 fps, and `MIN_SAMPLES = 20`. One drop in fewer than 10% of frames never trips the p90.
- **The PSS settle loop on slow devices:** it is bounded (about 4–5 s added before processing) and never affects `processing_ms`. The risk is an unsettled baseline that under-reports; detect it with G3's rule.
- **CSV append-only:** by position, yes. By meaning, three columns changed, which is mitigated by rotation (G5).
- **Volume lifetime on debug:** `noiseAndMesh` removes the caller-frame local. The `startCapture` coroutine's `vol` dies with the coroutine, because there is no suspension after `c.start`. `finish()` nulls the listener's reference. The only remaining holder is `VolumeHolder`, which is cleared at `ShapeScanPipeline.kt:177`.

**Checked and clean:**
- **Governor time base:** `onFrame`, `onBacklog` and `onThermal` all use the capture's `clockNs`, and `meanRateHz` integrates over the same base (`LabViewModel.kt:373`).
- **SEVERE in the mean rate:** it records rate 0 after the stop.
- **`observedFraction`:** O(1) (`TsdfVolume.kt:293`), so it doesn't lengthen the worker's slot hold.
- **Lab debug app:**
  - It installs **beside** the release app (`applicationIdSuffix = ".debug"`, `build.gradle.kts:107`), so the user's M4 gallery is untouched.
  - FileProvider uses `${applicationId}.fileprovider` (`AndroidManifest.xml:89`), which matches `LabStore`.
  - Debug sells through FakeBilling.

---

## 4. Spike readiness

**Can the user run §7.1.10 and bring back valid data?** Yes, once R1 is in the APK and the analysis rules below are followed.

The data is there:
- **Criterion 1 and R2:** the labels `carton-0.7m` and `carton-1.5m`, typed tape W/H/D, `mesh_minus_tape_*`, `mesh_pm_covers_tape`, and the actual stand-off in `mean_range_m` (use it, not the label, for R2's error-versus-range check). `aspect_mismatch` covers MINOR 7.
- **Criterion 2:** `fusion_ms_p95`, `ar_frame_ms_p95`, `governor_mean_hz`, `governor_final_hz` and `governor_events`.
- **Criterion 3:** `thermal_*`, `peak_extra_pss_mb` and the per-stage PSS in the JSON.
- **Criterion 4:** `processing_ms` and the per-stage times.

The protocol text in the lab (`strings_scan3d.xml:8`) matches §7.1.10 plus R2.

### What a debug build can and cannot conclude

A debuggable runtime JITs with fewer optimisations, has no AOT or baseline profile, and keeps locals alive to the end of each method. That makes CPU-bound work slower and memory lifetimes longer, but leaves array sizes and the ARCore and VIO data the same.

| Criterion | Columns | Debug **PASS** | Debug **FAIL** |
|---|---|---|---|
| **1 + R2: accuracy** | `c1_mesh_within_pm_of_box`, `mesh_minus_tape_*`, `mean_range_m` | **Conclusive** | **Conclusive** if the run integrated normally (no `BACKLOG` event, `governor_final_hz` ≥ 3). If a debug-only backlog starved it of frames, re-run on `lab`. Before blaming depth, check `aspect_mismatch`. |
| **2: fusion** | `c2_fusion_le_15ms` | **Conclusive** (release is faster) | **Not conclusive**: re-run on `lab`. |
| **2: AR frame time at ≥ 3 Hz** | `c2_ar_p95_le_33ms` (after R1), `c2_rate_ge_3hz`, `governor_final_hz` | **Conclusive** only if `c2_rate_ge_3hz = true` and there is no `BACKLOG` event; otherwise the pass was bought by a lower rate | **Not conclusive**: re-run on `lab`. |
| **3: thermal** | `c3_no_severe` (`unknown` below API 29) | **Conclusive** (debug burns more CPU) | **Not conclusive**: re-run on `lab`. |
| **3: memory** | `c3_extra_pss_le_40mb`, `pss_extra_processing_incl_volume_mb` | **Conclusive** if G3's settle check passes. Debug over-counts: the whole volume is added back, plus JIT cache growth and longer local lifetimes. | **Probably real**, but re-run on `lab` before lowering `N_MAX`. |
| **4: processing** | `c4_processing_le_5s` | **Conclusive** | **Not conclusive**: re-run on `lab`. |

**When a release-signed `lab` build is required:**
1. **Before acting on any FAIL** of the criterion-2, criterion-3 or criterion-4 rows above, and in particular before taking the §7.1.10 "If 2 or 3 fail" branch (lower `N_MAX` and the rate, or open the GLES-compute ADR).
2. **Always, before D's user-facing timing promises ship.** That covers the ≤ 3 s processing target with its progress bar, and the 30 fps capture claim. The Phase 3 release candidate re-measures criteria 2 and 4 on the release build itself (§8, P3-4).

**Building the lab build type when it is needed:**
- **Build type:**
  - `create("lab") { initWith(getByName("release")); applicationIdSuffix = ".lab"; versionNameSuffix = "-lab"; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }`
  - The `sourceSets["lab"]` source set points at `src/debug/kotlin`, `src/debug/res` and `src/debug/AndroidManifest.xml`. Today `src/debug` holds only the lab.
- **Entry point:** add `buildConfigField("boolean", "LAB", …)` (true for lab, false for the others), and gate the Settings link on `BuildConfig.DEBUG || BuildConfig.LAB`.
  - The link is needed because the activity is not exported, so `adb am start` cannot reach it on a non-debuggable build.
- **Profiling:** add `<profileable android:shell="true"/>` in the lab manifest so `gfxinfo` and Perfetto work.
- **Release safety:** never upload `lab`. CI keeps checking that the **release** dex has 0 `scan3d_lab` (R3).
- **Telling builds apart:** the `-lab` suffix shows in the `app_version` column, so lab rows and debug rows stay separate.

---

## 5. Budgets

| Budget | Status | Evidence |
|---|---|---|
| **APK / download** (PLAN §4: arm64 ≤ 15 MB; CI fails above 18 MiB) | **Met (estimate)** | **Release universal APK:** **20,124,777 B**, +134,612 B (+131 KiB) on M4's 19,990,165 B. **The delta:** `classes.dex` 8,667,828 → 8,862,428 B (+194,600 raw, **+106,710 deflated**), plus `resources.arsc` +19,924 B (the strings for furniture, the Pro template benefit, and `settings_scan3d_lab`). That is templates (`engine/furniture`), the `app/furniture` Compose flow, `GatedGalleryStore` and `FloorEstimator`. **The lab adds 0 B to release** (dex check above), and R8 also dropped `DepthCaptureListener` and `ShapeScanPipeline`. **arm64 download estimate:** M4's CI figure of 10.37 MiB plus about 0.12 MiB ≈ **10.5 MiB**, about 4.5 MiB under target. ARCH §6.3 estimated +30–50 KB for templates; the flow UI is the rest. **The real number** comes from the bundletool gate at the next CI run (R3). |
| **Per item ≤ 0.5 MB** | **Met** | **Templates:** the sofa GLB is < 16,000 B (asserted); ≤ 40 parts at about 1 KB each is ≤ about 40 KB worst case (estimate); no photo. **Meshes:** the `saveMesh` byte check is unchanged (≤ 500,000 B GLB, `commit` on exact bytes). The lab adds the 12k retry, and the M4 end-to-end mesh is 304,380 B. **Lab artefacts** (`files/scan3d_lab/`: CSV, a JSON per run, `mesh.glb` ≤ about 0.4 MB per run) live only in the debug app. |
| **Recon memory** | **JVM bound unchanged** (18.9 MB) | The phone number is the spike's output (§4). |
| **Lint** | **Unchanged** | App 0 errors / 6 warnings, as at M4; ar and data clean. |

---

## 6. Conditions

| # | Owner | What | When | Check |
|---|---|---|---|---|
| **R1** | Builder A | **G1:** `MAX_AR_P95_MS = 1000.0 / 30 + 0.5` and `JANK_NS = 33_833_333L`, plus a `LabMetricsTest` case (p95 33.333 gives true, 50.0 gives false). **Optional, same pass (append-only):** G3's `pss_settle_ms` and `pss_settled`, G4's `camera_fps_min` and `camera_fps_max` plus the `max(…, 30 fps)` clamp, and G5's pinned header and `"schema": 2`. | **Before the spike APK is built** | The test is green, and the debug dex contains the new constant. |
| **R2** | Orchestrator | **ARCH §7.1.10:** record the build policy and the §4 table (what debug can conclude, and when the `lab` build is required), plus the analysis rules from G3 and G4. **Doc drift (G6):** fix §7.1.6 (2 pooled slots, `trySend`, backlog), §4.3 (`Allowance.Only`), and §6.2 (Home row plus the empty-gallery entry; the FAB is deferred). **Checklist:** merge §7 below into `docs/TESTING.md` as **§19**. Then run `python docs/build_html.py`. | Before commit | Doc diff. |
| **R3** | Orchestrator | **Commit and CI:** commit, then get a green CI run (tests, lint, the size gate with the real arm64 figure, 16 KB, and SpaceViewMigrationTest). Add or keep a CI step asserting the **release** dex has 0 `scan3d_lab` / `Scan3dLab`. **APK:** build the debug APK from a clean worktree at that commit and save it as `builds/SpaceView-TD0-debug.apk`. **Record:** put its SHA-256 and the commit in HANDOFF. Every debug build reports `app_version` = `0.1.0-debug`, so the hash and date are what tie CSV rows to a build. | Before the APK goes to the user | CI log; the HANDOFF line. |

---

## 7. Device checklist for `docs/TESTING.md` §19 (T templates + the 3D Scan Lab spike)

**T: Furniture templates** (release or debug app; Free first, then Pro through FakeBilling on debug or a licence tester on release)

1. **Entry:**
   - Home → **Furniture** opens the picker.
   - An **empty** gallery shows "Add furniture".
   - A non-empty gallery has no furniture entry (known, N-c).
2. **Free versus Pro templates:**
   - On Free, **sofa, bed and wardrobe** start.
   - **L-sofa, dining table, dining chair, TV unit, bookshelf and study table** open the paywall **before** any camera.
   - On Pro, all nine start.
3. **Guided measure:**
   - Each W/D/H **Measure** opens a one-shot AR measure and returns its value with ±.
   - **Type** enters an exact value with no ±.
   - Untouched defaults show "typical".
   - Save stays disabled until W, H and D are each measured or typed.
   - Invalid values (seat above the height, for example) show an inline error.
4. **Preview:**
   - The 3D preview follows the options: seats, doors, shelves, chaise side.
   - The L-sofa's left and right are mirror images.
   - The overall size matches the entered W×H×D.
5. **Review and Save:**
   - Review shows the "overall box" note. Save → Saved.
   - Placing the item stands it on the floor with a W×D footprint.
   - Next to the real sofa or bed, the outline matches within the item's ±.
6. **Verdict:** an L-sofa against a measured gap says "Checked against the overall box (width × depth × height)".
7. **Gallery cap:**
   - On Free with 20 items, the picker opens the paywall before AR.
   - At 19 items, start a template, then make the account lapse (FakeBilling) before Save. Save shows the needs-Pro card and keeps the draft. After upgrading, Save works.
8. **Process death** (Developer options → "Don't keep activities"):
   - Background the app mid-measure, at Review and at Saved.
   - Step, values (with ±), options, preset, palette and name are restored. Saved does not save twice.
9. **Stability:** a saved template looks identical after an app restart and after placing it twice.
10. **Lapse never hides data (ADR-005 rule 2):** save an L-sofa as Pro, then lapse. It still opens, places, shares and deletes.
11. **Ads:**
    - Save → Saved → Done: at most one ad, and only on a non-AR screen.
    - Saved → Place → Back: no ad.
    - Never an ad over the camera.
12. **Hindi, TalkBack and 200% font:**
    - The picker, guided measure and Review are in Hindi with nothing truncated.
    - TalkBack reads each parameter row, with its value and source.
    - Targets are ≥ 48 dp.

**3D Scan Lab spike** (debug APK `builds/SpaceView-TD0-debug.apk` from R3: package `com.spaceview.app.debug`, which installs **beside** the release app; depth phone)

13. **Setup:**
    - Install the APK, then open Settings → the debug panel → **3D Scan Lab**.
    - Depth must show a source (RAW, HARDWARE or SMOOTHED). Note which.
    - Start each block cool, in good even light.
    - Tape the carton (about 60×40×40 cm) and type its W/H/D on the intro screen.
14. **Carton at about 0.7 m, 5 scans** (label `carton-0.7m`):
    - Mark the box: 3 base corners, then the top.
    - Tap Start, walk slowly all the way round for 45–90 s, then Finish.
    - Record `c1_mesh_within_pm_of_box` and `mesh_minus_tape_*`.
15. **Carton at about 1.5 m, 5 scans** (label `carton-1.5m`), with the same tape values (R2).
16. **Armchair, 5 scans,** and **a 2–3-seat sofa about 5 cm from a wall, 5 scans,** each taped. Check:
    - there is no wall patch in the mesh viewer;
    - D is not inflated;
    - `c4_processing_le_5s`.
17. **One full 90 s scan from a cool start:**
    - `stop_reason = TIME_CAP`;
    - record `thermal_max` and `c3_no_severe`;
    - record `peak_extra_pss_mb`.
18. **A second phone,** ideally a smoothed-depth-only one: repeat items 14 and 15.
19. **Harness edge cases:**
    - **Back during "Starting…":** the next run's numbers are clean (`ar_frames` is about 90 s worth, not doubled).
    - **Start while the phone is hot:** "too warm" is shown, with no dead capture.
    - **App switch mid-capture:** the capture stops with `SESSION_PAUSED` and processes what it captured.
20. **Save one sofa scan from the lab:**
    - the item is ≤ 0.5 MB;
    - it places on the floor at the measured footprint (bottom about 1 cm up);
    - it shows in the **debug** app's gallery only.
21. **Share all results:** send `spaceview-scan3d-runs.csv`, plus 2 or 3 `metrics.json` files and GLBs.
22. **Glossy floor (G2 and N1)** (release app, Place):
    - On a polished vitrified or marble floor, do the plain-wall fallback twice: once with a coffee table in view, once without.
    - Note any wrongly rejected wall–floor taps and the centre-height readout.

**How to read the CSV** (orchestrator or FRIDAY; this goes into ARCH §7.1.10 under R2):
- **Valid run:**
  - `stop_reason` is `USER` or `TIME_CAP` (a `THERMAL_SEVERE` run counts for criterion 3 only);
  - `capture_s` ≥ 45;
  - `glb_error` is empty.
- **Criterion 1:** a set passes if 4 of 5 valid runs at one distance have `c1_mesh_within_pm_of_box = true`.
- **R2:** plot `mesh_minus_tape_*` against `mean_range_m`. If the error grows with range beyond the ±, add the per-`DepthSource` range term.
- **Debug versus `lab`:** apply the §4 table.
- **Camera-side drops (G4):** a `FRAME_TIME` event with a healthy `ar_frame_ms_p95` and `camera_frame_ms_p95` ≈ 66 ms is a light problem, not app load.
- **Unsettled baseline (G3):** if the JSON's `noise` stage `pss_mb` is below the processing baseline − 1 MB, ignore that run's c3.
- **Intrinsics before depth:** `aspect_mismatch = true` combined with a c1 failure means check intrinsics before blaming depth.

---

## 8. Recommendations

### D step 2: user-facing screens (only after the spike passes items 1–4 on at least one phone, or after the "ToF and raw only" / Beta decision)

1. **Reuse, don't copy.** `ShapeScanPipeline`, `DepthCaptureListener` and `ShapeScanGovernor` are already in `:ar` main; build `app/scan3d` (main source set) on them. **Never copy `LabViewModel.save`** (MINOR 9).
2. **One gated store (D4):** add `saveMesh` to `GatedGalleryStore`. Check `checkDeviceScan()` at scan entry **before AR** and again at Save, and keep the draft on `NeedsPro`. Record the completed scan for ads (K2), and add the D case to `MeasureVisitTrackerTest` (D7).
3. **Budget (D3):** on a byte refusal, retry at 12k triangles, then offer **"Save as box"** from the kept box numbers. Never surface a raw `IllegalArgumentException`.
4. **Pause and process death (D8, §7.1.6):** no TSDF checkpoint.
   - On resume, offer "Finish with what was captured" (≥ 50% coverage) or "Start again".
   - After process death in capture, show "The scan was interrupted", with **Scan again** and **Save as box instead**.
   - After process death in Review, restore the draft GLB and the crop values.
   - Cover each path with JVM tests on the view model. The lab's MINOR 5 and 8 fixes have no tests; the D view model must.
5. **Spec items the harness skipped on purpose:**
   - "Colour integration is dropped first, before the rate" (§7.1.6). The lab always fuses colour, so its timings are conservative.
   - `onTrimMemory(RUNNING_CRITICAL)` → stop and offer Finish.
   - The typed `ShapeScanError` set, with the no-depth fallback to the box scan.
   - The 256 px WebP keyframe thumbnail (the lab saves none).
6. **Apply the spike results:**
   - If R2 shows range-dependent error, add `rangeBiasRel · r̄` per `DepthSource` before the mesh ± is shown.
   - Fix G4's clamp in the governor if R1 did not.
   - Tune `N_MAX` and the rates only from **lab-build** timing.
7. **Labels and gating (D9, D5):**
   - Use "Approximate 3D shape · measured size" everywhere.
   - Ship as **Beta** until the bench holds on real depth.
   - Flip `DEVICE_SCAN` to `shipped = true` in the same change that adds the gated route.
   - Get the user's answer on "3 saved" versus "3 lifetime", and state it on the paywall.
8. **Keep the lab** as a debug-only regression harness. Keep the CI release-dex check (R3).
9. **Hindi, TalkBack, 200% font** on every D screen. No ads anywhere in the D flow until Saved.

### T follow-ups (small, next fix pass)

- **T-1:** in `TemplateGoldenTest`, also pin each id's **static** `ParamSpec` list (id, kind, min, max). A tightened optional range would then fail even when the 39 sampled maps don't hit it.
- **T-2:** decide on the Gallery furniture entry (N-c): add the FAB per §6.2, or amend the spec (R2 records the deviation).

### Phase 3: release candidate → Play internal track

1. **Release gate (§6.2):** every BLOCKER and MAJOR is closed and verified in code, and CI is green on the RC commit. That covers tests, lint, Kover, the bundletool size gate (≤ 18 MiB; target ≤ 15 MB arm64), 16 KB alignment, and SpaceViewMigrationTest 3/3.
2. **Schema:** v3 is closed (M4 shipped). Any schema change is v4, with a migration test from v1, v2 and v3, and an on-device install over M4.
3. **Device passes:** TESTING §16–§19 end to end on one low-end and one mid-range phone, from a release-signed build installed **over** the previous one.
4. **If D ships in the RC:** re-measure criteria 2 and 4 on **that release build**, never the debug or lab numbers. Run the 30 fps profile (`gfxinfo`, Perfetto; N5 GC check) on Place and on capture.
5. **Billing and policy:**
   - Real Play Billing with licence testers (TESTING §17).
   - The Data safety form and the privacy policy match the app's behaviour (AdMob, UMP, no uploads).
   - The Firelog decision (P1-10).
6. **Accessibility:** TalkBack, 200% font and contrast across Measure, Scan, Place, Furniture and Pro.
7. **Lint and polish (M6):** the 6 app lint warnings (PluralsCandidate and UnusedResources from N7), the SceneView skybox and material strip (about 4 MB), and the EmojiCompat initializer.
8. **Hand-off artefacts:**
   - An AAB from `bundleRelease` only, never `lab`.
   - Upload the R8 mapping, and keep the previous AAB.
   - The known-limitations list: L-sofa verdict on the overall box, an approximate 3D shape, depth-capable phones only, and the offline grace bound.
9. **Track:** internal track first. A personal Play account then needs a closed test with **12 testers for 14 days** before production (HANDOFF decision). Plan staged rollout and rollback.

---

## FRIDAY report

**Decision:** APPROVED WITH WARNINGS (= **PASS WITH CONDITIONS** R1–R3). T is approved. D step 1 is approved for the spike once R1 is in the spike APK.

**Evidence:**
- **Orchestrator build, 2026-11-17, EXIT 0:** engine 282/0, ar 129/0, data 65/0, app 286/0; `koverVerify`; assemble debug and release; lint app 0E/6W, ar and data clean; release universal 20,124,777 B.
- **My checks:**
  - result-XML totals (they agree);
  - source freshness against the results;
  - dex string checks: fixes present in the debug APK, lab absent from release;
  - APK entry diff against `builds/SpaceView-M4-release.apk`;
  - CSV column count (104 original + 8 appended).
- **Code read** at the `file:line` references above.

**Findings:**
- **From the review:** B1 FIXED, M2 FIXED, MINORs 3–9 FIXED (5 and 8 code-verified only, with no test).
- **New:** MAJOR 1 (G1, pre-existing in the harness and missed by the review), MINOR 3 (G2 a regression of MINOR 3's fix, G3, G4), NIT 1 (G5), DOC 1 (G6).

**Conditions:**
- **R1:** Builder A, before the spike APK.
- **R2:** orchestrator, docs, before commit.
- **R3:** orchestrator, CI plus a clean-worktree debug APK, before the user installs it.

**Accepted warnings:**
- debug-build timing is not conclusive for a FAIL (§4);
- G2, G3 and G4 are handled by analysis rules until fixed.

**Not verified:**
- **No Gradle run** by me.
- **No device.** Every §7 item is open, and so are all the real-world behaviours: Choreographer cadence under SceneView, ARCore's `fpsRange` values (G4), how long camera and Filament teardown takes before PSS settles (G3), and whether glossy floors produce point-qualified bins without a plane (G2).
- The real arm64 download (bundletool, R3).
- `FurnitureScreens` / `FurnitureVisuals` / `FurnitureLabels` beyond the review's reading.
- The `PaywallScreen` copy for the template benefit.
- Whether the golden hashes predate the constants refactor (untracked files; moot, since T has not shipped).

**Debt recorded:**
- the lab's direct save path (debug only, MINOR 9 KDoc);
- `GatedGalleryStore` is template-only until D step 2;
- the Robolectric migration test is skipped on Windows (CI covers it);
- the entitlement cache is not encrypted.

**Next steps:**
1. R1.
2. R2.
3. R3 (commit, CI, `SpaceView-TD0-debug.apk`).
4. The user runs §19 items 13–21 (the spike) and items 1–12 (T).
5. FRIDAY reads the CSV under the §4 rules. On a timing FAIL, build the `lab` type and re-run those items.
6. D step 2 only after the spike decision.
