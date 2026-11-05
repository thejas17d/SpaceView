package com.spaceview.app.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2 review M6: an interstitial is only ever considered after a Measure visit with a save. */
class MeasureVisitTrackerTest {
    private var now = 1_000_000L
    private val tracker = MeasureVisitTracker(clock = { now }, pendingTtlMillis = 5_000L)

    @Test
    fun `leaving without saving never arms an interstitial`() {
        tracker.enterMeasure("visit-1")
        assertFalse(tracker.leaveMeasure("visit-1"))
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `leaving after a save arms exactly one chance`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        assertTrue(tracker.hasSavedThisVisit)
        assertTrue(tracker.leaveMeasure("visit-1"))
        assertTrue(tracker.consumePending())
        assertFalse("used at most once", tracker.consumePending())
    }

    @Test
    fun `scan saved then Place in my room never arms an interstitial`() {
        tracker.enterMeasure("scan-1")
        tracker.onScanSaved()
        tracker.leaveWithoutAd("scan-1")
        assertFalse(tracker.hasSavedThisVisit)
        assertFalse("the gallery item screen finds no chance", tracker.consumePending())
        // A later leave of the closed visit does not arm one either.
        assertFalse(tracker.leaveMeasure("scan-1"))
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `scan saved then Share then Done arms exactly one chance`() {
        tracker.enterMeasure("scan-1")
        tracker.onScanSaved()
        // Share opens the system sheet and comes back: nothing is consumed or armed.
        assertFalse(tracker.consumePending())
        assertTrue(tracker.leaveMeasure("scan-1"))
        assertTrue(tracker.consumePending())
    }

    @Test
    fun `leaving without an ad also drops an already armed chance`() {
        tracker.enterMeasure("scan-1")
        tracker.onScanSaved()
        assertTrue(tracker.leaveMeasure("scan-1"))
        tracker.leaveWithoutAd("scan-1")
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `a save in an earlier visit does not count for the next one`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        assertTrue(tracker.leaveMeasure("visit-1"))
        tracker.enterMeasure("visit-2")
        assertFalse("entering a new visit drops the unused chance", tracker.consumePending())
        assertFalse(tracker.leaveMeasure("visit-2"))
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `re-entering the same visit (activity recreated) keeps its saved flag`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        tracker.enterMeasure("visit-1")
        assertTrue(tracker.leaveMeasure("visit-1"))
    }

    @Test
    fun `saves outside a measure visit (calibration screen) are ignored`() {
        tracker.onScanSaved()
        tracker.enterMeasure("visit-1")
        assertFalse(tracker.leaveMeasure("visit-1"))
    }

    @Test
    fun `leaving a visit that is not the current one does nothing`() {
        tracker.enterMeasure("visit-2")
        tracker.onScanSaved()
        assertFalse(tracker.leaveMeasure("visit-1"))
        assertTrue(tracker.hasSavedThisVisit)
    }

    @Test
    fun `a second leave of the same visit cannot re-arm`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        assertTrue(tracker.leaveMeasure("visit-1"))
        assertTrue(tracker.consumePending())
        assertFalse(tracker.leaveMeasure("visit-1"))
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `pending chance expires so it never becomes an ad on app return`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        tracker.leaveMeasure("visit-1")
        now += 5_001L
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `pending chance survives a normal transition`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        tracker.leaveMeasure("visit-1")
        now += 700L
        assertTrue(tracker.consumePending())
    }

    @Test
    fun `clock moving backwards voids the pending chance`() {
        tracker.enterMeasure("visit-1")
        tracker.onScanSaved()
        tracker.leaveMeasure("visit-1")
        now -= 1L
        assertFalse(tracker.consumePending())
    }

    @Test
    fun `AR foreground is counted across overlapping AR screens`() {
        assertFalse(tracker.arInForeground)
        tracker.arScreenEntered()
        tracker.arScreenEntered()
        tracker.arScreenLeft()
        assertTrue(tracker.arInForeground)
        tracker.arScreenLeft()
        assertFalse(tracker.arInForeground)
        tracker.arScreenLeft()
        assertFalse("never negative", tracker.arInForeground)
        tracker.arScreenEntered()
        assertTrue(tracker.arInForeground)
    }
}
