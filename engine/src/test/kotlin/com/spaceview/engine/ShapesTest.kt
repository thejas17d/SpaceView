package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class ShapesTest {
    private val exactModel = UncertaintyModel(scaleSigmaRel = 0.0)

    /** Point whose covariance is complete (sigma includes localisation), so no tier floor is added. */
    private fun loc(p: Vec3, sigma: Double) = UncertainPoint(p).withLocalisationSigma(sigma)

    /** 60 × 40 cm frame on the wall z = −1.5, top-left at (−0.3, 1.6). */
    private val frame = listOf(
        Vec3(-0.3, 1.6, -1.5), Vec3(0.3, 1.6, -1.5), Vec3(0.3, 1.2, -1.5), Vec3(-0.3, 1.2, -1.5),
    )
    private val wall = Plane.fromPointNormal(Vec3(0.0, 0.0, -1.5), Vec3.UNIT_Z)

    @Test fun exactRectangle() {
        val r = Shapes.rectangle(frame.map { loc(it, 0.0) }, wall, exactModel)
        assertEquals(0.60, r.width.value, 1e-12)
        assertEquals(0.40, r.height.value, 1e-12)
        assertEquals(0.24, r.area.value, 1e-12)
        assertEquals(sqrt(0.36 + 0.16), r.diagonal.value, 1e-12)
        assertEquals(0.0, r.width.sigma, 1e-15)
        for (a in r.cornerAnglesDeg) assertEquals(90.0, a, 1e-9)
        assertEquals(0.0, r.maxRightAngleDeviationDeg, 1e-9)
        assertTrue(r.isSquare)
        assertEquals(0.0, r.diagonalMismatch, 1e-12)
        assertEquals(0.0, r.planarityRms, 1e-12)
        assertEquals(Quality.GOOD, r.quality)
        assertEquals(4, r.corners.size)
        assertFalse(r.cornersReordered)
        assertTrue(r.isConvex && r.isGeometricallyConsistent)
    }

    @Test fun cornersAreSnappedToThePlane() {
        // Corners hovering 1–3 cm off the wall (bad depth) are pulled onto the fitted plane.
        val off = listOf(0.01, -0.02, 0.03, 0.0)
        val corners = frame.mapIndexed { i, p -> UncertainPoint.of(p + Vec3(0.0, 0.0, off[i]), 0.002) }
        val r = Shapes.rectangle(corners, wall, exactModel)
        for (c in r.corners) assertEquals(0.0, wall.signedDistance(c), 1e-12)
        assertEquals(0.60, r.width.value, 1e-12)
        assertEquals(0.40, r.height.value, 1e-12)
        assertTrue(r.planarityRms > 0.015)
    }

    @Test fun fitsPlaneWhenNoneGiven() {
        val r = Shapes.rectangle(frame.map { UncertainPoint.of(it, 0.001) }, null, exactModel)
        assertEquals(0.60, r.width.value, 1e-9)
        assertEquals(1.0, abs(r.plane.normal dot Vec3.UNIT_Z), 1e-9)
    }

    @Test fun threeCornerRectangle() {
        val r = Shapes.rectangle(frame.take(3).map { loc(it, 0.0) }, null, exactModel)
        assertEquals(0.60, r.width.value, 1e-12)
        assertEquals(0.40, r.height.value, 1e-12)
        assertTrue(frame[3].approxEquals(r.corners[3], 1e-12))
        assertTrue(r.isSquare)
    }

    @Test fun skewedQuadFailsSquareness() {
        val skew = listOf(frame[0] + Vec3(0.03, 0.0, 0.0), frame[1], frame[2], frame[3])
        val r = Shapes.rectangle(skew.map { UncertainPoint.of(it, 0.0) }, wall, exactModel)
        assertFalse(r.isSquare)
        assertTrue(r.maxRightAngleDeviationDeg > 2.0)
        assertTrue(r.diagonalMismatch > 0.01)
        // Mismatched top/bottom edges widen the width σ to at least half their difference.
        assertTrue(r.width.sigma >= 0.015 - 1e-12)
        // 3-corner input: only the angle at the middle corner is known; tilt the top edge by ~5°.
        val skew3 = listOf(frame[0] + Vec3(0.0, 0.05, 0.0), frame[1], frame[2])
        assertFalse(Shapes.rectangle(skew3.map { UncertainPoint.of(it, 0.0) }, wall, exactModel).isSquare)
    }

    @Test fun noisyRectangleIsAccurateAndHonest() {
        val rng = Random(60)
        val model = UncertaintyModel(DeviceTier.NO_DEPTH)
        var inside = 0
        var sumErr = 0.0
        val trials = 500
        repeat(trials) {
            val scale = 1 + rng.nextGaussian() * model.scaleSigmaRel
            val corners = frame.map {
                UncertainPoint.of(it * scale + Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * 0.003, 0.003)
            }
            val r = Shapes.rectangle(corners, Plane.fromPointNormal(Vec3(0.0, 0.0, -1.5 * scale), Vec3.UNIT_Z), model)
            sumErr += abs(r.width.value - 0.6)
            if (abs(r.width.value - 0.6) <= r.width.bound95) inside++
        }
        assertTrue("mean err ${sumErr / trials}", sumErr / trials < 0.008)
        val coverage = inside.toDouble() / trials
        assertTrue("coverage=$coverage", coverage in 0.90..0.995)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rectangleNeedsThreeOrFourCorners() {
        Shapes.rectangle(frame.take(2).map { UncertainPoint.of(it, 0.0) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun collinearCornersWithoutPlane() {
        Shapes.rectangle(listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)).map { UncertainPoint.of(it, 0.0) })
    }

    @Test fun boxFromRawDimensions() {
        val model = UncertaintyModel(scaleSigmaRel = 0.01)
        val b = Shapes.box(RawLength(0.45, 0.0), RawLength(0.26, 0.0), RawLength(0.35, 0.0), model)
        assertEquals(0.45, b.width.value, 1e-12)
        assertEquals(0.45 * 0.26 * 0.35, b.volume.value, 1e-12)
        assertEquals(3 * 0.01 * b.volume.value, b.volume.sigma, 1e-12)
        // Wall-hung frame with a preset 2 cm depth (not measured): no scale error on depth.
        val f = Shapes.box(RawLength(0.6, 0.0), RawLength(0.4, 0.0), RawLength(0.02, 0.005, isMeasured = false), model)
        assertEquals(0.005, f.depth.sigma, 1e-15)
        assertEquals(Quality.ROUGH, f.depth.quality) // ±1 cm on 2 cm
        // The preset depth is an estimate: labelled, and excluded from the box badge (MINOR-6).
        assertTrue(f.depthIsEstimate && f.hasEstimates)
        assertFalse(f.widthIsEstimate || f.heightIsEstimate)
        assertEquals(Quality.FAIR, f.quality) // W, H at the 1.96% scale prior; the 2 cm preset does not count
        // A sigma = 0 preset must not make a rough box look good either.
        val rough = Shapes.box(RawLength(0.6, 0.02), RawLength(0.4, 0.0), RawLength(0.02, 0.0, isMeasured = false), model)
        assertEquals(Quality.ROUGH, rough.quality)
        assertFalse(Shapes.box(RawLength(0.6, 0.0), RawLength(0.4, 0.0), RawLength(0.3, 0.0), model).hasEstimates)
    }

    @Test fun boxFromPoints() {
        // Microwave 45 × 26 × 35 cm on a counter at y = 0.9.
        val b0 = loc(Vec3(0.0, 0.9, -1.0), 0.001)
        val b1 = loc(Vec3(0.45, 0.9, -1.0), 0.001)
        val b2 = loc(Vec3(0.45, 0.9, -1.35), 0.001)
        val top = loc(Vec3(0.2, 1.16, -1.2), 0.001)
        val b = Shapes.boxFromPoints(b0, b1, b2, top, exactModel)
        assertEquals(0.45, b.width.value, 1e-12)
        assertEquals(0.35, b.depth.value, 1e-12)
        assertEquals(0.26, b.height.value, 1e-12)
        assertEquals(sqrt(0.001 * 0.001 + 3 * 0.001 * 0.001 / 9), b.height.sigma, 1e-12)
        assertEquals(Quality.GOOD, b.quality)
    }
}
