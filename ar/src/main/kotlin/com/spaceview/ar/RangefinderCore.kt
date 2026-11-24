package com.spaceview.ar

import com.spaceview.engine.Cov3
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.LocalisationInput
import com.spaceview.engine.Pose
import com.spaceview.engine.Ray
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import com.spaceview.engine.range.GroundRange
import com.spaceview.engine.range.RangeErrorModel
import com.spaceview.engine.range.RangeLimits
import com.spaceview.engine.range.RangeMethod
import com.spaceview.engine.range.RangeOutcome
import com.spaceview.engine.range.RangeReading
import com.spaceview.engine.range.RangeRejection
import com.spaceview.engine.range.RayTriangulation
import com.spaceview.engine.range.Sight
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where the Distance screen is in its flow (docs/PLAN-v3-2D.md W4). */
enum class RangePhase {
    /** Live reading from Near (AR hit) or Ground, whichever is available with the smaller ±. */
    LIVE,

    /** 2-sight: aim at the target and take sight 1. */
    AIM_SIGHT_1,

    /** 2-sight: holding steady while sight 1 is averaged. */
    TAKING_SIGHT_1,

    /** 2-sight: walk sideways (guided), keep the target in the crosshair, then take sight 2. */
    WALK,

    /** 2-sight: holding steady while sight 2 is averaged. */
    TAKING_SIGHT_2,

    /** 2-sight: the result (a number, out of range, or a refusal with its reason). */
    RESULT,
}

/** Why the live reading is empty (each maps to one hint). */
enum class RangeIssue {
    /** Moving too much to read the aim. */
    HOLD_STEADY,

    /** No surface under the crosshair and no floor known yet: point at the floor near you once. */
    NEED_FLOOR,

    /** The crosshair is at or above the horizon and nothing was hit: use 2-sight. */
    ABOVE_HORIZON,

    /** A point on the ground, but too far for Ground's ±: use 2-sight. */
    GROUND_TOO_FAR,

    /** Beyond 500 m. */
    OUT_OF_RANGE,
}

/** Why a sight could not be taken. */
enum class SightFailure { NOT_TRACKING, NOT_STEADY }

/** One-off events of the Distance screen. */
sealed class RangeEvent {
    data class SightTaken(val index: Int) : RangeEvent()
    data class SightFailed(val reason: SightFailure) : RangeEvent()

    /** A 2-sight outcome is ready (announce it). */
    data class Result(val outcome: RangeOutcome) : RangeEvent()

    /** The live reading was held (announce it). */
    data class Held(val reading: RangeReading) : RangeEvent()
}

/**
 * UI snapshot of the Distance screen (value-equal snapshots are not re-emitted; lengths are rounded for that).
 *
 * @property live the live reading (phase [RangePhase.LIVE]); null with [liveIssue] saying why.
 * @property held a live reading the user froze with "Hold".
 * @property requiredBaselineM 2-sight: the sideways walk the app asks for (0.5 m steps), null before sight 1.
 * @property walkedBaselineM 2-sight: sideways distance walked from sight 1 so far (0.1 m steps).
 * @property preview 2-sight: what sight 2 would give right now (while walking and aiming).
 * @property result 2-sight outcome in [RangePhase.RESULT].
 * @property floorKnown the camera's height above the floor is known (Ground possible).
 */
data class RangeState(
    val sessionRunning: Boolean = false,
    val tracking: TrackingStatus = TrackingStatus.INITIALIZING,
    val capabilities: ArCapabilities? = null,
    val phase: RangePhase = RangePhase.LIVE,
    val live: RangeReading? = null,
    val liveIssue: RangeIssue? = null,
    val held: RangeReading? = null,
    val stability: Float = 0f,
    val floorKnown: Boolean = false,
    val requiredBaselineM: Double? = null,
    val walkedBaselineM: Double = 0.0,
    val preview: RangeReading? = null,
    val result: RangeOutcome? = null,
)

