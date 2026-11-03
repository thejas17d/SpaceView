# M2 review: measure mode, engine fixes, M5 foundations (fbe1479 against f6ac685)

Reviewer: Reviewer agent, 2026-11-03. Scope: `git diff f6ac685 fbe1479` (88 files). Source was not edited.

**Verdict: 1 BLOCKER, 6 MAJOR, 17 MINOR.** The ARCore plumbing is careful: every image, point cloud and raw-depth image is closed on every path, all ARCore reads happen on the frame thread, and the SceneView 4.65 calls match the jar. The problems are in what the ± means once it reaches the user. Its pixel units come from the screen rather than the camera, calibration leaks outside its scope, and the formatter rounds the ± down. There is also one teardown race and one ad-placement rule broken.

## How the findings were checked

- **Probe test.** A temporary JVM test (`ar/src/test/.../TmpReviewProbeTest.kt`) built endpoints the way `MeasureSession`'s worker does: 0.3 mm jitter SE, `LocalisationInput(range, focal, 2 px)`. It ran them through `MeasurementComposer` and `Units`. The test has been deleted; its output is quoted below. Build lock taken and released.
- **SceneView 4.65.0 bytecode.** I disassembled the AAR from the Gradle cache with `javap` (there is no sources jar). Three things were checked:
  - `ARSceneView` applies its typed config, then calls `sessionConfiguration`, then `Session.configure`, so SpaceView's settings win. Verified.
  - The `depthMode` re-apply is guarded by a `ChangeGate`, so it never overrides SpaceView's AUTOMATIC. Verified.
  - Session teardown is `ARSession.beginClose`: on the main thread it calls `onPaused`, then posts `Session.pause()` and `Session.close()` to an executor (`SessionTeardown`).
- **ARCore 1.57.0 bytecode.** `Anchor.detach()` calls `nativeDetach(session.nativeWrapperHandle, handle)`. `Session.close()` does not zero that handle.

### Probe output (NO_DEPTH tier)

| Case | Displayed | Engine ±95 |
|---|---|---|
| Card 85.6 mm at 0.3 m, focal 500 px (VGA camera) | 8.6 cm ± 0.4 | 3.8 mm |
| same, focal 2000 px (FHD+ view) | 8.6 cm ± 0.2 | 2.1 mm |
| same, focal 2700 px (QHD+ view) | **86 mm ± 2** (badge GOOD) | 2.0 mm |
| Card calibration at 0.3 m, focal 500 | REJECTED (TOO_NOISY) | n/a |
| Card calibration at 0.3 m, focal 2000 | **ACCEPTED**, relSigma 0.61% | n/a |
| Height floor → table, calibrated on the table | **calibrated = true**, ± 5.1 mm (uncalibrated ± 15.1 mm) | n/a |
| Floor → wall distance, calibrated on the table | **calibrated = true** | n/a |
| bound95 = 1.45 in (3.7 cm), ROOM | **"5′ 3″ ± 1″"** (−31%) | n/a |
| bound95 = 0.74 in, ROOM | **"± 1/2″"** (−32%) | n/a |
| bound95 = 2.49 cm, OBJECT | **"60 cm ± 2"** (−20%) | n/a |

---

## BLOCKER

### B1. The aim term uses screen pixels, so the ± depends on screen resolution and is several times too small. The tier floor is never applied.

**Where**
- `ar/.../MeasureSession.kt:270-272`: `fyView = projM[5] * viewH / 2`.
- `MeasureSession.kt:605`: `LocalisationInput(range, s.focalPx, AIM_PIXELS)`.
- `MeasureSession.kt:540, 709`: `ADJUST_PIXELS = 1.0` view pixels.
- `engine/.../Uncertainty.kt:211-215, 233-238`: the tier floor applies only to *unlocalised* endpoints.

**What happens**
- The focal length passed to the engine is in **view pixels**: about 2000 on an FHD+ phone and about 2700 on a QHD+ phone. The engine's model, the research and the M0–M1 probe use **camera-image pixels** (`Uncertainty.pixelLocalisationSigma`: "≈2 mm at 1 m on a VGA stream, f≈500 px").
- So "2 px" here is about 0.5 VGA pixel. The M0–M1 review measured the realistic aim error at about 1.5 VGA pixels (3 mm at 1 m). The modelled aim σ is therefore roughly 3× too small.
- The aim σ also shrinks as the display gets sharper, although the camera is the same. A QHD+ phone shows a smaller ± than an FHD+ phone with the same sensor.
- Every AR endpoint carries a `LocalisationInput`, so `DeviceTier.absFloor95` (1 cm, the research rule "never better than ±1 cm on non-LiDAR Android") is **never applied on the shipping path**.

