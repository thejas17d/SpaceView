package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** W10: plane-first lifting with a thickness estimate and a plane σ. */
class SupportPlaneTest {
    private val table = Plane.fromPointNormal(Vec3.ZERO, Vec3.UP)
    private val camera = Vec3(0.0, 0.45, 0.25)

    /** Points on the top face of a slab [thickness] thick, ±[noise] (m). */
    private fun slab(thickness: Double, noise: Double, n: Int = 80, seed: Long = 7): List<Vec3> {
        val r = Random(seed)
        return List(n) { Vec3(r.nextDouble() * 0.2 - 0.1, thickness + noise * r.nextGaussian(), r.nextDouble() * 0.28 - 0.14) }
    }

    /** Width of an A4 (0.21 m) whose top face is at [thickness], measured by lifting its corner rays onto [plane]. */
    private fun liftedWidth(plane: Plane, thickness: Double): Double {
        val a = Vec3(-0.105, thickness, 0.0)
        val b = Vec3(0.105, thickness, 0.0)
        val pa = plane.intersect(Ray(camera, a - camera), 0.05)!!
        val pb = plane.intersect(Ray(camera, b - camera), 0.05)!!
        return pa.distanceTo(pb)
    }

    @Test
    fun aSixMillimetreSlabShiftsThePlaneAndKeepsWidthWithinHalfAPercent() {
        val range = camera.length
        val r = SupportPlane.resolve(table, slab(0.006, 0.0005), camera, range)
        assertEquals(SupportPlane.Kind.SHIFTED, r.kind)
        assertEquals(0.006, r.thicknessM, 0.0005)
        val w = liftedWidth(r.plane!!, 0.006)
        assertTrue("width error ${abs(w - 0.21) / 0.21}", abs(w - 0.21) / 0.21 < 0.005)
        // Lifting onto the table itself would be over 1 % too wide.
        assertTrue(abs(liftedWidth(table, 0.006) - 0.21) / 0.21 > 0.01)
        assertTrue(r.sigmaM >= SupportPlane.planeSigma(range))
    }

    @Test
    fun aThinSheetAgreesWithTheSupportPlane() {
        val r = SupportPlane.resolve(table, slab(0.0003, 0.0005), camera, 0.5)
        assertEquals(SupportPlane.Kind.AGREES, r.kind)
        assertNotNull(r.plane)
        assertEquals(SupportPlane.planeSigma(0.5), r.sigmaM, 0.0)
    }

    @Test
    fun scatteredPointsDisagreeAndFewPointsAreNoEvidence() {
        val scattered = SupportPlane.resolve(table, slab(0.03, 0.02), camera, 0.5)
        assertEquals(SupportPlane.Kind.DISAGREES, scattered.kind)
        assertNull(scattered.plane)
        val few = SupportPlane.resolve(table, slab(0.006, 0.0005, n = 10), camera, 0.5)
        assertEquals(SupportPlane.Kind.NO_EVIDENCE, few.kind)
        assertNotNull(few.plane)
    }

    @Test
    fun planeSigmaIsOneMillimetreOrHalfAPercent() {
        assertEquals(0.001, SupportPlane.planeSigma(0.1), 0.0)
        assertEquals(0.005, SupportPlane.planeSigma(1.0), 1e-12)
        assertEquals(0.005, SupportPlane.biasRel(1.0), 1e-12)
        assertEquals(0.0, SupportPlane.biasRel(0.0), 0.0)
        assertEquals(0.002, SupportPlane.agreeTolerance(0.3), 0.0)
        val r = SupportPlane.resolve(table, slab(0.006, 0.0005), camera, 0.5)
        assertEquals(r.sigmaM / 0.5, r.biasRel(0.5), 1e-12)
    }
}
