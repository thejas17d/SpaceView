package com.spaceview.ar

import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SM1a hands-free point drop: the dwell state machine (ARCHITECTURE-v2 §5.6, `:ar` row). */
class DwellTriggerTest {
    private val frameNs = 33_333_333L
    private val still = Pose(Vec3(0.0, 1.4, 0.0))
    private val spot = Vec3(0.0, 0.0, -1.5)
    private val far = Double.POSITIVE_INFINITY

    /** Feeds frames until the trigger fires or [maxNs] of camera time passed; returns the fire time or -1. */
    private fun DwellTrigger.run(
        from: Long,
        maxNs: Long,
        pose: (Long) -> Pose = { still },
        target: (Long) -> Vec3? = { spot },
        tracking: (Long) -> Boolean = { true },
        nearest: Double = far,
    ): Long {
        var t = from
        while (t <= from + maxNs) {
            if (update(t, tracking(t), pose(t), target(t), 1.5, nearest)) return t
            t += frameNs
        }
        return -1
    }

    @Test fun steadyAndLockedDropsOnceAfterTheHold() {
        val d = DwellTrigger()
        val fired = d.run(0, 3_000_000_000L)
        // The first frame has no motion history (not yet steady); the hold then runs HOLD_NS.
        assertTrue("fired at $fired", fired >= DwellTrigger.HOLD_NS)
        assertTrue("fired at $fired", fired <= DwellTrigger.HOLD_NS + 3 * frameNs)
        assertEquals(0f, d.progress)
        // Holding still on the same spot afterwards never drops again (re-arm needs a 3 cm move), even after the cooldown.
        assertEquals(-1L, d.run(fired + frameNs, 5_000_000_000L))
    }

    @Test fun progressRisesDuringTheHold() {
        val d = DwellTrigger()
        var t = 0L
        repeat(2) { d.update(t, true, still, spot, 1.5, far); t += frameNs }
        val early = d.progress
        repeat(10) { d.update(t, true, still, spot, 1.5, far); t += frameNs }
        assertTrue(d.progress > early)
        assertTrue(d.progress in 0.3f..0.7f)
    }

    @Test fun jitterOfTheTargetBeyondTheDriftLimitRestartsTheHold() {
        val d = DwellTrigger()
        // The target hops 2 cm every 300 ms (a shaky aim that the estimator still calls locked): never 700 ms in one place.
        val hopping: (Long) -> Vec3 = { t -> if ((t / 300_000_000L) % 2 == 0L) spot else spot + Vec3(0.02, 0.0, 0.0) }
        assertEquals(-1L, d.run(0, 4_000_000_000L, target = hopping))
        // Small jitter inside the limit (±3 mm) still drops.
        val e = DwellTrigger()
        val small: (Long) -> Vec3 = { t -> spot + Vec3(0.003 * kotlin.math.sin(t / 2e7), 0.0, 0.0) }
        assertTrue(e.run(0, 2_000_000_000L, target = small) > 0)
    }

    @Test fun aMovingPhoneNeverDrops() {
        val d = DwellTrigger()
        // Turning at ~0.5 rad/s (well above the 0.20 limit) while the target happens to stay put.
        val turning: (Long) -> Pose = { t -> Pose(still.translation, Quaternion.fromAxisAngle(Vec3.UP, 0.5 * t / 1e9)) }
        assertEquals(-1L, d.run(0, 3_000_000_000L, pose = turning))
        // Walking at 0.2 m/s.
        val walking: (Long) -> Pose = { t -> Pose(still.translation + Vec3(0.2 * t / 1e9, 0.0, 0.0)) }
        assertEquals(-1L, DwellTrigger().run(0, 3_000_000_000L, pose = walking))
    }