**Failure scenario**
- A bank card at 30 cm shows **"86 mm ± 2"** with a GOOD badge (probe).
- With the aim error the M0–M1 review measured, the real 95% band is about ±3–4 mm. Coverage drops back towards the 32–75% that review flagged as MAJOR-1.
- The same mistake lets a **card calibration at 30 cm pass**: accepted with 0.61% when the focal is in view pixels, rejected as TOO_NOISY with a camera focal. The UI tells the user to hold the phone about 30 cm away (`calib_card_detail`), so most card calibrations will report a precision they do not have.
- Freeze-frame makes it worse. The still comes from the CPU image (long side ≤ 1280, often 640×480), stretched about 3–4× onto the view. `ADJUST_PIXELS = 1` view pixel is about ¼ of an image pixel, which no loupe can resolve.

**Fix**
1. Express pixel error in camera pixels. Use the GPU-texture focal length (`camera.textureIntrinsics.focalLength`, the image the user actually sees) for the aim term. Use the CPU-image focal (`camera.imageIntrinsics`) for `ADJUST_PIXELS`, because the frozen still *is* the CPU image.
2. Until the bench test proves smaller values, enforce `tier.absFloor95` as a minimum on every point-to-point length, localised or not: `σ_len ≥ absFloor95 / K95`.
3. Re-run the coverage scenarios with these units.

---

## MAJOR

### M1. The ± shown is rounded **down**, up to about 20% (decimal) and about 33% (fractional inches)

**Where:** `engine/.../Units.kt:141, 147-151, 163-169, 268-272`. `decimalSteps` and `formatPlusMinus` round the ±95 to the *nearest* displayed step; the inch path deliberately picks "the largest step not exceeding it".

**Failure scenario**
- A 1.6 m room span on NO_DEPTH has a ±95 of 1.45 in. AUTO units format it as **"5′ 3″ ± 1″"** (probe).
- A 60 cm object with ±2.49 cm shows **"± 2"**.
- The engine's coverage scenarios are tuned to just over 95% (minimum 95.05%), so every round-down pushes the band the user reads below 95%.

**Fix**
- Round the ± **up** to the display step (ceil, with an epsilon for binary noise), then derive the value step from it. For inches, pick the smallest fraction step ≥ the ±.
- Add a property test: displayed ± ≥ bound95 across contexts and preferences.

### M2. A calibration still applies when the points are on *different* surfaces

**Where**
- `ar/.../MeasurementComposer.kt:204, 261-275`: `sharedPlane` returns null when the points' planes differ.
- `engine/.../Calibration.kt:62-74` and `Uncertainty.kt:201-204`: "unknown plane (null) does not exclude".

**Failure scenario:** Calibrate with A4 on a table at 0.5 m, then measure floor → table-top height from about 0.6 m.
- `sharedPlane` is null because the offset is 75 cm, so `appliesTo(null, 0.6)` is true.
- The table's factor is applied: the value changes by 2.25 cm.
- The ± drops from **15.1 mm to 5.1 mm** (probe).
- Floor → wall distances are treated as calibrated too.

The AR layer turns "points on different surfaces" into "plane unknown", which is exactly the case that should exclude. Research §2.9 says the factor corrects scale "for that plane and session".

**Fix:** In `MeasurementComposer`, when `model.calibration?.plane != null` and `sharedPlane(...) == null`, use `model.withCalibration(null)`. Keep "unknown does not exclude" only for engine callers that truly lack geometry. Add tests for floor→table and floor→wall.

### M3. A calibration from an earlier session is applied forever, to any plane, and claims to be tighter than no calibration

**Where**
- `app/.../MeasureViewModel.kt:63, 77-78, 117, 126-131`
- `data/.../MeasureSettingsRepository.kt:52-56`
- `MeasureScreen.kt:75, 204`
- `AppNavHost.kt:110-118`

