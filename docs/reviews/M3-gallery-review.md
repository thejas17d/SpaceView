# M3 review: AR Gallery, Room v2 and front-photo rectifier (uncommitted, on top of ccebb01)

Reviewer: Reviewer agent, 2026-11-05. Scope: the uncommitted gallery work (Builder B), excluding HANDOFF.md:
- `data/.../gallery/*`, the `SpaceViewDatabase` v1→v2 migration, `MeasurementEntity` / `MeasurementRepository`, `schemas/.../2.json`
- `data/src/test/.../gallery/*` and `SpaceViewMigrationTest`
- `app/.../gallery/*` and `app/.../scan/imaging/*` with their tests
- `AppNavHost` gallery routes, `AppContainer`, the manifest FileProvider, `res/xml/file_paths.xml`, and the EN/HI strings

Builder A's in-progress scan flow is out of scope. Source was not edited.

**Verdict: 0 BLOCKER, 3 MAJOR, 18 MINOR.** The storage layer is well built:
- Staged writes with temp files, fsync and rename.
- The row insert and the directory move happen in one transaction, and the rollback tests cover it.
- The migration SQL matches the exported schema byte for byte.
- The 0.5 MB budget is enforced against real encoder output.

The rectifier maths and the GLB orientation are correct. The problems are three:
- **TV diagonal ±.** It is understated by 20–27%, and it is still shown when the diagonal rests on a typed value.
- **Dimension edits.** They write the GLB before the database row, so the two can diverge.
- **Ad route.** The gallery-item route will show the pending after-save interstitial when the user taps **Place** on the Saved screen, which the ad rules forbid.

## How the findings were checked

- **Build lock** taken, then released. Two test runs:
  - `:data:testDebugUnitTest --tests com.spaceview.data.gallery.*`: green.
  - `:app:testDebugUnitTest --tests com.spaceview.app.gallery.* --tests com.spaceview.app.scan.imaging.*`: green.
- **Sizes printed by the tests.** The JDK JPEG encoder stands in for Android's; the thumbnail is JPEG rather than WebP, which overstates it.

  | Case | model.glb | front.jpg | thumb | Total | Share of 500 KB |
  |---|---|---|---|---|---|
  | Typical 1024×768 | 103 448 B | 100 896 B | 7 373 B | **211 717 B** | 42% |
  | Detailed poster | 206 296 B | 203 741 B | 19 752 B | **429 789 B** | 86% |
  | Worst-case noise (stepped down to q66, 768 px) | 222 232 B | 219 680 B | 13 235 B | **455 147 B** | 91% |

  GLB overhead without the JPEG is **2 552 B**, against the 4 096 B margin in `estimatedItemBytes`.
- **Rectifier timing:** 1024×731 from a 1920×1440 frame takes 16–17 ms (desktop JVM).
- **Migration SQL.** A script compared the `gallery_items` `createSql` in `2.json` with the string in `MIGRATION_1_2`: identical. The `identityHash` in `2.json` (`1907359e…`) matches the one in the generated `SpaceViewDatabase_Impl`, so the JSON was exported by Room, not written by hand.
- **Room 2.8.5 bytecode** (`room-runtime-android` AAR, `javap`). In compatibility mode, `withTransaction` → `startTransactionCoroutine` is a `suspendCancellableCoroutine` that runs the block in `runBlocking` on the transaction executor. This is the basis of MINOR 1.
- **Diagonal uncertainty.** Computed by hand against the engine's `Shapes.rectangle` model (MAJOR 1).

---

## BLOCKER

None.

---

## MAJOR

### M1. The TV diagonal ± is 20–27% too small, and it is shown with a ± even when the diagonal rests on a typed value

**Where**
- `app/.../gallery/GalleryFormatter.kt:62-68`: `diagonal()` propagates σ_W and σ_H as independent.
- `GalleryFormatter.kt:79-80`: `diagonalDetail()`.
- `GalleryViewModels.kt:123-125`: the diagonal row.

