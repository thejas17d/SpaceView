package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM1 review MINOR 16 / MAJOR 1 / MINOR 4: the dropper's session-facing rules, on a fake session. */
class HandsFreeDropperTest {
    private val frameNs = 33_333_333L

    private class FakeSession : AutoDropSource {
        var target: Vec3? = Vec3(0.0, 0.0, -1.5)
        val points = ArrayList<Vec3>()
        override var cameraTrackingOk = true
        override val lastFramePose: Pose = Pose(Vec3(0.0, 1.4, 0.0))
        override val committedCount: Int get() = points.size
        override var frozen = false
        var drops = 0

        override fun autoDropTarget(): EndpointData? =
            target?.takeIf { !frozen }?.let { EndpointData(UncertainPoint(it), null, HitKind.PLANE, 1.5) }

        override fun distanceToNearestPoint(p: Vec3): Double = points.minOfOrNull { it.distanceTo(p) } ?: Double.POSITIVE_INFINITY

        override fun addPointHandsFree() {
            drops++
            target?.let { points += it }
        }
    }

    /** Runs frames from [from] for [ns]; returns the time of the first new drop, or -1. */
    private fun HandsFreeDropper.run(s: FakeSession, from: Long, ns: Long): Long {
        val before = s.drops
        var t = from
        while (t <= from + ns) {
            onFrame(t)
            if (s.drops > before) return t
            t += frameNs
        }
        return -1
    }

    @Test fun dropsWhenEnabledAndNeverWhileSuspended() {
        val s = FakeSession()
        val d = HandsFreeDropper(s)
        // Not enabled (the screen suspends it while the calibration picker or a dialog covers the reticle): no drop.
        assertEquals(-1L, d.run(s, 0, 4_000_000_000L))
        d.enabled = true
        val t = d.run(s, 4_000_000_000L, 4_000_000_000L)
        // Enabling holds off 1 s first, then the hold.
        assertTrue("dropped at $t", t - 4_000_000_000L >= DwellTrigger.COOLDOWN_NS + DwellTrigger.HOLD_NS - frameNs)
        assertEquals(1, s.drops)
    }

    @Test fun aRemovedPointHoldsOffAndTheSameSpotIsNotDroppedAgain() {
        val s = FakeSession()
        val d = HandsFreeDropper(s).also { it.enabled = true }
        val t1 = d.run(s, 0, 4_000_000_000L)
        assertTrue(t1 > 0)
        // Undo: the count drops. Still aimed at the same spot: never dropped again there.
        s.points.clear()
        assertEquals(-1L, d.run(s, t1 + frameNs, 4_000_000_000L))
        // Aim 10 cm away right after another Undo-like removal: the hold-off delays the drop by 1 s.
        s.points += Vec3(5.0, 0.0, 0.0)
        val t2 = t1 + 5_000_000_000L
        d.onFrame(t2)
        s.points.clear()
        s.target = Vec3(0.10, 0.0, -1.5)
        val t3 = d.run(s, t2 + frameNs, 4_000_000_000L)
        assertTrue("dropped ${t3 - t2} after the removal", t3 - t2 >= DwellTrigger.COOLDOWN_NS + DwellTrigger.HOLD_NS - frameNs)
    }

    @Test fun theEndOfAFreezeHoldsOffAndNeedsTheReticleToMove() {
        val s = FakeSession()
        s.points += Vec3(-0.5, 0.0, -1.5) // a point placed with "+"
        val d = HandsFreeDropper(s).also { it.enabled = true }
        var t = 0L
        // Frozen for a while: no target, no drop.
        s.frozen = true
        repeat(30) { d.onFrame(t); t += frameNs }
        s.frozen = false
        // Still aimed at the spot the user corrected the point away from: nothing drops there.
        assertEquals(-1L, d.run(s, t, 4_000_000_000L))
        assertEquals(0, s.drops)
        // Aiming 5 cm away: drops after a full hold.
        s.target = Vec3(0.05, 0.0, -1.5)
        assertTrue(d.run(s, t + 4_100_000_000L, 4_000_000_000L) > 0)
    }

    @Test fun autoDropIsAllowedOnlyInAMeasuringState() {
        fun allowed(
            closed: Boolean = false, live: Boolean = true, frozen: Boolean = false, pending: Boolean = false,
            points: Int = 1, required: Int = 2, ok: Boolean = true,
        ) = AutoDropRules.allowed(closed, live, frozen, pending, points, required, ok)
        assertTrue(allowed())
        assertFalse("closed", allowed(closed = true))
        assertFalse("paused", allowed(live = false))
        assertFalse("frozen", allowed(frozen = true))
        assertFalse("+ pending", allowed(pending = true))
        assertFalse("complete", allowed(points = 2))
        assertFalse("points not tracked / unanchored", allowed(ok = false))
    }

    /** SM1-M5 gate R1: auto-drop → Undo → "+" within 1 s at the same spot is a real tap, not a swallowed one. */
    @Test fun aTapAfterUndoingAHandsFreeDropIsAccepted() {
        val g = TapGuard()
        val spot = Vec3(0.0, 0.0, -1.5)
        g.onCommitted(1_000, spot, auto = true, complete = false)
        assertTrue("right after the drop, same spot: a confirmation", g.ignoreTap(1_300, spot + Vec3(0.01, 0.0, 0.0), complete = false))
        g.onPointsRemoved() // Undo
        assertFalse(g.ignoreTap(1_500, spot + Vec3(0.01, 0.0, 0.0), complete = false))
        // Clear after completing a result: "+" starts a new measurement at once.
        g.onCommitted(2_000, spot, auto = false, complete = true)
        assertTrue(g.ignoreTap(2_200, null, complete = true))
        g.onPointsRemoved()
        assertFalse(g.ignoreTap(2_300, null, complete = false))
    }

    @Test fun aTapRightAfterAHandsFreeDropOrACompletionIsIgnored() {
        // Same spot within 1 s of an auto-drop: a confirmation, not a "same spot" error.
        assertTrue(AutoDropRules.ignoreTap(400, 0.01, complete = false, sinceCompleteMs = null))
        assertTrue(AutoDropRules.ignoreTap(400, null, complete = false, sinceCompleteMs = null))
        // A different spot, or later: a normal tap.
        assertFalse(AutoDropRules.ignoreTap(400, 0.05, complete = false, sinceCompleteMs = null))
        assertFalse(AutoDropRules.ignoreTap(1_500, 0.0, complete = false, sinceCompleteMs = null))
        assertFalse(AutoDropRules.ignoreTap(null, null, complete = false, sinceCompleteMs = null))
        // Within 1 s of completing a result, "+" never clears it; later it starts a new measurement.
        assertTrue(AutoDropRules.ignoreTap(null, null, complete = true, sinceCompleteMs = 300))
        assertFalse(AutoDropRules.ignoreTap(null, null, complete = true, sinceCompleteMs = 2_000))
    }
}
