# SM1 + M5-remainder gate: Smart Measure SM1, UMP/review/Help/About/Delete, accessibility, N2 (Final Reviewer + Judge)

FRIDAY, 2026-11-17. Gate tree: the uncommitted work on top of **13f3740**. It covers:
- **SM1 Smart Measure** (Builder A): the hands-free dwell drop, ML Kit naming, G2, accessibility in A's screens, and recording `CaptureMethod.HANDS_FREE`;
- **the M5 remainder** (Builder B): UMP consent, in-app review, Help/FAQ, About/OSS, Delete my data, the accessibility pass, N2, and the store-build guards;
- **both fix rounds** for `docs/reviews/SM1-M5-review.md` (3 MAJOR, 13 MINOR).

I checked it against `HANDOFF.md` (the Phase 3 entries), `docs/reviews/SM1-M5-review.md`, `docs/ARCHITECTURE-v2.md` §5 (with the as-built notes and the tick-off decision), §4 and §12.3 K6, `docs/PLAN.md` §9, `docs/TESTING.md` §17, §20 and §21, and `docs/research/android-performance.md` §6.3. No source was edited and no Gradle task was run; this file is the only write.

## Verdict: **PASS WITH CONDITIONS**

**All 3 MAJORs are FIXED and verified in the code or docs. All 13 MINORs are closed** (MINOR 15 by a heuristic whose residual risk is tracked by a device item). The fix rounds introduce **no new BLOCKER or MAJOR**.

I found **two new MINORs from the fix rounds** and one doc set:
- **G1:** the consent form can still open over an AR screen. The AR check runs when the request *starts*, not when the form is *shown*, so a user who opens Measure during the network round-trip gets the form over the camera. Hands-free is safe, because window focus suspends it. But TESTING §20 item 5 promises "never over Measure", and §21 item 8 tells testers to expect it there.
- **G2:** the new "+" tap guard is not reset by Undo or Clear. A deliberate "+" within 1 s of an auto-drop, after an Undo and a small (< 3 cm) correction, is swallowed silently. That is exactly the precision correction the guard should never block.
- **G3–G5:** doc drift (DOC).

There are three conditions, all small: **R1** and **R2** are code (a few lines each, with a test), and **R3** is docs, commit and CI.

**RC readiness:** the app is **feature-complete for the Phase 3 RC**, apart from the user's configuration and D step 2. On-device 3D scan is correctly *not* advertised: `ProCatalog.kt:124` has `DEVICE_SCAN shipped = false`, and the paywall lists only shipped benefits (`:136`). What remains is release work, not features (§5):
- device passes;
- M6 polish (EmojiCompat, the skybox, lint plurals);
- store artefacts (listing, privacy-policy text, known limitations);
- the user's secrets and Play Console set-up.

### How this was verified
- **Reading** (tests first, then code, then call sites):
  - **Tests:** `HandsFreeGatingTest`, `HandsFreeDropperTest`, `FloorEstimatorTest`, `SpokenTextTest`, `ObjectNamerTest`, `AnnounceThrottleTest`, and the `AdsConsentTest` case names.
  - **Code:** `HandsFree.kt`, `HandsFreeDropper.kt` (`AutoDropRules`), the `MeasureSession` tap guard, commit and `undo`/`clear` paths, the `MeasureScreen`, `ScanArScreen` and `FurnitureMeasureScreen` wiring, `FloorEstimator`, `SpokenText`, `AdsManager` consent, `AdsSdk.requestConsent`, `UserDataEraser`, `GalleryRepository.deleteAll`, `MlKitObjectNamer`, `LabelObjectNamer`, `AppLinks` with the Gradle guard, `ProCatalog`, and the EN/HI help, settings and smart strings.
- **Test results on disk** (a script over the result XMLs):

  | Module | Tests | Failures | Errors | Skipped | Results written |
  |---|---|---|---|---|---|
  | engine | 282 | 0 | 0 | 0 | 11-17 11:38 |
  | ar | 148 | 0 | 0 | 0 | 11-17 16:22 |
  | data | 71 | 0 | 0 | 1 | 11-17 15:42 |
  | app | 372 | 0 | 0 | 1 | 11-17 16:20 |

  These agree with the orchestrator's run.
- **Freshness:**
  - Every module's results postdate its newest source. The newest are ar 16:09, app 16:11 (including `app/build.gradle.kts`) and data 15:41; engine is unchanged.
  - The release APK (16:51) and the app lint report (16:51, 6 warnings) postdate every source. The ar lint report (16:56) and the data lint report (15:50) show "No issues found".