**What happens**
- **Cross-session use.** Calibrating from Home or onboarding goes through the `CALIBRATE` route, which has its own ViewModel. The calibration is therefore *always* cross-session when used in Measure.
- **Scope and floor across sessions.** The plane scope is dropped and only the ±50% range scope remains. The floor is 0.8%, which is still below the NO_DEPTH prior of 1.02% σ.
- **No expiry and no way to clear it.** A record never expires, and Settings has no "clear calibration" yet (TODO in HANDOFF).
- **Independent session scale errors.** If VIO scale errors are independent between sessions (σ ≈ 1%), applying an old factor gives error s₂ − s₁. That has σ ≈ 1.4%, worse than not calibrating, while the ± shrinks.
- **Session id is the ViewModel's visit time, not the ARCore session.** "Try again" after a session failure (`sessionKey++`), or a configuration change outside `configChanges` (locale, fontScale), creates a **new ARCore world frame with the same id**. The old plane and anchor, which are world coordinates, are then compared in the wrong frame, with the tight 0.3% floor. On a horizontal plane only the height matters. A new session starts at about the same phone height, so the stale plane can easily "match".

**Fix**
- Until the bench test shows that scale carries over between sessions, apply a stored calibration **only inside the ARCore session that produced it**. Make the session id an ARCore-session identity: a counter bumped in `onSessionCreated`, held by `MeasureSession`, not by the ViewModel.
- Otherwise show "Calibrated earlier, recalibrate" and use the uncalibrated model.
- Add expiry, and add the Settings "clear calibration" toggle.
- Only show the "Calibrated" chip (`MeasureHud.kt:95-96`) when the current reading is calibrated.

### M4. The fitted surface overrides a nearer depth or feature hit and can land behind the object

**Where**
- `ar/.../MeasureSession.kt:591-603`
- `LocalSurfaceModel.kt:101-113` (the cone accepts points at *any* depth along the ray)
- `FrameReaders.kt:78-82`

**What happens:** `FrameReaders.centreHit` deliberately prefers a depth or feature hit that is clearly nearer than the ARCore plane (the box-on-floor case). The worker then discards that choice: for any non-PLANE hit it fits RANSAC to every cloud and depth point within the cone, whatever their depth, and takes the fitted surface **first**.

**Failure scenario:** Aim at the top of a 40 cm box on a textured floor from 1.2 m.
- The cone (radius 8 cm) holds box-top and floor points.
- If the floor supplies 60% or more of the inliers, the fit is the floor, and the endpoint is placed about 40 cm behind the box top.
- `planeBiasRel` does not cover this, because it measures the fit's own noise, not choosing the wrong surface.
- A frame hung 2–3 cm off a wall shows a smaller version of the same effect: a few % length bias.

**Fix**
- Reject a fitted intersection whose range differs from the raw hit distance by more than about max(2 cm, 3%). Better, only feed points within a depth band around the raw hit into RANSAC.
- Keep the fit only to *refine* the raw hit, never to replace it with a different depth layer.

### M5. Anchors are detached while SceneView is pausing or closing the session on another thread

**Where:** `ar/.../MeasureSession.kt:442-454`, `app/.../MeasureScreen.kt:76-77` against `147`.

**What happens**
- `DisposableEffect(session) { close() }` is registered **before** `ARSceneView`. Compose forgets effects in reverse order, so SceneView's dispose runs first.
- `ARCore.destroy()` leads to `ARSession.beginClose()`. That calls `onPaused` on main, then hands `Session.pause()` and `Session.close()` to the `SessionTeardown` executor.
- Next, on main, `MeasureSession.close()` calls `anchor.detach()` for every endpoint. Each call is `nativeDetach(session.nativeWrapperHandle, …)`, a handle that `close()` does not zero. These calls therefore run **concurrently with, or after, `Session.close()`**.
- `runCatching` cannot catch a native fault.

**Failure scenario:** Place two points and press back. Depending on executor timing, the result is a native crash or undefined behaviour in `ArAnchor_detach` while the session is being destroyed. This is intermittent and only visible on a device.

**Fix:** Do not detach in `close()`, because `Session.close()` releases every anchor. Just drop the references. Alternatively, register the `MeasureSession` `DisposableEffect` *after* `ARSceneView` so it is disposed first, while the session is still alive. Add this to the device checklist: exit with points placed, 20 times.

### M6. An interstitial can show on **every** exit from Measure, even with nothing saved, and it starts while the AR screen is still on screen

**Where:** `app/.../nav/AppNavHost.kt:97-102`, `ads/AdsManager.kt:217-254`, `measure/MeasureScreen.kt:109-118`.

**What happens**
- `close` calls `maybeShowAfterSave(BACK)` whenever the Measure route is popped. That includes back from the permission or ARCore gate, and a visit with zero saves.
- `AdPolicy` only counts *lifetime* `scansSinceInterstitial`. After the grace period, a user who saved 3 measurements yesterday opens Measure, cannot get tracking, presses back, and gets an interstitial.
- That breaks the rule in `docs/research/business.md` and ui-ux §5.2: only after Save → Done, at a natural break. It also breaks the `NextAction` contract in `AdPolicy`.
- `ad.show()` runs one DataStore read after `popBackStack()`. Navigation-compose's default 700 ms exit transition keeps the `ARSceneView` composed and drawing camera frames, so the ad opens over a still-visible AR screen.

