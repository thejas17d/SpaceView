# M2 gate: measure mode (Final Reviewer + Judge)

Judge, 2026-11-03. Gate commit: **10f7abd** (fixes for the M2 review of fbe1479). I checked it against `docs/PLAN.md` §4, §6 and §7, `HANDOFF.md`, `docs/reviews/M0-M1-review.md`, `docs/reviews/M2-review.md` and `docs/TESTING.md`. No source was edited.

## Verdict: **PASS WITH CONDITIONS**

All 1 BLOCKER and 11 MAJOR findings from the two reviews are fixed in the code, not just claimed. The ± now always includes the tier floor and is expressed in camera pixels. Calibration is scoped to its plane and to its own ARCore session. Anchors are never detached after a pause. Interstitials only follow a save, and never over the camera.

Three small honesty gaps remain:
- **R1.** A rounding step in the composer can show a ± a few micrometres below the engine's bound.
- **R2.** The live AR value is visible while the user measures the known-length span, so the calibration can be made circular.
- **R3.** Home and Settings say "Calibrated on …" for a calibration that no longer applies.

Each is a one-to-ten-line fix. Apply R1–R4 and rebuild the debug APK before the user does the bench test (PLAN §7). No new review round is needed: the orchestrator can verify the fixes with the tests named below.

### How this was verified
- I read every changed file in `git diff fbe1479 HEAD`, plus the unchanged code paths each fix depends on.
- **Probe test** (`ar/src/test/.../TmpGateProbeTest.kt`, deleted after the run):
  - Setup: 20,000 random readings (all modes, all tiers, half with an in-scope calibration, worker-style endpoints using `AimModel`).
  - Each reading was formatted in every `MeasureContext` × `UnitPreference`, 400,740 checks in total.
  - Each displayed ± was compared with the **unrounded** engine `bound95`.
  - Results are quoted under R1.
- **Unit tests** at HEAD, all green: engine 163, ar 40, data 14, app 43. `:engine:koverVerify` passes.
- **Release APK** at HEAD (`app/build/outputs/apk/release/app-release.apk`, R8 full mode):
  - I inspected the R8 mapping, the merged release manifest and the dex (`dexdump`).
- I took and released the build lock for every Gradle run.

---

## 1. Review items (BLOCKER and MAJOR)

| # | Finding | Status | Evidence |
|---|---|---|---|
| M2-B1 | Aim term in view pixels; tier floor never applied | **FIXED** | `AimModel.kt` gives the aim error in VGA-normalised camera-image pixels (2 px; the measured value is about 1.5) and the freeze adjust in still pixels. `MeasureSession.kt:338-341,713` reads `FrameReaders.imageIntrinsics` (CPU image). `Uncertainty.kt:246-251`: `randomVariance` always adds `floorLengthSigma` for measured lengths, localised or not. The probe found **0** readings with ±95 below `absFloor95`. `M2ReviewArTest.bankCardAt30cm…` shows the card at 30 cm as ≥ ±1 cm and ROUGH on every screen size. |
| M2-M1 | ± rounded down | **FIXED in `Units`**, but see R1 | `Units.kt:151-176` rounds up with `ceilTo`, including the smallest fraction step ≥ ±. Probe: formatting the unrounded engine value gave **0 / 400,740** violations. The remaining shortfall comes from the composer (R1). |
| M2-M2 | Calibration applied across surfaces | **FIXED** | `MeasurementComposer.kt:229-231,260,286-290` → `ScaleCalibration.appliesToPoints` (`Calibration.kt:84-97`): every point must be within 5 cm of the plane, normals within 10°. Tests cover floor→table and floor→wall (engine and ar). Rectangle calibrated flag `:268` matches the scope `Shapes` applies. |
| M2-M3 | Cross-session or permanent calibration | **FIXED**, with a status-text gap (R3) | A new UUID is made per `onSessionCreated` (`MeasureSession.kt:272`). `CalibrationRecord.applicableIn` (`MeasureSettingsRepository.kt:64-65`) applies only for the same session id and when not stale. Legacy M2 records are never applied. Settings has *Clear calibration*. The "Calibrated" chip and the result-card date show only when `reading.calibrated` is true (`MeasureHud.kt:95,138`). |
| M2-M4 | Fitted surface replaces a nearer hit | **FIXED** | `SurfaceChooser.kt:38-48`: a fit is used only within max(2 cm, 3%) of the raw hit. `LocalSurfaceModel` depth band `:65-66,133`. Tests `boxTopIsNotReplacedByTheFloorBehindIt` and `inconsistentFitFallsBackToTheNearerHit`. |
| M2-M5 | Anchor detach races `Session.close()` | **FIXED** | Detach happens only in `onSessionPaused` (`MeasureSession.kt:293-296`), which runs on main before SceneView posts pause/close, and in `detach()` guarded by `live && !closed`. `close()` (`:534-546`) never touches an anchor or the session. Still needs the device check in R4. |
| M2-M6 | Interstitial on every Measure exit, over the camera | **FIXED** | `MeasureVisitTracker`: an ad is possible only if something was saved in this visit, keyed by the back-stack id. The chance is used by a RESUMED non-AR screen, after waiting until no AR screen is composed (`AdsManager.kt:270-283`), and expires after 5 s. No preload while AR is in the foreground (`:194,292`). `AdPolicy` also blocks `CAMERA_AR`. 114 lines of tests. |
| M0M1-1 | ± is jitter-only (32% coverage) | **FIXED** | Endpoints are always localised, or get the tier stand-in, plus the floor (`Uncertainty.kt:241-262`). |
| M0M1-2 | Autocorrelation under-covers | **FIXED** | `PointEstimator.kt:194-208`: bias-corrected ρ, cap 0.97, Student-t factor. |
| M0M1-3 | Rectangle badge ignores geometry | **FIXED** | `Shapes.kt`: Z-order input reordered, convexity / planarity / squareness checks, ROUGH when inconsistent (`:51-53`). The UI shows a skew warning (`ResultCards.kt:108-116`). |
| M0M1-4 | Strip plane fits | **FIXED** | `PlaneFit.isWellConditioned`; `RansacConfig.rejectIllConditioned = true` by default, which `LocalSurfaceModel` uses. A normal-tilt term goes into `planeBiasRel` (`LocalSurfaceModel.kt:179`). |
| M0M1-5 | No coverage tool | **FIXED** | Kover 0.9.11 with a 90% line bound; `koverVerify` is in CI and green locally. |

