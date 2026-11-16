# M4 review: Phase 0 + M4 Place in room (Builder A) and `engine/recon` (Builder C)

Reviewer: Reviewer agent, 2026-11-06. Scope: the uncommitted work against `4f8ec1f` (`git status`, `git diff HEAD`),
limited to the files in the brief. Builder B's Pro, billing and ads code is out of scope, except where A uses K1, K2
and K5. Standard: FRIDAY §1 and §4.B, both the standard pass and the thermonuclear structural pass. Source was not
edited, and Gradle was not run (the orchestrator reports the full build green: engine 249, ar 104, data 63, app 217).

**Verdict: 0 BLOCKER, 2 MAJOR, 11 MINOR.**

The core of M4 is sound:
- The verdict combines uncertainties linearly by default, and its boundaries are tested.
- The GLB contract (K8, ADR-003) is implemented as written. The photo face is unlit. The node is placed at 1:1 with
  `scaleToUnits = null` and `centerOrigin = null`.
- The `MeasureSession` pooling removed the per-frame allocations it targeted, without aliasing.
- Room v3 matches `3.json`.
- `modelForPlacement` regenerates through the existing atomic path and never touches a mesh.
- Recon is deterministic, allocation-free in `integrate`, and its marching-cubes table is crack-free by construction.

The two MAJORs are both about what a number tells the user:
1. The fit card can quote the wrong axis and amount on a "Too big" or "Fits".
2. The open 1 cm GLB-versus-`MeshExtent` question is now a hard seam failure. As written, `saveMesh` would reject
   every real 3D scan.

## How the findings were checked

- **Order (FRIDAY §4.B):** I read the tests first, then the diff, then the call sites.
- **Fit verdict:** checked by hand against `FitVerdict.evaluate` (`engine/.../FitVerdict.kt:108-153`), including the
  ranking expression and `limitingAxis`.
- **Room v3:** I parsed `data/schemas/.../3.json` with Python and compared every new column (type, NOT NULL, default)
  with `V3_STATEMENTS`. All 12 statements match, and `model_kind` is `TEXT NOT NULL DEFAULT 'PROXY_BOX'` in both.
- **Strings:** I diffed the keys of `values/strings_place.xml` and `values-hi/strings_place.xml` with a script: 66 and
  66, none missing. Every `R.string.place_*` used in `PlaceScreen.kt` is defined. The `%1$s` / `%1$d` placeholders
  agree in both languages.
- **Decimator failure:** I read the latest result file,
  `engine/build/test-results/test/TEST-...VertexClusterDecimatorTest.xml` (11:17Z = 16:47 IST, 4/4 passed), and the
  recon file timestamps (`ls --time-style=full-iso`).
- **Memory figures:** computed from the `LongIntMap` sizing rule (`VertexClusterDecimator.kt:243-245`) and the sofa
  sizes that the test result file prints (133,588 → 19,898 triangles).

---

## BLOCKER

None.

---

## MAJOR

