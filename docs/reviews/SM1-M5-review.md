# SM1 + M5-remainder review: Smart Measure SM1 (Builder A) and the M5 remainder + N2 (Builder B)

**Reviewer:** FRIDAY §1, §4.B (standard and thermonuclear passes), §4.D (ML Kit, UMP, data deletion), §4.E (dwell and announcement per-frame cost, cold start), §4.J (accessibility). Read-only: this file is the only write.
**Scope:** all uncommitted work on top of `13f3740` (`git status`, `git diff HEAD`, and every untracked file listed there).
**Method:**
- Read order: the tests (`DwellTriggerTest`, `SpokenTextTest`, `ObjectNamerTest`, `UserDataEraserTest`, `ReviewPrompterTest`, `FeatureGateTest`), then the diff, then the call sites. These included `MeasureSession` (commit path, freeze, pause, `setMode`), `MeasureScreen`/`MeasureHud` (calibration steps), `ArSessionHost`/`ArRequirements`, `AdsManager.start`/`gatherConsent`, `EntitlementRepository.start`, `GallerySaveGate`/`GatedGalleryStore`, `GalleryRepository.cleanUp`, `ResultCards`, `Units.kt` (imperial formatting), and the TESTING, ARCH and research docs.
- Evidence is `file:line` that I read in this session.
- **I ran no Gradle task** (as instructed: RAM is low and the orchestrator builds). So no test, lint or merged-manifest result here is mine.
- **Commands run:**
  - `git`;
  - one read-only Python script for EN/HI key parity, placeholder parity and file parity across every `strings*.xml`, and for unused string keys.

## Verdict: **APPROVED WITH WARNINGS. No BLOCKER. The 3 MAJORs must be fixed before release (MAJOR 1 before the RC goes to testers).**

**Counts:** BLOCKER 0, MAJOR 3, MINOR 13. Three nits are listed at the end and not counted.

- **Where the findings are:**
  - All 16 are regressions in the new code or docs. None is a pre-existing defect.
  - MINOR 10 sits next to a pre-existing guard (`app/build.gradle.kts:51-63` is unchanged from HEAD); the new part is the stricter runtime check in `AppLinks`.
- **What is clean:**
  - The dwell core (`DwellTrigger`, `SteadinessFilter` extraction, the `MeasureSession` read-outs) is sound, single-threaded and allocation-free per frame.
  - `UserDataEraser` is complete for user content and is correctly scoped away from Pro and consent.
  - N2's wait is bounded.
  - The review prompt's policy edges hold.
  - Strings are at full EN/HI parity.
  - **There is no Room schema change** (`SpaceViewDatabase.kt:32` is still `version = 3`; `GalleryDao.deleteAll` is a query only; `captureMethod` was already a v3 column, and `CaptureMethod.HANDS_FREE` already existed at `ScanDraft.kt:12`).

---

## BLOCKER

None.

## MAJOR

**1. Hands-free keeps dropping points while the reticle is hidden behind the calibration picker, and the stray points carry into known-length calibration:** `app/src/main/kotlin/com/spaceview/app/measure/MeasureScreen.kt:83`, `:100-102`; `MeasureHud.kt:114`, `:128`; `ar/src/main/kotlin/com/spaceview/ar/HandsFreeDropper.kt:63-82`; `MeasureSession.kt:285-286`.

- **Problem:**
  - The dropper's `enabled` follows only the setting and the HUD switch (`HandsFree.kt:79-80`).
  - During `CalibrationStep.Choosing` the HUD hides the reticle and the controls (`MeasureHud.kt:114`) and shows `ReferencePicker` (`:128`), but the AR session runs and the dropper keeps firing.
  - The Calibrate route starts in `MeasureMode.DISTANCE` (`AppNavHost.kt:229`), and "Known length" also measures in `DISTANCE` (`MeasureScreen.kt:93-96`). `setMode` returns early when the mode is unchanged (`MeasureSession.kt:286`), so nothing clears the hidden points when the user picks Known length.
  - In known-length mode the live length is deliberately hidden (gate R2, `MeasureScreen.kt:98`, `MeasureHud.kt:119-121`), so the user can't see that the span is wrong.
