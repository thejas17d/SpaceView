# SpaceView — Handoff (read this first)

Living context file so any new session/LLM can continue. Updated after every step of work.

_Last updated: 2026-11-02 — Plan APPROVED. Development started: M0 (scaffold) + M1 (engine)._

## What SpaceView is
Android-first (Google Play) AR app: measure anything precisely; scan an object (e.g. photo frame in a café) → save to "AR Gallery" with exact dimensions → place it in your room at true size. Later: B2B for Indian retailers (Croma, Reliance Digital, furniture), iOS (ARKit/LiDAR).
Original brief: user's pasted spec (8→9 agents, orchestrator = Claude Opus 5.5). Accuracy is #1 priority. Time & space (size/perf) optimisation required for Play.

## Hard rules from the user
- Development approved by the user on 2026-11-02; follow `docs/PLAN.md`.
- Everything lives in `C:\Users\Kanishk\Desktop\Dev\SpaceView`.
- Keep this HANDOFF.md updated frequently (user asked: every ~5 min of work).
- 9 agents total: UI/UX research, Measurement research, Business research, Android perf/Play research, 2× Builders, Reviewer, Final Reviewer + Judge (merged), Documentation.

## Environment constraints
- Windows PC, no Mac, no Unity, no Android Studio, low RAM (no emulator + parallel builds).
- Build locally: `~/.android-toolchain/g.sh <gradle tasks>` (JDK 21, SDK 36/37, Gradle 9.6.1). `./gradlew` fails locally (no access to services.gradle.org) but works on GitHub Actions.
- Sibling projects to copy setup from: `Desktop/Dev/Gridlark` (CI workflow `.github/workflows/android.yml`), `Desktop/Dev/Tashlark`.
- GitHub pushes go through account `melinnsystems`; commit author `Kanishk <kanishkyoganand78@gmail.com>`. No SpaceView repo yet.

## Research status (docs/research/)
| File | Status | Key takeaways |
|---|---|---|
| measurement-engine.md | done | Mid-range Android ≈ ±2% (±1–2 cm @1 m, ±3–6 cm @3 m); LiDAR ±1 cm. Stack: Kotlin + ARCore 1.57 + SceneView/ARSceneView 4.65 (Compose+Filament). v1 capture = "measured proxy" (W×H×D + rectified front photo → textured GLB box). Tracking gating, 15–30 sample averaging, edge snap, A4/card calibration, always show ±. AR Optional; non-AR fallback = photo + reference card / manual entry. Risk: plain walls, depth of wall-hung items. |
| ui-ux.md | done | ± + Good/Fair/Rough badge; centre crosshair + big "+"; freeze-frame & magnifier; true-scale lock + Fits/Tight/Too big verdict; wall fallback (tap floor-wall line); no ads on camera/AR screens; EN+HI; context units; WhatsApp share; "precision instrument" visual style. Screens S0–S15. |
| business.md | done | Interstitial per scan conflicts with AdMob guidance → cap: none for first 3 scans, ≤1 per 3 scans / 3 min / ~4/day, only after Save→Done. No banners on camera. B2B edition has zero ad code. Lead B2B = "does it fit?" web widget; TVs/appliances 3D from spec sheets. Pro ₹99/mo or ₹599/yr. |
| android-performance.md | done | ≤15 MB arm64 download (CI fail >18), cold start ≤800 ms, crash <0.3%, ANR <0.15%, 30 fps AR. targetSdk 36; 16 KB page alignment (Play blocks updates from 1 Feb 2027). R8 full, no filamat, lazy ARCore/AdMob, Baseline Profiles on CI. SceneView 4.65 needs Kotlin 2.4 (Gridlark on 2.3.21). Recommends AR Required for v1. |

## Plan
`docs/PLAN.md` written (draft) and presented to user — awaiting approval + answers to its section 8 decisions.