/** One distinct camera frame, copied out of ARCore by [RangefinderSession] (or built by a test). */
internal class RangeFrame(
    val timestampNs: Long,
    val tracking: CameraTracking,
    val reason: FailureReason,
    val cameraPose: Pose,
    val light: Float,
    val centreRay: Ray?,
    val hit: HitSample?,
    val floorY: Double?,
    /** 1σ crosshair aim error (rad per axis), from [AimModel.angularSigma]. */
    val aimSigmaRad: Double,
    /** Aim localisation of a Near point at a range (null when intrinsics are unknown). */
    val nearLocalisation: (Double) -> LocalisationInput? = { null },
)

/**
 * The Distance screen's logic, free of ARCore (JVM-tested). Main thread only; one [onFrame] per **distinct** camera
 * frame. Methods (docs/PLAN-v3-2D.md W4): Near = an AR hit within [RangeLimits.NEAR_MAX_M] averaged by the usual
 * [SampleStabilizer]; Ground = camera height above the floor and the averaged depression angle ([GroundRange]); 2-sight
 * = two averaged sights intersected by [RayTriangulation]. Every number carries its ±; beyond 500 m only "out of range".
 *
 * @param clockMs wall clock (ms) for the sight time-out.
 */
internal class RangefinderCore(private val clockMs: () -> Long) {
    /** Device tier: the Near uncertainty prior and the VIO scale error of Ground / 2-sight. */
    var tier: DeviceTier = DeviceTier.NO_DEPTH
        set(value) {
            field = value
            model = UncertaintyModel(value)
            errors = RangeErrorModel(scaleSigmaRel = value.scaleSigmaRel)
        }
    private var model = UncertaintyModel(tier)
    private var errors = RangeErrorModel(scaleSigmaRel = tier.scaleSigmaRel)

    private val gate = TrackingGate()
    private val near = SampleStabilizer()
    private val aim = DirectionWindow(GROUND_WINDOW)
    private val sightWindow = DirectionWindow(SIGHT_SAMPLES)

    var phase = RangePhase.LIVE
        private set
    var live: RangeReading? = null
        private set
    var liveIssue: RangeIssue? = null
        private set
    var held: RangeReading? = null
        private set
    var status = TrackingStatus.INITIALIZING
        private set
    var stability = 0f
        private set
    var floorKnown = false
        private set
    var requiredBaselineM: Double? = null
        private set
    var walkedBaselineM = 0.0
        private set
    var preview: RangeReading? = null
        private set
    var result: RangeOutcome? = null
        private set

    private var sight1: Sight? = null
    private var lostSinceSight1 = false
    private var pathLength = 0.0
    private var lastPosition: Vec3? = null
    private var collectStartMs = 0L
    private var lastAimSigma = AimModel.DEFAULT_AIM_SIGMA_RAD

    private val pending = ArrayList<RangeEvent>(2)

    /** Events since the last call (main thread). */
    fun drainEvents(): List<RangeEvent> = if (pending.isEmpty()) emptyList() else ArrayList(pending).also { pending.clear() }

