# M4 + P1 gate: Place in room, Pro foundation, 3D core (Final Reviewer + Judge)

FRIDAY, 2026-11-16. Gate tree: the uncommitted work on top of **4f8ec1f**. It covers:
- Phase 0 (frame-listener seam, GLB writer v2);
- M4 Place in room (Builder A);
- P1 Pro foundation (Builder B);
- the `engine/recon` 3D core (Builder C);
- all three fix rounds.

I checked it against `HANDOFF.md`, `docs/ARCHITECTURE-v2.md` (§7.1.4–7.1.6 re-read after Builder C's update at 15:27), `docs/PLAN.md` §9, `docs/contracts/phase0.md`, `docs/reviews/M4-review.md`, `docs/reviews/P1-pro-review.md` and `docs/TESTING.md` §17. No source was edited.

## Verdict: **PASS WITH CONDITIONS**

All 5 MAJOR findings (M4 review 2, P1 review 3) are fixed in the code. Each fix has a test that fails on the old code. I found **no new BLOCKER or MAJOR**.

- **The fit card quotes the deciding axis.** "Too big" names the axis that is too big, and "Fits" quotes the smallest spare.
- **A real 3D scan now saves and places.** Recon output saves end to end and stands on the floor at its measured footprint. The GLB is 304 KB.
- **AdMob waits for a settled entitlement.** It never starts for a Pro user whose cache is stale or missing while Play is still answering.
- **Winding the clock back no longer extends the offline grace.** A rollback inside the window does not help either.
- **The 20-item Free gallery cap is enforced.** It is checked at scan entry, before AR starts, and again at Save, where the draft is kept.

Room v3 matches `3.json` column for column. Hindi is complete. Lint shows 0 errors. The budgets hold.

**Decisions on the two open calls:**
- **(a)** Builder C's revised C5 ± is **accepted**, with one bench condition (R2).
- **(b)** Builder B's rejection of the prepaid 31-day cut-off is **upheld**.

There are three conditions. All are small: two documentation fixes and one CI/device step before the APK leaves the team.

### How this was verified
- **Reading.** I read the tests first, then the code at every fix and call site:
  - `FitVerdict`, `PlaceVerdict`, `PlaceViewModel`, `PlaceScreen` (frame path, `NodeHolder`), `PlaceNavigation`;
  - `PlacementCore`, `PlacementController`, `PlacementMath`;
  - `GlbWriter.writeMesh`, `GalleryDrafts`, `GalleryRepository` (save, saveTemplate, saveMesh, placement, counts), `GalleryDao`, `GalleryItemEntity`, `SpaceViewDatabase`;
  - `MeshExtent`, `ReconMemoryTest`, `ScanSaveEndToEndTest`, `MigrationV3SchemaTest`, `SpaceViewMigrationTest`;
  - `Entitlement`, `EntitlementRepository`, `AdsManager`, `GallerySaveGate`, `FeatureGate`, `ProCatalog`, `PurchaseVerifier`, `AppContainer`, `MainActivity`;
  - the `AppNavHost` diff, the `ScanViewModel` and `ScanRoute` diffs, `ScanReviewScreen`, `GalleryFullCard`, `PaywallNavigation`;
  - `app/build.gradle.kts` (licence-key check) and `data/build.gradle.kts` (Robolectric setup).
- **Schema.** A script compared `data/schemas/…/3.json` with `2.json` and with every `ALTER` in `SpaceViewDatabase.V3_STATEMENTS`:
  - The 15 added columns (14 in `gallery_items`, 1 in `measurements`) match the migration in affinity, NOT NULL and default.
  - No column, table or index was removed or changed.
  - The `identityHash` `7e2ad5f0…` in `3.json` equals the one in the generated `SpaceViewDatabase_Impl`, in both debug and release.
- **Strings.** A script compared `values/strings*.xml` with `values-hi/strings*.xml`:
  - EN has 441 keys and HI has 440. The difference is `app_name`, which is untranslatable.
  - No key is missing, and none is extra. No format argument differs. Each key sits in the same file in both languages.
  - 9 entries are identical in both on purpose: format-only strings, "SpaceView Pro" and the licence text.
- **Freshness.** No `app`, `ar`, `data` or `engine` source file is newer than the last test results (12:27–12:29). Only `ARCHITECTURE-v2.md` changed after that.
- **Orchestrator build, green (EXIT 0):**
  - Tasks: `:engine:test :engine:koverVerify :ar:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :ar:lintDebug :data:lintDebug`.
  - Tests: engine 262, ar 106, data 65 (1 skipped: the Windows-excluded Robolectric migration test), app 242 (1 skipped). 0 failures.
  - A back-to-back `:engine:test --rerun-tasks` also passed, which supports the determinism claim for recon.
  - Release universal APK: 19,990,165 B.
  - The result XMLs on disk agree: engine 262/0, app 242/0 (1 skipped), data 65/0 (1 skipped), ar 106/0.
  - I did not run Gradle.
- **Budget numbers.** These come from the printed output of the last green run, plus my own deflate-9 estimate over the release APK (see §5).

---

## 1. MAJOR findings

| # | Finding | Status | Evidence |
|---|---|---|---|
| M4-1 | The fit card quoted the least pessimistic axis, so "Too big by 0 cm" was possible | **FIXED** | `FitVerdict.kt:126` passes the overall verdict to `limitingOf`. `limitingOf` (`:153-160`) chooses only among axes whose verdict equals it: TOO_BIG takes min(c + z·σc), the largest overshoot; FITS takes min c, the smallest spare; TIGHT takes min(c − z·σc). The set is never empty, because the overall verdict is the worst axis verdict. `PlaceVerdict.kt:104-109` quotes `axes[limiting.itemAxis]`, which is correct under rotation. **Tests that fail on the old code:** `FitVerdictLinearTest.tooBigQuotesTheAxisThatIsTooBigNotAWideTightOne` (the reviewer's TV case: the old rule picks the height at −8.5 cm), `fitsQuotesTheSmallestSpareEvenWhenAnotherAxisHasTheLowerMargin` (the old rule picks H with 8 cm spare), and `PlaceVerdictTest.mixedAxesQuoteTheAxisThatDecides`, which checks the card amounts: 5 cm, rounded up, and 3 cm, rounded down. |
| M4-2 | `saveMesh` required extents equal to the GLB box, so every real scan would have thrown | **FIXED** | **Writer:** `GlbWriter.writeMesh(keepInputOrigin = true)` (`GlbWriter.kt:257-297`) writes the positions unchanged, so the scan stays in the measured box frame. It requires the frame origin within `ORIGIN_SLACK_M` = 0.5 m of the mesh. **Draft:** `MeshDraft` (`GalleryDrafts.kt:65-83`) carries `glbExtents`, checked against the extras, and `meshExtents`, the §7.1.5 values. **Save:** `saveMesh` (`GalleryRepository.kt:250-289`) checks the kind and origin, checks `matches(info, glbExtents)` and the GLB bytes, then writes `mesh_extent_*` and the new `mesh_glb_*` columns. **Placement:** `usable()` (`:603-617`) checks a mesh against `mesh_glb_*`. **End-to-end test:** `ScanSaveEndToEndTest.reconScanSavesAndPlacesAtItsMeasuredFootprint` runs real TSDF fusion, then marching cubes, floor cut, largest component, decimation to 15k, `MeshExtent`, `writeMesh`, `saveMesh` and `modelForPlacement`. It asserts that the GLB box and the extents differ, that W/H/D are the measured box, that the bottom is at the floor cut, and that the footprint is centred at 0 ± 1 voxel. The last run printed "304380 B, 15000 tris; GLB 605.8 × 391.0 × 406.3 mm, extents 602.5 × 400.4 × 403.6 mm, bottom 10.0 mm". The old `saveMesh` rejects this draft. The contract is updated in `phase0.md` §1 and §5. |
| P1-1 | AdMob initialised for Pro after a stale cache or reinstall plus slow Play | **FIXED** | **Signal:** `EntitlementRepository.settled` (`EntitlementRepository.kt:113-121`) becomes true in a `finally` after the first APP_START refresh, whether it is answered or failed (`:150-155`). **Ads:** `AdsManager.start` (`AdsManager.kt:151-157`) waits for `settled`, capped at `SETTLE_WAIT_MS` = 12 s (`:406`), which is above the 10 s connect timeout. Only then does it follow the entitlement, so the 1 s Free published from a stale cache is ignored. **Wiring:** `MainActivity` builds nothing billing- or ads-related before the first frame. It then calls `container.onFirstFrame()`, which starts the entitlement, followed by `ads.start`. **Tests:** `AdsManagerProTest` "stale cache – Free before Play answers is not trusted, then Pro" and "slow Play – Unknown for 5 s, then Pro" both expect zero consent requests and zero inits. Before the fix, the old test asserted the opposite. "Play never answers" checks the 12 s cap. **Residual:** if Play takes longer than 12 s, the ads follow the cache. That is accepted. |
| P1-2 | Rolling the clock back inside the grace window gave unlimited offline Pro | **FIXED** | `CachedEntitlement.lastSeenMillis` is a persisted high-water mark (`Entitlement.kt:56-60`). `seen()` only moves it forward (`:143-144`), on every Failed query (`:90`). `effective()` (`:130-140`) returns Free when `now < max(lastSeen, confirmedAt) − 5 min`, and computes age as max(now, highWater) − confirmedAt. **Tests:** `EntitlementReducerTest` "rollback inside the grace window cannot extend it" (day 6 seen, clock set to day 1, Free), "repeated rollbacks never get past 7 days of observed time", and "small NTP correction below the high-water mark keeps Pro". The first fails on the old code. **Residual (documented in the KDoc, `:126-128`):** a user who sets the clock back *before every launch*, with the Play Store disabled throughout, still gets 7 days of *app-observed* time. That is inside ADR-004's client-only threat model. |
| P1-3 | The paywall sold a 20-item gallery cap that nothing enforced | **FIXED** | `GallerySaveGate` (`GallerySaveGate.kt:17-53`) gives `checkItem` and `checkDeviceScan`. A count error lets the save through. The counts are `COUNT(*)` queries (`GalleryDao`), which load no rows. **At scan entry, before AR:** `AppNavHost.kt:91-98` (Home and Gallery `onScan`) opens the paywall instead of the Scan route. **At Save:** `AppNavHost.kt:140` wires `ScanViewModel` `canSave`, and `ScanViewModel.kt:249-253` keeps the whole draft with `galleryFull = true`. `GalleryFullCard` then offers Upgrade, Share sizes, and Discard behind a confirmation. That card appears on Review, which is a non-AR step. **Rule:** `ProCatalog.kt:106` keeps `GALLERY_UNLIMITED` `shipped = true`, and it is now true. **Tests:** `GallerySaveGateTest` (20th allowed, 21st needs Pro; a lapse blocks only the next save; the 3D cap sits inside the gallery cap; an unreadable count never blocks) and `ScanViewModelTest.galleryFullKeepsTheDraftAndSavesAfterUpgrade`. |

### MINORs spot-checked

| # | Item | Status |
|---|---|---|
| M4-3 | A floor rectangle never checks height | FIXED: `heightNotChecked = GAP \|\| !onWall` (`PlaceVerdict.kt:112`). |
| M4-4 | Fallback "on the floor" was relative to the first tap | FIXED: the floor plane is now at `floorY`, the lowest horizontal hit (`PlacementCore.kt:296`). This creates a new robustness gap; see N1. |
| M4-5 | A storage failure on Place said "deleted" | FIXED: `placementModel` returns `Ready \| Missing \| Failed` (`GalleryRepository.kt:577-597`). `PlaceViewModel` maps `Failed` to an error card. |
| M4-6 | Height slider, saved state, "before the pause" note | FIXED: `HeightSlider` (`PlaceScreen.kt:367, 442`), `KEY_HEIGHT` and `spaceMeasuredBeforePause` (`PlaceViewModel`). |
| M4-7 / 8 / 9 / 10 | Recon: saturation rule, wall fragments, memory, test strength | FIXED. **7:** asymmetric saturation thresholds (`MarchingCubes.kt:63-64`). **8:** `keepLargestComponent(insideBox)` (`MeshSegmenter.kt:110, 139-147`); the "box 5 cm from a wall" scene now gives D = 410.7 mm. **9:** peak 49 → 18.9 MB (§5). **10:** the ± upper bound is ≤ 4× p95 (`ReconSceneTest.kt:178`), and the decimator margin is printed (2.29 mm against 5 mm). |
| M4-11 | Template source not wired | OPEN by design: `TemplateModelSource.NONE` until T, owned by the orchestrator. Carried to recommendation T3. |
| M4-12 | Origin → centre rule written in three places | FIXED: `BoxOrigin.centreOffset` (`GlbWriter.kt:27`) is the single owner, used by the writer, `PlacedItem.centre` and `centreHeight`. This creates a mesh caveat; see N3. |
| M4-13 | Per-frame allocations in Place | PARTIAL: the status snapshot is built only when published, and state kinds are compared with `is` checks. Pose conversions remain; see N5. |
| P1-4 | Replay; packageName; prepaid cut-off | PARTIAL, accepted: the package is checked (`PurchaseVerifier.kt:66`, which reads `packageName` from the signed JSON). The cut-off was rejected; see decision (b). |
| P1-5 | Wrong licence key | FIXED: `app/build.gradle.kts:37-45` fails any build given a key that is not base64 X.509 RSA ≥ 2048 bits. The real-purchase check is release gate item 1 in TESTING §17. |
| P1-6 | RSA parsing on main in `onCreate` | FIXED: `licenceKey` is lazy (`AppContainer.kt:99-101`), and nothing is built before the first frame (`MainActivity`). |
| P1-7 | Close button scrolls away | FIXED: `CloseBar` sits outside the `verticalScroll` (`PaywallScreen.kt:109` vs `:113`). |
| P1-8 | `purchase()` took any outcome | FIXED: only an event with no purchases, or with a token not seen before, answers the flow (`EntitlementRepository.kt:198-205`). |
| P1-9 | Missing tests | FIXED: rollback, `UNSPECIFIED`, and stale cache with slow Play are all added. |
| P1-10 | Firelog / Data safety | Recorded in TESTING §17, "Play Console: Data safety". To be decided at submission. |

---

## 2. Cross-cutting consistency

| Check | Result |
|---|---|
| **K1** (gates, never billing) | Holds. `app/place` and `app/scan` import only `app.pro` (`ProFeature`, `GalleryFullCard`). `featureGate` and `gallerySaveGate` read the entitlement flow only. Unknown, Free and Pending all gate as Free. |
| **K2** (no ads on AR or Place) | Holds. Place has no Measure visit and never calls `recordCompletedScan`. Saved → Place calls `onVisitLeftWithoutAd` and then navigates to the item and on to Place. Back to the item finds no chance to consume. Review → paywall stays inside the Scan visit. The paywall has no ad effect, and Discard (`onClose`, no save) arms nothing. See the full flow below. |
| **K3 / K5** | `place/{id}` and `paywall/{feature}` exist, and both use `launchSingleTop`. Place accepts `onOpenPaywall` and never calls it (Place is Free). Review opens the paywall through navigation, never from AR. |
| **K4** | `placementModel` → `PlacementResult`. A proxy box or template regenerates through the versioned-file path. A mesh never regenerates, falls back to a box in the cache, and leaves its row untouched. |
| **K8 / K9 / K11** | The writer and reader match `phase0.md` §1. The `keepInputOrigin` exception is documented there. `TriMesh` is unchanged. Caveat N3: a mesh's declared `BOTTOM_CENTER` is no longer the centre of its own bounding box. |
| **K10 vs `GallerySaveGate`** | **Consistent, but enforced by convention only.** `saveTemplate` and `saveMesh` do not call the gate themselves. That is correct: `:data` must not depend on `:app/pro`. There are no callers yet, since D and T are not built. `phase0.md` §5 and the `GallerySaveGate` KDoc both say callers check `checkDeviceScan()` or `checkItem()` first. → Recommendations D4 and T2: route every insert through one gated `:app` store, the same way scans go through `canSave`. |
| **Ads end to end, Free vs Pro** | **Free:** first frame, then `settled` (≤ 12 s), then UMP, then `MobileAds.initialize` off main. After a save, one chance is used only by the next RESUMED non-AR screen. **Pro:** UMP and init never run. Becoming Pro drops the interstitial and any pending chance, and banners dispose. **Pending** is still treated as Free for ads, which is correct. |
| **Room v3** (`mesh_glb_*` amended in place) | **Correct, and acceptable** because no v3 APK has left the team (the last APK handed over was M3 = v2). `MIGRATION_2_3` matches `3.json` exactly (script above). `MigrationV3SchemaTest` (JVM, 2 tests, green) asserts the same thing. `SpaceViewMigrationTest.migrate2To3BackfillsProxyBoxAndKeepsRows` calls `runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3)`, which validates against `3.json` (exported into the test assets), and a v1 → latest chain test also exists. **It has never run:** it is excluded on Windows, and CI has not run yet. → R3. |
| **B's edits to A's files, and A's later `GalleryRepository` edits** | Combine cleanly. `GalleryDao` gains three `COUNT(*)` queries, B's. `GalleryRepository` gains `count`, `observeCount` and `countDeviceScans` from B, and `placementModel`, the `mesh_glb_*` columns and `saveMesh` with `glbExtents` from A. They touch disjoint members, and every write still goes through `commitLocked` → `commit` and its `check(bytes ≤ MAX_SCAN_BYTES)`. `ScanViewModel` gains `canSave` (B). The scan logic is unchanged, and A has no later edit there. `AppNavHost` has B's `startScan` and `canSave` and the orchestrator's Place and paywall wiring; the Saved → Place path keeps `onVisitLeftWithoutAd`. `countDeviceScans` counts `model_kind = 'MESH_DEVICE'`, a column A's v3 adds with a default, so legacy rows count as PROXY_BOX. The data tests (65) and app tests (242) ran after the last edit to each of these files. |

## 3. Decisions on the open technical calls

### (a) C5 ±: scale term rel·L, no height inflation. **ACCEPTED, with condition R2.**

The argument that settles it is the physics of the depth source:
- RAW depth-from-motion is triangulated from the VIO poses.
- A VIO scale error therefore scales the whole fused model consistently: camera positions, depths and surfaces together.
- So the error on an extent is rel·L, not rel·r̄.

The old max(L, √2·r̄) modelled something else: an **independent** depth-scale bias. Such a bias moves each observed face along its viewing ray by about bias·r̄. That is a different error, and it applies mainly to hardware ToF depth and to per-device depth calibration.

Applying the old term to depth-from-motion made the ± 3–6× the error. The H ±95 was 38.6 mm against a p95 error of 6.2 mm. That is uninformative, and review MINOR 10 rightly asks the tests to reject it.

**Evidence:**
- The revised ± covers 200/200 seeded trials on every axis, at 2.25× (W), 3.07× (H) and 2.68× (D) the p95 error.
- The trials include a uniform ±1.5% scale error.
- The new upper-bound assertion (≤ 4× p95) passes, and it would have failed under the old formula.

**Dropping height inflation is consistent with the source.** Research §7.6 places the silhouette inflation on W and D, and H is measured from the support plane.

**Why the residual risk is tolerable:**
- **The mesh extents are never the item's size (ADR-013).** They feed only the "3D shape (approximate)" line and the cross-check.
- **The cross-check tolerance is floored at max(3 cm, 3%).** For objects under about 1 m, a slightly narrow mesh ± barely changes the badge. Where it does, it errs towards ROUGH, which is a warning and the honest direction.

**What the synthetic tests cannot show** is whether real depth has a range-dependent bias. Spike exit criterion 1 (§7.1.10) scans the carton at one distance only, so it cannot separate rel·L from bias·r̄. → **R2:** add a two-range check to the spike. If the error grows with range at fixed L beyond the ±, restore a range term for that `DepthSource` (for example `rangeBiasRel · r̄`, set per source) before the mesh ± is shown to users.

### (b) Rejection of the prepaid 31-day cut-off (P1 MINOR 4). **UPHELD.**

Builder B is right.
- **The client cannot tell the two cases apart.** Play's `Purchase` does not expose the base plan, so the client cannot distinguish a prepaid `pass30` from a cancelled `yearly`. Both report `autoRenewing = false`.
- **The cut-off would revoke paying users.** A cancelled yearly plan legitimately stays active for up to a year after `purchaseTime`, so the cut-off would take paid Pro away from cancelled-yearly users. That is a refund risk and a Play policy risk, and the users are honest ones.
- **The benefit is small.** The legitimate path already revokes: Play stops returning an expired pass, and the next `Ok` query means Free. The cut-off would only stop replay through a fake billing service. That attack needs a rooted or hooked Play environment, which is already inside ADR-004's accepted threat model ("a patched APK skips the check").

ADR-004 now records "Replay of a genuine purchase" and "Expired prepaid passes" as accepted residual risks. `purchaseTime` is carried in `OwnedPurchase` for the server check. The real fix is server verification through the Play Developer API, and §8.3 already makes that a release gate before anything with marginal cost, such as cloud credits, ships.

## 4. New issues (introduced or exposed by the fix rounds)

None is a BLOCKER or MAJOR.

| # | Severity | Where | Problem and scenario | Fix |
|---|---|---|---|---|
| N1 | MINOR | `PlacementCore.kt:128-130, 296, 377-379` | **Problem:** `floorY` is the minimum of *every* horizontal reticle hit in the session, point and depth hits included, and it never recovers. **Scenario:** a polished vitrified or marble floor (common in Indian homes) or a glass table can give one mirrored feature point a few cm below the real floor. Every genuine wall–floor tap is then more than `MAX_OFF_FLOOR_M` = 3 cm above `floorY` and gets rejected, and the eye-level readout reads high. | Take `floorY` from tracked horizontal ARCore `Plane`s only (the lowest plane), or from a robust low percentile of hits, never a single point. Add a test with one outlier below the floor. Device item 4. |
| N2 | MINOR | `GallerySaveGate.checkItem` → `EntitlementFeatureGate.check`, `EntitlementRepository.kt:138-142` | **Problem:** the gates wait only for "not Unknown", while the repository publishes Free from a stale cache at 1 s. MAJOR 1 fixed this for ads, not for gates. **Scenario:** a yearly Pro user with 25 items opens the app after 8 days and taps "Scan object" within about 1–3 s. They see the paywall. Nothing is lost: the paywall's refresh shows Pro, and Back works. | In `GallerySaveGate`, when the answer is `NeedsPro` and `settled` is false, wait for `settled` (capped at about 5 s, with progress on the Scan button), then check again. |
| N3 | MINOR (before D) | `PlacementMath.kt:35-36, 109-113`; `GlbWriter.writeMesh(keepInputOrigin)` | **Problem:** `PlacedItem.centre` assumes the GLB's bounding box is centred on its declared origin. A `keepInputOrigin` mesh breaks that by the 1 cm floor cut plus any asymmetry in a partial scan, up to `ORIGIN_SLACK_M` = 0.5 m. **Scenario:** floor placement is correct, because the offset is 0 there. A mesh switched to a wall target, or the wall centre-height readout, is off by that asymmetry. | Write the bounding-box centre into the mesh extras (for example `aabbCx`, `aabbCy`, `aabbCz` via `MeshExtras.values`) and use it in `PlacedItem`, or restrict meshes to floor and table targets. Decide before D integrates. |
| N4 | MINOR | `AppNavHost.kt:91-98` | **Problem:** `startScan` navigates after a suspending check (≤ 1 s wait plus a count) without checking that the user is still on Home or Gallery. **Scenario:** tap Scan, then Settings within a second, and the Scan route opens on top of Settings. | Before navigating, check `navController.currentBackStackEntry` is the entry that started the check. |
| N5 | MINOR (perf) | `PlacementController.onFrame` → `toEngine()` (`SessionSetup.kt:94`), `placedPose`/`modelPose`, `Placed.copy`, `NodeHolder.show` (`PlaceScreen.kt:249-250`) | **Problem:** about 15–25 small objects are still allocated per frame: ARCore pose plus engine `Pose`/`Vec3`/`Quaternion` per conversion, the state copy, and `Float3`/`Quaternion` for the node. That is roughly 30–60 KB/s at 30–60 Hz. MINOR 13 removed only the status snapshot. | Profile at the 30 fps gate (device item 12). If GC shows in the trace, reuse mutable poses for the camera and anchor, and set the node transform from reused `Float3`/`Quaternion`. |
| N6 | DOC | `GalleryRepository.kt:565` (KDoc of `modelForPlacement`) | Says a mismatched mesh is "extras vs the `mesh_extent_*` columns". Since MAJOR 2 it is `mesh_glb_*`. A D builder reading the KDoc would compare against the wrong columns. | One-line KDoc fix → R1. |
| N7 | MINOR (lint) | `strings_pro.xml:10, 11, 13, 110` | 4 `PluralsCandidate` warnings. The counts are constants (20, 3, 2), so English and Hindi are correct today. There is also one `UnusedResources` (`home_accuracy_good`) and one `UseKtx`. | Use `<plurals>` or `tools:ignore="PluralsCandidate"`, and remove the unused string at the M6 lint pass. |

**Areas I checked in particular and found clean:**
- **Threading:**
  - `EntitlementRepository` mutates its sets and cache under one `Mutex` on Main. The 1 s timeout path reads only the `@Volatile` cache.
  - `AdsManager` is main-only.
  - `GalleryRepository` counts run on IO.
  - Mesh thumbnail encoding runs on `cpu`, and every catch rethrows `CancellationException`.
- **Cancellation:**
  - `commitLocked` honours cancellation until the files are committed, then completes under `NonCancellable`. This is unchanged, and it now also covers templates and meshes.
  - The `canSave` false path returns before any write.
  - `purchase()` cancels its waiter when the launch fails.
- **Process death:**
  - Place restores the target, spin, height and space, with a note.
  - `galleryFull` is not saved. It doesn't need to be: Scan restores to Outline, as in M3, and a second Save re-checks.
  - The entitlement cache and high-water mark are persisted on every change.
- **Seams:** both builders' columns and counts, the `MeshDraft` fields and the end-to-end test agree. Place uses the row's W/H/D for the verdict and the GLB's extras for rendering, as K4 says.
- **Strings and lint:** Hindi is complete (above). `lintDebug` reports app 0 errors / 6 warnings, and ar and data "No issues found".

## 5. Budgets

| Budget | Status | Evidence |
|---|---|---|
| **Per item ≤ 0.5 MB** | **Met, and enforced** | **Mesh:** `ScanSaveEndToEndTest` printed **304,380 B** for 15,000 triangles. The test asserts ≤ 400,000 B. `saveMesh` refuses a GLB over 500,000 B, and `commit` checks the exact staged bytes, which adds about 10–20 KB of 256 px WebP thumbnail. **Templates:** about 1 KB per part. **Scans:** unchanged since M3 (≤ 455,992 B worst case). **Rule for D:** decimate to ≤ 15k triangles. A denser mesh fails Save instead of breaking the budget, so D must handle that by decimating again (D3). |
| **APK / download** (PLAN §4: arm64 ≤ 15 MB, CI fails above 18 MiB) | **Met (estimate)** | **Universal release APK:** **19,990,165 B**. That is +341,839 B (+334 KiB) on M3's 19,648,326 B. Billing (Builder B's estimate) is about 184 KiB; the rest is Place, Pro UI, recon and strings. `classes.dex` went from 8.05 to **8.67 MB**. **Download:** my deflate-9 estimate over the APK without the non-arm64 ABIs and META-INF is **10.36 MiB**, against 10.08 MiB at M3, the same method as at the M2 and M3 gates. **Still owed:** the real arm64 number from bundletool and the CI size gate, which come with the first CI run (R3). |
| **Recon memory** (spike §7.1.10: peak extra PSS ≤ 40 MB) | **Met on the JVM** | `ReconMemoryTest` (allocation counter, an upper bound) printed **PEAK 18.9 MB**: the 12.44 MB TSDF plus marching cubes, with the volume released after meshing. It is 23.3 MB if the volume is kept, and the test asserts ≤ 25 MB both ways. The decimator alone is 8.1 MB. Phone PSS is a spike item. |
| **Recon time** | Desktop JVM only | Integration median 17.6 ms per frame (desktop JVM, 160×120 plus colour). Processing for the sofa takes 1.3 s. Phone numbers are spike items 2 and 4. |
| **Place 30 fps** | Device only | See N5 and device item 12. |

---

## 6. Conditions (close before commit, except R3)

| # | Where | Fix | Check |
|---|---|---|---|
| **R1** | `data/.../GalleryRepository.kt:565` | Correct the KDoc to say a mismatched mesh is checked against the `mesh_glb_*` columns (N6). Then regenerate `SpaceView.html` (`python docs/build_html.py`). | Read the KDoc. |
| **R2** | `docs/ARCHITECTURE-v2.md` §7.1.10 item 1 | Decision (a): scan the carton at **two stand-off distances (about 0.7 m and about 1.5 m), 5 scans each**. If the mesh-extent error at fixed L grows with range beyond the ±, add a per-`DepthSource` range term before the mesh ± is shown to users. Also add device items 14–19 below to `docs/TESTING.md` §18. | Doc diff. |
| **R3** | First CI run, **before the M4 APK is handed to anyone** | The CI log must show `TEST-com.spaceview.data.SpaceViewMigrationTest.xml` with 3 tests passed (1→2, 2→3, and the v1 → latest chain), plus the bundletool size gate and the 16 KB check. On the phone, do device item 1 (install M4 over M3 with gallery items and History rows). **The v3 amendment window closes the moment an APK leaves the team.** | CI artefact, device item 1. |

The orchestrator has already planned TESTING §18 (Place + 3D core). Use §7 below for it.

## 7. Device-checklist items for `docs/TESTING.md` §18 (Place + 3D core)

**Upgrade and data**
1. **v2 → v3 upgrade (mandatory first):** install the M4 APK **over** M3 on a phone with several gallery items (TV, frame, box) and History rows.
   - Every item still opens, shares and deletes, and History is intact.
   - Placing each M3 item once regenerates its model, which happens silently. The item still opens after an app restart.

**Place**

2. **True scale:**
   - Place a gallery item next to the real object, or a box you scanned next to the box itself. The outline matches within the item's ±.
   - The "1:1 True size" chip is shown, and pinching never scales.
3. **Surfaces:**
   - Floor, table and wall.
   - A frame or TV hangs flush and upright. A box stands on its bottom.
   - Move, rotate (90° and twist) and 1 cm nudges behave from several viewing angles.
4. **Plain wall fallback:**
   - On a plain painted wall the fallback is offered after about 5 s. Two taps on the wall–floor line build the wall.
   - Two taps on a table edge are rejected.
   - **Repeat on a polished vitrified or marble tile floor (N1).** Note any wrongly rejected floor taps.
5. **Eye level and slider:** the centre-height readout matches a tape within about 2 cm. The slider value survives "Don't keep activities".
6. **Fit verdict:**
   - Measure a gap about 3 cm wider than a measured item: the card reads Tight (linear band).
   - Measure a gap 10 cm wider: Fits, quoting the smallest spare.
   - A wall rectangle where only the width is too small: "Too big by X · width" (M4 MAJOR 1).
   - A typed size shows "using your typed size".
7. **A floor rectangle** shows "Height not checked".
8. **Pause and process death:**
   - Power button on Place: the item is picked up, and the space is labelled "measured before the pause".
   - Under "Don't keep activities", the target, spin, height and space are restored with the restore note.
9. **Errors:** delete the item while Place is open, in another task or via Back stack tricks. The screen says "deleted" and goes back, with no crash.
10. **Rendering:** the photo face of a placed TV or frame matches the real colours (it is unlit). The sides are lit and not black.
11. **Ads:**
    - Saved → Place → Back → item → Back: no ad anywhere.
    - Gallery item → Place → Back: no ad.
    - Never an ad over the camera.
12. **30 fps:** run `gfxinfo` on Place with a textured item placed, on the low-end and the mid-range phone. Frame time p95 must be ≤ 33 ms. Note any GC pauses in a trace (N5).
13. **Hindi and TalkBack:** the Place screens in Hindi with nothing truncated. TalkBack reads the verdict card in full: verdict, amount, axis and notes.

**3D core (spike, D milestone; depth phone)**

14. **Carton 60×40×40 cm at about 0.7 m and about 1.5 m, 5 scans each.** Record the mesh-extent error against a tape and the ± (decision (a), R2).
15. If a ToF phone is available, repeat item 14 with ToF depth. If not, record the depth source the phone used.
16. **Memory, thermal and time:**
    - 90 s capture from a cool start: peak extra PSS ≤ 40 MB (`dumpsys meminfo`).
    - No SEVERE thermal state.
    - Sofa processing ≤ 5 s.
17. **Sofa about 5 cm from a wall:** no wall patch in the mesh, and D is not inflated (M4 MINOR 8).
18. **Saved scan:**
    - The item is ≤ 0.5 MB (Settings storage, or the app's files).
    - It places standing on the floor at its measured footprint (bottom about 1 cm up).
    - The Gallery shows "Approximate 3D shape · measured size".
19. **Free caps:** with 3 saved 3D scans on Free, the next 3D scan opens the paywall **before** the camera. The 20-item cap behaves as in TESTING §17 items 8–10.

## 8. Recommendations for the next phase

### D: 3D-scan device spike, then app integration

1. **Spike first, with hard exits (§7.1.10) plus R2's two-range check.** Run it on the user's depth phone with a release build. Do not write the D screens until items 1–4 pass, or until the Beta / "ToF and raw depth only" fallback has been decided.
2. **Pipeline order exactly as in `ReconMemoryTest`:**
   - compute `DepthNoise` and the `mesh_info` numbers from the volume;
   - run marching cubes and release the volume;
   - then floor clip, `keepLargestComponent(insideBox)`, decimation to 15k, `MeshExtent`, and finally `writeMesh(keepInputOrigin = true)`.
   Copy the order from the test, not from memory.
3. **Budget handling at Save:** if `saveMesh` throws on the byte check, decimate again to about 12k and retry once, then fall back to "Save as box". Never show a raw `IllegalArgumentException`.
4. **One gated insert path:** add a `GatedGalleryStore` in `:app` that calls `gallerySaveGate.checkDeviceScan()` before `saveMesh` and keeps the draft on `NeedsPro`, like `canSave`. Gate DEVICE_SCAN **before** capture as well (K1), never mid-session.
5. **Settle the counting rule.** `countDeviceScans` counts *saved* MESH_DEVICE rows, so deleting a scan frees a Free slot. The user decision was "3 free". Decide whether that means 3 saved at a time (as built) or 3 lifetime (needs a persisted counter), and say it on the paywall.
6. **Fix N3** (the mesh bounding-box centre in the extras) before meshes can target walls.
7. **K2 for D:** saving a 3D scan *does* count as a completed scan. The after-save chance is used only by the next non-AR screen. Extend `MeasureVisitTrackerTest`.
8. **Process death and pause** as in §7.1.6: no TSDF checkpoint, "Finish with what was captured" or "Start again", and "Save as box instead" from the kept numbers. Test each restore path in a `ScanViewModel`-style JVM test.
9. **Label everywhere:** "Approximate 3D shape · measured size". Ship it as **Experimental / Beta** per PLAN §9's fallback rule until the bench shows the ± holds on real depth.

### T: furniture templates

1. **Fix the rule conflict before building.** `ProCatalog` gives `FURNITURE_TEMPLATES` `free = None`, but `FREE_TEMPLATE_IDS` says the sofa, bed and wardrobe templates are free. Model this as an allowance per template id (or a separate "core templates" feature). Flip `shipped` in the same change.
2. **Gate `saveTemplate` through the same `:app` store** with `checkItem()`, and through the template gate for non-core ids, at the picker, before any AR.
3. **Wire `TemplateModelSource`** in `AppContainer.galleryRepository` (`GalleryRepository.create(appContext, templates = registry)`; M4 MINOR 11). Add a JVM test that a stale template model (glbVersion bump) regenerates its parts rather than falling back to a box.
4. **Parts must union to exactly W×H×D** (`saveTemplate` refuses otherwise, at 0.1 mm). Generate the parts from the measured W/H/D with closed-form maths, and property-test the union box over the parameter ranges.
5. **The verdict uses the bounding box** (`boundingBox = true` for FURNITURE) for L-sofas. Say so on the card ("overall box"). A footprint-aware verdict is a later item.
6. **Budget:** about 1 KB per part, so it is negligible. Keep `MAX_PARTS` = 512.

### Carry-over
- New MINORs: N1, N2, N4 and N7 in the next fix pass, and N5 at the 30 fps profile.
- From M3: the bench test, and the high-resolution still for the front photo (M6).

---

## FRIDAY report

**Decision:** APPROVED WITH WARNINGS (= PASS WITH CONDITIONS R1–R3).

**Evidence:**
- **Orchestrator build, EXIT 0:**
  - Tests: engine 262/0, ar 106/0, data 65/0 (1 skipped), app 242/0 (1 skipped).
  - `koverVerify`, `assembleDebug`, `assembleRelease`, and `lintDebug` for app, ar and data.
  - Engine re-run with `--rerun-tasks`: green.
- **My checks:**
  - Schema script (v3 = migration, `identityHash` = Impl).
  - Hindi parity script.
  - Result-XML totals.
  - Lint reports.
  - Deflate-9 size estimate.
  - Source freshness against the test results.

**Findings:**
- **Previous reviews:** the 5 MAJORs are verified fixed; MINORs as in §1.
- **New:** BLOCKER 0, MAJOR 0, MINOR 6 (N1–N5, N7), DOC 1 (N6).

**Conditions / accepted warnings:**
- **R1:** the orchestrator.
- **R2:** Builder C and the orchestrator.
- **R3:** the orchestrator, at the first CI run and on the device.
- **Accepted residuals:**
  - offline grace bounded by 7 days of app-observed time;
  - purchase replay until the server check exists (ADR-004);
  - an independent depth-scale bias in the mesh ±, until R2's bench.

**Not verified:**
- **Devices:** no device was available, so every §7 item is open.
- **Robolectric `SpaceViewMigrationTest`:** never executed (Windows exclusion, no CI yet). → R3.
- **Real arm64 download size:** the bundletool number is still owed.
- **Release APK timestamp:** the APK on disk is dated 12:04, but its `classes.dex` matches the 12:39 R8 output in size. Build the M4 APK from a clean worktree, as planned.
- **SceneView behaviour** (unlit extension honoured, `Node(apply)` recomposition): carried from the M4 review.
- **Real Play Billing and UMP:** TESTING §17.
- **Not read line by line:** `PaywallScreen` and `SettingsScreen` beyond the cited lines; `SyntheticScenes`; `TsdfVolume` (unchanged since the M4 review).

**Debt recorded:**
- `TemplateModelSource.NONE` until T.
- The Robolectric migration test is skipped on Windows (`data/build.gradle.kts` shortcut).
- The entitlement cache is not encrypted (`Entitlement.kt` shortcut).

**Next steps:**
1. R1 and R2.
2. TESTING §18.
3. Commit, push, CI. R3 must be green before the APK.
4. M4 APK from a clean worktree.
5. Install over M3 (device item 1).
6. D spike ∥ T.
