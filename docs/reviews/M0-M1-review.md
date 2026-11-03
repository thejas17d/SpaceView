# Review: M0 (scaffold and CI) + M1 (`:engine`), commit f6ac685

Reviewer agent, 2026-11-03. I read every file in `engine/src/main`, all engine tests, the CI workflow, the Gradle files, `ci/check_native_libs.py`, the manifest (source and merged release) and the R8 setup. I checked them against `docs/PLAN.md`, `docs/research/measurement-engine.md` and `docs/research/android-performance.md`.

**How the findings were verified.** Each suspicion was tested with a throwaway JUnit probe, which has since been deleted. The tree is clean apart from other agents' work in progress. The probe numbers below come from that run. GLB validity was checked with the official Khronos `gltf-validator` 2.0.0-dev.3.10, run from the scratchpad. After the probe was deleted, `:engine:test` ran green with all 119 tests passing.

**Line numbers** refer to commit f6ac685. Builder B's uncommitted M5 work has since changed `android.yml`, `app/build.gradle.kts` and the manifest, so lines in those files may have shifted. The `engine/` and `ci/` folders are unchanged.

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 5 |
| MINOR | 17 |

The maths is mostly correct and carefully written. The median/MAD, quickselect, Jacobi eigen-solver, Hartley normalisation, quaternion and pose algebra, correlated-scale propagation for area and volume, the fit-verdict bands and the feet-inches carry are all correct. The GLB passes the Khronos validator with **0 errors and 0 warnings**.

The real risk is **honesty of the ±**. The engine's ± reaches about 95% only if every caller adds terms the API does not ask for. The autocorrelation correction under-covers on realistic, correlated AR samples. Neither problem is a bug in the arithmetic. Both mean that, as wired today, the app would show ranges that are too small. Fix them before M2 wires `:ar` into the engine.

---

## 1. `engine/src/main` correctness

### MAJOR-1: The public distance and rectangle APIs give about 32% coverage, not 95%, unless the caller remembers to add endpoint-localisation error
`Uncertainty.kt:189` (`UncertaintyModel.distance`), `Uncertainty.kt:164-186`, `PointEstimator.kt:184`, `Shapes.kt:66`

`PointEstimate.point.covariance` is the **standard error of the mean** of per-frame jitter. The user's aiming or tap error and the hit-test bias stay the same for the whole 0.5–1 s hold, so averaging cannot see them. `distance()`, `rectangle()` and `Calibrator` take that covariance as the whole random error. The only thing that adds the localisation term is an optional `withExtraSigma()`, and the only test that proves 95% coverage (`displayedBoundCoversTruth`) calls it by hand.

**Failing scenario (probe).** Endpoints have a 3 mm per-axis aim error (about 1.5 px at 1 m on VGA) plus 1 mm frame jitter, 30 samples, NO_DEPTH tier, fed through `PointEstimator` → `model.distance()`:

| True length | Mean shown ±95 | Actual coverage |
|---|---|---|
| 8.56 cm (card) | ±1.8 mm | **32%** |
| 30 cm | ±6.0 mm | **75%** |
| 1 m | ±2.0 cm | 92% (scale term dominates) |

The same flaw feeds calibration. A card or A4 measured this way reports a factor σ that is far too small. The `TOO_NOISY` gate (`Calibration.kt:148`) then never trips, and the calibrated ± falls to the 0.3% floor, which the user sees as a "precise" ±0.6%.

**Fix.** Make the safe path the only path:
- Have `distance`, `height`, `rectangle` and `boxFromPoints` take an endpoint type that carries the localisation inputs (range, focal px and pixel error, or an explicit σ), for example `EndpointObservation(estimate, rangeM, focalPx, pixelSigma)`.
- Alternatively, give `UncertaintyModel` a required `minEndpointSigma(range)` that is always added in quadrature.
- Also consider the research's absolute floor ("never better than ±1 cm" for non-LiDAR Android) as a tier-level `absFloor95`.
- Add a test that runs the scenario above and requires coverage ≥ 0.9. It fails today.

### MAJOR-2: The autocorrelation correction under-covers on correlated AR samples (55–82% instead of 95%)
`PointEstimator.kt:179-184`, `PointEstimator.kt:205-206` (`MAX_RHO = 0.9`)

Consecutive AR frames are strongly correlated: hand tremor and slow VIO wobble are low-frequency. Probe: AR(1) jitter with σ = 3 mm, 30 samples, checking the true point against ±1.96·SE along x:

