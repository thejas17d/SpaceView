package com.spaceview.ar

import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Pose
import com.spaceview.engine.Ray
import com.spaceview.engine.Vec3
import com.spaceview.engine.range.RangeMethod
import com.spaceview.engine.range.RangeOutcome
import com.spaceview.engine.range.RangeRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** W4: the Distance screen's logic on synthetic frames (30 fps, a still or walking phone). */
class RangefinderCoreTest {
    private var nowMs = 0L
    private var ts = 0L
    private val core = RangefinderCore { nowMs }.also { it.tier = DeviceTier.DEPTH }
    private val intr = ImageIntrinsics(500.0, 640, 480)
    private val aim = AimModel.angularSigma(intr)

    private fun frame(
        camera: Vec3,
        target: Vec3?,
        hit: HitSample? = null,
        floorY: Double? = 0.0,
        tracking: CameraTracking = CameraTracking.TRACKING,
    ) {
        ts += FRAME_NS
        nowMs += 33
        val ray = target?.let { Ray(camera, it - camera) }
        core.onFrame(
            RangeFrame(
                ts, tracking, FailureReason.NONE, Pose(camera), 0.5f, ray, hit, floorY, aim,
                nearLocalisation = { r -> AimModel.aim(r, intr) },
            ),
        )
    }

    private fun hold(n: Int, camera: Vec3, target: Vec3?, hit: HitSample? = null, floorY: Double? = 0.0) =
        repeat(n) { frame(camera, target, hit, floorY) }

    private val eye = Vec3(0.0, 1.5, 0.0)

    @Test
    fun nearReadsAnArHitWithItsBound() {
        val p = Vec3(0.0, 1.0, -4.0)
        val hit = HitSample(p, Vec3(0.0, 0.0, 1.0), HitKind.DEPTH_POINT, p.distanceTo(eye))
        hold(60, eye, p, hit, floorY = null)
        val live = core.live!!
        assertEquals(RangeMethod.NEAR, live.method)
        assertEquals(p.distanceTo(eye), live.distance.value, 1e-6)
        assertTrue(live.distance.bound95 > 0.01)
        assertFalse(live.assumesLevelGround)
    }

    @Test
    fun groundReadsAPointOnTheFloorBeyondTheHitRange() {
        val target = groundPoint(9.0)
        hold(40, eye, target)
        val live = core.live!!
        assertEquals(RangeMethod.GROUND, live.method)
        assertEquals(9.0, live.distance.value, 1e-6)
        assertTrue(live.assumesLevelGround)
        assertTrue(core.floorKnown)
    }

    @Test
    fun groundTooFarOffersTwoSightInsteadOfANumber() {
        hold(40, eye, groundPoint(40.0))
        assertNull(core.live)
        assertEquals(RangeIssue.GROUND_TOO_FAR, core.liveIssue)
        hold(40, eye, Vec3(0.0, 30.0, -200.0)) // above the horizon, nothing hit
        assertEquals(RangeIssue.ABOVE_HORIZON, core.liveIssue)
        hold(40, eye, groundPoint(9.0).let { it }, floorY = null)
        assertEquals(RangeIssue.NEED_FLOOR, core.liveIssue)
    }

    @Test
    fun holdFreezesTheLiveReadingAndAnnouncesIt() {
        assertFalse(core.hold())
        hold(40, eye, groundPoint(8.0))
        assertTrue(core.hold())
        assertNotNull(core.held)
        assertTrue(core.drainEvents().any { it is RangeEvent.Held })
        core.release()
        assertNull(core.held)
    }