## Decisions (user approved plan on 2026-11-02 — "yes everything you think is recommended")
- AR **Required** for v1. Ad **cap** (not 1 interstitial/scan). GitHub repo: `thejas17d/SpaceView` (user must create it + invite `melinnsystems`).
- "REA" = "Reverse Engineer Everything": study how top apps behave and adopt their approaches (design level only; never copy code/assets).
- Play account: **personal** → needs 12 testers × 14-day closed test before production.
- Estimate given: ~18–26 agent-hours to release-ready AAB (M0–M6), excluding on-device testing.

## Build rules
- PC has only ~2 GB free RAM: ONE Gradle build at a time. Lock = directory `SpaceView/.build.lock` (create with `mkdir`; if it exists, someone is building — do other work and retry; remove when done; stale after 20 min).
- `SpaceView/.m0-ready` exists once the M0 scaffold builds.

## Next steps
1. M0 (Builder B) + M1 engine (Builder A) running in parallel.
2. Then Reviewer → Judge gate → M2.

## Milestone status
- **M0 DONE** (Builder B, 2026-11-02 20:38): builds debug+release locally; arm64 Play download 7.22 MiB. Versions: Kotlin 2.4.21, KSP 2.3.12, AGP 9.4.1, Gradle 9.6.1, SceneView/arsceneview 4.65.0 (Filament 1.72.1, no filamat), ARCore 1.57.0, Compose BOM 2026.09.00, compileSdk 37 / target 36 / minSdk 24, JVM 21. appId `com.spaceview.app`. Manifest: CAMERA only (SceneView's INTERNET/VIBRATE stripped — M5 must re-add INTERNET for AdMob + update `ci/allowed-permissions.txt`); `com.google.ar.core`=required via tools:replace. CI: tests, lint, release APK/AAB, 16 KB check (`ci/check_native_libs.py`), permission allowlist, bundletool size gate 18 MiB. Git initialised, nothing committed, no remote; when committing run `git update-index --chmod=+x gradlew`. M6 todo: strip SceneView skybox/unused materials (~4 MB), remove EmojiCompat initializer.
- **M1 DONE**: `:engine` (Vec3/Pose, PointEstimator, PlaneFitter+RANSAC, Uncertainty/Quality, Calibration, Shapes, Units, FitVerdict, Homography, GlbWriter) — 119 JVM tests, all pass. Builder A agent stalled after finishing; orchestrator verified tests.
- **Commit f6ac685** = M0+M1 (local only, no remote yet).
- **In progress (2026-11-03):** Reviewer → `docs/reviews/M0-M1-review.md`; Builder A → M2 measure mode (`:ar` MeasureSession, `:data` Room history + DataStore settings, `app/measure|history|nav`, calibration); Builder B → M5 foundations (`app/ads` AdPolicy+AdsManager, `onboarding`, `settings`, `common` components, `values-hi`). Orchestrator then wires B's screens into nav, applies review fixes, Judge gate, commit.
- **M0+M1 review DONE** → `docs/reviews/M0-M1-review.md`: 0 blocker, 5 major, 17 minor. Key: ± was jitter-only (~32% coverage on small objects, not 95%); correlated-frame correction under-covers; rectangle badge ignores geometry (Z-order corners); thin-strip plane fits; no Kover. GLB passes Khronos validator; units/homography correct.
- **Engine-fix agent running** (engine/ only): adds `LocalisationInput(rangeMeters, focalLengthPx, pixelErrorPx=2.0)` + optional `localisation` param, per-tier ± floor, all engine MAJOR/MINOR fixes, Kover ≥90%, coverage target ≥93%. Builder A (M2) told to pass LocalisationInput from ARCore. Builder B told to fix CI MINORs (size gate: download 18 MiB / installed 30 MiB; keystore check; extra CI checks; add koverVerify).
- **M5 foundations DONE (Builder B, uncommitted):** `app/ads` (AdPolicy 12/12 tests, AdsManager, BannerAd), `settings` (SettingsRepository, SettingsRoute), `onboarding`, `common` components, `values-hi` (182 keys), CI fixes (size gate 18/30 MiB, keystore guard, dependency guard `ci/dependency_guard.py` + baseline `ci/release-dependencies.txt`, lintRelease, r8 metadata, koverVerify). play-services-ads 25.5.0 (GMA Next-Gen migration = M6 candidate). **Orchestrator TODO wiring:** (1) MainActivity after setContent: `AdsManager.get(this).startAfterFirstFrame(this)`; (2) SETTINGS route → `SettingsRoute(onRecalibrate→calibration, onBack)`; (3) onboarding `onCalibrateNow` → calibration; (4) on successful save: `recordCompletedScan()` once; (5) leaving Saved result via Done/Back: `maybeShowAfterSave(activity, NextAction.DONE){…}`; never from AR screens; (6) `BannerAd()` at end of History/Gallery content. New strings need Hindi or lint fails. Regenerate dependency baseline after dep changes.
- 2026-11-03 ~15:50 IST: usage limit hit mid-work; both Builder A (M2, was at lint stage) and engine-fix agent (was writing per-finding tests) resumed after reset. Lint tip: Metaspace OOM → run lint per module or raise `-XX:MaxMetaspaceSize`.
- **Engine review fixes DONE (uncommitted):** 154 tests pass, Kover 99.4% lines (koverVerify ≥90%, Kover 0.9.11). All 18 statistical coverage scenarios ≥95% (min 95.05%). New API: `LocalisationInput`, tier floors (`DeviceTier.absFloor95` 1 cm/1 cm/5 mm), `Shapes.rectangleFromRays`, PlaneFit conditioning, scoped `ScaleCalibration` (plane/anchor/rangeM, band 0.9–1.1), `FitRotation`. Integration notes sent to Builder A (M2). Tunable constants listed in the agent report — tune after bench test.
- Lock rule clarified: only remove `.build.lock` if your own mkdir created it.
- **M2 DONE (Builder A, uncommitted):** `:ar` MeasureSession (SceneView owns ARCore Session; 30 fps; plane+depth+feature hits; worker thread; LocalisationInput wired; own RANSAC wall plane), `:data` Room v1 history + MeasureSettingsRepository (DataStore file `spaceview_measure`; B's is `spaceview_settings`), `app/measure|history|nav`, calibration persisted with plane/anchor/range/session id (cross-session: range scope only, 0.8% floor). Tests: engine 154, ar 27, data 10, app 26. Est. arm64 download ~9.9 MiB. Real-device checklist (15 items) is in Builder A's M2 report → copy into docs/TESTING.md at M2 gate.
- **Orchestrator integration (2026-11-03):** fixed BannerAd non-local return (D8 crash with Kotlin 2.4); MainActivity starts ads after first frame; measure route: saving calls `recordCompletedScan()`, leaving measure pops back THEN `maybeShowAfterSave(BACK)` (ad never over camera); History has inline banner at list end; Metaspace raised to 768m; removed unused `home_sample_*/home_mode_*/home_coming_soon` strings. TODO: Settings toggles for depth + clear calibration (keys exist in MeasureSettingsRepository).
- Full local build GREEN (all unit tests, assembleDebug, assembleRelease). **Commit fbe1479** = M2 + engine fixes + M5 foundations + integration (pre-review). Unreviewed debug APK for the user: `SpaceView/builds/SpaceView-M2-debug.apk` (37 MB debug; `builds/` gitignored). Test checklist: `docs/TESTING.md`.
- **M2 review DONE** → `docs/reviews/M2-review.md`: 1 BLOCKER (focal length in view px not camera px → ± ~3× too small; tier floor skipped for localised points), 6 MAJOR (± rounded down; calibration across surfaces; stale cross-session calibration; fitted surface beats nearer hit; anchor detach races session close; interstitial on every Measure exit), 17 MINOR. **The fbe1479 debug APK given to the user shows ± too small — warn user; bench results from it understate error.**
- Fix agents running: Builder A (engine/ar/data/measure: B1, M1–M5 + minors), Builder B (ads/nav/settings/manifest/CI: M6 + minors + Settings depth toggle & clear calibration). Next: build → Final Reviewer+Judge gate → commit → new APK.
- **Builder B M2 fixes DONE (uncommitted):** MeasureVisitTracker (ad only if saved this visit; shown only once a non-AR screen is RESUMED; 5 s expiry), no preload during AR, History banner moved to fixed bottom slot (anchored adaptive), FOREGROUND_SERVICE + WAKE_LOCK removed (reasons in manifest comments), configChanges += locale|layoutDirection|fontScale, CI jvmargs 4g, Settings: depth toggle, calibration date/stale, Clear calibration. SceneView blanket keep rules → M6 task (documented in proguard-rules.pro). Add to device checklist: interstitial never over camera / never on plain exit; test ads still work without WAKE_LOCK.
- **Builder A M2 fixes DONE (uncommitted):** camera CPU-image intrinsics + fixed-angle aim error (`ar/AimModel.kt`); tier floor ALWAYS in quadrature; ± rounds UP in all units; calibration scoped to points on the calibrated plane AND same ARCore session only (UUID per session), stale after 30 days; `SurfaceChooser` (fit only wins if within max(2 cm,3%) of hit); anchors detached only in onSessionPaused; all minors. Tests: engine 163, ar 40, data 14, app 38 — green.
- **Decision (orchestrator, 2026-11-03):** with the floor, card/A4 calibration is always rejected → v1 uses **Known length** calibration (tape-measured span 1–5 m); card/A4 hidden behind `CALIBRATION_SMALL_REFERENCES_ENABLED = false` until bench test justifies a smaller floor. DONE: known-length flow (1–5 m, typed in cm/in, AR value hidden before typing; refused if ends on two surfaces = `NOT_ONE_SURFACE`; on NO_DEPTH phones 2 m+ needed, best from 1.2–1.5 m away). Tests app 43. Full build GREEN. **Commit 10f7abd** = all M2 review fixes. New APK: `builds/SpaceView-M2-fixed-debug.apk` (supersedes the M2-debug one). **M2 GATE: PASS WITH CONDITIONS** (`docs/reviews/M2-gate.md`): all 12 blocker/major verified fixed. Conditions R1 (round σ up in MeasurementComposer), R2 (hide live AR value during known-length calibration), R3 (don't show old calibration as active on Home/Settings) → Builder A fixing; R4 (TESTING.md) done by orchestrator. Gate §6 has M3 recommendations (calibration UX inside Measure, onboarding "2 m" copy, imperial target pill, Settings `guarded` writes, first CI run).
- **M3 started:** Builder B → `:data` gallery (Room v2 + migration, `GalleryRepository`, contract `data/.../gallery/ScanDraft.kt`: ObjectKind, Dimension(meters,bound95,isEstimate), ScanDraft), `app/scan/imaging/FrontPhotoRectifier`, `app/gallery` screens + routes GALLERY, GALLERY_ITEM/{id}. Next: fresh Builder A agent → scan capture flow (`:ar` + `app/scan`) producing ScanDraft; orchestrator adds Home entry points ("Scan object", "Gallery").
- 2026-11-03 ~20:50 IST: usage limit hit again; both fix agents resumed (A had verification green; B was waiting on A's compile fix).
- If resuming after a crash: run `git status` to see uncommitted M2/M5 work; build with lock; re-launch only the missing pieces.

## Log
- 2026-11-02: Plan approved. Launched Builder B (M0: scaffold, 4 modules, CI with size + 16 KB checks, git init — no commit/remote yet) and Builder A (M1: `:engine` maths + GLB writer + tests; waits for `.m0-ready` before building). If a session dies mid-way: check which of these exist — `settings.gradle.kts`, `.m0-ready`, `engine/src/test` — and re-run the missing part per docs/PLAN.md.
- 2026-11-02: Created folder; launched 4 research agents; 3 finished. Gave user timeline estimate (v1 on Play ≈ 4–6 weeks after approval).