| ρ | Coverage | Mean n_eff |
|---|---|---|
| 0.5 | 0.90 | 12.5 |
| 0.8 | **0.82** | 5.9 |
| 0.9 | **0.70** | 4.3 |
| 0.95 | **0.55** | 3.6 |

There are four causes:
1. The lag-1 ρ estimate from 30 samples is biased low (about −(1+4ρ)/n).
2. ρ is capped at 0.9.
3. With strong correlation, the sample variance itself underestimates the process variance.
4. k = 1.96 is used even when n_eff is about 3–6 (Student-t would be about 2.6–4.3).

Existing tests miss this. `standardErrorIsCalibrated` turns the correction **off** and uses i.i.d. data. `autocorrelationInflatesStandardError` only checks that the SE grows.

**Fix.** Any of these closes most of the gap:
- Use the bias-corrected ρ: ρ̂ + (1+4ρ̂)/n.
- Raise the cap to about 0.97.
- Apply the variance correction to `sampleCov` as well: multiply by 1/(1 − 2ρ/(n(1−ρ))).
- Use a Student-t factor for n_eff < 30. Simplest of all, floor `nEff` at a physically motivated maximum, for example "≤ 1 independent sample per 100 ms of hold".

Add an AR(1) coverage test at ρ = 0.9 that requires ≥ 0.9.

### MAJOR-3: The rectangle badge ignores geometric consistency, so a wrong number can be shown as FAIR or GOOD
`Shapes.kt:39`, `Shapes.kt:66-116`

`RectangleMeasurement.quality` is `worst(width, height)`. Neither of those reflects corner order, squareness or planarity. The research (§2.6) says the angle deviation must feed the uncertainty. Probes:
- **Bow-tie (Z) tap order.** Corners TL, TR, BL, BR on a 60 × 40 cm frame give `H = 72.1 cm ± 1.5` (true 40 cm), badge **FAIR**, `maxRightAngleDeviationDeg = 56°`. Both "height" edges are diagonals and agree with each other, so the half-difference σ guard does nothing.
- **Corners 4 cm off the supplied plane** (bad depth or wrong plane), DEPTH_SENSOR tier: `planarityRms = 0.04`, `W = 60 cm ± 0.5`, badge **GOOD**.

**Fix.**
- Reorder the snapped corners by angle around their centroid in the plane before computing edges, or reject non-convex and self-intersecting quads, as `Homography.rectify` already does.
- Fold `maxRightAngleDeviationDeg`, `diagonalMismatch` and `planarityRms` into the width/height σ, for example σ_geom = ½·|diag mismatch| and planarity × tan(view angle).
- Force ROUGH when `!isSquare` or when `planarityRms` is above about 1 cm.

### MAJOR-4: Plane fitting accepts near-collinear (strip) point sets and returns a confident, tilted plane
`Plane.kt:260`, `Plane.kt:145`

The degeneracy test only rejects λ₁ ≤ 1e-12·λ₂, which means exactly collinear points. Probe: 200 depth or feature points in a 1 m × 2 cm strip on a wall (a skirting line, a narrow textured band on a plain wall, the edge of a frame), with 3 mm noise. RANSAC returns a fit **200/200 times**, with normal tilt up to **8.4°**, a small `rmsResidual` and a tiny `offsetStandardError`.

At 30 cm from the strip, 8.4° puts the plane about 4.4 cm off. Every ray–plane corner beyond the strip inherits that error, yet the ± contains no term for it. Plain painted walls are the research's top risk (§6.4-1), and these inputs are exactly what such walls produce.

**Fix.**
- Reject or flag fits where √λ₁ (in-plane minor extent) is less than about 10 × rmsResidual, or λ₁/λ₀ < about 25.
- Expose a normal-angle σ ≈ rms / (√λ₁ · √n) in `PlaneFit`, and propagate it as a lateral term proportional to the distance from the centroid.
- Add a test: a strip fit must be null or flagged.

### MINOR-1: `PointEstimator.estimate()` can return NaN, and the next engine call crashes
`PointEstimator.kt:159-160`

The comment "m ≥ 1 always" is false. Being within one MAD holds per axis, not jointly. Probe: with 15 samples, give each axis a different majority subset of exact-equal values so the three subsets have no common member. The result is `inlierCount = 0` and a NaN position. `model.distance()` then throws `IllegalArgumentException` from `RawLength.init`, which would happen on the AR frame thread. It is rare, because it needs strongly clustered or quantised samples, but the fix is trivial.

**Fix.** `if (m == 0)` → fall back to the per-axis medians with SE = 1.2533·MAD/√n, or return null and mark the estimate unstable.