- **Scenario:**
  1. Settings → Recalibrate (hands-free is on by default).
  2. The picker appears over the camera. The user holds the phone still over a table while reading the three options for about 2 s: a 1 s hold-off from session start, about 0.5 s to lock, and the 0.7 s hold.
  3. Point 1 drops, unseen. A natural 3 cm hand drift re-arms the dwell, and point 2 can drop too.
  4. The user taps "Known length". The mode doesn't change, so both points stay. `isComplete` is already true, so `KnownLengthCard` appears at once (`MeasureHud.kt:129-135`) for a span the user never chose.
  5. The user types their tape length. A factor inside 0.9–1.1 (`Calibration.kt:154`, `:249`) is accepted and scales every measurement in that ARCore session.
  6. With only one stray point, the user's own first tap completes a span that starts at the stray point.
- **Fix:**
  - Gate the dropper on what is on screen: `dropper.enabled = handsFree.enabled && calibration !is CalibrationStep.Choosing`. One way is a `suspended` input on `rememberHandsFree` that each screen sets when its reticle or `Controls` are hidden.
  - Also call `session.clear()` in `onChooseReference` before `calibration = Measuring(ref)`. That way calibration always starts from zero points, even with hands-free off.
  - Consider defaulting hands-free **off inside calibration**, where the aim must be exact (card corners are 5.4 cm apart; the dwell's drift tolerance is 1 cm). The user can still switch it on from the HUD.
  - Add a JVM test on the screen's gating rule (no drop while `Choosing`).
- **If skipped:** accuracy is the #1 priority, and a silent wrong calibration is the worst kind of error. In practice the ±10 % gate rejects most stray spans, which leaves the user with an unexplained "check failed" on the first calibration attempt, and a rare accepted one corrupts the session's scale.

**2. ML Kit's (and the other SDKs') diagnostics are missing from the Data safety plan, and the in-app privacy text says "everything stays on your phone":** `app/src/main/AndroidManifest.xml` (`ica` meta-data), `app/src/main/kotlin/com/spaceview/app/smart/MlKitObjectNamer.kt:27-33`; `docs/TESTING.md:168-173`; `docs/research/android-performance.md` §6.3; `app/src/main/res/values/strings_help.xml:32-33`, `:50`.

- **Problem:**
  - Inference is on-device, and the photo is never uploaded (correct, `MlKitObjectNamer.kt`).
  - However, the Play-services ML Kit client sends API-usage diagnostics to Google. ARCH §5.2 (`ARCHITECTURE-v2.md:525`) says so. Google's ML Kit data-disclosure page lists device and app information, performance metrics, API configuration, error codes and an installation identifier. The release dependency list now also carries `firebase-components` and the `com.google.mlkit` common modules (`ci/release-dependencies.txt`).
  - The planned Data safety answers don't mention this:
    - TESTING §17 lists only Billing (+ datatransport), AdMob and UMP.
    - android-performance §6.3 lists AdMob only, and says that photos and measurements are "not collected".
  - The new Help and About copy headlines "Privacy: everything stays on your phone" (`strings_help.xml:32`, `:50`). It also says "Delete my data removes everything SpaceView has saved" (`:33`), but the eraser deliberately keeps the ad counters, the review record and the entitlement cache (`UserDataEraser.kt:33-38`).
- **Scenario:**
  - The user submits the RC with the Data safety form filled from TESTING §17. ML Kit's "App info and performance → Diagnostics" (and, per the current disclosure text, "Device or other IDs") are undeclared.
  - Play's User Data policy requires the form, the privacy policy and in-app disclosures to be accurate and consistent. An undeclared SDK collection is a rejection or enforcement risk, and "everything stays on your phone" contradicts the form once that collection is declared.
- **Fix:**
  - Add an "ML Kit (image labelling via Play services)" row to TESTING §17 and to android-performance §6.3: diagnostics collected by Google, not shared, not user content. Mark it "verify against Google's ML Kit data-disclosure page at submission", as ARCH §5.2 already requires.
  - Re-check the form whenever `com.google.mlkit` changes in `ci/release-dependencies.txt`.
  - Reword the Help title to something true, e.g. "Privacy: your measurements and photos stay on your phone". Keep the body, and add one clause: "Google's services used by the app (ads, purchases, object naming) send them diagnostic data".
  - Change "removes everything SpaceView has saved" to "removes your measurements, Gallery, calibration and settings". The confirmation dialog already says this correctly (`strings_settings2.xml:14`).
  - Do the same in HI.