### MINORs spot-checked (crash, policy, honesty)

| # | Item | Status |
|---|---|---|
| M2 m1 | Saved value ≠ shown value | FIXED: `onSave(reading, …)` saves the card's own `Reading` (`ResultCards.kt:172`, `MeasureViewModel.kt:101-119`). |
| M2 m2 | Save while points are not tracking | FIXED (`MeasureHud.kt:140`). |
| M2 m3 | Worker overwrites main-thread state | FIXED: generation counter, and the worker merges only its own fields (`MeasureSession.kt:743,756-773`). |
| M2 m4 | Pre-loss samples committable | FIXED: stabiliser reset on NOT_TRACKING; no candidate unless quality is TRACKING (`:717,734`). Tested. |
| M2 m5 | Plane anchor on the wrong plane | FIXED (`MeasureSession.kt:593-609`, 1 cm check). |
| M2 m7 | Freeze capture throws out of `onFrame` | FIXED (`FrameReaders.kt:198-217` try/catch). |
| M2 m9 | I/O failures crash the app | FIXED: `MeasureViewModel.guarded`, `MeasurementRepository.guarded`, `recordCompletedScan` try/catch, DataStore `.catch`. Settings uses `runCatching` inside `launch`, which also swallows cancellation. That is harmless, but see M3-7. |
| M2 m10, m11 | Banner reloads; preload during AR | FIXED: the banner sits in a fixed slot outside the `LazyColumn` (`HistoryScreen.kt:114-124`); no preload during AR. |
| M2 m13 | FOREGROUND_SERVICE / WAKE_LOCK | FIXED and **checked in the release dex**. The only `newWakeLock` call sites are WorkManager's `Processor` foreground path and its SystemAlarm path (API ≤ 22; minSdk is 24), plus ExoPlayer's `WakeLockManager` inside GMA. ExoPlayer checks `checkSelfPermission(WAKE_LOCK)` first. `SystemForegroundService` is declared but never started and has no FGS type. |
| M2 m14 | configChanges | FIXED (`locale|layoutDirection|fontScale` added). |
| M2 m15 | SceneView blanket keeps | DEFERRED to M6. Documented in `proguard-rules.pro`. No crash risk. |
| M2 m16 | CI heap | FIXED (`CI_GRADLE_JVMARGS` 4g / 1g Metaspace). |
| M2 m17 | Calibrated chip out of scope | FIXED (keyed on `reading.calibrated`). |
| M2 m12 | Migration test harness | PARTIAL: `data/src/androidTest/.../SpaceViewMigrationTest.kt` exists, but it needs a device and has not been run. The canonical-record columns are still to come (M3). |

---

## 2. Honesty contract, end to end