    fun onFrame(f: RangeFrame) {
        val g = gate.update(f.timestampNs, f.tracking, f.reason, f.cameraPose, f.light)
        status = g.status
        lastAimSigma = f.aimSigmaRad
        val q = g.sampleQuality
        val pos = f.cameraPose.translation
        val ray = f.centreRay
        floorKnown = f.floorY != null && pos.y - f.floorY >= RangeLimits.MIN_CAMERA_HEIGHT_M

        // ---- 2-sight bookkeeping: any loss of tracking after sight 1 breaks the shared world frame.
        if (sight1 != null && phase != RangePhase.RESULT) {
            if (q == TrackingQuality.NOT_TRACKING) {
                lostSinceSight1 = true
            } else {
                lastPosition?.let { pathLength += it.distanceTo(pos) }
                lastPosition = pos
            }
        }

        // ---- Near: an AR hit close enough to trust, averaged like a measuring point.
        val hit = f.hit?.takeIf { it.distance <= RangeLimits.NEAR_MAX_M && q != TrackingQuality.NOT_TRACKING }
        near.add(hit?.position, hit?.distance ?: 0.0, q, hit?.let { f.nearLocalisation(it.distance) })
        val nearReading = near.estimate?.let { est ->
            val camera = UncertainPoint(pos, Cov3.ZERO, includesLocalisation = true)
            val m = model.distance(camera, est.point)
            if (m.value > 0) RangeReading(m, RangeMethod.NEAR) else null
        }
        stability = if (hit == null) 0f else ((near.progress * 10).toInt() / 10f)

        // ---- the averaged aim direction (Ground, sights).
        if (ray != null && q == TrackingQuality.TRACKING) aim.add(ray.direction, ray.origin) else aim.clear()

        // ---- Ground.
        val ground: RangeOutcome? = if (f.floorY != null && aim.size >= MIN_GROUND_SAMPLES) {
            val o = aim.meanOrigin()
            GroundRange.solve(o.y - f.floorY, GroundRange.depression(aim.meanDirection()), aim.sigma(f.aimSigmaRad), errors)
        } else {
            null
        }
        val groundReading = (ground as? RangeOutcome.Ok)?.reading
        live = listOfNotNull(nearReading, groundReading).minByOrNull { it.distance.bound95 }
        liveIssue = when {
            live != null -> null
            q != TrackingQuality.TRACKING -> RangeIssue.HOLD_STEADY
            ground == RangeOutcome.OutOfRange -> RangeIssue.OUT_OF_RANGE
            ground == RangeOutcome.Rejected(RangeRejection.TOO_UNCERTAIN) -> RangeIssue.GROUND_TOO_FAR
            ground == RangeOutcome.Rejected(RangeRejection.ABOVE_HORIZON) -> RangeIssue.ABOVE_HORIZON
            !floorKnown -> RangeIssue.NEED_FLOOR
            ray != null && GroundRange.depression(ray.direction) <= Math.toRadians(RangeLimits.MIN_DEPRESSION_DEG) -> RangeIssue.ABOVE_HORIZON
            else -> RangeIssue.HOLD_STEADY
        }

        when (phase) {
            RangePhase.TAKING_SIGHT_1, RangePhase.TAKING_SIGHT_2 -> collect(f, q, ray)
            RangePhase.WALK -> walk(f, q, ray)
            else -> Unit
        }
    }

    // ---- commands -------------------------------------------------------------------------------

    /** Freezes the live reading (announced). False when there is none. */
    fun hold(): Boolean {
        val r = live ?: return false
        if (phase != RangePhase.LIVE) return false
        held = r
        pending += RangeEvent.Held(r)
        return true
    }

    fun release() {
        held = null
    }

    /** Starts the 2-sight flow. */
    fun startTwoSight() {
        clearTwoSight()
        held = null
        phase = RangePhase.AIM_SIGHT_1
    }

    /** "Take sight": averages the next steady frames into sight 1 or 2. */
    fun takeSight() {
        when (phase) {
            RangePhase.AIM_SIGHT_1 -> startCollect(RangePhase.TAKING_SIGHT_1)
            RangePhase.WALK -> startCollect(RangePhase.TAKING_SIGHT_2)
            else -> Unit
        }
    }

    /** From a 2-sight result: keep sight 1 and take sight 2 again (only while tracking was never lost). */
    fun retakeSight2(): Boolean {
        if (phase != RangePhase.RESULT || sight1 == null || lostSinceSight1) return false
        result = null
        phase = RangePhase.WALK
        return true
    }

    /** Back to the live reading (leaves 2-sight). */
    fun backToLive() {
        clearTwoSight()
        phase = RangePhase.LIVE
    }