### MINOR-2: `planeBiasRel` divides by the wrong distance
`Uncertainty.kt:259-262`

A plane offset δ scales ray-intersected lengths by δ/h, where h is the **perpendicular** camera-to-plane distance, not the viewing range. If a caller passes the range h/cosθ, the term is too small by cosθ: 0.71 at 45°.

**Fix.** Take `cameraPosition: Vec3` and compute `abs(fit.plane.signedDistance(cam))`. Also consider adding the plane term inside `rectangle()` automatically when a `PlaneFit` is supplied.

### MINOR-3: The rectangle snaps corners by orthogonal projection, not ray–plane intersection
`Shapes.kt:73-78`

A corner off the plane by δ along the viewing ray keeps a lateral error of δ·sinθ after orthogonal projection. Ray intersection, as the research (§2.3 and §6.2) prescribes, removes it. The covariance shown is also wrong, because projection drops the normal component without adding the lateral leak.

**Fix.** Document that M2 must pass ray-intersected corners (`Plane.intersect`), or add a `rectangleFromRays(rays, planeFit)` overload.

### MINOR-4: A calibration can be accepted even when it makes the ± wider
`Calibration.kt:84`, `Uncertainty.kt:146-150`

`MAX_REL_SIGMA = 1%` is looser than the NO_DEPTH prior σ (2%/1.96 = 1.02%). Probe: a calibration with relSigma 0.99% is accepted. With the floor the effective σ becomes **1.034%**, which is wider than the uncalibrated 1.020%, while the app tells the user they are in "precision mode".

**Fix.** Accept only when √(relSigma² + floor²) < about 0.7 × the tier prior.

### MINOR-5: The calibration factor band and scope differ from the research
`Calibration.kt:80-81`

- The research says to apply s only when 0.9 < s < 1.1. The code allows 0.85–1.15. A 12–15% scale error means tracking is broken, so refuse it.
- `UncertaintyModel.withCalibration` applies the factor to every measured length in the session. The research says the factor corrects scale "for that plane and session". Depth bias is distance- and plane-dependent (δZ/Z).

**Fix.** Store the plane and range in `ScaleCalibration`. Widen the floor or skip the factor for other planes, or for ranges outside about ±50% of the calibration range.

### MINOR-6: A box with an unmeasured preset depth and σ = 0 is badged GOOD
`Shapes.kt:50`, `Uncertainty.kt:44`

A wall-hung frame with the 2 cm "thin frame" preset passed as `RawLength(0.02, 0.0, isMeasured = false)` gives a GOOD box. The research (§3.3) says D must be labelled "low confidence" or "estimate".

**Fix.** Exclude unmeasured dimensions from `BoxMeasurement.quality`, and expose an explicit `isEstimate` flag per dimension for the UI.

### MINOR-7: `FitPolicy.allowRotation` also lets the item be tipped onto its side
`FitVerdict.kt:73-74`

All 3! permutations are tried, so a 180 cm fridge can "fit" a 70 cm-high niche lying down. The test `rotationFindsBestOrientation` encodes exactly this for 2D (W↔H).

**Fix.** Add `rotation = NONE | ABOUT_VERTICAL | ANY`, with `ABOUT_VERTICAL` as the default. It permutes only the horizontal axes, W and D.

### MINOR-8: Homography edge cases
`Homography.kt:85-90`, `Homography.kt:73`, `Homography.kt:44`

- `rectify` accepts either winding. Corners given counter-clockwise on screen (TL, BL, BR, TR) produce a valid H that **mirrors** the texture (verified: accepted). **Fix:** require the clockwise image-space sign, or reorder the corners.
- The DLT fixes h₃₃ = 1 in normalised coordinates. This fails if the source centroid maps to infinity. That cannot happen for convex→convex quads, but `fromCorrespondences` is public. **Fix:** document this, or add an SVD fallback.
- `det > 1e-300` is not scale-aware. Use a relative test.

Conditioning is fine: 4K-pixel corners give a max reprojection error of 9e-13 px.

### MINOR-9: Display rounding chooses the step from the unrounded ±
`Units.kt:135-156`

A ± of 0.999 cm gives `"45.5 cm ± 1.0"`, so the value shows 0.1 cm resolution against a ±1 cm range. `stepFor` sees 0.999, whose leading place is 0.1, and the ± is then rounded up to 1.0. Similarly, `"0.02 m ± 0.020"` shows the ± with more decimals than the value.

**Fix.** Round the ± to its displayed significant figures first, then derive the step from the rounded value.

Checked and correct: `6′ 0″` (no `5′ 12″`) at 71.97 in and 71.99 in, `24 in` at 23.97 in, negative values, `-0` suppression, Hindi and German locales, and fraction reduction.