| Check | Result |
|---|---|
| Shown ± ≥ the engine's 95% bound after rounding | **Not strictly.** 178 of 400,740 displays (0.04%) are short by **≤ 0.0086 mm**. For example, "74 in ± 1.7" against an engine bound of 1.70034 in. Cause: `MeasurementComposer.rounded()` rounds σ to the *nearest* 0.01 mm before `Units` rounds up. `Units` alone gives 0 violations. The size is negligible, but the invariant is the product's #1 promise and the fix is one line. → **R1** |
| Badge matches | Yes. The badge comes from the same (rounded) `Measurement` as the ±. The rectangle badge is forced to ROUGH on inconsistent geometry. The History badge is the stored quality of the saved reading. |
| Calibration only tightens when valid | Yes. `Calibrator` accepts only if the effective scale σ is < 0.7 × the prior. The floor stays in every calibrated length. Points off the plane, out of range, from another session or stale use the uncalibrated model. Tests cover floor→table, floor→wall, the 1 m span (TOO_NOISY) and two surfaces (NOT_ONE_SURFACE). |
| Nothing shown as "calibrated" when it isn't | **No.** During measuring, the chip, the result-card date and the History flag are correct. But **Home** (`home_calibrated_on`) and **Settings** (`settings_calibration_on`, `settings_clear_calibration_detail` "Stop using the saved scale correction") present a calibration that by design is never applied once Measure is left. → **R3** |
| Calibration cannot be made circular | **No.** `KnownLengthCard` hides the AR value after both ends are placed. But `LiveReadout` (`MeasureHud.kt:106-113,293-303`) shows the live AR distance with its ± while the user aims at the second end. A user who types that number gets k ≈ 1 accepted with a 0.3%-floor scale σ: a tighter ± with no correction behind it. `TESTING.md` §11 says this value is hidden. → **R2** |
| No reading without the tier floor | Yes. The probe found 0 floor violations across live, committed, calibrated, rectangle and height readings. Freeze-frame readings go through the same composer. The only number shown without a ± is the coarse camera→target distance pill. It is labelled as such, but in imperial it shows whole inches (M3-5). |

---

## 3. M2 gate criteria (PLAN §4, §6)

| Criterion | Status |
|---|---|
| First installable build | **Met.** `builds/SpaceView-M2-fixed-debug.apk` (37 MB debug) is byte-identical to `app/build/outputs/apk/debug/app-debug.apk` at HEAD. A debug-signed R8 release APK is also at `app/build/outputs/apk/release/app-release.apk` (19.5 MB universal). Rebuild both after R1–R3. |
| Distance / height / rectangle | **Met** (`MeasureMode`, `MeasurementComposer`, mode selector, result cards). |
| Calibration | **Met, via known length** (orchestrator decision in HANDOFF): 1–5 m tape span on one surface, card and A4 behind `CALIBRATION_SMALL_REFERENCES_ENABLED = false`. This deviates from PLAN §1 ("bank card / A4"). It is justified: the 1 cm floor makes small references always TOO_NOISY. Keep it documented until the bench test. |
| History | **Met.** Room v1, newest first, delete, reformat on unit change, guarded I/O, saves exactly the reading shown. |
| Size ≤ 15 MB arm64 download | **Met (estimate).** bundletool was not run locally. Deflate-9 of the release APK without `armeabi-v7a` gives **9.94 MiB**: dex 3.65, native 3.14, assets 3.07, which includes about 2.5 MB of unused SceneView materials and skybox for M6. Play's brotli download should be at or below this. CI's bundletool gate (18 MiB) is the authoritative check once a remote exists. |
| No ads on AR screens | **Met.** Banner only on History. Interstitials are blocked while any AR destination (Measure, Calibrate) is composed, and only follow a save. |
| CAMERA-first permissions | **Met.** CAMERA is the only dangerous permission. It is requested from `ArGate` after an in-context explanation, with an *Open settings* path once blocked. Onboarding asks for nothing. Merged release manifest: CAMERA, INTERNET, ACCESS_NETWORK_STATE, AD_ID, three ACCESS_ADSERVICES_*. This matches `ci/allowed-permissions.txt`. The Data safety form must declare the advertising ID (M5/M6). |
| Strings translated | **Met.** EN 195 keys / HI 194; the missing one is `translatable="false"`. No key is missing in `values-hi`. Three entries are identical on purpose: a format string, an a11y template and a licence name. I did not run lint locally (RAM); the CI run is still owed. |

## 4. First-launch crash review (release / R8)