    /** A new or paused ARCore session: nothing carries over (a new world frame). */
    fun reset() {
        gate.reset()
        near.reset()
        aim.clear()
        sightWindow.clear()
        held = null
        live = null
        preview = null
        when (phase) {
            RangePhase.WALK, RangePhase.TAKING_SIGHT_2 -> {
                // The world frame does not survive a pause or a new session: a pending 2-sight is lost.
                val r = RangeOutcome.Rejected(RangeRejection.TRACKING_LOST)
                result = r
                phase = RangePhase.RESULT
                pending += RangeEvent.Result(r)
            }
            RangePhase.TAKING_SIGHT_1 -> phase = RangePhase.AIM_SIGHT_1
            else -> Unit
        }
        // Sight 1 (if any) is in the old frame: never re-used for a retake.
        lostSinceSight1 = sight1 != null
    }

    // ---- internals --------------------------------------------------------------------------------

    private fun clearTwoSight() {
        sight1 = null
        lostSinceSight1 = false
        pathLength = 0.0
        lastPosition = null
        requiredBaselineM = null
        walkedBaselineM = 0.0
        preview = null
        result = null
        sightWindow.clear()
    }

    private fun startCollect(next: RangePhase) {
        sightWindow.clear()
        collectStartMs = clockMs()
        phase = next
    }

    private fun collect(f: RangeFrame, q: TrackingQuality, ray: Ray?) {
        val first = phase == RangePhase.TAKING_SIGHT_1
        if (q == TrackingQuality.NOT_TRACKING) {
            failSight(SightFailure.NOT_TRACKING, first)
            return
        }
        if (ray != null && q == TrackingQuality.TRACKING) {
            // A sight is one steady aim: if the crosshair wanders, start the average again.
            if (sightWindow.size > 0 && sightWindow.angleTo(ray.direction) > Math.toRadians(SIGHT_MAX_WANDER_DEG)) sightWindow.clear()
            sightWindow.add(ray.direction, ray.origin)
        }
        if (sightWindow.size >= SIGHT_SAMPLES) {
            val sight = Sight(sightWindow.meanOrigin(), sightWindow.meanDirection(), sightWindow.sigma(f.aimSigmaRad), f.timestampNs / 1e9)
            sightWindow.clear()
            if (first) {
                sight1 = sight
                lostSinceSight1 = false
                pathLength = 0.0
                lastPosition = f.cameraPose.translation
                // The walk the app asks for comes from the best distance estimate at hand (live reading or a guess).
                requiredBaselineM = roundHalf(RayTriangulation.requiredBaseline(live?.distance?.value ?: DEFAULT_GUESS_M, f.aimSigmaRad, errors))
                walkedBaselineM = 0.0
                phase = RangePhase.WALK
                pending += RangeEvent.SightTaken(1)
            } else {
                val s1 = sight1 ?: return backToLive()
                val t = RayTriangulation.solve(s1, sight, pathLength, lostSinceSight1, errors)
                result = t.outcome
                phase = RangePhase.RESULT
                pending += RangeEvent.SightTaken(2)
                pending += RangeEvent.Result(t.outcome)
            }
            return
        }
        if (clockMs() - collectStartMs > SIGHT_TIMEOUT_MS) failSight(SightFailure.NOT_STEADY, first)
    }

    private fun failSight(reason: SightFailure, first: Boolean) {
        sightWindow.clear()
        pending += RangeEvent.SightFailed(reason)
        phase = if (first) RangePhase.AIM_SIGHT_1 else RangePhase.WALK
    }

    private fun walk(f: RangeFrame, q: TrackingQuality, ray: Ray?) {
        val s1 = sight1 ?: return backToLive()
        if (lostSinceSight1) {
            val r = RangeOutcome.Rejected(RangeRejection.TRACKING_LOST)
            result = r
            phase = RangePhase.RESULT
            pending += RangeEvent.Result(r)
            return
        }
        walkedBaselineM = (RayTriangulation.perpendicularBaseline(s1, f.cameraPose.translation) * 10).roundToInt() / 10.0
        preview = if (ray != null && q == TrackingQuality.TRACKING) {
            val now = Sight(ray.origin, ray.direction, f.aimSigmaRad, f.timestampNs / 1e9)
            (RayTriangulation.solve(s1, now, pathLength, false, errors).outcome as? RangeOutcome.Ok)?.reading
        } else {
            null
        }
        preview?.let { requiredBaselineM = roundHalf(RayTriangulation.requiredBaseline(it.distance.value, f.aimSigmaRad, errors)) }
    }

