package com.spaceview.ar

import com.spaceview.engine.Cov3
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.SupportPlane
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W9: a detected rectangle is measured from all 4 corners, with an honest ±; before/after error budget vs distance. */
class DetectedRectangleTest {
    private val model = UncertaintyModel(DeviceTier.DEPTH)

    /** A4 corners on the table (y = 0), top-left, top-right, bottom-right, bottom-left. */
    private val a4 = listOf(Vec3(-0.1485, 0.0, -0.105), Vec3(0.1485, 0.0, -0.105), Vec3(0.1485, 0.0, 0.105), Vec3(-0.1485, 0.0, 0.105))

    private fun detected(range: Double, sigmaPx: Double = 0.3, f: Double = 920.0) = a4.map {
        EndpointData(
            UncertainPoint(it, Cov3.isotropic(sigmaPx * range / f), includesLocalisation = true),
            Vec3.UP, HitKind.DETECTED_CORNER, range, planeBiasRel = SupportPlane.biasRel(range),
        )
    }

    /** Hands-free tapped corners before W9/W10: 2 VGA px aim (≈ 4.2 px at f = 920 on 1280 px) and no plane σ. */
    private fun tapped(range: Double) = a4.take(3).map {
        val loc = AimModel.aim(range, ImageIntrinsics(920.0, 1280, 720))!!
        EndpointData(UncertainPoint.of(it, 0.0005).withLocalisation(loc), Vec3.UP, HitKind.PLANE, range, focalPx = loc.focalLengthPx, pixelErrorPx = loc.pixelErrorPx)
    }

    @Test
    fun fourDetectedCornersMeasureTheSheet() {
        val r = MeasurementComposer.compose(MeasureMode.RECTANGLE, detected(0.5), null, model)!!
        assertEquals(0.297, r.primary.value, 1e-4)
        assertEquals(0.21, r.secondary!!.value, 1e-4)
        assertTrue(r.geometryConsistent)
        // The ± keeps the scale, plane and floor terms: never below the device floor.
        assertTrue(r.primary.bound95 >= DeviceTier.DEPTH.absFloor95)
    }

    @Test
    fun expectedErrorVsDistanceBeforeAndAfter() {
        println("W9/W10 A4 long side ±95 (mm): range | before (3 tapped corners) | after (4 detected corners)")
        for (range in listOf(0.3, 0.5, 1.0)) {
            val before = MeasurementComposer.compose(MeasureMode.RECTANGLE, tapped(range), null, model)!!.primary.bound95
            val after = MeasurementComposer.compose(MeasureMode.RECTANGLE, detected(range), null, model)!!.primary.bound95
            println("  $range m | ${"%.1f".format(before * 1000)} | ${"%.1f".format(after * 1000)}")
            assertTrue(after > 0)
        }
    }
}
