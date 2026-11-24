package com.spaceview.ar

import android.os.SystemClock
import androidx.annotation.MainThread
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Session
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Vec3
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Distance screen's AR feature (docs/PLAN-v3-2D.md W4): a new [ArFrameListener] (ADR-001), the **primary** one of
 * its host, so it configures the session itself (planes on, depth when wanted and supported). No measuring tools, no
 * anchors: it only reads the crosshair ray, the hit under it and the floor, and hands them to [RangefinderCore].
 *
 * Main thread only. The per-frame work is a hit test, a few vector operations and (about once a second) the list of
 * ARCore planes for the floor; there is no worker. State is published at most every [PUBLISH_INTERVAL_MS], and at once
 * on every command or phase change.
 *
 * @param depthWanted the user's "Use depth" setting.
 */
class RangefinderSession(private val depthWanted: Boolean) : ArFrameListener {
    override val requirements: ArRequirements = ArRequirements(depth = depthWanted, lights = false, planeRenderer = true)

    private val core = RangefinderCore { SystemClock.elapsedRealtime() }
    private val floor = FloorEstimator()

    private val _state = MutableStateFlow(RangeState())
    /** Throttled UI state. */
    val state: StateFlow<RangeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RangeEvent>(extraBufferCapacity = 8)
    /** Sight taken / failed, results, holds (sounds, TalkBack announcements). */
    val events: SharedFlow<RangeEvent> = _events.asSharedFlow()

    private var capabilities: ArCapabilities? = null
    private var running = false
    private var closed = false
    private var viewW = 0
    private var viewH = 0
    private var lastTimestamp = Long.MIN_VALUE
    private var frameCounter = 0
    private var lastPublishMs = 0L
    private var intrinsics: ImageIntrinsics? = null
    private var viewPxPerImagePx = Double.POSITIVE_INFINITY
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    /** Size of the AR view in pixels (the crosshair is its centre). */
    @MainThread
    fun setViewSize(width: Int, height: Int) {
        viewW = width
        viewH = height
        updateViewScale()
    }

    override fun selectCameraConfig(session: Session): CameraConfig = SessionSetup.selectCameraConfig(session, depthWanted)

    override fun configureSession(session: Session, config: Config) {
        val caps = SessionSetup.detect(session, depthWanted)
        capabilities = caps
        core.tier = caps.tier
        SessionSetup.configure(config, caps.depthActive)
        publish(force = true)
    }

    override fun onSessionCreated(session: Session) {
        if (closed) return
        floor.reset()
        core.reset()
        intrinsics = null
        lastTimestamp = Long.MIN_VALUE
        drain()
        publish(force = true)
    }

    override fun onSessionResumed(session: Session) {
        if (closed) return
        running = true
        publish(force = true)
    }

    override fun onSessionPaused(session: Session) {
        if (closed) return
        running = false
        core.reset()
        drain()
        publish(force = true)
    }

    override fun onFrame(session: Session, frame: Frame) {
        if (closed || viewW <= 0 || viewH <= 0) return
        running = true
        // SceneView repeats the camera frame at the display rate: one sample per distinct camera frame (W6).
        if (frame.timestamp == lastTimestamp) return
        lastTimestamp = frame.timestamp
        val camera = frame.camera
        val tracking = FrameReaders.tracking(camera.trackingState)
        val pose = camera.pose.toEngine()
        var ray: com.spaceview.engine.Ray? = null
        var hit: HitSample? = null
        if (tracking == CameraTracking.TRACKING) {
            if (intrinsics == null) {
                intrinsics = FrameReaders.imageIntrinsics(frame)
                updateViewScale()
            }
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projMatrix, 0, NEAR_M, FAR_M)
            ray = ViewProjection(viewMatrix, projMatrix, viewW, viewH).centreRay()
            FrameReaders.centreHit(frame, viewW / 2f, viewH / 2f)?.let { h ->
                hit = HitSample(h.position, h.normal, h.kind, h.distance)
                val n = h.normal
                if (n != null && (n dot Vec3.UP) >= FLOOR_NORMAL_COS) floor.add(h.position.x, h.position.y, h.position.z, h.kind == HitKind.PLANE, frame.timestamp)
            }
            if (frameCounter++ % FLOOR_PLANE_EVERY == 0) FrameReaders.feedFloorPlanes(session, floor, frame.timestamp)
        }
        val light = frame.lightEstimate.let { if (it.state == LightEstimate.State.VALID) it.pixelIntensity else Float.NaN }
        val intr = intrinsics
        val scale = viewPxPerImagePx
        core.onFrame(
            RangeFrame(
                timestampNs = frame.timestamp,
                tracking = tracking,
                reason = FrameReaders.reason(camera.trackingFailureReason),
                cameraPose = pose,
                light = light,
                centreRay = ray,
                hit = hit,
                floorY = floor.floorY(),
                aimSigmaRad = AimModel.angularSigma(intr, scale),
                nearLocalisation = { range -> intr?.let { AimModel.aim(range, it, scale) } },
            ),
        )
        publish(force = drain())
    }

    // ---- commands (main thread) ----------------------------------------------------------------

    @MainThread fun hold() = command { core.hold() }
    @MainThread fun release() = command { core.release() }
    @MainThread fun startTwoSight() = command { core.startTwoSight() }
    @MainThread fun takeSight() = command { core.takeSight() }
    @MainThread fun retakeSight2() = command { core.retakeSight2() }
    @MainThread fun backToLive() = command { core.backToLive() }

    /** Releases everything. Idempotent; never touches the ARCore session (SceneView owns it). */
    @MainThread
    fun close() {
        closed = true
        running = false
    }

    // ---- internals --------------------------------------------------------------------------------

    private inline fun command(block: () -> Unit) {
        if (closed) return
        block()
        drain()
        publish(force = true)
    }

    /** Emits the core's pending events; true when there were any (publish at once). */
    private fun drain(): Boolean {
        val ev = core.drainEvents()
        for (e in ev) _events.tryEmit(e)
        return ev.isNotEmpty()
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val next = RangeState(
            sessionRunning = running,
            tracking = core.status,
            capabilities = capabilities,
            phase = core.phase,
            live = core.live,
            liveIssue = core.liveIssue,
            held = core.held,
            stability = core.stability,
            floorKnown = core.floorKnown,
            requiredBaselineM = core.requiredBaselineM,
            walkedBaselineM = core.walkedBaselineM,
            preview = core.preview,
            result = core.result,
            canRetake = core.canRetake,
        )
        val phaseChanged = next.phase != _state.value.phase
        if (!force && !phaseChanged && now - lastPublishMs < PUBLISH_INTERVAL_MS) return
        lastPublishMs = now
        _state.value = next
    }

    private fun updateViewScale() {
        val intr = intrinsics
        viewPxPerImagePx = if (intr != null && viewW > 0 && viewH > 0) AimModel.viewPxPerImagePx(intr, viewW, viewH) else Double.POSITIVE_INFINITY
    }

    /** The device tier of the running session (NO_DEPTH before it is configured). */
    val tier: DeviceTier get() = capabilities?.tier ?: DeviceTier.NO_DEPTH

    companion object {
        /** UI publish interval: ≤ 10 Hz. */
        const val PUBLISH_INTERVAL_MS = 100L
        private const val NEAR_M = 0.05f
        private const val FAR_M = 1000f
        private val FLOOR_NORMAL_COS = kotlin.math.cos(Math.toRadians(15.0))
        private const val FLOOR_PLANE_EVERY = 30
    }
}
