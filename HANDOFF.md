# SpaceView — Handoff (read this first)

Living context file so any new session/LLM can continue. Updated after every step of work.

_Last updated: 2026-11-17 — T templates + D0 3D Scan Lab GATE PASSED (conditions closed except R3 in progress); next: user runs the spike on the TD0 debug APK; D step 2 after spike._

## What SpaceView is
Android-first (Google Play) AR app: measure anything precisely; scan an object (e.g. photo frame in a café) → save to "AR Gallery" with exact dimensions → place it in your room at true size. Later: B2B for Indian retailers (Croma, Reliance Digital, furniture), iOS (ARKit/LiDAR).
Original brief: user's pasted spec (8→9 agents, orchestrator = Claude Opus 5.5). Accuracy is #1 priority. Time & space (size/perf) optimisation required for Play.

## Hard rules from the user
- Development approved by the user on 2026-11-02; follow `docs/PLAN.md`.
- Everything lives in `C:\Users\Kanishk\Desktop\Dev\SpaceView`.
- Keep this HANDOFF.md updated frequently (user asked: every ~5 min of work).
- All docs stay Markdown (.md). **`SpaceView.html`** (project root) is the consolidated Build Book of every doc, section by section; regenerate it with `python docs/build_html.py` after any doc change (and update the `MILESTONES` list in that script when a milestone changes state). Never hand-edit the HTML. The script auto-includes every .md in the repo (new research/reviews appear automatically) plus the FRIDAY skill from `../Chief Engineering Intelligence - FRIDAY.md`.
- 9 agents total: UI/UX research, Measurement research, Business research, Android perf/Play research, 2× Builders, Reviewer, Final Reviewer + Judge (merged), Documentation.

