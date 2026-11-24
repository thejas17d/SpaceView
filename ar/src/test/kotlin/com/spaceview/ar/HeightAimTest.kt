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
        // Base σ along the plane normal × tan 60°.
        val expected = base().point.sigmaAlong(Vec3(0.0, 0.0, 1.0)) * tan(Math.toRadians(60.0))
        assertEquals(expected, c.extraSigmaM, 1e-9)
        val plain = AimModel.aim(c.range, intr)!!
        val loc = c.localisation(plain)!!
        val px = plain.pixelErrorPx * 2.0
        val extraPx = expected * plain.focalLengthPx / plain.rangeMeters
        assertEquals(sqrt(px * px + extraPx * extraPx), loc.pixelErrorPx, 1e-9)
    }

    @Test
    fun boxTopUsesTheTopFaceWhenItIsHitElseTheVerticalPlaneOverTheNearestCorner() {
        val corners = listOf(Vec3(-0.5, 0.0, -2.0), Vec3(0.5, 0.0, -2.0), Vec3(0.5, 0.0, -3.0)).map { base(it) }
        val f = forward(-10.0)
        val ray = Ray(camera, f)
        val topFace = ChosenPoint(Vec3(0.0, 0.8, -2.5), Vec3.UP, HitKind.PLANE, 2.6, 0.0)
        val onTop = HeightAim.boxTop(corners, topFace, ray, f) as HeightAim.Top.Point
        assertSame(topFace, onTop.chosen)
        // A hit on the wall behind the box (outside its footprint): the vertical plane instead.
        val wall = ChosenPoint(Vec3(0.0, 1.2, -4.0), Vec3(0.0, 0.0, 1.0), HitKind.PLANE, 4.0, 0.0)
        val tall = forward(40.0)
        val viaPlane = HeightAim.boxTop(corners, wall, Ray(camera, tall), tall) as HeightAim.Top.Point
        assertEquals(HitKind.VERTICAL_PLANE, viaPlane.chosen.kind)
        // The crosshair is above the middle of the front edge: the nearest corner's plane is 2 m ahead.
        assertEquals(-2.0, viaPlane.chosen.position.z, 1e-9)
        assertSame(HeightAim.Top.None, HeightAim.boxTop(corners.take(2), null, ray, f))
    }

    @Test
    fun cornerUnderRayIgnoresCornersBehindTheCamera() {
        val corners = listOf(Vec3(0.0, 0.0, 2.0), Vec3(0.3, 0.0, -3.0), Vec3(-2.0, 0.0, -3.0))
        assertEquals(1, HeightAim.cornerUnderRay(corners, Ray(camera, Vec3(0.0, 0.2, -1.0))))
        assertNull(HeightAim.cornerUnderRay(corners, Ray(camera, Vec3.UP)))
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
}