### MINOR-10: The GLB is valid, with small polish items
`GlbWriter.kt:149-152`, `GlbWriter.kt:164`

The Khronos validator reports **0 errors and 0 warnings** for textured, untextured and extreme-size boxes (0.1 mm × 50 m). It reports two infos:
- `UNUSED_OBJECT TEXCOORD_0` on the untextured primitive(s).
- `IMAGE_NPOT_DIMENSIONS` when the JPEG is not a power of two. `Homography.outputSize` produces 1024×683 while the sampler asks for `LINEAR_MIPMAP_LINEAR`.

Manually verified:
- bufferView offsets are 4-aligned; there is no byteStride (tightly packed); the index accessor offset 12 is a multiple of 2; the image bufferView has no `target`.
- POSITION has min/max and they match the floats exactly.
- JSON padding is 0x20 and BIN padding is 0x00; all fields are little-endian.
- All 12 triangles are counter-clockwise from outside (r × u = n for every face), so nothing is culled. UV (0,0) is top-left.

**Suggestions:**
- Use a separate UV-less attribute set for the sides, or accept the info.
- Either produce power-of-two textures or use `minFilter` LINEAR (9729), because Filament mip-maps NPOT textures but some viewers (Scene Viewer) may not.
- Document that callers must bake JPEG EXIF orientation, since glTF ignores EXIF.
- Add `gltf-validator` (npm) to CI on a generated fixture.

### MINOR-11: Quality thresholds disagree with the research colour bands
`Uncertainty.kt:56-82`

The research (§2.10) says green means ≤ 1%. `GOOD_ABS = 5 mm` gives GOOD to a card at ±4 mm (4.7%). Also, an uncalibrated NO_DEPTH or DEPTH device can **never** show GOOD for anything over 25–50 cm, because the scale prior alone is 1.5–2%. Both may be intended, but the UI/UX and Judge agents should confirm them before M2, because the badge is the product's main honesty signal.

### MINOR-12: Hot-path notes
These are not bugs.
- `PointEstimator` is allocation-free per sample, and `estimate()` allocates only its four result objects. That is fine at 30 fps.
- `PlaneFitter.ransac` performs up to 500 iterations × N points, and allocates `BooleanArray`s and list iterators. With 1–2k depth points that is several milliseconds, so it must run off the GL thread (`Dispatchers.Default`), not per frame. Say so in its KDoc.
- `Ray.fromPixel` assumes the intrinsics and pixel coordinates are in the same (sensor/landscape) orientation. ARCore's `getImageIntrinsics()` is in sensor orientation, while touch coordinates are in display orientation. Document that the caller must convert with `Frame.transformCoordinates2d`.

---

## 2. Tests

The 119 tests pass. They are well targeted at arithmetic, but several "honesty" tests are **tautological**: they generate data from exactly the model's own assumptions.

- `UncertaintyTest.displayedBoundCoversTruth` and `ShapesTest.noisyRectangleIsAccurateAndHonest` inject i.i.d. Gaussian noise, draw the scale error from the same tier prior the model uses, and hand the model the exact tap σ they injected. They prove the formulas are internally consistent, not that the ± is honest. Neither covers aim bias that is constant over the hold (MAJOR-1) or correlated frames (MAJOR-2).
- `PointEstimatorTest.standardErrorIsCalibrated` disables the autocorrelation correction. `autocorrelationInflatesStandardError` asserts only that the SE grows by 1.5×, not that coverage is correct.
- `CalibrationTest.calibrationFixesInjectedThreePercentScaleError` is good. It is a real end-to-end check, although its corner σ is again the injected one.
- `GlbWriterTest` is a thorough structural parse. It would gain from a Khronos-validator run (MINOR-10).

### MAJOR-5: The M1 gate "≥ 90% engine test coverage" cannot be verified
`engine/build.gradle.kts`

No coverage tool (Kover or JaCoCo) is configured, so the Judge cannot check the PLAN §6 M1 gate. **Fix:** add the Kover plugin to `:engine`, and run `koverVerify` with `minBound(90)` in CI.

