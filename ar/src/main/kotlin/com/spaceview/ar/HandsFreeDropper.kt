package com.spaceview.ar

import androidx.annotation.MainThread
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.spaceview.engine.Pose
import com.spaceview.engine.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the hands-free dropper reads from the measuring session (main thread, after the session's own `onFrame`).
 * [MeasureSession] provides it; JVM tests fake it (SM1 review MINOR 16).
 */
interface AutoDropSource {
    val cameraTrackingOk: Boolean
    val lastFramePose: Pose?
    val committedCount: Int

    /** A freeze-frame adjustment is open (or requested). */
    val frozen: Boolean

    /** The locked point "+" would commit now, or null when the session would not take a point without a tap. */
    fun autoDropTarget(): EndpointData?

    fun distanceToNearestPoint(p: Vec3): Double

    /** "+" without a tap: the same checks and commit; the resulting `PointAdded` says `auto`. */
    fun addPointHandsFree()
}

/** The session-side rules of the hands-free drop and of "+" next to it, pure so they are table-tested. */
object AutoDropRules {
    /** A tap within this long of a hands-free drop on the same spot (or of completing a result) is ignored. */
    const val TAP_GUARD_MS = 1_000L

    /** May the session offer a point to the dwell now? Never while closed, paused, frozen, "+"-pending or complete. */
    fun allowed(
        closed: Boolean,
        live: Boolean,
        frozen: Boolean,
        commitPending: Boolean,
        points: Int,
        required: Int,
        pointsOk: Boolean,
    ): Boolean = !closed && live && !frozen && !commitPending && points < required && pointsOk

    /**
     * Should a tap on "+" be ignored (SM1 review MINOR 5)?
     * - Within [TAP_GUARD_MS] of a hands-free drop, aimed at the same spot ([toLastAutoM] < 3 cm, or unknown): the user
     *   confirms what the dwell just did, which is not a "same spot" error.
     * - Within [TAP_GUARD_MS] of completing a result: "+" would clear the result it just completed.
     */
    fun ignoreTap(sinceAutoMs: Long?, toLastAutoM: Double?, complete: Boolean, sinceCompleteMs: Long?): Boolean {
        if (complete && sinceCompleteMs != null && sinceCompleteMs < TAP_GUARD_MS) return true
        return sinceAutoMs != null && sinceAutoMs < TAP_GUARD_MS && (toLastAutoM == null || toLastAutoM < DwellTrigger.MIN_SPACING_M)
    }
}

/**
 * The state behind [AutoDropRules.ignoreTap], owned by the session (main thread). Anything that removes points (Undo,
 * Clear, a new or paused session) resets it, so a deliberate "+" after an Undo is never swallowed (SM1-M5 gate R1).
 */
class TapGuard {
    private var lastAutoAtMs: Long? = null
    private var lastAutoPosition: Vec3? = null
    private var completedAtMs: Long? = null

    /** A point was committed at [nowMs]; [auto] = dropped hands-free, [complete] = it completed the measurement. */
    fun onCommitted(nowMs: Long, position: Vec3, auto: Boolean, complete: Boolean) {
        if (complete) completedAtMs = nowMs
        if (auto) {
            lastAutoAtMs = nowMs
            lastAutoPosition = position
        }
    }

    /** Undo, Clear, or a session change removed points: nothing to protect any more. */
    fun onPointsRemoved() {
        lastAutoAtMs = null
        lastAutoPosition = null
        completedAtMs = null
    }

    /** Should a "+" at [nowMs], aimed at [target] (null = none), be ignored? */
    fun ignoreTap(nowMs: Long, target: Vec3?, complete: Boolean): Boolean {
        val toAuto = if (target != null) lastAutoPosition?.distanceTo(target) else null
        return AutoDropRules.ignoreTap(lastAutoAtMs?.let { nowMs - it }, toAuto, complete, completedAtMs?.let { nowMs - it })
    }
}

/**
 * Hands-free point drop (SM1a) for any screen that measures with a [MeasureSession] (Measure, Scan outline, furniture
 * guided measure): a separate [ArFrameListener] registered **after** the session (ADR-001: new AR features are new
 * listeners, never new sections of `MeasureSession`). Each frame it feeds the session's locked reticle point into a
 * [DwellTrigger] and, when that fires, calls the session's own commit path, so the point goes through exactly the
 * same checks, anchoring and ± as a tap on "+".
 *
 * Hold-offs (no drop for [DwellTrigger.COOLDOWN_NS]): a resume, a switch of [enabled], a point removed (Undo, Clear,
 * New), and the end of a freeze; after a freeze the reticle must also move 3 cm first (SM1 review MINOR 4).
 *
 * Main thread only; nothing allocated per frame.
 */
class HandsFreeDropper(
    private val source: AutoDropSource,
    private val trigger: DwellTrigger = DwellTrigger(),
) : ArFrameListener {

    constructor(session: MeasureSession) : this(session.autoDropSource)

    /** Neutral: no depth, no lights, and no opinion on the plane grid (the session decides). */
    override val requirements: ArRequirements = ArRequirements(depth = false, lights = false, planeRenderer = false)

    private val _progress = MutableStateFlow(0f)

    /**
     * Progress of the current hold in steps of 1/[PROGRESS_STEPS] (so Compose sees at most a few changes per hold;
     * the ring animates between them). 0 when not holding or disabled.
     */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var lastCount = 0
    private var wasFrozen = false

    /**
     * Set by the screen: the user's setting and HUD switch, **and** whether the reticle and controls are on screen at
     * all (a picker, a dialog, a review card or a lost window focus suspend it; SM1 review MAJOR 1). Every switch
     * restarts with a hold-off.
     */
    var enabled: Boolean = false
        @MainThread set(value) {
            if (field == value) return
            field = value
            trigger.holdOff()
            _progress.value = 0f
        }

    override fun onSessionCreated(session: Session) {
        trigger.reset()
        lastCount = 0
        wasFrozen = false
        _progress.value = 0f
    }

    /** Never within [DwellTrigger.COOLDOWN_NS] of a resume (tracking and anchors are still settling). */
    override fun onSessionResumed(session: Session) {
        trigger.holdOff()
    }

    override fun onSessionPaused(session: Session) {
        trigger.holdOff()
        _progress.value = 0f
    }

    override fun onFrame(session: Session, frame: Frame) {
        onFrame(frame.timestamp)
    }

    /** One `onSessionUpdated` call at camera time [timestampNs] (the testable body of [onFrame]). */
    @MainThread
    fun onFrame(timestampNs: Long) {
        val s = source
        val count = s.committedCount
        // A point was removed (Undo, Clear, "New", next furniture step): hold off, so the user gets time to aim.
        if (count < lastCount) trigger.holdOff()
        lastCount = count
        // A freeze just ended: the reticle still aims where the point was before it was corrected (MINOR 4).
        val frozen = s.frozen
        if (wasFrozen && !frozen) trigger.holdOff(rearmAtNextTarget = true)
        wasFrozen = frozen
        if (!enabled) return
        val target = s.autoDropTarget()
        val p = target?.point?.position
        val fire = trigger.update(
            timestampNs = timestampNs,
            trackingOk = s.cameraTrackingOk,
            cameraPose = s.lastFramePose,
            target = p,
            rangeM = target?.rangeM ?: 0.0,
            nearestCommittedM = if (p != null) s.distanceToNearestPoint(p) else Double.POSITIVE_INFINITY,
        )
        val step = (trigger.progress * PROGRESS_STEPS).toInt() / PROGRESS_STEPS.toFloat()
        if (_progress.value != step) _progress.value = step
        if (fire) s.addPointHandsFree()
    }

    companion object {
        /** Ring steps per hold (700 ms / 5 = one UI change per ~140 ms, within the ≤ 10 Hz UI budget). */
        const val PROGRESS_STEPS = 5
    }
}