1. **The fit card quotes the axis with the least pessimistic margin, not the axis that decided the verdict. "Too big by
   0 cm" is possible.** `engine/.../FitVerdict.kt:123-125`, consumed at `app/.../place/PlaceVerdict.kt:102-107` and
   `PlaceScreen.kt:475-477`.
   - **Problem:**
     - `limiting = axes.minBy { c − z·σc }` is taken over **all** axes, while `fit = max(ordinal)` (the worst verdict).
     - On a two-axis wall rectangle, a TIGHT axis with a wide band can have a lower `c − band` than the TOO_BIG axis.
     - The card then prints "Too big by |c|" using the TIGHT axis's clearance and names that axis.
     - The FITS variant overstates the spare. Take W at 3 cm spare with a 0.5 cm band (score 2.5) and H at 8 cm spare
       with a 6 cm band (score 2). The card says "Fits, 8 cm spare" on the height, while the width has only 3 cm.
   - **Scenario:**
     - A TV of 120 × 70 cm, measured with ± 0.5 cm, and `FitRotation.NONE`.
     - The wall space is measured as 115 ± 1 cm wide and 70 ± 8 cm tall (a long vertical edge measured from far away).
     - Width: c = −5 cm, band 1.5 cm, so TOO_BIG. Its score (c − band) is −6.5 cm.
     - Height: c = 0, band 8.5 cm, so TIGHT. Its score is −8.5 cm, which is lower.
     - Height therefore becomes `limitingAxis`, and the card reads **"Too big by 0 cm · height"** while the width is
       5 cm over.
     - `PlaceVerdictTest` has no case with mixed per-axis verdicts.
   - **Fix:**
     - Choose the limiting axis among the axes whose verdict equals the overall verdict.
     - For TOO_BIG, take the largest overshoot (min `c + band`). For FITS, take the smallest clearance (min `c`). For
       TIGHT, keep the current rule.
     - This is one line in `FitVerdict.evaluate` (the doc comment already says `limitingAxis` is "what the UI
       quotes"), plus a mixed-axis test in `FitVerdictLinearTest` and `PlaceVerdictTest`.
   - **If skipped:** the headline verdict stays right, but the number and axis the user acts on ("by how much", "which
     side") can be wrong. That defeats R1's honesty goal on exactly the near-boundary cases it exists for.

2. **K10/K11 seam: `saveMesh` requires the mesh extents to equal the GLB's own bounding box, but §7.1.5
   `MeshExtent.estimate` produces different numbers. As written, every real 3D-scan save throws. This is the open
   "1 cm `heightM`" item.** `data/.../GalleryRepository.kt:241`; `engine/.../GlbWriter.kt:269` (`recentre`);
   `engine/.../recon/MeshExtent.kt:97-99`.
   - **Problem:**
     - `writeMesh` re-centres on the mesh's own AABB, so `extras.heightM = max_y − min_y`. After the C2 floor clip,
       `min_y` is plane + max(1 cm, v).
     - `MeshExtent.estimate` measures H from the support plane (y = 0) to the 99.5th percentile, and W and D as
       0.5–99.5 percentile spreads.
     - `MeshExtents` documents itself as "the mesh's own extents and ±, §7.1.5", and `saveMesh` demands
       `matches(info, e.widthM, e.heightM, e.depthM)` to 0.1 mm.
     - The ± stored beside these extents belongs to the percentile extents, not to the AABB.
   - **Scenario:**
     - Take the `ReconSceneTest` pipeline for the 60 × 40 × 40 cm box.
     - `MeshExtent` H is about 0.400 m, while the GLB `heightM` is about 0.390 m (the clip is at 1 cm). W and D differ
       by the percentile trim.
     - The D-milestone scan screen fills `MeshExtents` from `Extent3`, and `require` throws `IllegalArgumentException`,
       so the scan cannot be saved.
     - If a builder "fixes" it by passing the GLB extras instead, the `mesh_extent_*` columns hold AABB values with a
       ± computed for different quantities. The "3D shape: … ± 3" line is then inconsistent.
     - Placement also drops the mesh 1 cm and centres it on its own AABB (a partial scan is shifted sideways).
     - No test builds a `MeshDraft` from the real recon pipeline. `GalleryPlacementTest` uses hand-made meshes whose
       extents equal their AABB.
   - **Fix (decide before the D milestone; the cheapest consistent option):**
     - `MeshDraft` carries two things: `glbExtents` (checked against the extras, which keeps K8 "extras = own geometry")
       and `meshExtents` (the §7.1.5 values with ±, stored in the columns).
     - Add a `writeMesh` option to keep the input frame (origin = the box base, not the AABB's bottom centre), so a
       placed scan sits on the floor at its measured footprint.
     - Record the decision in phase0.md §5 and §7.1.4, and add one end-to-end test: `fusedBox` → `writeMesh` →
       `saveMesh` → `modelForPlacement`.
   - **If skipped:** the 3D scan's Save fails on first integration, or it stores self-inconsistent numbers.

---

## MINOR

3. **A floor rectangle never checks height, and the card does not say so.** `PlaceVerdict.kt:95-98, 109`.
   - **Problem:** `heightNotChecked` is set only for GAP. A floor AREA compares W × D only.
   - **Scenario:** a fridge in a niche under a cabinet. The user measures the floor rectangle, and the card reads
     "Fits, 3 cm spare" with no "height not checked" note, although the niche is 5 cm too low.
   - **Fix:** set `heightNotChecked = space.kind == GAP || !onWall` and word the note "Height not checked".

4. **The wall-fallback "on the floor" test is only relative to the first tap.** `ar/.../PlacementCore.kt:273-274`.
   - **Problem:** the floor plane is built through tap `a` itself, so `verticalThroughFloorLine`'s 3 cm off-floor check
     only compares `b` with `a`. The tilt check is trivially true.
   - **Scenario:** both taps land on the front edge of a 45 cm-high coffee table. A "wall" is built along the table
     edge, and a frame is hung on it. `floorY` (the lowest horizontal hit) is known and ignored.
   - **Fix:** when `floorY` is known, build the floor plane at `floorY` and reject taps more than `MAX_OFF_FLOOR_M`
     from it. Add a test with two points at the same raised height.

5. **A storage failure or an unreadable row on Place says "This item was deleted".**
   `GalleryRepository.modelForPlacement` (`:548`) and `guardedOrNull` (`:792-798`) turn every exception into `null`,
   and `PlaceViewModel` maps `null` to `PlaceLoad.Deleted`.
   - **Scenario:** the disk is full during the one-time regeneration of an M3 model (glbVersion 1). The user is told
     their item was deleted. §3.6 requires the error card with Back.
   - **Fix:** return a sealed result (`Missing | Failed | Ready`), or rethrow `IOException` so the view model shows
     `Failed`.

6. **Spec drift in Place restore.** `PlaceViewModel.kt`, `PlaceScreen.kt`.
   - **Problem:**
     - §3.4 and §3.6 specify a centre-height slider saved in `SavedStateHandle`, plus a space shown as "measured before
       the pause".
     - The build has only an eye-level button, does not save the height, and has no "before the pause" note.
     - After a pause the old space silently feeds the verdict for a re-placed item, which is acceptable (it is a
       length) but unlabelled.
   - **Fix:** implement the slider and its saved key and add the label, or amend §3.4 and §3.6.

7. **The marching-cubes saturation skip is dead code after C1.** `engine/.../recon/MarchingCubes.kt:46, 109, 112`.
   - **Problem:**
     - With `behindFraction = 0.5`, integration never writes a TSDF value below −0.5 (`TsdfVolume.kt:237-238`).
     - A cube with any inside corner therefore always has a corner with |tsdf| < 0.9, so `allSaturated` is true only
       for all-positive cubes, which `cfg == 0` already skips.
     - The rule the doc describes ("two truncated bands meeting") can no longer fire. The only test,
       `unobservedAndSaturatedCubesAreSkipped`, writes ±1 by hand with `setVoxel`, a value integration cannot produce.
   - **Scenario:** a chair rail or table edge thinner than about 6 cm is carved from both sides. The spurious crossing
     between −0.5 and +1 is not filtered, contrary to the §7.1.4 text.
   - **Fix:** scale the threshold to the asymmetric band (negative at −0.9 × `behindFraction`), or drop the rule and
     the doc claim.

8. **C6 keeps wall fragments, contrary to the doc.** `MeshSegmenter.kt:105-109` and §7.1.4 step 3.
   - **Problem:** the doc says "floaters and wall fragments go", but the 5 % rule keeps any piece with at least 5 % of
     the largest piece's area.
   - **Scenario:**
     - A 2.2 × 0.9 × 1.0 m sofa stands 5 cm from a wall, with the default 10 cm margin.
     - The wall patch inside the volume is about (2.2 + 0.2) × (0.9 + 0.1) ≈ 2.4 m², roughly 30 % of the sofa's
       surface. It is kept.
     - The GLB bounding box, `MeshExtent` D and the cross-check all include it, so the scan reads ROUGH or too deep.
     - The only test drops a 3 cm cube.
   - **Fix:** after C2, drop components that touch the volume's outer margin (outside the measured box), or fix the
     doc and rely on the crop step. Add a sofa-near-a-wall scene test.

9. **The decimator's working set is about 21 MB for the sofa, against a §7.1.6 estimate of "≤ ~10 MB", while the
   12.6 MB TSDF is still alive.** `VertexClusterDecimator.kt:82-83, 135`, and the pipeline order in §7.1.4.
   - **Problem:** each `LongIntMap(n)` takes the next power of two ≥ 2n + 2 slots at 12 B each. For the 133,588-triangle
     sofa:
     - `edgeUse(3T)` takes 1,048,576 slots, about 12.6 MB.
     - `triMap(T)` takes 524,288 slots, about 6.3 MB.
     - `cellMap(V)` takes about 3 MB.
     - The pipeline keeps the volume until `DepthNoise.from(vol)`, which comes after decimation, so the peak is about
       12.6 + 21 + the meshes, roughly 40 MB.
   - **Scenario:** a noisy real capture produces about 2× the synthetic triangle count. The spike's "peak extra PSS
     ≤ 40 MB" criterion (§7.1.10 item 3) fails on memory the algorithm doesn't need.
   - **Fix:**
     - Compute `DepthNoise` and `observedFraction` before meshing, then release the volume.
     - Size `edgeUse` by about 1.5T (a closed-ish mesh has E ≈ 1.5T).
     - Record the real figures in §7.1.6.

10. **Recon test quality: some assertions could pass with a wrong implementation, and others have thin margins.**
    `ReconSceneTest.kt:167`, `MeshProcessingTest.kt:156-162, 182`.
    - **Coverage test:** it asserts only "≥ 95 % covered". A ± inflated tenfold passes, and the doc already reports a
      ± about 3.7× the worst error. Add an upper bound, for example mean ±95 ≤ 4 × the 95th-percentile error, so an
      over-wide ± fails.
    - **Hausdorff test, original → decimated direction:** "≤ cell·√3" is not guaranteed by construction. A cluster
      whose triangles all collapse has no output vertex (`outId < 0`), so its original vertices may be further away.
      It passes today with fixed seeds, but an upstream change to the fused mesh can flip it (see the decimator note
      below).
    - **Sofa bounding-box check:** it asserts ≤ 5 mm, and §7.1.4 reports "≤ 5 mm" measured. The margin is unknown.
      Print it, as the box test does.

11. **The template regeneration source is not wired.** `app/.../AppContainer.kt:48` calls
    `GalleryRepository.create(appContext)` without `templates = …`, although phase0.md §5 says the orchestrator wires
    Builder B's registry there.
    - **Scenario:** once templates are saved, any future `GLB_VERSION` bump, or a missing template file, makes Place
      show the plain measured box with no furniture shape, silently.
    - **Fix:** pass B's registry as a `TemplateModelSource` when it lands, and add a test in `AppContainer` (or a TODO
      tied to the T milestone).

12. **The origin → box-centre rule is implemented three times.** `GlbWriter.anchorOffset` (`GlbWriter.kt:323-327`),
    `PlacementMath.attachOffset` (`PlacementMath.kt:106-114`) and `PlacementCore.centreHeight`
    (`PlacementCore.kt:359-363`).
    - **Scenario:** a fourth `BoxOrigin` (for example TOP_CENTER for pendant lamps) is added to the writer only.
      Placement then hangs items offset by half their size, and the eye-level readout is wrong.
    - **Fix:** have one owner in `:engine` (for example `BoxOrigin.centreOffset(w, h, d): Vec3`) and use it from all
      three places.

13. **Small per-frame allocations remain in the Place loop.** `PlacementController.kt:87-94, 208`;
    `PlacementCore.kt:88-97`.
    - **Problem:** per frame there is a `PlacementStatus` data object (built even when the publish is throttled), two
      `KClass` references from `state::class`, `SurfaceHit`, and three or four `Pose`/`Vec3`/`Quaternion` values.
    - **Scenario:** at 30–60 Hz this is a steady garbage stream in exactly the loop the M4 30 fps gate profiles. It is
      small next to the remaining `HitSample` and `ViewProjection` allocations, and that is why this is MINOR.
    - **Fix:** compare `state` kinds with `is` checks, and build the status only when publishing.

---

## The `VertexClusterDecimatorTest` failure (Builder A's run)

**Read: it is not flaky by construction. The most likely cause is a mixed working-tree snapshot.**

**Why the code is deterministic:**
- **Data structures:** no `HashMap` or `HashSet` and no iteration in identity-hash order. `LongIntMap` is open
  addressing with fixed Fibonacci hashing, and clusters are numbered in vertex order.
- **Inputs and threading:** all randomness is seeded (`Random(0)`, `Random(3)`), and everything runs on a single
  thread.
- **Shared state:** recon has no mutable global state (`McTable` is immutable after class init). JUnit 4 builds a new
  test-class instance per test, so `fused by lazy` is not shared. Gradle runs the classes sequentially in one worker.
- **Floating point:** this is IEEE-strict Kotlin/JVM, with no automatic FMA, so the same inputs give the same bits.
- `sofaSizeMeshDecimatesTo20kAndIsDeterministic` itself asserts bit-identical repeat output, and it passed.

**Why a mixed snapshot is the likely cause:**
- The builders share one working tree. Builder C edited recon between 16:14 and 16:40:
  - `TsdfVolume.kt` at 16:14
  - `VertexClusterDecimator.kt` and `TsdfVolumeTest.kt` at 16:24:43
  - `MeshSegmenter.kt` and `MeshProcessingTest.kt` at 16:28
  - `MarchingCubes.kt` and its test at 16:40
- A run started in that window could compile a new decimator against an old test (or an old fused mesh against new
  thresholds).
- The latest result (16:47) passes 4/4 with margin: bounding-box change 1.77 mm against 5 mm, and 19,898 against
  20,000 triangles.

**Residual risk:** the assertions in MINOR 10 (the unguaranteed Hausdorff direction and the unknown sofa margin) are
where a legitimate upstream change, such as tuning C1 or C3, would show up as a "random" failure.

**Recommendation:** if it recurs on a clean tree, keep the XML (with `system-out`), because the printed per-bound
deltas show which assertion moved.

---

## Checked and OK

- **R1 and D6:**
  - `FitPolicy.combination` defaults to LINEAR, and RSS is explicit opt-in.
  - The boundaries are tested at ± 0.1 mm, with a typed side, a frame turned portrait/landscape, a floor item turned
    W↔D, and `minClearance`.
  - The R1 sofa reads TIGHT.
  - Item σ is the rounded-up stored bound95 / 1.96, and typed or preset sides have σ = 0 and are labelled.
  - Amounts round cautiously: spare down, overshoot and band up, in whole cm or inches.
  - TV is NONE, frame is ANY, and a floor rectangle is ANY.
- **K8 and ADR-003:**
  - Writer: `extras` carry W/H/D (the GLB's own bounding box), `origin`, `glbVersion = 2` and `modelKind`. The geometry
    is translated, never scaled. `KHR_materials_unlit` is in `extensionsUsed` and on the textured front only, and the
    sides stay lit.
  - Mesh output: u16 indices only when V ≤ 65,535, so index 65,535 never occurs. `COLOR_0` is RGBA8 normalised.
  - Reader: `GlbReader` reads only the header and the JSON chunk, never throws, and its number grammar accepts the
    writer's `1.0E-4` form.
- **True scale:**
  - `PlacedModel` passes `scaleToUnits = null` and `centerOrigin = null`, editing is off, and `NodeHolder` never
    touches scale.
  - BOTTOM_CENTER on the floor puts the origin at the hit. BACK_CENTER on a wall is flush (back face at distance 0), as
    are BACK_CENTER on a table and BOTTOM_CENTER on a wall (checked by hand through `attachOffset`).
  - The placed pose is recomputed every frame from the anchor plus a local offset, so it neither drifts nor
    accumulates.
- **R3 pooling (`MeasureSession`):**
  - Samples come from an 8-slot `ArrayBlockingQueue`.
  - A sample dropped by DROP_OLDEST returns through `onUndeliveredElement`. A failed `trySend` (closed channel, where
    no undelivered callback runs) is offered back exactly once.
  - The worker returns each sample in `finally`, after its last read. `LocalSurfaceModel.addPoints` copies the cloud,
    and `Reading` does not retain the endpoint list, so no pooled buffer escapes a frame.
  - Queue offer and poll give the happens-before edge between main and the worker.
  - `latestCentreHit` is reset at the start of each frame. The dispatcher calls listeners in list order, with
    `MeasureSession` first.
  - `MeasureSession.kt` grew from 1000 to 1039 lines. All of that is the mandated R3 pooling plus the §3.5 shared hit,
    with no new feature logic.
- **Room v3:**
  - The migration matches `3.json` (verified by script and by `MigrationV3SchemaTest`). Existing rows backfill to
    PROXY_BOX, and the other columns are NULL-tolerant.
  - **Regeneration:** new file, then one UPDATE, then delete the old file, under `writeLock` and `NonCancellable`, with
    a re-check under the lock.
  - **Meshes:** never regenerated; they fall back to a cache box, and the row is untouched.
  - **K10:** `saveTemplate` and `saveMesh` both commit through `commitLocked` → `commit` → `check(bytes ≤ MAX_SCAN_BYTES)`.
- **AR lifecycle:**
  - The anchor is detached in `onSessionPaused` while the session is still live, and also on pickUp, on a target
    change and on a failed drop.
  - `close()` only drops references.
  - A repeated frame timestamp changes nothing.
  - Pause drops the fallback plane and offers the fallback again.
  - Process death restores the target, the spin and the space reading.
  - The lights requirement is set by `PlacementController` only, and the host keeps SceneView's lights only then.
- **Recon:**
  - **Truncation:** μ = max(4 cm, 3v).
  - **Weights:** `round(2·conf/255·s/z²)`, clamped to 1..64, saturating at 127. There is no Int overflow
    (127·32767 + 64·32767).
  - **Ray walk:** a projective SDF in z-depth units, at v/2 Euclidean steps. Its NGP cells are monotone, so the
    "last" dedupe is enough.
  - **C1:** the behind band ends at 0.5μ.
  - **C2:** Sutherland–Hodgman clipping with welded cut vertices.
  - **C3:** the QEM point is clamped to the cell's vertex box, so the output never leaves the input's bounding box.
  - **C4:** the generated table gives consistent face segments and face-safe fans. All 256 cases and random fields
    are tested watertight.
  - **C5:** never smaller than the original form, since max(L, √2·r̄) ≥ √2·r̄.
  - **Memory:** at `N_MAX`, `VolumeSpec` guards `dims` product ≤ Int.MAX/3. `VolumeSizing` grows the voxel until the
    fence-posted count fits, and the 2.5 m cap keeps the voxel under 3 cm (worst case about 2.7 cm on low RAM).
- **K2:**
  - Place uses `ArScreenAdGuard`, never enters a Measure visit and never calls `recordCompletedScan`.
  - Saved → Place calls `onVisitLeftWithoutAd`, which drops any pending chance.
  - The gallery item that resumes after Place finds nothing to consume. `MeasureVisitTrackerTest` covers both paths.
- **K1 and K5:** Place imports only `app.pro.ProFeature`, never `app/billing`. `onOpenPaywall` is accepted and
  unused, because Place is Free.
- **Strings:** all 66 `place_*` keys have Hindi, including `gallery_kind_furniture`, and the placeholders agree.

## Missing tests

- A mixed-axis verdict (MAJOR 1): one axis TOO_BIG, the other TIGHT with a wide band; and a FITS case where the
  smallest clearance is not on the min-score axis.
- The real recon pipeline into `writeMesh`, `saveMesh` and `modelForPlacement` (MAJOR 2).
- A floor AREA showing the "height not checked" note (MINOR 3). A fallback line on a raised surface (MINOR 4).
- `PlaceViewModel`: a repository failure shows Failed, not Deleted (MINOR 5).
- A `MeasureSession` pool test: no sample in two places at once, and a drop-oldest return, using a fake channel or an
  extracted `SamplePool`.
- Recon: a sofa-near-a-wall scene (MINOR 8), an upper bound on the ± (MINOR 10), and integration producing a
  thin-part case for the saturation rule (MINOR 7).

## Verdict

**Mergeable for the device gate after MAJOR 1. MAJOR 2 must be resolved before the D (3D scan) milestone integrates
`saveMesh`.** No BLOCKERs. The MINORs can ship with tickets. None of the findings is a regression of pre-existing
behaviour; all are in new code.

## Not checked

- **Nothing was run:** no Gradle, no tests and no device. The pass/fail claims above are the orchestrator's and the
  latest result XML's, not mine.
- **SceneView 4.65 behaviour (bytecode not inspected this time):**
  - whether `Node(apply = …)` re-runs `apply` on recomposition. If it does, `NodeHolder.set` re-hides the model at the
    HUD's 10 Hz recomposition rate, and it would flicker for up to a frame.
  - `createModelInstance(ByteBuffer)` buffer lifetime.
  - whether `KHR_materials_unlit` is honoured, and the mipmaps for `minFilter 9987`.
- **On a device:**
  - 30 fps on Place.
  - colour fidelity of the unlit photo face, and lighting on the Place host.
  - real ARCore hit normals on point hits.
  - thermal behaviour and peak PSS of recon.
- **Not read line by line:** `GlbWriterV2Test` (only skimmed), `SyntheticScenes.kt`, and `SymmetricEigen3` (assumed
  deterministic; it is pre-existing engine code).
- **Out of scope:** Builder B's Pro, billing, ads, Settings and AppContainer changes, beyond K1, K2 and K5 and the
  template-source wiring line.
- **Not checked in detail:** the CI workflow, the manifest and backup-rule diffs, and the `SpaceView.html` and
  `HANDOFF.md` content.
