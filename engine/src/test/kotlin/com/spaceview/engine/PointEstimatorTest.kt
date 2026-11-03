package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PointEstimatorTest {

    private fun noisy(rng: Random, truth: Vec3, sigma: Double) =
        Vec3(truth.x + rng.nextGaussian() * sigma, truth.y + rng.nextGaussian() * sigma, truth.z + rng.nextGaussian() * sigma)

    @Test fun needsMinimumSamples() {
        val est = PointEstimator()
        val rng = Random(1)
        repeat(14) { assertEquals(SampleResult.ACCEPTED, est.add(noisy(rng, Vec3.ZERO, 0.003))) }
        assertNull(est.estimate())
        assertFalse(est.hasEnoughSamples)
        assertEquals(14.0 / 15, est.progress, 1e-12)
        est.add(noisy(rng, Vec3.ZERO, 0.003))
        assertTrue(est.hasEnoughSamples)
        assertEquals(1.0, est.progress, 0.0)
        assertNotNull(est.estimate())
    }

    @Test fun averagingReducesError() {
        val rng = Random(42)
        val truth = Vec3(0.4, 1.1, -1.5)
        val sigma = 0.005
        var singleErr = 0.0
        var avgErr = 0.0
        val trials = 400
        repeat(trials) {
            val est = PointEstimator()
            var first: Vec3? = null
            repeat(30) {
                val s = noisy(rng, truth, sigma)
                if (first == null) first = s
                est.add(s)
            }
            singleErr += first!!.distanceTo(truth)
            avgErr += est.estimate()!!.position.distanceTo(truth)
        }
        singleErr /= trials; avgErr /= trials
        // √30 ≈ 5.5× theoretical; require at least 4×.
        assertTrue("single=$singleErr avg=$avgErr", avgErr < singleErr / 4)
        assertTrue(avgErr < 0.002)
    }

    @Test fun standardErrorIsCalibrated() {
        // The true point should fall inside ±1.96·SE along a fixed direction ~95% of the time.
        val rng = Random(7)
        val truth = Vec3(0.0, 0.0, -1.0)
        val dir = Vec3(1.0, 0.5, -0.3).normalized()
        var inside = 0
        val trials = 1000
        repeat(trials) {
            val est = PointEstimator(PointEstimatorConfig(correctAutocorrelation = false))
            repeat(30) { est.add(noisy(rng, truth, 0.004)) }
            val e = est.estimate()!!
            val err = (e.position - truth) dot dir
            if (kotlin.math.abs(err) <= 1.96 * e.point.sigmaAlong(dir)) inside++
        }
        val coverage = inside.toDouble() / trials
        assertTrue("coverage=$coverage", coverage in 0.92..0.98)
    }

    @Test fun rejectsOutliers() {
        val rng = Random(3)
        val truth = Vec3(1.0, 0.0, -2.0)
        val est = PointEstimator()
        repeat(30) { i ->
            // 20% gross outliers (e.g. hit-test slipping onto the background 30 cm behind).
            val p = noisy(rng, truth, 0.002) + if (i % 5 == 2) Vec3(0.0, 0.0, -0.3 - rng.nextDouble() * 0.2) else Vec3.ZERO
            est.add(p)
        }
        val e = est.estimate()!!
        assertEquals(24, e.inlierCount)
        assertEquals(30, e.sampleCount)
        assertTrue("err=${e.position.distanceTo(truth)}", e.position.distanceTo(truth) < 0.002)
        assertTrue(e.isStable)
        assertTrue(e.spreadRms < 0.006)
        assertTrue(e.standardError > 0)
        assertTrue(e.toString().contains("PointEstimate"))
    }

    @Test fun outlierRejectionRatesOverManyTrials() {
        val rng = Random(1001)
        var goodRejected = 0
        var goodTotal = 0
        var badKept = 0
        repeat(300) {
            val est = PointEstimator()
            val nBad = rng.nextInt(7) // 0–20% gross outliers
            repeat(30) { i ->
                val bad = i < nBad
                val offset = if (bad) Vec3(rng.nextGaussian() * 0.05, rng.nextGaussian() * 0.05, -0.2 - rng.nextDouble() * 0.5) else Vec3.ZERO
                est.add(noisy(rng, Vec3(0.0, 1.0, -1.5), 0.003) + offset)
            }
            val e = est.estimate()!!
            goodTotal += 30 - nBad
            val rejected = 30 - e.inlierCount
            if (rejected >= nBad) goodRejected += rejected - nBad else badKept += nBad - rejected
            assertTrue(e.position.distanceTo(Vec3(0.0, 1.0, -1.5)) < 0.004)
        }
        assertEquals(0, badKept)
        assertTrue("false rejection ${goodRejected.toDouble() / goodTotal}", goodRejected.toDouble() / goodTotal < 0.01)
    }

    @Test fun trackingGate() {
        val est = PointEstimator()
        assertEquals(SampleResult.REJECTED_TRACKING, est.add(Vec3.ZERO, TrackingQuality.LIMITED))
        assertEquals(SampleResult.REJECTED_INVALID, est.add(Vec3(Double.NaN, 0.0, 0.0)))
        assertEquals(0, est.sampleCount)
        repeat(10) { est.add(Vec3.ZERO) }
        assertEquals(10, est.sampleCount)
        // LIMITED keeps the buffer; NOT_TRACKING clears it (relocalisation may jump).
        est.add(Vec3.ZERO, TrackingQuality.LIMITED)
        assertEquals(10, est.sampleCount)
        assertEquals(SampleResult.REJECTED_TRACKING, est.add(Vec3.ZERO, TrackingQuality.NOT_TRACKING))
        assertEquals(0, est.sampleCount)

        val keep = PointEstimator(PointEstimatorConfig(resetOnTrackingLoss = false))
        repeat(5) { keep.add(Vec3.ZERO) }
        keep.add(Vec3.ZERO, TrackingQuality.NOT_TRACKING)
        assertEquals(5, keep.sampleCount)
    }

    @Test fun slidingWindowKeepsLatest() {
        val est = PointEstimator(PointEstimatorConfig(minSamples = 5, windowSize = 10))
        // Old samples far away, then 10 fresh ones at the true spot: the window must forget the old ones.
        repeat(10) { est.add(Vec3(5.0, 5.0, 5.0)) }
        val rng = Random(9)
        repeat(10) { est.add(noisy(rng, Vec3.ZERO, 0.001)) }
        assertEquals(10, est.sampleCount)
        val e = est.estimate()!!
        assertTrue(e.position.length < 0.002)
        est.reset()
        assertEquals(0, est.sampleCount)
    }

    @Test fun identicalSamplesGiveZeroError() {
        val est = PointEstimator()
        repeat(20) { est.add(Vec3(1.0, 2.0, 3.0)) }
        val e = est.estimate()!!
        assertEquals(Vec3(1.0, 2.0, 3.0), e.position)
        assertEquals(0.0, e.standardError, 0.0)
        assertEquals(20, e.inlierCount)
        assertTrue(e.isStable)
    }

    @Test fun autocorrelationInflatesStandardError() {
        // A slow random walk (drifting VIO) is strongly autocorrelated: the SE must be larger than σ/√n.
        val rng = Random(11)
        val walk = PointEstimator()
        val naive = PointEstimator(PointEstimatorConfig(correctAutocorrelation = false))
        var p = Vec3.ZERO
        repeat(30) {
            p += Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * 0.0005
            walk.add(p); naive.add(p)
        }
        val a = walk.estimate()!!
        val b = naive.estimate()!!
        assertTrue(a.effectiveSampleSize < 15)
        assertEquals(30.0, b.effectiveSampleSize, 0.0)
        assertTrue(a.standardError > 1.5 * b.standardError)
    }

    @Test fun unstableWhenScattered() {
        val rng = Random(5)
        val est = PointEstimator()
        repeat(30) { est.add(noisy(rng, Vec3.ZERO, 0.03)) } // 3 cm hand shake
        val e = est.estimate()!!
        assertFalse(e.isStable)
    }

    @Test fun unstableWhenMostlyOutliers() {
        val rng = Random(17)
        val est = PointEstimator(PointEstimatorConfig(minSamples = 10, windowSize = 10))
        // 7 good samples and 3 gross outliers: 70% inliers is still acceptable.
        repeat(7) { est.add(noisy(rng, Vec3.ZERO, 0.001)) }
        repeat(3) { est.add(Vec3(0.5 + it, 0.3 * it, -1.0)) }
        val ok = est.estimate()!!
        assertEquals(7, ok.inlierCount)
        assertTrue(ok.isStable)
        // A stricter policy calls the same data unstable.
        val strict = PointEstimator(PointEstimatorConfig(minSamples = 10, windowSize = 10, minInlierFraction = 0.8))
        val rng2 = Random(17)
        repeat(7) { strict.add(noisy(rng2, Vec3.ZERO, 0.001)) }
        repeat(3) { strict.add(Vec3(0.5 + it, 0.3 * it, -1.0)) }
        val e = strict.estimate()!!
        assertEquals(7, e.inlierCount)
        assertFalse(e.isStable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badConfig() { PointEstimatorConfig(minSamples = 20, windowSize = 10) }

    @Test fun medianHelpers() {
        assertEquals(3.0, Stats.median(listOf(5.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertEquals(7.0, Stats.median(listOf(7.0)), 0.0)
        assertEquals(2.0, Stats.median(listOf(2.0, 2.0, 2.0, 2.0)), 0.0)
        val rng = Random(13)
        repeat(200) {
            val n = 1 + rng.nextInt(40)
            val xs = List(n) { (rng.nextInt(20) - 10).toDouble() }
            val sorted = xs.sorted()
            val expected = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
            assertEquals(expected, Stats.median(xs), 0.0)
        }
    }
}
