package com.spaceview.app.measure

import com.spaceview.app.history.HistoryViewModel
import com.spaceview.ar.EndpointData
import com.spaceview.ar.HitKind
import com.spaceview.ar.MeasurementComposer
import com.spaceview.data.history.MeasurementKind
import com.spaceview.data.history.SavedMeasurement
import com.spaceview.data.settings.CalibrationRecord
import com.spaceview.engine.LocalisationInput
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureLogicTest {

    @Test
    fun contextFollowsSizeAndKind() {
        assertEquals(MeasureContext.ROOM, MeasureFormatter.contextFor(MeasurementKind.DISTANCE, 2.0))
        assertEquals(MeasureContext.OBJECT, MeasureFormatter.contextFor(MeasurementKind.DISTANCE, 0.6))
        assertEquals(MeasureContext.SMALL_OBJECT, MeasureFormatter.contextFor(MeasurementKind.DISTANCE, 0.08))
        assertEquals(MeasureContext.ROOM, MeasureFormatter.contextFor(MeasurementKind.HEIGHT, 1.7))
        assertEquals(MeasureContext.OBJECT, MeasureFormatter.contextFor(MeasurementKind.RECTANGLE, 0.6))
    }

    @Test
    fun formattingNeverShowsMorePrecisionThanTolerance() {
        // ±95 ≈ 1.96 × 0.006 = 1.2 cm → whole centimetres.
        val f = MeasureFormatter.length(Measurement(0.61234, 0.006), MeasureContext.OBJECT, UnitPreference.AUTO)
        assertEquals("61 cm ± 1.2", f.primary.text)
    }

    @Test
    fun percentText() {
        assertEquals("+1.2%", MeasureFormatter.percent(0.012))
        assertEquals("−0.8%", MeasureFormatter.percent(-0.008))
        assertEquals("0.0%", MeasureFormatter.percent(0.0001))
    }

    private fun corner(x: Double, y: Double) = EndpointData(
        UncertainPoint(Vec3(x, y, -0.5)).withLocalisation(LocalisationInput(0.5, 1500.0, 1.0)),
        Vec3(0.0, 0.0, 1.0), HitKind.PLANE, 0.5, focalPx = 1500.0, pixelErrorPx = 1.0,
    )

    private fun a4Check(scale: Double, skewX: Double = 0.0) = MeasurementComposer.calibrationCheck(
        ReferenceObject.A4,
        listOf(corner(0.0, 0.210 / scale), corner(0.297 / scale, 0.210 / scale + skewX), corner(0.297 / scale, 0.0)),
        UncertaintyModel(),
    )

    @Test
    fun calibrationFromA4IsAcceptedWithFactor() {
        // Measured 1% short on both sides → factor ≈ 1.01.
        val out = MeasureViewModel.evaluateCalibration(ReferenceObject.A4, a4Check(1.01))
        assertTrue(out.toString(), out is CalibrationOutcome.Accepted)
        assertEquals(1.01, (out as CalibrationOutcome.Accepted).calibration.factor, 2e-3)
        assertEquals(0.5, out.calibration.rangeM!!, 1e-9)
    }

    @Test
    fun calibrationRejectsSkewedImplausibleAndMissing() {
        assertTrue(MeasureViewModel.evaluateCalibration(ReferenceObject.A4, a4Check(1.0, skewX = 0.05)) is CalibrationOutcome.Rejected)
        assertEquals(
            CalibrationOutcome.Rejected(CalibrationRejection.OUT_OF_RANGE),
            MeasureViewModel.evaluateCalibration(ReferenceObject.A4, a4Check(1.25)),
        )
        assertEquals(
            CalibrationOutcome.Rejected(CalibrationRejection.INVALID),
            MeasureViewModel.evaluateCalibration(ReferenceObject.A4, null),
        )
    }

    @Test
    fun earlierSessionCalibrationDropsPlaneScopeAndGetsLargerFloor() {
        val accepted = MeasureViewModel.evaluateCalibration(ReferenceObject.A4, a4Check(1.01)) as CalibrationOutcome.Accepted
        val rec = CalibrationRecord.from(accepted.calibration, ReferenceObject.A4, atMillis = 1_000, sessionId = 42)!!
        val (cNow, floorNow) = MeasureViewModel.calibrationForSession(rec, sessionId = 42)
        assertEquals(1.01, cNow!!.factor, 2e-3)
        assertTrue(cNow.plane != null)
        assertEquals(UncertaintyModel.DEFAULT_CALIBRATED_FLOOR_REL, floorNow, 0.0)
        val (cOld, floorOld) = MeasureViewModel.calibrationForSession(rec, sessionId = 99)
        assertNull(cOld!!.plane)
        assertEquals(0.5, cOld.rangeM!!, 1e-9)
        assertEquals(MeasureViewModel.PREVIOUS_SESSION_FLOOR_REL, floorOld, 0.0)
        assertNull(MeasureViewModel.calibrationForSession(null, 0).first)
    }

    @Test
    fun historyRowFormatsRectangleWithCurrentUnits() {
        val m = SavedMeasurement(
            id = 1, kind = MeasurementKind.RECTANGLE,
            primary = Measurement(0.6, 0.002), secondary = Measurement(0.4, 0.002), area = Measurement(0.24, 0.002),
            quality = Quality.GOOD, context = MeasureContext.OBJECT, calibrated = false,
            timestampMillis = 0, label = null, thumbnailPath = null,
        )
        val metric = HistoryViewModel.toRow(m, UnitPreference.METRIC)
        assertTrue(metric.valueText, metric.valueText.contains("cm") && metric.valueText.contains("×"))
        val imperial = HistoryViewModel.toRow(m, UnitPreference.IMPERIAL)
        assertTrue(imperial.valueText, imperial.valueText.contains("in"))
    }
}
