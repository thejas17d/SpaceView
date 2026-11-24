package com.spaceview.app.range

import com.spaceview.app.measure.surfaceDebugText
import com.spaceview.ar.HitKind
import com.spaceview.ar.RangeIssue
import com.spaceview.ar.SightFailure
import com.spaceview.ar.SurfaceDiagnostics
import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.range.RangeMethod
import com.spaceview.engine.range.RangeReading
import com.spaceview.engine.range.RangeRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeUiTest {
    @Test
    fun everyMethodIssueRejectionAndFailureHasAMessage() {
        RangeMethod.entries.forEach { assertTrue(RangeUi.methodRes(it) != 0) }
        RangeIssue.entries.forEach { assertTrue(RangeUi.issueRes(it) != 0) }
        RangeRejection.entries.forEach { assertTrue(RangeUi.rejectionRes(it) != 0) }
        SightFailure.entries.forEach { assertTrue(RangeUi.sightFailureRes(it) != 0) }
    }

    @Test
    fun readingTextFollowsTheBoundAndTheUnitSetting() {
        val r = RangeReading(Measurement(123.4, 12.2 / Uncertainty.K95), RangeMethod.TWO_SIGHT)
        assertEquals("120 m ± 13 m", RangeUi.text(r, imperial = false).text)
        assertTrue(RangeUi.text(r, imperial = true).text.endsWith("ft"))
    }

    @Test
    fun walkGuidanceIsRoundedToHalfMetresOrWholeFeet() {
        assertEquals("12.5 m", RangeUi.walkText(12.4, imperial = false))
        assertEquals("3 m", RangeUi.walkText(3.1, imperial = false))
        assertEquals("10 ft", RangeUi.walkText(3.05, imperial = true))
    }

    @Test
    fun debugLineShowsTimesSourceAndDepth() {
        val t = surfaceDebugText(SurfaceDiagnostics(800, 1460, HitKind.DEPTH_POINT, 2100, 31), HitKind.PLANE, true)
        assertEquals("dbg track 0.8s · hit 1.5s DEPTH_POINT · lock 2.1s · now PLANE · depth on · rep 31", t)
        assertEquals("dbg track - · hit -  · lock - · now - · depth ? · rep 0", surfaceDebugText(SurfaceDiagnostics(), null, null))
    }
}
