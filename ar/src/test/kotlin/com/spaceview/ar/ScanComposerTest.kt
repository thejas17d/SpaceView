package com.spaceview.ar

import com.spaceview.engine.Plane
import com.spaceview.engine.Quality
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanComposerTest {
    private val model = UncertaintyModel()
    private val up = Vec3.UP
    private val wall = Vec3(0.0, 0.0, 1.0)

    private fun ep(x: Double, y: Double, z: Double, normal: Vec3?, range: Double = 0.9) =
        EndpointData(UncertainPoint.of(Vec3(x, y, z), 0.001), normal, HitKind.PLANE, range)

    /** A 60 × 40 cm footprint, 85 cm tall: front-left, front-right, back-right on the floor, then the top. */
    private val box = listOf(ep(0.0, 0.0, 0.0, up), ep(0.6, 0.0, 0.0, up), ep(0.6, 0.0, -0.4, up), ep(0.3, 0.85, -0.2, up))

    private fun assertVec(expected: Vec3, actual: Vec3) = assertTrue("$expected != $actual", expected.approxEquals(actual, 1e-6))

    private fun forwardFrom(camera: Vec3, target: Vec3) = (target - camera).normalized()

    // ---- composer (BOX mode) ---------------------------------------------------------------------

    @Test
    fun boxGivesWidthDepthAndHeight() {
        val r = MeasurementComposer.compose(MeasureMode.BOX, box, null, model)!!
        assertEquals(MeasureMode.BOX, r.mode)
        assertEquals(0.6, r.primary.value, 1e-4)
        assertEquals(0.4, r.secondary!!.value, 1e-4)
        assertEquals(0.85, r.boxHeight!!.value, 1e-4)
        assertNull(r.area)
        assertNull(r.diagonal)
        assertFalse(r.isLive)
        assertTrue(r.boxHeight!!.sigma > 0)
        // The tier floor is in every dimension (M2 review B1).
        assertTrue(r.boxHeight!!.bound95 >= model.absFloor95 * (1 - 1e-9))
        assertEquals(Quality.worst(Quality.of(r.primary), Quality.of(r.secondary!!), Quality.of(r.boxHeight!!)), r.quality)
    }

    @Test
    fun boxBuildsUpPointByPoint() {
        val two = MeasurementComposer.compose(MeasureMode.BOX, box.take(1), box[1], model)!!
        assertTrue(two.isLive)
        assertNull(two.secondary)
        val three = MeasurementComposer.compose(MeasureMode.BOX, box.take(3), null, model)!!
        assertEquals(0.4, three.secondary!!.value, 1e-4)
        assertNull(three.boxHeight)
        val liveTop = MeasurementComposer.compose(MeasureMode.BOX, box.take(3), box[3], model)!!
        assertTrue(liveTop.isLive)
        assertEquals(0.85, liveTop.boxHeight!!.value, 1e-4)
    }

    /** A floor calibration may scale W × D, but the top is off that plane: the box is not "calibrated". */
    @Test
    fun floorCalibrationDoesNotCoverTheHeight() {
        val cal = ScaleCalibration(1.01, 0.002, plane = Plane.fromPointNormal(Vec3.ZERO, up), anchor = Vec3(0.3, 0.0, -0.2), rangeM = 0.9)
        val r = MeasurementComposer.compose(MeasureMode.BOX, box, null, model.withCalibration(cal))!!
        assertFalse(r.calibrated)
        assertEquals(0.85, r.boxHeight!!.value, 1e-4) // not scaled
    }

    /** M3 review m7: a top point on the floor (or below the base) is not a box height. */
    @Test
    fun boxTopMustBeAboveTheBase() {
        val base = box.take(3).map { it.point.position }
        assertFalse(MeasurementComposer.boxTopTooLow(base, Vec3(0.3, 0.85, -0.2)))
        assertTrue(MeasurementComposer.boxTopTooLow(base, Vec3(0.8, 0.012, 0.1)))
        assertTrue(MeasurementComposer.boxTopTooLow(base, Vec3(0.3, -0.3, -0.2)))
        assertFalse(MeasurementComposer.boxTopTooLow(base, Vec3(0.3, MeasurementComposer.MIN_BOX_HEIGHT_M + 0.001, -0.2)))
        // The foot is the tapped corner nearest the top (one reference for H, overlay and photo, m8).
        assertEquals(2, MeasurementComposer.boxFoot(base, Vec3(0.65, 0.9, -0.45)))
    }

    // ---- outline: box ----------------------------------------------------------------------------

    private fun boxReading() = MeasurementComposer.compose(MeasureMode.BOX, box, null, model)!!
    private val positions = box.map { it.point.position }

    private fun boxOutline(camera: Vec3): ScanOutline {
        val choice = ScanComposer.chooseFace(ScanShape.BOX, positions, camera, forwardFrom(camera, Vec3(0.3, 0.4, -0.2)))!!
        return ScanComposer.outline(ScanShape.BOX, boxReading(), positions, choice)!!
    }

    @Test
    fun boxFrontIsTheFaceTheUserStandsAt() {
        val o = boxOutline(Vec3(0.3, 1.4, 1.5))
        assertEquals(0.6, o.width.value, 1e-4)
        assertEquals(0.85, o.height.value, 1e-4)
        assertEquals(0.4, o.depth!!.value, 1e-4)
        assertNull(o.diagonal)
        assertVec(Vec3(0.0, 0.0, 1.0), o.face.normal)
        assertVec(Vec3(0.0, 0.85, 0.0), o.face.corners[0]) // top-left
        assertVec(Vec3(0.6, 0.85, 0.0), o.face.corners[1]) // top-right
        assertVec(Vec3(0.6, 0.0, 0.0), o.face.corners[2]) // bottom-right
        assertVec(Vec3(0.0, 0.0, 0.0), o.face.corners[3]) // bottom-left
    }

    @Test
    fun standingAtTheSideSwapsWidthAndDepth() {
        val camera = Vec3(2.0, 1.4, -0.2)
        assertEquals(1, ScanComposer.frontEdge(positions, camera))
        val o = boxOutline(camera)
        assertEquals(0.4, o.width.value, 1e-4)
        assertEquals(0.6, o.depth!!.value, 1e-4)
        assertVec(Vec3(1.0, 0.0, 0.0), o.face.normal)
        // Seen from +x, "right" is −z.
        assertVec(Vec3(0.6, 0.85, 0.0), o.face.corners[0])
        assertVec(Vec3(0.6, 0.85, -0.4), o.face.corners[1])
        assertVec(Vec3(0.6, 0.0, -0.4), o.face.corners[2])
    }

    /**
     * M3 review M1 probe: a 2.0 × 0.6 m wardrobe. Standing in front of the long face, even 10–20 cm inside
     * its right end, picks the long face (the old nearest-midpoint rule picked the 0.6 m side).
     */
    @Test
    fun wardrobeLongFaceWinsNearItsEnd() {
        val wardrobe = listOf(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0), Vec3(2.0, 0.0, -0.6), Vec3(1.0, 1.9, -0.3))
        for (camera in listOf(Vec3(1.0, 1.4, 1.0), Vec3(1.6, 1.4, 0.6), Vec3(1.8, 1.4, 0.4), Vec3(1.9, 1.4, 0.5))) {
            assertEquals("camera $camera", 0, ScanComposer.frontEdge(wardrobe, camera))
        }
        // Clearly beside the short end: the side face.
        assertEquals(1, ScanComposer.frontEdge(wardrobe, Vec3(3.0, 1.4, -0.3)))
    }

    /**
     * The face is decided once: the photo face from the stored choice stays the same however the user moves
     * before the capture, and W / D in the outline do not change.
     */
    @Test
    fun faceChoiceIsStableWhenTheCameraMoves() {
        val camera = Vec3(0.3, 1.4, 1.5)
        val o = boxOutline(camera)
        // The user walks to the side before the photo step arms.
        val later = ScanComposer.face(ScanShape.BOX, positions, o.choice)!!
        for (i in 0 until 4) assertVec(o.face.corners[i], later.corners[i])
        assertVec(o.face.normal, later.normal)
        // Recomputing the outline for a refreshed reading with the stored choice keeps W and D.
        val again = ScanComposer.outline(ScanShape.BOX, boxReading(), positions, o.choice)!!
        assertEquals(o.width, again.width)
        assertEquals(o.depth, again.depth)
    }

    // ---- outline: flat ---------------------------------------------------------------------------

    private val flatCamera = Vec3(0.3, 1.2, 0.5)
    private val flatForward = Vec3(0.0, 0.0, -1.0)

    private fun flatOutline(pts: List<EndpointData>): ScanOutline {
        val r = MeasurementComposer.compose(MeasureMode.RECTANGLE, pts, null, model)!!
        val positions = pts.map { it.point.position }
        val choice = ScanComposer.chooseFace(ScanShape.FLAT, positions, flatCamera, flatForward)!!
        return ScanComposer.outline(ScanShape.FLAT, r, positions, choice)!!
    }

    @Test
    fun flatFrameTappedClockwise() {
        val pts = listOf(ep(0.0, 1.4, -1.0, wall), ep(0.6, 1.4, -1.0, wall), ep(0.6, 1.0, -1.0, wall))
        val r = MeasurementComposer.compose(MeasureMode.RECTANGLE, pts, null, model)!!
        val o = flatOutline(pts)
        assertEquals(0.6, o.width.value, 1e-4)
        assertEquals(0.4, o.height.value, 1e-4)
        assertNull(o.depth)
        assertNotNull(o.diagonal)
        assertEquals(r.quality, o.quality)
        assertVec(wall, o.face.normal)
        assertVec(Vec3(0.0, 1.4, -1.0), o.face.corners[0])
        assertVec(Vec3(0.0, 1.0, -1.0), o.face.corners[3])
    }

    /** Tapped top-left, bottom-left, bottom-right: the reading's first edge is vertical, the object's width is still the horizontal one. */
    @Test
    fun flatFrameTappedDownFirstKeepsWidthHorizontal() {
        val pts = listOf(ep(0.0, 1.4, -1.0, wall), ep(0.0, 1.0, -1.0, wall), ep(0.6, 1.0, -1.0, wall))
        val r = MeasurementComposer.compose(MeasureMode.RECTANGLE, pts, null, model)!!
        assertEquals(0.4, r.primary.value, 1e-4)
        val o = flatOutline(pts)
        assertEquals(0.6, o.width.value, 1e-4)
        assertEquals(0.4, o.height.value, 1e-4)
        assertEquals(r.secondary, o.width)
        assertVec(Vec3(0.0, 1.4, -1.0), o.face.corners[0])
        assertVec(Vec3(0.6, 1.4, -1.0), o.face.corners[1])
        assertVec(Vec3(0.6, 1.0, -1.0), o.face.corners[2])
        assertVec(Vec3(0.0, 1.0, -1.0), o.face.corners[3])
    }

    /** A poster lying on a table: "up" in the photo is away from the user, fixed when the outline is confirmed. */
    @Test
    fun horizontalFlatFaceKeepsItsOrientationFromTheOtherSide() {
        val pts = listOf(Vec3(-0.2, 0.75, 0.0), Vec3(0.2, 0.75, 0.0), Vec3(0.2, 0.75, -0.3))
        val choice = ScanComposer.chooseFace(ScanShape.FLAT, pts, Vec3(0.0, 1.5, 0.6), Vec3(0.0, -0.6, -0.8))!!
        val face = ScanComposer.face(ScanShape.FLAT, pts, choice)!!
        assertVec(up, face.normal)
        assertVec(Vec3(-0.2, 0.75, -0.3), face.corners[0])
        assertVec(Vec3(0.2, 0.75, -0.3), face.corners[1])
        assertVec(Vec3(0.2, 0.75, 0.0), face.corners[2])
        // The user walks round the table before the photo: the stored choice still gives the same face.
        val again = ScanComposer.face(ScanShape.FLAT, pts, choice)!!
        for (i in 0 until 4) assertVec(face.corners[i], again.corners[i])
        // A fresh choice from the other side would flip it; the stored one never does.
        val other = ScanComposer.chooseFace(ScanShape.FLAT, pts, Vec3(0.0, 1.5, -0.9), Vec3(0.0, -0.6, 0.8))!!
        assertVec(Vec3(0.2, 0.75, 0.0), ScanComposer.face(ScanShape.FLAT, pts, other)!!.corners[0])
    }

    @Test
    fun liveOrWrongModeReadingsHaveNoOutline() {
        val pts = listOf(ep(0.0, 1.4, -1.0, wall), ep(0.6, 1.4, -1.0, wall))
        val live = MeasurementComposer.compose(MeasureMode.RECTANGLE, pts, ep(0.6, 1.0, -1.0, wall), model)!!
        val all = (pts.map { it.point.position }) + Vec3(0.6, 1.0, -1.0)
        val choice = ScanComposer.chooseFace(ScanShape.FLAT, all, flatCamera, flatForward)!!
        assertNull(ScanComposer.outline(ScanShape.FLAT, live, all, choice))
        val boxChoice = ScanComposer.chooseFace(ScanShape.BOX, positions, flatCamera, flatForward)!!
        assertNull(ScanComposer.outline(ScanShape.BOX, boxReading().copy(mode = MeasureMode.RECTANGLE), positions, boxChoice))
    }

    @Test
    fun canonicalOrderIgnoresInputOrder() {
        val tl = Vec3(0.0, 1.0, 0.0); val tr = Vec3(1.0, 1.0, 0.0); val br = Vec3(1.0, 0.0, 0.0); val bl = Vec3(0.0, 0.0, 0.0)
        for (input in listOf(listOf(br, bl, tl, tr), listOf(tl, bl, br, tr), listOf(tr, br, bl, tl))) {
            assertEquals(listOf(tl, tr, br, bl), ScanComposer.canonicalOrder(input, wall, up))
        }
        // Seen from behind (normal −z), left and right swap: never a mirrored photo.
        assertEquals(listOf(tr, tl, bl, br), ScanComposer.canonicalOrder(listOf(tl, tr, br, bl), -wall, up))
    }
}