**What happens**
- On every non-LiDAR tier the stored σ_W and σ_H are dominated by the **same** VIO scale error (σ_s ≈ 1%). The engine knows this. `Shapes.rectangle` (engine `Shapes.kt:286-290`) propagates only the random parts and then applies the scale **once** to the diagonal.
- The gallery recombines the stored totals as if they were independent: `σ_d = √((w σ_w)² + (h σ_h)²) / d`. This cancels part of the correlated scale term.
- For an estimated W or H, `measurement()` gives σ = 0. The diagonal then takes the ± of the *other* side only. The row shows, for example, "43.0 in ± 0.3 (109 cm)" **and** "Estimated (typed, not measured)". A typed value ends up carrying a measured-looking ±, which is exactly what the edit rules forbid.

**Failure scenario** (NO_DEPTH, σ_s = 1.02%, 2 mm random per side; ±95)

| Item | Engine | Gallery shows | Under by |
|---|---|---|---|
| 43″ TV, 0.952 × 0.536 m | ±22.2 mm (0.87 in) | ±17.8 mm (0.70 in) | 20% |
| 4:3 monitor, 0.60 × 0.45 m | ±15.5 mm | ±11.7 mm | 25% |
| Square panel | ±14.7 mm | ±10.7 mm | 27% |

The M2 review (M1) already established that the engine's coverage is tuned to just over 95%, so a 20% shrink drops the band the user reads well below 95%.

In the second case, the user edits the TV width to the box label's 95 cm. The spec block still shows the diagonal "± 0.3 in", which is no longer a measurement of anything.

**Fix**
- Carry the engine's diagonal in the contract. Add `diagonal: Dimension?` to `ScanDraft` and a `diagonal_bound95_m` column. v2 has not shipped, so `2.json` can still change without a migration.
- On edit, recompute it conservatively: bound95_d = (w·b_w + h·b_h) / d. This is the fully-correlated bound, ≥ the engine value in all three cases above (22.5 / 16.0 / 15.2 mm).
- When W or H is an estimate, show the diagonal **without** a ± (σ = 0, "~"), as `diagonalInches` already does on the card.
- Add a test: when either side is an estimate, the diagonal row has no `plusMinus`; when neither is, the diagonal ± ≥ the fully-correlated bound.

### M2. `updateDimensions` writes the new GLB before the database row, so the model and the row can disagree

**Where:** `data/.../gallery/GalleryRepository.kt:205-219`. `writeAtomic(model, glb)` (210) replaces `model.glb` in place, then `dao.setDimensions(...)` (211-219) runs outside any transaction. The KDoc (186-191) promises "the item is then unchanged" on failure.

**What happens:** each of these leaves `model.glb` at the **new** size while the row (and every number on screen) keeps the **old** size:
- `setDimensions` throws (SQLiteFull, I/O error, or the DB closed on a corrupt file). `guarded` returns false and the user sees "Couldn't save the change".
- The process dies between the rename and the UPDATE (low-memory kill while the dialog closes).

Nothing ever reconciles the two. The KDoc on `GalleryItem.modelFile` says the GLB is "true-size", and M4 places that GLB at 1:1 against a wall. The user then gets a frame placed at 50 cm while the spec block says 45.7 cm ± 0.5. A wrong true-size placement is the failure this app exists to avoid.

**Fix** (any one of these)
1. **Version the model file.** Write `model-<updatedAt>.glb`. Update `model_path` together with the dimensions in one UPDATE (or one `withTransaction`). Delete the old file after the commit. A crash then leaves either the old row with the old file, or an unreferenced new file that `cleanUp` removes.
2. **Treat the row as the source of truth.** When M4 loads a model, compare the GLB `extras.widthM/heightM/depthM` with the row and regenerate it if they differ (it takes milliseconds). Also do this check in `cleanUp`.

Add a test with a DAO whose `setDimensions` throws: the GLB `extras` must still equal the row.