- **If skipped:** this is a store-submission policy risk, and the in-app privacy claim becomes inaccurate as soon as ML Kit ships.

**3. SM1 ships on by default with a haptic tick, but TESTING.md has no SM1 device checklist, so the condition the design relies on can't be met:** `docs/ARCHITECTURE-v2.md:506`, `:508`; `docs/TESTING.md` (no SM1 section; §20 is Builder B's only); `app/src/main/kotlin/com/spaceview/app/smart/HandsFree.kt:91-101`.

- **Problem:**
  - ARCH's as-built note makes the haptic conditional: "device checklist item confirms tracking is unaffected, otherwise drop the tick" (`ARCHITECTURE-v2.md:508`). ARCH §5.6 also lists the device checks "hands-free on a shaky hold (tune `holdMs`); TalkBack announces".
  - None of this is in TESTING.md: a search for dwell, hands-free, haptic or ML Kit finds only §20 item 20 and the G2 item 22. Only that file is the release gate.
  - The research says Android uses sound cues *because of* the ARCore haptics caveat (`docs/research/ui-ux.md` §6.1 and §2.1), and SceneView's `VIBRATE` is stripped for that reason (`AndroidManifest.xml:22-28`). The deviation is recorded, but nothing verifies it.
  - On-by-default is a deviation that changes every existing user's Measure, Scan, Furniture and Calibrate flows.
- **Scenario:**
  1. The RC goes to testers, who follow TESTING §1–§20.
  2. Nobody checks whether the tick shifts anchors right after the last point. `PointAdded(complete = true)` fires the tick while the result card's reading is still live-refined from anchors (`MeasureSession.kt:415-425`).
  3. Nobody checks whether 700 ms, 0.20 rad/s and 0.10 m/s suit a shaky hand or drop too eagerly for a steady one.
  4. Nobody checks whether the first TalkBack "Placed point 1" is spoken (MINOR 3).
  5. Nobody checks the ML Kit chip with the model present and absent, or that no `MlKitInitProvider` remains in the merged manifest.
- **Fix:** add "§21 Smart Measure SM1" to TESTING.md. It should cover:
  - **Tracking stability with the tick:** a bench distance measured 5× hands-free vs 5× with "+", with the reading after 10 s; drop the tick if the hands-free spread is worse.
  - **Dwell tuning:** a shaky hold, a steady hold, and an existing user who keeps tapping "+" (MINOR 2).
  - **Behaviour around overlays:** no drop during the calibration picker, a freeze, or a dialog.
  - **TalkBack:** every drop is announced, including after an Undo.
  - **ML Kit chip:** on a fresh install before the model download (no chip), with the model (a chip within 3 s, EN and HI), and in airplane mode.
  - **Merged-manifest check:** no `MlKitInitProvider`, and no new permission.
  - **Cold start:** an `am start -W` comparison against `13f3740`.
- **If skipped:** an on-by-default behaviour change, and a haptic the research advises against, reach users with no device evidence. The ARCH condition "otherwise drop the tick" can never fire.

---

## MINOR

**4. There is no hold-off after a Freeze ends, so a point can drop at the spot the user just corrected away from:** `ar/src/main/kotlin/com/spaceview/ar/HandsFreeDropper.kt:63-67`; `MeasureSession.kt:658-680`.

- **Problem:** the dropper holds off only on a decrease in the point count, on a resume and on a toggle. While frozen, `autoDropTarget()` is null (`MeasureSession.kt:498`), but `endFreeze` doesn't change the count, so no hold-off follows. The re-arm spot is set only by a hands-free drop.
- **Scenario:**
  1. The user taps "+" at X (manual), freezes, drags point 1 by 4 cm to X′, taps Done, and keeps the phone still (it is still aimed at X).
  2. 0.7 s later point 2 drops at X, 4 cm from X′, and a 4 cm distance completes.
- **Fix:** have `MeasureSession` expose a `freezeGeneration`, or emit an event on `endFreeze`, and call `trigger.holdOff()` and set `rearmFrom` to the reticle target when it changes. Alternatively, have the dropper treat "frozen → live" as a hold-off edge.
- **If skipped:** an occasional junk point after a freeze, which the user fixes with Undo.

