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
- **M1** in progress (Builder A).

## Log
- 2026-11-02: Plan approved. Launched Builder B (M0: scaffold, 4 modules, CI with size + 16 KB checks, git init — no commit/remote yet) and Builder A (M1: `:engine` maths + GLB writer + tests; waits for `.m0-ready` before building). If a session dies mid-way: check which of these exist — `settings.gradle.kts`, `.m0-ready`, `engine/src/test` — and re-run the missing part per docs/PLAN.md.
- 2026-11-02: Created folder; launched 4 research agents; 3 finished. Gave user timeline estimate (v1 on Play ≈ 4–6 weeks after approval).