### M3. The gallery-item route uses up the after-save interstitial, so **Place** on the Saved screen will show an ad

**Where**
- `app/.../nav/AppNavHost.kt:156`: `NonArScreenAdEffect(ads)` on `GALLERY_ITEM_PATTERN`.
- `ads/AdsManager.kt:270-283` and `MeasureVisitTracker.consumePending()` (`MeasureVisitTracker.kt:64-68`).

**What happens**
- `NonArScreenAdEffect` makes any non-AR destination that reaches RESUMED **use up** a pending after-measure chance, then show the interstitial if `AdPolicy` allows it.
- HANDOFF (M3 plan) wires the scan flow's Saved screen as **[Place → gallery item | Share | Done (ad slot)]**. Until M4, Place lands on this route.
- When the scan visit ends with a save, leaving it arms the pending chance (as Measure does with `onMeasureLeaving`).
- The first non-AR screen to resume is then the gallery item, and it shows the ad.

**Failure scenario:** a user past the grace period scans a frame, saves it and taps **Place in my room**. The interstitial opens over the item screen. ui-ux §5.2 (eligibility rule 5, and the flow diagram: "[Place in my room] → AR (NO AD)") and Play's disruptive-ads policy both call this unexpected: the user tapped a button expecting to start an activity. The same happens if the Saved screen's **Share** is implemented as "go to item, then share".

This depends on how Builder A wires the Saved screen, but the gallery route in scope is what makes it fire.

**Fix**
- Give `NonArScreenAdEffect` a `consumePending: Boolean = true` parameter and pass `false` on `GALLERY_ITEM`. Alternatively, have the scan flow call `visits.dropPending()` (new) before navigating for Place or Share.
- Implement §5.2 rule 6 (carry the chance over to the next natural exit, once) only if the product wants it.
- Add an `AdPolicy` / `MeasureVisitTracker` unit test: "leave the scan visit via Place → no ad", and include it in Builder A's review.

---

## MINOR

1. **A cancelled `save()` cleans up while the Room transaction is still running.** `GalleryRepository.kt:142-177`. Room's compat `withTransaction` resumes the caller with `CancellationException` as soon as its Job is cancelled, while the block keeps running on the transaction thread until its next suspension point (verified in the 2.8.5 bytecode). This causes three problems:
   - The `catch` (173-177) can delete `committedDir`, which is a plain captured `var` with no memory barrier, after `setFiles` returned but before the commit. The result is a committed row with no files.
   - `writeLock` is released early, so a concurrent `cleanUp()` can delete the not-yet-committed `<id>` directory.
   - The caller sees "not saved" while the item exists.

   The window is milliseconds; it is triggered by Back during Save. **Fix:** `ensureActive()` after encoding, then run the stage → transaction → return part in `withContext(NonCancellable)`, so cancellation never lands between the move and the commit.
2. **Durability gaps, and the startup sweep is incomplete.** `GalleryRepository.kt:162, 245-262, 401-417`.
   - The directory rename and the new directory entries are never fsynced. On power loss, the WAL commit can survive while the rename does not, leaving a row that points at a missing `gallery/<id>/` (`cleanUp` then deletes the `.tmp-…` staging directory that held the files).
   - `cleanUp` does not handle rows whose files are missing (android-performance.md §storage: "deletes orphan directories **and rows whose files are missing**").
   - A `model.glb.tmp` left inside an item directory by an interrupted edit is never removed, and `dirBytes` counts it.

   **Fix:** fsync the parent directory after the rename (open it read-only and call `FileChannel.force`, or accept the risk and document it). In `cleanUp`, flag rows whose `model_path` file is missing (regenerate the GLB from the row, since it is pure data), and delete stray `*.tmp` files.
