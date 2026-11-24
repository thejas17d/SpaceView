package com.spaceview.engine.range

import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class RayTriangulationTest {
    private val model = RangeErrorModel()

    /** 2 VGA pixels at a VGA focal length of ~500 px (AimModel defaults). */
    private val aim = 2.0 / 500.0

    private val camera1 = Vec3(0.0, 1.5, 0.0)

    /** A target [d] m straight ahead of sight 1, a little above eye level. */
    private fun target(d: Double) = Vec3(0.0, 3.0, -d)

    private fun sight(from: Vec3, to: Vec3, t: Double, sigma: Double = aim) = Sight(from, to - from, sigma, t)

    @Test
    fun closestApproachOfIntersectingRaysIsTheirIntersection() {
        val p = Vec3(1.0, 2.0, -7.0)
        val c = RayTriangulation.closestApproach(Vec3.ZERO, p, Vec3(3.0, 0.0, 0.0), p - Vec3(3.0, 0.0, 0.0))!!
        assertTrue(c.point.approxEquals(p, 1e-9))
        assertEquals(0.0, c.miss, 1e-9)
        assertTrue(c.s > 0 && c.t > 0)
    }

    @Test
    fun parallelRaysHaveNoClosestApproach() {
        assertNull(RayTriangulation.closestApproach(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), Vec3.UNIT_X, Vec3(0.0, 0.0, -1.0)))
    }

    @Test
    fun noiseFreeScenesGiveTheTrueDistanceWithAPositiveBound() {
        for (d in DISTANCES) {
            val b = RayTriangulation.requiredBaseline(d, aim, model)
            val camera2 = camera1 + Vec3(b, 0.0, 0.0)
            val t = target(d)
            val r = RayTriangulation.solve(sight(camera1, t, 0.0), sight(camera2, t, b), b, false, model, Double.MAX_VALUE)
            val ok = r.outcome as RangeOutcome.Ok
            assertEquals("distance at $d m", t.distanceTo(camera2), ok.reading.distance.value, 1e-6 * d)
            assertTrue(ok.reading.distance.bound95 > 0)
            assertEquals(RangeMethod.TWO_SIGHT, ok.reading.method)
            assertEquals(0.0, r.missM, 1e-6)
        }
    }

    /** One Monte-Carlo trial at the 1σ model inputs, with the product cap ON. */
    private fun trial(d: Double, rnd: Random): Pair<RangeOutcome, Double> {
        val b = RayTriangulation.requiredBaseline(d, aim, model)
        val walkS = b / WALK_SPEED
        val camera2 = camera1 + Vec3(b, 0.0, 0.0)
        val t = target(d)
        val heading = model.headingSigma(walkS)
        val sigmaB = model.baselineSigma(b, walkS)
        val d1 = perturb(t - camera1, aim, rnd)
        val d2 = perturb(perturb(t - camera2, aim, rnd), heading, rnd)
        // Sight 1's position relative to sight 2: VIO scale error on the baseline plus per-axis drift.
        val scale = 1 + model.scaleSigmaRel * rnd.nextGaussian()
        val o1 = camera2 + (camera1 - camera2) * scale + Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()) * sigmaB
        val r = RayTriangulation.solve(Sight(o1, d1, aim, 0.0), Sight(camera2, d2, aim, walkS), b, false, model)
        return r.outcome to t.distanceTo(camera2)
    }

    private class Tally(var ok: Int = 0, var inside: Int = 0, var outOfRange: Int = 0, var refused: Int = 0) {
        val wrongNumbers: Int get() = ok - inside
    }

    private fun tally(d: Double, rnd: Random): Tally {
        val t = Tally()
        repeat(TRIALS) {
            val (o, truth) = trial(d, rnd)
            when (o) {
                is RangeOutcome.Ok -> {
                    t.ok++
                    if (abs(o.reading.distance.value - truth) <= o.reading.distance.bound95) t.inside++
                }
                RangeOutcome.OutOfRange -> t.outOfRange++
                is RangeOutcome.Rejected -> t.refused++
            }
        }
        return t
    }

    @Test
    fun monteCarloCoverageIsAtLeast95PercentFrom2To300MetresWithTheCapOn() {
        val rnd = Random(20261124)
        for (d in listOf(2.0, 10.0, 50.0, 150.0, 300.0)) {
            val t = tally(d, rnd)
            val coverage = t.inside.toDouble() / t.ok
            assertTrue("coverage at $d m = $coverage", coverage >= 0.95)
            assertTrue("refused at $d m = ${t.refused} of $TRIALS", t.refused <= TRIALS * 0.02)
            assertTrue("out of range at $d m = ${t.outOfRange} of $TRIALS", t.outOfRange <= TRIALS * 0.02)
        }
    }

    @Test
    fun atAndBeyondTheLimitTheCapIsHonest() {
        val rnd = Random(500600)
        // A true 500 m: almost always "may be over 500 m"; a number that misses the truth stays rare.
        val at = tally(500.0, rnd)
        assertTrue("out of range at 500 m = ${at.outOfRange}", at.outOfRange >= TRIALS * 0.9)
        assertTrue("wrong numbers at 500 m = ${at.wrongNumbers}", at.wrongNumbers <= TRIALS * 0.05)
        // A true 600 m: essentially never a number.
        val beyond = tally(600.0, rnd)
        assertTrue("numbers at 600 m = ${beyond.ok}", beyond.ok <= TRIALS * 0.005)
    }

    @Test
    fun aNumberIsShownOnlyWhenItsWholeIntervalIsWithin500Metres() {
        for ((d, expectNumber) in listOf(300.0 to true, 450.0 to false)) {
            val b = RayTriangulation.requiredBaseline(d, aim, model)
            val c2 = camera1 + Vec3(b, 0.0, 0.0)
            val o = RayTriangulation.solve(sight(camera1, target(d), 0.0), sight(c2, target(d), b), b, false, model).outcome
            if (expectNumber) {
                val m = (o as RangeOutcome.Ok).reading.distance
                assertTrue(m.high95 <= RangeLimits.MAX_RANGE_M)
            } else {
                assertEquals(RangeOutcome.OutOfRange, o)
            }
        }
    }

    @Test
    fun underTheFullWalkAPointWithinTheLimitIsWalkFurtherNotOutOfRange() {
        // Gate G1: a 350 m target after a 12 m walk: its interval reaches past 500 m, but a longer walk would measure it.
        val t = target(350.0)
        val short = camera1 + Vec3(12.0, 0.0, 0.0)
        val r12 = RayTriangulation.solve(sight(camera1, t, 0.0), sight(short, t, 12.0), 12.0, false, model)
        assertEquals(RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN), r12.outcome)
        assertNotNull(r12.point)
        val full = camera1 + Vec3(RangeLimits.MAX_BASELINE_M, 0.0, 0.0)
        val r30 = RayTriangulation.solve(sight(camera1, t, 0.0), sight(full, t, 30.0), 30.0, false, model)
        assertTrue(r30.outcome is RangeOutcome.Ok)
        // With the full walk done, a point whose interval still reaches past 500 m is out of range.
        val far = target(450.0)
        val r450 = RayTriangulation.solve(sight(camera1, far, 0.0), sight(full, far, 30.0), 30.0, false, model)
        assertEquals(RangeOutcome.OutOfRange, r450.outcome)
    }

    @Test
    fun badInputBecomesNoSightInsteadOfACrash() {
        assertNull(Sight.of(Vec3(Double.NaN, 0.0, 0.0), Vec3(0.0, 0.0, -1.0), aim, 0.0))
        assertNull(Sight.of(Vec3.ZERO, Vec3.ZERO, aim, 0.0))
        assertNull(Sight.of(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), Double.NaN, 0.0))
        assertNull(Sight.of(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), aim, Double.POSITIVE_INFINITY))
        assertNotNull(Sight.of(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), aim, 0.0))
    }

    @Test
    fun farTargetsEndWithAnHonestlyLargerBound() {
        val near = boundAt(50.0)
        val far = boundAt(500.0)
        assertTrue(far / 500.0 > near / 50.0)
        // Never shown with more digits than that bound supports.
        val m = solvedAt(500.0).distance
        val text = RangeFormat.format(m, imperial = false)
        assertTrue(text.text, !text.value.contains('.') && !text.plusMinus.contains('.'))
    }

    private fun solvedAt(d: Double): RangeReading {
        val b = RayTriangulation.requiredBaseline(d, aim, model)
        val c2 = camera1 + Vec3(b, 0.0, 0.0)
        return (RayTriangulation.solve(sight(camera1, target(d), 0.0), sight(c2, target(d), b / WALK_SPEED), b, false, model, Double.MAX_VALUE).outcome as RangeOutcome.Ok).reading
    }

    private fun boundAt(d: Double) = solvedAt(d).distance.bound95

    @Test
    fun beyond500MetresIsOutOfRangeNeverANumber() {
        val d = 600.0
        val b = RayTriangulation.requiredBaseline(d, aim, model)
        val c2 = camera1 + Vec3(b, 0.0, 0.0)
        val r = RayTriangulation.solve(sight(camera1, target(d), 0.0), sight(c2, target(d), b), b, false, model)
        assertEquals(RangeOutcome.OutOfRange, r.outcome)
    }

    @Test
    fun trackingLostBetweenSightsIsRejected() {
        val c2 = camera1 + Vec3(5.0, 0.0, 0.0)
        val r = RayTriangulation.solve(sight(camera1, target(50.0), 0.0), sight(c2, target(50.0), 5.0), 5.0, true, model)
        assertEquals(RangeOutcome.Rejected(RangeRejection.TRACKING_LOST), r.outcome)
    }

    @Test
    fun nearParallelRaysAreRejected() {
        // 1 m sideways on a 200 m target: ~0.29°, under the 0.5° minimum.
        val c2 = camera1 + Vec3(1.0, 0.0, 0.0)
        val r = RayTriangulation.solve(sight(camera1, target(200.0), 0.0), sight(c2, target(200.0), 1.0), 1.0, false, model)
        assertEquals(RangeOutcome.Rejected(RangeRejection.NEAR_PARALLEL), r.outcome)
        // Exactly parallel sights.
        val p = RayTriangulation.solve(
            Sight(camera1, Vec3(0.0, 0.0, -1.0), aim, 0.0), Sight(c2, Vec3(0.0, 0.0, -1.0), aim, 1.0), 1.0, false, model,
        )
        assertEquals(RangeOutcome.Rejected(RangeRejection.NEAR_PARALLEL), p.outcome)
        assertNull(p.point)
    }

    @Test
    fun raysMeetingBehindThePhoneAreRejected() {
        val c2 = camera1 + Vec3(5.0, 0.0, 0.0)
        // Sight 2 aims away from the target (diverging rays meet behind the cameras).
        val r = RayTriangulation.solve(
            sight(camera1, target(50.0), 0.0), Sight(c2, Vec3(0.3, 0.0, -1.0), aim, 5.0), 5.0, false, model,
        )
        assertEquals(RangeOutcome.Rejected(RangeRejection.BEHIND_CAMERA), r.outcome)
    }

    @Test
    fun sightsOnDifferentThingsAreRejectedAsInconsistent() {
        val d = 50.0
        val c2 = camera1 + Vec3(7.0, 0.0, 0.0)
        // Sight 2 is on something 5 m higher: the rays miss each other by metres, far beyond their ±.
        val r = RayTriangulation.solve(sight(camera1, target(d), 0.0), sight(c2, target(d) + Vec3(0.0, 5.0, 0.0), 7.0), 7.0, false, model)
        assertEquals(RangeOutcome.Rejected(RangeRejection.INCONSISTENT_SIGHTS), r.outcome)
        assertTrue(r.missM > RangeLimits.MISS_REJECT_K * r.expectedMissSigmaM)
    }

    @Test
    fun aModerateMissInflatesTheBound() {
        val d = 50.0
        val c2 = camera1 + Vec3(7.0, 0.0, 0.0)
        val clean = RayTriangulation.solve(sight(camera1, target(d), 0.0), sight(c2, target(d), 7.0), 7.0, false, model)
        val expected = clean.expectedMissSigmaM
        // Miss of ~3 expected σ: still accepted, but with a wider ±.
        val off = RayTriangulation.solve(
            sight(camera1, target(d), 0.0), sight(c2, target(d) + Vec3(0.0, 3.0 * expected, 0.0), 7.0), 7.0, false, model,
        )
        val a = (clean.outcome as RangeOutcome.Ok).reading.distance.bound95
        val b = (off.outcome as RangeOutcome.Ok).reading.distance.bound95
        assertTrue("$b > $a", b > a * 1.3)
    }

    @Test
    fun tooShortABaselineForTheAimErrorIsTooUncertain() {
        // Huge aim error: the ± would be unbounded.
        val c2 = camera1 + Vec3(2.0, 0.0, 0.0)
        val r = RayTriangulation.solve(
            sight(camera1, target(100.0), 0.0, sigma = 0.02), sight(c2, target(100.0), 2.0, sigma = 0.02), 2.0, false, model,
        )
        assertEquals(RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN), r.outcome)
    }

    @Test
    fun invalidPathIsRejected() {
        val c2 = camera1 + Vec3(5.0, 0.0, 0.0)
        val r = RayTriangulation.solve(sight(camera1, target(50.0), 0.0), sight(c2, target(50.0), 5.0), Double.NaN, false, model)
        assertEquals(RangeOutcome.Rejected(RangeRejection.INVALID_INPUT), r.outcome)
    }

    @Test
    fun requiredBaselineGrowsWithDistanceAndIsClamped() {
        assertEquals(RangeLimits.MIN_BASELINE_M, RayTriangulation.requiredBaseline(2.0, aim, model), 1e-9)
        val b50 = RayTriangulation.requiredBaseline(50.0, aim, model)
        val b150 = RayTriangulation.requiredBaseline(150.0, aim, model)
        assertTrue(b150 > b50 && b50 > RangeLimits.MIN_BASELINE_M)
        assertEquals(RangeLimits.MAX_BASELINE_M, RayTriangulation.requiredBaseline(500.0, aim, model), 1e-9)
        assertEquals(RangeLimits.MIN_BASELINE_M, RayTriangulation.requiredBaseline(Double.NaN, aim, model), 1e-9)
    }

    @Test
    fun perpendicularBaselineIgnoresWalkingAlongTheSightLine() {
        val s1 = Sight(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), aim, 0.0)
        assertEquals(0.0, RayTriangulation.perpendicularBaseline(s1, Vec3(0.0, 0.0, -4.0)), 1e-12)
        assertEquals(3.0, RayTriangulation.perpendicularBaseline(s1, Vec3(3.0, 0.0, -4.0)), 1e-12)
    }

    @Test
    fun perpendicularAxesAreOrthonormalEvenLookingStraightUp() {
        for (d in listOf(Vec3(0.0, 0.0, -1.0), Vec3.UP, Vec3(0.3, -0.9, 0.1).normalized())) {
            val (u, v) = RayTriangulation.perpendicularAxes(d)
            assertEquals(1.0, u.length, 1e-9)
            assertEquals(1.0, v.length, 1e-9)
            assertEquals(0.0, u dot d, 1e-9)
            assertEquals(0.0, v dot d, 1e-9)
            assertEquals(0.0, u dot v, 1e-9)
        }
    }

    @Test
    fun sightRejectsInvalidInput() {
        val e = runCatching { Sight(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), -1.0, 0.0) }.exceptionOrNull()
        assertNotNull(e)
    }

    private fun perturb(direction: Vec3, sigma: Double, rnd: Random): Vec3 {
        val d = direction.normalized()
        val (u, v) = RayTriangulation.perpendicularAxes(d)
        return (d + u * (sigma * rnd.nextGaussian()) + v * (sigma * rnd.nextGaussian())).normalized()
    }

    private companion object {
        val DISTANCES = listOf(2.0, 10.0, 50.0, 150.0, 300.0, 500.0)
        const val TRIALS = 4000
        const val WALK_SPEED = 1.0
    }
}
