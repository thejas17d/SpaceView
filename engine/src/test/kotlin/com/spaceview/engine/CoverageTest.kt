package com.spaceview.engine

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Honesty of the displayed ± under noise that does **not** come from the model's own assumptions
 * (review §2): constant aim bias per endpoint (averaging cannot remove it), AR(1)-correlated frames,
 * heavy-tailed (Laplace) aim errors, non-Gaussian (uniform) scale error, crossing corner order, and
 * plane fits from thin strips. Acceptance: empirical coverage of value ± bound95 ≥ 93% per scenario,
 * at fixed seeds. Each scenario prints a `COVERAGE` line (see the test report's system-out).
 */
class CoverageTest {

    companion object {
        const val TRIALS = 2000
        const val MIN_COVERAGE = 0.93
        private val results = ArrayList<Pair<String, Double>>()

        @JvmStatic @AfterClass fun summary() {
            if (results.isEmpty()) return
            val min = results.minOf { it.second }
            println("COVERAGE SUMMARY: ${results.size} scenarios, min=${"%.4f".format(min)}, mean=${"%.4f".format(results.map { it.second }.average())}")
        }
    }

    private fun record(name: String, inside: Int, total: Int, meanBound: Double) {
        val c = inside.toDouble() / total
        synchronized(results) { results += name to c }
        println("COVERAGE %-62s %.4f  (n=%d, mean ±95 = %.2f mm)".format(name, c, total, meanBound * 1000))
        assertTrue("$name: coverage $c < $MIN_COVERAGE", c >= MIN_COVERAGE)
    }

    private fun gauss3(rng: Random) = Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian())

    /** Laplace (double-exponential) sample with the given RMS: heavier tails than the model's Gaussian. */
    private fun laplace(rng: Random, rms: Double): Double {
        var u: Double
        do { u = rng.nextDouble() - 0.5 } while (abs(u) >= 0.5 - 1e-12)
        return -(rms / sqrt(2.0)) * sign(u) * ln(1 - 2 * abs(u))
    }

    private fun laplace3(rng: Random, rms: Double) = Vec3(laplace(rng, rms), laplace(rng, rms), laplace(rng, rms))

    /** Scale error uniform on ±scale95 (not the model's Gaussian; same stated 95% half-width). */
    private fun uniformScale(rng: Random, tier: DeviceTier) = 1 + (rng.nextDouble() * 2 - 1) * tier.scale95

    /** Stationary AR(1) jitter series in 3-D with marginal σ [sigma] and lag-1 correlation [rho]. */
    private fun ar1(rng: Random, n: Int, rho: Double, sigma: Double): List<Vec3> {
        var x = gauss3(rng) * sigma
        val innov = sigma * sqrt(1 - rho * rho)
        return List(n) { if (it > 0) x = x * rho + gauss3(rng) * innov; x }
    }

    private fun randomUnit(rng: Random): Vec3 {
        while (true) {
            val v = gauss3(rng)
            if (v.length > 1e-3) return v.normalized()
        }
    }

    // ---- 1. Constant aim bias, no LocalisationInput (the review's MAJOR-1 probe) ----------------

    /**
     * 3 mm per-axis aim bias held for the whole hold (≈1.5 px at 1 m on VGA) plus 1 mm i.i.d. jitter,
     * 30 samples, NO_DEPTH, PointEstimator → model.distance() with no localisation. Before the fix:
     * 32% / 75% / 92% coverage for 8.56 cm / 30 cm / 1 m.
     */
    @Test fun constantAimBiasWithoutLocalisation() {
        val tier = DeviceTier.NO_DEPTH
        val model = UncertaintyModel(tier)
        for ((i, length) in listOf(0.0856, 0.30, 1.0).withIndex()) {
            val rng = Random(100L + i)
            var inside = 0
            var bounds = 0.0
            repeat(TRIALS) {
                val s = uniformScale(rng, tier)
                val a = Vec3(rng.nextDouble() * 0.4 - 0.2, 1.0, -1.0)
                val b = a + randomUnit(rng) * length
                fun endpoint(p: Vec3): UncertainPoint {
                    val bias = gauss3(rng) * 0.003
                    val est = PointEstimator()
                    repeat(30) { est.add(p * s + bias + gauss3(rng) * 0.001) }
                    return est.estimate()!!.point
                }
                val m = model.distance(endpoint(a), endpoint(b))
                bounds += m.bound95
                if (abs(m.value - length) <= m.bound95) inside++
            }
            record("aim bias 3mm/axis, jitter 1mm, no LocalisationInput, L=${length}m", inside, TRIALS, bounds / TRIALS)
        }
    }

    // ---- 2. Heavy-tailed aim bias with LocalisationInput, correlated jitter -----------------------

    /**
     * Ranges 0.5–2.5 m, focal 500 or 1500 px, LocalisationInput at its default 2 px. The true aim error
     * is a constant Laplace error per endpoint with 2 px RMS per axis (heavier tails than the model),
     * jitter is AR(1) with ρ = 0.8, σ = 1.5 mm, scale error uniform on ±2%.
     */
    @Test fun laplaceAimBiasWithLocalisation() {
        for (tier in listOf(DeviceTier.NO_DEPTH, DeviceTier.DEPTH, DeviceTier.DEPTH_SENSOR)) {
            val model = UncertaintyModel(tier)
            val rng = Random(200L + tier.ordinal)
            var inside = 0
            var bounds = 0.0
            repeat(TRIALS) {
                val range = 0.5 + rng.nextDouble() * 2.0
                val f = if (rng.nextBoolean()) 500.0 else 1500.0
                val loc = LocalisationInput(range, f)
                val s = uniformScale(rng, tier)
                val length = 0.05 + rng.nextDouble() * 1.5
                val a = Vec3(rng.nextDouble() - 0.5, 1.2, -range)
                val dir = Vec3(rng.nextGaussian(), rng.nextGaussian(), rng.nextGaussian() * 0.2).normalized()
                val b = a + dir * length
                fun endpoint(p: Vec3): UncertainPoint {
                    val bias = laplace3(rng, 2.0 * range / f)
                    val est = PointEstimator()
                    for (j in ar1(rng, 30, 0.8, 0.0015)) est.add(p * s + bias + j)
                    return est.estimate(loc)!!.point
                }
                val m = model.distance(endpoint(a), endpoint(b))
                bounds += m.bound95
                if (abs(m.value - length) <= m.bound95) inside++
            }
            record("Laplace aim 2px, AR(1) rho=0.8, LocalisationInput, $tier", inside, TRIALS, bounds / TRIALS)
        }
    }

    // ---- 3. AR(1)-correlated frames -------------------------------------------------------------

    /** Point level (MAJOR-2): true point inside ±1.96·SE along a fixed direction. Was 90/82/70/55%. */
    @Test fun ar1PointStandardError() {
        val dir = Vec3(1.0, 0.4, -0.2).normalized()
        for ((i, rho) in listOf(0.5, 0.8, 0.9, 0.95).withIndex()) {
            val rng = Random(300L + i)
            var inside = 0
            var bounds = 0.0
            repeat(TRIALS) {
                val est = PointEstimator()
                for (j in ar1(rng, 30, rho, 0.003)) est.add(Vec3(0.0, 1.0, -1.0) + j)
                val e = est.estimate()!!
                val bound = Uncertainty.K95 * e.point.sigmaAlong(dir)
                bounds += bound
                if (abs((e.position - Vec3(0.0, 1.0, -1.0)) dot dir) <= bound) inside++
            }
            record("AR(1) point SE, sigma=3mm, n=30, rho=$rho", inside, TRIALS, bounds / TRIALS)
        }
    }

    /** End to end: AR(1) jitter σ = 3 mm, Laplace aim bias, LocalisationInput, NO_DEPTH distance. */
    @Test fun ar1Distance() {
        val model = UncertaintyModel(DeviceTier.NO_DEPTH)
        for ((i, rho) in listOf(0.5, 0.8, 0.9, 0.95).withIndex()) {
            val rng = Random(400L + i)
            var inside = 0
            var bounds = 0.0
            repeat(TRIALS) {
                val loc = LocalisationInput(1.0, 500.0)
                val s = uniformScale(rng, DeviceTier.NO_DEPTH)
                val length = 0.05 + rng.nextDouble() * 1.0
                val a = Vec3(0.0, 1.0, -1.0)
                val b = a + randomUnit(rng) * length
                fun endpoint(p: Vec3): UncertainPoint {
                    val bias = laplace3(rng, 0.004)
                    val est = PointEstimator()
                    for (j in ar1(rng, 30, rho, 0.003)) est.add(p * s + bias + j)
                    return est.estimate(loc)!!.point
                }
                val m = model.distance(endpoint(a), endpoint(b))
                bounds += m.bound95
                if (abs(m.value - length) <= m.bound95) inside++
            }
            record("AR(1) jitter 3mm + Laplace aim, distance, rho=$rho", inside, TRIALS, bounds / TRIALS)
        }
    }

    // ---- 4. Z-order corners -----------------------------------------------------------------------

    private val frame = listOf(Vec3(-0.3, 1.6, -1.2), Vec3(0.3, 1.6, -1.2), Vec3(0.3, 1.2, -1.2), Vec3(-0.3, 1.2, -1.2))

    /** Corners tapped TL, TR, BL, BR (bow-tie) or TL, TR, BL (3 corners, right angle first). */
    @Test fun zOrderCorners() {
        val model = UncertaintyModel(DeviceTier.NO_DEPTH)
        val orders = mapOf("4 corners Z-order" to listOf(0, 1, 3, 2), "3 corners, right angle first" to listOf(0, 1, 3))
        for ((k, entry) in orders.entries.withIndex()) {
            val rng = Random(500L + k)
            var inside = 0
            var total = 0
            var bounds = 0.0
            var reordered = 0
            repeat(TRIALS) {
                val s = uniformScale(rng, DeviceTier.NO_DEPTH)
                val loc = LocalisationInput(1.25, 500.0)
                val wall = Plane.fromPointNormal(Vec3(0.0, 0.0, -1.2 * s), Vec3.UNIT_Z)
                val corners = entry.value.map { idx ->
                    val bias = laplace3(rng, 2.0 * 1.25 / 500.0)
                    val est = PointEstimator()
                    for (j in ar1(rng, 30, 0.9, 0.002)) est.add(frame[idx] * s + bias + j)
                    est.estimate(loc)!!.point
                }
                val r = Shapes.rectangle(corners, wall, model)
                if (r.cornersReordered) reordered++
                for ((m, truth) in listOf(r.width to 0.6, r.height to 0.4)) {
                    total++
                    bounds += m.bound95
                    if (abs(m.value - truth) <= m.bound95) inside++
                }
            }
            assertEquals("${entry.key}: every trial must be reordered", TRIALS, reordered)
            record("Z-order rectangle W and H, ${entry.key}", inside, total, bounds / total)
        }
    }

    // ---- 5. Thin-strip planes ---------------------------------------------------------------------

    /**
     * The wall is only seen in a horizontal band (a textured strip on a plain wall). Corners of a
     * 60 × 40 cm frame extend ±20 cm beyond it and are found by ray–plane intersection with the fitted
     * band plane ([Shapes.rectangleFromRays]), from a camera low and to the side. A 2 cm band must be
     * refused; an 8 cm band is accepted and its tilt uncertainty must keep the ± honest.
     */
    @Test fun thinStripPlanes() {
        val tier = DeviceTier.DEPTH
        val model = UncertaintyModel(tier)
        val rng0 = Random(600)
        var refused = 0
        repeat(200) {
            val pts = List(150) {
                Vec3((rng0.nextDouble() * 2 - 1) * 0.5, 1.4 + (rng0.nextDouble() * 2 - 1) * 0.01, -1.2 + rng0.nextGaussian() * 0.003)
            }
            if (PlaneFitter.ransac(pts) == null) refused++
        }
        assertEquals("2 cm strips must never give a plane", 200, refused)

        for ((k, cam) in listOf(Vec3(0.2, 1.4, 0.0), Vec3(0.6, 0.7, 0.0)).withIndex()) {
            val rng = Random(610L + k)
            var inside = 0
            var total = 0
            var bounds = 0.0
            var noFit = 0
            repeat(TRIALS) {
                val s = uniformScale(rng, tier)
                val pts = List(120) {
                    Vec3((rng.nextDouble() * 2 - 1) * 0.5, 1.4 + (rng.nextDouble() * 2 - 1) * 0.04, -1.2 + rng.nextGaussian() * 0.003) * s
                }
                val fit = PlaneFitter.ransac(pts)
                if (fit == null) { noFit++; return@repeat }
                val f = 1000.0
                val rays = frame.map { c ->
                    val d = (c * s - cam).normalized()
                    // Constant Laplace aim error of 2 px RMS per image axis.
                    val u1 = (d cross Vec3.UP).normalized()
                    val u2 = u1 cross d
                    Ray(cam, d + u1 * laplace(rng, 2.0 / f) + u2 * laplace(rng, 2.0 / f))
                }
                val locs = frame.map { LocalisationInput(it.distanceTo(cam), f) }
                val r = Shapes.rectangleFromRays(rays, fit, model, locs) ?: return@repeat
                for ((m, truth) in listOf(r.width to 0.6, r.height to 0.4)) {
                    total++
                    bounds += m.bound95
                    if (abs(m.value - truth) <= m.bound95) inside++
                }
            }
            assertTrue("8 cm bands should fit ($noFit refused)", noFit < TRIALS / 20)
            record("8cm strip plane, rays, camera ${if (k == 0) "head-on" else "low/oblique"}", inside, total, bounds / total)
        }
    }
}
