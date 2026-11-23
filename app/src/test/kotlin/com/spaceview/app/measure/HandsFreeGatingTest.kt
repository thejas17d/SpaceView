package com.spaceview.app.measure

import com.spaceview.app.smart.HandsFreeGate
import com.spaceview.engine.ReferenceObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM1 review MAJOR 1: no hands-free drop behind the calibration picker, and a reference choice starts from zero points. */
class HandsFreeGatingTest {
    @Test fun neverWhileThePickerCoversTheReticle() {
        assertTrue(handsFreeSuspended(CalibrationStep.Choosing, sessionFailed = false, frozen = false))
        val active = HandsFreeGate.active(enabled = true, suspended = handsFreeSuspended(CalibrationStep.Choosing, false, false), windowFocused = true)
        assertFalse(active)
        // Measuring (a reference chosen) or plain Measure: the reticle is back, so it may run.
        assertFalse(handsFreeSuspended(CalibrationStep.Measuring(null), sessionFailed = false, frozen = false))
        assertFalse(handsFreeSuspended(null, sessionFailed = false, frozen = false))
        // A freeze or a failed session also covers the reticle.
        assertTrue(handsFreeSuspended(null, sessionFailed = false, frozen = true))
        assertTrue(handsFreeSuspended(null, sessionFailed = true, frozen = false))
    }

    @Test fun aDialogOrTheSwitchStopsIt() {
        assertTrue(HandsFreeGate.active(enabled = true, suspended = false, windowFocused = true))
        assertFalse("a dialog (e.g. the consent form) has focus", HandsFreeGate.active(true, false, windowFocused = false))
        assertFalse("switched off", HandsFreeGate.active(false, false, true))
    }

    @Test fun choosingAReferenceClearsThePoints() {
        var cleared = 0
        assertEquals(CalibrationStep.Measuring(null), startCalibration(null) { cleared++ })
        assertEquals(CalibrationStep.Measuring(ReferenceObject.A4), startCalibration(ReferenceObject.A4) { cleared++ })
        assertEquals(2, cleared)
    }
}
