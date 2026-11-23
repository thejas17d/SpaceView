package com.spaceview.app.scan

import com.spaceview.ar.FaceChoice
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.data.gallery.CaptureMethod
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

/** SM1a: a scan whose outline used a hands-free drop is saved as HANDS_FREE (v3 `capture_method`, no schema change). */
class CaptureMethodTest {
    private fun values(): ScanValues {
        val face = FrontFace(listOf(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3.ZERO), Vec3.UNIT_Z)
        fun m(v: Double) = Measurement(v, 0.01 / Uncertainty.K95)
        val outline = ScanOutline(ScanShape.BOX, m(1.0), m(0.5), m(0.4), null, Quality.GOOD, false, true, face, FaceChoice(0, Vec3.UP, Vec3.UNIT_Z))
        return ScanDraftBuilder.values(ObjectKind.BOX, outline)
    }

    @Test fun handsFreeAndManualScansAreRecorded() {
        val v = values()
        assertEquals(CaptureMethod.MANUAL, ScanDraftBuilder.draft(ObjectKind.BOX, v, null, "Box", DeviceTier.NO_DEPTH, null).captureMethod)
        assertEquals(
            CaptureMethod.HANDS_FREE,
            ScanDraftBuilder.draft(ObjectKind.BOX, v, null, "Box", DeviceTier.NO_DEPTH, null, handsFree = true).captureMethod,
        )
    }
}