    @Test
    fun twoSightFlowGivesTheDistanceFromTheSecondSightWithinItsBound() {
        val target = Vec3(0.0, 4.0, -100.0)
        hold(30, eye, target) // settle tracking
        core.startTwoSight()
        assertEquals(RangePhase.AIM_SIGHT_1, core.phase)
        core.takeSight()
        assertEquals(RangePhase.TAKING_SIGHT_1, core.phase)
        hold(RangefinderCore.SIGHT_SAMPLES, eye, target)
        assertEquals(RangePhase.WALK, core.phase)
        assertTrue(core.drainEvents().contains(RangeEvent.SightTaken(1)))
        val asked = core.requiredBaselineM!!
        assertTrue(asked >= 1.0 && asked <= 30.0)
        // Walk 15 m sideways at ~0.6 m/s, keeping the target roughly in view.
        var pos = eye
        while (pos.x < 15.0) {
            pos += Vec3(0.02, 0.0, 0.0)
            frame(pos, target)
        }
        assertEquals(15.0, core.walkedBaselineM, 0.11)
        hold(30, pos, target) // stop and steady
        assertNotNull("live preview while aiming", core.preview)
        core.takeSight()
        hold(RangefinderCore.SIGHT_SAMPLES, pos, target)
        assertEquals(RangePhase.RESULT, core.phase)
        val reading = (core.result as RangeOutcome.Ok).reading
        assertEquals(RangeMethod.TWO_SIGHT, reading.method)
        val truth = target.distanceTo(pos)
        assertTrue(abs(reading.distance.value - truth) <= reading.distance.bound95)
        assertEquals(truth, reading.distance.value, 1e-3)
        assertTrue(core.drainEvents().any { it is RangeEvent.Result })
        // Retake keeps sight 1.
        assertTrue(core.retakeSight2())
        assertEquals(RangePhase.WALK, core.phase)
        core.backToLive()
        assertEquals(RangePhase.LIVE, core.phase)
    }

    @Test
    fun losingTrackingBetweenSightsRejectsTheResult() {
        val target = Vec3(0.0, 4.0, -80.0)
        hold(30, eye, target)
        core.startTwoSight()
        core.takeSight()
        hold(RangefinderCore.SIGHT_SAMPLES, eye, target)
        assertEquals(RangePhase.WALK, core.phase)
        frame(eye + Vec3(1.0, 0.0, 0.0), target, tracking = CameraTracking.PAUSED)
        frame(eye + Vec3(1.0, 0.0, 0.0), target)
        assertEquals(RangePhase.RESULT, core.phase)
        assertEquals(RangeOutcome.Rejected(RangeRejection.TRACKING_LOST), core.result)
        assertFalse(core.retakeSight2())
    }

    @Test
    fun aPausedSessionLosesAPendingTwoSight() {
        val target = Vec3(0.0, 4.0, -80.0)
        hold(30, eye, target)
        core.startTwoSight()
        core.takeSight()
        hold(RangefinderCore.SIGHT_SAMPLES, eye, target)
        core.reset()
        assertEquals(RangeOutcome.Rejected(RangeRejection.TRACKING_LOST), core.result)
        assertFalse(core.retakeSight2())
    }

    @Test
    fun aSightThatCannotSteadyTimesOut() {
        hold(30, eye, Vec3(0.0, 4.0, -80.0))
        core.startTwoSight()
        core.takeSight()
        // The phone swings: no steady frame, then the time-out.
        var t = Vec3(0.0, 4.0, -80.0)
        repeat(120) {
            t += Vec3(3.0, 0.0, 0.0)
            frame(eye, t)
        }
        assertEquals(RangePhase.AIM_SIGHT_1, core.phase)
        assertTrue(core.drainEvents().contains(RangeEvent.SightFailed(SightFailure.NOT_STEADY)))
    }

    @Test
    fun trackingLostWhileTakingASightFailsIt() {
        hold(30, eye, Vec3(0.0, 4.0, -80.0))
        core.startTwoSight()
        core.takeSight()
        frame(eye, Vec3(0.0, 4.0, -80.0), tracking = CameraTracking.PAUSED)
        assertEquals(RangePhase.AIM_SIGHT_1, core.phase)
        assertTrue(core.drainEvents().contains(RangeEvent.SightFailed(SightFailure.NOT_TRACKING)))
    }

    @Test
    fun directionWindowRestartsWhenTheAimTurnsAndInflatesForJitter() {
        val w = RangefinderCore.DirectionWindow(10)
        repeat(5) { w.add(Vec3(0.0, 0.0, -1.0), eye) }
        assertEquals(5, w.size)
        assertEquals(aim, w.sigma(aim), 1e-12) // no jitter: the aim model alone
        w.add(Vec3(0.1, 0.0, -1.0), eye) // ~5.7°: a new target
        assertEquals(1, w.size)
        assertEquals(aim * 2, w.sigma(aim), 1e-12)
        w.clear()
        w.add(Vec3(0.001, 0.0, -1.0), eye)
        w.add(Vec3(-0.001, 0.0, -1.0), eye)
        assertTrue(w.sigma(aim) > aim)
    }

    private fun groundPoint(lineOfSight: Double): Vec3 {
        val horizontal = sqrt(lineOfSight * lineOfSight - 1.5 * 1.5)
        return Vec3(0.0, 0.0, -horizontal)
    }

    private companion object {
        const val FRAME_NS = 33_333_333L
    }
}
