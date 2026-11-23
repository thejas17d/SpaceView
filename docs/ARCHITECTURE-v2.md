# SpaceView: Architecture v2 (M4, Smart Measure, Templates, Pro, 3D scan)

FRIDAY (Chief Engineering Intelligence), 2026-11-05. Workflow phases 1–2: **Discovery and Design**. No production code was written or changed.

**Status: PROPOSED.** Every ADR below is `proposed` until the user decides on §13.

**Inputs read for this document:**
- **Handoff and plan:** `HANDOFF.md`, `docs/PLAN.md`.
- **Gate review:** `docs/reviews/M3-gate.md` (§7 has the M4 recommendations).
- **Research:** `docs/research/{measurement-engine,ui-ux,business,android-performance}.md`.
- **Code:** the source listed under "How this was produced".
- **Monetisation research:** `docs/research/pro-monetization.md` landed during this work, and its decisions are folded into §4.3 and §13.
- **3D capture research:** `docs/research/3d-capture.md` landed during this work. §7.1 (on-device: a pure-Kotlin TSDF at 1 cm voxels) and §8 (cloud: self-hosted COLMAP with ARCore pose priors on Modal L4 + R2, about $0.45 per scan) are aligned with it.
- **Scope change (orchestrator, 2026-11-05):** the on-device rough 3D scan is **in this sprint**. It has a full design in §7.1.

**How this was produced.** I read the following code at `HEAD = dcda24b`:
- **App shell:** `AppContainer`, `MainActivity`, `SpaceViewApplication`, `nav/AppNavHost`.
- **Ads:** every file in `ads/`.
- **Settings:** `SettingsRepository`.
- **AR:** the `MeasureScreen.ArCameraView` host, plus the structure and lifecycle of `MeasureSession` (lines 1–450 and 620–645) and the API surface of every other `:ar` file.
- **Data:** `SpaceViewDatabase`, the gallery entity, DAO and `ScanDraft`, and the `GalleryRepository` save, edit and clean-up paths.
- **Engine:** `FitVerdict`, `GlbWriter` (header and material JSON), and `Shapes.rectangleFromRays`.
- **Build and CI:** the manifest, backup rules, the Gradle files and the version catalog, the CI workflow, the dependency baseline and the permission allowlist.

I also checked the Play Billing release notes and the ML Kit image-labelling page (sources in §14). I did not run Gradle or any test, as the brief asked.

---

