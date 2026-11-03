package com.spaceview.ar

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

class MeasurementComposerTest {
    private val model = UncertaintyModel()
    private val wallNormal = Vec3(0.0, 0.0, 1.0)

    private fun ep(x: Double, y: Double, z: Double, range: Double = 0.8, kind: HitKind = HitKind.PLANE, normal: Vec3? = wallNormal) =
        EndpointData(UncertainPoint.of(Vec3(x, y, z), 0.001), normal, kind, range)

    @Test
    fun distanceNeedsTwoPoints() {
        assertNull(MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(ep(0.0, 0.0, 0.0)), null, model))
        val r = MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(ep(0.0, 0.0, 0.0), ep(0.6, 0.0, 0.0)), null, model)!!
        assertEquals(0.6, r.primary.value, 1e-4)
        assertFalse(r.isLive)
        assertTrue(r.primary.sigma > 0)
    }

    @Test
    fun liveReadingUsesReticlePoint() {
        val r = MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(ep(0.0, 0.0, 0.0)), ep(0.0, 0.3, 0.0), model)!!
        assertTrue(r.isLive)
        assertEquals(0.3, r.primary.value, 1e-4)
    }

    @Test
    fun heightIsVerticalComponentOnly() {
        val r = MeasurementComposer.compose(MeasureMode.HEIGHT, listOf(ep(0.0, 0.0, 0.0), ep(0.5, 0.75, -0.2)), null, model)!!
        assertEquals(0.75, r.primary.value, 1e-4)
    }

    @Test
    fun rectangleFromThreeCorners() {
        val pts = listOf(ep(0.0, 0.4, -1.0), ep(0.6, 0.4, -1.0), ep(0.6, 0.0, -1.0))
        val r = MeasurementComposer.compose(MeasureMode.RECTANGLE, pts, null, model)!!
        assertEquals(0.6, r.primary.value, 1e-4)
        assertEquals(0.4, r.secondary!!.value, 1e-4)
        assertEquals(0.24, r.area!!.value, 1e-4)
        assertNotNull(r.rawSecondary)
        assertEquals(0.0, r.maxRightAngleDeviationDeg!!, 1e-6)
    }

    @Test
    fun calibrationScalesReadingAndIsFlagged() {
        val cal = model.withCalibration(ScaleCalibration(1.02, 0.002))
        val r = MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(ep(0.0, 0.0, 0.0), ep(1.0, 0.0, 0.0)), null, cal)!!
        assertEquals(1.02, r.primary.value, 1e-4)
        assertEquals(1.0, r.rawPrimary.value, 1e-4)
        assertTrue(r.calibrated)
    }

    @Test
    fun equalInputsGiveEqualReadings() {
        val a = MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(ep(0.0, 0.0, 0.0), ep(0.6, 0.0, 0.0)), null, model)
        val b = MeasurementComposer.compose(MeasureMode.DISTANCE, listOf(ep(0.0, 0.0, 0.0), ep(0.6, 0.0, 0.0)), null, model)
        assertEquals(a, b)
    }

    @Test
    fun tipPrefersMovingCloserWhenFar() {
        val pts = listOf(ep(0.0, 0.0, 0.0, range = 2.5), ep(0.05, 0.0, 0.0, range = 2.5))
        val far = UncertaintyModel(scaleSigmaRel = 0.03)
        val r = MeasurementComposer.compose(MeasureMode.DISTANCE, pts, null, far)!!
        if (r.quality != Quality.GOOD) assertEquals(ImprovementTip.MOVE_CLOSER, MeasurementComposer.tipFor(r, pts, false))
        val good = r.copy(quality = Quality.GOOD)
        assertNull(MeasurementComposer.tipFor(good, pts, false))
    }

    @Test
    fun degenerateWhenPointsCoincide() {
        assertTrue(MeasurementComposer.isDegenerate(listOf(ep(0.0, 0.0, 0.0), ep(0.001, 0.0, 0.0))))
        assertFalse(MeasurementComposer.isDegenerate(listOf(ep(0.0, 0.0, 0.0), ep(0.1, 0.0, 0.0))))
    }

    @Test
    fun outlineInfersFourthCorner() {
        val o = MeasurementComposer.rectangleOutline(listOf(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0)))
        assertTrue(o[3].approxEquals(Vec3(0.0, 0.0, 0.0)))
    }
}