### Important missing test cases
1. Coverage of `distance` and `rectangle` **without** `withExtraSigma`, which fails today (MAJOR-1).
2. AR(1)-correlated samples at ρ = 0.8, 0.9 and 0.95 (MAJOR-2).
3. Bow-tie and reversed corner order, and corners far off the supplied plane (MAJOR-3).
4. RANSAC and least squares on a thin strip, and on two parallel planes (wall plus frame glass 2 cm in front) (MAJOR-4).
5. A PointEstimator input with zero inliers (MINOR-1).
6. A calibration that widens the ±, and a factor of 1.12 that should be rejected (MINOR-4 and MINOR-5).
7. 3D rotation that must not tip the item (MINOR-7).
8. Mirrored-winding `rectify` (MINOR-8).
9. Units at the ± rounding boundaries (0.999 cm, 0.0999 m) and negative feet-inches carry (−11.97 in).
10. `SymmetricEigen3` with repeated eigenvalues, for example a planar disc where λ₁ = λ₂.
11. A GLB checked by the Khronos validator in CI.

---

## 3. Build, CI, manifest and R8

Nothing was found that would definitely break on `ubuntu-latest`. The workflow mirrors Gridlark's action versions and wrapper (same Gradle 9.6.1 SHA). `python3` (3.12) handles `int | None` with `from __future__ import annotations`. The `zipalign -P 16`, `aapt2 dump`, bundletool `get-size total` and `cut -d, -f2` parsing are all correct. AGP auto-installs SDK 37 on hosted runners.

I could not run the workflow because there is no remote yet. Lint for `:ar`, `:data` and `:app` was not re-run locally because of the RAM limit, so the first CI run is still the real test.

The merged **release** manifest was verified (`app/build/intermediates/merged_manifests/release`):
- The only permissions are `CAMERA` and the app-private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which CI filters out. INTERNET and VIBRATE are correctly removed.
- `android.hardware.camera.ar` is required.
- `glEsVersion 0x30000` is required (from SceneView).
- `com.google.ar.core = required` overrides SceneView's `optional`.

R8 runs in full mode with resource shrinking, `proguard-android-optimize.txt` and no blanket keeps. filamat is excluded and also forbidden in the 16 KB check. The engine uses no reflection, so it needs no keep rules.

### MINOR-13: The size gate applies the download budget to the installed size
`android.yml:125`, `android.yml:142`

`INSTALLED` sums the extracted arm64 splits. Native libraries are stored **uncompressed** (`useLegacyPackaging = false`), so the installed size is about 1.4–1.6× the download size. In the local release build, the arm64 `.so` files alone are 6.6 MiB uncompressed and the installed split is roughly 11 MiB, against a 7.2 MiB download. The 18 MiB "download" budget will therefore fail on the installed figure first, around M4/M5 when AdMob arrives, while the real Play download is still about 12 MB.

**Fix.** Gate on `DOWNLOAD` only. Report `INSTALLED` for information, or give it its own larger budget.

### MINOR-14: Release-config guard misses the keystore passwords
`app/build.gradle.kts:12-19`

On tag builds, `REQUIRE_RELEASE_CONFIG` checks `KEYSTORE_FILE` but not `KEYSTORE_PASSWORD`, `KEY_ALIAS` or `KEY_PASSWORD`. A tag build with the keystore secret but a missing password fails late in `packageRelease` with an unclear signing error. **Fix:** add the three passwords to `missing`.

### MINOR-15: Some CI gates from the research are missing
`android.yml:47-53`

`android-performance.md` §7.5 asks for the following, none of which is present:
- `:app:lintRelease`. Only `lintVitalRelease` runs, through `checkReleaseBuilds`.
- A dependency guard (a `releaseRuntimeClasspath` baseline diff).
- An `r8.json` artifact extracted from the AAB.
- A 16 KB-page emulator smoke run.

None of these is needed for M0's gate, but the dependency guard in particular catches mediation SDKs sneaking in at M5.

### MINOR-16: The permission pipeline's `|| true` also hides aapt2 failures
`android.yml:103-105`

`|| true` masks a failure of `aapt2` itself, which would produce an empty list. The following `diff` still fails the step, so the impact is only a confusing message. **Fix:** run `aapt2` to a file first under `set -e`, then filter.

### MINOR-17: The `.gitattributes` and gradlew setup is fine but redundant
`gradlew` is committed as 100755 with LF line endings, and CI also runs `chmod +x`. No action is needed. This note only closes the HANDOFF's `update-index --chmod=+x` to-do.

---

## Recommended order of fixes before M2 wiring
1. MAJOR-1: an endpoint type that carries the localisation σ, plus an absolute floor. Without it every M2 number is over-confident.
2. MAJOR-2: autocorrelation and Student-t fix, plus a correlated-coverage test.
3. MAJOR-3 and MAJOR-4: geometric-consistency gating for rectangles and plane fits.
4. MAJOR-5: Kover with a 90% gate.
5. The MINOR items can land with M2 or M6.