3. **A "measured" dimension with `bound95 = 0` is accepted and shown with no ± and no estimate label.** `GalleryRepository.kt:381-384` (`validate` allows ≥ 0); `GalleryFormatter.kt:55`. If the scan flow ever passes a measured side with a zero bound, the spec block shows "45.7 cm" at the finest step, unmarked, which breaks "every number carries its tolerance". **Fix:** in `save`, require `bound95 > 0` when `!isEstimate`.
4. **Provenance is a single boolean, and edits throw away the measured value.**
   - `ScanDraft.kt:5`, `strings.xml:224-225`, `GalleryRepository.kt:186-222`.
   - `isEstimate` mixes a scan-flow **preset** (for example a frame's default depth) with a **user-typed** value. A preset depth is labelled "Estimated (**typed**, not measured)", which is false. An item whose sides are all presets shows "Edited by you".
   - An edit overwrites the measured value and its ±, so ui-ux §4.4's "the original measured value is kept" and "Reset to measured" cannot be built later without data that is already gone.

   **Fix (cheap now that v2 has not shipped):** replace the flag with `source: MEASURED | PRESET | TYPED`. Add `measured_*_m` / `measured_*_bound95_m` columns, set on save and never overwritten. Change the copy to "Estimated (not measured)" or make it depend on the source.
5. **The canonical-record columns are added but never filled. A calibrated History row records `calibration_factor = 1.0`, which the KDoc defines as "uncalibrated".**
   - `MeasureViewModel.kt:105-115` does not pass `deviceTier`, `arcoreVersion`, `calibrationFactor` or `rawPrimaryMetres`.
   - `MeasurementEntity.kt:45`.
   - The gallery entity has `calibrated` but no factor, scope or raw lengths, which the M2 gate §6 item 3 asks for.

   **Fix:** wire the four values from the `Reading` / `MeasureModel`. Make `calibration_factor` nullable (NULL = unknown for legacy rows). Add factor and raw dims to `gallery_items` before v2 freezes.
6. **The budget is not a hard limit.** `GalleryRepository.kt:278-291` returns the last attempt even when it is still over, and `save` never checks `bytes <= MAX_SCAN_BYTES`. The step-down measures real encoder bytes, so this holds in practice (worst case 455 KB above). Still, detailed scans use 86–91% of the budget, and the GLB overhead is an estimate. **Fix:** after `dirBytes(stage)`, if the total is over budget, re-encode one more step (512 px at q50) or fail loudly in debug builds. Add a test with an 80-character Devanagari label (the JSON names grow by about 480 B).
7. **CPU-heavy encoding runs on `Dispatchers.IO` with no parallelism cap.** `GalleryRepository.kt:94` (and the rectifier KDoc suggests `Dispatchers.Default`). The worst case is up to 12 JPEG encodes plus a WebP encode, and it may run while the scan route's ARCore session is still alive. android-performance.md recommends `Dispatchers.Default.limitedParallelism(2)` for JPEG/WebP/GLB work so ARCore's VIO threads are not starved ("VIO frequency low"). **Fix:** take a separate `cpu` dispatcher for `encodeImages` and `buildGlb`, and keep `io` for files and Room.
8. **The thumbnail aliases.** `GalleryImageCodec.kt:68`: `createScaledBitmap(..., filter = true)` is a single bilinear pass, and 1024 → 256 is a 4:1 minification. Fine text and stripes on posters will moiré in the grid. **Fix:** halve in steps (1024 → 512 → 256), or decode the thumbnail separately with `inSampleSize = 4`.
9. **Rectifier: an isotropic prefilter on anisotropic quads, and an unbounded source window.** `RectifyMath.kt:47-51`, `FrontPhotoRectifier.kt:38-39`.
   - `prefilter` comes from the longest edge only. A strongly foreshortened face (3000 px wide, 300 px tall in the photo, true aspect 1:1) gets a 2× box filter in both directions. The short direction drops to 150 px before it is upsampled to 1024, which blurs it for no reason.
   - The `IntArray` window covers the quad's bounding box. With a 12 MP still that is about 48 MB plus the downsampled copy, an OOM risk on 3–4 GB phones.

   **Fix:** use separate x and y prefilter factors (from the max edge lengths in each direction). Document in `FrontPhotoRectifier` that the source must be the ARCore CPU image or a still capped at about 2–3 MP, and tell Builder A.
10. **Share copies outlive delete; the previous copy is deleted on the next share; `getUriForFile` sits outside the try.** `GalleryImages.kt:99-131`, `GalleryRepository.kt:225-234`.
    - Deleting an item does not remove its `cache/share/SpaceView-*.jpg`, while the dialog promises "Its photo … will be removed from this phone" (`strings.xml:244`).
    - `listFiles().delete()` on the next share can break a receiver that reads lazily (a Gmail draft, a slow upload).
    - `FileProvider.getUriForFile` (112) can throw `IllegalArgumentException` outside the `try`, in a `rememberCoroutineScope` launch, which crashes the app.

    **Fix:** clear `cache/share` in `delete()`. Name the copy per share (`<id>-<updatedAt>.jpg`) and prune copies older than about 1 day instead of all of them. Move `getUriForFile` inside the `try`.
11. **The FileProvider exposes more than the share needs.** `res/xml/file_paths.xml:5`: `files-path gallery/` is never used (shares go through the `cache/share/` copy). The provider is not exported and grants are per URI, so this is least-privilege hygiene. **Fix:** remove it until M4 or M5 actually shares a GLB.
12. **Bad rows disappear silently, and a storage error looks like an empty gallery.** `GalleryRepository.kt:309-328, 101-105`.
    - `toDomain()` returns null for any unknown enum name, for example after a `DeviceTier` rename or a downgrade. The item becomes invisible and cannot be deleted, while its files still count in `totalBytes`.
    - `observeAll().catch { emit(emptyList()) }` makes the grid show "Nothing saved yet", and the flow ends, so it never recovers until the screen is resubscribed.

    **Fix:** map unknown names to a fallback (`OTHER`, `DeviceTier.NO_DEPTH`, and the worst `Quality`) rather than dropping the row. Expose an error state ("Couldn't open your gallery") instead of the empty state.
13. **A late delete callback can pop the Gallery too.** `GalleryViewModels.kt:110-114`, `GalleryItemScreen.kt:144`. `onDeleted = onBack = popBackStack()` runs whenever the delete coroutine finishes. If the user presses Back first and the coroutine finishes during the exit transition (before the ViewModel is cleared), a second pop takes them out of the Gallery. **Fix:** guard it as the Measure route does (`currentBackStackEntry?.id == entry.id`), or emit a one-shot `Deleted` event that the screen collects.
14. **The TV diagonal ignores the unit preference on the card and in share text.** `GalleryFormatter.kt:71-76`, `GalleryItemScreen.kt:174-175`. `diagonalInches` always formats inches; the detail row respects the preference. A METRIC user sees "43.0 in diagonal" only. ui-ux §6.4 says the defaults are "all switchable". **Fix:** under METRIC show cm first with inches secondary ("109 cm (43 in)", Croma style). Keep inches under AUTO.
15. **Accessibility and i18n details.**
    - `GalleryScreen.kt:155`: the compact dimensions are read by TalkBack as "18 times 24 times tilde 1 in". The "~" estimate marker has no spoken meaning and nothing on the card says "estimated". Add a `contentDescription` built from the same data ("about 1 inch, estimated").
    - `GalleryFormatter.kt:115-122`: Devanagari digits (`४५`), which some Hindi keyboards type, fail to parse and get the misleading "Enter a size from 1 mm to 50 m". Normalise Unicode digits (`Character.digit`) before parsing.
16. **The gallery banner position differs from ui-ux §5.3.** `GalleryScreen.kt:100-105`. The research suggests an inline full-width row every 12 items, or an anchored banner **at the top** under the title. AdMob specifically flags banners next to gallery images, and this one is anchored right under a scrolling thumbnail grid (24 dp gap). Being outside the lazy list and anchored is fine (no reload on scroll, which addresses M2 minor 10). **Fix:** move it to the top, or add a divider and keep at least 24 dp. Record the decision in ui-ux.md.
17. **Edit dialog state.** `GalleryItemScreen.kt:322`: `typed` uses `remember` while `dialog` uses `rememberSaveable`. After process death or recreation the dialog reopens with the measured values and the typed numbers are lost. The "was this field really edited" rule (377-383) lives inside a composable, so no test covers it. **Fix:** `rememberSaveable` with a list saver, and move `edited()` into `GalleryFormatter` with unit tests (unit switch, retyping the prefilled value).
18. **The empty state never offers "Scan an object".** `AppNavHost.kt:145-150` does not pass `onScan`, so the button stays hidden. The copy says "Scan an object to save it here." with no way to do it. The `home_scan_object*` / `home_gallery*` strings are unused until the orchestrator wires Home. **Fix:** wire `onScan` when Builder A's SCAN route lands.

---

## Missing tests

- **The migration test has never run.** `SpaceViewMigrationTest` needs a device; the PC has no emulator, and `.github/workflows/android.yml` runs no connected tests. M2 gate m12 carried this over. Only the SQL comparison in this review checks the migration. **Add:** run it on the bench phone before the M3 gate (add it to `docs/TESTING.md`), or add a Robolectric JVM variant.
- **Real DAO and transaction.** `GalleryDao` SQL (the `setDimensions` bindings, `ORDER BY created_at DESC, id DESC`) and `RoomGalleryTransactions` are never executed: all repository tests use `FakeGalleryDao` with `FakeTransactions`. **Add:** an instrumented DAO test (same device run), or Robolectric with an in-memory Room.
- **`updateDimensions` failure:** a DAO whose `setDimensions` throws (M2).
- **Save cancellation:** cancel during and after the transaction; there must be no row without files and no files without a row (MINOR 1).
- **Diagonal honesty:** no ± on the diagonal when W or H is an estimate; diagonal ± ≥ the correlated bound (M1).
- **Ads:** leaving the scan visit via Place or Share shows no interstitial (M3).
- **Budget:** an 80-character Devanagari label and a 16:1 aspect photo (MINOR 6).
- **End-to-end orientation:** rectify a synthetic photo whose top-left is red, encode it, build the GLB, and check that the texel at the front face's UV (0, 0) is red. Each piece is tested on its own; the chain is not.
- **Android-only paths:** `FrontPhotoRectifier` (HARDWARE bitmap copy and recycle), `AndroidGalleryImageCodec`, and the FileProvider share intent. These go on the device checklist.

---

## Checked and OK

- **Migration.**
  - `MIGRATION_1_2` creates `gallery_items` and its index exactly as in `2.json`, and adds four nullable or defaulted columns to `measurements`. It never touches existing rows.
  - No `fallbackToDestructiveMigration` anywhere.
  - `calibration_factor`'s `DEFAULT 1.0` matches the entity's `defaultValue = "1.0"`, so Room's validation passes.
  - `SpaceViewDatabase.get()` makes History and Gallery share one instance, so invalidations propagate.
  - The migration test asserts the legacy row's defaults.
- **Save atomicity (normal paths).**
  - The staging directory, each file written as temp + fsync + rename, then insert, move and `setFiles` in one transaction.
  - Rollback deletes the staging directory and the moved directory.
  - A reused id after a rollback overwrites the orphan directory.
  - `cleanUp` removes staging and orphan directories under the write lock.
  - Tests cover a DB failure, a failure after the move, a codec failure, invalid drafts and id reuse.
- **Delete** removes the row, then `gallery/<id>/`. A failed file delete is swept later. Tested.
- **Edits (data rules).**
  - Each edited side becomes `isEstimate = true`, `bound95 = 0`. Untouched sides keep their measured value and ±.
  - The badge never improves (`Quality.worst`). When all sides are typed, the badge is replaced by "Edited by you".
  - The UI sends only fields whose text differs from the prefilled value.
  - The GLB is regenerated with the same embedded photo; this is tested by searching the GLB bytes for the photo.
  - After a width correction the front texture is stretched to the new W:H. That is **correct**: the rectifier mapped the object's real corners, so the corrected aspect is the true one.
- **Budget (PLAN M3 gate):** holds, with the numbers in the table above.
  - The step-down measures actual encoder bytes, so it carries over to Android's encoder.
  - WebP thumbnails will be smaller than the JPEG stand-in.
  - The photo stays within ui-ux's 300 KB guidance for shared images.
- **GLB.**
  - 1 unit = 1 m; +Y up; front face +Z, with the front-face top-left vertex at UV (0,0), which glTF defines as the image's top-left, i.e. JPEG row 0. The texture is not mirrored.
  - FRAME/TV use `BACK_CENTER` (box spans z ∈ [0, d] from the wall); other kinds use `BOTTOM_CENTER`.
  - Accessor min/max bounds are correct.
  - The JPEG is embedded as image/jpeg (gltfio has no WebP support).
- **Rectifier maths.**
  - The pixel-centre convention is consistent: output centres at (i + ½, j + ½) map through H⁻¹, then −½ gives sample indices.
  - Prefilter coordinates are scaled by 1/s relative to the crop origin.
  - Fixed-point bilinear weights sum to 65536, with no Int overflow.
  - NaN and out-of-range samples are clamped.
  - Counter-clockwise corners are reordered, so the texture is not mirrored. Rotating the camera image by 90° keeps the winding, so a landscape sensor image works as long as corner 0 is the object's top-left as seen upright. **Contract for Builder A:** corners in source-image pixels, corner 0 = top-left as seen by the user, and aspect = measured W/H.
  - The output long side is the quad's longest edge, clamped to [16, 1024]. The output is never upscaled beyond the photo's detail.
  - 10 tests pass, including perspective round-trip, CCW = CW, prefilter alignment and degenerate quads.
- **Honest display.**
  - Estimates format with σ = 0 and a "~" marker.
  - `maxPlusMinus` ignores estimates and rounds up through the M2-fixed `Units`.
  - The spec block shows ± per measured side plus "Estimated" per typed side, and "The rating covers only the measured sizes" when mixed.
  - Share text carries the ± and the secondary unit.
  - Units follow the setting (frames read in inches, objects in cm), apart from MINOR 14.
- **Threading.**
  - All repository work goes through `withContext(io)`; image decode in `GalleryImage` runs on IO.
  - `guarded` / `guardedOrNull` and the Flow `catch` rethrow `CancellationException`. Every `runCatching` wraps non-suspending code only.
  - Bitmaps in the codec are recycled on every path.
  - Grid thumbnails are 256 px, keyed by id, cached in a 12 MB LRU, and formatted outside composition.
  - The ViewModels hold no Context or Activity.
- **Security and privacy.**
  - The provider is `exported=false` with `grantUriPermissions`.
  - The share intent sets only `FLAG_GRANT_READ_URI_PERMISSION` with `ClipData`.
  - No storage permission is added.
  - The share file name is sanitised; `StorageLayout.itemDir` rejects path separators.
- **Ads.**
  - No banner on the item screen (stricter than §5.3 allows).
  - Nothing near Share or Delete.
  - The grid banner sits outside the lazy grid, so it does not reload on scroll.
  - No ads on AR routes.
- **i18n.** EN and HI have identical key sets (only `app_name` is EN-only). No count strings, so no plurals are needed. Numbers are formatted with `Locale.ROOT` (Western digits, as ui-ux §6.4 asks). Every new string has a Hindi translation.
- **Accessibility.**
  - 48–56 dp touch targets; title headings marked as headings.
  - The detail photo has a content description; card thumbnails are decorative inside a clickable card with its title.
  - The badge carries its own semantics.
