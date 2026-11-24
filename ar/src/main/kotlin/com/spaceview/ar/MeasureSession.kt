package com.spaceview.ar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import androidx.annotation.MainThread
import com.google.ar.core.Anchor
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Plane
import com.spaceview.engine.ReferenceObject
import com.spaceview.engine.ScaleCalibration
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.abs

/**
 * Throttled (≤ ~10 Hz) snapshot of the measuring state for the UI. Value-equal snapshots are not
 * re-emitted.
 *
 * @property targetDistanceCm camera → surface under the reticle, rounded to whole cm.
 * @property stability 0..1 progress of the point estimate under the reticle (stability ring).
 * @property locked the reticle point is steady enough to be placed.
 * @property reading live (point count < required) or final measurement.
 * @property tip one improvement tip once the measurement is complete.
 * @property pointsTracking false while committed points are not tracked (tracking lost).
 * @property lowLight the scene is dark.
 * @property pendingCommit "+" was pressed and we are waiting for a steady point.
 * @property aimTooSteep W5: the height / box-top point is refused because the phone points nearly straight up or down
 *   (the crosshair grazes the vertical measuring plane by more than 75°).
 * @property surfaceSeen W6: a usable surface point has been found at least once in this ARCore session (until then the
 *   UI coaches "move the phone slowly side to side").
 * @property diagnostics W6: surface-finding timings for the debug HUD line.
 */
data class MeasureState(
    val mode: MeasureMode = MeasureMode.DISTANCE,
    val sessionRunning: Boolean = false,
    val tracking: TrackingStatus = TrackingStatus.INITIALIZING,
    val hasSurface: Boolean = false,
    val surfaceKind: HitKind? = null,
    val targetDistanceCm: Int? = null,
    val stability: Float = 0f,
    val locked: Boolean = false,
    val pointCount: Int = 0,
    val reading: Reading? = null,
    val isComplete: Boolean = false,
    val tip: ImprovementTip? = null,
    val pointsTracking: Boolean = true,
    val lowLight: Boolean = false,
    val capabilities: ArCapabilities? = null,
    val isFrozen: Boolean = false,
    val pendingCommit: Boolean = false,
    val aimTooSteep: Boolean = false,
    val surfaceSeen: Boolean = false,
    val diagnostics: SurfaceDiagnostics? = null,
) {
    val requiredPoints: Int get() = mode.requiredPoints
}

/**
 * W6 surface-finding diagnostics of one ARCore session (debug HUD line; record the numbers in TESTING). Times are
 * milliseconds since the session started running.
 *
 * @property trackingMs first frame with ARCore tracking.
 * @property firstHitMs first frame with a usable surface point under the crosshair, and [firstHitKind] its source.
 * @property firstLockMs first steady (committable) point.
 * @property repeatedFrames frames SceneView delivered again with an unchanged camera timestamp during the first 10 s
 *   (skipped by the averaging since W6: they used to be averaged as if they were new samples).
 */
data class SurfaceDiagnostics(
    val trackingMs: Long? = null,
    val firstHitMs: Long? = null,
    val firstHitKind: HitKind? = null,
    val firstLockMs: Long? = null,
    val repeatedFrames: Int = 0,
)

/** Why "+" did not place a point. */
enum class CommitFailure {
    NOT_TRACKING, NO_SURFACE, NOT_STEADY, SAME_SPOT,

    /** [MeasureMode.BOX]: the top point is not above the base (aim at the top of the object). */
    BOX_TOP_TOO_LOW,
}

/** One-off events (sounds, snackbars). */
sealed class MeasureEvent {
    /** [auto]: dropped hands-free ([HandsFreeDropper]), not by a tap on "+". */
    data class PointAdded(val index: Int, val complete: Boolean, val auto: Boolean = false) : MeasureEvent()
    data class CommitRejected(val reason: CommitFailure) : MeasureEvent()
    data object FreezeUnavailable : MeasureEvent()
}

/**
 * A frozen camera frame for fine adjustment of the last point.
 *
 * @property imageToView maps bitmap pixels to view pixels (draw the bitmap with it).
 * @property projection the camera at the moment of the freeze.
 * @property points committed endpoints at freeze time (world).
 */
class FrozenFrame(
    val bitmap: Bitmap,
    val imageToView: Matrix,
    val projection: ViewProjection,
    val points: List<Vec3>,
    val mode: MeasureMode,
)

/**
 * The front photo of a scan: the CPU camera image (sensor orientation, ARGB) and the four face corners
 * in its pixels, top-left, top-right, bottom-right, bottom-left as seen from the front (x, y pairs).
 * The receiver owns [bitmap] (rectify it, then recycle it).
 */
class FrontPhoto(val bitmap: Bitmap, val cornersPx: FloatArray)

/** Result of dragging the last point on a frozen frame. */
class FrozenAdjustment(val screen: ScreenPoint, val position: Vec3, val reading: Reading?)

/**
 * Per-frame drawing data for the AR overlay. Written and read on the main thread only; the overlay
 * view redraws itself from it (no Compose state involved).
 */
class OverlayFrame {
    var projection: ViewProjection? = null
    val points = ArrayList<Vec3>(4)
    var live: Vec3? = null
    var pointsVisible = true
    var mode: MeasureMode = MeasureMode.DISTANCE
    var hidden = false
}

