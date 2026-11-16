package com.spaceview.ar

import android.os.SystemClock
import androidx.annotation.MainThread
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.spaceview.engine.Pose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.ar.core.Pose as ArPose

/**
 * M4 placement as an [ArFrameListener] (S1, ADR-001): the ARCore adapter around [PlacementCore]. It runs entirely
 * on the main thread and never calls `frame.hitTest`: the reticle hit comes from [hits] (the [MeasureSession]
 * listed **before** it in the same host, §3.5).
 *
 * Rendering: [onModelPose] is called once per new frame with the model pose to draw (null = hide); the screen sets
 * it on the SceneView node directly, never through Compose state. [status] is published at most every
 * [MeasureSession.PUBLISH_INTERVAL_MS], or at once when the state's kind changes.
 *
 * Anchors: one anchor per drop, attached to the hit plane when the reticle was on a tracked plane, else a world
 * anchor. It is detached in [onSessionPaused] (the M2 M5 rule), on [pickUp], on a target change and on a failed
 * drop, and only while the session is live; [close] only drops references.
 */
class PlacementController(
    item: PlacedItem,
    initialTarget: SurfaceTarget,
    private val hits: MeasureSession,
) : ArFrameListener {
    private val core = PlacementCore(item, initialTarget)

    override val requirements = ArRequirements(depth = false, lights = true, planeRenderer = true)

    private val _status = MutableStateFlow(core.status)
    val status: StateFlow<PlacementStatus> = _status.asStateFlow()

    /** Main thread, once per new frame: the model pose to draw, or null to hide the model. */
    var onModelPose: ((Pose?) -> Unit)? = null

    private var session: Session? = null
    private var anchor: Anchor? = null
    private var live = false
    private var closed = false
    private var lastHit: ArHit? = null
    private var lastPublishMs = 0L

    @MainThread
    override fun onSessionCreated(session: Session) {
        if (closed) return
        anchor = null // released with the previous session
        this.session = session
        core.onSessionCreated()
        publish(force = true)
    }

    @MainThread
    override fun onSessionResumed(session: Session) {
        if (closed) return
        this.session = session
        live = true
    }

    @MainThread
    override fun onSessionPaused(session: Session) {
        if (closed) return
        detachAnchor()
        live = false
        core.onPaused()
        lastHit = null
        onModelPose?.invoke(null)
        publish(force = true)
    }

    @MainThread
    override fun onFrame(session: Session, frame: Frame) {
        if (closed) return
        this.session = session
        live = true
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val hit = hits.latestCentreHit
        lastHit = hit
        val a = anchor
        val anchorPose = if (a != null && a.trackingState == TrackingState.TRACKING) a.pose.toEngine() else null
        val changed = core.onFrame(
            timestampNs = frame.timestamp,
            tracking = tracking,
            camera = camera.displayOrientedPose.toEngine(),
            hitPos = hit?.position,
            hitNormal = hit?.normal,
            anchorPose = anchorPose,
        )
        if (!changed) return
        onModelPose?.invoke(core.modelPose)
        publish(force = false)
    }

    // ---- commands (main thread) ----------------------------------------------------------------------------

    /** Switches the surface type (a placed item is picked up). */
    @MainThread
    fun target(t: SurfaceTarget) {
        if (t == core.target) return
        detachAnchor()
        core.setTarget(t)
        afterCommand()
    }

    /** "Place here": anchors the item at the reticle. False when nothing valid is under it. */
    @MainThread
    fun dropAtReticle(): Boolean {
        val s = session
        if (closed || !live || s == null) return false
        val req = core.drop() ?: return false
        detachAnchor()
        val pose = req.surfacePose.toArPose()
        val plane = lastHit?.trackable as? com.google.ar.core.Plane
        val created = runCatching {
            if (plane != null && plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                plane.createAnchor(pose)
            } else {
                s.createAnchor(pose)
            }
        }.getOrNull()
        anchor = created
        core.onAnchored(created != null)
        afterCommand()
        return created != null
    }

    /** "Move": the item follows the reticle again. */
    @MainThread
    fun pickUp() {
        detachAnchor()
        core.pickUp()
        afterCommand()
    }

    /** Whole 1 cm steps, screen-mapped. */
    @MainThread
    fun nudge(rightSteps: Int, upSteps: Int) {
        core.nudge(rightSteps, upSteps)
        afterCommand()
    }

    @MainThread
    fun rotate90() {
        core.rotate90()
        afterCommand()
    }

    /** Yaw (floor) or roll (wall) only; never scale. */
    @MainThread
    fun twist(deltaRad: Double) {
        core.twist(deltaRad)
        afterCommand()
    }

    /** Wall items: centre at [heightM] above the floor (eye-level guide). */
    @MainThread
    fun setCentreHeight(heightM: Double): Boolean = core.setCentreHeight(heightM).also { afterCommand() }

    @MainThread
    fun startFallback() {
        detachAnchor()
        core.startFallback()
        afterCommand()
    }

    /** Adds the reticle point to the wall–floor line; false when the reticle is not on the floor. */
    @MainThread
    fun addFallbackPoint(): Boolean = core.addFallbackPoint().also { afterCommand() }

    @MainThread
    fun cancelFallback() {
        core.cancelFallback()
        afterCommand()
    }

    /** Releases references (never touches the anchor: SceneView may be closing the session). Idempotent. */
    @MainThread
    fun close() {
        closed = true
        live = false
        anchor = null
        session = null
        lastHit = null
        onModelPose = null
    }

    private fun afterCommand() {
        onModelPose?.invoke(core.modelPose)
        publish(force = true)
    }

    private fun detachAnchor() {
        val a = anchor ?: return
        anchor = null
        if (live && !closed) runCatching { a.detach() }
    }

    /**
     * Publishes [PlacementCore.status] at most every [MeasureSession.PUBLISH_INTERVAL_MS], or at once on a change of
     * state kind, target or tracking. The snapshot is built only when it is published (M4 review MINOR 13).
     */
    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishMs < MeasureSession.PUBLISH_INTERVAL_MS) {
            val prev = _status.value
            val kindChanged = !sameKind(core.state, prev.state) || core.target != prev.target || core.cameraTrackingNow != prev.cameraTracking
            if (!kindChanged) return
        }
        lastPublishMs = now
        _status.value = core.status
    }

    private fun sameKind(a: PlacementState, b: PlacementState): Boolean = when (a) {
        is PlacementState.Searching -> b is PlacementState.Searching
        is PlacementState.Preview -> b is PlacementState.Preview
        is PlacementState.Placed -> b is PlacementState.Placed && a.tracking == b.tracking
        is PlacementState.FallbackLine -> b is PlacementState.FallbackLine
    }

    private fun Pose.toArPose(): ArPose = ArPose(
        floatArrayOf(translation.x.toFloat(), translation.y.toFloat(), translation.z.toFloat()),
        floatArrayOf(rotation.x.toFloat(), rotation.y.toFloat(), rotation.z.toFloat(), rotation.w.toFloat()),
    )
}
