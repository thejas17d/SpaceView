package com.spaceview.ar

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Ray
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** W5: height by a vertical measuring plane through the base point. */
class HeightAimTest {
    private val camera = Vec3(0.0, 1.5, 0.0)
    private val intr = ImageIntrinsics(500.0, 640, 480)
    private val model = UncertaintyModel(DeviceTier.DEPTH)

    private fun forward(pitchDeg: Double, yawDeg: Double = 0.0): Vec3 {
        val p = Math.toRadians(pitchDeg)
        val y = Math.toRadians(yawDeg)
        return Vec3(-sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    /** A committed base point on the floor 3 m ahead (an averaged plane hit with its aim term). */
    private fun base(p: Vec3 = Vec3(0.0, 0.0, -3.0)): EndpointData {
        val range = p.distanceTo(camera)
        val loc = AimModel.aim(range, intr)!!
        return EndpointData(UncertainPoint.of(p, 0.001).withLocalisation(loc), Vec3.UP, HitKind.PLANE, range, focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx)
    }

    /** The live top endpoint as the worker builds it (aim localisation stretched by the grazing angle). */
    private fun liveTop(chosen: ChosenPoint): EndpointData {
        val loc = chosen.localisation(AimModel.aim(chosen.range, intr))!!
        return EndpointData(UncertainPoint(chosen.position).withLocalisation(loc), chosen.normal, chosen.kind, chosen.range, focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx)
    }

    private fun aimAt(pitchDeg: Double, yawDeg: Double = 0.0): HeightAim.Top {
        val f = forward(pitchDeg, yawDeg)
        return HeightAim.top(base(), Ray(camera, f), f)
    }

    @Test
    fun tiltingThePhoneUp60DegreesKeepsThePoint() {
        // The door top is far above the crosshair's surface hits: only the vertical plane can follow the tilt.
        val top = aimAt(60.0) as HeightAim.Top.Point
        val c = top.chosen
        assertEquals(HitKind.VERTICAL_PLANE, c.kind)
        assertEquals(1.5 + 3.0 * tan(Math.toRadians(60.0)), c.position.y, 1e-9)
        assertEquals(-3.0, c.position.z, 1e-9)
        assertEquals(2.0, c.aimInflation, 1e-9) // 1 / cos 60°
        val reading = MeasurementComposer.compose(MeasureMode.HEIGHT, listOf(base()), liveTop(c), model)!!
        assertEquals(c.position.y, reading.primary.value, 1e-3)
    }

    @Test
    fun theBoundGrowsAsThePhoneTiltsAndBeyond75DegreesThePointIsRefused() {
        val b20 = MeasurementComposer.compose(MeasureMode.HEIGHT, listOf(base()), liveTop((aimAt(20.0) as HeightAim.Top.Point).chosen), model)!!
        val b60 = MeasurementComposer.compose(MeasureMode.HEIGHT, listOf(base()), liveTop((aimAt(60.0) as HeightAim.Top.Point).chosen), model)!!
        assertTrue(b60.primary.bound95 > b20.primary.bound95)
        assertSame(HeightAim.Top.TooSteep, aimAt(80.0))
        assertSame(HeightAim.Top.None, aimAt(10.0, 180.0)) // looking away from the base
    }

    @Test
    fun yawingAwayFromTheBaseStillGivesTheAimedHeight() {
        val top = aimAt(30.0, 15.0) as HeightAim.Top.Point
        val f = forward(30.0, 15.0)
        assertEquals(1.5 + top.chosen.range * f.y, top.chosen.position.y, 1e-9)
    }

    @Test
    fun theBaseDepthErrorLeaksIntoASteepTop() {
        val c = (aimAt(60.0) as HeightAim.Top.Point).chosen
        // Base σ along the plane normal ⊕ the base-not-under-the-top allowance, × tan 60° (review MINOR 4).
        val sb = base().point.sigmaAlong(Vec3(0.0, 0.0, 1.0))
        val expected = sqrt(sb * sb + HeightAim.BASE_OFFSET_SIGMA_M * HeightAim.BASE_OFFSET_SIGMA_M) * tan(Math.toRadians(60.0))
        assertEquals(expected, c.extraSigmaM, 1e-9)
        val plain = AimModel.aim(c.range, intr)!!
        val loc = c.localisation(plain)!!
        val px = plain.pixelErrorPx * 2.0
        val extraPx = expected * plain.focalLengthPx / plain.rangeMeters
        assertEquals(sqrt(px * px + extraPx * extraPx), loc.pixelErrorPx, 1e-9)
    }

    /** The box of review MAJOR 4: front edge z = −2 (x −0.5..0.5), back edge z = −3, 0.8 m tall. */
    private val box = listOf(Vec3(-0.5, 0.0, -2.0), Vec3(0.5, 0.0, -2.0), Vec3(0.5, 0.0, -3.0)).map { base(it) }
    private val boxH = 0.8

    private fun boxTopAimedAt(from: Vec3, target: Vec3, surface: ChosenPoint? = null): HeightAim.Top =
        HeightAim.boxTop(box, surface, Ray(from, target - from))

    @Test
    fun boxTopUsesTheTopFaceHitWhenThereIsOne() {
        val topFace = ChosenPoint(Vec3(0.0, boxH, -2.5), Vec3.UP, HitKind.PLANE, 2.6, 0.0)
        val onTop = boxTopAimedAt(camera, Vec3(0.0, boxH, -2.5), topFace) as HeightAim.Top.Point
        assertSame(topFace, onTop.chosen)
        assertSame(HeightAim.Top.None, HeightAim.boxTop(box.take(2), null, Ray(camera, Vec3(0.0, -0.2, -1.0))))
    }

    @Test
    fun aimingAtTheTopFrontEdgeOffCentreLandsOnTheFrontFaceNotABackCorner() {
        // 10 cm right of centre: the old nearest-line rule picked the back-right corner and read 0.45 m.
        val hitOnFrontFace = ChosenPoint(Vec3(0.1, boxH - 0.01, -2.0), Vec3(0.0, 0.0, 1.0), HitKind.PLANE, 2.3, 0.0)
        val top = boxTopAimedAt(camera, Vec3(0.1, boxH, -2.0), hitOnFrontFace) as HeightAim.Top.Point
        assertEquals(HitKind.VERTICAL_PLANE, top.chosen.kind)
        assertEquals(boxH, top.chosen.position.y, 1e-9)
        assertEquals(-2.0, top.chosen.position.z, 1e-9)
        assertTrue(top.chosen.normal!!.approxEquals(Vec3(0.0, 0.0, 1.0), 1e-12)) // faces the camera
    }

    @Test
    fun aDiagonalViewOfTheImplicitFourthCornerUsesItsSideFace() {
        // From behind-left the nearest corner is c0 + c2 − c1 = (−0.5, 0, −3), which is never tapped.
        val from = Vec3(-3.0, 1.5, -5.0)
        val top = boxTopAimedAt(from, Vec3(-0.5, boxH, -2.9)) as HeightAim.Top.Point
        assertEquals(boxH, top.chosen.position.y, 1e-9)
        assertEquals(-0.5, top.chosen.position.x, 1e-9)
        // The same view onto the back face, 20 cm from that corner.
        val back = boxTopAimedAt(from, Vec3(-0.3, boxH, -3.0)) as HeightAim.Top.Point
        assertEquals(boxH, back.chosen.position.y, 1e-9)
        assertEquals(-3.0, back.chosen.position.z, 1e-9)
    }

    @Test
    fun aTallWardrobeTopIsReadOnItsFrontFaceWhenTheTopCannotBeSeen() {
        val tall = 2.2
        val top = boxTopAimedAt(camera, Vec3(-0.2, tall, -2.0)) as HeightAim.Top.Point
        assertEquals(tall, top.chosen.position.y, 1e-9)
        assertTrue(top.chosen.aimInflation > 1.0)
    }

    @Test
    fun aRayMissingEveryFaceGivesNoPointAndAGrazingFaceIsTooSteep() {
        // Aimed 1 m to the left of the box: no side face within its width.
        assertSame(HeightAim.Top.None, boxTopAimedAt(camera, Vec3(-1.6, 0.5, -2.0)))
        // Looking almost straight down onto the front face from just in front of it.
        assertSame(HeightAim.Top.TooSteep, boxTopAimedAt(Vec3(0.0, 3.0, -1.9), Vec3(0.0, 0.0, -2.0)))
    }

    @Test
    fun farBaseOnTheFloorUsesGroundRangeOnlyWithinItsLimit() {
        val d = 6.0
        val dir = Vec3(0.0, -1.5 / d, -sqrt(1 - (1.5 / d) * (1.5 / d)))
        val g = HeightAim.groundBase(Ray(camera, dir), 0.0, AimModel.DEFAULT_AIM_SIGMA_RAD, DeviceTier.DEPTH.scaleSigmaRel)
        assertNotNull(g)
        assertEquals(HitKind.GROUND, g!!.kind)
        assertEquals(0.0, g.position.y, 1e-9)
        assertEquals(d, g.range, 1e-9)
        val cov = g.extraCovariance!!
        // The height of the base is known to the floor's precision, far better than its range.
        assertTrue(cov.along(Vec3.UP) < cov.along(dir))
        assertNull(g.localisation(AimModel.aim(d, intr)))
        val far = 40.0
        val farDir = Vec3(0.0, -1.5 / far, -sqrt(1 - (1.5 / far) * (1.5 / far)))
        assertNull(HeightAim.groundBase(Ray(camera, farDir), 0.0, AimModel.DEFAULT_AIM_SIGMA_RAD, DeviceTier.DEPTH.scaleSigmaRel))
        assertNull(HeightAim.groundBase(Ray(camera, dir), Double.NaN, AimModel.DEFAULT_AIM_SIGMA_RAD, DeviceTier.DEPTH.scaleSigmaRel))
    }

    @Test
    fun w5AimingAppliesToTheHeightTopAndTheBoxTopOnly() {
        assertTrue(MeasureSession.aimsW5(MeasureMode.HEIGHT, 1))
        assertTrue(MeasureSession.aimsW5(MeasureMode.BOX, 3))
        assertTrue(!MeasureSession.aimsW5(MeasureMode.HEIGHT, 0))
        assertTrue(!MeasureSession.aimsW5(MeasureMode.DISTANCE, 1))
    }

    @Test
    fun aHeightOnAFarFloorBaseSaysItAssumesALevelFloor() {
        val d = 6.0
        val dir = Vec3(0.0, -1.5 / d, -sqrt(1 - (1.5 / d) * (1.5 / d)))
        val g = HeightAim.groundBase(Ray(camera, dir), 0.0, AimModel.DEFAULT_AIM_SIGMA_RAD, DeviceTier.DEPTH.scaleSigmaRel)!!
        val baseEp = EndpointData(UncertainPoint(g.position, g.extraCovariance!!, includesLocalisation = true), Vec3.UP, HitKind.GROUND, g.range)
        val f = forward(10.0)
        val top = (HeightAim.top(baseEp, Ray(camera, f), f) as HeightAim.Top.Point).chosen
        val reading = MeasurementComposer.compose(MeasureMode.HEIGHT, listOf(baseEp), liveTop(top), model)!!
        assertTrue(reading.assumesLevelFloor)
        assertTrue(!MeasurementComposer.compose(MeasureMode.HEIGHT, listOf(base()), liveTop(top), model)!!.assumesLevelFloor)
    }
}