**5. "+" and the dwell compete for existing users:** `MeasureSession.kt:711-713`; `MeasureHud.kt:389-392`; `ResultCards.kt:174-187`.

- **Problem:** the "+" button is disabled only while `pendingCommit` is true, which lasts about one frame after an auto-drop. A user who aims, waits and taps "+" (aim to drop is about 1.2 s) taps just after the dwell has committed.
- **Scenario:**
  - **Point 1:** the tap requests a second commit at the same spot, which is rejected as `SAME_SPOT` with the "same spot" message, an error shown for doing what the app taught them.
  - **Last point:** for the one to two frames before the worker publishes the non-live reading, "+" is still shown with `isComplete = true`. A tap then reaches `addPoint()`, whose first line `clear()`s a complete measurement (`MeasureSession.kt:480`). After those frames, the same finger lands on the result card's bottom row (Undo / New / Save).
- **Fix:**
  - In `addPoint()`, ignore a manual tap within the dwell cool-down (about 1 s) of an auto-drop when the reticle target is within `MIN_SPACING_M` of that drop. Treat it as a confirmation (no error) rather than a new point.
  - Also guard `addPoint()`'s `clear()`-when-complete branch with "not within 1 s of completion".
  - Add a device check under MAJOR 3.
- **If skipped:** confusing errors for upgrading users, and a rare lost result.

**6. Hands-free and naming status isn't reliably announced:** `app/src/main/kotlin/com/spaceview/app/smart/HandsFree.kt:100`, `:108-114`; `ScanReviewScreen.kt` (the `SuggestionChip`).

- **Problem:** the announcer is a 1 dp live-region node whose text is "Placed point N".
  - After Undo, the re-dropped point has the same N, so the semantics don't change and TalkBack says nothing.
  - The node is *created* on the first drop (`if (n <= 0) return`). Whether Compose announces a live region that appears, rather than one that changes, is device-dependent. Not verified.
  - The ML Kit chip appears up to 3 s after the photo, with no announcement.
- **Scenario:**
  - A TalkBack user drops point 2, hears it, taps Undo, and holds again. Point 2 drops silently.
  - A TalkBack user never learns that a name suggestion exists.
- **Fix:**
  - Keep the announcer node always composed with empty text.
  - Make the announced state a `(count, sequence)` pair so every drop changes the text, or call `view.announceForAccessibility` from the event collector.
  - Give the chip's row `liveRegion = Polite`, or announce once when `nameSuggestion` becomes non-null.

**7. Live-region chatter from the readout:** `app/src/main/kotlin/com/spaceview/app/measure/MeasureHud.kt:324-357`; `LiveAnnouncement.kt:138-139`.

- **Problem:** the throttled node includes the distance-to-target pill. The distance changes with every hand movement, so TalkBack gets a polite announcement every ≤ 2 s for the whole time the user is aiming, including before any point exists. These queue with the hint area, the step pill and "Placed point N".
- **Scenario:** with TalkBack on, the user aims at a wall for 10 s and hears 5 distance readouts. The hands-free "Placed point 1" is delayed behind them, or cut off.
- **Fix:**
  - Announce the distance only on a change of more than 10 cm, or not at all while there are 0 points.
  - Announce the live length only when the reading settles (SETTLE only, no 2 s forced gap) while a point is being aimed.
  - Leave the distance for touch exploration.

**8. SpokenText gaps (EN and HI):** `app/src/main/kotlin/com/spaceview/app/common/SpokenText.kt:46-80`; `strings_smart.xml:60-64`.

- **Fractions:** fractional inches pass through as raw text ("23 5/8 inches"; `Units.kt:20`, `:287`), so TTS decides how to read "5/8". Google TTS can read a slash pair as a date or as "five slash eight", and HI TTS is less predictable.
- **Units after 1:** units are always plural: "1 inches" and "1 feet" (asserted by `SpokenTextTest`: "plus or minus 1 inches", "about 1 inches").
- **EN action labels:** the new custom-action labels say "Move point 1 cm left" and the photo description says "1 cm at a time". These are exactly the symbols the M5 pass removes elsewhere; the HI strings are correct.
- **Scenario:** an imperial user hears "twenty-three, May eighth, inches" on a frame width.
- **Fix:**
  - Speak fractions in words through string resources ("23 and 5 eighths inches"; "5/8" → `a11y_fraction_eighths`, and so on for /16, /4, /2).
  - Choose singular or plural units with `<plurals>` keyed on the integer part.
  - Write "centimetre" in the EN action strings.
  - Add the tests.