**Fix**
- Keep `savedThisVisit` in `MeasureViewModel`, set on `Saved`. Only request the ad when it is true.
- Trigger the ad from the destination screen (Home or History) once the transition has settled and the AR entry is gone, for example a one-shot `pendingInterstitial` flag consumed by `LaunchedEffect` on Home.
- Add an explicit "Done" on the Saved confirmation so `NextAction.DONE` is real.

---

## MINOR

1. **The saved value is not the one the user saw.** `MeasureScreen.kt:202` saves `session.finalReading()`, recomputed from the current anchor poses. The ResultCard shows the worker's `state.reading` (`MeasureHud.kt:126-127`). History can then read "60.4 ± 0.5" after the card showed "60.2 ± 0.5". **Fix:** save the exact `Reading` that was displayed, or display `finalReading()`.
2. **Save is possible while points are not tracking** (`MeasureHud.kt:126` against `167`). **Fix:** disable Save, or warn, when `!state.pointsTracking`.
3. **The worker overwrites main-thread state.** `MeasureSession.kt:657`: `_state.value = next`, built from a buffered `FrameSample` (channel of 4), overwrites main-thread updates (`pointCount`, `pendingCommit`, `reading = null` after `clear()`). The ResultCard can flicker back for 1–3 frames after Save, Undo or a commit, and `isComplete` toggles, which drops ResultCard's `rememberSaveable` label. **Fix:** stamp samples with an endpoints generation counter and drop stale ones, or merge only the worker-owned fields.
4. **Pre-loss samples can be committed after a short tracking loss.** `SampleStabilizer.kt:56-58`: a NOT_TRACKING frame arrives with `position == null` (the worker skips the hit), so the estimator is not reset until 5 misses. After a loss of under 5 frames, the warm-up frames (LIMITED) recompute `cached` from pre-loss samples, still marked stable and committable during the warm-up the gate meant to enforce. **Fix:** pass the quality through and reset on NOT_TRACKING even without a position. Do not expose a commit candidate while LIMITED.
5. **The plane anchor may go on the wrong plane.** `MeasureSession.kt:496-506`: `lastPlaneHit` can be a different plane from the candidate's. `isPoseInPolygon` ignores height, so a table-top point can get anchored to the floor plane and later move with floor refinements. **Fix:** check that `|plane.centerPose.distanceToPlane(pose)| < 1 cm`, or keep the trackable with the candidate.
6. **The surface buffer is smaller than the cloud copy.** `LocalSurfaceModel` keeps 768 points (`LocalSurfaceModel.kt:48`), but up to 2048 cloud points (`MeasureSession.kt:718`) plus depth points are pushed per update. Most of each cloud is overwritten at once, and points near the reticle are dropped arbitrarily. **Fix:** cone-filter before buffering, or size the buffer to match.
7. **A failed freeze capture can throw out of `onFrame`.** `FrameReaders.kt:182-192`: `toNv21` and `transformCoordinates2d` inside `image.use` are not guarded, so an exception there escapes `onFrame` into SceneView's frame callback. **Fix:** wrap the body in try/catch and return null.
8. **Allocation per frame.** About 2×`FloatArray(16)`, 2×`DoubleArray(16)` per `ViewProjection`, `endpoints.map`, `FrameSample`, the cloud copy, and in `DepthPatch.toWorldPoints` about 2 `Vec3` per pixel (up to about 900 per depth frame). This is acceptable at 30 fps but causes GC churn on low-end devices. **Fix:** reuse matrices and back-project into primitive arrays.
9. **I/O failures crash the app.** These run in scopes with no handler:
   - `MeasureViewModel.save` and `applyCalibration` (`MeasureViewModel.kt:82-104`): Room or DataStore exceptions.
   - `AdsManager.recordCompletedScan` (`AdsManager.kt:204-209`): `DataStoreAdCounterStore.update` does not catch `IOException`.

   With disk full they crash the app. **Fix:** catch and report.
