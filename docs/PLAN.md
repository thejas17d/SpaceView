# SpaceView — Workflow & Execution Plan (v1, Android)

Status: **DRAFT — awaiting user approval. No development until approved.**
Inputs: `docs/research/{measurement-engine,ui-ux,business,android-performance}.md`.

## 1. Product scope for v1 (Play Store)
| In v1 | Later |
|---|---|
| Measure: point-to-point distance, height, rectangle (W×H + area) with ± and Good/Fair/Rough badge | Room/floor plan, PDF export (Pro) |
| Calibration with bank card / A4 sheet | iOS (ARKit + LiDAR, Object Capture) |
| Scan object → "measured proxy" (W×H×D + rectified front photo → true-size textured GLB) | Full photogrammetry |
| AR Gallery (local, offline) with edit, rename, share (WhatsApp first) | Cloud sync, accounts |
| Place in room at locked true scale + Fits / Tight / Too big verdict | Retailer catalogue in app |
| Wall fallback (tap floor–wall line) for plain walls | Non-AR fallback (photo + card) — v1.1 |
| AdMob: capped interstitial after Save→Done, inline banners on non-camera screens | Rewarded ads, Pro (₹99/mo, ₹599/yr) |
| English + Hindi; cm / in / ft-in, context-aware | More Indian languages |
| — | B2B "does it fit?" web widget (no ads) |

Honest accuracy claim in-app: "typically ±2% (about ±1–2 cm at 1 m)"; every result shows its own ±.

## 2. User workflow
1. **First run:** 3-screen onboarding → camera permission → coaching ("move phone slowly") → optional calibration.
2. **Measure:** crosshair at centre, big "+" to drop points, freeze-frame + magnifier to adjust, result with ± and badge + one improvement tip → save to History.
3. **Scan object:** pick type (frame/TV/appliance/box) → mark front rectangle (corners, edge-snap) → depth (tap back edge, or enter manually for wall-hung items) → auto-captured, rectified front photo → review/edit dims → Save to Gallery → Done (ad slot, capped).
4. **Place in room:** Gallery → item → "Place in my room" → detect floor/wall (or wall fallback) → item at true size, scale locked → move/rotate → Fits/Tight/Too big against a measured space → screenshot/share.

## 3. Architecture
Native Kotlin, Jetpack Compose, ARCore 1.57, SceneView/ARSceneView 4.65 (Filament). Gradle modules:

| Module | Type | Responsibility |
|---|---|---|
| `:engine` | pure Kotlin/JVM | Geometry, robust plane fit (RANSAC), sample averaging & outlier rejection, uncertainty model, calibration scale factor, unit formatting, fit verdict, GLB box writer. 100% JVM-unit-tested (fast CI). |
| `:ar` | Android lib | ARCore session lifecycle, tracking-quality gate, hit-tests + depth refinement, anchors, frame→engine samples, thermal step-down. No UI. |
| `:data` | Android lib | Room DB (measurements, gallery items), GLB/JPEG/WebP files in app-private storage, cache eviction. |
| `:app` | Android app | Compose UI (screens S0–S15), navigation, manual DI, AdMob + UMP (lazy, background init), strings EN/HI. |

Data flow: ARCore frame → `:ar` (gate + sample) → `:engine` (estimate value ± σ) → ViewModel StateFlow (throttled, never per-frame into Compose) → UI. Saved items: `:engine` writes GLB → `:data` stores → placed via SceneView.

## 4. Performance & Play budgets (CI-enforced where possible)
- Download ≤ 15 MB arm64 (CI fails > 18 MB). R8 full mode + resource shrinking, no `filamat`, no ad mediation.
- Cold start ≤ 800 ms mid-range; Baseline + Startup Profiles generated on CI.
- Crash < 0.3%, ANR < 0.15%; AR steady 30 fps; ≤ 500–700 MB p99 memory.
- targetSdk 36, 16 KB page-aligned native libs (CI check), Data safety form, camera-only permission.
- Toolchain: Kotlin 2.4 (SceneView requirement), AGP 9.4.x, Gradle 9.6.1, JDK 21 — reuse Gridlark's CI pattern.

## 5. Agent roles (9 total)
| # | Agent | Role |
|---|---|---|
| 1–4 | Research (UI/UX, Measurement, Business, Android perf) | Done. Re-consulted when questions arise. |
| 5 | Builder A | `:engine`, `:ar`, `:data` — measurement accuracy, AR, storage |
| 6 | Builder B | `:app` UI, ads, strings, build/CI, size & startup tooling |
| 7 | Reviewer | Reviews each milestone diff: correctness, accuracy math, leaks, threading, policy |
| 8 | Final Reviewer + Judge | Milestone gate: checks against this plan & budgets, PASS/FAIL with required fixes |
| 9 | Documentation | `docs/` — concept, workflow, architecture, tech stack, accuracy protocol, Play listing text |
Orchestrator (Claude Opus 5.5): assigns tasks, resolves conflicts, runs builds, updates `HANDOFF.md` after every step.

Loop per milestone: Builders (parallel, separate modules) → local build + unit tests → Reviewer → fixes → Judge gate → CI green → handoff update → next milestone.

## 6. Milestones
| M | Deliverable | Gate |
|---|---|---|
| M0 | Repo scaffold, 4 modules, CI (build, tests, lint, size, 16 KB check), theme | CI green, empty app < 8 MB |
| M1 | `:engine` complete with tests (plane fit, averaging, uncertainty, units, GLB writer) | ≥ 90% engine test coverage on maths |
| M2 | Measure mode end-to-end (AR + UI + ±/badge + calibration + history) | **First installable build** for your phone |
| M3 | Scan → Gallery (proxy GLB, photo rectification, edit) | Scans saved ≤ 0.5 MB each |
| M4 | Place in room + fit verdict + wall fallback | 30 fps on mid-range |
| M5 | Ads (capped), Hindi, onboarding, settings, share, accessibility | Policy checklist passes |
| M6 | Perf pass (profiles, R8, size), docs, Play listing assets, release AAB | All budgets met → closed testing |
Then: accuracy bench test on your phone(s) with known objects → tune → production.

## 7. Accuracy test protocol (summary)
Ground-truth set: A4 sheet, bank card, door, a TV/frame, a 2 m wall span. Measure each 5× at 0.5/1/2/3 m in good and dim light. Pass: median error within displayed ±, ≥ 90% of readings inside ±; badge thresholds tuned from this data.

## 8. Decisions needed from user
1. AR Required (recommended for v1: only ARCore phones can install, no bad reviews) vs AR Optional (wider reach, needs non-AR fallback).
2. Ad cap instead of strict "1 interstitial per scan".
3. GitHub repo for SpaceView (new repo name/owner?).
4. "REA" meaning.
5. Play developer account: personal or organisation?