Nothing blocking was found.
- **AdMob APPLICATION_ID:** the placeholder resolves in both build types. Debug and an untagged release use the Google test app ID (checked in the merged release manifest). Tag builds fail without real IDs.
- **Startup:** `Application.onCreate` is empty and `AppContainer` is lazy. The onboarding flag is read through DataStore asynchronously. UMP and `MobileAds.initialize` run after the first frame, with initialize on `Dispatchers.IO`. No main-thread disk I/O.
- **R8:** `SpaceViewDatabase_Impl` is kept under its name (Room reflection). The DataStore protobuf `GeneratedMessageLite` field-keep rule is in the R8 configuration. `com.google.ar.core.*` and `com.google.android.filament.*` are kept, via SceneView's blanket rules and ARCore's own. `MainActivity` and `SpaceViewApplication` are kept. ViewModels use initializer lambdas and routes are strings, so neither uses reflection.
- **Manifest:** AR Required (`camera.ar` required, `com.google.ar.core=required`), `configChanges` covers rotation, locale and font scale, `exported` is set on the launcher.
- **AR availability:** uses `checkAvailabilityAsync`, which never blocks the main thread. Install exceptions are mapped, not thrown.
- **Residual risk (device only):** the debug and release APKs have not run on a phone yet. TESTING §13 (release flow) and the R4 additions are the real proof.

---

## 5. Required fixes (conditions of this PASS)

| # | Where | Fix | Check |
|---|---|---|---|
| **R1** | `ar/src/main/kotlin/com/spaceview/ar/MeasurementComposer.kt:313-317` (`Measurement.rounded()`) | Round σ **up**, not to nearest: `val s = if (sigma > 0) kotlin.math.ceil(sigma / 1e-5 - 1e-9).coerceAtLeast(1.0) * 1e-5 else 0.0; Measurement(round(value, 1e-4), s)`. | Add an ar unit test, as in the deleted probe: random composer readings × all contexts and preferences → parsed displayed ± ≥ unrounded engine `bound95`. Expect 0 violations. |
| **R2** | `app/src/main/kotlin/com/spaceview/app/measure/MeasureHud.kt:106-113` (and `LiveReadout` `:287-303`) | While `calibration is CalibrationStep.Measuring && calibration.reference == null`, do not show the live value: pass a flag to `LiveReadout` that skips the `DimensionLabel`, but keep the target-distance pill. | Update TESTING §11 ("The measured AR value is not shown before you type") so it holds during aiming too. |
| **R3** | `app/src/main/res/values/strings.xml:19,201,202,204,206` and the same keys in `values-hi/strings.xml:19,201,202,204,206`; `HomeScreen.kt:146-152`; `SettingsScreen.kt:191-197` | Stop presenting a saved calibration as active. For example: `home_calibrated_on` → "Last calibrated %1$s. It applies only while that Measure screen stays open." (or show the default detail on Home); `settings_calibration_on` → "Last calibrated %1$s (only used in the session it was made in)"; `settings_clear_calibration_detail` / `_body` → "Delete the saved calibration record". Translate into Hindi in the same change, or lint fails. | Visual check on Home and Settings. |
| **R4** | `docs/TESTING.md` | (a) §2: add "Place 2 points, press back. Repeat 20× with no crash" (M2-M5 is device-only). (b) §11 line 64: rotation and font size **no longer** create a new session (`configChanges`), so a calibration *stays* after rotation. Only leaving Measure, *Try again* or the depth toggle drop it. Fix the expectation. (c) §12: "a test video interstitial plays normally (WAKE_LOCK was removed)". (d) The header says "from the GitHub Actions run", but there is no remote yet. Point to `builds/` and put the release APK there for §13. | n/a |

## 6. Recommended for M3 (not gating)

1. **Calibration UX.** Home's and onboarding's *Calibrate* open a separate AR screen whose calibration is lost on leaving. After *Use this calibration*, continue straight into measuring with a visible "calibrated for this session" banner, or start calibration from inside Measure only. Also drop the calibration on a tracking reset after a pause: the world frame can shift, which the plane check only partly catches.
2. **Bench test (PLAN §7) with the fixed APK.** Results from the fbe1479 APK understate error and must be discarded. Tune `AIM_PIXELS_VGA`, the tier floors, `CONSISTENT_ABS/REL`, the badge thresholds and the calibration acceptance from the data. Re-enable card/A4 only if the data justifies a smaller floor.
3. **M3 Room migration.** Add the canonical-record columns (device tier, ARCore version, calibration factor and scope, raw lengths) and run `SpaceViewMigrationTest` on a device.
4. **Onboarding copy** says "1 m or more", while the tip and known-length detail say "2 m or more works best" (1 m is usually rejected). Align on 2 m.
5. **Target-distance pill in imperial** shows whole inches (`MeasureFormatter.targetDistance`). Make it as coarse as the metric 0.1 m, for example to the half-foot.
6. **Per-frame allocations** (M2 minor 8) and the SceneView keep-rule narrowing plus unused materials and skybox (about 2.5 MB): M6, verified on a device.
7. **Settings writes** use `runCatching` inside `launch`. Use the same `guarded` helper as the ViewModels (rethrow `CancellationException`) and surface failures.
8. **Create the GitHub remote and get a first green CI run.** Lint (all modules plus `lintRelease`), the bundletool size gate and the 16 KB check have only been run locally or not at all.
