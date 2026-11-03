package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic regression tests for docs/reviews/M0-M1-review.md (engine findings). */
class ReviewFindingsTest {

    private fun loc(p: Vec3, sigma: Double) = UncertainPoint(p).withLocalisationSigma(sigma)

    // ---- MAJOR-1: localisation is part of every endpoint ----------------------------------------

    @Test fun localisationInput() {
        val l = LocalisationInput(1.0, 500.0)
        assertEquals(2.0, l.pixelErrorPx, 0.0)
        assertEquals(0.004, l.sigmaMeters, 1e-15)
        assertEquals(0.002, LocalisationInput(3.0, 1500.0, 1.0).sigmaMeters, 1e-15)
        assertEquals(LocalisationInput(1.0, 500.0, 2.0), l)
        for (bad in listOf({ LocalisationInput(-1.0, 500.0) }, { LocalisationInput(1.0, 0.0) },
            { LocalisationInput(1.0, 500.0, -1.0) }, { LocalisationInput(Double.NaN, 500.0) })) {
            try { bad(); throw AssertionError("accepted invalid input") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun tierFloors() {
        assertEquals(0.010, DeviceTier.NO_DEPTH.absFloor95, 0.0)
        assertEquals(0.010, DeviceTier.DEPTH.absFloor95, 0.0)
        assertEquals(0.005, DeviceTier.DEPTH_SENSOR.absFloor95, 0.0)
        for (t in DeviceTier.entries) {
            assertEquals(t.absFloor95, Uncertainty.K95 * sqrt(2.0) * t.unlocalisedEndpointSigma, 1e-15)
        }
    }

    @Test fun distanceNeverCollapsesToJitterWithoutLocalisation() {
        for (tier in DeviceTier.entries) {
            val model = UncertaintyModel(tier)
            // Two perfectly still endpoints (zero jitter): the ± must still be at least the tier floor.
            val d = model.distance(UncertainPoint(Vec3.ZERO), UncertainPoint(Vec3(0.0856, 0.0, 0.0)))
            assertTrue("$tier ${d.bound95}", d.bound95 >= tier.absFloor95 - 1e-12)
            // Same through the raw path the AR layer uses (rawDistance + length).
            val raw = Uncertainty.rawDistance(UncertainPoint(Vec3.ZERO), UncertainPoint(Vec3(0.0856, 0.0, 0.0)))
            assertEquals(2, raw.unlocalisedEndpoints)
            assertEquals(d.sigma, model.length(raw).sigma, 1e-15)
        }
        // Localised endpoints use their own σ instead of the floor (here 0.5 mm each).
        val model = UncertaintyModel(scaleSigmaRel = 0.0)
        val a = UncertainPoint(Vec3.ZERO)
        val b = UncertainPoint(Vec3(1.0, 0.0, 0.0))
        val l = LocalisationInput(0.5, 2000.0, 2.0)
        assertEquals(sqrt(2.0) * 0.0005, model.distance(a, b, l, l).sigma, 1e-15)
        // One localised, one not: floor for one endpoint only.
        val f = DeviceTier.NO_DEPTH.unlocalisedEndpointSigma
        assertEquals(sqrt(0.0005 * 0.0005 + f * f), model.distance(a, b, l, null).sigma, 1e-15)
        assertEquals(1, Uncertainty.rawDistance(a.withLocalisation(l), b).unlocalisedEndpoints)
        // Height / horizontal take localisation too.
        assertEquals(sqrt(2.0) * 0.0005, model.height(a, Vec3(0.0, 1.0, 0.0).let { UncertainPoint(it) }, Vec3.UP, l, l).sigma, 1e-15)
        assertEquals(sqrt(2.0) * 0.0005, model.horizontalDistance(a, b, Vec3.UP, l, l).sigma, 1e-15)
        // Typed (unmeasured) lengths never get the floor.
        assertEquals(0.0, model.length(RawLength(0.02, 0.0, isMeasured = false, unlocalisedEndpoints = 2)).sigma, 0.0)
    }

    @Test fun localiseDoesNotDoubleCount() {
        val model = UncertaintyModel()
        val p = UncertainPoint(Vec3.ZERO).withLocalisationSigma(0.003)
        assertSame(p, model.localise(p, LocalisationInput(1.0, 500.0)))
        assertSame(p, model.localise(p))
        val q = model.localise(UncertainPoint(Vec3.ZERO))
        assertTrue(q.includesLocalisation)
        assertEquals(DeviceTier.NO_DEPTH.unlocalisedEndpointSigma, sqrt(q.covariance.xx), 1e-15)
        // withExtraSigma keeps the flag as it was; movedTo keeps covariance and flag.
        assertFalse(UncertainPoint(Vec3.ZERO).withExtraSigma(0.002).includesLocalisation)
        assertTrue(p.withExtraSigma(0.001).includesLocalisation)
        val m = p.movedTo(Vec3.UNIT_X)
        assertTrue(m.includesLocalisation && m.position == Vec3.UNIT_X && m.covariance.xx == p.covariance.xx)
        // Snapping to a plane keeps the flag.
        assertTrue(Plane(Vec3.UNIT_Z, 0.0).project(p).includesLocalisation)
        assertTrue(p.toString().contains("localised=true"))
    }

    @Test fun pointEstimatorTakesLocalisation() {
        val est = PointEstimator()
        val rng = Random(3)
        repeat(30) { est.add(Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * 0.001) }
        val plain = est.estimate()!!
        assertFalse(plain.point.includesLocalisation)
        assertEquals(0.0, plain.localisationSigma, 0.0)
        val l = LocalisationInput(1.0, 500.0)
        val withLoc = est.estimate(l)!!
        assertTrue(withLoc.point.includesLocalisation)
        assertEquals(0.004, withLoc.localisationSigma, 1e-15)
        assertEquals(plain.point.covariance.xx + 0.004 * 0.004, withLoc.point.covariance.xx, 1e-15)
    }

    @Test fun calibrationScope() {
        val wall = Plane(Vec3.UNIT_Z, 1.5)
        val c = ScaleCalibration(0.97, 0.001, plane = wall, anchor = Vec3(0.0, 1.0, -1.5), rangeM = 1.0)
        assertTrue(c.appliesTo(wall, 1.2))
        assertTrue(c.appliesTo(null, null))
        assertFalse(c.appliesTo(wall, 0.4)) // < 50% of the calibration range
        assertFalse(c.appliesTo(wall, 1.6)) // > 150%
        assertFalse(c.appliesTo(Plane(Vec3.UNIT_X, 0.0), 1.0)) // perpendicular wall
        assertFalse(c.appliesTo(Plane(Vec3.UNIT_Z, 1.4), 1.0)) // parallel but 10 cm in front
        assertTrue(c.appliesTo(Plane(Vec3.UNIT_Z, 1.48), 1.0))
        // Without an anchor the plane's own closest point is used.
        assertTrue(ScaleCalibration(1.0, 0.001, plane = wall).appliesTo(wall, null))
        assertTrue(c.toString().contains("range=1.0"))

        val model = UncertaintyModel(DeviceTier.NO_DEPTH).withCalibration(c)
        assertSame(model, model.forScope(wall, 1.0))
        assertNull(model.forScope(wall, 3.0).calibration)
        val uncal = model.withCalibration(null)
        assertSame(uncal, uncal.forScope(wall, 3.0))
        // distance() scopes by the endpoint ranges when localisation is given.
        val a = UncertainPoint(Vec3.ZERO)
        val b = UncertainPoint(Vec3(1.0, 0.0, 0.0))
        assertEquals(0.97, model.distance(a, b, LocalisationInput(1.0, 500.0), LocalisationInput(1.1, 500.0)).value, 1e-12)
        assertEquals(1.0, model.distance(a, b, LocalisationInput(3.0, 500.0), LocalisationInput(3.0, 500.0)).value, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calibrationRangeMustBePositive() { ScaleCalibration(1.0, 0.0, rangeM = 0.0) }

    // ---- MAJOR-2 / MINOR-1: point estimator ------------------------------------------------------

    @Test fun studentT() {
        assertEquals(12.706, Stats.studentT975(1.0), 1e-9)
        assertEquals(12.706, Stats.studentT975(0.3), 1e-9)
        assertEquals(4.303, Stats.studentT975(2.0), 1e-9)
        assertEquals(2.228, Stats.studentT975(10.0), 1e-9)
        assertTrue(Stats.studentT975(2.5) in 3.182..4.303)
        assertEquals(1.980, Stats.studentT975(120.0), 1e-9)
        assertEquals(1.96 + 0.02 * 120 / 1e6, Stats.studentT975(1e6), 1e-9)
        var prev = Double.MAX_VALUE
        var nu = 1.0
        while (nu < 500) { val t = Stats.studentT975(nu); assertTrue(t <= prev); prev = t; nu += 0.37 }
    }

    @Test fun zeroInliersFallsBackToMedians() {
        // Each axis has a different exact-equal majority; no sample is an inlier on all three at once.
        val est = PointEstimator()
        for (i in 0 until 15) {
            val x = if (i <= 7) 0.0 else 1.0
            val y = if (i >= 7) 0.0 else 1.0
            val z = if (i <= 6 || i == 8) 0.0 else 1.0
            est.add(Vec3(x, y, z))
        }
        val e = est.estimate()!!
        assertEquals(0, e.inlierCount)
        assertFalse(e.isStable)
        assertTrue(e.position.isFinite)
        assertEquals(Vec3.ZERO, e.position)
        assertTrue(e.standardError > 0 && e.standardError.isFinite())
        // The next engine call must not crash.
        val d = UncertaintyModel().distance(e.point, UncertainPoint(Vec3(1.0, 0.0, 0.0)))
        assertEquals(1.0, d.value, 1e-12)
        assertTrue(est.estimate(LocalisationInput(1.0, 500.0))!!.point.includesLocalisation)
    }

    // ---- MAJOR-3: rectangle geometry -------------------------------------------------------------

    private val frame = listOf(Vec3(-0.3, 1.6, -1.5), Vec3(0.3, 1.6, -1.5), Vec3(0.3, 1.2, -1.5), Vec3(-0.3, 1.2, -1.5))
    private val wall = Plane.fromPointNormal(Vec3(0.0, 0.0, -1.5), Vec3.UNIT_Z)
    private val exact = UncertaintyModel(scaleSigmaRel = 0.0)

    @Test fun bowTieOrderIsReordered() {
        // TL, TR, BL, BR (Z order): before the fix H came out as the 72 cm diagonal, badge FAIR.
        val z = listOf(frame[0], frame[1], frame[3], frame[2]).map { loc(it, 0.001) }
        val r = Shapes.rectangle(z, wall, UncertaintyModel(DeviceTier.NO_DEPTH))
        assertTrue(r.cornersReordered)
        assertTrue(r.isConvex)
        assertEquals(0.60, r.width.value, 1e-9)
        assertEquals(0.40, r.height.value, 1e-9)
        assertTrue(r.isSquare)
        assertTrue(r.isGeometricallyConsistent)
        assertTrue(r.corners[2].approxEquals(frame[2], 1e-12))
        // Another crossing order (TL, BR, TR, BL) also recovers the outline.
        val x = listOf(frame[0], frame[2], frame[1], frame[3]).map { loc(it, 0.001) }
        val rx = Shapes.rectangle(x, wall, exact)
        assertTrue(rx.cornersReordered)
        assertEquals(setOf(0.6, 0.4), setOf(round4(rx.width.value), round4(rx.height.value)))
    }

    private fun round4(v: Double) = Math.round(v * 1e4) / 1e4

    @Test fun reversedWindingKeepsTapOrder() {
        // TL, BL, BR, TR is a valid (counter-clockwise) outline: kept, first edge is the 40 cm one.
        val r = Shapes.rectangle(listOf(frame[0], frame[3], frame[2], frame[1]).map { loc(it, 0.0) }, wall, exact)
        assertFalse(r.cornersReordered)
        assertEquals(0.40, r.width.value, 1e-12)
        assertEquals(0.60, r.height.value, 1e-12)
        assertEquals(Quality.GOOD, r.quality)
    }

    @Test fun threeCornersWithTheRightAngleFirst() {
        // TL, TR, BL: the right angle is at TL, not in the middle.
        val r = Shapes.rectangle(listOf(frame[0], frame[1], frame[3]).map { loc(it, 0.0) }, wall, exact)
        assertTrue(r.cornersReordered)
        assertEquals(0.60, r.width.value, 1e-12)
        assertEquals(0.40, r.height.value, 1e-12)
        assertTrue(r.isSquare)
        // Right angle last: TR, BL, TL → middle becomes TL.
        val r2 = Shapes.rectangle(listOf(frame[1], frame[3], frame[0]).map { loc(it, 0.0) }, wall, exact)
        assertTrue(r2.cornersReordered)
        assertEquals(0.24, r2.area.value, 1e-12)
    }

    @Test fun cornersFarOffThePlaneAreRough() {
        // DEPTH_SENSOR tier, corners 4 cm in front of the supplied plane: was badged GOOD.
        val off = frame.map { loc(it + Vec3(0.0, 0.0, 0.04), 0.0005) }
        val r = Shapes.rectangle(off, wall, UncertaintyModel(DeviceTier.DEPTH_SENSOR))
        assertEquals(0.04, r.planarityRms, 1e-12)
        assertFalse(r.isGeometricallyConsistent)
        assertEquals(Quality.ROUGH, r.quality)
        assertTrue("σ must include the lateral leak", r.width.sigma > 0.02)
    }

    @Test fun skewAndConcavityFeedTheBadge() {
        // One corner 3 cm off: angle deviation ≈ 4° with tiny corner noise → inconsistent, wider σ.
        val skew = listOf(frame[0] + Vec3(0.03, 0.0, 0.0), frame[1], frame[2], frame[3]).map { loc(it, 0.0005) }
        val r = Shapes.rectangle(skew, wall, exact)
        assertFalse(r.isGeometricallyConsistent)
        assertEquals(Quality.ROUGH, r.quality)
        assertTrue(r.height.sigma > 0.005)
        // Concave quad (a corner pushed inside): cannot be repaired by reordering.
        val concave = listOf(frame[0], frame[1], Vec3(0.0, 1.5, -1.5), frame[3]).map { loc(it, 0.0) }
        val c = Shapes.rectangle(concave, wall, exact)
        assertFalse(c.isConvex)
        assertEquals(Quality.ROUGH, c.quality)
        // Small deviations explained by large corner noise stay consistent.
        val noisy = listOf(frame[0] + Vec3(0.006, 0.0, 0.0), frame[1], frame[2], frame[3]).map { loc(it, 0.006) }
        assertTrue(Shapes.rectangle(noisy, wall, exact).isGeometricallyConsistent)
    }

    @Test fun localisationListForRectangle() {
        val l = LocalisationInput(1.0, 1000.0, 1.0) // 1 mm
        val r = Shapes.rectangle(frame.map { UncertainPoint(it) }, wall, exact, localisation = List(4) { l })
        // W averages two edges of two 1 mm corners each; projection onto the wall keeps the in-plane part.
        assertEquals(0.001, r.width.sigma, 1e-12)
        val floor = Shapes.rectangle(frame.map { UncertainPoint(it) }, wall, exact)
        assertEquals(DeviceTier.NO_DEPTH.unlocalisedEndpointSigma, floor.width.sigma, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rectangleLocalisationSizeMustMatch() {
        Shapes.rectangle(frame.map { UncertainPoint(it) }, wall, localisation = listOf(LocalisationInput(1.0, 500.0)))
    }

    // ---- MINOR-3 / MINOR-2: ray–plane rectangles and the plane term ------------------------------

    private val cam = Vec3(0.2, 1.4, 0.0)
    private fun raysTo(points: List<Vec3>) = points.map { Ray(cam, it - cam) }

    @Test fun rectangleFromRaysIsExactOnTheTruePlane() {
        val r = Shapes.rectangleFromRays(raysTo(frame), wall, exact, List(4) { LocalisationInput(1.0, 1500.0, 0.0) })!!
        assertEquals(0.60, r.width.value, 1e-9)
        assertEquals(0.40, r.height.value, 1e-9)
        assertEquals(0.0, r.width.sigma, 1e-12)
        assertEquals(0.0, r.planarityRms, 0.0)
        for (i in 0 until 4) assertTrue(r.corners[i].approxEquals(frame[i], 1e-9))
        // Localisation is mapped through the ray: σ grows with range and obliquity, never below the head-on value.
        val l = LocalisationInput(1.0, 1000.0, 1.0)
        val withLoc = Shapes.rectangleFromRays(raysTo(frame), wall, exact, List(4) { l })!!
        val ranges = frame.map { it.distanceTo(cam) }
        assertTrue(withLoc.width.sigma >= ranges.min() / 1000.0 * 0.999)
        // Without localisation the tier floor is used.
        val floor = Shapes.rectangleFromRays(raysTo(frame), wall, exact)!!
        assertTrue(floor.width.sigma >= DeviceTier.NO_DEPTH.unlocalisedEndpointSigma * 0.999)
        // A ray pointing away from the wall misses.
        assertNull(Shapes.rectangleFromRays(listOf(Ray(cam, Vec3.UNIT_Z)) + raysTo(frame.take(3)), wall, exact))
    }

    @Test fun rectangleFromRaysAddsPlaneFitUncertainty() {
        val rng = Random(5)
        // A well-spread wall patch and a narrow 8 cm band: the band's tilt term must widen the ±.
        fun patch(halfX: Double, halfY: Double) = List(80) {
            Vec3((rng.nextDouble() * 2 - 1) * halfX, 1.4 + (rng.nextDouble() * 2 - 1) * halfY, -1.5 + rng.nextGaussian() * 0.003)
        }
        val wide = PlaneFitter.ransac(patch(0.5, 0.5))!!
        val band = PlaneFitter.ransac(patch(0.5, 0.04))!!
        assertTrue(wide.isWellConditioned && band.isWellConditioned)
        assertTrue(band.normalAngleSigma > 5 * wide.normalAngleSigma)
        val l = List(4) { LocalisationInput(1.5, 1500.0, 0.5) }
        // Camera low and to the side, so the weakly constrained tilt really moves the corners.
        val low = Vec3(0.2, 0.5, 0.0)
        val rw = Shapes.rectangleFromRays(frame.map { Ray(low, it - low) }, wide, exact, l)!!
        val rb = Shapes.rectangleFromRays(frame.map { Ray(low, it - low) }, band, exact, l)!!
        assertTrue("band ${rb.height.sigma} vs wide ${rw.height.sigma}", rb.height.sigma > 1.5 * rw.height.sigma)
        // The offset term uses the perpendicular camera distance (1.5 m), not the slant range.
        assertEquals(wide.offsetStandardError / 1.5, Uncertainty.planeBiasRel(wide, cam), 1e-6)
        val oblique = Vec3(1.5, 1.4, 0.0) // 1.5 m to the side: range ≈ 2.1 m, h is still 1.5 m
        assertEquals(Uncertainty.planeBiasRel(wide, cam), Uncertainty.planeBiasRel(wide, oblique), 1e-6)
        assertTrue(Uncertainty.planeBiasRel(wide, cam, Vec3(0.5, 1.4, -1.5)) >= Uncertainty.planeBiasRel(wide, cam))
    }

    @Test(expected = IllegalArgumentException::class)
    fun planeBiasNeedsCameraOffThePlane() {
        val fit = PlaneFitter.leastSquares(frame)!!
        Uncertainty.planeBiasRel(fit, frame[0])
    }

    // ---- MAJOR-4: strip-shaped plane fits --------------------------------------------------------

    private fun strip(rng: Random, widthM: Double, n: Int = 200) = List(n) {
        Vec3((rng.nextDouble() * 2 - 1) * 0.5, 1.0 + (rng.nextDouble() * 2 - 1) * widthM / 2, -1.2 + rng.nextGaussian() * 0.003)
    }

    @Test fun thinStripIsRejectedOrFlagged() {
        // 1 m × 2 cm band with 3 mm noise: used to return a confident fit tilted up to 8°, 200/200 times.
        var returned = 0
        repeat(200) { s ->
            val pts = strip(Random(1000L + s), 0.02)
            if (PlaneFitter.ransac(pts) != null) returned++
            val ls = PlaneFitter.leastSquares(pts)!!
            assertFalse(ls.isWellConditioned)
            assertTrue(ls.minorExtent < PlaneFitter.MIN_EXTENT_TO_RMS * ls.rmsResidual)
        }
        assertEquals(0, returned)
        // Opting out of the rejection returns the flagged fit.
        val f = PlaneFitter.ransac(strip(Random(1), 0.02), RansacConfig(rejectIllConditioned = false))!!
        assertFalse(f.isWellConditioned)
        assertTrue(f.normalAngleSigma > 0)
        // The offset σ grows with the distance from the centroid along the weak direction.
        assertTrue(f.offsetSigmaAt(f.centroid + Vec3(0.0, 0.3, 0.0)) > 3 * f.offsetSigmaAt(f.centroid))
        // A normal wall patch is well conditioned.
        val wallFit = PlaneFitter.ransac(strip(Random(2), 0.6))!!
        assertTrue(wallFit.isWellConditioned)
        assertEquals(3, wallFit.eigenvalues!!.size)
    }

    @Test fun planeFitWithoutEigenvaluesIsTreatedAsUnknown() {
        val p = PlaneFit(Plane(Vec3.UNIT_Z, 0.0), 0.003, 100, BooleanArray(100) { true }, Vec3.ZERO)
        assertTrue(p.isWellConditioned)
        assertEquals(0.0, p.normalAngleSigma, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, p.minorExtent, 0.0)
        assertEquals(p.offsetStandardError, p.offsetSigmaAt(Vec3(5.0, 5.0, 0.0)), 0.0)
    }

    @Test fun twoParallelPlanesPickOne() {
        // Wall (200 points) and frame glass 2 cm in front (120 points): RANSAC must fit one, not average.
        val rng = Random(42)
        val wallPts = List(200) { Vec3((rng.nextDouble() * 2 - 1) * 0.6, 1.4 + (rng.nextDouble() * 2 - 1) * 0.6, -1.5 + rng.nextGaussian() * 0.002) }
        val glass = List(120) { Vec3((rng.nextDouble() * 2 - 1) * 0.3, 1.4 + (rng.nextDouble() * 2 - 1) * 0.2, -1.48 + rng.nextGaussian() * 0.002) }
        val fit = PlaneFitter.ransac((wallPts + glass).shuffled(Random(1)), RansacConfig(inlierThreshold = 0.006))!!
        val offset = -fit.plane.facing(Vec3.ZERO).d
        assertTrue("offset=$offset", abs(abs(offset) - 1.5) < 0.003)
        assertTrue(fit.inlierCount in 190..215)
        assertTrue(abs(fit.plane.normal dot Vec3.UNIT_Z) > cos(Math.toRadians(1.0)))
    }

    @Test fun repeatedEigenvalues() {
        // Planar disc: λ₁ = λ₂ (in-plane), λ₀ = 0 (normal). Any orthonormal in-plane pair is valid.
        val q = Quaternion.fromAxisAngle(Vec3(0.3, -1.0, 0.4), 0.7)
        val n = q.rotate(Vec3.UNIT_Z)
        val a = q.rotate(Vec3.UNIT_X)
        val b = q.rotate(Vec3.UNIT_Y)
        fun m(i: Int, j: Int): Double {
            val av = doubleArrayOf(a.x, a.y, a.z); val bv = doubleArrayOf(b.x, b.y, b.z)
            return 0.25 * (av[i] * av[j] + bv[i] * bv[j])
        }
        val e = SymmetricEigen3.decompose(m(0, 0), m(0, 1), m(0, 2), m(1, 1), m(1, 2), m(2, 2))
        assertEquals(0.0, e.values[0], 1e-12)
        assertEquals(0.25, e.values[1], 1e-12)
        assertEquals(0.25, e.values[2], 1e-12)
        assertEquals(1.0, abs(e.vectors[0] dot n), 1e-9)
        assertEquals(0.0, e.vectors[1] dot e.vectors[2], 1e-9)
        assertEquals(0.0, e.vectors[1] dot n, 1e-9)
        // Fully isotropic: all equal.
        val iso = SymmetricEigen3.decompose(2.0, 0.0, 0.0, 2.0, 0.0, 2.0)
        for (v in iso.values) assertEquals(2.0, v, 0.0)
        // A disc of points fits with the right normal.
        val disc = List(64) { k -> val t = k * 2 * Math.PI / 64; a * (0.3 * cos(t)) + b * (0.3 * sin(t)) }
        val fit = PlaneFitter.leastSquares(disc)!!
        assertEquals(1.0, abs(fit.plane.normal dot n), 1e-9)
        val ev = fit.eigenvalues!!
        assertEquals(ev[1], ev[2], 1e-12)
    }

    // ---- MINOR-4 / MINOR-5: calibration acceptance -----------------------------------------------

    @Test fun calibrationThatWidensThePlusMinusIsRejected() {
        // relSigma ≈ 0.99% (accepted before): with the 0.3% floor it would be 1.03% > the 1.02% prior.
        val r = Calibrator.calibrate(listOf(KnownLength(1.0, 0.0, RawLength(1.0, 0.0099))))
        assertEquals(CalibrationResult.Reason.TOO_NOISY, (r as CalibrationResult.Rejected).reason)
        // ~0.6% is not clearly better than the prior either (needs < 0.7 × 1.02% with the floor).
        assertTrue(Calibrator.calibrate(listOf(KnownLength(1.0, 0.0, RawLength(1.0, 0.0068)))) is CalibrationResult.Rejected)
        assertTrue(Calibrator.calibrate(listOf(KnownLength(1.0, 0.0, RawLength(1.0, 0.004)))) is CalibrationResult.Accepted)
        // On a hardware-depth tier the prior is already near the floor: calibration cannot help.
        val ds = UncertaintyModel(DeviceTier.DEPTH_SENSOR)
        assertTrue(Calibrator.calibrate(listOf(KnownLength(1.0, 0.0, RawLength(1.0, 0.0005))), model = ds) is CalibrationResult.Rejected)
    }

    @Test fun cardWithoutLocalisationCannotCalibrate() {
        // MAJOR-1 for calibration: a card measured from jitter-only endpoints used to look precise.
        val card = listOf(Vec3(0.0, 1.0, -0.3), Vec3(0.0856, 1.0, -0.3), Vec3(0.0856, 0.946, -0.3), Vec3(0.0, 0.946, -0.3))
        val plane = Plane.fromPointNormal(card[0], Vec3.UNIT_Z)
        val jitterOnly = card.map { UncertainPoint.of(it, 0.0002) }
        val rect = Shapes.rectangle(jitterOnly, plane)
        assertEquals(CalibrationResult.Reason.TOO_NOISY,
            (Calibrator.fromReference(ReferenceObject.ID1_CARD, rect) as CalibrationResult.Rejected).reason)
        // Raw sides with jitter-only endpoints (unlocalised) are floored inside the calibrator too.
        val raw = RawLength(0.0856, 0.0002, unlocalisedEndpoints = 2)
        val raw2 = RawLength(0.05398, 0.0002, unlocalisedEndpoints = 2)
        assertTrue(Calibrator.fromReference(ReferenceObject.ID1_CARD, raw, raw2) is CalibrationResult.Rejected)
        // An A4 sheet at 50 cm on a 1080p stream (0.5 mm corners) calibrates, scoped to its plane and range.
        val a4 = listOf(Vec3(0.0, 1.0, -0.5), Vec3(0.297, 1.0, -0.5), Vec3(0.297, 0.79, -0.5), Vec3(0.0, 0.79, -0.5))
        val r = Shapes.rectangle(a4.map { UncertainPoint(it) }, Plane.fromPointNormal(a4[0], Vec3.UNIT_Z),
            localisation = List(4) { LocalisationInput(0.5, 1500.0, 1.5) })
        val cal = (Calibrator.fromReference(ReferenceObject.A4, r, rangeM = 0.5) as CalibrationResult.Accepted).calibration
        assertNotNull(cal.plane)
        assertEquals(0.5, cal.rangeM!!, 0.0)
        assertTrue(cal.anchor!!.approxEquals(Vec3(0.1485, 0.895, -0.5), 1e-9))
        assertFalse(cal.appliesTo(Plane.fromPointNormal(Vec3(0.0, 0.0, -2.0), Vec3.UNIT_Z), 0.5))
    }

    // ---- MINOR-7: rotation about the vertical only ------------------------------------------------

    @Test fun fridgeIsNotTippedIntoALowNiche() {
        fun m(v: Double) = Measurement(v, 0.005)
        val fridge = listOf(m(0.70), m(1.80), m(0.65)) // W, H, D
        val lowNiche = listOf(m(1.90), m(0.75), m(0.80))
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(fridge, lowNiche, FitPolicy(allowRotation = true)).fit)
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(fridge, lowNiche, FitPolicy(rotation = FitRotation.ABOUT_VERTICAL)).fit)
        assertEquals(Fit.FITS, FitVerdict.evaluate(fridge, lowNiche, FitPolicy(rotation = FitRotation.ANY)).fit)
        // Turning about the vertical (W ↔ D) is allowed: 0.9 × 0.6 deep into a 0.65 wide × 0.95 deep space.
        val cabinet = listOf(m(0.90), m(1.00), m(0.60))
        val space = listOf(m(0.65), m(1.10), m(0.95))
        assertEquals(Fit.TOO_BIG, FitVerdict.evaluate(cabinet, space).fit)
        val r = FitVerdict.evaluate(cabinet, space, FitPolicy(allowRotation = true))
        assertEquals(Fit.FITS, r.fit)
        assertTrue(r.rotated)
        assertEquals(listOf(2, 1, 0), r.axes.map { it.itemAxis })
        assertEquals(FitRotation.NONE, FitPolicy().rotation)
        assertEquals(FitRotation.ABOUT_VERTICAL, FitPolicy(allowRotation = true).rotation)
        // 1-D with ABOUT_VERTICAL: nothing to permute.
        assertFalse(FitVerdict.evaluate(m(1.0), m(1.1), FitPolicy(allowRotation = true)).rotated)
    }

    // ---- MINOR-8: homography -----------------------------------------------------------------------

    @Test fun counterClockwiseCornersAreNotMirrored() {
        val tl = Vec2(100.0, 100.0); val tr = Vec2(900.0, 120.0); val br = Vec2(880.0, 700.0); val bl = Vec2(120.0, 680.0)
        assertTrue(Homography.isClockwiseOnScreen(listOf(tl, tr, br, bl)))
        assertFalse(Homography.isClockwiseOnScreen(listOf(tl, bl, br, tr)))
        val cw = Homography.rectify(listOf(tl, tr, br, bl), 1024.0, 683.0)!!
        val ccw = Homography.rectify(listOf(tl, bl, br, tr), 1024.0, 683.0)!!
        for (p in listOf(tl, tr, br, bl, Vec2(400.0, 300.0))) {
            assertTrue(cw.map(p).distanceTo(ccw.map(p)) < 1e-6)
        }
        // The top-right image corner lands top-right in the texture (not mirrored to the bottom-left).
        assertTrue(ccw.map(tr).distanceTo(Vec2(1024.0, 0.0)) < 1e-6)
    }

    @Test fun inverseSingularityIsScaleAware() {
        // A tiny-but-valid matrix (det ≈ 1e-315) inverts; a badly conditioned one is refused.
        val tiny = Homography(doubleArrayOf(1e-105, 0.0, 0.0, 0.0, 1e-105, 0.0, 0.0, 0.0, 1e-105)).inverse()
        assertTrue(tiny.map(3.0, 4.0).distanceTo(Vec2(3.0, 4.0)) < 1e-9)
        try {
            Homography(doubleArrayOf(1e6, 0.0, 0.0, 0.0, 1e6, 0.0, 0.0, 0.0, 1e-9)).inverse()
            throw AssertionError("near-singular homography accepted")
        } catch (_: IllegalArgumentException) {}
    }

    // ---- MINOR-9: display rounding -----------------------------------------------------------------

    @Test fun roundingBoundaries() {
        fun m(v: Double, pm: Double) = Measurement(v, pm / Uncertainty.K95)
        // ±0.999 cm displays as 1.0, so the value is whole centimetres (was "45.5 cm ± 1.0").
        assertEquals("46 cm ± 1.0", Units.format(m(0.4552, 0.00999), LengthUnit.CM).text)
        assertEquals(1.0, Units.stepFor(0.999, LengthUnit.CM), 0.0)
        // ±0.0999 m displays as 0.10 → value to 0.1 m.
        assertEquals("1.2 m ± 0.10", Units.format(m(1.2345, 0.0999), LengthUnit.M).text)
        // ±0.0199 m displays as 0.02 (was "0.02 m ± 0.020").
        assertEquals("0.02 m ± 0.02", Units.format(m(0.02, 0.0199), LengthUnit.M).text)
        // Still two figures when the rounded ± starts with 1.
        assertEquals("1.23 m ± 0.015", Units.format(m(1.2345, 0.0149), LengthUnit.M).text)
        // Fractional inches: ±0.97 in rounds to 1, so whole inches.
        assertEquals("24 in ± 1", Units.format(m(0.6, 0.97 * Units.METRES_PER_INCH), LengthUnit.INCH_FRACTION).text)
        // Negative feet-inches carry: −11.97 in is −1′ 0″, not −0′ 12″.
        assertEquals("-1′ 0″ ± 1″", Units.format(m(-11.97 * Units.METRES_PER_INCH, Units.METRES_PER_INCH), LengthUnit.FEET_INCHES).text)
        assertEquals("-1′ 0″", Units.feetInchesText(-12.0))
        // Areas use the same rule: ±0.0999 m² → 0.10.
        assertEquals("1.2 m² ± 0.10", Units.formatArea(m(1.2345, 0.0999), AreaUnit.M2))
    }
}