- **Release APK** (read-only `aapt2` and `unzip`; no build):
  - **Permissions:** `aapt2 dump permissions` equals `ci/allowed-permissions.txt` (8 entries).
  - **Manifest:** `MlKitInitProvider` is absent; `com.google.mlkit.vision.DEPENDENCIES=ica` is present.
  - **New components against `builds/SpaceView-TD0-release.apk`:** only `MlKitComponentDiscoveryService` (`exported=false`, never started at launch) and `PlayCoreDialogWrapperActivity` (`exported=false`), plus the three ML Kit registrar meta-data entries. There is **no new provider or startup initializer**.
  - **Debug-only code:** 0 dex hits for `Scan3dLab`, `scan3d_lab`, `FakeBillingControls` and `MlKitInitProvider`.
  - **R8 mapping:** `MlKitContext.initializeIfNeeded` is kept, renamed to `c`, and the Play review service strings are present.
- **Strings** (my script over all 8 `strings*.xml` pairs):
  - 708 EN = 708 HI, with the same file per key and matching placeholders.
  - No `±`, `×` or bare `cm` in any `a11y`/`spoken` key.
  - The only unused key is `home_accuracy_good`, which is pre-existing.
- **Orchestrator build, 2026-11-17, EXIT 0:**
  - the four unit-test tasks above, `koverVerify`, `assembleDebug` and `assembleRelease`, and `lintDebug` for app, ar and data;
  - app lint 0 errors and 6 warnings (the same 6 as at M4 and TD0: 4× PluralsCandidate, UnusedResources and UseKtx);
  - release universal APK 20,289,833 B.

  I did not run Gradle.

---

## 1. MAJORs from the review

| # | Finding | Status | Evidence |
|---|---|---|---|
| M1 | Hands-free dropped points behind the calibration picker, and they reached known-length calibration | **FIXED** | **Gate:** the dropper runs only when `HandsFreeGate.active(enabled, suspended, windowFocused)` (`HandsFree.kt:90-92`, `:128-130`). `SideEffect` writes it, and `LocalWindowInfo.isWindowFocused` is snapshot state, so focus changes recompose. **Measure:** passes `suspended = handsFreeSuspended(calibration, sessionFailed, frozen)`, which is true for `CalibrationStep.Choosing` (`MeasureScreen.kt:139-144`, `:221-222`), and `startOff = calibration != null`, so calibration starts with hands-free **off**. **Clear on choice:** `onChooseReference` goes through `startCalibration(ref, session::clear)`, which clears before `Measuring` (`:196`, `:228-231`). This also closes the review's step 6 (one stray point plus the user's first tap). **Scan** (`ScanArScreen.kt:131`) and **Furniture** (`FurnitureMeasureScreen.kt:137`) suspend on non-outline steps, freeze, failure and complete. **Every enable edge holds off 1 s** (`HandsFreeDropper.kt:99-105`). **Tests:** `HandsFreeGatingTest.neverWhileThePickerCoversTheReticle` and `aDialogOrTheSwitchStopsIt` pin the rule, and `choosingAReferenceClearsThePoints` pins the clear. They fail against the old behaviour (no suspension, no clear; strictly, the old tree lacks the functions). `HandsFreeDropperTest.dropsWhenEnabledAndNeverWhileSuspended` covers the dropper side. **Residual:** the Compose wiring itself (the `SideEffect` and focus read) is code-read only; TESTING §21 item 4 covers it on a device. |
| M2 | ML Kit diagnostics were missing from the Data safety plan, and the privacy text overclaimed | **FIXED** | **Data safety plan:** `android-performance.md` §6.3 (`:303-306`) adds an ML Kit row (diagnostics, device/app info, **device or other IDs**; collected, not shared; images labelled on-device) and a Play Billing `datatransport` row, and keeps "photos, measurements, 3D models: not collected". **TESTING §17** (`:173-181`) has the same ML Kit answer with the disclosure page's date, the in-app review row, the "one consistent story" rule, and the list of dependencies whose change triggers a re-check. **In-app copy:** `help_privacy_title` is now "your measurements and photos stay on your phone" (`strings_help.xml:37`). The body names AdMob, Play purchases and ML Kit diagnostics, and lists exactly what Delete removes and keeps (`:38`), which matches `UserDataEraser.kt:20-37`. `about_privacy_summary_detail` is changed the same way (`:55`). The HI text matches (`values-hi/strings_help.xml:36-37`, `:53`). **Backup claim checked:** "stored only on this phone" holds, because the backup rules include only `datastore/` and exclude calibration and the entitlement (`backup_rules.xml`, `data_extraction_rules.xml`). **Test:** none applies; this is a copy and doc fix, verified by reading. **Not verified by me:** the live Google disclosure pages. |
| M3 | SM1 shipped on by default with an unverified haptic tick and no device checklist | **FIXED** | **Tick off:** `HAPTIC_TICK_ENABLED = false` (`HandsFree.kt:148`), and its only use is guarded (`:107`). ARCH §5.1 as-built records the decision and its reason. **Checklist:** TESTING **§21** exists with 20 items: dwell tuning (1–3), no unintended drops (4–8), the tick experiment on a *debug* build only (9), TalkBack (10–12), the ML Kit chip with and without the model, offline, and on every bump (13–16), the merged manifest and cold start (17–18), and the G2 floor (19–20). Every check the review asked for is present. **Test:** none applies (a constant and a doc). |