**9. Per-call regex compilation in SpokenText on hot UI paths:** `SpokenText.kt:49-57`.

- **Problem:** each `speak()` call builds a `Map` and compiles two `Regex`es (`(\d)(mm|cm|m|in|ft)\b` and `\s+`).
  - `LiveReadout` calls it on every recomposition (up to 10 Hz, one or two calls).
  - `FreezeOverlay` calls it on every drag frame for rectangle and box readings (up to 60 Hz), whether or not TalkBack is on.
- **Scenario:** dragging a frozen box point causes about 120 regex compilations per second plus the token lists. That is GC churn on a mid-range phone during a precision drag.
- **Fix:**
  - Hoist both regexes and the symbol map to `private val`s on the object, building the map from `Words` once per `Words` instance (or keying it by the words).
  - Optionally skip the work when `AccessibilityManager.isTouchExplorationEnabled` is false.

**10. The new UMP retry in `onStart` can open the consent form over an AR screen, and hands-free keeps dropping behind it:** `app/src/main/kotlin/com/spaceview/app/MainActivity.kt:45-50`; `AdsManager.kt:159-163`, `:190-201`.

- **Problem:** before this change the consent request was retried only "with the next activity", which never happened in this single-activity app. Now every `onStart` calls `gatherConsent`. That shows UMP's form, a dialog over the current activity, on whatever screen is in front, including Measure, Scan or Place. A dialog doesn't pause the activity, so the AR session (and the dwell, MAJOR 1's class) keeps running behind it.
  - The retry itself is idempotent: `consentRequested` and `sdkStarted` are CAS-guarded.
  - It is a no-op for Pro: `adsAllowed` starts `false`.
  - No ad request precedes consent: `startSdkIfAllowed` checks `canRequestAds()`.
- **Scenario:**
  1. First launch is offline, so the consent request fails.
  2. Later the user is measuring, switches to WhatsApp and comes back online. The consent form appears over the camera mid-task.
- **Fix:** in `onStart`, retry only when `!ads.arInForeground`. Alternatively, move the retry into `onNonArScreenResumed`, the natural break that ui-ux §5.1 asks for.

**11. Delete my data empties the *whole* cache directory, including third-party SDK caches, while the Settings banner's WebView is live:** `app/src/main/kotlin/com/spaceview/app/settings/UserDataEraser.kt:63-68`; `AppContainer.kt:193`.

- **Problem:** the app's own cache content is `cache/share/` and `cache/place/` (`GalleryRepository.kt:849`, `:669`), and `GalleryRepository.deleteAll` already removes both (`:457`). `clearCaches()` additionally deletes everything else under `cacheDir` and `externalCacheDir`: the Chromium WebView HTTP and code caches, and whatever Play services and ML Kit keep there. It does this while Settings is composing an AdMob `BannerAd`.
- **Scenario:** the user taps Delete everything on Settings with a banner showing. The WebView's disk cache disappears under a running Chromium network service.
  - The usual result is cache misses and logged errors, not a crash. But this isn't user data, and "caches" fails whenever an SDK holds a directory it recreates, so the row shows "partial" with no way to fix it.
- **Fix:** drop the `cacheDirs` step, or limit it to app-owned subdirectories. The share and place directories are already covered.
- **If skipped:** a spurious "Some data could not be deleted" result and SDK cache churn. No user data is affected either way.

**12. ML Kit's failure modes are silent:** `app/src/main/kotlin/com/spaceview/app/smart/MlKitObjectNamer.kt:27`, `:41-45`; `ObjectNamer.kt:60-66`.

- **`sdkinternal` reliance:** `MlKitContext.initializeIfNeeded` is a `@KeepForSdk` API in `com.google.mlkit.common.sdkinternal`, not public API.
  - A rename or signature change breaks the build on the next version bump. That is visible and acceptable.
  - A *semantic* change breaks only at runtime, for example a new component the removed `MlKitInitProvider` used to register. The exception is swallowed by `LabelObjectNamer` (`catch (e: Exception) { null }`), so the chip silently never appears again.
- **Cancellation:** `addOnCanceledListener { cont.cancel() }` resumes the caller with a `CancellationException`, which `LabelObjectNamer` rethrows as though the *caller* were cancelled (`:62-63`). That contradicts "Never throws". It is harmless only because `ScanViewModel.suggestName` launches a throwaway job.
- **No cancellation hook:** there is no `invokeOnCancellation`, so a task that never completes keeps the labeller open and the bitmap referenced. This is documented and bounded per photo.
- **Scenario:**
  - After a dependency bump, the naming chip disappears for every user and nobody notices.
  - A Play-services-side cancel ends the naming job with a cancellation instead of `null`.
- **Fix:**
  - Map a task cancellation to `resumeWithException(IOException(...))`, or `resume(emptyList())`.
  - Log a WARN once per process when `labels()` throws: the class name only, no PII.
  - Add the MAJOR 3 device check ("chip appears with the model present") to catch a silent regression on each ML Kit bump.
  - Pin the ML Kit version in `libs.versions.toml` with a comment that bumping it requires that device check.

**13. The store-build guard checks only that the values are present, but `AppLinks` silently rejects malformed ones:** `app/build.gradle.kts:58-59` (pre-existing guard); `app/src/main/kotlin/com/spaceview/app/about/AppLinks.kt:22-27`.

- **Problem:** the guard passes any non-blank `PRIVACY_POLICY_URL` or `SUPPORT_EMAIL`. At runtime, `AppLinks.privacyPolicyUrl` accepts only `https://`, and `supportEmail` must pass `isPlausibleEmail`; otherwise they are null and Settings and About show no policy link.
- **Scenario:** the CI secret holds `http://spaceview.example/privacy`, or an address with a stray space. The store build succeeds, and the shipped app has no privacy-policy link, which Play requires for apps with ads.
- **Fix:** apply the same two checks in the Gradle guard (https prefix, and the same email rule) and fail the store build otherwise.

**14. Help still teaches "tap +" and never mentions hands-free, although it is now on by default:** `app/src/main/res/values/strings_help.xml:11`, `:20` (and HI).

- **Scenario:** a user wonders why points appear by themselves. Help → "Hold steady when you add a point" says nothing about the dwell or where to switch it off.
- **Fix:** add a "Hands-free points" FAQ entry: what it does, the HUD switch, the Settings switch, and that Undo is one tap away. Mention it in the accuracy section as well. EN and HI.

**15. G2: reflections of furniture can form a strong 2-D "floor" below a tracked floor plane:** `ar/src/main/kotlin/com/spaceview/ar/FloorEstimator.kt:124-167`; `docs/TESTING.md:305-307`.

- **Problem:** the new `strong()` test (≥ 30 hits, minor-axis σ ≥ 0.10 m, ≥ 2 s) lets a point-only bin *below* a plane win. On a mirror-polished floor, the reflection of a table top or bed is a horizontal 2-D patch at minus its height. It is seen over time, so it passes all three criteria wherever hits fall outside the floor plane's polygon. The old rule ("a plane always wins") prevented exactly this, at the cost of G2.
- **Scenario:** on a marble floor that ARCore *does* track, with a 75 cm table reflected near the plane's edge, the floor jumps 75 cm down. Wall–floor taps are then rejected, and the wall centre-height readout is wrong.
- **Fix:**
  - Extend device item 22 with "polished floor that ARCore *does* track, a table reflected in it".
  - In code, consider requiring the strong bin to be within about 0.30 m below the plane (a table's underside never reflects that close), or requiring `onPlane` hits to be absent in the strong bin's x–z footprint.

**16. There is no test for the session-side contract the dwell depends on:** `ar/src/main/kotlin/com/spaceview/ar/MeasureSession.kt:497-502`; `HandsFreeDropper.kt:63-82`.

- **Problem:** `DwellTriggerTest` covers the pure trigger well (10 cases, including repeated timestamps and a clock restart). The rules "no target while frozen, complete, `+` pending or unanchored" and "hold-off on a count decrease" live in `autoDropTarget()` and `HandsFreeDropper.onFrame`, which no test covers.
- **Scenario:** a later edit to `autoDropTarget()` drops the `commitRequestedAtMs >= 0` clause, and the dwell double-commits over a pending "+". Nothing fails.
- **Fix:**
  - Extract the predicate `autoDropAllowed(closed, live, frozen, freezeRequested, pending, count, required, pointsOk)` as a pure function and table-test it.
  - Test `HandsFreeDropper`'s count-decrease hold-off with a fake session seam, or by moving that logic into `DwellTrigger.onPointCount(n)`.

---

## Answers to the brief's focus questions

### Dwell correctness

- **Unintended drops:**
  - **While frozen:** no; `autoDropTarget()` is null when `frozenCapture != null || freezeRequested` (`MeasureSession.kt:498`). Right after a freeze: yes (MINOR 4).
  - **After completion:** no (`endpoints.size >= requiredPoints` → null, `:499`), so the dwell never reaches `addPoint()`'s clear-and-restart branch.
  - **On resume:** no. There is a 1 s hold-off (`HandsFreeDropper.kt:54-56`), and the incomplete points are cleared on pause (`MeasureSession.kt:360-364`).
  - **Double drop:** no. There is a 1 s cool-down plus re-arm after 3 cm (`DwellTrigger.kt:130-133`), and the commit is refused while one is pending (`:498`).
  - **During calibration known-length:** yes, through the hidden-picker path (MAJOR 1).
- **Does it fight "+"?** In the commit logic, no: a pending "+" nulls the target, and a manual point blocks the same spot through `nearestCommittedM`. In the UX, yes (MINOR 5).
- **Thread safety:**
  - Everything the dropper touches runs on the main thread: SceneView callbacks (`ArFrameListener.kt:29`), `SideEffect` for `enabled`, and `_progress` (a `StateFlow`).
  - The only cross-thread datum is the worker's `@Volatile candidate`, which is read once into a local (`MeasureSession.kt:500`).
  - No race found.
- **Haptic tick risk for tracking:** plausible but unmeasured. The mitigations (after commit, inside the cool-down) protect new samples but not the live anchor refinement of the points already committed. This needs a device check (MAJOR 3).
- **Per-frame cost:**
  - `onFrame` does O(points ≤ 4) work and no allocation; `Float` boxing in `_progress` happens only on the ≤ 5 step changes per hold.
  - The ring is read in a draw lambda (`MeasureHud.kt:284`), so it causes no recomposition per frame.
  - The announcement path is fine apart from MINOR 9.

### ML Kit

- **Network:** inference is on-device; Play services downloads the model at install (`ica`).
- **Telemetry:** diagnostics go to Google, which the Data safety form must declare (MAJOR 2).
- **No new permission:** this is the builder's claim. I did not check the merged manifest.
- **`sdkinternal`:** acceptable with a device check per bump; it fails silently (MINOR 12).
- **Threading and cold start:** nothing runs at cold start (the provider is removed). Init, decode and `process` start on `Dispatchers.Default` (`ObjectNamer.kt:61`); the task listeners run on main and only resume.
- **Timeout:** `withTimeoutOrNull` correctly gives up waiting, and caller cancellation propagates. The cancel-listener edge case is MINOR 12.

### UMP and ads

- The ordering is unchanged: the SDK starts only when `canRequestAds()` allows.
- The `onStart` re-call is idempotent and a no-op for Pro.
- No ad loads before consent.
- One UX edge (MINOR 10).

### In-app review

- Never on AR: `arInForeground` is checked first (`ReviewPolicy.kt:70`), and asks happen only from `NonArScreenAdEffect`.
- Never after an interstitial: `interstitialBusy` covers the owed, deciding and showing states (`AdsManager.kt:136`), plus a quiet window of 10 minutes.
- At most once per 90 days.
- Clock rollback means "too soon" (`ReviewPolicy.kt:76`; the safe direction).
- Each launch counts as an ask, and is recorded before launching.
- Cold start: `ReviewPrompter` is built on Home's first composition, but it touches no I/O or Play API until an ask (`PlayReviewLauncher.manager` is lazy).

### Delete my data

- **Complete for user content:** Room History and Gallery rows, `files/gallery/**`, `cache/share`, `cache/place`, the measure DataStore, and the app settings except onboarding.
- **Excluded by design:** the ad counters, the review record, the entitlement cache (Pro is unaffected) and UMP consent.
- **Partial failure:** each step runs and reports its own flag; a retry is idempotent.
- **Process death:** gallery rows deleted before the files are deleted leave orphan files, which `cleanUp()` removes on the next gallery open (`GalleryRepository.kt:498-523`). The other steps aren't resumable but are harmless to re-run.
- **3D Scan Lab files** (`files/scan3d…`, `LabPerf.kt:109`) aren't erased. That is debug-only and acceptable.
- One overreach (MINOR 11). The wording issue is in MAJOR 2.

### N2

- A one-shot `check` that would say `NeedsPro` waits for `settled` or Pro, for at most 5 s, after the existing ≤ 1 s Unknown wait. The worst case is about 6 s, not the 5 s stated in HANDOFF (nit 1).
- An "Allowed" answer never waits, so no sequence of checks stacks two waits:
  - **`checkDeviceScan`:** the first check either returns `NeedsPro` (no second check) or is Allowed without waiting. Worst case about 6 s, not 10 s. It also has no main-build caller today.
- No UI blocks:
  - Home shows "Checking…" with a spinner and ignores repeat taps (`AppNavHost.kt:99-115`).
  - Gallery's scan button exists only in the empty state, where the gate always allows.
  - Furniture already shows "Checking…".

### Accessibility

- `FreezeOverlay` nudges are correct:
  - The probe and the move intersect the same plane through the last point (`MeasureSession.kt:639-654`), so `restore()` is exact.
  - Scaling 20 px to 1 cm adapts to perspective, and a refused probe changes nothing.
- 48 dp targets are met on the new controls (the switch, the mode tabs, the chip, the dialog buttons, the nudge buttons).
- Gaps: MINORs 6–8.

### Strings

- 688 keys, with full EN/HI parity across all eight `strings*.xml` pairs. Every key is in the same file in both locales, and placeholders match.
- The only unused key, `home_accuracy_good`, is pre-existing.
- The five keys removed from `strings.xml` (`common_close`, `settings_version`, `settings_licences`, `settings_licences_apache`, `settings_contact`) have no remaining references in either locale.

### Merge correctness

The shared files compose correctly:
- `SettingsScreen` carries both builders' parameters.
- `MeasureSettingsRepository` has both `setHandsFreeEnabled` and `clearAll`, and `clearAll` resets hands-free to its default (on).
- `HomeScreen`, `AppNavHost`, `PlaceScreen` and `FurnitureMeasureScreen` are each internally consistent.
- `ScanViewModel.settingsFlow` feeds both Scan and Furniture, so both honour the setting.

## Nits (not counted)

1. HANDOFF says the N2 gate "waits up to 5 s". It is ≤ 1 s (Unknown) + ≤ 5 s (settle).
2. ARCH §5.1 still says "`MeasureSession` itself is unchanged" above an as-built note that lists its new members. Strike the old line to avoid confusion.
3. `HomeScreen.kt` and `ScanRoute.kt` put new imports above the existing block, out of order. Leave it to lint or ktlint.

---

Not checked:
- **Build, tests and lint:** no Gradle build, unit test or lint run. HANDOFF records that lint wasn't re-run after A's last string change.
- **Merged release manifest:** I did not check whether `MlKitInitProvider` is gone, whether ML Kit or Review add any permission, or the +158 KB size claim.
- **On a device:** nothing was run, including:
  - the haptic and tracking interaction;
  - the dwell tuning;
  - the TalkBack behaviour of the appearing live region, fractions and Hindi TTS;
  - the UMP form on an EEA test device;
  - the Play review card on an internal track;
  - cold-start timings.
- **The current text of Google's ML Kit and Play Billing data-disclosure pages:** I cited them from ARCH and memory, not fetched.
- **Excluded from the read:**
  - the Help and About content beyond the privacy and hands-free strings;
  - the full text of `ContrastTokensTest`'s 19 pairs (I didn't recompute the ratios);
  - `OssLicences` completeness beyond spot-checking the ML Kit, Review and Firebase rows;
  - `AdsConsentTest` and `AdsReviewHandOffTest` bodies (only their names and the code they cover);
  - Builder B's a11y edits in `GalleryItemScreen`, `GalleryScreen`, `HistoryScreen`, `PaywallScreen`, `OnboardingScreen`, `AccuracyBadge`, `DimensionLabel`, `ArGate` and `FurnitureScreens`, beyond the diff stat (only the SpokenText call pattern was checked).
