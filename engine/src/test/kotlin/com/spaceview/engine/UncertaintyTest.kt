package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class UncertaintyTest {

    @Test fun measurementBasics() {
        val m = Measurement(0.60, 0.005)
        assertEquals(0.0098, m.bound95, 1e-12)
        assertEquals(0.0098 / 0.60, m.relative95, 1e-12)
        assertEquals(0.60 - 0.0098, m.low95, 1e-12)
        assertEquals(0.60 + 0.0098, m.high95, 1e-12)
        assertEquals(Measurement(0.60, 0.005), m)
        assertEquals(Measurement(0.60, 0.005).hashCode(), m.hashCode())
        assertNotEquals(Measurement(0.61, 0.005), m)
        assertTrue(m.toString().contains("±"))
        assertEquals(0.0, Measurement.exact(1.0).bound95, 0.0)
        assertEquals(0.0, Measurement(0.0, 0.0).relative95, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, Measurement(0.0, 0.01).relative95, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSigmaRejected() { Measurement(1.0, -0.1) }

    @Test(expected = IllegalArgumentException::class)
    fun nanValueRejected() { Measurement(Double.NaN, 0.1) }

    @Test fun qualityThresholds() {
        // Relative rule.
        assertEquals(Quality.GOOD, Quality.of(0.009, 0.05))
        assertEquals(Quality.FAIR, Quality.of(0.025, 0.05))
        assertEquals(Quality.ROUGH, Quality.of(0.04, 0.05))
        // Absolute override for small objects: card 8.56 cm ± 4 mm (4.7%) → GOOD by ≤ 5 mm.
        assertEquals(Quality.GOOD, Quality.of(Measurement(0.0856, 0.004 / Uncertainty.K95)))
        // ± 1.5 cm on 30 cm (5%) → FAIR by ≤ 2 cm.
        assertEquals(Quality.FAIR, Quality.of(Measurement(0.30, 0.015 / Uncertainty.K95)))
        // ± 1.5 cm on 10 cm (15%) → ROUGH: absolute override disabled above 10% relative.
        assertEquals(Quality.ROUGH, Quality.of(Measurement(0.10, 0.015 / Uncertainty.K95)))
        // Boundaries are inclusive.
        assertEquals(Quality.GOOD, Quality.of(Quality.GOOD_REL, 1.0))
        assertEquals(Quality.FAIR, Quality.of(Quality.FAIR_REL, 1.0))
        assertEquals(Quality.ROUGH, Quality.of(Quality.FAIR_REL + 1e-9, 1.0))
        assertEquals(Quality.ROUGH, Quality.worst(Quality.GOOD, Quality.ROUGH, Quality.FAIR))
        assertEquals(Quality.GOOD, Quality.worst())
        assertEquals(Quality.FAIR, Measurement(1.0, 0.01).quality)
    }

    @Test fun deviceTiers() {
        assertEquals(0.02 / 1.96, DeviceTier.NO_DEPTH.scaleSigmaRel, 1e-15)
        assertTrue(DeviceTier.DEPTH.scaleSigmaRel < DeviceTier.NO_DEPTH.scaleSigmaRel)
        assertTrue(DeviceTier.DEPTH_SENSOR.scaleSigmaRel < DeviceTier.DEPTH.scaleSigmaRel)
    }

    @Test fun lengthCombinesRandomAndScale() {
        val model = UncertaintyModel(DeviceTier.NO_DEPTH)
        val m = model.length(RawLength(1.0, 0.003))
        val s = DeviceTier.NO_DEPTH.scaleSigmaRel
        assertEquals(1.0, m.value, 0.0)
        assertEquals(sqrt(0.003 * 0.003 + s * s), m.sigma, 1e-12)
        // With no random error, ±95 equals the tier's 2%.
        assertEquals(0.02 * 2.0, model.length(RawLength(2.0, 0.0)).bound95, 1e-12)
        // Extra relative term adds in quadrature.
        val e = model.length(RawLength(1.0, 0.0), extraRelSigma = 0.005)
        assertEquals(sqrt(s * s + 0.005 * 0.005), e.sigma, 1e-12)
        // Unmeasured (typed-in) lengths carry only their own σ.
        val typed = model.length(RawLength(0.02, 0.005, isMeasured = false))
        assertEquals(0.005, typed.sigma, 1e-15)
        assertTrue(RawLength(1.0, 0.0).toString().contains("RawLength"))
    }

    @Test fun customScaleSigma() {
        val model = UncertaintyModel(scaleSigmaRel = 0.0)
        assertEquals(0.002, model.length(RawLength(3.0, 0.002)).sigma, 1e-15)
    }

    @Test fun calibratedModelUsesCalibrationUncertainty() {
        val cal = ScaleCalibration(0.97, 0.97 * 0.002)
        val model = UncertaintyModel(DeviceTier.NO_DEPTH).withCalibration(cal)
        assertEquals(0.97, model.scaleFactor, 0.0)
        val rel = sqrt(0.002 * 0.002 + 0.003 * 0.003)
        assertEquals(rel, model.effectiveScaleSigmaRel, 1e-12)
        val m = model.length(RawLength(1.0, 0.001))
        assertEquals(0.97, m.value, 1e-12)
        assertEquals(sqrt((0.97 * 0.001) * (0.97 * 0.001) + (rel * 0.97) * (rel * 0.97)), m.sigma, 1e-12)
        assertEquals(1.0, model.withCalibration(null).scaleFactor, 0.0)
    }

    @Test fun productPropagation() {
        val model = UncertaintyModel(scaleSigmaRel = 0.01)
        val area = model.product(listOf(RawLength(2.0, 0.02), RawLength(1.0, 0.01)))
        assertEquals(2.0, area.value, 1e-12)
        // random rel: √(0.01² + 0.01²); scale fully correlated: 2 × 1%.
        val rel = sqrt(0.0001 + 0.0001 + 0.02 * 0.02)
        assertEquals(rel * 2.0, area.sigma, 1e-12)
        val vol = model.product(listOf(RawLength(1.0, 0.0), RawLength(1.0, 0.0), RawLength(1.0, 0.0, isMeasured = false)))
        assertEquals(0.02, vol.sigma, 1e-12) // two measured dims → 2 × 1%
    }

    @Test fun distanceProjectsCovarianceOnTheLine() {
        val model = UncertaintyModel(scaleSigmaRel = 0.0)
        // Large error perpendicular to the segment must not count; along it, it must.
        val a = UncertainPoint(Vec3.ZERO, Cov3(1e-6, 0.0, 0.0, 1e-2, 0.0, 1e-2))
        val b = UncertainPoint(Vec3(1.0, 0.0, 0.0), Cov3(1e-6, 0.0, 0.0, 1e-2, 0.0, 1e-2))
        val d = model.distance(a, b)
        assertEquals(1.0, d.value, 1e-12)
        assertEquals(sqrt(2e-6), d.sigma, 1e-12)
        // Coincident points: falls back to the average variance.
        val z = Uncertainty.rawDistance(a, a)
        assertEquals(0.0, z.value, 0.0)
        assertTrue(z.randomSigma > 0)
    }

    @Test fun heightAndHorizontal() {
        val model = UncertaintyModel(scaleSigmaRel = 0.0)
        val floor = UncertainPoint.of(Vec3(0.3, 0.0, -1.0), 0.002)
        val top = UncertainPoint.of(Vec3(0.8, 1.75, -1.4), 0.002)
        val h = model.height(floor, top)
        assertEquals(1.75, h.value, 1e-12)
        assertEquals(sqrt(2.0) * 0.002, h.sigma, 1e-12)
        val hz = model.horizontalDistance(floor, top)
        assertEquals(sqrt(0.25 + 0.16), hz.value, 1e-12)
        assertEquals(0.0, Uncertainty.rawHorizontal(floor, UncertainPoint.of(Vec3(0.3, 2.0, -1.0), 0.0)).value, 1e-12)
        // Height is the same whichever point is first.
        assertEquals(h.value, model.height(top, floor).value, 0.0)
    }

    @Test fun pixelLocalisation() {
        assertEquals(0.002, Uncertainty.pixelLocalisationSigma(1.0, 500.0), 1e-15)
        assertEquals(0.002, Uncertainty.pixelLocalisationSigma(3.0, 1500.0), 1e-15)
        assertEquals(0.006, Uncertainty.pixelLocalisationSigma(1.0, 500.0, 3.0), 1e-15)
    }

    /**
     * End-to-end honesty check: simulate the error sources the model assumes (scale error drawn from the
     * tier prior, endpoint noise averaged over 20 samples, tap localisation) and check that the displayed
     * ±95 really covers ~95% of trials — "a ± that is honest is worth more than one that is small".
     */
    @Test fun displayedBoundCoversTruth() {
        val rng = Random(2026)
        val model = UncertaintyModel(DeviceTier.NO_DEPTH)
        val trials = 2000
        var inside = 0
        repeat(trials) {
            val length = 0.3 + rng.nextDouble() * 2.5
            val trueA = Vec3(rng.nextDouble(), 1.0, -1.5)
            val dir = Vec3(rng.nextGaussian(), rng.nextGaussian() * 0.3, rng.nextGaussian() * 0.2).normalized()
            val trueB = trueA + dir * length
            val scale = 1 + rng.nextGaussian() * model.scaleSigmaRel
            val tapSigma = Uncertainty.pixelLocalisationSigma(1.5, 500.0, 1.0)
            fun endpoint(p: Vec3): UncertainPoint {
                val tapErr = Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * tapSigma
                val est = PointEstimator()
                repeat(20) {
                    est.add(p * scale + tapErr + Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian()) * 0.003)
                }
                return est.estimate()!!.point.withExtraSigma(tapSigma)
            }
            val m = model.distance(endpoint(trueA), endpoint(trueB))
            if (abs(m.value - length) <= m.bound95) inside++
        }
        val coverage = inside.toDouble() / trials
        assertTrue("coverage=$coverage", coverage in 0.92..0.985)
    }
}