### MINORs spot-checked

| # | Item | Status |
|---|---|---|
| 4 | No hold-off after a freeze | **FIXED**: the dropper tracks `wasFrozen` even while disabled, and a frozen→live edge calls `holdOff(rearmAtNextTarget = true)` (`HandsFreeDropper.kt:136-139`). Test: `theEndOfAFreezeHoldsOffAndNeedsTheReticleToMove`. |
| 5 | "+" and the dwell compete | **FIXED**: `AutoDropRules.ignoreTap` (`HandsFreeDropper.kt:55-58`) is called first in `addPoint` (`MeasureSession.kt:481-491`). A tap within 1 s of a same-spot auto-drop, or of completion, is ignored. Test: `aTapRightAfterAHandsFreeDropOrACompletionIsIgnored`. **New edge: see G2.** |
| 6 | Announcements | **FIXED**: the announcer is always composed (`HandsFree.kt:136-141`). Each drop blanks it for one frame, then sets the text (`:115-120`), so a re-drop after Undo is a change. The name chip sits in a polite live region (`ScanReviewScreen.kt:153`). Device item §21-10/12. |
| 7 | Readout chatter | **FIXED**: the live length uses the settle-only throttle (`MeasureHud.kt:351`; `LiveAnnouncement.kt:38-39`), and so does `FreezeOverlay.kt:177`. Test: `AnnounceThrottleTest`. |
| 8 | SpokenText fractions and singular units (EN/HI) | **FIXED**: fractions are spoken in words through resources ("23 and 5 eighths inches"; HI "23 सही 5 बटा 8 इंच"). Units are singular after exactly "1" or after a lone fraction (`SpokenText.kt:79-91`, `:141-155`). The EN action labels say "centimetre" (`strings_smart.xml:60-64`). The HI `_one` keys exist (`values-hi/strings_settings2.xml:26-42`). Tests: `SpokenTextTest:37-60`, `:96-100`. |
| 9 | Per-call regex compilation | **FIXED**: the regexes and symbols are `private val`s on the object (`SpokenText.kt:57-61`); units are matched by `when`. Residual: about 18 `stringResource` reads and one `Words` per call. That is fine, since the expensive part is gone. |
| 10 | Consent retry over AR | **FIXED as asked** (the `onStart` retry is removed; `gatherConsent` returns while AR is in front, `AdsManager.kt:200`; retried in `onNonArScreenResumed`, `:334`). Tests: `AdsConsentTest:274`, `:293`. **Residual in-flight race: see G1.** |
| 11 | Delete emptied the whole cache directory | **FIXED**: there is no `cacheDirs` step (`UserDataEraser.kt:47-54`). `GalleryRepository.deleteAll` removes only `files/gallery`, `cache/share` and `cache/place` (`:453-462`). |
| 12 | ML Kit failures silent; inner cancel rethrown | **FIXED**: an ML Kit-side cancel becomes an `IOException` (`MlKitObjectNamer.kt:55`). `LabelObjectNamer` rethrows only the *caller's* cancellation (`ensureActive`), and reports every failure to `onFailure`, which logs one WARN per process with no PII (`:27-32`). The version is pinned with a comment (`libs.versions.toml`). Tests: `theCallersCancellationIsNotSwallowed`, `failuresAreReportedAndAnInnerCancellationIsJustNoSuggestion`. **`sdkinternal` fallback:** it catches `LinkageError` → `MlKit.initialize` (`:89-95`). This is acceptable, but note that `MlKitContext` ships *in the APK* (the R8 mapping keeps it as `c`), so a removed API breaks the **build**, not the runtime. The fallback is belt and braces; the real guard is §21 item 16 on each bump. |
| 13 | The store-build guard accepted malformed values | **FIXED**: the same email and https rules apply in Gradle (`app/build.gradle.kts:51-62`) and in `AppLinks` (`AppLinks.kt:40-45`), with a "change both together" note. |
| 14 | Help didn't mention hands-free | **FIXED**: a "Hands-free points" section (what it does, both switches, Undo) and the accuracy line (`strings_help.xml:11`, `:14-17`). HI is equal. |
| 15 | G2: a reflection under a *tracked* glossy floor could win | **FIXED (heuristic)**: a strong point-only bin must also cover ≥ `AREA_RATIO`² × the plane bin's x–z scatter determinant (`FloorEstimator.kt:174-183`, `:244`). Test: `aReflectedTableBelowATrackedFloorPlaneNeverWins` fails under the pre-fix "strong" rule. Both `aStronglySupportedPointFloorBelowATablePlaneWins` (G2) and `aLongWatchedMirroredLineOrSpotNeverBeatsAPlaneFloor` (T-D0 MINOR 3) still hold. **Builder A was right to reject the reviewer's "within 0.3 m below the plane" idea.** G2's own case is a coffee table about 0.45 m above an untracked floor, so a 0.3 m band would block the very floor G2 must find. Height can't separate the two cases, but the area of the aimed-over evidence can. **Residual risk:** the scatter is of *reticle hits* (`PlacementCore.kt:149`), not of surface extents, so it compares where the user aimed. A user who aims over a small patch of tracked floor and then dwells over a large reflected bed outside the plane's polygon could still flip the floor. This is device-gated (§21 item 20, with `AREA_RATIO` tunable). It is no worse than the T-D0 status quo, and I accept it as a warning (W2). |
| 16 | No test of the session-side dwell contract | **FIXED**: the `AutoDropSource` seam plus `HandsFreeDropperTest` (5 tests: suspended, count-decrease hold-off and no re-drop, freeze edge, the `allowed` table, the `ignoreTap` table). `autoDropTarget` uses `AutoDropRules.allowed` (`MeasureSession.kt:514-523`). |
| Nit 1 | N2 waits "≤ 5 s" in HANDOFF | Not re-checked (HANDOFF text only). |
| Nit 2 | The ARCH §5.1 "unchanged" line | **FIXED**: §5.1 as-built now lists the session's small read-outs. |
| Nit 3 | Import order | Left to lint (no finding). |

