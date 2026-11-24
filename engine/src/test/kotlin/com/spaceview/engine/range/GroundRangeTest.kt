package com.spaceview.engine.range

import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class GroundRangeTest {
    private val model = RangeErrorModel()
    private val aim = 2.0 / 500.0
    private val h = 1.45

    private fun theta(lineOfSight: Double): Double = atan2(h, sqrt(lineOfSight * lineOfSight - h * h))

    @Test
    fun geometryIsHeightOverSinDepression() {
        val d = 10.0
        assertEquals(d, GroundRange.lineOfSight(h, theta(d)), 1e-9)
        val f = GroundRange.fix(h, theta(d), aim, model)!!
        assertEquals(d, f.distance.value, 1e-9)
        assertEquals(sqrt(d * d - h * h), f.horizontalM, 1e-9)
        assertEquals(h, f.cameraHeightM, 0.0)
    }

    @Test
    fun depressionOfADirection() {
        assertEquals(Math.toRadians(30.0), GroundRange.depression(Vec3(0.0, -0.5, -sqrt(0.75))), 1e-12)
        assertTrue(GroundRange.depression(Vec3(0.0, 0.2, -1.0)) < 0)
        assertEquals(0.0, GroundRange.depression(Vec3.ZERO), 0.0)
    }

    @Test
    fun monteCarloCoverageIsAtLeast95PercentWhereGroundIsUsable() {
        val rnd = Random(424242)
        for (d in listOf(2.0, 5.0, 8.0, 12.0)) {
            val th = theta(d)
            var inside = 0
            var ok = 0
            repeat(TRIALS) {
                val hMeas = h * (1 + model.scaleSigmaRel * rnd.nextGaussian()) + model.floorHeightSigmaM * rnd.nextGaussian()
                val tMeas = th + aim * rnd.nextGaussian() + model.attitudeSigmaRad * rnd.nextGaussian() +
                    model.levelGroundSigmaRad * rnd.nextGaussian()
                val f = GroundRange.fix(hMeas, tMeas, aim, model) ?: return@repeat
                ok++
                if (abs(f.distance.value - d) <= f.distance.bound95) inside++
            }
            val coverage = inside.toDouble() / ok
            assertTrue("coverage at $d m = $coverage", coverage >= 0.95)
            assertTrue(ok > TRIALS * 0.98)
        }
    }

    @Test
    fun groundIsUsableNearbyAndRefusedFarAway() {
        assertTrue(GroundRange.solve(h, theta(8.0), aim, model) is RangeOutcome.Ok)
        val ok = GroundRange.solve(h, theta(8.0), aim, model) as RangeOutcome.Ok
        assertTrue(ok.reading.assumesLevelGround)
        assertEquals(RangeMethod.GROUND, ok.reading.method)
        assertTrue(ok.reading.distance.relative95 <= RangeLimits.GROUND_MAX_REL95)
        // At 30 m the cot θ factor puts ±95 far above 10%: offer 2-sight instead.
        assertEquals(RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN), GroundRange.solve(h, theta(30.0), aim, model))
        val far = GroundRange.fix(h, theta(30.0), aim, model)!!
        assertTrue(!far.usable)
    }

    @Test
    fun refusals() {
        assertEquals(RangeOutcome.Rejected(RangeRejection.NO_FLOOR), GroundRange.solve(0.1, 0.5, aim, model))
        assertEquals(RangeOutcome.Rejected(RangeRejection.ABOVE_HORIZON), GroundRange.solve(h, -0.1, aim, model))
        assertEquals(RangeOutcome.Rejected(RangeRejection.INVALID_INPUT), GroundRange.solve(Double.NaN, 0.5, aim, model))
        assertEquals(RangeOutcome.Rejected(RangeRejection.INVALID_INPUT), GroundRange.solve(h, 0.5, -1.0, model))
        // 3 m / sin(0.3°) ≈ 573 m: beyond the product limit, never a number.
        assertEquals(RangeOutcome.OutOfRange, GroundRange.solve(3.0, Math.toRadians(0.3), aim, model))
        assertNull(GroundRange.fix(0.1, 0.5, aim, model))
        assertNull(GroundRange.fix(h, 0.001, aim, model))
        assertNull(GroundRange.fix(h, 0.5, Double.NaN, model))
        // Too uncertain to bound at all.
        assertEquals(RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN), GroundRange.solve(h, theta(100.0), 0.05, model))
    }

    @Test
    fun heightSigmaIncludesTheFloorAndTheScale() {
        val s = GroundRange.heightSigma(2.0, model)
        assertEquals(sqrt(0.02 * 0.02 + 0.02 * 0.02), s, 1e-12)
        assertNotNull(GroundRange.fix(2.0, 0.5, aim, model)?.heightSigmaM)
    }

    @Test
    fun inverseLawIsNeverNarrowerThanLinearAndRefusesUnboundedIntervals() {
        val m = RangeUncertainty.inverseLaw(100.0, 0.05)!!
        assertTrue(m.bound95 >= 1.96 * 0.05 * 100.0)
        assertNull(RangeUncertainty.inverseLaw(100.0, 0.3))
        assertNull(RangeUncertainty.inverseLaw(-1.0, 0.01))
        assertNull(RangeUncertainty.inverseLaw(1.0, Double.NaN))
        assertEquals(0.0, RangeUncertainty.inverseLaw(10.0, 0.0)!!.sigma, 0.0)
    }

    @Test
    fun errorModelValidatesAndComposes() {
        assertTrue(runCatching { RangeErrorModel(attitudeSigmaRad = -1.0) }.isFailure)
        assertTrue(runCatching { RangeErrorModel(sigmaMargin = 0.5) }.isFailure)
        val m = RangeErrorModel(baselineRelSigma = 0.01, baselineDriftM = 0.0, baselineDriftPerS = 0.0)
        assertEquals(0.1, m.baselineSigma(10.0, 5.0), 1e-12)
        assertTrue(model.headingSigma(30.0) > model.headingSigma(0.0))
    }

    private companion object {
        const val TRIALS = 4000
    }
}
