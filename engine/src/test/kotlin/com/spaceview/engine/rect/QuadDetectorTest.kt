package com.spaceview.engine.rect

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.FaceReprojection
import com.spaceview.engine.Plane
import com.spaceview.engine.Vec2
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** W9 acceptance tests on synthetic perspective images (research `close-range-and-rectangles.md` §5.2). */
class QuadDetectorTest {
    private val detector = QuadDetector()
    private val table = Plane.fromPointNormal(Vec3.ZERO, Vec3.UNIT_Z)

    /** The detected quad matching the scene's rectangle (all corners within 4 px), or null. */
    private fun match(scene: SyntheticScene, quads: List<DetectedQuad>): DetectedQuad? =
        quads.firstOrNull { q -> scene.trueCornersPx.all { t -> q.corners.any { it.distanceTo(t) < 4.0 } } }

    private fun errors(scene: SyntheticScene, blur: Double = 0.7, noise: Double = 2.0, seed: Long = 1): Pair<Double, Double>? {
        val q = match(scene, detector.detect(scene.render(blur, noise, seed))) ?: return null
        return SyntheticScene.cornerErrors(scene.trueCornersPx, q.corners)
    }

    @Test
    fun anA4AtHalfAMetreIsFoundWithSubPixelCorners() {
        val t0 = System.nanoTime()
        val e = errors(SyntheticScene())
        val ms = (System.nanoTime() - t0) / 1e6
        assertNotNull("not detected", e)
        assertTrue("corner RMS ${e!!.first} px", e.first <= 0.5)
        println("W9 detect + refine (JVM, incl. render): $ms ms")
    }

    @Test
    fun recallAndCornerAccuracyAcrossTiltAndDistance() {
        var found = 0
        var total = 0
        val worst = ArrayList<String>()
        for (d in listOf(0.5, 1.0)) for (tilt in listOf(0.0, 20.0, 40.0)) for (yaw in listOf(0.0, 35.0)) {
            total++
            val scene = SyntheticScene(distance = d, tiltDeg = tilt, yawDeg = yaw, widthM = if (d > 0.7) 0.4 else 0.297, heightM = if (d > 0.7) 0.3 else 0.21)
            // Only views where the whole object is in the picture count (the others must be refused: see below).
            if (!scene.trueCornersPx.all { it.x in 64.0..1216.0 && it.y in 36.0..684.0 }) { total--; continue }
            val e = errors(scene, seed = total.toLong()) ?: continue
            found++
            if (e.first > 0.5) worst += "d=$d tilt=$tilt yaw=$yaw rms=${e.first}"
        }
        assertTrue("recall $found / $total", found >= total * 0.95)
        assertTrue("corner RMS > 0.5 px: $worst", worst.isEmpty())
    }

    @Test
    fun blurOfTwoPixelsKeepsCornersWithinOnePixel() {
        val e = errors(SyntheticScene(tiltDeg = 20.0), blur = 2.0)
        assertNotNull(e)
        assertTrue("RMS ${e!!.first}", e.first <= 1.0)
    }

    @Test
    fun lowContrastWithNoiseIsStillFound() {
        val e = errors(SyntheticScene(objectGrey = 125.0, backgroundGrey = 95.0, woodTexture = false, text = false), noise = 4.0)
        assertNotNull("30-level contrast not detected", e)
        assertTrue(e!!.first <= 1.0)
    }

    @Test
    fun roundedCornersGiveTheVirtualCornerWithinOnePixel() {
        val e = errors(SyntheticScene(cornerRadiusM = 0.01, tiltDeg = 15.0))
        assertNotNull(e)
        assertTrue("max ${e!!.second}", e.second <= 1.0)
    }

    @Test
    fun aFrameIsFoundByItsOuterBorderFirst() {
        val scene = SyntheticScene(widthM = 0.40, heightM = 0.30, distance = 0.8, objectGrey = 55.0, backgroundGrey = 175.0,
            woodTexture = false, text = false, inner = Triple(0.30, 0.20, 205.0))
        val quads = detector.detect(scene.render(0.7, 2.0))
        assertTrue(quads.isNotEmpty())
        val e = SyntheticScene.cornerErrors(scene.trueCornersPx, quads[0].corners)
        assertTrue("first candidate is not the outer border (max ${e.second} px)", e.second < 1.0)
    }

    @Test
    fun aFingerOverOneCornerDoesNotStopTheDetection() {
        val e = errors(SyntheticScene(finger = true))
        assertNotNull(e)
        assertTrue("max ${e!!.second}", e.second <= 1.5)
    }