## Contents
1. [Current architecture assessment](#1-current-architecture-assessment)
2. [Cross-cutting seams this design introduces](#2-cross-cutting-seams)
3. [Item 1: M4 Place in room](#3-item-1-m4-place-in-room)
4. [Item 4: Pro tier (billing, entitlements, gating, ads)](#4-item-4-pro-tier)
5. [Item 2: Smart Measure](#5-item-2-smart-measure)
6. [Item 3: Furniture templates](#6-item-3-furniture-templates)
7. [Item 5 (client): on-device rough 3D scan (this sprint) and cloud scan](#7-item-5-client-side)
8. [Cloud backend sketch](#8-cloud-backend-sketch)
9. [Persistence roadmap (Room v3+)](#9-persistence-roadmap)
10. [Performance and size roll-up](#10-performance-and-size-roll-up)
11. [ADRs](#11-adrs)
12. [Delegation plan and milestone order](#12-delegation-plan-and-milestone-order)
13. [Decisions the user must make](#13-decisions-the-user-must-make)
14. [Not checked / sources](#14-not-checked-and-sources)

---

## 1. Current architecture assessment

### 1.1 Map (as built)

```
:engine (pure JVM)  maths, uncertainty, FitVerdict, GlbWriter, Homography, Shapes      163 tests, Kover 99%
   ^ api
:ar (Android lib)   MeasureSession (1000 lines) + gates/composers/readers; api(arcore, arsceneview)   83 tests
   ^                                                                                  
:data (Android lib) Room v2 (measurements, gallery_items), GalleryRepository, DataStore(measure)    48 tests
   ^
:app                Compose UI, nav, manual DI (AppContainer + 4 process singletons), AdMob+UMP      97 tests
```

**Strengths worth keeping:**
- **Dependency direction is clean and acyclic.**
- **The maths is pure JVM and heavily tested.**
- **The file + row consistency protocol is textbook (§4.G).** New file name, then one `UPDATE`, then delete the old file, then sweep. See `GalleryRepository.kt:244-288` and `:337-375`.
- **The AR threading rules are written down and followed.** Native reads happen on main; maths runs on one worker; a generation counter drops stale results. See `MeasureSession.kt:145-155`.
- **Ad placement is policy-tested.**

None of this needs re-architecting. v2 adds seams; it does not rewrite.

### 1.2 The five most important engineering risks

Severity is for the next milestones (M4 and Pro), and each risk has a concrete failing scenario (FRIDAY §1.7).

| # | Risk | Severity | Evidence | Scenario |
|---|---|---|---|---|
| **R1** | **The Fit verdict would turn an unvalidated ± into a buy/don't-buy answer, and the code and the gate disagree on how to combine uncertainties.** | **MAJOR** (accuracy is priority #1) | `FitVerdict.kt:117-126` combines in quadrature, σc = √(σs² + σi²). M3 gate `M3-gate.md:200` recommends the linear sum ("the honest default"). The Gallery stores **bound95 rounded up** (`GalleryItemEntity.kt` `*_bound95_m`), while `FitVerdict` takes σ (`Measurement`). The ± model has never been checked against ground truth on a device: `HANDOFF.md:68` says it is "waiting on user's bench-test results", and the constants are untuned. | A sofa measured at 200 ± 1.5 cm (bound95) and a gap measured at 203 ± 2 cm. RSS gives a combined 95% band of 2.5 cm, so the clearance of 3 cm reads **FITS**. The linear sum gives 3.5 cm, so it reads **TIGHT**. If the bench test then shows the ± under-covers (it did once: M0-M1 review, 32% coverage on small objects), "Fits" is wrong in a way that costs the user money. |
| **R2** | **The migration safety net has never run anywhere, and v3, v4 and v5 are coming.** | **MAJOR** (data integrity) | `SpaceViewDatabase.kt:16-17` claims "every migration … is exercised by SpaceViewMigrationTest … before it ships". `data/build.gradle.kts:45-46` says the test is "compiled on CI". The CI unit/lint step (`android.yml:54-60`) has **no** `compileDebugAndroidTest*`, `connected*` or managed-device task. `M3-gate.md:104` says the test "has never run on a device". The v1→v2 migration is already on the user's phone (M3 APK). | Templates add v3 and cloud adds v4. A typo in a `CREATE TABLE` string, or a column that is `NOT NULL` without a `DEFAULT`, ships. Room throws `IllegalStateException: Migration didn't properly handle` on first open. Because there is no destructive fallback (correct), History and Gallery are unreachable and the app crashes on Home. That is the crash-loop class that tanks Android vitals. |
| **R3** | **The AR frame pipeline is one 1000-line class bound to the only AR host, all native work runs on the main thread, and 30 fps has never been measured.** M4 adds GPU model rendering and Smart Measure adds per-frame CPU image analysis on top. | **MAJOR** (perf budget and change risk) | **The class:** `MeasureSession.kt` is 1000 lines (FRIDAY §3.2 smell threshold). **The host:** `ArCameraView(session: MeasureSession, …)` (`MeasureScreen.kt:218`) takes only a `MeasureSession`. **Main-thread work:** `onFrame` (`MeasureSession.kt:349-441`) does the hit-test, point cloud, depth, anchor reads and front capture on main. **Per-frame allocations:** `cloudBuffer.copyOf` (`:383`), a snapshot list (`:407`) and a `FrameSample` (`:408`). **The cost of image work:** the one-off NV21 copy already costs an "estimated 5–20 ms hitch" (`M3-gate.md:145`). **No measurement:** the 30 fps row says "Device-only (M4 gate) n/a" (`M3-gate.md:146`). | Smart Measure copies a 1280 px NV21 frame on main 5 times a second. That is 25–100 ms of main-thread work every second, so frames drop below 30 fps and tracking degrades. The other failure is that M4 adds placement as a fourth responsibility inside `MeasureSession` and lifts it to around 1400 lines. The next fix then regresses Measure, which was M2's most-reviewed path. |
| **R4** | **There is no entitlement seam.** Ads start unconditionally, the ads singleton sits outside DI, and backups would carry a cached entitlement to another device. | **MAJOR for the Pro milestone** (not a bug today) | **Unconditional start:** `MainActivity.kt:28` calls `AdsManager.get(this).startAfterFirstFrame(this)` unconditionally, which runs UMP and then `MobileAds.initialize` (`AdsManager.kt:99-186`). **Outside DI:** `AdsManager` is a companion singleton (`:383-392`), reached from 10 call sites, while `AppContainer.kt` does not own it. **Backups:** `data_extraction_rules.xml:5,10` back up **all** of `datastore/` (only `spaceview_measure` is excluded), and `allowBackup="true"` (`AndroidManifest.xml:52`). | (a) A Pro user still gets the UMP consent form and the ads SDK init (a cold-start cost and a privacy surface) because nothing asks "is this user Pro?" before line 28. (b) If the entitlement cache is put in DataStore under `datastore/` with no rule change, a device-transfer restore grants Pro on a second phone without a purchase until the next Play query. |
| **R5** | **A placed gallery model will probably render dark or off-colour.** The GLB is lit PBR, the AR host has no lights, and an M6 task would strip the environment. | **MAJOR for M4** (likely; device-unverified) | **Lit material:** `GlbWriter.kt:155-161` writes `pbrMetallicRoughness` (lit) materials for the photo face and the sides. **No lights:** the only AR host passes `mainLightNode = null, fillLightNode = null` (`MeasureScreen.kt:228-229`). **Environment removed:** `HANDOFF.md:50` plans "M6: strip SceneView skybox/unused materials (~4 MB)", which can remove the indirect light. **Mipmaps:** the texture sampler asks for mipmaps (`minFilter 9987`, `GlbWriter.kt:164`). | A user places a scanned café frame on their wall. The photo face is shaded by a nonexistent light, so it looks near-black or muddy and doesn't match the Review photo. The user concludes the scan "failed". The fix is cheap if decided now (ADR-003). Done after M6, it means regenerating every user's models. |

### 1.3 Other observations (not top-5; carried into the plan)

| # | Observation | Evidence | Handled in |
|---|---|---|---|
| O1 | `arcoreVersion()` makes a Binder call on main at every save (M3 gate N2, still open) | `AppContainer.kt:32-34` | M4 carry-over (Builder B, part of the AppContainer change) |
| O2 | The repo is **public**, so every client-side check (licence verification, gating) is readable and patchable | `HANDOFF.md:22` | ADR-004 threat model |
| O3 | The manifest strips `FOREGROUND_SERVICE` on purpose. A long cloud upload may need it back, with a type. | `AndroidManifest.xml:31-33` | §7.3 |
| O4 | There are no Compose UI tests anywhere: all 97 app tests are JVM logic. Paywall and gate states need UI tests. | `app/src/test` only, no `androidTest` in `:app` | §4.8, §12 |
| O5 | CI actions are pinned by tag, not SHA (FRIDAY §4.K) | `android.yml:39-46` | B, P1 CI task |
| O6 | `work-runtime` is 2.7.0, pulled in transitively by ads. Cloud upload wants the catalog's 2.11.2, so the upgrade changes the ads SDK's transitive version. | `ci/release-dependencies.txt`, `libs.versions.toml` `work = "2.11.2"` | §7.3; one dependency per change |

---

## 2. Cross-cutting seams

These four seams are the whole architectural change. Everything in §3–§8 hangs off them.

### S1. `ArFrameListener` fan-out (fixes the R3 coupling without rewriting `MeasureSession`)

- **Interface.** A new interface in `:ar` with the SceneView callbacks `MeasureSession` already implements: `onSessionCreated`, `onSessionResumed`, `onSessionPaused`, `onFrame(session, frame)`, `configureSession` and `selectCameraConfig`. It also adds `requirements: ArRequirements(depth, lights, planeRenderer)`.
- **`ArSessionHost`** (in `:app/common/ar`, which replaces `ArCameraView`) takes a `List<ArFrameListener>` and calls each one on the main thread, in order.
- **Configuration.** `configureSession` and `selectCameraConfig` come from **one** primary listener: whichever owns accuracy, which is `MeasureSession` when present.
- **No source change to `MeasureSession`'s logic.** It implements the interface; the existing methods already match.
- **Rule from now on:** new AR features are separate listeners or collaborators (`PlacementController`, `RectangleFinder`, `DwellTrigger`, `DepthBoxEstimator`), never new sections of `MeasureSession`. That file is frozen at about 1000 lines. Further growth is a review finding.

### S2. `FrameImageTap`: a bounded, allocation-free CPU-image feed

- **Owner and inputs.** It lives in `:ar` and is owned by the host. Listeners declare `wantsImage(rateHz, longSidePx)`.
- **Main-thread work.** On main, at most every `1/rateHz`, it runs `acquireCameraImage` → copies **only the Y plane, decimated during the copy** into one of 2 preallocated `ByteArray`s → `close()`.
  - At 320×240 that is 77 KB, an estimated 0.5–1 ms.
  - Optional small **full-resolution windows** (for example 4 × 48×48 around corner hints) are copied into a preallocated buffer.
- **Hand-off.** The buffers go to the listener's worker. A buffer is reused only after the worker releases it (2 pooled slots; `trySend`; if both are busy the frame is dropped and counted as backlog, which also steps the governor down).
- **Fixes R3 for Smart Measure:** no NV21, no `Bitmap` and no allocation per frame. The only native call is the one ARCore requires on the GL/main thread.
- **`DepthFrameTap`** is the same pattern for the 3D scan: the depth (16-bit), confidence and decimated YUV, with the pose and intrinsics, in two pooled slots. Spec in §7.1.3.

### S3. `Entitlements` + `FeatureGate` (fixes R4)

- **Location:** `:app/pro`.
- **Source of truth:** `EntitlementRepository`, which exposes `StateFlow<Entitlement>`. Billing writes to it, and everyone else reads it.
- **Ads gate:** `AdsManager` becomes an `AppContainer` member and reads the flow **before** it does any UMP or SDK work.
- **Feature gate:** features ask `FeatureGate`, never `BillingClient`.
- **Pro feature list:** data (`ProCatalog`), filled from `pro-monetization.md`.

### S4. Model provenance (`model_kind`) and a versioned GLB contract

- Gallery rows gain a `model_kind`: `PROXY_BOX | TEMPLATE | MESH_DEVICE | MESH_CLOUD`. This lands in v3, with templates (§9).
- Every GLB SpaceView writes carries `extras.{widthM,heightM,depthM,origin}`, which exists today (`GlbWriter.kt:147`), plus a new `extras.glbVersion`.
- Placement trusts **the row's dimensions**, checks them against the GLB extras, and regenerates on a mismatch or an old version (M3 gate §7.1). Cloud meshes are the one exception: the row records the mesh's measured bounding box, and placement never rescales them.

---

## 3. Item 1: M4 Place in room

### 3.1 Problem, constraints, non-goals

- **Problem:** put a gallery item in the user's room at **true scale (locked)**, on the floor, a table or a wall. That includes a wall fallback for plain walls (ui-ux §3.5). Measure the target space live and give **Fits / Tight / Too big** honestly.
- **Constraints:**
  - 30 fps on a mid-range phone
  - no new dependencies
  - no ads on the AR screen, and Place never owes an ad
  - offline
  - EN and HI
- **Non-goals for M4:**
  - persisted room layouts or "room profiles" (later, a Pro candidate)
  - multi-item compare (stretch goal only, if it is free of risk)
  - occlusion
  - real-time shadows beyond a cheap contact shadow
  - clearance callouts to other planes (ui-ux D.4: later)

### 3.2 Options considered

| Option | Complexity | Risk | Test cost | Perf / size | Reversibility |
|---|---|---|---|---|---|
| **A. Extend `MeasureSession`** with placement | Low at first | **High**: R3; regresses Measure | High (an Android-bound class) | Same | Hard |
| **B. Separate `PlacementController` + reuse `MeasureSession` for the space measurement, both fed by the S1 fan-out** | Medium | Low | Pure parts are JVM-testable | Same | Easy |
| C. A second, independent AR screen with its own ARCore session for measuring the gap | Medium | Medium: two sessions mean a session restart, and the world frame (and the item's anchor) is lost | Medium | Restart costs 1–3 s | Easy |

**Decision: B** (ADR-001). The space is measured with the same `MeasureSession` (DISTANCE or RECTANGLE mode, with all its gating, ± and calibration). Its value feeds the verdict, and the placed model shares the world frame.

### 3.3 Module placement and key types

**`:engine`** (pure JVM, Builder A)

```kotlin
// FitVerdict.kt: additive; the default changes only for the new placement path
enum class UncertaintyCombination { LINEAR, RSS }
class FitPolicy(..., val combination: UncertaintyCombination = UncertaintyCombination.RSS) // existing callers unchanged
// Placement constructs FitPolicy(combination = LINEAR) (ADR-002)

// Plane.kt: wall fallback
fun Plane.Companion.verticalThroughFloorLine(a: Vec3, b: Vec3, towards: Vec3, floor: Plane): Plane?  // null if |b−a| < 0.30 m or not on floor
// GlbWriter.kt: front face KHR_materials_unlit, sides lit; extras.glbVersion = 2 (ADR-003)
const val GLB_VERSION = 2
```

**`:ar`** (Builder A)

```kotlin
interface ArFrameListener { /* S1 */ }
class PlacementController(item: PlacementModel) : ArFrameListener {   // main thread only; no worker needed
    val state: StateFlow<PlacementState>          // throttled ≤ 10 Hz, like MeasureState
    fun target(kind: SurfaceTarget)               // FLOOR, TABLE, WALL, WALL_FALLBACK
    fun dropAtReticle(); fun pickUp()
    fun nudge(dxM: Double, dyM: Double)           // ±1 cm screen-space-mapped steps (ui-ux §2.6)
    fun rotate90(); fun twist(deltaRad: Double)   // yaw only; never scale
    fun addFallbackPoint()                        // 2 taps on the wall–floor line
    fun close()
}
sealed interface PlacementState {
    data object Searching : PlacementState
    data class Preview(val pose: PoseSnapshot, val surface: SurfaceTarget) : PlacementState
    data class Placed(val pose: PoseSnapshot, val anchored: Boolean, val surface: SurfaceTarget) : PlacementState
    data class FallbackLine(val pointsTapped: Int) : PlacementState
}
```

- **Pure math stays out of `PlacementController`.** That covers the pose from a hit, wall-snap for `BACK_CENTER` items, the nudge mapping and the fallback plane. It lives in `PlacementMath` (an object in `:ar` with no ARCore types, tested on the JVM; the same pattern as `ScanComposer`).
- **Rendering.** SceneView `ModelNode` under an `AnchorNode`.
  - The model loads via `modelLoader.createModelInstance(file)` on the main thread. Parsing happens on SceneView's loader.
  - Scale and rotation editing are disabled on the node, and scale is set to exactly 1. **No pinch-scale** (ui-ux §2.6).
  - The "1:1 True size" chip is always visible.

**`:data`** (Builder A)

```kotlin
class PlacementModel(val id: Long, val file: File, val widthM: Double, val heightM: Double, val depthM: Double,
                     val origin: BoxOrigin, val kind: ObjectKind, val width: Dimension, val height: Dimension, val depth: Dimension)
suspend fun GalleryRepository.modelForPlacement(id: Long): PlacementModel?
```

- Reads the row, then reads the GLB JSON chunk only. That is about 3 KB; parse `extras`, never the image.
- If the extras disagree with the row by more than 0.1 mm, or `glbVersion < 2`, it regenerates through the **existing** versioned-model path (`nextModelName` → one `UPDATE` → delete the old file, `NonCancellable`, under `writeLock`).
- A missing file goes through `repair`. This is the M3 gate's §7.1 recommendation and the fix for R5 on existing user data.

**`:app/place`** (Builder A, as with `app/measure` and `app/scan` before)
- **Screen and view model:** `PlaceRoute`, `PlaceScreen` (AR) and `PlaceViewModel` (with `SavedStateHandle`).
- **Fit card:** `FitCard` shows the verdict and the limiting axis, worded as "Fits, 6 cm spare", "Tight: confirm with a tape" or "Too big by 4 cm".
- **Snapshot:** `PlaceSnapshot`, a PixelCopy of the `SurfaceView` plus the overlay, written to `cache/share/`. The existing FileProvider root needs no new path.
- **Strings:** `res/values/strings_place.xml` and `values-hi/strings_place.xml` (a separate file, so the builders never collide).

### 3.4 Flow and verdict contract

1. Gallery item → **Place in my room**. The route is `place/{id}`.
2. The surface target comes from the kind and the origin. `BACK_CENTER` (FRAME, TV) means a wall. `BOTTOM_CENTER` means the floor or a table, with a toggle.
3. **Wall:**
   - If no vertical plane appears within 5 s, offer **"Use the wall–floor line instead"**.
   - Two taps on the floor where it meets the wall build the plane (`verticalThroughFloorLine`).
   - The item slides along that plane. A height slider shows the item's centre height, with the "eye level 145 cm" guide as an option.
4. **Measure the space (optional):**
   - A bottom-sheet button "Check the fit" switches the shared `MeasureSession` to DISTANCE (a gap) or RECTANGLE (wall space W×H).
   - The item stays rendered.
   - The space reading arrives with its own ± (calibration applies under the existing scope rules).
5. **Verdict:** `FitVerdict.evaluate(item, space, FitPolicy(combination = LINEAR, rotation = r))`.
   - **Item σ:** `bound95 / 1.96` for MEASURED sides. The stored bound95 is rounded **up**, so this is conservative.
   - **Typed or preset sides:** σ = 0, and the card says "using your typed size" (M3 gate §7.2).
   - **Rotation:** ANY for FRAME (portrait or landscape); NONE for TV; ABOUT_VERTICAL for floor items.
   - **Axes compared:** a DISTANCE space compares only the item's width ("Width fits; height not checked"). A RECTANGLE compares W×H (wall) or W×D (floor).
   - **Fits and Too big** are only ever shown outside the combined band; everything else is **Tight**.
6. **Ads:** Place carries `ArScreenAdGuard`. Nothing is armed on entering or leaving. Saving a space measurement from Place does **not** call `recordCompletedScan`, so Place never owes an ad. That closes M3 gate §7.4 by documenting it (§13 D7).

### 3.5 Threading

- `PlacementController` runs entirely on main: one hit test per frame (shared with `MeasureSession`'s centre hit via S1, so there is no second `frame.hitTest`) plus pose math. No worker is needed.
- Model loading and regeneration: `modelForPlacement` runs on IO, with the CPU part on `GalleryRepository.cpu`. The screen shows a spinner, then loads the node on main.
- `PlacementState` goes into Compose throttled to ≤ 10 Hz (the existing `PUBLISH_INTERVAL_MS` pattern). The per-frame model transform is set on the node, never through Compose state.

### 3.6 Errors, offline, process death

| Case | Behaviour |
|---|---|
| Item deleted while Place is open | `observe(id)` emits null → message "This item was deleted" → back |
| GLB unreadable and regeneration fails | Error card with **Back**. The row is never touched (the regeneration path already guarantees this) |
| Tracking lost | The item stays at its anchor pose and its hidden state follows `anchor.trackingState`. Coaching card as in Measure |
| Session pause (call, app switch) | Anchors are detached in `onSessionPaused` (the M2 M5 rule) → state goes back to `Searching`, with the item still selected. The space measurement's **number** survives (it is a length), shown as "measured before the pause" |
| Process death | `SavedStateHandle`: item id, surface target, yaw, height slider value, and the last space reading (value, σ, mode). It restores to `Searching` with the same settings. Anchors cannot survive, and the UI says so |
| Offline | Fully offline |

### 3.7 Security and privacy (MASVS)

- **Risk class: LOW.** No new IPC, permission or network.
- **Snapshot:** stored under `cache/share/` with the existing pruning (1 day) and shared through the existing narrow FileProvider root. Not backed up, since cache isn't.
- **Snapshot content:** it may show the user's home, so it is only ever shared by explicit user action. No auto-save to MediaStore. Saving to Photos would need MediaStore insert with no permission on API 29+; that is a later decision.

### 3.8 Performance and size

- **Size:** no new dependencies. The engine, `ar` and `app` code is an estimated +40–80 KB of dex.
- **GPU:**
  - One mesh, 24 vertices, one 1024² texture (4 MB plus mips, about 5.3 MB GPU).
  - One directional light plus SceneView's default indirect light, **on the Place host only** (`ArRequirements.lights = true`). Measure and Scan keep no lights.
  - Measure on device. Budget: frame time p95 ≤ 33 ms on the mid-range bench phone, via `dumpsys gfxinfo` or a Perfetto frame timeline on a **release** build (FRIDAY §4.E).
- **Must fix in M4:** the per-frame allocations in `onFrame` (R3, M2 minor 8, N7). Pool `FrameSample`, reuse the snapshot list, and swap double-buffered cloud arrays instead of `copyOf`. M4 is the first milestone with a 30 fps gate, so a baseline is taken **before** the change.

### 3.9 M4 test plan (§4.C)

| Layer | Tests |
|---|---|
| Engine (JVM) | **Fit verdict:** `FitVerdictLinearTest` at all three boundaries (margin = band ± 0.1 mm), a typed side (σ = 0), a frame swapped portrait/landscape (ANY), a floor item swapped W↔D, and `minClearance`. Also the R1 regression: the sofa example in §1.2 gives TIGHT with LINEAR. **Fallback plane:** `PlaneTest.verticalThroughFloorLine`: the normal is horizontal and faces the camera, too short a line is rejected, and points off the floor are rejected. **GLB:** `GlbWriterTest`: `KHR_materials_unlit` is in `extensionsUsed` and on material 0 only, `extras.glbVersion = 2`, the dimensions are unchanged, and the Khronos-validator checks still pass through the `MiniJson` harness. |
| `:ar` (JVM) | `PlacementMathTest`: footprint yaw faces the camera; a BACK_CENTER item sits flush on the wall (back face at distance 0); each nudge step is 1 cm in world space along the surface axes; rotate90 ×4 returns to identity; scale is always 1. `PlacementStateTest`: pause → Searching with the item kept, and resume. **Repeated camera timestamps** (the M3 B1 lesson) don't advance state. |
| `:data` (JVM) | `modelForPlacement`: the extras match → same file; extras mismatch → a new versioned file plus one UPDATE; `glbVersion 1` → regenerated; missing file → repaired. Death between write and UPDATE leaves the old pair (the existing fault-injection harness). |
| `:app` (JVM) | `PlaceViewModel`: `SavedStateHandle` round-trip; verdict copy for each verdict × {measured, typed}. **Ads:** `MeasureVisitTrackerTest` "Place visit never arms" and "saving a space from Place does not count". |
| Device checklist (new `TESTING.md` §17) | 1. **True size:** place a scanned A4 sheet over the real one; the edges must agree within the shown ±. 2. **Plain painted wall:** the fallback works. 3. **Release build, mid-range phone, item placed:** 30 fps. 4. **Lighting:** the photo face matches the Review photo's colour. 5. **Pause and resume.** 6. **Process death** on Place. 7. **Hindi.** 8. **No ad** on entry or exit. 9. **Upgrade:** an M3 item (glbVersion 1) regenerates once and keeps its size. |

---

## 4. Item 4: Pro tier

### 4.1 Problem, constraints, non-goals

- **Scope:**
  - Play Billing subscriptions (monthly and yearly; a trial on yearly per business.md §6)
  - possibly consumable cloud-scan credits
  - entitlements, feature gating, "remove ads", restore, and an offline grace period
- **Constraints:**
  - **No backend today.**
  - India: UPI (Unified Payments Interface) Autopay and **pending** payments are common.
  - Personal Play account.
  - Public repo.
  - Cold start ≤ 800 ms.
- **Non-goals for v1:**
  - server-side verification
  - cross-platform entitlements
  - Pro for Business seats
  - lifetime purchase (§13 D10)
  - **consumables** (they need a backend; see ADR-004)

### 4.2 Billing verification without a backend (ADR-004)

Play's server-side purchase verification (Play Developer API `purchases.subscriptionsv2.get`, plus RTDN, the Real-time Developer Notifications) needs a server holding a service-account credential.

| Option | What it protects | Residual risk | Cost |
|---|---|---|---|
| **A. Client trusts `queryPurchasesAsync` only** | Normal users | A fake-billing-service app (Lucky-Patcher style) can return forged purchases | Zero |
| **B. A + local RSA signature check** of `originalJson` with the app's Play licence public key (`Purchase.getSignature`) | Forged purchase JSON from fake billing services: they can't sign with Google's key | **A patched APK** skips the check (the repo is public, which makes it easy). Refunds and cancellations are only noticed at the next successful query. | ~1 day; ~0 size (`java.security`) |
| C. A minimal verification backend now (Cloud Run + Developer API + RTDN) | Everything server-side, including refunds in near real time | It is a production service with on-call, secrets, cost, privacy policy and an account model, for a ₹99 ad-removal | Weeks; ongoing |

**Decision: B for every entitlement that unlocks only local, zero-marginal-cost features (ad removal, local feature limits).**

- **Hard rule:** anything that costs real money per use (cloud GPU credits) is granted **only by a server** after Developer API verification (C). Consumables therefore do not ship before the §8 backend.
- **Threat model accepted:** someone who patches the APK can also strip the ads and the gates directly. Play Integrity would not stop an offline patched APK either, since there is no server to enforce it. The revenue at risk is small: business.md §6 expects 0.5–2% conversion.
- **Monitor:** compare the Play Console's subscriber count with the in-app "Pro active" analytics event once analytics exists. Today there is none.

### 4.3 Types and module placement (ADR-006: no new Gradle module)

All of this lives in `:app`, in the packages `com.spaceview.app.pro` (domain, pure Kotlin) and `com.spaceview.app.billing` (Play adapter), owned by Builder B.

```kotlin
// ---- pro/ProCatalog.kt  (the ONE file pro-monetization.md decisions edit) ----
enum class ProFeature { NO_ADS, GALLERY_UNLIMITED, EXPORT_GLB, EXPORT_PDF, SMART_RECTANGLE, FURNITURE_TEMPLATES,
                        DEVICE_SCAN, CLOUD_SCAN /* …added by research */ }
data class FeatureRule(val feature: ProFeature, val free: Allowance, val pro: Allowance)
sealed interface Allowance { data object Unlimited : Allowance; data object None : Allowance; data class Count(val max: Int) : Allowance; data class Only(val itemIds: Set<String>) : Allowance /* T: core templates */ }
object ProCatalog {            // values from pro-monetization.md §1 and §8
    const val CATALOG_VERSION = 1
    const val SUBSCRIPTION_ID = "pro"                                   // one subscription, several base plans
    val basePlans = listOf("monthly", "yearly" /* offer trial7 */, "pass30" /* prepaid, no auto-renew */)
    // v1.x, not in this sprint: "studio" subscription, "pro_lifetime" non-consumable, "credits_3"/"credits_10" (server only)
    val rules: List<FeatureRule> = listOf(
        FeatureRule(NO_ADS, free = None, pro = Unlimited),
        FeatureRule(GALLERY_UNLIMITED, free = Count(20), pro = Unlimited),
        FeatureRule(FURNITURE_TEMPLATES, free = Count(3) /* sofa, bed, wardrobe */, pro = Unlimited),
        FeatureRule(DEVICE_SCAN, free = Count(3) /* saved 3D scans, inside the gallery cap */, pro = Unlimited),
        FeatureRule(COMPARE, free = Count(2), pro = Unlimited),
        FeatureRule(EXPORT_GLB, free = None, pro = Unlimited), FeatureRule(EXPORT_PDF, free = None, pro = Unlimited),
        // Smart Measure (rectangles, hands-free, naming), Place and the verdict: FREE. Not in the catalogue on purpose.
    )
    const val OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000   // §13 D3
}

// ---- pro/Entitlement.kt (pure) ----
sealed interface Entitlement {
    data object Unknown : Entitlement                                   // before the cache is read (ms)
    data object Free : Entitlement
    data class Pro(val basePlan: String, val autoRenewing: Boolean,     // pass30 → autoRenewing = false
                   val confirmedAtMillis: Long, val source: Source) : Entitlement   // Source = PLAY_LIVE | CACHE_GRACE
    data class Pending(val basePlan: String) : Entitlement             // UPI/cash pending: Free features + banner
    // PRO_LIFETIME / STUDIO (research §8) are added as Pro variants when those products ship (v1.x)
}
object EntitlementReducer {                                           // pure, the state machine; 100% JVM-tested
    fun onQuery(prev: Cached?, result: QueryResult, now: Long): Cached
    fun effective(cached: Cached?, now: Long): Entitlement            // applies grace; clock-rollback safe
}

// ---- pro/EntitlementRepository.kt ----
class EntitlementRepository(store: EntitlementStore, billing: BillingGateway, clock: () -> Long, io: CoroutineDispatcher) {
    val entitlement: StateFlow<Entitlement>          // Unknown → (cache) → (live query)
    suspend fun refresh(reason: RefreshReason)       // APP_START, FOREGROUND, PAYWALL, RESTORE_BUTTON
    suspend fun purchase(activity: Activity, product: ProductRef): PurchaseOutcome
}
// ---- pro/FeatureGate.kt ----
interface FeatureGate {
    fun observe(feature: ProFeature): Flow<GateState>                 // Allowed | Limited(remaining) | NeedsPro
    suspend fun check(feature: ProFeature, currentUsage: Int = 0): GateState
}

// ---- billing/BillingGateway.kt  (the only place Play types exist) ----
interface BillingGateway {
    suspend fun connect(): BillingAvailability                        // OK | UNAVAILABLE(reason) | ERROR
    suspend fun queryActive(): QueryResult                            // subs + one-time, mapped to our types
    suspend fun productDetails(ids: List<String>): List<ProductOffer> // price strings from Play; never hard-coded
    suspend fun launch(activity: Activity, offer: ProductOffer): LaunchResult
    suspend fun acknowledge(token: String): Boolean
    val updates: Flow<PurchaseUpdate>                                 // PurchasesUpdatedListener, mapped
}
class PlayBillingGateway(...) : BillingGateway   // billing-ktx 9.1.0: enableAutoServiceReconnection(),
                                                 // enablePendingPurchases(PendingPurchasesParams…enableOneTimeProducts()
                                                 //   .enablePrepaidPlans().build())  ← pass30 is a prepaid plan
class FakeBillingGateway : BillingGateway        // in src/test (and a debug-only menu for UI work before Play setup)
object PurchaseVerifier { fun verify(base64PublicKey: String, signedData: String, signature: String): Boolean }
```

**Where this differs from pro-monetization.md §8:**
- **Remote Config.** The research wants Remote-Config-backed limits and a ₹99/₹599 price A/B. Firebase Remote Config means adding the Firebase SDK, `google-services`, start-up initialisation and new Data safety rows, all for limits that change rarely. **For v1 the limits are code** (`ProCatalog`). The A/B can be run with Play base plans plus a deterministic install bucket stored in DataStore. Remote overrides come later, from our own backend (§8) or Firebase if the user prefers (§13 D16).
- **"Billing module".** The research says a `:app` billing *module*. ADR-006 keeps it as packages in `:app`; the responsibilities are the same.

**Library facts this relies on** (from the Billing release notes, §14):
- PBL 8.0 removed `queryPurchaseHistory`, the no-argument `enablePendingPurchases()` and the SKU APIs. It added `enableAutoServiceReconnection()`.
- PBL 8.1 raised the library's minSdk to 23. The app's minSdk is 24, so that's fine.
- PBL 9.0 changed the error for a blocked Play Store to `BILLING_UNAVAILABLE` and targets SDK 35.
- The catalog already pins `billing = "9.1.0"` (`libs.versions.toml`).

### 4.4 Entitlement state machine (the core of "offline grace")

| Event | From | To |
|---|---|---|
| Cold start | — | `Unknown` → a cached Pro (inside grace) is published as soon as the DataStore read finishes (after the first frame); otherwise `Unknown` until Play answers, at most 1 s, then the cache through `effective()`. `settled` turns true only when the first start-up query has finished (Ok or Failed); **ads wait for `settled`** (≤ 12 s), so a stale or missing cache never starts UMP or AdMob for a Pro user (P1 review MAJOR 1) |
| Live query OK, active sub with a valid signature | any | `Pro(source = PLAY_LIVE)`; `confirmedAt = now` is written to the cache |
| Live query OK, purchase `PENDING` | any | `Pending` (no Pro features). The user sees "Payment pending: Pro unlocks when your bank confirms" |
| Live query OK, nothing active (expired, refunded, account hold) | any | `Free` **immediately**. Play keeps returning cancelled subscriptions until they expire, and keeps them during Play's own grace period, so "nothing returned" is authoritative |
| Live query fails (offline, `SERVICE_UNAVAILABLE`, `BILLING_UNAVAILABLE`) | cached Pro | `Pro(CACHE_GRACE)` while `max(now, lastSeen) − confirmedAt ≤ OFFLINE_GRACE_MS`, else `Free`. `lastSeen` (a persisted high-water mark of every clock reading taken while offline) moves forward to `now`, never back |
| Wall clock moved backwards below `lastSeen` (or below `confirmedAt`) by more than 5 minutes | cached Pro | `Free` until Play confirms again. The grace is therefore bounded by 7 days of **app-observed** time: winding the clock back, inside or outside the window, once or repeatedly, does not extend it. A forward jump only shortens it. (Previously "treat `now = confirmedAt`", which let a rollback inside the window extend grace indefinitely: P1 review MAJOR 2.) |
| Live query OK, Pro confirmed | any | `confirmedAt = lastSeen = now`: Play's answer is authoritative, so the grace restarts from the current clock |
| Purchase of another package (`packageName` in the signed JSON is not ours) | any | Ignored, like an invalid signature (P1 review MINOR 4) |
| Signature invalid | any | Ignore that purchase and log a non-PII counter. It **never** grants |
| Purchase not acknowledged | Pro | Acknowledge, retrying on every start until done (Play refunds unacknowledged purchases after 3 days) |

**The persisted `Cached` record:**
- **What it holds:** productId, purchaseToken **hash** (SHA-256, for idempotency; the raw token is never logged), confirmedAt, autoRenewing, acknowledged, `catalogVersion` and `lastSeen` (the clock high-water mark).
- **Where:** its own DataStore file, `spaceview_entitlement`.
- **Backups:** **excluded** from cloud backup and device transfer. `data_extraction_rules.xml` and `backup_rules.xml` gain an `<exclude>`, which fixes R4(b).
- **No encryption:** the record holds no secret and cannot grant anything Play does not confirm within 7 days. Encryption would protect nothing against the root/patch attacker who can already bypass the check. This is a recorded `shortcut:`.

### 4.5 Gating rules (ADR-005)

1. **Gates are checked at entry points and at Save, never mid-AR.** A paywall never opens over the camera (the same principle as ads). It is a non-AR route, `PAYWALL/{feature}`.
2. **A lapse never hides, deletes or locks existing user data.** Take Free with a 15-item gallery cap and a lapsed Pro user with 40 items: all 40 can still be viewed, placed, shared, exported and deleted. Only the **41st save** is gated. The same applies to templates and exports already made.
3. **Accessibility features are never Pro.** Hands-free point drop (§5) stays Free.
4. **Prices** come from `ProductDetails` (localised by Play). They are never hard-coded, and the strings never contain a currency.
5. **Unknown state:** `Unknown` gates as Free but **suppresses ads** until it resolves. A wait of at most 1 s, then Free, keeps a Pro user from ever seeing a first-launch ad flash.

### 4.6 Ads removal (fixes R4(a))

- `AdsManager` moves into `AppContainer` as `val ads: AdsManager by lazy { … }`, and `AdsManager.get(context)` delegates to it. The 10 call sites keep compiling, so this is a seam change, not churn.
- A new constructor parameter, `entitlement: StateFlow<Entitlement>`, plus an `AdsSdk` wrapper interface over the `MobileAds`, `InterstitialAd` and UMP statics, for testability.
- **`startAfterFirstFrame`:** wait for the first non-`Unknown` value (≤ 1 s). If Pro, **skip UMP and `MobileAds.initialize` entirely.**
- **`canShowAds`** = `consentOk && sdkReady && entitlement !is Pro`. `BannerAd` already returns early on `!canShowAds` (`BannerAd.kt:58-59`), so every banner disappears with no per-screen change.
- **Becoming Pro mid-session:** drop the loaded interstitial, cancel `pendingShowJob`, and set `_canShowAds = false`. MobileAds can't be de-initialised, but no further requests go out.
- **Pro lapses mid-session:** the normal start path runs. UMP runs then, since consent must be collected before the first ad request.
- **Settings:** "Privacy options" (UMP) only while ads are active. "Restore purchases" and "Manage subscription" (deep link to `https://play.google.com/store/account/subscriptions?sku=…&package=…`) always.

### 4.7 Threading, errors, process death

- **Threading:**
  - The billing connection starts **after the first frame**, never in `Application.onCreate` (cold start).
  - Refreshes happen on `ProcessLifecycleOwner` ON_START (`lifecycle-process` is already on the classpath, `ci/release-dependencies.txt`), throttled to at most one per 10 minutes, plus on paywall open and on Restore.
  - The reducer is pure. Repository writes run on the injected IO dispatcher. One `Mutex` serialises refresh, purchase and acknowledge, because check-then-grant is a race (§4.I).
- **Errors:** `USER_CANCELED` is silent. `ITEM_ALREADY_OWNED` triggers a restore. `BILLING_UNAVAILABLE` shows "Purchases aren't available on this device (Play Store missing or blocked)". `NETWORK_ERROR` shows a retry with offline copy. `DEVELOPER_ERROR` gets a non-PII log and a generic error.
- **Process death mid-purchase:** the result is lost with the listener. The next start's `queryActive()` finds the purchase, and granting is **idempotent by token hash**. A paywall killed by process death restores to the same feature (`SavedStateHandle`).

### 4.8 Security (MASVS) and Play policy

- **Risk class: HIGH** (payments). The review uses §4.D in full.
- **STORAGE:** no purchase token or order id in logs (release logging is levelled). The entitlement file is excluded from backup (above).
- **CODE:**
  - `PurchaseVerifier` uses only `java.security` (`KeyFactory` RSA, `Signature` SHA1withRSA, the scheme Play uses), with no custom crypto.
  - The public key arrives as a build config field from a Gradle property or CI secret. It is not secret, but this keeps it out of the public repo's history if keys rotate. When the property is missing, release builds fail (`REQUIRE_RELEASE_CONFIG`, `app/build.gradle.kts:30-39`), so nothing fails open.
- **PLATFORM:** Billing merges the `com.android.vending.BILLING` permission, so `ci/allowed-permissions.txt` must be updated. No new exported components.
- **RESILIENCE:** no Play Integrity for v1 local Pro. Without a server it adds nothing enforceable (ADR-004). It becomes mandatory for cloud (§8).
- **PRIVACY:** the Data safety form needs review for purchase history if the app stores or transmits it. It stores a token hash locally only. Confirm at submission.
- **Play policy:**
  - Subscriptions need clear terms on the paywall: price, period, auto-renew, trial end, and how to cancel.
  - Restore must be reachable.
  - A personal account needs a Play **payments profile** before products can be created (a user action).

### 4.9 Performance and size

- **Billing:** billing-ktx 9.1.0 is an estimated +0.2–0.5 MB download. This is **[est.]**; the CI bundletool gate measures it, and the dependency guard baseline must be regenerated.
- **New permission:** `com.android.vending.BILLING`.
- **Cold start:** no impact by design. Verify with a before/after `am start -W` on a release build (3 runs).

### 4.10 Pro test plan

| Layer | Tests |
|---|---|
| Pure (JVM) | `EntitlementReducerTest`: table-driven over every row of §4.4. Grace at 6 d 23 h 59 m (Pro) and 7 d + 1 ms (Free); clock rollback; pending → purchased; refund (absent) → Free; signature fail → no grant. `PurchaseVerifierTest`: vectors signed with a **test** RSA key (valid, one flipped byte, wrong key, empty signature, non-base64). `FeatureGateTest`: the catalogue table, Count limits, and **lapse never hides data** (40 items, Free → all readable, save gated). |
| Coroutines | `EntitlementRepositoryTest` with `FakeBillingGateway`, `runTest` and an injected dispatcher: refresh racing a purchase (one grant only), acknowledgement retry after a simulated kill, `ITEM_ALREADY_OWNED` → restore, the throttle. |
| Ads | `AdsManagerProTest` with `FakeAdsSdk`: Pro at start → zero UMP and zero init calls. Becoming Pro mid-session drops the interstitial and `canShowAds` goes false. Lapse → init once. Unknown → no ad for ≤ 1 s. |
| DataStore | Corrupt entitlement file → `Free` (then a live query restores Pro). Backup XML test: the entitlement file is excluded in both XML files. |
| Compose UI (first in the project; O4) | Paywall: loading, offers, pending, error and offline states. Settings: the restore button states. Found by `testTag`. |
| Device / Play (internal track, licence testers) | Monthly purchase; yearly with trial; **slow test card → pending → approved**; declined card; cancel → Pro until expiry; refund from Play Console → Free on next start; reinstall → restore; airplane mode inside and outside grace; a second Google account on the device → no Pro; Hindi paywall. |

---

## 5. Item 2: Smart Measure

**Four sub-features with very different risk.** I split them into SM1 (low risk), SM2 (high risk) and SM3 (later, depth only).

### 5.1 SM1a: Hands-free point drop (accessibility; Free)

- **Type:** `DwellTrigger`, in `:ar`, pure and JVM-tested. It reuses `FrontCaptureGate`'s steadiness model (`STEADY_MAX_ANGULAR_RAD_S = 0.10`, `STEADY_MAX_LINEAR_M_S = 0.06`, EMA and max-gap; `FrontCaptureGate.kt:154-163`) by extracting it into a shared `SteadinessFilter` with no behaviour change. `FrontCaptureGate` is then refactored to use it, as a separate change.
- **Fire rule:**
  - all of these hold for `holdMs` (default 900 ms, a tunable): tracking OK, steady, the commit candidate `locked` (the existing `CommitCandidate.locked`), and the reticle at least 3 cm from the last committed point
  - **after** firing, the trigger re-arms only once the reticle has moved ≥ 3 cm and settled again, so it can't double-drop
- **It never fires:**
  - in freeze-frame
  - within 1 s of resume
  - while the result is complete
  - on repeated camera timestamps (the M3 B1 lesson: skip `dt ≤ 1e-4` without touching state)
- **Wiring:** a listener next to `MeasureSession` (S1) that calls the existing commit path (see "As built" below for the small session additions).
- **UX:**
  - a progress ring on the reticle
  - the existing click sound on fire
  - a TalkBack live-region announcement ("Point 2 placed")
  - a toggle on the Measure HUD and in Settings, persisted in the `spaceview_measure` DataStore, off by default
- **As built (SM1, Builder A, 2026-11-17).** Deviations from the design above, each deliberate:
  - **On by default.** It answers the user's own complaint ("too manual, hands shiver"), and a tap on "+" itself shakes the phone at the worst moment. Undo, and the HUD switch, are always one tap away.
  - **`HOLD_NS` = 700 ms** (brief: 0.6–0.8 s), counted only while `CommitCandidate.locked`, so aim-to-drop is about 1.2 s (the estimator's ~0.5 s lock + the hold).
  - **Dwell steadiness 0.20 rad/s and 0.10 m/s** (`DwellTrigger`), looser than the front photo's 0.10 / 0.06: the point is already averaged and locked, and a shaky hand must still be able to drop. Both share the extracted `SteadinessFilter`.
  - **Also:** target drift ≤ max(1 cm, 1% of range) or the hold restarts; ≥ 3 cm from every committed point; 1 s cool-down after a drop, an Undo/Clear and a resume; re-arm only after moving 3 cm from the last drop spot (so an undone point is not dropped again on the same spot).
  - **Wiring:** `HandsFreeDropper` (`:ar`, an `ArFrameListener` after the session) calls `MeasureSession.addPointHandsFree()`, the same commit path as "+"; `MeasureSession` gained only small `internal` read-outs (`autoDropTarget`, `distanceToNearestPoint`, …) and `PointAdded.auto`. `app/smart/HandsFree.kt` gives Measure, Scan outline and furniture guided measure the same ring, switch and announcement through `ArCameraView`.
  - **Feedback:** the existing click, the ring and a TalkBack announcement. The **haptic tick is off** (`HAPTIC_TICK_ENABLED = false`, SM1 review MAJOR 3): ARCore's guidance and the research advise against vibration in AR; TESTING §21 item 9 is the experiment that could turn it on.
  - **Never behind hidden UI** (review MAJOR 1): each screen passes `suspended` when its reticle or controls are not shown (calibration picker, freeze, photo step, result card, failed session), and a window without focus (any dialog) suspends it too. Choosing a calibration reference clears the points, and calibration starts with hands-free off. Hold-offs also follow the end of a freeze (re-arm after a 3 cm move), and "+" within 1 s of an auto-drop on the same spot, or of completing a result, is ignored.
  - The HUD switch overrides the Settings value for that screen only.

### 5.2 SM1b: Object naming (ML Kit image labelling, unbundled)

- **Dependency:** `com.google.android.gms:play-services-mlkit-image-labeling`, about **200 KB** app size against **5.7 MB** bundled (Google's figures, §14). Unbundled is the only option within budget.
- **Manifest:** `<meta-data android:name="com.google.mlkit.vision.DEPENDENCIES" android:value="ica"/>`, so Play services fetches the model at install. `ModuleInstallClient` checks availability, and the code handles "not yet downloaded" by giving no suggestion.
- **When it runs:** **once**, on the rectified front photo at Review, on `Dispatchers.Default`. Never per frame and never in AR.
- **What the user sees:** a **suggestion chip** ("Wooden frame?") that the user taps to accept. The model is never auto-named (ui-ux §3.3 B).
- **Language:** the base model has 400+ labels, and the label language is not documented. Assume English.
  - `LabelMapper` maps a curated ~60-label allowlist to `ObjectKind` plus an EN and HI string.
  - Anything outside the allowlist gives **no** suggestion in Hindi, and the English label only in English.
  - This avoids mixed-script UI (§13 D11).
- **Placement:** `:app/scan/naming` (`ObjectNamer` interface, `MlKitObjectNamer`, `FakeObjectNamer`). Builder A owns the scan flow, and Builder B owns the dependency, manifest and baseline changes.
- **Privacy:** inference is on-device. ML Kit's data disclosure (API usage diagnostics sent to Google) must be reflected in the Data safety form, so **verify the current disclosure text at submission**.
- **As built (SM1, Builder A, 2026-11-17):**
  - `play-services-mlkit-image-labeling` **16.0.8** (the latest published). New transitive artifacts: `com.google.mlkit` common, vision-common, vision-interfaces, image-labeling-common, image-labeling-default-common; `firebase-components`/`-annotations`; `odml:image`; `exifinterface`. **No new permission** (checked in every new AAR manifest and the merged release manifest).
  - `MlKitInitProvider` is **removed** from the merged manifest (it would run at every cold start); `MlKitImageLabelSource` calls `MlKitContext.initializeIfNeeded` on first use, off main.
  - Code lives in `app/smart` (`ObjectNamer`, `LabelObjectNamer`, `LabelMapper`, `MlKitObjectNamer`), not `app/scan/naming`. K6 gained an optional `kind` so a frame scan is never offered "Sofa". The curated list is 44 ML Kit labels → 42 names (EN + HI in `strings_smart.xml`); outside it nothing is shown in **any** language.
  - Runs once per straightened photo on `Dispatchers.Default`, 3 s timeout, confidence ≥ 0.70; the chip is a `SuggestionChip` under the name field and applies only on tap.

### 5.3 SM2: Auto-detect flat rectangles (frames, TVs, doors, A4), anchored in 3D

**Options considered:**

| Option | Size | Accuracy fit | Verdict |
|---|---|---|---|
| OpenCV Android | +8–15 MB per ABI [est.] | Good | **Rejected**: breaks the 15 MB budget |
| ML Kit Document Scanner (GmsDocumentScanning) | Small (Play services) | It is a separate full-screen capture UI, not anchored in AR and gives no camera pose | Rejected for AR. Possibly useful for a non-AR "A4 reference" flow later |
| ML Kit Object Detection | Small | Gives axis-aligned boxes, not perspective quads; cannot localise corners | Rejected |
| **Pure Kotlin edge + quad detector in `:engine`, on a decimated Y plane, with full-resolution corner refinement, intersected with the measured plane through the existing `Shapes.rectangleFromRays`** | <60 KB dex [est.] | Corner error is bounded by refinement; the existing ± model already takes `pixelErrorPx` | **Chosen** (ADR-007) |

**Pipeline:**
1. **`FrameImageTap` (S2):** 320×240 Y at 5 Hz, plus 4 × 48×48 full-resolution windows around the previous frame's corners.
2. **`:engine/vision`** (pure, deterministic, JVM-testable on synthetic and recorded images):
   - **Edges:** Gaussian 3×3, then Sobel, then non-maximum suppression with hysteresis (Canny-lite) into a preallocated `ShortArray` and `ByteArray`.
   - **Lines:** line segments from orientation-binned edge chains (LSD-lite), then a merge of collinear pieces.
   - **Quads:** candidate quads from two pairs of lines that are near-parallel in perspective. Scores are edge support along the perimeter, convexity (`Homography.isConvexQuad` exists), area ≥ 3% of the image, and plausible angles. Nested rectangles (frame plus mount) prefer the outer one, with a toggle.
   - **Refinement:** sub-pixel line fit inside the full-resolution windows, so each corner gets a residual in px.
3. **`:ar` `RectangleFinder`** (an `ArFrameListener` with its own single worker):
   - **Rays:** corner pixels go to rays with the existing `ImageProjection` and CPU intrinsics (`CpuImageCamera`), using the pose of **that** frame.
   - **Plane:** the rays are intersected with the plane under the quad centre. That is an ARCore plane, or the existing `LocalSurfaceModel` RANSAC wall (`LocalSurfaceModel.kt:73`), which covers plain walls with a textured object on them.
   - **Measurement:** `Shapes.rectangleFromRays(rays, planeFit, model, localisation = residualPx)` gives W×H with an honest ±.
   - **Temporal gate:** three consecutive detections whose world corners agree within max(1 cm, 1%) before the proposal is shown.
4. **UI:** the outline is drawn snapped, with "Use this rectangle (W × H ± …)".
   - Accepting commits the four corners as anchored endpoints into the **existing** RECTANGLE pipeline (via a new `MeasureSession.commitDetected(corners)`, the only addition there, at around 20 lines). History, Scan and Gallery then work unchanged.
   - Nothing is ever auto-saved.
5. **A4 and card as a calibration reference:** stays behind `CALIBRATION_SMALL_REFERENCES_ENABLED = false` (`MeasureViewModel.kt:164`) until the bench data justifies it. Detection makes the reference *easier* to mark; it does not make the tier floor smaller.

**Risks:**
- glossy TV screens and glass (ARCore hits; research §6.4.6)
- low contrast between a white frame and a white wall
- doors bigger than the field of view (partial detection: skip, never guess)
- `RectangleFinder` worker time is ≤ 15 ms per analysed frame [est.; measure on device]

**Gate:** SM2 ships only if the bench protocol (PLAN §7) shows that auto-detected W×H are **at least as accurate as manual** in ≥ 90% of cases, and the false-positive rate on plain walls is ≤ 1 per minute.

### 5.4 SM3: Auto 3D box from depth (later; depth-capable phones only)

- **Gate:** `ArCapabilities.depthSupported` (`SessionSetup.kt:22`). Otherwise the option is hidden.
- **Flow:** the user taps the object's top. A full depth image at 160×120 is copied into a pooled buffer at 5 Hz for about 2 s, giving points.
- **Fitting:** `engine/OrientedBoxFitter` takes points more than 2 cm above the floor plane, region-grows from the tap, and computes a 2D convex hull on the floor. Rotating calipers give the minimum-area rectangle (W, D), and H is the 95th percentile of height. The ± comes from a depth noise model per sensor (ToF versus depth-from-motion).
- **Badge:** ROUGH unless hardware depth is available.
- **Output:** the same BOX outline the manual flow makes, so Scan and Gallery are unchanged.
- **Status:** still **later** (the user's brief). It reuses the on-device scan's `DepthFrameTap` and `:engine/recon` from this sprint. When it ships, it can replace the manual "mark the box" step of the 3D scan (§7.1.3), but only on depth phones, and the result is labelled ROUGH.

### 5.5 Smart Measure: threading, errors, process death, security, size

| Aspect | Design |
|---|---|
| Threading | Image copy on main (S2, ≤ 1 ms). Detection on the `RectangleFinder` worker (`limitedParallelism(1)`). Proposals reach Compose ≤ 10 Hz. Naming on Default, once. |
| Errors | Detector exceptions are caught per frame and counted. After three in a row the feature disables itself for the session with "Auto-detect is off for now". `CancellationException` is always rethrown. |
| Process death | Proposals are transient. Accepted corners follow the existing Measure rules (anchors are lost and a finished result keeps its numbers). The hands-free toggle is persisted. |
| Offline | All on-device. ML Kit only needs network for the first model download. |
| Security | No new trust boundary. Images never leave the device. Recorded test fixtures are downsampled Y planes of test objects only, with **no people and no home interiors** in the public repo. |
| Size | SM1 adds ~200 KB (ML Kit, Google's figure) plus ~20 KB. SM2 adds <60 KB [est.]. SM3 adds <30 KB [est.]. |

### 5.6 Smart Measure test plan

| Layer | Tests |
|---|---|
| Engine vision (JVM) | **Synthetic renderer** (in test sources) that draws rectangles under random homographies, with noise, blur, low contrast, partial occlusion, nested frames and a TV bezel. Recall ≥ 95% on the clean set, corner error ≤ 1 px at 320 px before refinement and ≤ 0.3 px after (full-res synthetic), and **zero** quads on 200 noise or texture images. **Recorded fixtures:** about 20 Y-plane crops from the bench phone (≤ 60 KB each) as golden regressions. Benchmark printouts are informational only (a JVM is not a device). |
| `:ar` (JVM) | `RectangleFinder` temporal gate (3 agreeing detections; one outlier resets). Rays to W×H ± include the detector residual. `DwellTrigger`: fires after the hold; doesn't fire with motion; no double-fire without moving 3 cm; reset on tracking loss, freeze or pause; **repeated timestamps at 60/120 Hz** neither reset nor advance. `SteadinessFilter` extraction: `FrontCaptureGateTest` passes unchanged. |
| `:app` (JVM) | `LabelMapper` table (allowlist to kind plus EN and HI; outside the list in Hindi gives null). `ObjectNamer` unavailable or not downloaded gives no chip. The hands-free toggle persists. |
| Device checklist | SM1: hands-free on a shaky hold (tune `holdMs`); TalkBack announces. SM2: the bench protocol (A4, door, TV, frame at 1, 2 and 3 m, good and dim light), auto against manual error; false positives on a plain wall for 60 s; fps with detection on (release, mid-range). |

---

## 6. Item 3: Furniture templates

> **Template immutability (added 2026-11-16, T-D0 review MINOR 4):** shipped template geometry is frozen. `TemplateGoldenTest` pins a SHA-256 of each id's parts for 39 parameter cases. A different look or range ships as a new id (`sofa_v2`) with its own constants object; `PartBuilder.box/row` and `cornerLegs` are frozen infrastructure.

### 6.1 Design (ADR-010)

**Problem:** "Will this sofa fit?" needs a recognisable 3D shape at true size, not a box, built from a few guided measurements. The design is parametric and deterministic: no ML and no assets, so APK size stays flat.

**`:engine/furniture`** (pure JVM, Builder A):

```kotlin
data class ParamSpec(val id: String, val min: Double, val max: Double,
                     val default: (Map<String, Double>) -> Double,    // e.g. seatHeight = 0.45·H clamp
                     val measureHint: MeasureHint)                     // DISTANCE | HEIGHT | TYPED_ONLY
data class Part(val name: String, val center: Vec3, val size: Vec3, val yawRad: Double, val material: PartMaterial)
interface FurnitureTemplate {
    val id: String                                   // stored by name: never rename ("sofa_v1", "lsofa_v1", …)
    val params: List<ParamSpec>                      // W, D, H always first and always required
    fun validate(p: Map<String, Double>): List<ParamError>
    fun build(p: Map<String, Double>): List<Part>    // invariant: union bbox == exactly W×H×D
}
object Templates { val all = listOf(Sofa, LSofa, Bed, Wardrobe, Table) }
// GlbWriter: writeParts(parts, palette, origin, name) → one mesh, one primitive per material, glbVersion 2
```

| Template | Required (measured) | Optional, with defaults from ratios |
|---|---|---|
| Sofa | W, D, H | seat height, arm width, back depth, leg height |
| L-sofa | W, D, H, chaise length, chaise depth, side (L/R) | as sofa |
| Bed | W, L, H (frame) | headboard height, mattress thickness |
| Wardrobe | W, D, H | number of doors, plinth height |
| Table | W, D (or diameter), H | top thickness, leg inset, round/rect |

### 6.2 Flow, persistence, verdict

- **Flow:**
  - Home "Furniture" row, or the empty Gallery's "Add furniture" button → pick a template. (A Gallery FAB is deferred.)
  - A guided list of parameters, each with a small line illustration (a vector drawable, no bitmaps) and **Measure** (it opens Measure in DISTANCE or HEIGHT mode as a one-shot that returns its value) or **Type**.
  - A live 3D preview, then Save → Place.
- **Persistence (Room v3, §9):**
  - `model_kind = TEMPLATE`, `template_id`, `template_params` as JSON (kotlinx-serialization-json; the core library is already on the classpath, `ci/release-dependencies.txt`).
  - W, H and D go into the existing columns with their sources: MEASURED with ±, or TYPED.
  - The GLB is generated through the existing save path, with no photo. A template item is about 5–15 KB.
- **Verdict:** uses the overall bounding box. For an L-sofa that is conservative (the footprint is not rectangular), and the card says so.

### 6.3 Errors, process death, security, size, tests

- **Errors:** validation errors appear inline per parameter ("Seat height must be less than the total height"), and Save is disabled until the parameters are valid.
- **Process death:** the parameter map, the template id and the current step are in `SavedStateHandle`. They are plain numbers.
- **Security:** LOW. The JSON params are parsed with defaults and unknown keys are ignored. A corrupt row falls back to a box rather than a crash (the existing `enumOr`/`toDomain` pattern).
- **Size:** code only, an estimated +30–50 KB.
- **Tests:**
  - **Engine:** per template, `validate` boundaries; **bbox == W×H×D to 1e-9** for random valid parameters (property-style, fixed seed); L-sofa mirroring; GLB validity through `MiniJson`; part count.
  - **Data:** v2→v3 `MigrationTestHelper` (CI, §9) and a JSON round-trip.
  - **App:** guided-flow view-model restore.
  - **Device:** place a template sofa next to the real sofa and compare its outline.

---

## 7. Item 5, client side

### 7.1 On-device rough 3D scan (**THIS SPRINT**; scope change from the user, 2026-11-05)

> **Main risk, stated plainly.** Two things are unproven on a device:
> - **Depth quality.** ARCore depth-from-motion on mid-range phones is low-resolution (typically 160×120), noisy at the centimetre level, and has holes and "flying pixels" on edges. It fails on dark, glossy, transparent and textureless surfaces (a black TV, a steel fridge, glass). Expect a **recognisable but blobby** shape on fabric, wood and cartons, and poor results on shiny appliances.
> - **Cost.** The pure-Kotlin CPU budget and the thermal cost of a 45–90 s depth + AR session have never been measured.
>
> **Coverage:** the Depth API is supported on ≈ 76% of listed ARCore models, but only ≈ 48% of Samsung Galaxy A/M/F models (3d-capture.md §7.2; model share, not install share). **Expected accuracy:** mesh extents of about ±2–4 cm on W/D for a sofa in good conditions, biased **large** by depth-edge inflation, and ±5 cm or failures on dark, plain or glossy upholstery (3d-capture.md §7.6; an engineering estimate with no published benchmark). The design therefore:
> 1. **Never takes dimensions from the mesh.** The measured box stays authoritative (§7.1.5).
> 2. **Ships behind an "Experimental: approximate 3D shape" label.**
> 3. **Starts with a device spike that has hard exit criteria** (§7.1.10). If the spike fails, the feature stays behind a flag and the sprint keeps M4, Pro, Smart Measure and templates.

#### 7.1.1 Problem, constraints, non-goals
- **Problem:** turn a free-standing object (sofa, chair, carton, plant, appliance) into a coloured 3D mesh at true scale. It is placeable in M4, stored in the gallery and works offline.
- **Constraints:**
  - no new native libraries (10.07 of 15 MiB)
  - 30 fps AR during capture
  - ≤ 0.5 MB per item
  - mid-range thermal limits
  - honest accuracy
- **Non-goals:**
  - photo-real textures (that is cloud, §7.2)
  - room-scale scans (objects ≤ 2.5 m per side only)
  - wall-hung objects (use the proxy scan)
  - closed bottoms (the base touching the floor is never seen)
  - live mesh preview during capture

#### 7.1.2 Options

| Option | Size | Perf risk | Complexity | Verdict |
|---|---|---|---|---|
| Open3D / ARCore-sample native TSDF (C++) | +2–6 MB per ABI [est.] plus an NDK toolchain, 16 KB alignment and a CMake build on the 2 GB PC | Low CPU | High build cost | **Rejected:** size and toolchain |
| GLES 3.1 compute shaders (`android.opengl.GLES31`, no native library) in a second EGL context beside Filament | 0 | GPU contention with Filament; driver variance | High: no Android Studio, so GPU debugging is blind | **Escalation path only** if the spike shows the CPU can't sustain 3 Hz |
| Filament compute or RenderScript | — | — | Filament exposes no general compute API to Java; RenderScript is deprecated | Rejected |
| **Pure-Kotlin, CPU, bounded dense TSDF with ray-based (Chisel-style) integration on one worker, plus marching cubes, vertex-clustering decimation and vertex colour** | <0.1 MB dex [est.] | Medium: budget below | Medium, all JVM-testable | **Chosen** (ADR-013) |

**Why CPU is plausible.** Integrate only the depth samples that land inside the object's bounded volume, at most 160×120 = 19.2k samples per frame, usually far fewer. Each updates about 2μ/v ≈ 8 voxels along its ray, so roughly 150k voxel updates per integrated frame. At an estimated 20–50 ns each in ART-compiled Kotlin, that is about 3–8 ms per frame on one worker core, at 3–5 Hz. This is **[est.]** and the spike verifies it.

#### 7.1.3 Capture (ARCore Depth API)
- **Gate:**
  - **Support check:** `ArCapabilities.depthSupported` (`SessionSetup.kt:22`, from `session.isDepthModeSupported(AUTOMATIC)`).
  - **Without depth:** the entry point still shows, but opens a sheet: *"3D shape scan needs a phone that supports ARCore Depth. You can still save this object as a measured box with a photo."* The **Scan as box** action goes to the existing proxy scan. The fallback is never a dead end.
  - **Hardware depth (ToF)** phones get a "Best quality" hint.
- **Session:** the shape-scan screen creates its `MeasureSession` with `depthWanted = true`, overriding the user's Measure depth setting for this screen only (the same constructor, `MeasureScreen.kt:77`). The plane renderer is off. No lights.
- **Depth mode** (aligned with 3d-capture.md §7.5):
  - `RAW_DEPTH_ONLY` when `isDepthModeSupported(RAW_DEPTH_ONLY)`, otherwise `AUTOMATIC`. `SessionSetup.configure` gains a `DepthPurpose.SCAN`.
  - Raw depth comes with confidence (0–255, 0 on textureless pixels) and has no smoothing smear at edges.
  - On `AUTOMATIC`-only phones the **smoothed** `acquireDepthImage16Bits()` is used at a 0.5× weight.
  - The source is recorded in `mesh_info`.
- **Sample gate:**
  - confidence ≥ 128 (tune 100–200)
  - depth within **0.35–2.0 m**, since error grows roughly quadratically with distance
  - angular speed < 0.3 rad/s (motion-blur rejection)
- **Copy-out (main thread, `DepthFrameTap`, an extension of S2):**
  - Only when `depth.timestamp` is new. Repeated timestamps are skipped without touching any state (the M3 B1 lesson).
  - Only when tracking is TRACKING and the camera moved ≥ 2 cm or ≥ 3° since the last integrated frame (view diversity, no duplicate work).
  - Only at ≤ the governor's rate (§7.1.6).
  - It copies depth (≈ 38 KB), confidence (≈ 19 KB) and a decimated YUV at 2× the depth resolution, for colour, into one of **two pooled slots**, together with the camera pose and the depth intrinsics (texture intrinsics scaled to the depth resolution).
  - `use {}` closes every `Image`. `NotYetAvailableException` means skip this frame.
  - Budget: ≤ 1.5 ms on main per integrated frame.
- **Flow (one screen, three steps):**
  1. **Box.** Mark the object with the existing **BOX** mode (3 base corners plus top; `MeasureMode.BOX`). This gives the floor or support plane, the oriented bounding box, and the **measured W×H×D with ±**. Later, SM3's auto-box can replace this step.
  2. **Crop.** The box is shown in AR with an adjustable margin (default 5 cm; 0–15 cm) and per-side trims, so a wall or an adjacent object can be excluded *before* capture.
  3. **Capture.** "Walk slowly around the object."
     - A **coverage dome** of 3 rings × 8 azimuth bins lights up as views are integrated (the same dome as the cloud capture, 3d-capture.md §6).
     - **Finish** is enabled after ≥ 20 s **and** ≥ 50% coverage; ≥ 80% is recommended. The target is 45–90 s.
     - **Hard cap 90 s**: capture auto-finishes, with a 10 s warning. The research suggests a 120 s cap. I chose 90 s for thermal margin; it is tunable after the spike.

#### 7.1.4 Fusion, meshing, segmentation, colour (`:engine/recon`, pure JVM)

As built by Builder C (2026-11-06; updated 2026-11-16 for M4 review MINORs 7–10). The code is in `engine/src/main/kotlin/com/spaceview/engine/recon/` and the tests are in the matching test folder (57 tests, 99.7% line coverage). The reviewer and FRIDAY's gate still have to sign off on changes C1–C6 below, including the C5 revision in §7.1.5.

```kotlin
// Supporting types (§7.1.4 names them; recon defines them)
class OrientedBox(val pose: Pose, val width: Double, val height: Double, val depth: Double)
    // local origin = centre of the box BASE on the support plane, +X width, +Y up, +Z depth;
    // occupies [-w/2, w/2] × [0, h] × [-d/2, d/2]. contains(), toLocal(), trimmed(6 trims), local(w, h, d).
class DepthFrame(width, height, depthMm: ShortArray, confidence: ByteArray) {   // pooled slot, refilled per frame
    var intrinsics: CameraIntrinsics   // at depth resolution, sensor orientation
    var cameraToWorld: Pose            // Camera.getPose(), NOT the display-oriented pose (looks along −Z)
    var timestampNs: Long }            // depthMm = mm along the optical axis, 0 = none; smoothed depth: confidence 255
class YuvFrame(width, height, y, u, v)   // planar 4:2:0, full-range BT.601, same field of view as the depth image
enum class DepthSource(sigmaAt1mM, inflationPerSideM, sampleWeight) { RAW, SMOOTHED, HARDWARE }
    // σ at 1 m 1.5 / 2 / 0.5 cm; inflation per side 1 / 2 / 0.5 cm; sample weight 1 / 0.5 / 1
class DepthNoise(source, meanRangeM, observations, voxelM, scaleSigmaRel, absFloor95M)  // DepthNoise.from(volume, source, model)
class Extent3(val width: Measurement, val height: Measurement, val depth: Measurement, val vertexCount: Int)

class VolumeSpec(val boxToWorld: Pose, val minCorner: Vec3, val dims: IntArray, val voxelM: Double, val truncM: Double)
object VolumeSizing {   // voxel = clamp(cbrt(V / N_MAX), 1 cm, 3 cm); V = box + margins + 2 cm below floor; μ = max(4 cm, 3 v)
    const val N_MAX = 1 shl 21            // 2,097,152 voxels
    const val N_MAX_LOW_RAM = 1 shl 20    // memoryClass < 192 MB or isLowRamDevice
    const val MAX_SIDE_M = 2.5
    fun spec(box: OrientedBox, marginM: Double, lowRam: Boolean): VolumeSpec?    // null if any side > MAX_SIDE_M
}
class TsdfVolume(spec: VolumeSpec, params: TsdfParams = TsdfParams()) {
    // dense SoA: ShortArray tsdf (±1), ByteArray weight (bit 7 = colour set), ByteArray rgb(3N)
    fun integrate(depth: DepthFrame, colour: YuvFrame?, sampleWeight: Float)   // ray-based; AABB pre-test; allocation-free
    fun observedFraction(): Double     // + reset(), meanRangeM(), surfaceObservations(), stats
}
object MarchingCubes { fun extract(v: TsdfVolume, minWeight: Int): TriMesh }      // generated table (C4); welded via edge index
object MeshSegmenter {
    fun removeBelow(mesh: TriMesh, plane: Plane, cutM: Double): TriMesh            // clips at the plane (C2)
    fun keepLargestComponent(mesh: TriMesh, keepFraction: Double = 0.05, insideBox: OrientedBox? = null): TriMesh  // C6 + inside-box rule
    fun crop(mesh: TriMesh, box: OrientedBox): TriMesh                              // keeps triangles fully inside
}
object VertexClusterDecimator { fun decimate(mesh: TriMesh, targetTriangles: Int): TriMesh }   // QEM representatives (C3)
object MeshExtent {
    fun estimate(mesh: TriMesh, frame: OrientedBox, noise: DepthNoise): Extent3   // §7.1.5
    fun crossCheck(extent: Extent3, measured: BoxMeasurement): CrossCheck          // agrees, badge APPROXIMATE|ROUGH, worstAxis
}
class TriMesh(val positions: FloatArray, val colours: ByteArray, val indices: IntArray)   // colours empty, RGB or RGBA (phase0.md §2)
// GlbWriter (Builder A, docs/contracts/phase0.md):
fun GlbWriter.writeMesh(mesh: TriMesh, origin: BoxOrigin = BOTTOM_CENTER, unlit: Boolean = true, extras: MeshExtras = MeshExtras()): ByteArray
```

- **Frames.**
  - `VolumeSizing.spec` takes the box in **world** (`pose` = box → world).
  - The mesh functions take an `OrientedBox` in the **mesh's** frame.
  - Marching-cubes meshes are already in the box frame (origin at the base centre), so use `OrientedBox.local(w, h, d)` or its `trimmed(…)`.
- **Volume.** A **dense, bounded** volume in the measured box's frame: the box plus a margin, plus 2 cm below the support plane.
  - Dense beats voxel hashing because the bounds are known: the code is simpler, the cost is deterministic, and there is no allocation during capture.
  - **Memory:** 6 bytes per voxel (2 B TSDF, 1 B weight, 3 B RGB), so **≤ 12.6 MB** at `N_MAX` (6.3 MB on low-RAM phones). Measured on the sofa example: 12.44 MB.
  - **Allocation:** once, when capture starts. It is released at the end of processing or on cancel. `reset()` reuses it for "Start again".
- **Voxel size:** 1 cm by default. 3d-capture.md §7.5 suggests 1.5 cm for volumes over 3 m³ or phones with less than 4 GB RAM, which the formula reproduces through `N_MAX` and `N_MAX_LOW_RAM`. Below 1 cm adds memory but no detail, because the depth noise is centimetre-level.
- **Margin:** the default is **10 cm** around the box (research §7.5), adjustable from 0 to 15 cm.
- **Example sizes:**

  | Object | Volume with margins | Voxel | Voxels | Memory |
  |---|---|---|---|---|
  | 60×40×40 cm carton | 0.25 m³ | 1 cm | ~0.26 M | 1.6 MB |
  | 2.2×1.0×0.9 m sofa | ≈ 2.9 m³ | ≈ 1.14 cm | ~2.08 M | 12.5 MB |

- **Integration.** For each depth sample with confidence ≥ 128 and depth 0.35–2.0 m:
  - **Pre-test:** back-project, then do an AABB test in volume space. If the sample is outside, drop it (most background goes here).
  - **Ray walk (C1):**
    - Walk the ray from **d − μ to d + 0.5·μ** (`TsdfParams.behindFraction`) at v/2 steps, updating each grid point once per ray.
    - Each update is `tsdf = (W·tsdf + w·sdf)/(W + w)` with the projective SDF and weights saturating at 127.
    - The band behind the surface is shortened because a full band leaks through convex edges: rays that hit the top near an edge carried "inside" values out through the side face (synthetic box: +12 mm on D; +3 mm after the change).
  - **Weight:** w = round(2 × confidence/255 × source factor / z²), clamped 1–64, so a typical sample weighs 1–2 and the weight-3 extraction threshold drops one-frame noise.
  - **Truncation:** μ = max(4 cm, 3 v) per volume (research §7.5).
    - It exceeds the depth noise at the working distance of about 1 m.
    - The per-sample σ_depth(z) acts through the weight, not through μ.
  - **Colour:** a running average from the YUV sample at the depth pixel, only when the view angle to the surface, taken from the depth image's own normal, is < 60°.
- **Meshing** (after capture, on the processing step, with the AR session paused, so it has the whole CPU and GPU):
  1. **Marching cubes** (C4) over voxels with weight ≥ 3. Cubes whose corners are **all saturated** are skipped: a sign change there is two truncated bands meeting (e.g. a part thinner than the bands, carved from both sides), not a surface.
     - "Saturated" follows the asymmetric band (C1): tsdf ≥ +0.9 in front, tsdf ≤ −0.9 × `behindFraction` behind (−0.45 by default; integration never writes below −`behindFraction`).
     - A real surface always has a corner within √3/2 voxel, i.e. |tsdf| ≤ 0.29 at the 3-voxel minimum μ, so it is never skipped. (Review M4 MINOR 7: the earlier symmetric ±0.9 rule could no longer fire after C1.)
     - Two passes over the volume: the first counts vertices and indices, the second fills arrays of exactly that size.

     The 256-case table is **generated** at class load:
     - On an ambiguous face, the inside corners stay separate, so neighbouring cubes always agree and the surface has no cracks.
     - Every loop is fan-triangulated with no diagonal on a cube face, so every interior edge is shared by exactly 2 triangles. All 256 cases are tested.
     - No published table is copied.
  2. **Floor cut** (C2): `removeBelow` **clips** triangles at the support plane + max(1 cm, v) (Sutherland–Hodgman, with welded cut vertices and interpolated colour), so the open bottom rim is a straight line rather than voxel-jagged.
  3. **Largest component** (C6, with the measured box): `keepLargestComponent(m, insideBox = OrientedBox.local(w, h, d))`.
     - First, pieces with less than 50% of their area (triangle centroids) inside the measured box grown by 3 cm are dropped. This removes wall patches and neighbouring furniture inside the capture margin, even when they are larger than the object (review M4 MINOR 8: a wall 5 cm behind a sofa is about 30% of the sofa's area).
     - Then the largest remaining piece by area is kept, plus any piece with ≥ 5% of its area (a chair's separately fused legs survive; floaters go).
     - Tested: a box 5 cm from a wall (the area rule alone keeps the wall; with the box rule D = 410.7 mm against 400) and a chair with 4 cm legs in front of a wall (all four legs kept, wall dropped).
     - Limit: a wall closer than about 6 cm (the TSDF bands) fuses into the object's piece and must be trimmed with the Review crop.
  4. **Decimation** (C3) to the **budget-derived target** (§7.1.7; `DEFAULT_TARGET_TRIANGLES = 20_000`, `MAX_TRIANGLES = 25_000`):
     - Vertex clustering on a uniform grid, with the cell size found by 20 bisection steps.
     - Cells, triangles and border edges are found by **sorting** packed keys in one reused `LongArray(max(V, 3T))` (an allocation-free introsort), not by hash tables (review M4 MINOR 9).
     - Each cell's representative is the **quadric-error minimiser** (Lindstrom 2000 over Garland–Heckbert quadrics), clamped to the cell's own vertices.
     - Open-border edges add a perpendicular constraint plane.
     - A plain cell average shrank faces by up to half a cell. Measured bounding-box change: 1.4 mm on the carton at a 32 mm cell, and at most 2.3 mm on the sofa over 2 yaws × targets of 15k, 20k and 25k triangles (limit 5 mm).
     - Vertex Hausdorff, checked exhaustively both ways: every vertex whose cell survives is within one cell diagonal of its output vertex, and every output vertex is within one cell diagonal of an input vertex. Vertices in cells that collapse entirely have no bound (0.32% on the carton test).
  5. The mesh's own extents and ± (§7.1.5), and `crossCheck` against the measured box.
  6. A **draft GLB** written to `files/shape_drafts/<uuid>/draft.glb`.
- **Pipeline call order** (as in `ReconSceneTest`):

  ```kotlin
  val vol = TsdfVolume(VolumeSizing.spec(boxWorld, marginM, lowRam) ?: return TooBig)
  vol.integrate(frame, yuv, source.sampleWeight)                             // per integrated frame, fusion worker
  // Processing: take everything still needed from the volume first …
  val noise = DepthNoise.from(vol, source, uncertaintyModel)                 // mean range, observations, voxel size
  val coverage = vol.observedFraction(); val voxelM = vol.spec.voxelM         // for mesh_info
  var m = MarchingCubes.extract(vol, MarchingCubes.DEFAULT_MIN_WEIGHT)       // 3
  // … then drop every reference to vol (12.6 MB) before segmentation and decimation.
  val local = OrientedBox.local(w, h, d)
  m = MeshSegmenter.removeBelow(m, Plane(Vec3.UP, 0.0), max(0.01, voxelM))
  m = MeshSegmenter.keepLargestComponent(m, insideBox = local)
  m = VertexClusterDecimator.decimate(m, targetTriangles)                    // §7.1.7
  val ext = MeshExtent.estimate(m, local, noise)
  val check = MeshExtent.crossCheck(ext, measuredBox)                         // ROUGH badge on failure
  // then GlbWriter.writeMesh(m, BOTTOM_CENTER, unlit = true, extras); an empty mesh → ShapeScanError.ProcessingFailed
  ```

  Measured timings (desktop JVM, sofa-size volume; phone numbers come from the spike, §7.1.10):
  - integration: median 6.3 ms per 160×120 frame, 0 bytes allocated (2026-11-06, idle machine)
  - processing: 0.31 s (marching cubes 51 ms, segmentation 47 ms, decimation 216 ms), measured before the MINOR 9 changes
  - after them (2026-11-16): marching cubes 122–183 ms and decimation 267–615 ms. These were measured while the machine was loaded (integration on unchanged code read 9.4–17.6 ms in the same runs), so the cost of the two-pass meshing and sort-based decimation is not yet separated from load. Re-measure on an idle machine and in the spike.
- **Open item for Builder A (phase 2 integration): `writeMesh` re-centring.**
  - `writeMesh` translates each mesh so that its own AABB's `BOTTOM_CENTER` is the origin (phase0.md K8).
  - After the floor cut the mesh's lowest point is at plane + 1 cm, so the GLB drops it 1 cm onto the floor.
  - The GLB's `heightM` is then about 1 cm less than `MeshExtent`'s H, which is measured from the support plane (§7.1.5).
  - The mesh is also centred horizontally on its own AABB rather than on the measured box. That matters for partial scans.
  - Decide whether `heightM`/extras carry the mesh AABB (current behaviour), and whether a mesh placed in M4 should keep the box-frame origin. Either way, the cross-check uses `MeshExtent`, not the GLB extras.
- **Bottom:** left **open**. The material is `doubleSided`, so looking underneath never shows a hole as a missing face. The underside is never visible when the object is placed on a floor.
- **Colour:** vertex colours (`COLOR_0`), with the front material **unlit** (the captured colour already contains the room's lighting; same reasoning as ADR-003).
- **No texture atlas:** UV unwrapping, seams and keyframe storage are too complex for a "rough" scan, and too large for 0.5 MB. Photo-real textures are the cloud product's job.
- **User crop (Review):** six trim sliders (`OrientedBox.local(w, h, d).trimmed(left, right, bottom, top, back, front)`), applied to the draft mesh by `MeshSegmenter.crop`, which keeps triangles fully inside (instant, no re-fusion). Extents and ± recompute live.
- **Known limits:**
  - Parts thinner than about 6 cm (μ + 0.5μ) can be carved from both sides; the saturation rule removes the resulting false surfaces where the bands meet, not the carving.
  - A wall or object closer than about 6 cm fuses with the scanned object and needs the Review crop.
  - Clustering can pinch thin parts into non-manifold geometry and blurs colour per cell.
  - All accuracy figures are from synthetic scenes (no ARCore edge smoothing or textureless holes modelled).

#### 7.1.5 Honest accuracy
- **The item's dimensions are the measured box:** the W, H and D columns, MEASURED with their ±, exactly as today. The Fit verdict (ADR-002) and every size readout use them. **The mesh never changes them.**
- **The mesh's own extents** are reported separately, with a 95% ± (`MeshExtent.estimate`):
  - **W and D:** robust percentiles (0.5% and 99.5%) of the referenced vertices' coordinates along the box axes, so stray vertices can't stretch them (tested: 0.2% outliers ignored).
  - **H:** from the **support plane** (box-frame y = 0) to the 99.5% percentile, because the floor cut removes the bottom ~1 cm. If the mesh reaches below the plane, H is taken from its own 0.5% percentile instead.
  - **Random face term:** σ_face² = σ_depth(r̄)²/n_eff + v²/12, where:
    - σ_depth(r̄) = `DepthSource.sigmaAt1mM` × r̄²: the depth noise at mean range, per source (raw or ToF is better than smoothed depth-from-motion)
    - n_eff = min(observations, 10), because consecutive frames are correlated
    - observations = `TsdfVolume.surfaceObservations()`, which counts rays, not frames: an upper bound that the cap absorbs
  - **Extent σ (C5, revised 2026-11-16; pending the reviewer and FRIDAY's gate):** σ_ext² = 2·σ_face² + (rel_scale · L)², for W, D and H (H's two "faces" are the top and the support plane).
    - rel_scale is the tier's scale term, `UncertaintyModel.effectiveScaleSigmaRel`, and L is the extent itself.
    - The scale term acts on the **length** because depth-from-motion is triangulated from the VIO poses: a VIO scale error scales the whole fused model, so it moves an extent by rel·L.
    - The first C5 used rel_scale · max(L, √2·r̄). On a 60×40×40 cm carton that made the ± 3–6× the synthetic error (H: mean ±95 38.6 mm against a 95th-percentile error of 6.2 mm), which review M4 MINOR 10 asks the tests to reject.
    - **Residual risk:** a depth-scale bias that does **not** come from the VIO scale (e.g. a per-device depth calibration error) would move faces by about bias·r̄ and is no longer covered. The bench (§7.1.10, research §7.6) must check the mesh-extent error against a tape before the ± is trusted on devices.
  - **The extent ±** = 1.96·σ_ext + the inflation bias (below, **W and D only**), then the engine's tier floor, rounded **up** for display (the existing rules).
- **Inflation bias:** fused depth-from-motion reads silhouettes **1–3 cm large per side** (research §7.6).
  - The bias is added to the mesh ± (`DepthSource.inflationPerSideM`: raw 1 cm, smoothed 2 cm, ToF 0.5 cm), on 2 sides for W and D. **H gets none:** research §7.6 places the silhouette inflation on W and D and calls the height the best dimension (revised C5).
  - It is not "corrected", because it varies by surface.
- **Synthetic check (§7.1.11),** 200 seeded trials:
  - Each trial has a random yaw, a uniform ±1.5% scale error, 1% range noise, holes, flying pixels and 1-pixel silhouette fattening (about 1 cm per side, the RAW inflation term).
  - The test asserts both coverage ≥ 95% and **not over-wide**: mean ±95 ≤ 4 × the 95th-percentile error on each axis (review M4 MINOR 10).
  - Result with the revised C5:

    | Axis | Covered | Max error | 95th-percentile error | Mean ±95 | Ratio |
    |---|---|---|---|---|---|
    | W | 100% | 20.0 mm | 17.9 mm | 40.3 mm | 2.25× |
    | H | 100% | 6.8 mm | 6.2 mm | 19.1 mm | 3.07× |
    | D | 100% | 20.0 mm | 14.6 mm | 39.1 mm | 2.68× |

  - Real depth is worse than the synthetic noise. Tune `DepthSource` on the bench (§7.1.10).
- **Cross-check (`MeshExtent.crossCheck`):** if any |mesh extent − box extent| > max(3 cm, 3%, combined ±) (research §7.6 threshold, widened by our ±), the Review shows *"The 3D shape and your measured size differ by X cm on the width. Re-scan, or check the box."* The mesh badge becomes ROUGH, and `worstAxis` names the axis.
  - The combined ± is the **root-sum-square** of the two ±95s. The errors are independent, and RSS gives the smaller tolerance, so the check warns rather than stays quiet when in doubt (cf. R1).
  - An empty mesh never agrees.
  - The mesh is **never silently rescaled to the box**, and the box is never rescaled to the mesh.
  - Within tolerance, both are shown: "Size (measured): 82 × 41 × 76 cm ± 1 · 3D shape (approximate, from depth): 80 × 40 × 74 cm ± 3".
- **Labels everywhere** (Gallery card, item screen, Place chip, share text): **"Approximate 3D shape · measured size"**. The "1:1 True size" chip on Place still applies, because the mesh is at fused metric scale.

#### 7.1.6 Thermal, memory, frame rate, process death
- **Rate governor** (`ShapeScanGovernor`, pure, in `:ar`): starts at **5 Hz** integration and steps down to 3 Hz, then 2 Hz, when any of these happens:
  - camera frames are being dropped: the p90 of the camera-timestamp intervals over the last 2 s exceeds 1.5× the locked frame period (50 ms at 30 fps, 25 ms at 60 fps). A plain "p90 > 33 ms" rule would fire on every healthy 30 fps session, whose nominal interval is 33.3 ms (T-D0 review BLOCKER 1)
  - the fusion worker has a backlog (a slot is still busy at the next copy)
  - thermal status reaches MODERATE (`PowerManager.addThermalStatusListener`, API 29+; on API 24–28 only the frame-time rule applies)

  At thermal **SEVERE**, capture stops. With ≥ 50% coverage it offers "Finish with what was captured"; otherwise "Your phone is too warm. Let it cool for a few minutes." Colour integration is dropped first, before the rate.
- **30 fps:**
  - ARCore and the camera keep running every frame. Only integration is rate-limited. 2 pooled depth slots handed to the worker with `trySend` (as built): a busy worker means the frame is skipped and counted as backlog, which steps the governor down. The AR loop never waits on fusion. The step-down trigger is dropped frames (p90 interval above 1.5 × the 30 fps-clamped frame period), not raw frame time.
  - Rendering during capture is only the box wireframe and the coverage overlay (the existing Canvas overlay approach). No mesh is rendered live.
- **Time limit:** a 45–90 s target with a 90 s hard cap (thermal and battery). Processing then runs with the session **paused**, with a target of ≤ 3 s (marching cubes plus clustering, [est.]; desktop JVM 0.3–1.3 s, §7.1.4) and a progress bar.
- **Memory:**
  - **Capture:** the TSDF (≤ 12.6 MB) plus the two depth slots, all allocated at capture start; integration allocates nothing per frame (measured: 0 bytes over 20 frames).
  - **Processing** (review M4 MINOR 9): `DepthNoise` and the `mesh_info` numbers are taken from the volume **before** meshing, and the volume is released straight after marching cubes (§7.1.4 pipeline).
  - **Measured peak extra memory** on a noisy sofa scan (148k raw triangles, 140k after segmentation), as the bytes each stage allocates plus the live inputs it still holds (`ReconMemoryTest`; an upper bound on the live peak):

    | Stage | Before MINOR 9 | After |
    |---|---|---|
    | Marching cubes (volume alive) | 22.6 MB | 18.9 MB |
    | Floor clip | 38.9 MB | 8.0 MB |
    | Components | 21.1 MB | 8.7 MB |
    | Decimation | 49.0 MB | 10.9 MB |
    | **Peak** | **49.0 MB** (volume kept to the end) | **18.9 MB** (volume released after meshing); 23.3 MB if it is kept |

    The decimator alone went from 33.8 MB to 8.1 MB and the floor clip from 23.5 MB to about 2–3 MB. The test asserts a peak ≤ 25 MB, against the spike's 40 MB criterion (§7.1.10 item 3).
  - Phone PSS is not measured; the spike does that.
  - On `onTrimMemory(RUNNING_CRITICAL)` capture stops and offers Finish.
- **Pause** (call, app switch) during capture: integration stops at once, and the anchors are detached by the existing rule. The TSDF stays in memory, but the world frame after resume is not guaranteed to match it, so resume shows **"Finish with what was captured"** (≥ 50% coverage) or **"Start again"**. The fusion is never continued across a pause.
- **Process death:**
  - **During capture:** the in-memory TSDF is lost, and it is deliberately **not** checkpointed: 12 MB of writes every few seconds would cost I/O and heat for at most 90 s of work, and the anchors can't survive anyway. `SavedStateHandle` keeps the step and the measured box *numbers*. Restore shows "The scan was interrupted" with **Scan again** (re-mark the box) and **Save as box instead** (uses the kept numbers).
  - **After processing:** the draft GLB on disk plus the crop values in `SavedStateHandle` restore Review exactly.
  - **Drafts:** directories older than 24 h are swept at start-up (the `cleanUp` pattern).

#### 7.1.7 Storage (keeps the 0.5 MB-per-item budget; ADR-014)
- **Vertex cost:** float3 position, float3 normal and RGBA8 colour = 28 B. Indices are u16 (6 B per triangle). A closed-ish mesh has V ≈ T/2, so **≈ 20 B per triangle**.
- **Budget:** `targetTriangles = min(20_000, (MAX_SCAN_BYTES − thumbnail − 8 KB overhead) / 20)` → **≤ 20k triangles ≈ 400 KB**, with an aim of 15k (≈ 300 KB) for headroom. That is ample for an "approximate shape" at 1–1.5 cm voxels.
- **Thumbnail:** a 256 px WebP (≈ 10–20 KB) from the captured keyframe that faced the box front most squarely. No `front.jpg` is stored.
- **Separate budget? No.** The existing `check(bytes <= MAX_SCAN_BYTES)` (`GalleryRepository.kt:186`) applies unchanged.
- **If quality later needs more:** `KHR_mesh_quantization` (int16 positions, int8 normals) halves the bytes per triangle **if** gltfio supports it on device (not checked). It is preferred over raising the budget.
- **Save path:** `GalleryRepository.saveMesh(MeshDraft)` reuses the staging → commit → sweep protocol, with `model_kind = MESH_DEVICE`, the box dimensions, `mesh_*` fields (§9) and the GLB.
- **Gallery cap:** device scans count toward it. Free gets **5 3D scans in total** (user decision 2026-11-23, replacing "3 saved"): a lifetime counter in `SettingsRepository` (`device_scans_used`), floored by the saved MESH_DEVICE rows; deleting a scan does not refund it, and Delete my data keeps it. **The D save path must call `SettingsRepository.recordDeviceScanSaved()` after every successful `saveMesh`.** Gated **before** capture starts, never in AR.

#### 7.1.8 Placement in M4 (contract K8)
- **Origin:** every device mesh is written in the measured box's frame with **`BOX_ORIGIN = BOTTOM_CENTER`** (the centre of the box base on the support plane). M4's floor placement therefore handles it exactly like a proxy box.
- **GLB extras:** `extras.{widthM,heightM,depthM}` always describe **the GLB's own geometry** (for a mesh, the mesh extents), plus `glbVersion = 2` and `modelKind`.
- **`modelForPlacement` rules:**
  - For PROXY_BOX and TEMPLATE, mismatched extras **regenerate** the model.
  - For MESH_DEVICE (and later MESH_CLOUD) the mesh **cannot** be regenerated. On a missing, corrupt or mismatched file it falls back to placing the **proxy box generated from the measured dimensions**, with *"3D shape unavailable; showing the measured box"*. It never crashes and never rescales.
- **The verdict** uses the measured box dimensions (ADR-002).
- **Sequencing:** Builder A delivers `GlbWriter.writeMesh` first in the sprint. M4's tests use an engine-generated sphere or box mesh GLB as the fixture, so M4 is mesh-ready before the scan UI exists.

#### 7.1.9 Module placement and threading summary

| Piece | Module | Thread |
|---|---|---|
| `VolumeSizing`, `TsdfVolume`, `MarchingCubes`, `MeshSegmenter`, `VertexClusterDecimator`, `MeshExtent`, `TriMesh`, `GlbWriter.writeMesh` | `:engine/recon` (pure JVM) | Callers' choice |
| `DepthFrameTap` (pooled 2-slot copy), `ShapeScanController : ArFrameListener` (coverage, governor, pause policy), `ShapeScanGovernor` (pure) | `:ar` | Copy on **main**; fusion on a dedicated `limitedParallelism(1)` worker, separate from `MeasureSession`'s worker |
| v3 columns, `saveMesh`, draft sweep | `:data` | IO, plus `GalleryRepository.cpu` |
| `app/scan3d`: `ShapeScanRoute`, `ShapeScanArScreen` (box → crop → capture), `ShapeProcessingScreen` (non-AR, progress), `ShapeReviewScreen` (non-AR SceneView turntable + crop sliders + dimension card) | `:app` | UI ≤ 10 Hz state |
| Thermal listener | `:app` → `ShapeScanGovernor` | main |

- **Errors:** depth not yet available → keep waiting (coaching "Move the phone slowly"). No depth after 5 s → "Depth isn't working here; try more light or a textured surface". Each step has a typed error (`ShapeScanError.NoDepth | TooBig | TooWarm | LowCoverage | Interrupted | ProcessingFailed`). `CancellationException` is always rethrown.
- **Offline:** fully on-device.
- **Security and privacy (LOW):** no new permission or network. Depth and frames never leave the device or reach disk except the draft GLB and thumbnail, which live in app-private storage. Test fixtures are synthetic only (no real home captures in the public repo).
- **Size:** <0.1 MB of dex [est.], with **no native library**. The marching-cubes tables are about 4 KB.

#### 7.1.10 Spike first (Builder A, the first 1–2 days of the D milestone), with hard exit criteria
On the bench depth phone (and on a smoothed-only depth phone, if one is available). **Build policy (orchestrator decision 2026-11-16, ratified by the T-D0 gate):** the spike runs on the **debug** build, which carries the 3D Scan Lab. A debug PASS is conclusive as the table below says. Any FAIL of criteria 2–4 is re-run on a release-signed `lab` build type before acting on it.
1. A 60×40×40 cm carton gives mesh extents within their ± of the measured box on all three axes, in 4 of 5 scans. **Run it at two distances (≈ 0.7 m and ≈ 1.5 m, 5 scans each)** and compare each against a tape as well as the box. This tests the revised C5 ± (scale term rel·L, no height inflation) against a depth-scale bias that doesn't come from VIO scale; a distance-dependent error means C5 must be widened again (M4-P1 gate R2).
2. AR frame time p95 ≤ 33 ms during capture at ≥ 3 Hz integration (`gfxinfo`), and fusion ≤ 15 ms per integrated frame (trace markers).
3. 90 s of capture does not reach thermal SEVERE from a cool start, and the peak extra PSS is ≤ 40 MB (`dumpsys meminfo`).
4. Processing ≤ 5 s for a sofa-size volume.

Also run the research's bench addition (3d-capture.md §7.6): a known box, an armchair and a 2–3-seat sofa, on 2 phones, 5 scans each. Record the mesh-extent error against a tape, the hole area, the scan time and the thermal state.

**If 1 fails:** ship only to ToF/raw-depth phones, or defer. **If 2 or 3 fail:** first lower `N_MAX` and the rate; the GLES 3.1 compute escalation (ADR-013) needs a separate decision.

**What a debug build can and cannot conclude** (from `docs/reviews/T-D0-gate.md` §4):

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

**Analysis rules for the lab CSV/JSON (gate G3, G4):**
- **G3, PSS settle:** a run's processing memory figure counts only if the JSON `noise` stage `pss_mb` is at least the processing baseline − 1 MB. Otherwise the baseline didn't settle; re-run.
- **G4, frame period:** the governor's period is clamped to 30 fps. On a variable or 60 fps camera range, judge the AR frame-time column against the logged range, not against 33 ms alone.
- **R2, error vs distance:** use `mean_range_m` (the real stand-off), not the label.
- **Timing claims:** user-facing timing claims always come from a release-signed build (lab or the release candidate).

#### 7.1.11 Tests (§4.C)

| Layer | Tests |
|---|---|
| **Engine (JVM): synthetic scenes** | `SyntheticDepthRenderer` (test sources) ray-casts analytic SDFs into 160×120 depth images from 24–60 virtual poses around the object, with an optional Gaussian noise of 1% of range, flying pixels at depth edges, 10% holes and a confidence map. **Sphere** (r = 0.25 m, floating, full coverage): mean \|distance to the true sphere\| ≤ 0.5 v noise-free and ≤ 1 v with noise. The fitted radius is within v/2. The mesh is **watertight** (each edge shared by exactly 2 triangles) and outward-oriented (signed volume > 0, within 3% of 4/3πr³ noise-free). **Box** (0.60×0.40×0.40 on a plane): extents within ±1 v noise-free. With noise, **the reported ± covers the truth in ≥ 95% of 200 seeded trials** (the same style as `CoverageTest`). **Floor removal:** no vertex below plane + cut. **Largest component:** a floating 3 cm cube is dropped, the object is kept. **Crop:** triangles outside the box are gone and the extents shrink to match. |
| **Engine (JVM): units** | `VolumeSizing`: voxel clamp at 1 and 3 cm, `N ≤ N_MAX`, the low-RAM cap, and null for sides > 2.5 m. `TsdfVolume`: one ray sets the right SDF sign either side of the surface, weights saturate, and the AABB pre-test drops samples outside. `MarchingCubes`: all 256 cube configurations produce consistently oriented triangles; welding gives shared vertices. `VertexClusterDecimator`: count ≤ target; Hausdorff distance to the original ≤ 1 cluster cell (sampled); extents preserved within one cell; deterministic. `MeshExtent`: percentiles ignore 0.2% outliers. `GlbWriter.writeMesh`: `COLOR_0` accessor valid, u16 indices when V < 65,536 and u32 otherwise, extras equal the vertex bounding box, the sofa-size synthetic fits ≤ `MAX_SCAN_BYTES`, the bytes are identical across runs, and the existing validator-style checks pass. Timing printouts are informational only. |
| `:ar` (JVM) | `DepthFrameTap`: a repeated depth timestamp means no copy and no state change; the slot ring never hands out a busy slot. `ShapeScanGovernor`: step-down on frame time, backlog and MODERATE; stop on SEVERE; never steps up during the same scan. Coverage bins from poses. Pause → integration stops, Finish or Start again. |
| `:data` (JVM + CI emulator) | v2 → v3 `MigrationTestHelper` (with v1 → v3). `saveMesh` budget enforced on exact bytes. Draft sweep after 24 h. `modelForPlacement`: a mesh with mismatched extras falls back to the proxy and **never** regenerates the mesh. |
| `:app` (JVM) | The shape-scan view model: process death during capture → "interrupted" with the box numbers kept; during Review → the draft plus crop restored. The no-depth fallback sheet routes to the box scan. The gate check happens before capture (Free cap 3). |
| **Device checklist** (`TESTING.md` §18) | No-depth phone shows the fallback. Carton, sofa and plant results (photographed for the record). Glossy or black appliance: the expected poor result with the honest cross-check warning. 90 s scan: fps, thermal status log and memory. Pause in the middle. Process death in capture and in Review. Place the mesh in M4. Hindi. |

### 7.2 Cloud scan, client side (only after §8 exists)

- **New module `:cloud`** (an Android library, Builder B). The new module is justified by a new network stack, WorkManager use and a separate owner. It keeps HTTP out of `:data` and `:ar`.

```kotlin
interface CloudScanApi {                                         // vendor-neutral; one impl + fakes
    suspend fun createJob(req: CreateJobRequest, integrityToken: String): CreateJobResponse  // returns upload slots
    suspend fun submit(jobId: String, manifest: UploadManifest): JobStatus
    suspend fun status(jobId: String): JobStatus                  // QUEUED|PROCESSING(progress)|SUCCEEDED(resultUrl)|FAILED(code)
    suspend fun delete(jobId: String)
    suspend fun credits(): CreditBalance
    suspend fun verifyPurchase(token: String, productId: String): CreditBalance   // server verifies + consumes
}
class CloudScanRepository(api: CloudScanApi, dao: CloudJobDao, files: CaptureStore, work: WorkManager)
```

- **Capture** (`:ar`, Builder A): `CloudCaptureListener` records keyframes at about 1 per 10 cm or 10° of motion, gated on steadiness. Each keyframe carries its ARCore **camera pose and intrinsics**, which fix the metric scale server-side.
  - Keyframes are JPEGs from the highest CPU image config that holds 30 fps, or high-resolution stills via `captureStill`. The resolution choice comes from 3d-capture.md.
  - Files go to app-private `files/cloud_capture/<uuid>/` with a `manifest.json` (poses, intrinsics, device tier).
  - **EXIF and GPS are never written:** the JPEGs are encoded from pixels, so there is no camera metadata.
- **Upload:**
  - A `CoroutineWorker` does resumable, chunked uploads to short-lived signed URLs. Constraints: network, Wi-Fi only by default with a user toggle, battery not low.
  - **No foreground service in v1.** The manifest strips `FOREGROUND_SERVICE` (`AndroidManifest.xml:31-33`). Plain resumable chunks survive deferral. A `dataSync` FGS (plus `POST_NOTIFICATIONS`) is added only if field data show stalls.
  - Upgrading `work-runtime` (O6) is its own change.
- **Polling:** while the app is foreground, backoff from 5 s to 60 s. Otherwise a 15-minute periodic worker until SUCCEEDED, FAILED or 24 h. No FCM in v1.
- **Result:** download the GLB to the item directory, then go through the existing save path (`model_kind = MESH_CLOUD`).
- **Room:** a `cloud_jobs` table (v-next, §9) is the UI's source of truth. A start-up sweep deletes capture directories with no row, the same pattern as `cleanUp`.
- **Networking:** `HttpURLConnection` (platform, zero size; §3.1 ladder) plus kotlinx-serialization-json. Every call has timeouts (connect 15 s, read 30 s). Only idempotent calls are retried, with exponential backoff and jitter. One sealed error model (`CloudError.Offline | Auth | Integrity | NoCredits | TooLarge | Server(code) | Unknown`) is mapped at the edge (§4.H).

---

## 8. Cloud backend sketch

**Principle:** vendor-neutral interfaces, and the minimal set of managed services.

One hard constraint: Play's **RTDN is delivered only through Google Cloud Pub/Sub**, and the **Developer API** needs a Google service account. Some Google Cloud footprint is therefore unavoidable, whatever hosts the rest.

```
 Phone ──HTTPS──► API (stateless container: Cloud Run / Lambda / Fly)          ◄── Pub/Sub push: Play RTDN
   │  ID token (Auth provider)        │  verifies: auth token, Play Integrity token (cost-incurring calls)
   │  + Play Integrity token           │
   │                                   ├── DB (Postgres): accounts, jobs, credit_ledger, purchases (unique token)
   │  signed PUT (15 min, size-capped) ├── Object storage: private buckets inputs/ (TTL), outputs/ (TTL)
   └──────────────────────────────────►├── Queue (Pub/Sub / SQS / Cloud Tasks): job messages, DLQ
                                        └── GPU worker pool (spot L4/A10 or vendor API) behind ReconstructionBackend
```

### 8.1 Components and contracts

| Component | Contract (vendor-neutral) | Notes |
|---|---|---|
| **Auth** | `AuthProvider.verify(idToken) → AccountId`. Short-lived ID tokens (≤ 1 h), refreshed by the client SDK. | Anonymous account on first cloud use, **linked to Google sign-in through Credential Manager** for restore across devices (§13 D9). An account means Play requires in-app **and** web account deletion. |
| **Purchases** | `POST /v1/purchases/verify {token, productId}`. The server calls the Developer API (`purchases.products.get` / `subscriptionsv2.get`), checks `obfuscatedAccountId == hash(accountId)` (set by the client in `BillingFlowParams`), writes the ledger, then **consumes** the consumable server-side. | Idempotent: `UNIQUE(purchase_token)`. RTDN updates subscriptions. A daily `voidedpurchases.list` job writes negative ledger entries for refunds. |
| **Jobs** | `POST /v1/scans` (reserve 1 credit, return N upload slots) → `PUT` to signed URLs → `POST /v1/scans/{id}/submit` (validate the manifest: count, sizes, SHA-256) → `GET /v1/scans/{id}` → `DELETE /v1/scans/{id}` | State: `AWAITING_UPLOAD → QUEUED → PROCESSING → SUCCEEDED / FAILED / EXPIRED`. **Every** read and write checks `job.account_id == caller` (IDOR is the #1 bug class to test). |
| **Queue** | At-least-once, a lease with heartbeat, max 2 attempts, then the dead-letter queue. | The worker is idempotent on `job_id`. |
| **GPU worker** | `interface ReconstructionBackend { fun reconstruct(inputs: CaptureSet, hints: ScaleHints): ReconResult }`. 3d-capture.md §1 picks the first implementation: **self-hosted COLMAP 4.2 with ARCore pose priors**, then mvs-texturing, meshoptimizer/xatlas and glTF-Transform, on **Modal serverless L4s**, with **Cloudflare R2** storage. 2DGS is the later path, and vendors are fallbacks behind the same interface. | **Metric scale:** a similarity transform (Umeyama) from the reconstruction's camera centres to the ARCore poses, with residuals reported. **Output contract** (aligned with research §2.1): GLB with one JPEG base-colour atlas ≤ 2048², ≤ 50k triangles, ≤ 5 MB, a **plain GLB** (no Draco, meshopt or KTX2) for the share copy, `extras.{widthM,heightM,depthM,scaleResidualM,glbVersion}`. **Isolation:** no egress except storage. Images are decoded in the sandbox. |
| **Credit ledger** | Append-only `(account_id, delta, reason, ref, created_at)`. Reasons: `PURCHASE, JOB_RESERVE, JOB_COMMIT, JOB_RELEASE, REFUND_VOIDED, GRANT`. Balance = sum. | Reserve on create, commit on success, **release on failure** (users don't pay for failed jobs). Unique `(ref, reason)` for idempotency. |
| **Deletion and retention** | Inputs are deleted when the user keeps the result or after **30 days** at most (research §1; long enough for the one free re-run), whichever comes first, and immediately on request. Outputs are deleted 30 days after success (the phone keeps its copy). `DELETE /v1/account` erases everything within 30 days, plus a web form. | Storage region in India (latency; DPDP Act 2023 comfort). The ledger keeps only what tax law requires, pseudonymised. |
| **Observability and cost** | Structured logs with no PII and no tokens. Metrics: cost per job, queue age, failure codes. Budget alerts. **A server kill switch** (stop accepting jobs). | Per-account rate limits: 2 concurrent jobs, 10 per day. Upload caps: ≤ 200 files, ≤ 1.5 MB each, ≤ 250 MB per job. |

### 8.2 Security and privacy for uploads (MASVS, server side)

- **Network:** TLS only. Signed URLs are scoped to one object, `PUT`-only, with content-type and length pinned and a 15-minute TTL. Buckets have no public ACLs.
- **Play Integrity (standard request):**
  - Prepared at a quiet moment, with at most 5 preparations per minute (android-performance §6.7).
  - Required on `POST /scans` and `POST /purchases/verify`, verified **server-side** (`decodeIntegrityToken`).
  - The server requires `PLAY_RECOGNIZED` and `MEETS_DEVICE_INTEGRITY`, and **fails closed** on those two calls because they cost money. Reads fail soft.
- **Consent:**
  - Explicit per-upload consent: "Photos of this object will be uploaded to process the 3D model and deleted within 7 days". A link to the privacy policy. A tip to keep people and documents out of frame.
  - The Data safety form adds photos and videos (collected and processed) and a user ID.
- **No secrets in the APK or the public repo:** the service-account key lives only in the server's secret manager. The client holds only the public API base URL.
- **Abuse:** MIME sniffing and a full image decode on the server before processing; rejection of oversized or odd dimensions; rate limits; per-account and global spend caps.

### 8.3 Must exist before the Pro cloud feature ships (release gate)

1. A backend with purchase verification, the ledger, RTDN, the voided-purchases job and the consume flow, tested end to end with licence testers.
2. Auth with Google linking, plus in-app **and** web account deletion (Play policy).
3. Play Integrity enforced on cost-incurring calls.
4. Retention jobs proven: a test bucket shows inputs gone after the TTL.
5. An updated privacy policy, the Data safety form, and a DPDP consent notice.
6. A security review (§4.D) of the API: IDOR, signed URL scope, oversized and malicious upload, replayed integrity token and replayed purchase token, plus a dependency/CVE audit of the worker image.
7. Cost controls: per-job cost known from 50 bench jobs, budget alerts, the kill switch, and a refund policy for failed jobs.
8. Load test: 20 concurrent jobs and queue-age SLOs.
9. A support path: "My scan failed / credits missing", with a ledger lookup by job id.
10. The client behind a server-side flag, plus a staged rollout.

---

## 9. Persistence roadmap

**Prerequisite (fixes R2, in P1 by Builder B):** a CI job runs `:data:connectedDebugAndroidTest`, i.e. `SpaceViewMigrationTest`.

- **Option 1:** a GitHub-hosted emulator through a SHA-pinned action.
- **Option 2:** Gradle Managed Devices (API 34, x86_64, ATD image). `:data` has no ABI filter, so this works.
- **Fallback:** if CI time is prohibitive, run `MigrationTestHelper` under Robolectric on the JVM.
- **Locally:** never on the 2 GB PC.

**Rules (FRIDAY §4.G):**
- v2 is **frozen** now that the M3 APK is on the user's phone.
- Every shipped version gets a migration test to the latest (1→N and 2→N).
- No destructive fallback, ever.

**Scope change → one consolidated v3 for this sprint.** Templates, Smart Measure and the on-device scan now all land in this sprint, so their columns are designed together and ship as **one** migration, `MIGRATION_2_3`.
- **Owner:** Builder A, who adds it in the **first** milestone of the sprint that needs it (templates or the 3D scan).
- **Amendment window:** v3 may be amended in place (documented in `SpaceViewDatabase`) **only until any APK containing v3 is handed to the user or to testers**. After that, it is v4.

| Version | Lands with | Change | Notes |
|---|---|---|---|
| **v2** (shipped) | M3 | — | Frozen |
| **v3** | **This sprint** (T / D / SM, whichever lands first) | `gallery_items`: `model_kind TEXT NOT NULL DEFAULT 'PROXY_BOX'` (`PROXY_BOX \| TEMPLATE \| MESH_DEVICE \| MESH_CLOUD`); `template_id TEXT`; `template_params TEXT` (JSON); `mesh_extent_w_m REAL`, `mesh_extent_h_m REAL`, `mesh_extent_d_m REAL`, `mesh_extent_w_bound95_m REAL`, `mesh_extent_h_bound95_m REAL`, `mesh_extent_d_bound95_m REAL` (§7.1.5; NULL unless a mesh); `mesh_info TEXT` (JSON diagnostics: voxel size, depth source raw/smoothed, frames integrated, coverage %, triangles, cross-check result); `capture_method TEXT` (`MANUAL \| HANDS_FREE \| AUTO_RECT \| DEPTH_SCAN`; NULL = legacy). `measurements`: `capture_method TEXT`. | The default backfills existing rows as proxies. W/H/D stay the **measured** box for every kind (§7.1.5). `ObjectKind.FURNITURE` is an enum entry, and `enumOr` already tolerates unknown names. The mesh extents are typed columns, not JSON, because the UI shows them and the cross-check reads them. |
| **v4** | **C (cloud)** | `CREATE TABLE cloud_jobs (id INTEGER PK, server_job_id TEXT UNIQUE, gallery_item_id INTEGER NULL REFERENCES gallery_items(id) ON DELETE SET NULL, status TEXT NOT NULL, progress REAL, error_code TEXT, capture_dir TEXT NOT NULL, bytes_total INTEGER NOT NULL, bytes_uploaded INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)` plus an index on `status` | `cloud_jobs` is local state only; credits are never stored as truth on the device |

**Not in Room:**
- **Entitlements** live in their own DataStore file, excluded from backup.
- **Placement** isn't persisted in M4.
- **ProCatalog** is code.
- **3D-scan drafts** are files in `files/shape_drafts/`, swept after 24 h.

---

## 10. Performance and size roll-up

| Item | Download Δ (arm64) | Cold start | AR frame | Evidence / how measured |
|---|---|---|---|---|
| Baseline | **10.07 MiB** | budget ≤ 800 ms (not yet measured on device) | 30 fps never measured | `HANDOFF.md:81` (CI bundletool) |
| M4 | +0.05–0.1 MB [est.] | 0 | + model render, + lights on Place only; **− per-frame allocations** | Release build, mid-range: `gfxinfo` p95 ≤ 33 ms |
| Pro (billing-ktx 9.1.0) | +0.2–0.5 MB [est.] | 0 (connect after first frame) | 0 | CI size gate; `am start -W` before and after |
| SM1 (ML Kit unbundled) | ~+0.2 MB (Google) | 0 | 0 (runs once, off AR) | CI size gate |
| SM2 (pure-Kotlin vision) | <+0.06 MB [est.] | 0 | +≤ 1 ms main, ≤ 15 ms worker at 5 Hz [est.] | On-device trace |
| Templates | +0.03–0.05 MB [est.] | 0 | as M4 | — |
| **On-device 3D scan (this sprint)** | <+0.1 MB [est.]; **no native library** | 0 | Main ≤ 1.5 ms per integrated frame (depth copy). Fusion on its own worker at 5→3→2 Hz (governor), ≤ 15 ms per frame [est.]. Depth `AUTOMATIC` itself costs ARCore CPU (unmeasured). | Spike criteria §7.1.10. Memory ≤ 12.6 MB TSDF plus ≤ 10 MB mesh working set. |
| `:cloud` | +0.1–0.3 MB [est.] (serialization-json; HttpURLConnection is free) | 0 | 0 | CI size gate |
| **Projected total** | **≈ 10.9–11.4 MiB [est.]**, under 15 | — | — | Re-measured by CI on every push |

**Rejected for size:**
- OpenCV (+8–15 MB per ABI [est.])
- bundled ML Kit labelling (+5.7 MB)
- OkHttp or Ktor for a handful of JSON calls (HttpURLConnection is enough)

**Headroom to reclaim in M6:** the SceneView blanket keep rules (`proguard-rules.pro`) and the skybox. R5 must not be undone by the skybox strip, so keep the indirect light used by Place (ADR-003).

---

## 11. ADRs

All dated 2026-11-05.

### ADR-001: Placement is a separate `ArFrameListener`; `MeasureSession` is frozen in scope
- **Status:** proposed.
- **Context:** `MeasureSession` is 1000 lines (R3). The only AR host takes exactly one `MeasureSession` (`MeasureScreen.kt:218`). M4, Smart Measure and scanning all need frame access. Placement needs the gap measurement in the same world frame.
- **Options:**
  - A. Extend `MeasureSession`. Fast, but grows a god class and risks Measure regressions.
  - B. An `ArFrameListener` fan-out with separate collaborators.
  - C. Separate screens and sessions. This loses the world frame and the anchors.
- **Decision:** B. A listener interface and a multi-listener host. Only `commitDetected(corners)` (about 20 lines, SM2) may be added to `MeasureSession`.
- **Consequences:**
  - Easier: features become isolated, JVM-testable pure cores.
  - Harder: listener order and the single primary listener for `configureSession`. Document both, and test the host's dispatch order.
  - Monitor: `MeasureSession.kt` line count in review.

### ADR-002: The fit verdict combines uncertainties linearly; typed sides are exact and labelled
- **Status:** proposed.
- **Context:**
  - R1: the code uses RSS (`FitVerdict.kt:117-126`), while the M3 gate recommends linear (`M3-gate.md:200`).
  - Item and space errors are not known to be independent: the same phone, the same IMU scale error, and calibration shared in scope.
  - The ± model has never been validated on a device.
- **Options:**
  - A. RSS. Assumes independence and is narrower, so it produces more "Fits".
  - B. Linear sum. An upper bound for any correlation ≥ −1.
  - C. RSS with an inflation factor. Arbitrary.
- **Decision:** B for placement (`FitPolicy.combination = LINEAR`). The RSS default stays for existing callers (none in the UI today). Measured σ = bound95 / 1.96, from the rounded-up stored bound. Typed and preset sides have σ = 0 and the UI says so.
- **Consequences:**
  - More "Tight" near the edge. That is the honest outcome for a "buy or not" decision.
  - Re-evaluate once the bench data shows the coverage; RSS may be justified then.
  - Boundary tests are mandatory.

### ADR-003: The placed model's photo face is unlit; GLBs carry `glbVersion`; old models regenerate lazily
- **Status:** proposed.
- **Context:** R5. The material is lit PBR (`GlbWriter.kt:155-161`), the host has no lights (`MeasureScreen.kt:228-229`), and M6 plans to strip the environment. The photo already contains real lighting, so lighting it again changes its colour.
- **Options:**
  - A. Add lights to the Place host only. The photo still double-lights.
  - B. Front face `KHR_materials_unlit` plus sides lit, plus a light and indirect light on the Place host.
  - C. Everything unlit. The box looks flat and has no depth cues.
- **Decision:** B.
  - `glbVersion = 2` goes in `extras`.
  - `modelForPlacement` regenerates any `glbVersion < 2` model once, through the existing atomic versioned-file path.
  - M6's skybox strip must keep the Place host's indirect light.
- **Consequences:**
  - The true-colour photo shows in the room, with existing data migrated without a schema change.
  - **Verify on device** that SceneView 4.65 and gltfio honour `KHR_materials_unlit` and generate mipmaps for `minFilter 9987`. Not checked (§14).

### ADR-004: v1 Pro is verified on the client with a signature check; anything with marginal cost is verified on the server
- **Status:** proposed.
- **Context:**
  - There is no backend, and the repo is public (`HANDOFF.md:22`).
  - Pro v1 unlocks only local features: ads removal and limits.
  - Cloud credits cost real GPU money.
- **Options:**
  - A. Trust the query.
  - B. A plus a local RSA signature check.
  - C. A backend now.
- **Decision:** B for local entitlements. Consumables and cloud credits **only** with C (§8). No Play Integrity for local Pro, because without a server it is unenforceable.
- **Consequences:**
  - Ships weeks earlier.
  - Accepts APK-patch piracy, which is low value.
  - Refunds take effect at the next online start.
  - **Residual risks accepted without a server (P1 review MINOR 4):**
    - *Replay of a genuine purchase.* A real, correctly signed purchase (its `originalJson` + signature) can be served by a fake billing service on another device: the signature proves Google signed it, not that it belongs to this account or is still current. Purchases of another package are rejected; nothing else can bind a purchase to an account offline.
    - *Expired prepaid passes.* The client can't tell a prepaid pass from a cancelled auto-renewing subscription (both report `autoRenewing = false`), and a cancelled yearly plan legitimately stays active for up to a year after its `purchaseTime`. A `purchaseTime + 31 d` cut-off would wrongly remove paid Pro, so it is **not** applied. A replayed pass therefore keeps granting until the server check (§8) exists. `purchaseTime` is carried in `OwnedPurchase` for that check.
    - *Offline grace* is bounded by 7 days of app-observed time (§4.4), not wall time.
  - When the §8 backend exists, subscriptions move to server verification as well. The `BillingGateway` and `EntitlementRepository` seams make that a data-source swap.

### ADR-005: Entitlement model: a catalogue as data, a 7-day offline grace, and a lapse that never hides data
- **Status:** proposed.
- **Context:**
  - pro-monetization.md sets the Free/Pro split (§4.3), and it will be tuned later.
  - Users go offline.
  - Indian payments can be pending.
  - Lapsing must not punish existing data (trust, and Play's subscription policy spirit).
- **Decision:**
  - `ProCatalog` (one Kotlin file) holds the rules. `EntitlementReducer` is a pure state machine (§4.4).
  - The grace period is 7 days of app-observed time from the last confirmation (a persisted clock high-water mark), so clock rollback never extends it.
  - The cache is excluded from backup.
  - Gates sit at entry points and at Save only, never in AR.
  - Accessibility is never paywalled.
  - When lapsed, everything already created remains usable.
- **Consequences:**
  - A research decision is a one-file edit plus tests.
  - The grace period is a product knob (§13 D3).

### ADR-006: Billing and Pro live in `:app` packages, not a new Gradle module
- **Status:** proposed.
- **Context:** the PC has 2 GB free, so each module costs configuration time and daemon memory. Billing types must not leak into the UI, and the logic must be testable.
- **Options:**
  - A. A `:billing` module.
  - B. `:app/billing` (the Play adapter) plus `:app/pro` (pure domain) behind the `BillingGateway` interface.
- **Decision:** B. A module gives no reuse benefit today, because only `:app` consumes entitlements. Revisit if `:cloud` needs entitlements; it should ask the server instead.
- **Consequences:** a lint or Detekt rule (or a review check) forbids `com.android.billingclient` imports outside `app/billing`.

### ADR-007: Rectangle detection is pure Kotlin in `:engine/vision` on a decimated luminance feed; ML Kit is used only for naming
- **Status:** proposed.
- **Context:**
  - The size budget rules out OpenCV.
  - ML Kit has no perspective-quad detector that is anchored in AR.
  - The existing `Shapes.rectangleFromRays` plus `ImageProjection` already turn image corners into W×H with honest ±.
  - The main-thread copy cost is R3.
- **Decision:**
  - The detector runs in `:engine/vision`, which is deterministic and tested on synthetic and recorded images.
  - `FrameImageTap` (S2) copies only a decimated Y plane plus small full-resolution corner windows, into pooled buffers.
  - ML Kit labelling, unbundled, runs once per scan for naming.
- **Consequences:**
  - We own a vision algorithm, so we own its tuning.
  - SM2 ships only after it beats manual on the bench (§5.3 gate).

### ADR-008: One consolidated v3 for the sprint, and migration tests run in CI first
- **Status:** proposed (revised for the scope change).
- **Context:** R2. v2 is on a device. Templates, Smart Measure and the 3D scan now share one sprint.
- **Decision:**
  - The CI emulator or managed-device migration job is a **prerequisite**, delivered in P1.
  - **One v3** carries every column this sprint needs (§9). It may be amended only until the first APK containing it leaves the team.
  - v4 is the cloud.
  - Every shipped version gets a migration test to the latest.
- **Consequences:** a single migration to test and review. CI is about 8–12 minutes longer [est.], and there is no on-device migration surprise.

### ADR-009: Cloud reconstruction sits behind `ReconstructionBackend` (server) and `CloudScanApi` (client); `:cloud` is created only when item 5 starts
- **Status:** proposed.
- **Context:** the vendor and technology are still being researched (3d-capture.md). The cost is real. Play RTDN forces some Google Cloud use.
- **Decision:**
  - Vendor-neutral interfaces (§7.2, §8.1).
  - Metric scale always comes from ARCore poses.
  - The output GLB contract is fixed (§8.1).
  - No client module exists until the backend exists.
- **Consequences:** the vendor can be swapped, or a self-hosted pipeline used, without a client change.

### ADR-010: Furniture is a parametric multi-part GLB generated in `:engine`
- **Status:** proposed.
- **Context:** shapes need to be recognisable at true size without asset downloads, and must stay within the size budget.
- **Options:**
  - A. Bundled or Play Asset Delivery (PAD) sofa models, scaled. Scaling a non-uniform model distorts it, and the assets cost MB.
  - B. Parametric parts from measured parameters.
- **Decision:** B.
  - Five templates.
  - Invariant: the union bounding box equals W×H×D exactly.
  - `template_id` is versioned by name (`sofa_v1`).
- **Consequences:** looks "blocky but honest". Revisit PAD once a model catalogue or B2B retailer models exist (android-performance §1.5).

### ADR-011: Ads are switched off at the source for Pro; `AdsManager` moves into `AppContainer`
- **Status:** proposed.
- **Context:** R4(a). An ads singleton outside DI starts unconditionally (`MainActivity.kt:28`, `AdsManager.kt:383-392`).
- **Decision:**
  - The entitlement is injected into `AdsManager`.
  - Pro means no UMP and no SDK init. `canShowAds` includes `!Pro`.
  - An `AdsSdk` wrapper makes this testable.
  - `AdsManager.get()` delegates to the container, so the 10 call sites are unchanged.
- **Consequences:** one place enforces "no ads for Pro". This is a HIGH-attention review item, because it touches consent.

### ADR-012: Milestone order (challenges the suggested order)
- **Status:** proposed, revised for the scope change. See §12.2.

### ADR-013: The on-device 3D scan is a bounded dense TSDF in pure Kotlin on the CPU, and the measured box stays authoritative
- **Status:** proposed.
- **Context:**
  - The user moved the on-device scan into this sprint.
  - The download is 10.07 of 15 MiB.
  - There is no NDK toolchain, and the PC has only 2 GB free.
  - AR must hold 30 fps.
  - Depth-from-motion is noisy and low-resolution (the main risk, §7.1).
- **Options:**
  - A. A native TSDF library (+MB, NDK).
  - B. GLES 3.1 compute in a second EGL context.
  - C. Pure-Kotlin CPU, ray-based integration in a dense volume bounded by the measured box, marching cubes, vertex clustering, and vertex colour.
- **Decision:** C. Its parameters:
  - voxel = clamp(∛(V/2²¹), 1 cm, 3 cm); ≤ 12.6 MB
  - integration at 5→3→2 Hz under a governor
  - a 90 s capture limit (45–90 s target)
  - meshing with the AR session paused
  - the floor removed; the largest component kept; a user crop
  - an open, double-sided bottom
  - vertex colours, unlit

  **The item's dimensions are always the measured box.** The mesh extents carry their own ± and are only cross-checked. B stays as an escalation path, behind a separate decision, if the spike fails its fps criterion.
- **Consequences:**
  - Zero native size. Fully JVM-testable against synthetic SDF scenes.
  - The quality ceiling is the depth input, not the algorithm.
  - "Experimental" until the device data say otherwise.
  - Monitor: fusion time per frame, thermal status and the cross-check failure rate.

### ADR-014: Meshes keep the 0.5 MB-per-item budget; the GLB extras describe the GLB's own geometry
- **Status:** proposed.
- **Context:** the per-item budget (PLAN M3 gate) is a hard check (`GalleryRepository.kt:186`). Meshes are a new model kind. Placement must handle every kind.
- **Options:**
  - A. A separate, larger mesh budget, for example 2 MB.
  - B. Keep 0.5 MB, with the triangle target derived from the budget.
- **Decision:** B.
  - ≤ 20k triangles at about 20 B per triangle; aim for 15k.
  - No texture atlas; a 256 px WebP thumbnail.
  - `extras.{widthM,heightM,depthM}` always equal the GLB's own bounding box.
  - Meshes are never regenerated. If the file is unusable, placement falls back to the proxy box built from the measured dimensions.
  - `KHR_mesh_quantization` is the next lever, before any budget increase, and only once gltfio support is verified.
- **Consequences:** one budget rule for all items, so the gallery's storage stays predictable (40 items ≈ 20 MB). A cloud mesh (§8, ≤ 8 MB) will need its own budget decision then, not now.

---

## 12. Delegation plan and milestone order

> **Scope change (orchestrator, 2026-11-05):** this sprint now contains **M4, Pro (P1/P2), Smart Measure (SM1, SM2), furniture templates (T) and the on-device 3D scan (D)**. SM3 (depth auto-box) and cloud stay later. The plan below rebalances the two builders, because Builder A's original queue (M4 + SM + templates + D) would be the critical path by a wide margin.

### 12.1 Ownership model

- **Builder A** owns `:ar`, `:data` and the accuracy-critical parts of `:engine`, **plus the AR-screen UI that drives them**:
  - **Engine:** `GlbWriter`, recon, vision, `FitVerdict`, `Plane`.
  - **App screens:** `app/place`, `app/scan3d`, and additions to `app/measure` and `app/scan`.

  This is how M2 and M3 were split (`HANDOFF.md:60,73`), and it keeps each accuracy-critical vertical with one author.
- **Builder B** owns the non-AR app, **plus templates and naming** (new for this sprint, to balance the load):
  - **Pro and ads:** `app/pro`, `app/billing`, `app/ads`, `app/settings`, and the paywall.
  - **Templates:** `:engine/furniture/**` (a new package; pure geometry; no overlap with A's files) and `app/furniture/**` (the guided flow).
  - **Naming:** `app/scan/naming/**` (ML Kit).
  - **Infrastructure:** `AppContainer.kt`, the manifest, `libs.versions.toml`, `ci/*` and the backup XMLs.
  - **Later:** `:cloud`.
- **Optional Builder C** (§13 D15): `:engine/recon/**` only (TSDF, marching cubes, segmenter, vertex-clustering decimator, `MeshExtent`), worked entirely against the synthetic tests in §7.1.11.
  - It is the cheapest Gradle task (`:engine:test`), it touches no Android code, and it takes the largest pure-maths block off A's critical path.
  - Without C, A does it.
- **The orchestrator** owns the `nav/AppNavHost.kt` wiring, `HANDOFF.md`, docs and builds (one at a time, behind the `.build.lock` rule).

**Shared-file rules (FRIDAY §5):**

| File | Rule |
|---|---|
| `nav/AppNavHost.kt` | **The orchestrator only.** A delivers `placeDestination(…)` and `shapeScanDestination(…)`. B delivers `paywallDestination(…)` and `furnitureDestination(…)`, each in its own `*Navigation.kt`. The orchestrator adds one line per destination and replaces the `onPlace` Toast at `AppNavHost.kt:211`. |
| `engine/GlbWriter.kt` | **A only.** A delivers the full writer API in **phase 0**: `writeBox` unlit plus `glbVersion`, `writeParts` and `writeMesh`, with signatures and tests. B's templates only *call* `writeParts`. |
| `data/SpaceViewDatabase.kt`, `GalleryRepository.kt`, `GalleryItemEntity.kt`, schemas | **A only.** A lands the **whole v3** (§9) in one change at the start of phase 2, including `saveTemplate(TemplateDraft)` for B and `saveMesh(MeshDraft)` for D. B never edits `:data`. |
| Strings | A: `strings_place.xml`, `strings_scan3d.xml`, `strings_smart.xml`. B: `strings_pro.xml`, `strings_furniture.xml`, `strings_naming.xml`. Each under `values/` and `values-hi/`. Nobody edits another builder's file or `strings.xml`. Every key has EN and HI, or lint fails. |
| `AppContainer.kt` | B. A asks the orchestrator for any new member (M4 needs none; D needs none). |
| `libs.versions.toml`, `ci/release-dependencies.txt`, `ci/allowed-permissions.txt`, manifest | B. Billing adds the `BILLING` permission; naming adds the ML Kit dependency and the `DEPENDENCIES=ica` meta-data. A adds no dependency this sprint. |
| `GalleryItemScreen.kt`, `GalleryScreen.kt` | A for the Place button and the 3D badge. B for the "Add furniture" FAB and the cap counter. These are different composables in the file, so the edits are **surgical and sequential**: A in phase 1, B in phase 2, never at the same time. |
| `scan/ScanReviewScreen.kt` | A. A inserts the naming chip that B's `ObjectNamer` drives (K6). |
| `MeasureSession.kt` | A only. Frozen except for `implements ArFrameListener` (phase 0) and `commitDetected` (SM2). |

### 12.2 Recommended order inside the sprint (challenges the suggested order)

The suggested order was M4 → Pro (parallel) → Smart Measure → templates → on-device scan → cloud.

**Kept:**
- **M4 ∥ P1 first.** The files really are disjoint.
- **Cloud last**, gated on Pro conversion evidence, the 3d-capture.md vendor choice and the §8.3 checklist.

**Changed, with the evidence:**

1. **A tiny phase 0 unblocks everyone.** `ArFrameListener` plus `ArSessionHost`, and the full `GlbWriter` API (unlit, `writeParts`, `writeMesh`). Templates and D both produce GLBs that M4 must place (K8, K9). Defining the writer once avoids two authors editing `GlbWriter`.
2. **D (the on-device 3D scan) moves *up*, right after M4, with its device spike first.**
   - It has the largest unknown in the sprint (§7.1 main risk), so it must fail fast, before the sprint commits to it.
   - It reuses M4's placement and the phase 0 seams directly.
   - The spike needs **the user's depth-capable phone** (§13 D17).
3. **Templates (T) run in parallel with D, on Builder B.** They are deterministic, JVM-testable and isolated in new packages, and answer the core "does the sofa fit?" job (ui-ux §3.3 D).
4. **The RC and closed test start once M4, P1 and T are green.** D, SM1 and SM2 ship **as updates during the 14 days**; the internal and closed tracks accept updates.
   - The personal Play account needs **12 testers × 14 continuous days** before production (android-performance §6.7; `HANDOFF.md:38`). That is the longest calendar pole.
   - Billing E2E testing (P2) needs a Play-track build anyway.
5. **Smart Measure is split.**
   - **SM1** (hands-free plus naming) is small and low-risk.
   - **SM2** (auto-rectangles) needs the **bench-test data that doesn't exist yet** (`HANDOFF.md:68`) to set its gate, so it is the sprint's **last and stretch** item. Pro research §1 keeps all of Smart Measure free, so it does not block Pro.

| Phase | Builder A | Builder B | (Builder C, optional) | Exit gate |
|---|---|---|---|---|
| **0** (S) | `ArFrameListener` + `ArSessionHost` (`MeasureSession` implements it; no logic change); `GlbWriter`: unlit front + `glbVersion`, `writeParts`, `writeMesh` with tests | CI migration-test job (ADR-008), SHA-pinned actions | — | Existing tests green; the new GlbWriter tests green |
| **1** (L ∥ L) | **M4:** engine (ADR-002, fallback plane), `:ar` (`PlacementController`, `PlacementMath`, per-frame allocation fix), `:data` (`modelForPlacement` incl. the mesh fallback, K8), `app/place` | **P1:** `app/pro` (catalogue §4.3, reducer, `FeatureGate`, `EntitlementRepository`), `app/billing` (gateway, Play impl, fake, verifier), AdsManager → container + `AdsSdk` + gating (ADR-011), paywall, Settings restore/manage, backup exclusions, O1 fix, allowlist and baseline | `:engine/recon` against the synthetic tests (§7.1.11 engine rows) | M4: §3.9 green, **including placing an engine-made mesh GLB**, plus 30 fps on device. P1: §4.10 JVM tests green with the fake. |
| **2** (L ∥ M) | **v3 migration** (§9, at the start, one change). **D:** device spike (§7.1.10) → `DepthFrameTap`, `ShapeScanController`, governor, `saveMesh`, `app/scan3d` (recon too if there is no C) | **T:** `engine/furniture` (5 templates; Free 3), `app/furniture` guided flow, Gallery FAB, cap counter, gates; consumes `writeParts` and `saveTemplate` | finish recon, decimator tuning | D: spike criteria **pass** (else D stays behind a flag) and §7.1.11 green. T: §6.3 green. v2 → v3 and v1 → v3 green in CI. |
| **3: RC** (M) | Bench-test tuning (needs the user's bench data), `TESTING.md` §17/§18 | M5 leftovers, M6 perf/size (keep rules; skybox **but not the Place light**; Baseline Profile), release config | — | Release gate §6.2; internal-track upload; **closed test starts (14 days)** |
| **4** (during the closed test) | **SM1a** hands-free (`DwellTrigger`, `SteadinessFilter`). Then **SM2** (stretch): `FrameImageTap`, `engine/vision`, `RectangleFinder`, `commitDetected` | **P2:** Play E2E with licence testers (§4.10 device rows). **SM1b:** naming (`ObjectNamer`, `LabelMapper`, ML Kit dependency and manifest) | — | SM1 tests; SM2 bench gate §5.3; billing device checklist |
| **5** | Production v1.0, staged rollout. Whatever failed its gate (D or SM2) stays behind its flag. | | | Vitals |
| Later | SM3 (depth auto-box) | `:cloud`, WorkManager, Room v4; **backend owner: a new specialist** (§13 D9) | | §8.3 checklist |

### 12.3 Contracts between the builders

| # | Contract | Provider → consumer | Shape |
|---|---|---|---|
| K1 | Feature gating | B → A | `FeatureGate.observe/check(ProFeature)` (§4.3). A never imports `app/billing`. D checks `DEVICE_SCAN` **before** capture. |
| K2 | No ads on AR and Place | A → B | Place and the shape scan use `ArScreenAdGuard`, and never call `recordCompletedScan` from Place. Saving a 3D scan **does** count as a completed scan (like a proxy scan), with its after-save chance used only on the next non-AR screen. B's `MeasureVisitTrackerTest` asserts both. |
| K3 | Entry from Gallery | A ↔ orchestrator | Route `place/{id: Long}`; `placeDestination(onBack, onOpenPaywall)` |
| K4 | Placement model | A internal | `GalleryRepository.modelForPlacement(id): PlacementModel?` (PROXY_BOX, TEMPLATE, MESH_DEVICE) |
| K5 | Paywall entry | B → A | `onOpenPaywall(feature: ProFeature)` callback passed by nav, never a direct navigation from A's screens |
| K6 | Naming | A (as built, SM1) | `suspend fun ObjectNamer.suggest(jpeg: ByteArray, kind: ObjectKind? = null): NameSuggestion?`. `kind` (the scan type picked) filters out names that cannot be that object (OTHER and null accept every curated name). Null when the model is unavailable or not downloaded, on any error or 3 s timeout, or below 0.70 confidence; never throws (only the caller's cancellation propagates). A shows the chip; it applies only on tap. |
| K7 | Cloud (later) | B (`:cloud`) ↔ A (capture) | `CaptureStore.write(frame, pose, intrinsics)`; `CloudScanRepository.enqueue(captureDir)` |
| K8 | Every GLB is placeable | A (writer, `:data`) → A (M4) and B (T) | Origin `BOTTOM_CENTER` (floor items, templates, meshes) or `BACK_CENTER` (wall items). `extras.{widthM,heightM,depthM}` = the GLB's own bounding box, plus `glbVersion = 2` and `modelKind`. A mesh is never regenerated; it falls back to the proxy box (ADR-014). |
| K9 | GLB writer API | A → B | `GlbWriter.writeParts(parts: List<Part>, palette, origin, name): ByteArray` and `writeMesh(mesh: TriMesh, origin, unlit, extras): ByteArray`, frozen at the end of phase 0 |
| K10 | v3 persistence | A → B | `GalleryRepository.saveTemplate(TemplateDraft(templateId, params: Map<String, Double>, w, h, d: Dimension, label))` and `saveMesh(MeshDraft(...))`. Both go through the budget check and return the id. |
| K11 | Recon API (if Builder C) | C → A | `:engine/recon` types exactly as in §7.1.4. C owns only `engine/src/*/kotlin/com/spaceview/engine/recon/**`. |

**Builder briefs must include** (FRIDAY §5):
- the docs to read (this file's section for their milestone)
- file ownership exactly as in §12.1
- the lock rule: `mkdir .build.lock`; remove it only if you created it; stale after 20 minutes
- verification, run with the lock:
  - Builder A: `:engine:test :engine:koverVerify :ar:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest`
  - Builder B: `:app:testDebugUnitTest :app:lintDebug`
  - the orchestrator does the full release build
- the report format of FRIDAY §6.1

---

## 13. Decisions the user must make

| # | Decision | Options | **Recommendation** |
|---|---|---|---|
| D1 | Order inside the sprint | As suggested, or §12.2 | **§12.2:** phase 0 seams → M4 ∥ P1 → the 3D scan (spike first) ∥ templates → RC and the closed test → SM1, P2 and SM2 (stretch) as updates during the 14 days. Cloud last and gated. |
| D2 | How Pro purchases are verified in v1 | Client-only with a signature check / build a backend now | **Client-only with a signature check** (ADR-004), accepting APK-patch piracy. **No cloud credits** until the backend exists. |
| D3 | Offline grace period | 3 / 7 / 14 / 30 days | **7 days** from the last confirmed Play check |
| D4 | What happens when Pro lapses | Hide over-limit items / block only new actions | **Block only new over-limit actions.** Never hide or lock existing user data (ADR-005). |
| D5 | Free or Pro: Smart Measure | Free / Pro | **Free**, all of it (hands-free is an accessibility feature; auto-rectangle reduces error). This matches pro-monetization.md §1. The other limits in §4.3 are taken from that research. |
| D6 | Fit verdict strictness | RSS (more "Fits") / linear (more "Tight") | **Linear** (ADR-002), until the bench test proves the ± coverage |
| D7 | An ad after Place (M3 gate §7.4) | Carry a deferred ad / never | **Never.** Place and anything started from it never owe an ad. |
| D8 | Saving placements ("room profiles") | M4 / later as a Pro feature | **Later** (anchors can't survive restarts anyway; it needs Cloud Anchors or re-localisation) |
| D9 | Cloud: hosting, region, sign-in, backend owner | — | **GPU and storage** per 3d-capture.md: **Modal L4 + Cloudflare R2, self-hosted COLMAP**. **API + ledger:** one small container anywhere. **Google Cloud only for Pub/Sub** (RTDN delivery, pushed to the API) and the Developer API service account. **Sign-in:** anonymous + Google link through Credential Manager. **Owner:** a **new backend specialist agent** (not Builder B). **Region:** confirm the DPDP cross-border position with the research (§5 risk 5). |
| D10 | Lifetime Pro (business.md lists ₹1,499) | Offer / defer | **Defer.** It complicates later cloud economics. Subscriptions only for v1. |
| D11 | Object names in Hindi | Show ML Kit's English labels / only a curated mapped list | **Curated list only** (no mixed-script UI) |
| D12 | Play Console actions only you can do | — | **Needed before P2:** (a) a payments profile for selling from a personal account, (b) create ONE subscription `pro` with base plans `monthly` (₹129), `yearly` (₹799, offer `trial7` = 7-day free trial) and `pass30` (₹149 prepaid), enable grace period + account hold, and put the licence key into the CI secret `PLAY_LICENSE_KEY`, (c) add licence testers, (d) recruit **12 closed-test testers**, (e) give me the privacy-policy URL and the support email for the release config. |
| D13 | Bench test | — | **Please run the `docs/TESTING.md` accuracy protocol on your phone(s) during M4.** The ADR-002 strictness, the SM2 gate and the tuned constants all depend on it. |
| D14 | Cloud credit model (later) | Consumable packs / credits included in Pro / both | pro-monetization.md §3 recommends **monthly included credits plus packs, charged only on success**. That works with the §8 ledger (reserve, commit, release). Accept it once 3d-capture.md gives the real cost per scan. |
| D15 | A third builder for `:engine/recon` | Two builders / add Builder C | **Add C if you can afford the tokens.** It is pure JVM work in a new package, with no file overlap, and takes the biggest maths block off Builder A's critical path. The build lock still allows only one Gradle run at a time, but `:engine:test` is the cheapest one. |
| D16 | Remote Config for limits and the price A/B (research §8) | Firebase Remote Config now / code constants now, remote later | **Code constants for v1** (§4.3). Firebase means a new SDK, start-up work and Data safety rows. |
| D17 | On-device 3D scan: device and gate | — | **Needed for the phase 2 spike:** tell me which of your phones support ARCore Depth (ideally one with a ToF sensor and one without). If the spike's exit criteria (§7.1.10) fail, the scan stays behind a flag and the release goes ahead without it. **Shipping:** as **"Experimental: approximate 3D shape"**, Free **5 in total** (user decision 2026-11-23; was "3 saved") and Pro unlimited. **Spike phone: Google Pixel 10a** (Depth API, no ToF). Its dimensions always come from the measured box. |

---

## 14. Not checked and sources

**Not checked:**
- **No builds or tests run.** I ran no Gradle build and no test, as instructed. Every test count quoted comes from `HANDOFF.md` and `M3-gate.md`, not from this session.
- **Code not read in full.** `MeasureSession.kt` lines 450–620 and 645–1000; the `:app` screen files beyond those named; `MeasureViewModel`, `ScanViewModel`, `GalleryItemScreen`, `SettingsScreen` and `HomeScreen` bodies; and engine files other than `FitVerdict`, `GlbWriter` (partial) and `Shapes` (signatures). R1–R5 do not depend on those, but a variant of R3 (more per-frame allocations) may exist there.
- **3D scan specifics, unverified (no device):**
  - whether raw depth is available under `DepthMode.AUTOMATIC` on the bench phones
  - the depth image's alignment to the CPU image and its typical resolution per phone
  - gltfio support for `COLOR_0` vertex colours, `doubleSided` and (for a later size lever) `KHR_mesh_quantization`
  - the real fusion, marching-cubes and decimation timings in ART
  - thermal behaviour over 90 s of depth + AR

  The ns-per-update and timing figures in §7.1 are estimates; the spike (§7.1.10) exists to measure them.
- **3d-capture.md** was cross-checked for its on-device (§7) and cloud stack (§1–2) sections only. I did not audit its cost maths (≈ $0.45 per scan) or its licence table.
- **Device-only, unverified:**
  - whether SceneView 4.65 provides a default indirect light when lights are `null`
  - whether gltfio honours `KHR_materials_unlit` and generates mipmaps for `minFilter 9987`
  - the actual frame time
  - the DataStore read latency at start
  - ML Kit model-download behaviour on Indian Play-services versions
- **Size figures marked [est.] are estimates.** Only ML Kit's 200 KB / 5.7 MB come from Google's page. The CI bundletool gate is the authority.
- **Play policy details** (Data safety rows for billing and ML Kit, account-deletion scope for anonymous accounts) need checking against the live policy at submission time.
- **pro-monetization.md** landed mid-way. Its feature split, product IDs and limits are in §4.3, and its differences from this design are listed there. I did not audit its pricing or fee figures (Pro ₹129/mo, ₹799/yr, ₹149 30-day pass).
- **Lint on androidTest.** I did not verify whether `:data:lintDebug` parses (without executing) the androidTest sources. R2 stands either way, because nothing **executes** the migration test.

**Sources consulted this session:**
- Google Play Billing Library release notes (PBL 8.0 removals and auto-reconnection; 8.1 minSdk 23; 9.0 `BILLING_UNAVAILABLE` for a blocked Play Store and target 35; 9.1.0, 2026-06-18): https://developer.android.com/google/play/billing/release-notes
- ML Kit image labelling on Android (unbundled `play-services-mlkit-image-labeling` ~200 KB against bundled ~5.7 MB; `com.google.mlkit.vision.DEPENDENCIES = ica`; 400+ labels; ModuleInstallClient): https://developers.google.com/ml-kit/vision/image-labeling/android
- Project docs and code as cited inline (`file:line`).