    private fun roundHalf(v: Double) = (v * 2).roundToInt() / 2.0

    /**
     * Recent aim directions (unit vectors) and camera positions: their mean, and the σ of the mean direction (per
     * axis), inflated for the correlation between consecutive frames.
     */
    internal class DirectionWindow(private val capacity: Int) {
        private val dirs = ArrayDeque<Vec3>(capacity)
        private val origins = ArrayDeque<Vec3>(capacity)
        val size: Int get() = dirs.size

        fun add(direction: Vec3, origin: Vec3) {
            if (dirs.size == capacity) {
                dirs.removeFirst()
                origins.removeFirst()
            }
            dirs.addLast(direction.normalized())
            origins.addLast(origin)
            // A turn of the phone (new target) restarts the window.
            if (dirs.size > 1 && angleTo(direction) > Math.toRadians(AIM_MOVE_DEG)) {
                val d = dirs.last()
                val o = origins.last()
                clear()
                dirs.addLast(d)
                origins.addLast(o)
            }
        }

        fun clear() {
            dirs.clear()
            origins.clear()
        }

        fun meanDirection(): Vec3 = dirs.fold(Vec3.ZERO) { a, d -> a + d }.normalized()
        fun meanOrigin(): Vec3 = origins.fold(Vec3.ZERO) { a, o -> a + o } / origins.size.toDouble()

        /** Angle (rad) between [direction] and the mean of the window. */
        fun angleTo(direction: Vec3): Double {
            val m = meanDirection()
            val c = (m dot direction.normalized()).coerceIn(-1.0, 1.0)
            return kotlin.math.acos(c)
        }

        /**
         * 1σ angular error of the mean direction per axis: the aim model's [aimSigmaRad] (a bias that does not average
         * away) ⊕ the hold jitter's standard error (per-frame scatter / √(n / [CORRELATION_FRAMES])).
         */
        fun sigma(aimSigmaRad: Double): Double {
            val n = dirs.size
            if (n < 2) return aimSigmaRad * 2
            val m = meanDirection()
            var ss = 0.0
            for (d in dirs) ss += (d - m).lengthSquared
            val perAxis = sqrt(ss / (2.0 * (n - 1)))
            val se = perAxis / sqrt(max(1.0, n / CORRELATION_FRAMES))
            return sqrt(aimSigmaRad * aimSigmaRad + se * se)
        }
    }

    companion object {
        /** Frames averaged into one sight (~0.4 s at 30 fps). Tunable. */
        const val SIGHT_SAMPLES = 12

        /** A sight that cannot gather [SIGHT_SAMPLES] steady frames in this time fails ("hold steady"). */
        const val SIGHT_TIMEOUT_MS = 3000L

        /** While a sight is averaged, the crosshair may wander this far (degrees) before the average restarts. */
        const val SIGHT_MAX_WANDER_DEG = 0.5

        /** Frames in the live Ground aim average, and the minimum before Ground is shown. */
        const val GROUND_WINDOW = 15
        const val MIN_GROUND_SAMPLES = 8

        /** The live aim average restarts when the crosshair turns more than this (degrees). */
        const val AIM_MOVE_DEG = 1.0

        /** Consecutive frames are correlated (hand tremor is slow): the jitter's effective sample size is n / this. */
        const val CORRELATION_FRAMES = 3.0

        /** 2-sight guidance before any estimate exists (m): a medium-range guess, refined while walking. */
        const val DEFAULT_GUESS_M = 50.0
    }
}