/**
 * SpaceView's measuring session on top of an ARCore [Session] owned by SceneView's `ARSceneView`
 * (which creates, resumes, pauses and closes it with the lifecycle; this class never outlives it
 * and drops every ARCore reference on pause/[close]).
 *
 * Anchor lifetime (M2 review M5): anchors are detached only in [onSessionPaused], which SceneView
 * calls on the main thread before it hands `Session.pause()`/`Session.close()` to its teardown
 * executor, and never afterwards. [close] only drops references (`Session.close()` releases every
 * anchor). After a pause a finished measurement keeps its last positions (unanchored, still
 * savable); an unfinished one is cleared, because new points could not be trusted to share a world
 * frame with points from before the pause. Every anchor / session use checks `closed` and `live`.
 *
 * State ownership (M2 review minor 3): the main thread owns the point list and the fields derived
 * from it (point count, completeness, pending commit, freeze, mode, session running); the worker
 * owns the per-frame fields (tracking, surface, stability, reading, tip). Every change to the points
 * bumps a generation counter, samples carry it, and the worker drops results of an older generation,
 * so a buffered frame can never resurrect a reading after Save, Undo or Clear.
 *
 * Threading: all public functions and the SceneView callbacks run on the **main** thread (where
 * SceneView's frame loop runs). Per frame the main thread only does the native reads (hit test,
 * point cloud, raw depth window), copies the data out and releases it; gating, averaging, plane
 * fitting and the uncertainty maths run on a single background worker. UI state is published at
 * most every [PUBLISH_INTERVAL_MS].
 *
 * Per-frame allocations (R3): [FrameSample]s come from a fixed pool; each carries its own point-cloud buffer
 * and endpoint list, filled in place on main and returned to the pool by the worker (or by the channel when it
 * drops an old sample). No `copyOf`, no snapshot list, no sample object per frame in steady state.
 *
 * It is the primary [ArFrameListener] of every AR host it is in (camera config, session configuration).
 *
 * @param depthWanted user setting: use the Depth API when supported (off by default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasureSession(
    initialMode: MeasureMode,
    private val depthWanted: Boolean,
) : ArFrameListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    /** Free samples (main takes, worker / channel give back). Array-backed: offer and poll never allocate. */
    private val samplePool = ArrayBlockingQueue<FrameSample>(SAMPLE_POOL).also { q -> repeat(SAMPLE_POOL) { q.offer(FrameSample()) } }
    private val frames = Channel<FrameSample>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { samplePool.offer(it) },
    )

    override val requirements: ArRequirements = ArRequirements(depth = depthWanted, lights = false, planeRenderer = true)

    /**
     * The reticle hit of the frame being dispatched (main thread). Listeners after this one in the same
     * [ArFrameDispatcher] read it instead of running a second `frame.hitTest` (S1, section 3.5). Null when not
     * tracking.
     */
    internal var latestCentreHit: ArHit? = null
        private set

    private val _state = MutableStateFlow(MeasureState(mode = initialMode))
    /** Throttled UI state. */
    val state: StateFlow<MeasureState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MeasureEvent>(extraBufferCapacity = 8)
    /** One-off events. */
    val events: SharedFlow<MeasureEvent> = _events.asSharedFlow()

    private val _frozen = MutableStateFlow<FrozenFrame?>(null)
    /** The frozen frame while freeze-frame adjustment is active. */
    val frozen: StateFlow<FrozenFrame?> = _frozen.asStateFlow()

    /** Drawing data for the overlay; [onOverlayChanged] fires after each update. */
    val overlay = OverlayFrame()
    var onOverlayChanged: (() -> Unit)? = null

    private val _sessionId = MutableStateFlow<String?>(null)
    /**
     * Identity of the current ARCore session: a new random id every time SceneView creates a session
     * (first start, "Try again", activity recreation). World coordinates (calibration plane, anchors)
     * are only meaningful within one id (M2 review M3). Null before the first session.
     */
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _frontCapture = MutableStateFlow<FrontCaptureStatus?>(null)
    /** Front-photo guidance while a capture is armed ([startFrontCapture]); null when not armed. */
    val frontCapture: StateFlow<FrontCaptureStatus?> = _frontCapture.asStateFlow()

    private val _frontPhoto = MutableStateFlow<FrontPhoto?>(null)
    /** The captured front photo, until [takeFrontPhoto] hands it over. */
    val frontPhoto: StateFlow<FrontPhoto?> = _frontPhoto.asStateFlow()

    // ---- main-thread state ------------------------------------------------------------------
    private var session: Session? = null
    @Volatile private var capabilities: ArCapabilities? = null
    private var mode = initialMode
    private val endpoints = ArrayList<Endpoint>(4)
    private var viewW = 0
    private var viewH = 0
    private var lastPointCloudTs = -1L
    private val cloudTs = LongArray(1)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private var lastPlaneHit: ArHit? = null
    private var commitRequestedAtMs = -1L
    private var commitAuto = false
    private var framePointsOk = true
    private val tapGuard = TapGuard()
    private var freezeRequested = false
    private var frozenCapture: FrozenCaptureState? = null
    private var pendingAdjust: Vec3? = null
    private var intrinsics: ImageIntrinsics? = null
    private var viewPxPerImagePx = Double.POSITIVE_INFINITY
    private var lastTracking = CameraTracking.STOPPED
    private var closed = false
    /** True between resume and pause: only then may anchors be created or detached. */
    private var live = false

    // ---- front photo (scan), main thread ----
    private val capture = FrontCaptureController()
    private var captureJob: Job? = null
    private var scanChoice: FaceChoice? = null
    private var scanChoiceGeneration = -1
    private val cornerPx = FloatArray(8)
    private var cpuCamera: CpuImageCamera? = null
    private var lastCameraPose: com.spaceview.engine.Pose? = null

    // ---- W5 / W6, main thread ----
    /** The floor under the camera (W5 far base): robust lowest horizontal surface of this session. */
    private val floor = FloorEstimator()
    private var frameCounter = 0
    /** Elapsed-realtime ms when the current ARCore session started running (W6 diagnostics), -1 before. */
    @Volatile private var sessionStartMs = -1L
    /** Bumped on every new ARCore session: the worker resets its per-session state and diagnostics. */
    @Volatile private var sessionEpoch = 0
    /** The worker's latest W5 aim point (height top / box top / far base) for the overlay, and its wall time. */
    @Volatile private var liveAim: Vec3? = null
    @Volatile private var liveAimWallNs = 0L

    /** Bumped (main thread) on every change to [endpoints]; the worker drops older results. */
    @Volatile private var generation = 0

    @Volatile private var model: UncertaintyModel = UncertaintyModel(DeviceTier.NO_DEPTH)
    @Volatile private var calibration: ScaleCalibration? = null

    /** Latest commit candidate published by the worker. */
    @Volatile private var candidate: CommitCandidate? = null

    init {
        scope.launch(worker) { runWorker() }
    }

    // ---- configuration ------------------------------------------------------------------------

    /**
     * Calibration to apply to readings (null = none). It must come from **this** ARCore session
     * ([sessionId]); the composer applies it only to readings whose points lie on its plane.
     */
    fun setCalibration(c: ScaleCalibration?) {
        calibration = c
        rebuildModel()
    }

    /** Switches mode; clears the current points. */
    @MainThread
    fun setMode(newMode: MeasureMode) {
        if (newMode == mode) return
        mode = newMode
        clear()
        _state.update { it.copy(mode = newMode) }
    }

    /** Size of the AR view in pixels (the overlay must have the same size). */
    @MainThread
    fun setViewSize(width: Int, height: Int) {
        viewW = width
        viewH = height
        updateViewScale()
    }

    // ---- SceneView callbacks ----------------------------------------------------------------------

    /** `sessionCameraConfig` selector. */
    override fun selectCameraConfig(session: Session): CameraConfig = SessionSetup.selectCameraConfig(session, depthWanted)

    /** `sessionConfiguration` callback. */
    override fun configureSession(session: Session, config: Config) {
        val caps = SessionSetup.detect(session, depthWanted)
        capabilities = caps
        SessionSetup.configure(config, caps.depthActive)
        rebuildModel()
        _state.update { it.copy(capabilities = caps) }
    }

    @MainThread
    override fun onSessionCreated(session: Session) {
        if (closed) return
        // A new session has a new world frame: nothing from an earlier one carries over. Its anchors
        // were detached in onSessionPaused (or released by Session.close()); only drop references.
        if (endpoints.isNotEmpty()) {
            endpoints.clear()
            tapGuard.onPointsRemoved()
            bumpGeneration()
            _state.update { it.copy(pointCount = 0, isComplete = false, reading = null, tip = null, pendingCommit = false) }
        }
        this.session = session
        lastPlaneHit = null
        lastPointCloudTs = -1L
        floor.reset()
        sessionEpoch++
        sessionStartMs = -1L
        liveAim = null
        intrinsics = null
        cpuCamera = null
        cancelFrontCapture()
        updateViewScale()
        _sessionId.value = UUID.randomUUID().toString()
    }

    @MainThread
    override fun onSessionResumed(session: Session) {
        if (closed) return
        this.session = session
        live = true
        if (sessionStartMs < 0) sessionStartMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(sessionRunning = true) }
    }

    /**
     * Also called by SceneView on the main thread right before it pauses and closes the session on
     * its teardown executor: the last moment anchors may be detached (M2 review M5).
     */
    @MainThread
    override fun onSessionPaused(session: Session) {
        if (closed) return
        commitRequestedAtMs = -1L
        freezeRequested = false
        if (frozenCapture != null) endFreeze(apply = false)
        // The photo needs live tracking; the screen re-arms it once the session runs again.
        cancelFrontCapture()
        for (e in endpoints) {
            e.anchor?.let { a -> runCatching { a.detach() } }
            e.anchor = null
        }
        lastPlaneHit = null
        live = false
        val incomplete = endpoints.isNotEmpty() && endpoints.size < mode.requiredPoints
        if (incomplete) {
            endpoints.clear()
            tapGuard.onPointsRemoved()
            bumpGeneration()
        }
        _state.update {
            if (incomplete) {
                it.copy(sessionRunning = false, pendingCommit = false, pointCount = 0, isComplete = false, reading = null, tip = null)
            } else {
                it.copy(sessionRunning = false, pendingCommit = false)
            }
        }
    }

    /** `onSessionUpdated`: once per ARCore frame, main thread. */
    @MainThread
    override fun onFrame(session: Session, frame: Frame) {
        latestCentreHit = null
        if (closed || viewW <= 0 || viewH <= 0) return
        this.session = session
        live = true // a frame means the session is running
        if (sessionStartMs < 0) sessionStartMs = SystemClock.elapsedRealtime()
        if (!_state.value.sessionRunning) _state.update { it.copy(sessionRunning = true) }
        val camera = frame.camera
        val tracking = FrameReaders.tracking(camera.trackingState)
        lastTracking = tracking
        val reason = FrameReaders.reason(camera.trackingFailureReason)
        val cameraPose = camera.pose.toEngine()
        lastCameraPose = cameraPose
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, NEAR_M, FAR_M)
        val vp = ViewProjection(viewMatrix, projMatrix, viewW, viewH)
        val centreX = viewW / 2f
        val centreY = viewH / 2f
        val light = frame.lightEstimate.let { if (it.state == com.google.ar.core.LightEstimate.State.VALID) it.pixelIntensity else Float.NaN }

        val sample = samplePool.poll() ?: FrameSample() // empty only if the worker stalls: never blocks main
        var hit: ArHit? = null
        var cloudCount = 0
        var depth: DepthPatch? = null
        if (tracking == CameraTracking.TRACKING) {
            // Camera-image intrinsics (fixed for the session's camera config): the aim error is
            // defined in camera-image pixels, never in view pixels (M2 review B1).
            if (intrinsics == null) {
                intrinsics = FrameReaders.imageIntrinsics(frame)
                updateViewScale()
            }
            hit = FrameReaders.centreHit(frame, centreX, centreY)
            latestCentreHit = hit
            if (hit?.kind == HitKind.PLANE) lastPlaneHit = hit
            val n = FrameReaders.pointCloud(frame, lastPointCloudTs, sample.cloud, cloudTs)
            if (n >= 0) {
                lastPointCloudTs = cloudTs[0]
                cloudCount = n
            }
            if (capabilities?.depthActive == true) depth = FrameReaders.depthPatch(frame, centreX, centreY)
            // W5 far base: the floor from horizontal hits under the crosshair and (every ~second) ARCore's upward planes.
            hit?.let { h ->
                val n = h.normal
                if (n != null && (n dot Vec3.UP) >= FLOOR_NORMAL_COS) {
                    floor.add(h.position.x, h.position.y, h.position.z, h.kind == HitKind.PLANE, frame.timestamp)
                }
            }
            if (frameCounter++ % FLOOR_PLANE_EVERY == 0) FrameReaders.feedFloorPlanes(session, floor, frame.timestamp)
        }

        // Anchor refinement: read the current pose of every committed, anchored point. Points left
        // unanchored by a pause keep their last position (a finished, frozen result).
        var allTracking = true
        var anyUnanchored = false
        for (e in endpoints) {
            val a = e.anchor
            if (a == null) {
                anyUnanchored = true
            } else if (a.trackingState == TrackingState.TRACKING) {
                e.data = e.data.movedTo(a.pose.position())
            } else {
                allTracking = false
            }
        }

        framePointsOk = allTracking && !anyUnanchored
        processCommitRequest(session, tracking)
        if (freezeRequested) captureFreeze(frame, vp, tracking)
        if (capture.isArmed) processFrontCapture(frame, cameraPose, tracking == CameraTracking.TRACKING && allTracking, !anyUnanchored)

        sample.timestampNs = frame.timestamp
        sample.generation = generation
        sample.tracking = tracking
        sample.reason = reason
        sample.cameraPose = cameraPose
        sample.light = light
        sample.centreRay = vp.centreRay()
        sample.hit = hit?.let { HitSample(it.position, it.normal, it.kind, it.distance) }
        sample.cloudCount = cloudCount
        sample.depth = depth
        sample.endpoints.clear()
        for (i in endpoints.indices) sample.endpoints += endpoints[i].data
        sample.pointsTracking = allTracking
        sample.intrinsics = intrinsics
        sample.viewPxPerImagePx = viewPxPerImagePx
        sample.mode = mode
        sample.frozen = frozenCapture != null
        sample.floorY = floor.floorY() ?: Double.NaN
        sample.epoch = sessionEpoch
        // DROP_OLDEST: a dropped sample goes back to the pool through onUndeliveredElement.
        if (frames.trySend(sample).isFailure) samplePool.offer(sample)

        overlay.projection = vp
        overlay.points.clear()
        for (e in endpoints) overlay.points += e.data.point.position
        overlay.live = if (endpoints.size < mode.requiredPoints && tracking == CameraTracking.TRACKING) {
            // W5: the height top / box top / far base is not the raw hit: never fall back to the hit for those.
            val aimed = liveAim?.takeIf { SystemClock.elapsedRealtimeNanos() - liveAimWallNs < STALE_NS }
            candidate?.takeIf { frame.timestamp - it.timestampNs < STALE_NS }?.data?.point?.position
                ?: aimed ?: hit?.position?.takeIf { !aimsW5(mode, endpoints.size) }
        } else {
            null
        }
        overlay.pointsVisible = allTracking && !anyUnanchored && tracking == CameraTracking.TRACKING
        overlay.mode = mode
        overlay.hidden = frozenCapture != null
        onOverlayChanged?.invoke()
    }

    // ---- commands (main thread) -----------------------------------------------------------------

    /**
     * "+": places a point at the reticle once the estimate under it is steady (waits up to
     * [COMMIT_TIMEOUT_MS]). When the measurement is already complete, starts a new one.
     */
    @MainThread
    fun addPoint() {
        // SM1 review MINOR 5: a tap just after a hands-free drop on the same spot is a confirmation, not a "same
        // spot" error, and a tap just after completion must not clear the result it completed.
        val complete = endpoints.size >= mode.requiredPoints
        if (tapGuard.ignoreTap(SystemClock.elapsedRealtime(), candidate?.data?.point?.position, complete)) return
        requestCommit()
    }

    private fun requestCommit() {
        if (closed || frozenCapture != null) return
        if (endpoints.size >= mode.requiredPoints) clear()
        if (!live || lastTracking != CameraTracking.TRACKING) {
            _events.tryEmit(MeasureEvent.CommitRejected(CommitFailure.NOT_TRACKING))
            return
        }
        commitRequestedAtMs = SystemClock.elapsedRealtime()
        commitAuto = false
        _state.update { it.copy(pendingCommit = true) }
    }

    // ---- hands-free (SM1a): read by HandsFreeDropper through [autoDropSource], main thread ----

    /** This session as the dropper's [AutoDropSource] (the seam its JVM tests fake). */
    internal val autoDropSource: AutoDropSource = object : AutoDropSource {
        override val cameraTrackingOk: Boolean get() = !closed && live && lastTracking == CameraTracking.TRACKING
        override val lastFramePose: com.spaceview.engine.Pose? get() = lastCameraPose
        override val committedCount: Int get() = endpoints.size
        override val frozen: Boolean get() = frozenCapture != null || freezeRequested

        override fun autoDropTarget(): EndpointData? {
            val allowed = AutoDropRules.allowed(
                closed = closed, live = live, frozen = frozenCapture != null || freezeRequested,
                commitPending = commitRequestedAtMs >= 0, points = endpoints.size, required = mode.requiredPoints,
                pointsOk = framePointsOk,
            )
            if (!allowed) return null
            val c = candidate ?: return null
            return c.data.takeIf { c.locked && SystemClock.elapsedRealtimeNanos() - c.wallNs < STALE_NS }
        }

        override fun distanceToNearestPoint(p: Vec3): Double {
            var d = Double.POSITIVE_INFINITY
            for (i in endpoints.indices) d = minOf(d, endpoints[i].data.point.position.distanceTo(p))
            return d
        }

        override fun addPointHandsFree() {
            requestCommit()
            commitAuto = commitRequestedAtMs >= 0
        }
    }

    /** True when any committed point was dropped hands-free (saved as `CaptureMethod.HANDS_FREE`). */
    @MainThread
    fun anyHandsFreePoint(): Boolean = endpoints.any { it.auto }


    /** Removes the last point (all of them when a pause left them unanchored). */
    @MainThread
    fun undo() {
        if (closed || frozenCapture != null) return
        if (endpoints.any { it.anchor == null }) {
            clear()
            return
        }
        commitRequestedAtMs = -1L
        cancelFrontCapture()
        endpoints.removeLastOrNull()?.let { detach(it) }
        tapGuard.onPointsRemoved()
        bumpGeneration()
        _state.update { it.copy(pointCount = endpoints.size, isComplete = false, tip = null, reading = null, pendingCommit = false) }
    }

    /** Removes all points. */
    @MainThread
    fun clear() {
        if (closed) return
        commitRequestedAtMs = -1L
        cancelFrontCapture()
        for (e in endpoints) detach(e)
        endpoints.clear()
        tapGuard.onPointsRemoved()
        bumpGeneration()
        _state.update { it.copy(pointCount = 0, isComplete = false, tip = null, reading = null, pendingCommit = false) }
    }

    /** Committed endpoints. */
    @MainThread
    fun committedPoints(): List<EndpointData> = endpoints.map { it.data }

    /**
     * Calibration from the three committed corners of a reference card / sheet, using this device
     * tier's *uncalibrated* model. Null until three corners are placed.
     */
    @MainThread
    fun calibrationCheck(reference: ReferenceObject): CalibrationCheck? {
        if (endpoints.size < 3) return null
        val uncalibrated = UncertaintyModel(capabilities?.tier ?: DeviceTier.NO_DEPTH)
        return MeasurementComposer.calibrationCheck(reference, endpoints.map { it.data }, uncalibrated)
    }

    /**
     * Calibration from the two committed ends of a tape-measured span ([trueValue] ± [trueSigma] m),
     * using this device tier's *uncalibrated* model. Null until two ends are placed.
     */
    @MainThread
    fun knownLengthCheck(trueValue: Double, trueSigma: Double): CalibrationCheck? {
        if (endpoints.size < 2) return null
        val uncalibrated = UncertaintyModel(capabilities?.tier ?: DeviceTier.NO_DEPTH)
        return MeasurementComposer.knownLengthCheck(trueValue, trueSigma, endpoints.map { it.data }, uncalibrated)
    }

    /**
     * The finished scan [reading] (what the user saw) as a [ScanOutline]. The front face is decided
     * **once** per set of points (the first call after the points last changed) and reused by later
     * calls, so W / D never swap while the Outline card is on screen (M3 review M1). Null unless the
     * points complete [shape]'s mode.
     */
    @MainThread
    fun scanOutline(shape: ScanShape, reading: Reading): ScanOutline? {
        val pose = lastCameraPose ?: return null
        if (mode != shape.mode || endpoints.size < shape.mode.requiredPoints) return null
        val points = endpoints.map { it.data.point.position }
        if (scanChoice == null || scanChoiceGeneration != generation) {
            scanChoice = ScanComposer.chooseFace(shape, points, pose.translation, pose.forward) ?: return null
            scanChoiceGeneration = generation
        }
        return ScanComposer.outline(shape, reading, points, scanChoice ?: return null)
    }

    /**
     * Arms the front-photo auto-capture for the confirmed [outline] (its front face, never re-decided):
     * once tracking is good, the phone is roughly parallel to the face and steady, and the whole face is
     * in the picture, the CPU image is taken and published on [frontPhoto]. Guidance is on
     * [frontCapture]. Refused with [CaptureHint.REMEASURE] while any point lost its anchor in a pause
     * (M3 review M2). False when not armed.
     */
    @MainThread
    fun startFrontCapture(outline: ScanOutline): Boolean {
        if (closed || !live || mode != outline.shape.mode || endpoints.size < outline.shape.mode.requiredPoints) return false
        cancelConversion()
        val armed = capture.arm(outline, pointsAnchored = endpoints.all { it.anchor != null })
        _frontCapture.value = capture.status
        return armed
    }

    /** Disarms the front-photo capture and drops a photo still being converted (keeps one already published). */
    @MainThread
    fun cancelFrontCapture() {
        cancelConversion()
        capture.disarm()
        _frontCapture.value = null
    }

    private fun cancelConversion() {
        captureJob?.cancel()
        captureJob = null
    }

    /** Hands over the captured photo (the caller owns its bitmap) and clears it. */
    @MainThread
    fun takeFrontPhoto(): FrontPhoto? = _frontPhoto.value.also { _frontPhoto.value = null }

    /** Freezes the current camera frame so the last point can be adjusted with the magnifier. */
    @MainThread
    fun freeze() {
        if (closed || !live || endpoints.isEmpty() || frozenCapture != null || endpoints.any { it.anchor == null }) {
            _events.tryEmit(MeasureEvent.FreezeUnavailable)
            return
        }
        freezeRequested = true
    }

    /**
     * Moves the last point to the surface under view pixel ([x], [y]) of the frozen frame (ray ∩
     * the point's own surface plane). Returns null when that position is not usable.
     */
    @MainThread
    fun adjustFrozen(x: Float, y: Float): FrozenAdjustment? {
        val cap = frozenCapture ?: return null
        val last = endpoints.lastOrNull() ?: return null
        val ray = cap.projection.rayThrough(x.toDouble(), y.toDouble()) ?: return null
        val origin = cap.cameraPosition
        val normal = last.data.normal ?: (origin - last.data.point.position).normalized()
        val plane = Plane.fromPointNormal(last.data.point.position, normal)
        val p = plane.intersect(ray, MIN_ADJUST_COS) ?: return null
        if (p.distanceTo(last.data.point.position) > MAX_ADJUST_M) return null
        pendingAdjust = p
        val adjusted = adjustedEndpoint(last.data, p, cap)
        val pts = endpoints.dropLast(1).map { it.data } + adjusted
        val reading = runCatching { MeasurementComposer.compose(mode, pts, null, model) }.getOrNull()
        val screen = cap.projection.project(p) ?: ScreenPoint(x, y)
        return FrozenAdjustment(screen, p, reading)
    }

    /** Leaves freeze-frame; [apply] re-anchors the last point at the adjusted position. */
    @MainThread
    fun endFreeze(apply: Boolean) {
        val cap = frozenCapture
        val p = pendingAdjust
        val s = session
        if (apply && !closed && live && cap != null && p != null && s != null && endpoints.isNotEmpty()) {
            val last = endpoints.last()
            val anchor = runCatching { s.createAnchor(translationPose(p)) }.getOrNull()
            if (anchor != null) {
                detach(last)
                endpoints[endpoints.size - 1] = Endpoint(anchor, adjustedEndpoint(last.data, p, cap), last.auto)
                bumpGeneration()
            }
        }
        frozenCapture = null
        pendingAdjust = null
        _frozen.value = null
        _state.update { it.copy(isFrozen = false, pointCount = endpoints.size) }
    }

    /**
     * Releases everything. Call when the AR screen leaves composition. Idempotent. Never touches an
     * anchor or the session: SceneView may already be closing it on another thread, and
     * `Session.close()` releases every anchor (M2 review M5).
     */
    @MainThread
    fun close() {
        if (closed) return
        closed = true
        live = false
        endpoints.clear()
        session = null
        lastPlaneHit = null
        frozenCapture = null
        _frozen.value = null
        cancelConversion()
        capture.disarm()
        // A photo nobody took is released here, not left to the GC (M3 review m6).
        _frontPhoto.value?.bitmap?.recycle()
        _frontPhoto.value = null
        onOverlayChanged = null
        frames.close()
        scope.cancel()
    }

    // ---- internals (main thread) ---------------------------------------------------------------

    private fun processCommitRequest(session: Session, tracking: CameraTracking) {
        val requested = commitRequestedAtMs
        if (requested < 0 || closed || !live) return
        val now = SystemClock.elapsedRealtime()
        val c = candidate
        val fresh = c != null && SystemClock.elapsedRealtimeNanos() - c.wallNs < STALE_NS
        if (tracking == CameraTracking.TRACKING && fresh && c.locked) {
            val data = c.data
            if (endpoints.any { it.data.point.position.distanceTo(data.point.position) < MeasurementComposer.MIN_LENGTH_M }) {
                finishCommit(CommitFailure.SAME_SPOT)
                return
            }
            if (mode == MeasureMode.BOX && endpoints.size == 3 &&
                MeasurementComposer.boxTopTooLow(endpoints.map { it.data.point.position }, data.point.position)
            ) {
                finishCommit(CommitFailure.BOX_TOP_TOO_LOW)
                return
            }
            val anchor = createAnchor(session, data) ?: run { finishCommit(CommitFailure.NOT_TRACKING); return }
            endpoints += Endpoint(anchor, data, commitAuto)
            bumpGeneration()
            commitRequestedAtMs = -1L
            val complete = endpoints.size >= mode.requiredPoints
            tapGuard.onCommitted(SystemClock.elapsedRealtime(), data.point.position, auto = commitAuto, complete = complete)
            _state.update { it.copy(pendingCommit = false, pointCount = endpoints.size, isComplete = complete) }
            _events.tryEmit(MeasureEvent.PointAdded(endpoints.size - 1, complete, auto = commitAuto))
            return
        }
        if (now - requested > COMMIT_TIMEOUT_MS) {
            finishCommit(
                when {
                    tracking != CameraTracking.TRACKING -> CommitFailure.NOT_TRACKING
                    c == null || !fresh -> CommitFailure.NO_SURFACE
                    else -> CommitFailure.NOT_STEADY
                },
            )
        }
    }

    private fun finishCommit(failure: CommitFailure) {
        commitRequestedAtMs = -1L
        _state.update { it.copy(pendingCommit = false) }
        _events.tryEmit(MeasureEvent.CommitRejected(failure))
    }

    /**
     * Plane-attached anchor when the point lies on the last hit plane (inside its polygon **and**
     * within [PLANE_ANCHOR_MAX_OFFSET_M] of it: `isPoseInPolygon` ignores height, so a table-top point
     * could otherwise be attached to the floor plane, M2 review minor 5), else a world anchor.
     */
    private fun createAnchor(session: Session, data: EndpointData): Anchor? {
        if (closed || !live) return null
        val pose = translationPose(data.point.position)
        val plane = lastPlaneHit?.trackable
        if (data.kind == HitKind.PLANE && plane is com.google.ar.core.Plane &&
            plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null &&
            runCatching { onPlane(plane, data.point.position) && plane.isPoseInPolygon(pose) }.getOrDefault(false)
        ) {
            runCatching { return plane.createAnchor(pose) }
        }
        return runCatching { session.createAnchor(pose) }.getOrNull()
    }

    private fun onPlane(plane: com.google.ar.core.Plane, p: Vec3): Boolean {
        val c = plane.centerPose
        return abs((p - c.position()) dot c.yAxisVec()) <= PLANE_ANCHOR_MAX_OFFSET_M
    }

    /** Detaches [e]'s anchor while the session is live (main thread, never after a pause/close). */
    private fun detach(e: Endpoint) {
        val a = e.anchor ?: return
        e.anchor = null
        if (live && !closed) runCatching { a.detach() }
    }

    private fun bumpGeneration() {
        generation++
    }

    private fun updateViewScale() {
        val intr = intrinsics
        viewPxPerImagePx = if (intr != null && viewW > 0 && viewH > 0) AimModel.viewPxPerImagePx(intr, viewW, viewH) else Double.POSITIVE_INFINITY
    }

    private fun captureFreeze(frame: Frame, vp: ViewProjection, tracking: CameraTracking) {
        freezeRequested = false
        if (tracking != CameraTracking.TRACKING) {
            _events.tryEmit(MeasureEvent.FreezeUnavailable)
            return
        }
        val still = FrameReaders.captureStill(frame, viewW, viewH)
        if (still == null) {
            _events.tryEmit(MeasureEvent.FreezeUnavailable)
            return
        }
        val cap = FrozenCaptureState(vp, frame.camera.pose.position(), still.intrinsics, still.viewPxPerImagePx)
        frozenCapture = cap
        pendingAdjust = null
        val points = endpoints.map { it.data.point.position }
        val m = mode
        _state.update { it.copy(isFrozen = true) }
        scope.launch {
            val bitmap = withContext(worker) { decodeStill(still) }
            if (bitmap == null || frozenCapture !== cap) {
                if (frozenCapture === cap) endFreeze(apply = false)
                _events.tryEmit(MeasureEvent.FreezeUnavailable)
                return@launch
            }
            val matrix = Matrix().apply { setPolyToPoly(still.imagePoints, 0, still.viewPoints, 0, 3) }
            _frozen.value = FrozenFrame(bitmap, matrix, vp, points, m)
        }
    }

    /**
     * One `onSessionUpdated` call while the front-photo capture is armed (main thread). The controller
     * skips repeated camera frames, refuses unanchored points and rebuilds the face from the anchors
     * every few frames; the face is rebuilt once more at the moment of capture.
     */
    private fun processFrontCapture(frame: Frame, pose: com.spaceview.engine.Pose, trackingOk: Boolean, anchored: Boolean) {
        val cam = cpuCamera ?: FrameReaders.cpuImageCamera(frame)?.also { cpuCamera = it } ?: return
        val take = capture.onFrame(frame.timestamp, trackingOk, anchored, pose, ::currentPoints, cam, cornerPx)
        val status = capture.status
        if (_frontCapture.value != status) _frontCapture.value = status
        if (take) captureFrontPhoto(frame, pose, cam)
    }

    private fun currentPoints(): List<Vec3> = endpoints.map { it.data.point.position }

    private fun captureFrontPhoto(frame: Frame, pose: com.spaceview.engine.Pose, cam: CpuImageCamera) {
        val outline = capture.armedOutline ?: return
        val face = capture.faceNow(currentPoints())
        val image = FrameReaders.cpuImage(frame)
        if (face == null || image == null) {
            capture.onCaptureFailed()
            return
        }
        val imageCam = cam.scaledTo(image.width, image.height)
        val corners = FloatArray(8)
        if (ImageProjection.fitCorners(face.corners, pose, imageCam, corners) != CornerFit.INSIDE) {
            capture.onCaptureFailed()
            return
        }
        val gen = capture.onCaptured()
        _frontCapture.value = capture.status
        cancelConversion()
        captureJob = scope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(worker) {
                    runCatching {
                        Bitmap.createBitmap(Nv21.toArgb(image), image.width, image.height, Bitmap.Config.ARGB_8888)
                    }.getOrNull()
                }
                if (closed || capture.generation != gen) return@launch // cancelled, re-armed or closed meanwhile
                val b = bitmap
                if (b == null) {
                    // Conversion failed (out of memory): clear the "Got it." state first, so the screen
                    // re-arms after a pause even if this attempt is refused (M3 review m1).
                    capture.disarm()
                    _frontCapture.value = null
                    startFrontCapture(outline)
                    return@launch
                }
                _frontPhoto.value?.bitmap?.recycle()
                _frontPhoto.value = FrontPhoto(b, corners)
                bitmap = null // published: the receiver owns it now
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun adjustedEndpoint(original: EndpointData, p: Vec3, cap: FrozenCaptureState): EndpointData {
        val range = cap.cameraPosition.distanceTo(p)
        // The still is the CPU image: the placement error is in its pixels with its focal length
        // (M2 review B1). Conservative: the original aim term stays and this error is added on top.
        val loc = cap.stillIntrinsics?.let { AimModel.adjust(range, it, cap.viewPxPerImagePx) }
        val sigma = loc?.sigmaMeters ?: model.unlocalisedEndpointSigma
        return EndpointData(
            point = original.point.movedTo(p).withLocalisationSigma(sigma),
            normal = original.normal,
            kind = HitKind.ADJUSTED,
            rangeM = range,
            planeBiasRel = original.planeBiasRel,
            spreadRms = original.spreadRms,
            incidenceCos = original.incidenceCos,
            focalPx = loc?.focalLengthPx ?: 0.0,
            pixelErrorPx = loc?.pixelErrorPx ?: 0.0,
        )
    }

    private fun rebuildModel() {
        val tier = capabilities?.tier ?: DeviceTier.NO_DEPTH
        model = UncertaintyModel(tier, calibration)
    }

    // ---- worker -----------------------------------------------------------------------------------

    private suspend fun runWorker() {
        val gate = TrackingGate()
        val stabilizer = SampleStabilizer()
        // W5 far base: a ground point jitters by cot(depression) x the hand's tremor, so it gets a wider "same spot" window.
        val groundStabilizer = SampleStabilizer(moveAbsM = GROUND_MOVE_ABS_M, moveRel = GROUND_MOVE_REL)
        val surface = LocalSurfaceModel()
        val depthPoints = FloatArray(3 * MAX_DEPTH_POINTS)
        var lastPublishMs = 0L
        var lastGeneration = -1
        var lastStatus: TrackingStatus? = null
        var lowLightSeen = false
        var lastTs = Long.MIN_VALUE
        var epoch = -1
        var diag = SurfaceDiagnostics()
        var surfaceSeen = false
        for (s in frames) {
            try {
                if (s.epoch != epoch) {
                    epoch = s.epoch
                    diag = SurfaceDiagnostics()
                    surfaceSeen = false
                    lastTs = Long.MIN_VALUE
                    gate.reset()
                    stabilizer.reset()
                    groundStabilizer.reset()
                    surface.clear()
                }
                // W6: SceneView calls onSessionUpdated at the display rate and repeats the camera frame (same timestamp)
                // in between. A repeated frame is not a new sample: averaging it would shrink the ± dishonestly.
                val repeated = s.timestampNs == lastTs
                lastTs = s.timestampNs
                if (repeated) {
                    // Counted over the first seconds only, so the snapshot stops changing (no 10 Hz recomposition).
                    val start = sessionStartMs
                    if (start >= 0 && SystemClock.elapsedRealtime() - start < DIAG_REPEAT_WINDOW_MS) {
                        diag = diag.copy(repeatedFrames = diag.repeatedFrames + 1)
                    }
                    if (s.generation == lastGeneration) continue
                }
                val gateResult = gate.update(s.timestampNs, s.tracking, s.reason, s.cameraPose, s.light)
                if (s.endpoints.isEmpty()) lowLightSeen = false
                if (gateResult.lowLight) lowLightSeen = true
                val tracked = gateResult.sampleQuality != TrackingQuality.NOT_TRACKING

                // Raw 3-D points for our own plane fit, pre-filtered to a cone around the reticle ray.
                val ray = s.centreRay
                if (!repeated) {
                    if (s.cloudCount > 0) surface.addPoints(s.cloud, s.cloudCount, s.timestampNs, ray)
                    s.depth?.let { patch ->
                        val n = patch.toWorldPoints(depthPoints)
                        if (n > 0) surface.addPoints(depthPoints, n, s.timestampNs, ray)
                    }
                }
                var tooSteep = false
                val chosen = if (tracked && ray != null) {
                    when (val aimed = chooseW5(s, ray, surface)) {
                        is HeightAim.Top.Point -> aimed.chosen
                        HeightAim.Top.TooSteep -> { tooSteep = true; null }
                        HeightAim.Top.None -> null
                        null -> SurfaceChooser.choose(s.hit, surface, ray, s.cameraPose.translation, s.timestampNs)
                            ?: farBase(s, ray)
                    }
                } else {
                    null
                }
                if (aimsW5(s.mode, s.endpoints.size) || chosen?.kind == HitKind.GROUND) {
                    liveAim = chosen?.position
                    liveAimWallNs = SystemClock.elapsedRealtimeNanos()
                } else {
                    liveAim = null
                }
                val range = chosen?.range ?: 0.0
                val loc = chosen?.localisation(s.intrinsics?.let { AimModel.aim(range, it, s.viewPxPerImagePx) })
                val ground = chosen?.kind == HitKind.GROUND
                val active = if (ground) groundStabilizer else stabilizer
                if (!s.frozen && !repeated) {
                    // While the user works on a frozen still, tracking stays fresh but nothing is averaged.
                    // NOT_TRACKING resets the stabiliser even without a position (M2 review minor 4).
                    active.add(chosen?.position, range, gateResult.sampleQuality, loc)
                    (if (ground) stabilizer else groundStabilizer).reset()
                }
                val est = active.estimate
                val liveData: EndpointData? = if (chosen != null) {
                    val normal = chosen.normal
                    val incidence = if (normal != null && ray != null) abs(normal dot ray.direction) else null
                    val extra = chosen.extraCovariance
                    // The estimate already carries the aim term (engine-localised); a raw live point gets it here. A ground
                    // point carries its full Ground covariance instead (aim included), never an extra aim term.
                    val point = if (extra != null) {
                        val p = est?.point ?: UncertainPoint(chosen.position)
                        UncertainPoint(p.position, p.covariance + extra, includesLocalisation = true)
                    } else {
                        est?.point ?: loc?.let { UncertainPoint(chosen.position).withLocalisation(it) } ?: UncertainPoint(chosen.position)
                    }
                    EndpointData(
                        point, normal, chosen.kind, range, chosen.planeBiasRel, est?.spreadRms ?: 0.0, incidence,
                        loc?.focalLengthPx ?: 0.0, loc?.pixelErrorPx ?: 0.0,
                    )
                } else {
                    null
                }
                // W6 diagnostics: time to tracking, to the first usable point and to the first steady one.
                val sinceStart = sessionStartMs.let { if (it < 0) null else SystemClock.elapsedRealtime() - it }
                if (sinceStart != null) {
                    if (diag.trackingMs == null && gateResult.sampleQuality != TrackingQuality.NOT_TRACKING) diag = diag.copy(trackingMs = sinceStart)
                    if (diag.firstHitMs == null && chosen != null) diag = diag.copy(firstHitMs = sinceStart, firstHitKind = chosen.kind)
                    if (diag.firstLockMs == null && active.isLocked) diag = diag.copy(firstLockMs = sinceStart)
                }
                if (chosen != null) surfaceSeen = true
                // Only a full-quality frame may offer a commit (never during the post-loss warm-up).
                candidate = if (liveData != null && est != null && gateResult.sampleQuality == TrackingQuality.TRACKING) {
                    CommitCandidate(liveData, est.isStable, s.timestampNs, SystemClock.elapsedRealtimeNanos())
                } else {
                    null
                }

                val now = SystemClock.elapsedRealtime()
                val discreteChange = s.generation != lastGeneration || gateResult.status != lastStatus
                if (!discreteChange && now - lastPublishMs < PUBLISH_INTERVAL_MS) continue
                if (s.generation != generation) continue // the points changed since this frame: stale
                lastPublishMs = now
                lastGeneration = s.generation
                lastStatus = gateResult.status

                val complete = s.endpoints.size >= s.mode.requiredPoints
                val reading = runCatching {
                    MeasurementComposer.compose(s.mode, s.endpoints, if (complete) null else liveData, model)
                }.getOrNull()
                val tip = if (complete && reading != null) MeasurementComposer.tipFor(reading, s.endpoints, lowLightSeen) else null
                val stability = if (chosen == null) 0f else ((active.progress * 10).toInt() / 10f)
                val locked = active.isLocked
                // Merge only the worker-owned fields, and only while the points are still those of this frame.
                _state.update { cur ->
                    if (s.generation != generation) {
                        cur
                    } else {
                        cur.copy(
                            tracking = gateResult.status,
                            hasSurface = chosen != null,
                            surfaceKind = chosen?.kind,
                            targetDistanceCm = if (chosen != null) (range * 100).toInt() else null,
                            stability = stability,
                            locked = locked,
                            reading = reading,
                            tip = tip,
                            pointsTracking = s.pointsTracking,
                            lowLight = gateResult.lowLight,
                            aimTooSteep = tooSteep,
                            surfaceSeen = surfaceSeen,
                            diagnostics = diag,
                        )
                    }
                }
            } finally {
                // Only now are the sample's buffers free again (main may refill it on the next frame).
                s.depth = null
                s.hit = null
                samplePool.offer(s)
            }
        }
    }

    /**
     * W5: the aimed point of a height top (vertical plane through the base) or a box top (top-face hit, else the vertical
     * plane through the corner aimed above). Null when the mode / point count does not use it.
     */
    private fun chooseW5(s: FrameSample, ray: com.spaceview.engine.Ray, surface: LocalSurfaceModel): HeightAim.Top? = when {
        s.mode == MeasureMode.HEIGHT && s.endpoints.size == 1 -> HeightAim.top(s.endpoints[0], ray, s.cameraPose.forward)
        s.mode == MeasureMode.BOX && s.endpoints.size == 3 -> HeightAim.boxTop(
            s.endpoints, SurfaceChooser.choose(s.hit, surface, ray, s.cameraPose.translation, s.timestampNs), ray, s.cameraPose.forward,
        )
        else -> null
    }

    /** W5 far base: the floor under the crosshair (Ground range) when no surface was hit, for a height's first point. */
    private fun farBase(s: FrameSample, ray: com.spaceview.engine.Ray): ChosenPoint? {
        if (s.mode != MeasureMode.HEIGHT || s.endpoints.isNotEmpty()) return null
        val aim = AimModel.angularSigma(s.intrinsics, s.viewPxPerImagePx)
        return HeightAim.groundBase(ray, s.floorY, aim, model.effectiveScaleSigmaRel)
    }

    private fun decodeStill(still: StillCapture): Bitmap? = runCatching {
        val yuv = YuvImage(still.nv21, android.graphics.ImageFormat.NV21, still.width, still.height, null)
        val out = ByteArrayOutputStream(still.width * still.height / 2)
        yuv.compressToJpeg(Rect(0, 0, still.width, still.height), STILL_JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    // ---- types ------------------------------------------------------------------------------------

    /** A committed point. [anchor] is null once a pause detached it (the data is then frozen). */
    private class Endpoint(var anchor: Anchor?, var data: EndpointData, val auto: Boolean = false)

    private class FrozenCaptureState(
        val projection: ViewProjection,
        val cameraPosition: Vec3,
        val stillIntrinsics: ImageIntrinsics?,
        val viewPxPerImagePx: Double,
    )

    private class CommitCandidate(val data: EndpointData, val locked: Boolean, val timestampNs: Long, val wallNs: Long)

    /**
     * One frame's copied-out data, pooled (R3). Filled on main, read by the worker, then returned to
     * [samplePool]; main never touches it while it is queued or being processed.
     */
    private class FrameSample {
        var timestampNs = 0L
        var generation = 0
        var tracking = CameraTracking.STOPPED
        var reason = FailureReason.NONE
        var cameraPose: com.spaceview.engine.Pose = com.spaceview.engine.Pose(Vec3.ZERO)
        var light = Float.NaN
        var centreRay: com.spaceview.engine.Ray? = null
        var hit: HitSample? = null

        /** Point cloud, x, y, z per point; only the first [cloudCount] points are valid (0 = no new cloud). */
        val cloud = FloatArray(3 * MAX_CLOUD_POINTS)
        var cloudCount = 0
        var depth: DepthPatch? = null
        val endpoints = ArrayList<EndpointData>(4)
        var pointsTracking = true
        var intrinsics: ImageIntrinsics? = null
        var viewPxPerImagePx = Double.POSITIVE_INFINITY
        var mode = MeasureMode.DISTANCE
        var frozen = false
        /** The floor height (world y) under the camera, NaN when unknown (W5 far base). */
        var floorY = Double.NaN
        /** [sessionEpoch] when filled. */
        var epoch = 0
    }

    companion object {
        /** UI publish interval: ≤ 10 Hz. */
        const val PUBLISH_INTERVAL_MS = 100L

        /** How long "+" waits for a steady point. */
        const val COMMIT_TIMEOUT_MS = 2500L

        /** A plane anchor is used only for points within this distance of the plane (else a world anchor). */
        const val PLANE_ANCHOR_MAX_OFFSET_M = 0.01

        /** A frozen-frame adjustment may move the point at most this far along its surface. */
        const val MAX_ADJUST_M = 0.3

        private const val MIN_ADJUST_COS = 0.2
        private const val STALE_NS = 300_000_000L
        private const val NEAR_M = 0.05f
        private const val FAR_M = 30f
        private const val MAX_CLOUD_POINTS = 2048
        private const val MAX_DEPTH_POINTS = 1024
        private const val STILL_JPEG_QUALITY = 92

        /** Samples in the pool: 4 queued + 1 in the worker + 1 being filled, plus slack. */
        private const val SAMPLE_POOL = 8
        /** A hit counts as floor-like (W5 floor estimate) when its normal is within ~15° of up. */
        private val FLOOR_NORMAL_COS = kotlin.math.cos(Math.toRadians(15.0))
        /** ARCore's upward planes are offered to the floor estimate every this many frames (~1 s at 30 fps). */
        private const val FLOOR_PLANE_EVERY = 30
        /** W6 diagnostics count repeated SceneView frames over this long after the session starts. */
        private const val DIAG_REPEAT_WINDOW_MS = 10_000L
        /** "Same spot" window of a far ground base (W5): absolute part (m) ... */
        const val GROUND_MOVE_ABS_M = 0.05
        /** ... and relative to the range. */
        const val GROUND_MOVE_REL = 0.03

        /** True when the live point of [mode] with [points] committed comes from W5 aiming, not from the raw hit. */
        internal fun aimsW5(mode: MeasureMode, points: Int): Boolean =
            (mode == MeasureMode.HEIGHT && points == 1) || (mode == MeasureMode.BOX && points == 3)
    }
}