`CaptureMethod.HANDS_FREE` is recorded through `session.anyHandsFreePoint()` (Measure: `MeasureViewModel.kt:129`; Scan: `ScanDraftBuilder.kt:174`). The test is `CaptureMethodTest`, and there is no schema change (v3 column).

---

## 2. New issues from the fix rounds

| # | Severity | Where | Problem and scenario | Fix |
|---|---|---|---|---|
| **G1** | MINOR (residual of MINOR 10) | `AdsManager.kt:196-207`; `AdsSdk.kt:69-81`; TESTING `:340`, `:382` | **Problem:** the AR check happens before `requestConsentInfoUpdate`. `loadAndShowConsentFormIfRequired` then runs in that request's success callback, whatever screen is in front by then. **Scenario:** offline first launch, later back online. The user resumes Home, which starts the retry, and taps Measure within the network round-trip (about 0.5–2 s). Or, on a first EEA launch, the user taps onboarding's "Calibrate now" at once. In both cases the UMP form opens over the camera. Hands-free does not drop behind it (the window loses focus, M1), and no policy forbids the form there. But TESTING §20 item 5 says "**never** over Measure, Scan or Place", while §21 item 8 tells testers to put "the consent form on a fresh EEA install" over the camera. The two contradict each other, and one of them will be reported as a failure. | Give `AdsSdk.requestConsent` a `canShowForm: () -> Boolean` (= `!visits.arInForeground`). In the success callback, when it is false, skip the form, call back with a new `ConsentResult.DEFERRED`, and reset `consentRequested` so the next non-AR resume shows it. Add one `AdsConsentTest` case (AR becomes foreground between the request and the form → no form; the next non-AR resume shows it). Change §21 item 8's example to "the camera-permission dialog or the notification shade". → **R2** |
| **G2** | MINOR (regression in the MINOR 5 fix) | `MeasureSession.kt:240-242`, `:481-491`, `:544-566` | **Problem:** `lastAutoAtMs`/`lastAutoPosition` (and `completedAtMs`) are set on commit but never cleared by `undo()` or `clear()`. **Scenario:** the dwell drops point 1 two centimetres off a corner. The user taps Undo (about 0.4 s) and nudges about 2 cm to the true corner. The deliberate "+" lands about 0.9 s after the drop, within 3 cm of the undone point, so `ignoreTap` returns true and **nothing happens, with no message**. The dwell won't place it either, because re-arming needs a 3 cm move from the last drop spot (`aRemovedPointHoldsOffAndTheSameSpotIsNotDroppedAgain`). The user has to tap again after the 1 s window. Precision corrections of a few cm are exactly the case the guard must never block. **It can't lose data**, so the severity is MINOR. | In `undo()` and `clear()`, set `lastAutoAtMs = null; lastAutoPosition = null; completedAtMs = null`: the user acted, so the tap is no longer a "confirmation". Add a test: either extract a tiny `TapGuardState` (record auto, record complete, `onPointsRemoved`, `ignore(now, target)`) next to `AutoDropRules` and table-test it, or extend `HandsFreeDropperTest`. → **R1** |
| G3 | DOC | `AndroidManifest.xml:22-25` | The VIBRATE comment still says "the one short tick after a hands-free point drop uses `performHapticFeedback`". The tick is now off. | Say "off (`HAPTIC_TICK_ENABLED`); if ever enabled it uses `performHapticFeedback`, which needs no permission". → R3 |
| G4 | DOC | ARCH `:1446` (§12.3 K6) | The contract row still reads `suggest(jpeg)`. As built, it is `suggest(jpeg, kind: ObjectKind? = null)` (`ObjectNamer.kt:36`; §5.2 as-built records it). | Update the K6 row. → R3 |
| G5 | DOC | TESTING `:364` (§20 item 20) | "Known open items for Builder A … see HANDOFF". HANDOFF says they are done (48 dp mode tabs `MeasureHud.kt:437`, the Scan AR rows, the spoken Review rows `ScanReviewScreen.kt:236-253`). | Replace it with a check: "Measure, Scan and Furniture HUDs: tabs ≥ 48 dp, Review rows read in full". → R3 |

