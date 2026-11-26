package com.spaceview.app.scan

import com.spaceview.app.scan.imaging.CornerSnap
import com.spaceview.app.scan.imaging.CutoutMaker
import com.spaceview.app.scan.imaging.CutoutStatus
import com.spaceview.app.scan.imaging.QuarterTurns
import com.spaceview.app.scan.imaging.RectifyMath
import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.engine.ArgbImage
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.CaptureCamera
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.FaceReprojection
import com.spaceview.engine.Measurement
import com.spaceview.engine.Pose
import com.spaceview.engine.Quality
import com.spaceview.engine.Vec2
import com.spaceview.engine.Vec3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN v3.1 W8: "Adjust corners" re-measures from geometry; the editor's pure helpers; the snapshot codec. */
class CornerAdjustTest {
    /** A 0.6 × 0.4 m frame on the wall z = 0, seen from 1.5 m by a 640 × 480 sensor camera with f = 500 px. */
    private val frame = listOf(Vec3(-0.3, 1.4, 0.0), Vec3(0.3, 1.4, 0.0), Vec3(0.3, 1.0, 0.0), Vec3(-0.3, 1.0, 0.0))
    private val camera = CaptureCamera(Pose(Vec3(0.0, 1.2, 1.5)), CameraIntrinsics(500.0, 500.0, 320.0, 240.0), 640, 480)

    private fun m(v: Double, b95: Double) = Measurement(v, b95 / 1.96)

    private val auto = ScanOutline(
        ScanShape.FLAT, m(0.6, 0.012), m(0.4, 0.011), null, m(0.72, 0.013), Quality.FAIR, false, true,
        FrontFace(frame, Vec3.UNIT_Z), FaceChoice(0, Vec3.UP, Vec3.UNIT_Z),
    )

    private fun px(points: List<Vec3> = frame): FloatArray = FloatArray(8).also { out ->
        points.forEachIndexed { k, p -> FaceReprojection.project(p, camera)!!.let { out[2 * k] = it[0].toFloat(); out[2 * k + 1] = it[1].toFloat() } }
    }

    @Test fun autoCornersReprojectToTheMeasuredSize() {
        val o = CornerAdjust.outline(auto, px(), camera, DeviceTier.NO_DEPTH)!!
        assertEquals(0.6, o.width.value, 1e-6)
        assertEquals(0.4, o.height.value, 1e-6)
        assertEquals(0.72111, o.diagonal!!.value, 1e-5)
        assertEquals("depth untouched (a frame's is preset later)", null, o.depth)
        for (k in 0 until 4) assertTrue(o.face.corners[k].approxEquals(frame[k], 1e-6))
        assertEquals(auto.choice, o.choice)
        assertTrue("± is honest: above the device floor", o.width.bound95 > DeviceTier.NO_DEPTH.absFloor95)
    }

    @Test fun twoPixelDragChangesTheWidthByTheGeometry() {
        val c = px()
        val dragged = c.copyOf().also { it[2] += 2f; it[4] += 2f } // the right side, 2 px right
        val w0 = CornerAdjust.outline(auto, c, camera, DeviceTier.NO_DEPTH)!!.width.value
        val w1 = CornerAdjust.outline(auto, dragged, camera, DeviceTier.NO_DEPTH)!!.width.value
        assertEquals("2 px at 1.5 m with f = 500 px is 6 mm", 0.006, w1 - w0, 1e-5)
        // The draft carries the re-measured width.
        val o = CornerAdjust.outline(auto, dragged, camera, DeviceTier.NO_DEPTH)!!
        val values = ScanDraftBuilder.values(com.spaceview.data.gallery.ObjectKind.FRAME, o)
        assertEquals(0.606, values.width.meters, 1e-3)
        assertFalse(values.width.isEstimate)
    }