10. **The banner reloads on every scroll.** `HistoryScreen.kt:119-121` with `BannerAd.kt:61-81`: the `AdView` is `remember`ed inside a LazyColumn item, so scrolling it off and back destroys it and fires a new ad request each time. **Fix:** hoist the `AdView` to the screen, or place the banner outside the lazy list, pinned under it.
11. **An interstitial is preloaded during AR.** `AdsManager.kt:204-208`: `recordCompletedScan` (called from the AR screen) preloads an interstitial (WebView, network) while ARCore is tracking, which costs memory and CPU on mid-range phones. **Fix:** defer the preload until the AR screen has been left.
12. **Database not ready for migrations.** `data/`: Room v1 has its schema exported, which is good, but there is no `room-testing` / `MigrationTestHelper` set up before M3 adds the Gallery tables. History rows also lack the canonical-record fields from research §6: device tier, ARCore version, calibration factor and scope, raw lengths. **Fix:** add `room-testing` and an `androidTest` for 1→2, and add those columns in the M3 migration.
13. **Manifest permissions.** The merged manifest has `FOREGROUND_SERVICE` and `WAKE_LOCK` (allow-listed in `ci/allowed-permissions.txt`), most likely from the ads SDK's WorkManager. Play's foreground-service declaration (target 34+) may then be required. **Fix:** confirm the source with `:app:processReleaseManifest` and remove them with `tools:node="remove"` if no foreground service is used. Also, `AndroidManifest.xml:5` still says "Camera is the only permission".
14. **Missing `configChanges` entries.** `AndroidManifest.xml:62` lacks `locale|layoutDirection|fontScale`. Any of these recreates the activity and with it the ARCore session; see M3 for the calibration side effect.
15. **SceneView's blanket keep rules cost size.** Its `proguard.txt` ships `-keep class com.google.android.filament.** { *; }`, `com.google.ar.core.**` and `dev.romainguy.kotlin.math.**`. There is no crash risk, since everything JNI-reachable is kept, but it defeats the "no blanket keeps" intent in `app/proguard-rules.pro` and costs dex size. **Fix:** strip it at M6 with `-ignorecustomproguardfiles`-style filtering or a narrowed rule set, then verify on a device.
16. **CI memory settings.** CI reuses `org.gradle.jvmargs=-Xmx1536m`, sized for the 2 GB local PC, while `lintDebug` for three modules plus `lintRelease` run in one daemon. Metaspace and heap OOM was already seen locally. **Fix:** override on CI, for example `GRADLE_OPTS` / `-Dorg.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g`. The workflow is otherwise correct for ubuntu: bash with pipefail, the bundletool checksum, the size-gate parsing, the keystore guard and the tag-only `REQUIRE_RELEASE_CONFIG`.
17. **"Calibrated" state shown out of scope.** The "Calibrated" chip and the `calibratedThisVisit` tip suppression (`MeasureHud.kt:95`, `ResultCards.kt:137`) are keyed on any stored record, not on whether this reading is calibrated. **Fix:** key them on `reading.calibrated`.

---

## Checked and OK

- **Native resource release.** `acquirePointCloud`, `acquireRawDepthImage16Bits`, `acquireRawDepthConfidenceImage` and `acquireCameraImage` are all closed through `use` on every path, including early returns and exceptions.
- **Frame lifetime.** No Frame is kept beyond `onFrame`.
- **ARCore threading.** All ARCore reads happen in `onSessionUpdated` on the main thread. The worker only sees copied primitives and engine types.
- **Coordinate conversions are right.** `ViewProjection` uses the column-major VP inverse. The depth patch uses the TEXTURE_NORMALIZED mapping with texture intrinsics scaled to the depth image and the sensor pose, as ARCore's raw-depth sample does. The still uses a VIEW→IMAGE_PIXELS affine. Hit tests are in view pixels, matching the display geometry.
- **Session configuration.** `SessionSetup.configure` runs after SceneView's typed defaults (verified in bytecode). Instant Placement is off and InstantPlacementPoint hits are ignored.
- **Calibration flow model.** Calibration measures with the uncalibrated model. The factor band, skew and TOO_NOISY gates are wired, and the calibration file is excluded from cloud backup and device transfer.
- **Ads setup.**
  - Consent: `canRequestAds()` is checked before `MobileAds.initialize`, which runs off the main thread after the first frame.
  - Ad IDs: debug always uses Google test IDs, and tag builds fail without real IDs.
  - Counting: `recordCompletedScan` is called once per successful save, with no double counting (it can under-count if the screen is left before the insert finishes, which is harmless).
  - Banners: never on camera screens.
- **Startup.** `Application.onCreate` is empty and every dependency is lazy.
- **AR Required manifest.** `camera.ar` is required and the `com.google.ar.core` meta-data is replaced.
- **Hindi strings.** HANDOFF says lint passes on `values-hi`. I did not check the strings one by one.