**Checked and found clean:**
- **Can a legitimate "+" be swallowed apart from G2?**
  - The completion guard only ignores a second "+" within 1 s of completing, which would otherwise `clear()` the result just made. That is the intended behaviour.
  - A tap with no current candidate within 1 s of an auto-drop is ignored. The old path would have requested a commit that, with the phone still there, ends in `SAME_SPOT`. This is acceptable.
  - With the hands-free switch off, `lastAutoAtMs` is never set, so the guard never fires.
- **Can hands-free stop working for good after a dialog?** No.
  - `isWindowFocused` is snapshot state read in composition (`HandsFree.kt:90`), so regaining focus recomposes, and `SideEffect` sets `enabled = true` again, with the normal 1 s hold-off.
  - Nothing latches. The other inputs (`suspended`, the switch) are recomputed every composition.
  - Split-screen or freeform loses focus only while the user is in the other window; touching SpaceView restores it.
  - The worst case is the HUD switch reading "on" while focus is elsewhere. That is cosmetic.
- **The consent retry path:**
  - It is idempotent (`consentRequested` CAS, reset only on a failed or gone result).
  - It is a no-op for Pro (`adsAllowed` false) and before `start()` (`entitlementJob` null).
  - No ad request precedes consent (`startSdkIfAllowed` checks `canRequestAds()`).
  - Offline, it re-asks once per non-AR resume, which is cheap.
  - Only G1 remains.
- **Lint and Hindi:**
  - The app has 6 warnings, all pre-existing types; ar and data are clean.
  - EN/HI parity is 708 = 708 with placeholders matching.
  - The new `a11y_*_one` and fraction keys exist in both locales.
  - The HI fractions use "सही"/"बटा", which reads naturally.

---

## 3. Budgets

| Budget | Status | Evidence |
|---|---|---|
| **APK / download** (PLAN §4/§9: arm64 ≤ 15 MB; CI fails above 18 MiB) | **Met (estimate)** | **Universal release:** **20,289,833 B**. That is **+165,056 B (+161 KiB)** on TD0's 20,124,777 B and +299,668 B (+293 KiB) on M4's 19,990,165 B. **The delta:** `classes.dex` grew 4,381,722 → 4,522,122 B deflated (**+140,400 B**: ML Kit thin client, firebase-components, Play review/core-common, Help, About, Delete, consent and SM1 code), and `resources.arsc` grew 139,968 → 171,684 B (+31,716 B, the new EN/HI strings). Native libraries and assets are unchanged. ARCH §5.5 budgeted about 220 KB for SM1 alone; SM1 and the M5 remainder together came in at 161 KiB. **arm64 download estimate:** the TD0 CI figure of 10.47 MiB + about 0.16 MiB ≈ **10.6 MiB**, about 4.4 MiB under target. The real number comes from the bundletool gate on the RC commit (R3). |
| **Cold start** (< 0.8 s mid-range) | **Low risk; unmeasured** | **ML Kit:** `MlKitInitProvider` is removed (verified in the release manifest), and the only new component is a discovery *service* that is never started at launch. ML Kit is initialised on first naming, on `Dispatchers.Default`. **Review:** `reviewPrompter` is `lazy` (`AppContainer`), first touched on Home's resume. It reads one DataStore off main, and `ReviewManager` is created only when it asks. **N2** affects only gate checks, not start-up. No new `androidx.startup` initializer. **Pre-existing start-up cost still open (M6):** `EmojiCompatInitializer`, `WorkManagerInitializer` and `MobileAdsInitProvider`. **Measure:** §21 item 18 (`am start -W` ×5 against 13f3740). |
| **Per-frame** | Met (code) | The dwell is O(points), with no allocation (`HandsFreeDropper.onFrame`); the ring is read in a draw lambda; the `FloorEstimator.strong` scan is O(bins) and only on a dirty estimate. |
| **Lint** | Unchanged | App 0 errors / 6 warnings; ar and data clean. |