    @Test
    fun scenesWithoutARectangleNeverGiveAMetricRectangle() {
        for (tilt in listOf(10.0, 35.0)) {
            val scene = SyntheticScene(noRectangle = true, tiltDeg = tilt, distance = 0.7)
            for (q in detector.detect(scene.render(0.7, 3.0))) {
                val ordered = QuadLock.orderCorners(q.corners)
                val c = ordered.map { FaceReprojection.lift(it.x, it.y, scene.camera, table) }
                if (c.any { it == null }) continue
                assertTrue("false rectangle at tilt $tilt", !QuadLock.squareness(c.map { it!! }).isRectangle)
            }
        }
    }

    @Test
    fun onATexturedTableTheMetricCheckPicksTheTrueRectangle() {
        // Wood grain can close larger image quads around the sheet; only the sheet is a rectangle in 3-D.
        for (yaw in listOf(0.0, 35.0)) {
            val scene = SyntheticScene(tiltDeg = 20.0, yawDeg = yaw)
            val pick = QuadLock.pickRectangle(detector.detect(scene.render(0.7, 2.0, 2)), scene.camera, table)
            assertNotNull("yaw $yaw", pick)
            assertTrue(SyntheticScene.cornerErrors(scene.trueCornersPx, pick!!.corners).second < 1.0)
        }
    }

    @Test
    fun aRectanglePartlyOutOfViewIsNotDetected() {
        val scene = SyntheticScene(widthM = 0.297, distance = 0.5, offsetX = 0.25)
        assertNull(match(scene, detector.detect(scene.render())))
    }

    @Test
    fun liftedWidthAndHeightAreWithinAThirdOfAPercent() {
        for (tilt in listOf(0.0, 30.0)) {
            val scene = SyntheticScene(tiltDeg = tilt, yawDeg = 20.0)
            val q = match(scene, detector.detect(scene.render()))!!
            val ordered = QuadLock.orderCorners(q.corners)
            val flat = DoubleArray(8) { i -> if (i % 2 == 0) ordered[i / 2].x else ordered[i / 2].y }
            val face = FaceReprojection.measure(flat, scene.camera, table, 0.3, 0.0, DeviceTier.DEPTH)!!
            val sides = listOf(face.width.value, face.height.value).sorted()
            assertEquals("tilt $tilt H", 0.21, sides[0], 0.21 * 0.003)
            assertEquals("tilt $tilt W", 0.297, sides[1], 0.297 * 0.003)
        }
    }

    @Test
    fun theCornerRefinerPullsRoughCornersToSubPixel() {
        val scene = SyntheticScene(tiltDeg = 25.0, yawDeg = 15.0)
        val img = scene.render()
        val r = Random(3)
        val rough = scene.trueCornersPx.map { Vec2(it.x + 2 * (r.nextDouble() - 0.5) * 2, it.y + 2 * (r.nextDouble() - 0.5) * 2) }
        val refined = CornerRefiner.refineQuad(img, rough)!!
        val e = SyntheticScene.cornerErrors(scene.trueCornersPx, refined.map { it.point })
        assertTrue("RMS ${e.first}", e.first <= 0.5)
        assertTrue(refined.all { it.sigmaPx > 0 && it.sigmaPx < 1.0 })
        // One corner on its own, from its rough neighbours (W8's dragged corner).
        val one = CornerRefiner.refineCorner(img, rough[0], rough[3], rough[1])!!
        assertTrue(one.point.distanceTo(scene.trueCornersPx[0]) <= 0.6)
        // No edge there: null, never a made-up corner.
        val blank = GrayImage(200, 200, FloatArray(200 * 200) { 100f })
        assertNull(CornerRefiner.refineCorner(blank, Vec2(100.0, 100.0), Vec2(100.0, 180.0), Vec2(180.0, 100.0)))
    }

    @Test
    fun quadGeometryHelpers() {
        val sq = listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(10.0, 10.0), Vec2(0.0, 10.0))
        assertEquals(100.0, QuadDetector.area(sq), 1e-9)
        assertTrue(QuadDetector.convexWithAngles(sq, 45.0))
        val bow = listOf(Vec2(0.0, 0.0), Vec2(10.0, 10.0), Vec2(10.0, 0.0), Vec2(0.0, 10.0))
        assertTrue(!QuadDetector.convexWithAngles(bow, 45.0))
        val order = QuadLock.orderCorners(listOf(Vec2(10.0, 10.0), Vec2(0.0, 0.0), Vec2(0.0, 10.0), Vec2(10.0, 0.0)))
        assertEquals(listOf(Vec2(0.0, 0.0), Vec2(10.0, 0.0), Vec2(10.0, 10.0), Vec2(0.0, 10.0)), order)
        val img = GrayImage(4, 2, floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 70f))
        assertEquals(25.0, img.sample(0.5, 0.5), 1e-6)
        assertEquals(25f, img.downsample2()[0, 0])
        val luma = GrayImage.fromLuma(byteArrayOf(0, -1, 5, 6), 2, 2)
        assertEquals(255f, luma[1, 0])
    }
}