## Environment constraints
- Windows PC, no Mac, no Unity, no Android Studio, low RAM (no emulator + parallel builds).
- Build locally: `~/.android-toolchain/g.sh <gradle tasks>` (JDK 21, SDK 36/37, Gradle 9.6.1). `./gradlew` fails locally (no access to services.gradle.org) but works on GitHub Actions.
- Sibling projects to copy setup from: `Desktop/Dev/Gridlark` (CI workflow `.github/workflows/android.yml`), `Desktop/Dev/Tashlark`.
- GitHub pushes go through account `melinnsystems`; commit author `Kanishk <kanishkyoganand78@gmail.com>`. Repo: thejas17d/SpaceView (public).

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
- **M2 gate conditions R1–R4 CLOSED — commit ccebb01.** Bench-test APKs are built from a clean worktree `Desktop/Dev/SpaceView-build` (checkout of ccebb01) so in-progress M3 edits can't break them → DONE: `builds/SpaceView-M2-final-debug.apk` + `SpaceView-M2-final-release.apk` (lint clean, release 19.5 MB universal, debug-key signed). Older APKs deleted. Waiting on user's bench-test results (docs/TESTING.md) to tune constants. Remove the worktree later with `git worktree remove ../SpaceView-build`.
- **M3 started:** Builder B → `:data` gallery (Room v2 + migration, `GalleryRepository`, contract `data/.../gallery/ScanDraft.kt`: ObjectKind, Dimension(meters,bound95,isEstimate), ScanDraft), `app/scan/imaging/FrontPhotoRectifier`, `app/gallery` screens + routes GALLERY, GALLERY_ITEM/{id}. Next: fresh Builder A agent → scan capture flow (`:ar` + `app/scan`) producing ScanDraft; orchestrator adds Home entry points ("Scan object", "Gallery").
- 2026-11-03 ~20:50 IST: usage limit hit again; both fix agents resumed (A had verification green; B was waiting on A's compile fix).
- **2026-11-05 resume:** Builder B's M3 gallery work (data/gallery Room v2 + GalleryRepository, app/gallery screens, scan/imaging rectifier, FileProvider for share) verified: full build GREEN, tests engine 163 / ar 41 / data 35 / app 64, uncommitted. Launched **Builder A → M3 scan capture** (type picker Flat/Box/Other, AR outline via MeasureSession extension, auto front-photo capture + rectify, Review (edit dims → isEstimate), Save → recordCompletedScan → Saved screen [Place→gallery item | Share | Done(ad slot)], SCAN route, Home entry points Scan object + Gallery, EN+HI strings, JVM tests). Launched **Reviewer** on B's gallery code → `docs/reviews/M3-gallery-review.md`. Next: A finishes → Reviewer on scan flow → fixes → Judge gate → commit → M3 APK.
- **M3 gallery review DONE** → `docs/reviews/M3-gallery-review.md`: 0 BLOCKER, 3 MAJOR (TV diagonal ± understated; GLB overwritten before row update on edit; interstitial would fire on Saved→Place), 18 MINOR. Scans 212–455 KB (≤0.5 MB gate OK). Migration SQL matches 2.json; GLB true-scale; rectifier maths OK. Migration test + real Room DAO never executed → bench-phone checklist. **Builder B fix agent running** (gallery/data/imaging files; MAJOR 1, 2 + minors). **MAJOR 3 assigned to Builder A** (owns AppNavHost/ads). Rectifier input: CPU image ≤2–3 MP, corner 0 = top-left as seen.
- **M3 scan flow DONE (Builder A, uncommitted):** `:ar` ScanComposer, ImageProjection, FrontCaptureGate, Nv21; `MeasureMode.BOX(4)` (3 base corners + top), `Reading.boxHeight`, MeasureSession scan/front-capture APIs; `app/scan` ScanRoute (type picker + Saved), ScanArScreen, ScanReviewScreen, ScanViewModel, ScanDraftBuilder; `ArCameraView` extracted from MeasureScreen; Home rows Scan object + Gallery; `Routes.SCAN`; 45 scan_* strings EN+HI. Gallery MAJOR 3 fixed via `MeasureVisitTracker.leaveWithoutAd` (Place/Share never arm an ad). Tests: engine 163, ar 70, data 48, app 85; assembleDebug + lint green. Tunables: FrontCaptureGate MAX_ANGLE_DEG 15, HOLD 600 ms; preset depth frame 3 cm / TV 8 cm; CPU image ≤1280 px (texture often 640×480 → hi-res still = M6 candidate). Device-checklist items (10) in A's report → add to docs/TESTING.md at gate. Confirmed merged manifest has `<queries>` for com.google.ar.core. **Reviewer running on scan flow** → `docs/reviews/M3-scan-review.md`. Builder B gallery-fix agent still running.
- **Gallery review fixes DONE (Builder B, uncommitted):** MAJOR 1 (diagonal ± = (w·b_w+h·b_h)/d; no ± if a side is estimate) and MAJOR 2 (versioned model-<t>.glb + single UPDATE, old file deleted after commit) fixed; 16/18 minors fixed. **Schema v2 modified in place:** gallery `*_source` (MEASURED/PRESET/TYPED) + measured_* + calibration_factor; `measurements.calibration_factor` nullable. New APIs: `GalleryRepository.shareCopy`, `GalleryFormatter.diagonalShort`. Tests data 48, app 86. **Open for fix round:** MeasureViewModel must pass deviceTier/arcoreVersion/calibrationFactor/rawPrimaryMetres to `MeasurementRepository.save`; banner position decision not recorded in ui-ux.md. Waiting on scan-flow review.
- **M3 scan review DONE** → `docs/reviews/M3-scan-review.md`: 1 BLOCKER (auto-capture never fires: SceneView repeats camera timestamps at display rate → gate resets hold), 2 MAJOR (box front face chosen twice + wrong rule → wrong face photographed; capture after pause with unanchored corners), 16 MINOR. Measurement honesty, projection, NV21, ads all verified OK. **Builder A resumed to fix** B1, M1, M2, minors, + MeasureViewModel history fields (deviceTier/arcoreVersion/calibrationFactor/rawPrimaryMetres) + Review diagonal ± = Gallery's formula. Next: build → Judge gate → commit → M3 APKs via clean worktree.
- **Scan review fixes DONE (Builder A, uncommitted):** B1 (FrontCaptureController skips repeated timestamps; gate ignores dt≤1e-4), M1 (FaceChoice decided once, stored in ScanOutline; face camera is most squarely in front of), M2 (no capture while any point unanchored → REMEASURE hint) + 14 minors fixed; m9/m10 partial (live box label unlabelled; gallery says "Diagonal" for TV outer diagonal). ScanViewModel now SavedStateHandle-backed; History fields populated. Tests engine 163 / ar 83 / data 48 / app 97. Orchestrator full build+release+lint running; **Judge running M3 gate** → `docs/reviews/M3-gate.md`. Then: commit → clean worktree build → `builds/SpaceView-M3-*.apk`.
- 2026-11-05 ~16:40 IST: usage limit hit during the Judge gate; the Judge was resumed after reset. The full build was GREEN, but it took 54 min under memory pressure: tests engine 163 / ar 83 / data 48 / app 97, lint clean, release universal 19.65 MB. The shell was killed after the build finished, which left a stale `.build.lock`; the orchestrator removed it.
- **M3 GATE: PASS WITH CONDITIONS** (`docs/reviews/M3-gate.md`): every BLOCKER and MAJOR from both reviews verified fixed, with tests. R1 is closed: the TV diagonal is labelled "Outer diagonal (incl. bezel)" in the gallery, EN+HI. R2 is closed: the M3 device checklist is now `docs/TESTING.md` §16, and its first item is upgrading M2→M3 over existing History rows, since the migration has never run on a device. New MINORs N1–N8 are listed in the gate report and are not gating. The gate's §7 has the M4 recommendations. Committed as the M3 commit (see git log).
- **M3 APKs DONE** (clean worktree at 683eaf8, lint clean): `builds/SpaceView-M3-debug.apk` (37.7 MB) + `builds/SpaceView-M3-release.apk` (19.6 MB universal, debug-key signed). Install OVER M2-final to test the v1→v2 migration (TESTING.md §16 item 1). **GitHub:** `thejas17d/SpaceView` still not found (not created or melinnsystems not invited) — user asked about building on CI; waiting for repo, then add remote + push main (first CI run, may need fixes). Next milestone: M4 (place in room) — see M3-gate §7.
- **GitHub:** `origin` = https://github.com/thejas17d/SpaceView (public; melinnsystems has write access). main pushed at 683eaf8 on 2026-11-05. First CI run: https://github.com/thejas17d/SpaceView/actions/runs/42051294140
- **First CI run (683eaf8): FAILED only at "Size budget"**: every build, test, lint and 16 KB step before it passed. The cause was bundletool's `get-size` output ending in CR, so the numeric check rejected `10557749`. The measured arm64 Play download is **10.07 MiB** (budget 18). Fixed by stripping non-digits (`tr -dc 0-9`) in android.yml. The same push adds `SpaceView.html` + `docs/build_html.py`.
- **CI run 2 (dcda24b): GREEN.** Artifacts: spaceview-release (APK+AAB+mapping), spaceview-debug-apk. https://github.com/thejas17d/SpaceView/actions/runs/42053791835
- **FRIDAY skill:** `Desktop/Dev/Chief Engineering Intelligence - FRIDAY.md` (standalone; 9 source repos audited, no hidden bypass instructions; not installed into ~/.claude). Used as the operating manual for the FRIDAY agent.
- **New scope requested by user (2026-11-05), pending plan v2 approval:** (1) M4 place-in-room; (2) Smart Measure (auto rectangle detection, hands-free point drop for shaky hands, ML Kit unbundled object naming, later auto 3D box via depth); (3) furniture templates (sofa/L-sofa/bed/wardrobe/table, full 3D from guided measurements); (4) **Pro tier built now** (Play Billing, entitlements, ad removal); (5) **on-device rough 3D scan — moved INTO this sprint by user** (ARCore depth → TSDF → mesh); (6) later: cloud photo-real 3D scan (Pro, paid). Android only. Running: 3D-capture research → `docs/research/3d-capture.md`; Business → `docs/research/pro-monetization.md`; FRIDAY design → `docs/ARCHITECTURE-v2.md`. Next: present recommendations + plan v2 to the user → on approval, Builders start (M4 + Pro billing in parallel).
- **2026-11-05:** PLAN.md §9 **release quality bar** ("pristine") added, approved by the user as the official launch gate: accuracy ≥ 90% inside ±, crash-free ≥ 99.7%, ANR < 0.15%, start < 0.8 s, 30 fps, native-reviewed Hindi, ≥ 3-phone device set, Beta label fallback. Pro pricing from `docs/research/pro-monetization.md` liked by the user (₹129/mo, ₹799/yr, ₹149 pass, credits). Documentation agent is writing `docs/PROJECT-GUIDE.md` (plain-English explanation of every file plus a glossary) for the user. It appears first in the HTML.
- **3D capture research DONE** → `docs/research/3d-capture.md`.
  - **Cloud tech recommendation:** run our own pipeline on permissive open source. COLMAP (BSD-3) with ARCore pose priors makes the model true-scale; mvs-texturing (BSD-3) adds texture; meshoptimizer, xatlas and glTF-Transform (MIT) produce a GLB ≤ 5 MB. It runs on Modal L4 serverless GPUs, with storage on Cloudflare R2.
  - **Cost:** about $0.45/scan (≈ ₹38), or about $0.30 on RunPod. The KIRI API is $1/scan with a $500 minimum, so use it only as a quality benchmark.
  - **Licence traps:** Inria 3DGS / 2DGS / SuGaR / GOF / MILo are non-commercial; OpenMVS is AGPL; the RealityScan EULA forbids SaaS use.
  - **Sofa accuracy:** ±1–2 cm overall.
  - **On-device:** pure Kotlin TSDF at 1 cm voxels inside the M3 box, about 12 MB RAM and +50–150 KB APK. 76% of ARCore models support depth. The mesh is only a cross-check; the box stays the source of the dimensions.
  - **Implication for pricing:** ₹38/scan is near the high end of the business sensitivity range, so per pro-monetization §3.4, the monthly plan gets about 1 credit and cloud scans lead with the Studio tier and packs.
  - **TODO:** `measurement-engine.md` miscites arXiv 2204.01693. That paper measures ARCore feature points vs LiDAR, not the Depth API; fix the citation.
- **FRIDAY architecture DONE** → `docs/ARCHITECTURE-v2.md` (1,344 lines).
  - **Top risks:**
    - R1: FitVerdict uses root-sum-square vs the linear sum, so a borderline result says "Fits" when it should say "Tight".
    - R2: the migration test has never run (not in CI, no device).
    - R3: MeasureSession is 1,000 lines with main-thread native work and per-frame allocations; 30 fps is unmeasured.
    - R4: no entitlement seam; ads start unconditionally, and the backup rules include datastore.
    - R5: placed GLBs will render dark (lit materials, no lights).
  - **Order (§12.2):**
    - Phase 0: AR frame-listener seam + GlbWriter (unlit/multi-part/mesh).
    - Phase 1: M4 Place ∥ P1 Pro foundation (fake billing).
    - Phase 2: on-device 3D scan (device spike first, exit criteria §7.1.10) ∥ furniture templates.
    - Phase 3: release candidate → internal track; the 14-day closed test starts.
    - Phase 4, during the closed test: SM1 hands-free drop + naming, P2 billing E2E, SM2 auto-rectangles (stretch).
    - Later: SM3 depth auto-box and cloud.
  - **Delegation:**
    - Builder A: ar/data/engine GLB/recon, app/place, app/scan3d, Room v3.
    - Builder B: Pro/billing/ads gating/paywall/settings/CI/manifest, templates, ML Kit naming.
    - Optional Builder C: engine/recon (TSDF / marching cubes).
    - Orchestrator: wires AppNavHost.
  - **User decisions D1–D17 in §13;** the plan is awaiting the user's approval.
- **2026-11-06: USER APPROVED plan v2** (`docs/ARCHITECTURE-v2.md` §12.2 order) with ALL of FRIDAY's recommended decisions D1–D17 (§13).
  - **Product decisions:** Smart Measure free; lapse never hides data; 7-day offline grace; client-side purchase check, with cloud credits server-only; no ads after Place; 3D scan "Experimental" (3 free, Pro unlimited); linear-sum fit verdict; Builder C allowed.
  - **Builders launched:**
    - Builder A → Phase 0 (AR frame-listener seam, GlbWriter unlit/multi-part/mesh, R1/R3/R5 fixes), then M4 Place.
    - Builder B → P1 Pro foundation (FeatureGate/entitlements, fake billing, ads gating, paywall, backup-rules fix, migration test in CI = R2/R4).
    - Builder C → `engine/recon` (pure-JVM TSDF, marching cubes, decimation) for the 3D scan.
  - **Still needed from the user:** phone models (depth support) for the 3D spike, the bench test, and Play Console setup (D12) by Phase 3.
- **Phase 0 DONE (Builder A, uncommitted).** The contract is frozen in `docs/contracts/phase0.md`.
  - GlbWriter writeBox/writeParts/writeMesh with K8 extras; GlbReader.readInfo; TriMesh in engine/recon.
  - ArFrameListener/ArFrameDispatcher (:ar) + ArSessionHost (app/common/ar); pooled FrameSamples (R3); unlit photo face (R5).
  - FitPolicy LINEAR|RSS. The orchestrator told A to make **LINEAR the default** (D6).
  - Tests: engine 192, ar 86. App changes are unverified while Builder B's billing work breaks `:app` compile (expected, in progress).
  - Builder A is now on M4.
- 2026-11-06 ~13:00–16:10 IST: the usage limit hit all 3 builders mid-work (A at M4 Room v3, B at the Settings Pro rows, C at the recon scene tests). All three were resumed at 16:11, with no stale lock.
- **Builder C recon core DONE (uncommitted):** `engine/recon` (VolumeSizing, TsdfVolume, MarchingCubes, MeshSegmenter, VertexClusterDecimator, MeshExtent + crossCheck, ReconTypes).
  - **Tests:** engine 249 tests (52 recon); recon line coverage 99.6%.
  - **Synthetic accuracy:** sphere mean error 0.27 mm; 600×400×400 box within 3 mm; ± covers truth in 200/200 trials, but the ± is about 3.7× the worst error, so tune it on the bench.
  - **Performance:** JVM about 6.3 ms/frame with 0 allocations; sofa volume 12.44 MB.
  - **Deviations 1–6 accepted provisionally:** behind-band 0.5μ, the floor cut clips, QEM-point decimator, generated MC table, a conservative scale term, and the keepFraction meaning.
  - **Open for A's Phase 2:** writeMesh re-centring → GLB heightM about 1 cm less than MeshExtent H; the pipeline order is documented in `ReconSceneTest`.
  - **Doc:** C was asked to update ARCHITECTURE §7.1.4–7.1.5.
- **M4 DONE (Builder A, uncommitted):** Place in room (floor/table/wall, move/rotate/nudge, wall two-tap fallback, eye level, Check the fit → Fits/Tight/Too big); Room v3 `MIGRATION_2_3`; `modelForPlacement` (K4); K10 `saveTemplate`/`saveMesh`; Place strings in `strings_place.xml`. Tests: ar 104, data 63, 12 Place tests. Device checklist (12 items) is in A's report → TESTING.md §17 at the gate.
- **P1 Pro DONE (Builder B, uncommitted):**
  - **Pro code:** `app/pro` (catalogue, entitlement reducer with 7-day grace, `FeatureGate`, paywall) and `app/billing` (Play gateway, signature verifier, fake gateway for debug).
  - **Ads:** behind `AdsSdk`, never initialised for Pro.
  - **Robolectric migration test:** runs in `:data` on CI only; Windows-excluded because of an androidx.sqlite path bug.
  - **CI:** actions pinned by SHA; `PLAY_LICENSE_KEY` secret; app tests 217 (104 new).
  - **Size:** release universal +184 KiB. Billing pulls in 12 transitive deps, incl. play-services-location (check the merged manifest).
  - **Play Console IDs:** subscription `pro`, base plans `monthly` / `yearly` (offer `trial7`) / `pass30`, licence key → CI secret `PLAY_LICENSE_KEY`.
  - **Known gap:** the gallery cap is not enforced at scan entry or Save yet.
- **Orchestrator nav wiring:**
  - Gallery item "Place" → `openPlace(id)`; Saved → item → Place.
  - `placeDestination` and `paywallDestination` added; Settings `onOpenPaywall`.
  - Unused strings removed (settings_remove_ads, settings_coming_soon, settings_section_ads, gallery_place_soon).
  - Full build is running.
  - Reviewers running → `docs/reviews/M4-review.md` (A+C) and `docs/reviews/P1-pro-review.md` (B).
- **Full build after nav wiring: GREEN.** Tests engine 249 / ar 104 / data 63 (1 skipped) / app 217 (1 skipped); koverVerify, assembleDebug and lint (app, ar, data) all clean. `VertexClusterDecimatorTest` passed this run; it failed once in A's run, so it may be flaky, and the reviewer is investigating.
- 2026-11-06 → 11-16: there was a long pause (usage limit + re-login). Both reviewers were resumed on 11-16.
- **P1 Pro review DONE** → `docs/reviews/P1-pro-review.md`: 0 BLOCKER, 3 MAJOR, 7 MINOR.
  - **MAJOR 1:** AdMob can init for Pro after a stale cache or reinstall plus slow Play; needs a "settled" signal, capped at about 12 s.
  - **MAJOR 2:** clock rollback inside the 7-day window → offline Pro forever; needs a `lastSeenMillis` high-water mark.
  - **MAJOR 3:** the paywall sells a 20-item gallery cap that nothing enforces.
  - **Verified OK:** signature check fails closed, the fake gateway is R8-stripped from release, the backup exclusions, the CI SHA pins, and no location permission merged.
  - **Builder B resumed to fix all of it.** For MAJOR 3, B may minimally touch `GalleryDao` (count), `AppNavHost` scan entry, and `ScanViewModel.save` (backstop), and will list those files for Builder A.
- **M4 + recon review DONE** → `docs/reviews/M4-review.md`: 0 BLOCKER, 2 MAJOR, 11 MINOR.
  - **MAJOR 1:** the fit card's limiting axis and amount can contradict the verdict.
  - **MAJOR 2:** `saveMesh` rejects real recon output (GLB bbox vs measured extents; 1 cm drop and re-centring).
  - **Decimator failure:** judged not flaky; the likely cause is a half-edited tree during A's run.
  - **Fix rounds running:**
    - Builder A: MAJOR 1–2, MINOR 3/4/5/6/12/13. A must not touch GalleryRepository/GalleryDao/ScanViewModel until the orchestrator says B is done.
    - Builder C: recon MINOR 7–10.
    - Builder B: the P1 fixes.
  - **Orchestrator owns MINOR 11:** pass the template source to GalleryRepository in AppContainer after B finishes; templates don't exist yet, so it stays NONE until the T milestone.
- **Builder A's M4 review fixes DONE:** MAJOR 1 (limiting axis only among axes sharing the verdict), MAJOR 2 (`writeMesh(keepInputOrigin)`; MeshDraft `glbExtents` + `meshExtents`; the end-to-end recon→save→place test; the GLB is 304 KB at 15k triangles), and MINORs 3/4/5/6/12/13.
  - **Room v3 amended in place** with `mesh_glb_w/h/d_m`. That's OK, because no v3 APK has shipped (the last APK given to the user was M3 = v2).
  - **Tests:** ar 106, data 65, app 242. The engine passes except 2 of Builder C's in-progress recon tests.
  - **Rule for the 3D-scan screen:** it must decimate to ≤15k triangles before `saveMesh`.
- **Builder B's P1 review fixes DONE (verified 12:40, app 242 / data 65, lint 0 errors).**
  - **MAJOR 1:** ads wait for `EntitlementRepository.settled`, capped at 12 s.
  - **MAJOR 2:** a `lastSeenMillis` high-water mark (rollback beyond 5 min → Free until Play confirms).
  - **MAJOR 3:** `GallerySaveGate` (gallery 20, 3D scans 3), checked at scan entry and as a Save backstop with `GalleryFullCard`. A lapse never hides items. **Callers of `saveTemplate`/`saveMesh` must call the gate first.**
  - **MINOR 4 partial:** packageName is checked. The prepaid 31-day cut-off was rejected, because it would wrongly revoke cancelled-yearly users; replay is an accepted risk until the server exists (ADR-004).
  - **MINOR 5:** the licence key is validated at build time. The rest are fixed.
  - **TESTING.md §17** is now the Pro device checklist. Builder A's Place checklist goes into §18 at the gate.
  - Release universal 19.99 MB.
- **Builder C's recon MINOR 7–10 DONE.**
  - **Fixes:** saturation rule, `insideBox` wall drop, memory peak 49 → 18.9 MB, stronger tests.
  - **Tests:** engine 262 pass; recon coverage 99.7%.
  - **C5 revised:** scale term rel·L, no height inflation; ± 2.25–3.07× the p95 error. The FRIDAY gate decides.
  - **Unverified:** determinism; a back-to-back rerun is running in the orchestrator build.
  - **Machine memory is critically low:** Claude Code reaped C's waiter. Run `gradle --stop` before big builds.
- **Gate in progress (2026-11-16):**
  - The orchestrator's full build + `:engine:test --rerun-tasks` are running.
  - C is updating ARCHITECTURE §7.1.4–7.1.6.
  - The **FRIDAY gate** writes `docs/reviews/M4-P1-gate.md`.
  - Then: TESTING §18 (Place + 3D core) → commit → push → CI → M4 APK from a clean worktree.
- **M4 + P1 + recon GATE: PASS WITH CONDITIONS** (`docs/reviews/M4-P1-gate.md`).
  - All 5 MAJORs were verified fixed in the code. C5 ± revision ACCEPTED; the prepaid cut-off rejection UPHELD. No new BLOCKER or MAJOR; new MINORs N1–N7 are listed in the gate.
  - **R1 closed:** the KDoc now says `mesh_glb_*`.
  - **R2 closed:** the two-distance carton bench was added to ARCH §7.1.10, and gate §7 was merged into TESTING.md §18.
  - **R3 OPEN:** the first CI run must show SpaceViewMigrationTest 3/3, the size gate and the 16 KB check. Then the user installs M4 over M3. **The Room v3 amendment window closes when the M4 APK leaves the team.**
  - **Full build evidence:** engine 262, ar 106, data 65, app 242, all green; `:engine:test --rerun-tasks` green (determinism); release universal 19.99 MB.
- **R3 CI part CLOSED:** CI run 43846146989 for de63706 is GREEN.
  - `SpaceViewMigrationTest` 3/3 passed on Linux (v1 row survives all migrations, 1→2, 2→3).
  - arm64 Play download is **10.37 MiB**, installed 18.15 MiB, and all 14 native libraries pass the 16 KB check.
  - **M4 APKs:** `builds/SpaceView-M4-{debug,release}.apk`, built from a clean worktree at de63706.
  - **The user was told to install over M3** (R3 device part, TESTING §18 item 1). **From this point the Room v3 amendment window is CLOSED: any schema change needs v4.**
- **Phase 2 started (2026-11-16), fresh agents:**
  - **Builder B → T furniture templates:**
    - `engine/furniture` parametric templates; registry wired as TemplateModelSource (M4 MINOR 11)
    - ProCatalog core-template fix (gate T1)
    - `app/furniture` picker → guided measure → preview → review → save, gated
    - Home entry; `strings_furniture`
  - **Builder A → D spike harness ONLY:** a debug-only "3D Scan Lab" (capture → recon pipeline in ReconMemoryTest order → metrics CSV/JSON + mesh viewer), plus MINORs N1/N3/N4/N5. Per gate §8 D1, the user-facing D screens wait until the spike passes on the user's phone.
  - **Open user decisions:** (1) 3D-scan Free rule: "3 saved at a time" (as built) vs "3 lifetime"; (2) phone models for the spike; (3) M4 install over M3 + TESTING §18 item 1.
- **Builder A: D spike harness DONE (uncommitted).**
  - **Lab:** a debug-only 3D Scan Lab (`app/src/debug/.../scan3d`, `Scan3dLabActivity`; Settings → debug panel).
    - `:ar` pieces: DepthCaptureListener (RAW/HARDWARE/SMOOTHED, pooled slots, ≤5 Hz worker), ShapeScanGovernor, ShapeScanPipeline (ReconMemoryTest order), FloorEstimator.
    - Metrics go to `files/scan3d_lab/runs.csv` + `<runId>/metrics.json` + `mesh.glb`, shareable.
    - The spike protocol text is in the lab.
    - Release contains no lab code (verified via dex + usage.txt).
  - **MINORs:** N1 (FloorEstimator), N3 (aabbC extras → wall placement uses the real centre), N4 (startScan back-stack check) done; N5 partial.
  - **Tests:** engine 281, ar 125, data 65, app 282.
  - **Orchestrator decision:** the spike runs on the DEBUG build. Debug is slower than release, so passing timing on debug is conservative. If the timing criteria (§7.1.10 items 2–4) FAIL on debug, add a release-signed `lab` build type and rerun them before concluding. Accuracy and coverage (item 1, R2) are build-independent.
  - **D integration must add the mesh path** to the production gated store (B's `GatedGalleryStore` is template-only).
- **Builder B: T furniture templates DONE (uncommitted).**
  - **Templates:** sofa / bed / wardrobe (Free); L-sofa, dining table, dining chair, TV unit, bookshelf, study table (Pro). All in `engine/furniture`, with property tests showing the union = W×H×D.
  - **Gating and wiring:** `Allowance.Only` (core templates); `GatedGalleryStore` (template gate → checkItem → saveTemplate); registry wired (M4 MINOR 11); `TemplateModelSource.palette()`.
  - **App flow:** picker → guided measure → 3D preview → review → save → Saved; Home "Furniture" row.
  - **Tests:** engine 281, app 282.
  - B's final lint was killed by low memory, so the orchestrator is re-running the full build.
  - **Note:** builders share one scratchpad, and A rewrote B's build.sh, so the lock-owner labels are unreliable. Fresh agents should use their own script names.
  - **Reviewer running** → `docs/reviews/T-D0-review.md`.
- **Phase 2 full build: GREEN.** engine 281 / ar 125 / data 65 / app 282 (0 failures); koverVerify OK; app lint 0 errors, 6 warnings; ar and data lint clean; release universal 20.12 MB (+134 KB vs M4).
- **T-D0 review DONE** → `docs/reviews/T-D0-review.md`: 1 BLOCKER, 1 MAJOR, 7 MINOR.
  - **BLOCKER:** the governor's 33.0 ms threshold sits below the 30 fps 33.33 ms period, so every scan drops to 2 Hz.
  - **MAJOR:** the processing PSS baseline excludes the volume, under-reporting by up to 12.6 MB.
  - **Templates (T):** APPROVED WITH WARNINGS; all gate §8 T1–T6 requirements met.
  - **Fix rounds running:** Builder A on the B1 + M2 + MINORs 3/5/6/7/8/9 (lab); Builder B on MINOR 4 (golden tests per template id).
  - **The user's spike run must wait for the B1 fix.**
- **Builder B MINOR 4 DONE:** `TemplateGoldenTest` (39 SHA-256 pinned cases, captured before the refactor and still matching); per-template constants objects. Engine 282, app 286. The immutability rule was added to ARCH §6.
- **Builder A T-D0 fixes DONE.**
  - **B1:** the governor steps down only on dropped frames (1.5 × the configured frame period).
  - **M2:** PSS adds the volume back, the baseline is taken after AR closes, and the local `vol` is gone.
  - **MINORs:** 3 (plane-backed floor first), 5 (too warm to start), 6 (the c2/c3 columns), 7 (image sizes plus `aspect_mismatch`), 8 (Back during Starting…) and 9 (KDoc).
  - **Orchestrator verification on 2026-11-17, run in the FOREGROUND in two steps to avoid the low-memory reaper:**
    - Tests: engine 282 / ar 129 / data 65 / app 286, 0 failures.
    - Kover, assembleDebug/Release and lint pass (app lint: 0 errors, 6 warnings).
    - Release dex has 0 "Scan3dLab" refs; debug has 16.
    - Release universal APK: 20.12 MB.
  - **FRIDAY gate running** → `docs/reviews/T-D0-gate.md`.
- **T-D0 GATE: PASS WITH CONDITIONS** (`docs/reviews/T-D0-gate.md`).
  - **Templates:** APPROVED. **Lab:** approved for the spike after G1.
  - **R1 CLOSED (orchestrator):**
    - G1: `MAX_AR_P95_MS = 1000/30 + 0.5`; `JANK_NS = 33_833_333`; new test `arFrameTimeCheckAcceptsASteady30fpsSession` (LabMetricsTest 10/10).
    - G4: the governor frame period is clamped to 30 fps.
  - **R2 CLOSED:** ARCH §7.1.10 now has the build policy, the debug-vs-lab table and the analysis rules (G3/G4/R2). G6 drift fixed (§7.1.6 slots/trySend, §4.3 `Allowance.Only`, §6.2 entry points). TESTING §19 added. HTML regenerated.
  - **R3:**
    - CI step added: "Debug-only code absent from release" (greps the release dex for Scan3dLab/scan3d_lab/FakeBillingControls; locally release 0, debug 32).
    - Commit + push + CI.
    - Then build `builds/SpaceView-TD0-debug.apk` from a clean worktree and record its SHA-256 + commit here.
  - **Open MINORs:** G2 (a table plane can beat an untracked glossy floor; decide from device item), G5 (nit).
- If resuming after a crash: run `git status` to see uncommitted M2/M5 work; build with lock; re-launch only the missing pieces.

## Log
- 2026-11-02: Plan approved. Launched Builder B (M0: scaffold, 4 modules, CI with size + 16 KB checks, git init — no commit/remote yet) and Builder A (M1: `:engine` maths + GLB writer + tests; waits for `.m0-ready` before building). If a session dies mid-way: check which of these exist — `settings.gradle.kts`, `.m0-ready`, `engine/src/test` — and re-run the missing part per docs/PLAN.md.
- 2026-11-02: Created folder; launched 4 research agents; 3 finished. Gave user timeline estimate (v1 on Play ≈ 4–6 weeks after approval).