    @Test fun crossedOrOffPlaneCornersAreRefused() {
        val c = px()
        val crossed = c.copyOf().also { it[0] = c[2]; it[1] = c[3]; it[2] = c[0]; it[3] = c[1] } // TL ↔ TR
        assertNull(CornerAdjust.outline(auto, crossed, camera, DeviceTier.NO_DEPTH))
        assertFalse(CornerAdjust.isConvexInOrder(FloatArray(8)))
        assertTrue(CornerAdjust.isConvexInOrder(c))
        // A camera turned away from the wall: every ray misses the face.
        val away = CaptureCamera(Pose(Vec3(0.0, 1.2, 1.5), com.spaceview.engine.Quaternion.fromAxisAngle(Vec3.UP, Math.PI)), camera.intrinsics, 640, 480)
        assertNull(CornerAdjust.outline(auto, c, away, DeviceTier.NO_DEPTH))
    }

    /** The adjusted rectangle is what gets straightened (and cut out): the background the auto corners caught is gone. */
    @Test fun rectificationAndCutoutHonourTheAdjustedCorners() {
        // A 200 × 150 photo: grey background, a red object filling x 50..150, y 40..110.
        val w = 200; val h = 150
        val photo = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 50 until 150 && y in 40 until 110) 0xFFDD2222.toInt() else 0xFF808080.toInt() }
        val autoCorners = listOf(Vec2(40.0, 30.0), Vec2(160.0, 30.0), Vec2(160.0, 120.0), Vec2(40.0, 120.0)) // 10 px loose
        val adjusted = listOf(Vec2(50.0, 40.0), Vec2(150.0, 40.0), Vec2(150.0, 110.0), Vec2(50.0, 110.0))
        fun greyShare(c: List<Vec2>, aspect: Double): Double {
            val (out, _, _) = RectifyMath.rectify(photo, w, h, c, aspect, 256)!!
            return out.count { (it and 0xFFFFFF) == 0x808080 }.toDouble() / out.size
        }
        assertTrue("auto corners include background", greyShare(autoCorners, 120.0 / 90) > 0.15)
        assertTrue("adjusted corners are the object only", greyShare(adjusted, 100.0 / 70) < 0.02)
        // And the cutout is made on exactly that straightened picture (never re-cropped).
        val (out, ow, oh) = RectifyMath.rectify(photo, w, h, adjusted, 100.0 / 70, 256)!!
        val mask = FloatArray(out.size) { 0.97f }
        val choice = CutoutMaker.choose(ArgbImage(ow, oh, out), flat = false, mask = mask)
        assertEquals(CutoutStatus.REMOVED, choice.status)
        assertEquals(ow, choice.cutout!!.width); assertEquals(oh, choice.cutout!!.height)
    }

    @Test fun quarterTurnsRoundTripAndPickTheUprightView() {
        for (k in 0..3) {
            val t = QuarterTurns(k, 640, 480)
            val (x, y) = t.toDisplay(100f, 30f)
            val (u, v) = t.toSensor(x, y)
            assertEquals(100f, u, 1e-4f); assertEquals(30f, v, 1e-4f)
            assertEquals(if (k % 2 == 0) 640 else 480, t.displayWidth)
        }
        // A face whose top is on the sensor's left (portrait phone): one quarter turn clockwise brings it up.
        val portrait = floatArrayOf(100f, 400f, 100f, 100f, 300f, 100f, 300f, 400f) // TL, TR, BR, BL
        val t = QuarterTurns.upright(portrait, 640, 480)
        assertEquals(1, t.turns)
        val d = t.cornersToDisplay(portrait)
        assertTrue("top-left is above bottom-left once turned", d[1] < d[7])
        assertArrayEquals(portrait, t.cornersToSensor(d), 1e-4f)
        assertEquals(0, QuarterTurns.upright(px(), 640, 480).turns)
    }

    /** A bright rectangle on a dark background with exact area anti-aliasing; corner [r] px rounded. Pixel centres at integers. */
    private fun rectImage(w: Int, h: Int, x0: Double, y0: Double, x1: Double, y1: Double, r: Double = 0.0): FloatArray {
        val sub = 8
        return FloatArray(w * h) { i ->
            val px = i % w; val py = i / w
            var inside = 0
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val x = px - 0.5 + (sx + 0.5) / sub; val y = py - 0.5 + (sy + 0.5) / sub
                var ok = x >= x0 && x <= x1 && y >= y0 && y <= y1
                if (ok && r > 0) {
                    val cx = x.coerceIn(x0 + r, x1 - r); val cy = y.coerceIn(y0 + r, y1 - r)
                    ok = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
                }
                if (ok) inside++
            }
            (40 + 180.0 * inside / (sub * sub)).toFloat()
        }
    }

    /** The sub-pixel refiner (W9) does the snap: it lands within a fraction of a pixel, also on a rounded corner. */
    @Test fun snapLandsSubPixelOnStraightAndRoundedCorners() {
        val w = 140; val h = 120
        for (radius in listOf(0.0, 5.0)) {
            val snap = CornerSnap(w, h, rectImage(w, h, 20.3, 30.6, 120.0, 100.0, radius))
            // The finger left the top-left corner at (22, 29); its sides run to BL (prev) and TR (next).
            val s = snap.snap(22f, 29f, prev = 20.3f to 100f, next = 120f to 30.6f)!!
            assertTrue("radius $radius: sub-pixel", s.subPixel)
            assertEquals("radius $radius: x", 20.3, s.u.toDouble(), 0.25)
            assertEquals("radius $radius: y", 30.6, s.v.toDouble(), 0.25)
            assertTrue("σ never below the 0.5 px floor", s.sigmaPx >= CornerSnap.MIN_SIGMA_PX)
        }
    }

    /** No edge for the refiner: Shi–Tomasi takes over on a clear corner; nothing at all on a flat area or a lone edge. */
    @Test fun snapFallsBackWhenTheRefinerFindsNoEdge() {
        val w = 60; val h = 60
        val argb = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x >= 20 && y >= 20) 0xFFE0E0E0.toInt() else 0xFF303030.toInt() }
        val snap = CornerSnap(w, h, CornerSnap.luma(argb))
        // Neighbours pointing away from the corner's real sides (into flat areas): the refiner finds no edge.
        val s = snap.snap(23.2f, 17.8f, prev = 23.2f to 2f, next = 2f to 17.8f)
        if (s != null && !s.subPixel) {
            assertEquals(20f, s.u, 1.6f); assertEquals(20f, s.v, 1.6f)
            assertEquals(CornerSnap.FALLBACK_SIGMA_PX, s.sigmaPx, 0.0)
        }
        // The fallback on its own.
        val st = snap.shiTomasi(23.2f, 17.8f)!!
        assertFalse(st.subPixel)
        assertEquals(20f, st.u, 1.6f); assertEquals(20f, st.v, 1.6f)
        assertNull("flat area", snap.snap(45f, 45f, prev = 45f to 59f, next = 59f to 45f))
        assertNull("flat area (fallback)", snap.shiTomasi(45f, 45f))
        assertNull("a straight edge is not a corner (fallback)", snap.shiTomasi(20f, 45f))
    }

    /** A snapped corner's σ replaces the finger's error in the ± (never below 0.5 px); hand-placed corners keep AimModel's. */
    @Test fun snappedCornerSigmaFeedsTheErrorBudget() {
        val c = px()
        val hand = CornerAdjust.outline(auto, c, camera, DeviceTier.NO_DEPTH)!!.width.sigma
        val snapped = CornerAdjust.outline(auto, c, camera, DeviceTier.NO_DEPTH, FloatArray(4) { 0.5f })!!.width.sigma
        val tooGood = CornerAdjust.outline(auto, c, camera, DeviceTier.NO_DEPTH, FloatArray(4) { 0.01f })!!.width.sigma
        val noSnap = CornerAdjust.outline(auto, c, camera, DeviceTier.NO_DEPTH, FloatArray(4) { Float.NaN })!!.width.sigma
        assertTrue("a 0.5 px snap is tighter than a 1 px finger: $snapped < $hand", snapped < hand)
        assertEquals("never below the 0.5 px floor", snapped, tooGood, 1e-12)
        assertEquals("NaN = placed by hand", hand, noSnap, 1e-12)
    }

    /** Gate V3: a calibrated Auto outline keeps its factor when corners are adjusted (or honestly loses the flag). */
    @Test fun calibratedScanKeepsItsFactorWhenCornersMove() {
        val k = 0.97
        val calibrated = ScanOutline(
            ScanShape.FLAT, m(0.6 * k, 0.008), m(0.4 * k, 0.008), null, m(0.72 * k, 0.01), Quality.GOOD, true, true,
            FrontFace(frame, Vec3.UNIT_Z), FaceChoice(0, Vec3.UP, Vec3.UNIT_Z),
        )
        val c = px()
        val same = CornerAdjust.outline(calibrated, c, camera, DeviceTier.NO_DEPTH, autoCornersPx = c)!!
        assertEquals("unchanged corners: the Auto width within 0.1 %", 0.6 * k, same.width.value, 0.6 * k * 0.001)
        assertEquals(0.4 * k, same.height.value, 0.4 * k * 0.001)
        assertTrue(same.calibrated)
        val nudged = CornerAdjust.outline(calibrated, c.copyOf().also { it[2] += 2f; it[4] += 2f }, camera, DeviceTier.NO_DEPTH, autoCornersPx = c)!!
        assertEquals("only the nudge moves W (6 mm raw, calibrated)", (0.6 + 0.006) * k, nudged.width.value, 1e-5)
        assertTrue(nudged.calibrated)
        // Without the automatic corners the factor can't be recovered: not calibrated, raw geometry.
        val unknown = CornerAdjust.outline(calibrated, c, camera, DeviceTier.NO_DEPTH)!!
        assertFalse(unknown.calibrated)
        assertEquals(0.6, unknown.width.value, 1e-6)
        // An uncalibrated scan stays uncalibrated and raw.
        val plain = CornerAdjust.outline(auto, c, camera, DeviceTier.NO_DEPTH, autoCornersPx = c)!!
        assertFalse(plain.calibrated)
        assertEquals(0.6, plain.width.value, 1e-6)
    }

    /** Gate v15: the TalkBack status reads centimetres with one decimal; a nudge moves one corner by the step. */
    @Test fun talkBackHelpers() {
        assertEquals("60.6", cmText(0.606))
        assertEquals("0.5", cmText(0.005))
        val c = floatArrayOf(10f, 10f, 90f, 10f, 90f, 60f, 10f, 60f)
        val up = moved(c, 0, 0f, -2f, 100f, 100f)
        assertEquals(8f, up[1], 0f)
        assertArrayEquals(c.copyOfRange(2, 8), up.copyOfRange(2, 8), 0f)
        assertEquals("kept inside the photo", 0f, moved(c, 0, -50f, 0f, 100f, 100f)[0], 0f)
    }

    @Test fun snapshotRoundTripsTheReview() {
        val o = ReviewSnapshot.decodeOutline(ReviewSnapshot.encodeOutline(auto))!!
        assertEquals(auto.width.value, o.width.value, 0.0); assertEquals(auto.height.sigma, o.height.sigma, 0.0)
        assertNull(o.depth); assertNotNull(o.diagonal)
        assertEquals(auto.quality, o.quality); assertEquals(auto.shape, o.shape)
        for (k in 0 until 4) assertTrue(o.face.corners[k].approxEquals(frame[k], 0.0))
        val cam = ReviewSnapshot.decodeCamera(ReviewSnapshot.encodeCamera(camera))!!
        assertArrayEquals(FaceReprojection.project(frame[0], camera), FaceReprojection.project(frame[0], cam), 1e-9)
        val edits = mapOf(ScanDim.DEPTH to 0.03)
        assertEquals(edits, ReviewSnapshot.decodeEdits(ReviewSnapshot.encodeEdits(edits)))
        assertNull(ReviewSnapshot.decodeOutline(doubleArrayOf(1.0, 2.0)))
        assertNull(ReviewSnapshot.decodeCamera(DoubleArray(13) { Double.NaN }))
        assertNull(ReviewSnapshot.validCorners(floatArrayOf(1f, Float.NaN, 0f, 0f, 0f, 0f, 0f, 0f)))
    }
}