---

## 4. Conditions

| # | Owner | What | When | Check |
|---|---|---|---|---|
| **R1** | Builder A | **G2:** clear the tap-guard state (`lastAutoAtMs`, `lastAutoPosition`, `completedAtMs`) in `MeasureSession.undo()` and `clear()`, plus one JVM test: "Undo, then '+' within 1 s on the same spot is **not** ignored". | Before the RC goes to testers | The test is green; the HANDOFF line. |
| **R2** | Builder B | **G1:** add the show-time AR re-check (`canShowForm`, `ConsentResult.DEFERRED`, reset `consentRequested`) and one `AdsConsentTest` case. Then fix TESTING §21 item 8's example. If the code change is declined, amend §20 item 5 to "not *requested* while an AR screen is open; a request already in flight may show over it", and state that in the known limitations. | Before the RC goes to testers | The test is green, or the doc diff. |
| **R3** | Orchestrator | **Docs:** G3, G4 and G5; update HANDOFF; run `python docs/build_html.py` (update `MILESTONES`: SM1 and M5 done). **Commit,** then get a **green CI run** on that commit: tests, lint, Kover, the dependency guard, the permission allowlist, 16 KB, debug-only-absent, `SpaceViewMigrationTest`, and the **bundletool arm64 size** recorded in HANDOFF. | Before the RC tag | CI log; the HANDOFF line. |

**Accepted warnings:**
- **W1:** hands-free is **on by default**, a recorded ARCH §5.1 deviation. The tuning is unproven until §21 items 1–3. If the device pass shows eager drops, the fallback is a default-off flip, a one-line change in `MeasureSettingsRepository.kt:220`.
- **W2:** the G2 `AREA_RATIO` heuristic compares where the user aimed (§1, MINOR 15). It is device-gated by §21 items 19–20.
- **W3:** card/A4 calibration stays behind `CALIBRATION_SMALL_REFERENCES_ENABLED = false` (`MeasureViewModel.kt:169`), although PLAN §1 lists it for v1. Ship with known-length calibration only, and say so in the known limitations, unless the bench data (TESTING §6/§11) justifies the flip.

---

## 5. RC readiness: what remains for an internal-track upload

**Feature-complete?** Yes, for the RC scope:
- **PLAN §1:** Measure, Scan, Gallery, Place with the wall fallback, capped ads, EN/HI, and units all exist.
- **M5:** consent, review, Help, About, Delete, the accessibility pass and Pro are in.
- **SM1** is in.
- **Out by decision:** D step 2 (the 3D-scan screens) is device-gated. Pro does not advertise it (`ProCatalog.kt:124`, `:136`).
- **Behind a flag (W3):** card/A4 calibration.

What is left is release work, against PLAN §9 and the FRIDAY §6.2 gate.

### A. The orchestrator and builders can do

1. **R1–R3** (above). Then **every BLOCKER and MAJOR is closed and verified** (§6.2 line 1).
2. **M6 polish** (PLAN §6, T-D0 gate §8 item 7):
   - Remove the `EmojiCompatInitializer` (manifest `tools:node="remove"` on its `androidx.startup` meta-data) once the build proves nothing needs it. This is cold start.
   - Strip SceneView's `neutral_skybox.ktx`, plus any unused `.filamat`, if Place doesn't render a skybox. That is about 1.6 MB raw and about 0.3 MB deflated (`assets/environments/neutral/`). Prove it on a device.
   - Clear the 6 lint warnings: 4 `<plurals>` (`strings_pro.xml:10, 11, 13, 110`, EN + HI), `home_accuracy_good`, and `UseKtx` in `FakeBillingStore`.
   - The SceneView keep-rule narrowing (`proguard-rules.pro` M6 TODO) is **optional for the RC**, since it only pays off in size and needs a device run.
3. **Store artefacts** (drafts; the user approves them):
   - **Privacy-policy text** (EN, optionally HI). It must tell the same story as Help, the Data safety form and TESTING §17: no account, measurements and photos stay on the phone, Google SDK diagnostics, AdMob with UMP, Delete my data, contact.
   - **Play listing:** short and full description (EN/HI), and the "approximate" and ± wording per PLAN §1.
   - **Release notes** and the **known-limitations list:**
     - ± is typically 2%;
     - plain walls (the wall–floor line);
     - known-length calibration only (W3);
     - the L-sofa verdict is on the overall box;
     - offline Pro grace of 7 days;
     - hands-free is on by default;
     - G1, if R2 takes the doc route;
     - no 3D scan in v1.0.