    @Test fun trackingLossCancelsTheHold() {
        val d = DwellTrigger()
        var t = 0L
        repeat(15) { assertFalse(d.update(t, true, still, spot, 1.5, far)); t += frameNs } // ~0.5 s held
        assertTrue(d.progress > 0f)
        assertFalse(d.update(t, false, still, spot, 1.5, far))
        assertEquals(0f, d.progress)
        t += frameNs
        // Tracking back: the hold starts from zero (and the motion history too), so no drop before a full hold.
        val fired = d.run(t, 3_000_000_000L)
        assertTrue(fired - t >= DwellTrigger.HOLD_NS)
        // No target (unlocked, frozen, complete or "+" pending) also cancels.
        val e = DwellTrigger()
        var u = 0L
        repeat(15) { e.update(u, true, still, spot, 1.5, far); u += frameNs }
        e.update(u, true, still, null, 1.5, far)
        assertEquals(0f, e.progress)
    }

    @Test fun neverOnTheSpotOfACommittedPoint() {
        val d = DwellTrigger()
        assertEquals(-1L, d.run(0, 3_000_000_000L, nearest = 0.02))
        assertTrue(DwellTrigger().run(0, 3_000_000_000L, nearest = 0.05) > 0)
    }

    @Test fun cooldownAfterADropThenReArmsAfterMovingAway() {
        val d = DwellTrigger()
        val first = d.run(0, 3_000_000_000L)
        assertTrue(first > 0)
        // Aim at a new spot 10 cm away right away: nothing before the 1 s cooldown ends, then a drop after a full hold.
        val next = spot + Vec3(0.10, 0.0, 0.0)
        val second = d.run(first + frameNs, 4_000_000_000L, target = { next })
        assertTrue("second at $second", second - first >= DwellTrigger.COOLDOWN_NS + DwellTrigger.HOLD_NS - frameNs)
    }

    @Test fun holdOffAfterResumeOrUndoAndTheSameSpotIsNotDroppedAgainAfterUndo() {
        val d = DwellTrigger()
        val fired = d.run(0, 3_000_000_000L)
        // Undo of that point: the user still aims at the same spot. It is not dropped again until they move.
        d.holdOff()
        assertEquals(-1L, d.run(fired + 2_000_000_000L, 3_000_000_000L))
        // Resume: no drop within the first second, even when everything else holds.
        val e = DwellTrigger()
        e.holdOff()
        val t0 = 10_000_000_000L
        val f = e.run(t0, 4_000_000_000L)
        assertTrue("fired ${f - t0} after resume", f - t0 >= DwellTrigger.COOLDOWN_NS + DwellTrigger.HOLD_NS - frameNs)
        // reset() (a new session) forgets the re-arm spot: the same coordinates are a new place in a new world frame.
        val g = DwellTrigger()
        val g1 = g.run(0, 3_000_000_000L)
        g.reset()
        assertTrue(g.run(g1 + 3_000_000_000L, 4_000_000_000L) > 0)
    }

    @Test fun repeatedCameraTimestampsNeitherAdvanceNorReset() {
        // SceneView calls back at 60/120 Hz with the 30 fps camera image repeated (M3 review B1).
        val d = DwellTrigger()
        var t = 0L
        var fired = -1L
        while (t < 3_000_000_000L && fired < 0) {
            repeat(4) { if (d.update(t, true, still, spot, 1.5, far)) fired = t }
            t += frameNs
        }
        assertTrue(fired >= DwellTrigger.HOLD_NS && fired <= DwellTrigger.HOLD_NS + 3 * frameNs)
        // A repeated frame with a bad target does not cancel a hold in progress either.
        val e = DwellTrigger()
        var u = 0L
        repeat(10) { e.update(u, true, still, spot, 1.5, far); u += frameNs }
        val p = e.progress
        e.update(u - frameNs, false, still, null, 1.5, far)
        assertEquals(p, e.progress)
    }

    @Test fun theCameraClockRestartingStartsOverWithAHoldOff() {
        val d = DwellTrigger()
        var t = 50_000_000_000L
        repeat(10) { d.update(t, true, still, spot, 1.5, far); t += frameNs }
        // New session: timestamps start again near zero.
        val f = d.run(0, 4_000_000_000L)
        assertTrue(f >= DwellTrigger.COOLDOWN_NS + DwellTrigger.HOLD_NS - frameNs)
    }
}