4. **The Data safety answer sheet** as one table the user can copy into Play Console (from TESTING §17 and perf §6.3): AdMob, UMP, Play Billing/`datatransport`, ML Kit, and Play review, with "data can be deleted: yes (Settings → Delete my data, uninstall)" and the encrypted-in-transit and not-shared flags.
5. **Version:** set `VERSION_NAME` for the RC (`versionCode` comes from the CI `run_number`). Tag `v…` so the tag build runs with `REQUIRE_RELEASE_CONFIG=true` (it fails without the user's secrets, by design).
6. **Hand-off artefacts:**
   - the AAB from `bundleRelease` only;
   - upload the R8 `mapping.txt` with it;
   - keep the TD0 and RC AABs (rollback);
   - the staged-rollout plan in HANDOFF.
7. **A screen-by-screen polish review** (PLAN §9 "Polish": loading, empty, error and offline on every screen). There are no screenshot tests in the repo (no Roborazzi or Paparazzi), so either add a small Roborazzi set or record the manual review as the evidence. **Optional for internal; required before the closed test.**

### B. Only the user can do

1. **Secrets for the tag build (CI secrets or gradle properties):**
   - `ADMOB_APP_ID`, `ADMOB_BANNER_ID` and `ADMOB_INTERSTITIAL_ID`;
   - `KEYSTORE_BASE64` plus the keystore and alias passwords (**Play App Signing**: keep the upload key backed up);
   - `PLAY_LICENSE_KEY` (**this app's** key, TESTING §17 item 1);
   - `SUPPORT_EMAIL`;
   - `PRIVACY_POLICY_URL` (an https page **hosted** with the approved text).
2. **AdMob console:**
   - create the app and the two ad units;
   - **create and publish the GDPR (EEA/UK) consent message under Privacy & messaging**; without it, UMP shows no form and EEA ads stay limited;
   - optionally, the US-states message.
3. **Play Console:**
   - create the app;
   - in-app products and subscriptions with the exact IDs in `ProCatalog` (`pro` monthly and yearly with the 7-day trial, and the 30-day pass), with prices;
   - **licence testers** and internal testers;
   - **the Data safety form** (from A4);
   - content rating, target audience, the **"contains ads" declaration**, app access (none), and the privacy-policy URL;
   - the listing graphics (icon 512, feature graphic, and phone screenshots taken on a device).
4. **Device passes** (release-signed, installed **over** the TD0/M4 build, on ≥ 1 low-end and ≥ 1 mid-range phone; Depth-API phone for §10):
   - TESTING **§6 accuracy bench** (the PLAN §9 accuracy bar);
   - **§16–§18**, **§20** (consent on an EEA test device; the review card needs the internal track) and **§21** (dwell tuning 1–3, no stray drops 4–8, ML Kit 13–16, cold start 18, G2 19–20);
   - **TalkBack, 200% font and contrast** across Measure, Scan, Place, Furniture and Pro;
   - the DB upgrade over the previous install (schema v3 is unchanged).
5. **Hindi sign-off** by a native speaker (PLAN §9), including the TalkBack readings of fractions and "कम या ज़्यादा".
6. **Decisions:**
   - D step 2: run the spike, or confirm "no 3D scan in v1.0";
   - W1 (keep hands-free on by default after the §21 tuning);
   - W3 (card/A4 calibration);
   - the phone models for the device set.
7. **Then:** internal track → **closed test with 12 testers for 14 days** (personal account) → production with a staged rollout.

### Release gate (FRIDAY §6.2) as of this gate

| Line | Status |
|---|---|
| All BLOCKER and MAJOR closed and verified in code | ✅ for SM1-M5 (3/3); R1 and R2 are MINORs |
| CI green on the release commit (unit, lint, coverage, size, alignment) | ⏳ local EXIT 0; CI on the RC commit owed (R3) |
| Migrations tested, including an on-device upgrade | ⏳ JVM migration tests in CI; on-device over TD0/M4 is a user item (B4) |
| Critical journeys on a low-end and a mid-range device | ⏳ user (B4) |
| Security pass; Data safety and privacy policy match behaviour | ⏳ code side ✅ (no new exported component; permissions = allowlist; no upload path; UMP before ads); the form and policy are A3/A4 + B1/B3 |
| Performance budgets on a release build (start-up, frame time, ANR paths, size) | ⏳ size ✅ (estimate); cold start and 30 fps owed on a device (§14, §21 item 18) |
| Accessibility pass: TalkBack, 200% font, contrast | ⏳ code ✅ (spoken text, live regions, 48 dp, `ContrastTokensTest`); device pass owed |
| Known limitations and release notes written | ❌ A3 |
| Rollback plan: staged rollout, previous AAB kept | ❌ A6 |

---

## 6. Recommendations for Phase 3

1. **Order:**
   - R1 and R2, which are tiny;
   - R3 (commit and green CI);
   - the M6 polish in **one** builder pass (EmojiCompat, skybox, plurals), with a size and cold-start re-check;
   - the drafts (A3, A4);
   - the user's secrets, then the tag build;
   - the internal track.

   Don't let the store drafts wait for device work; they are independent.
2. **First device pass priority** (one evening on the user's phone):
   - §6 bench (accuracy is the product);
   - §21 items 1–8 (hands-free on by default touches every flow);
   - §20 items 1–6 (consent);
   - §21 items 13–14 (ML Kit chip).

   **If §21 items 1–2 show eager or missing drops,** tune `HOLD_NS` within 0.6–0.8 s before anything else. If it stays bad, flip the default off (W1) rather than ship a fight with the user.
3. **Haptic tick:** leave it off for v1.0 regardless. Item 9 is an experiment for v1.1, not an RC blocker.
4. **Data safety:** fill the form from the A4 sheet **on the day of submission**, after re-reading the four Google disclosure pages (AdMob, ML Kit, Play Billing, Play Core). Re-check it whenever `ci/release-dependencies.txt` changes in the listed groups (TESTING §17 `:181`). `com.google.android.odml:image:1.0.0-beta1` arrived transitively with ML Kit; note it in the dependency audit, though it is not a finding.
5. **D step 2** stays behind the spike. If it slips past the RC, v1.0 ships without on-device 3D. The paywall already says nothing about it, so nothing in Pro needs to change.
6. **After the closed test:** watch Play vitals for the ANR-prone paths this milestone added (the first ML Kit init on Default, the DataStore read at Home resume for the review). Both are off main by design; vitals will confirm it.

---

## FRIDAY report

**Decision:** APPROVED WITH WARNINGS (= **PASS WITH CONDITIONS** R1–R3). SM1 and the M5 remainder are approved for the RC once R1–R3 land.

**Evidence:**
- **Orchestrator, 2026-11-17, EXIT 0:**
  - engine 282/0, ar 148/0, data 71/0 (1 skipped), app 372/0 (1 skipped);
  - `koverVerify`, assemble debug and release;
  - lint app 0E/6W, ar and data clean;
  - release universal 20,289,833 B; 0 debug-only references.
- **My checks:**
  - result-XML totals (they agree) and source and report freshness;
  - `aapt2` permissions = allowlist;
  - the release-manifest component diff against TD0 (no new provider or initializer; `MlKitInitProvider` gone; new components `exported=false`);
  - dex and R8-mapping string checks;
  - the APK entry and size diff against TD0;
  - an EN/HI parity, placeholder and unused-key script.
- **Code read** at the `file:line` references above.

**Findings:**
- **From the review:** MAJOR 1–3 FIXED (M1 with tests on the extracted rules; M2 and M3 are docs, copy and a constant). MINORs 4–16 FIXED (15 is a heuristic, W2). Nit 2 is FIXED.
- **New:** MINOR G1 (residual of MINOR 10), MINOR G2 (a regression in the MINOR 5 fix), DOC G3–G5. No new BLOCKER or MAJOR.

**Conditions:**
- **R1:** Builder A (G2), before testers.
- **R2:** Builder B (G1), before testers.
- **R3:** the orchestrator (docs, commit, green CI with bundletool size), before the RC tag.

**Accepted warnings:**
- W1: hands-free on by default, pending §21 items 1–3;
- W2: G2 `AREA_RATIO` compares aim footprints, pending §21 items 19–20;
- W3: card/A4 calibration off.

**Not verified:**
- **No Gradle run** by me.
- **No device:** dwell tuning, the window-focus behaviour on real dialogs, TalkBack speech (fractions, the HI TTS), the ML Kit chip with and without the model, the UMP form on an EEA test device, the Play review card (internal track only), cold start, the G2 floors, and whether ML Kit's registrar-based init works under R8 at run time (§21 item 14 is the first proof).
- **The live Google disclosure pages** (AdMob, ML Kit, Play Billing, Play Core): taken from TESTING §17 as recorded by Builder B, not fetched by me.
- **The real arm64 download** (bundletool, R3).
- **Excluded from my read:** Builder B's a11y edits in the Gallery, History, Paywall, Onboarding and Furniture screens beyond the reviewer's reading; `ReviewPrompter` and N2 internals beyond the review's analysis; `ContrastTokensTest` ratios; `OssLicences` completeness.

**Debt recorded:**
- the tick experiment (§21 item 9);
- the `sdkinternal` init (a §21 item 16 check on each ML Kit bump);
- the `FloorEstimator` constants to tune (`STRONG_*`, `AREA_RATIO`);
- the M6 items (EmojiCompat, skybox, keep-rule narrowing, plurals);
- no screenshot tests;
- the entitlement cache is not encrypted (carried).

**Next steps:**
1. R1 and R2.
2. R3.
3. The M6 polish pass.
4. Drafts A3 and A4.
5. User items B1–B3.
6. The tag build → internal track.
7. Device passes B4 and B5.
8. Closed test (12 × 14 days).
