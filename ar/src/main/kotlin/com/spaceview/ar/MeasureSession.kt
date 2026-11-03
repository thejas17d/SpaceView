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
import com.spaceview.engine.LocalisationInput
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
) {
    val requiredPoints: Int get() = mode.requiredPoints
}

/** Why "+" did not place a point. */
enum class CommitFailure { NOT_TRACKING, NO_SURFACE, NOT_STEADY, SAME_SPOT }

/** One-off events (sounds, snackbars). */
sealed class MeasureEvent {
    data class PointAdded(val index: Int, val complete: Boolean) : MeasureEvent()
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
 * Threading: all public functions and the SceneView callbacks run on the **main** thread (where
 * SceneView's frame loop runs). Per frame the main thread only does the native reads (hit test,
 * point cloud, raw depth window), copies the data out and releases it; gating, averaging, plane
 * fitting and the uncertainty maths run on a single background worker. UI state is published at
 * most every [PUBLISH_INTERVAL_MS].
 *
 * @param depthWanted user setting: use the Depth API when supported (off by default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeasureSession(
    initialMode: MeasureMode,
    private val depthWanted: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val frames = Channel<FrameSample>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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

    // ---- main-thread state ------------------------------------------------------------------
    private var session: Session? = null
    @Volatile private var capabilities: ArCapabilities? = null
    private var mode = initialMode
    private val endpoints = ArrayList<Endpoint>(4)
    private var viewW = 0
    private var viewH = 0
    private var lastPointCloudTs = -1L
    private val cloudBuffer = FloatArray(3 * MAX_CLOUD_POINTS)
    private var lastPlaneHit: ArHit? = null
    private var commitRequestedAtMs = -1L
    private var freezeRequested = false
    private var frozenCapture: FrozenCaptureState? = null
    private var pendingAdjust: Vec3? = null
    private var lastFocalPx = FrameReaders.DEFAULT_FOCAL_PX
    private var lastTracking = CameraTracking.STOPPED
    private var closed = false

    @Volatile private var model: UncertaintyModel = UncertaintyModel(DeviceTier.NO_DEPTH)
    @Volatile private var calibration: ScaleCalibration? = null
    @Volatile private var calibrationFloorRel = UncertaintyModel.DEFAULT_CALIBRATED_FLOOR_REL

    /** Latest commit candidate published by the worker. */
    @Volatile private var candidate: CommitCandidate? = null

    init {
        scope.launch(worker) { runWorker() }
    }

    // ---- configuration ------------------------------------------------------------------------

    /**
     * Calibration to apply to readings (null = none). [floorRel] is the residual relative scale σ
     * that calibration cannot remove; pass a larger value for a calibration from an earlier session.
     */
    fun setCalibration(c: ScaleCalibration?, floorRel: Double = UncertaintyModel.DEFAULT_CALIBRATED_FLOOR_REL) {
        calibration = c
        calibrationFloorRel = floorRel
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
    }

    // ---- SceneView callbacks ----------------------------------------------------------------------

    /** `sessionCameraConfig` selector. */
    fun selectCameraConfig(session: Session): CameraConfig = SessionSetup.selectCameraConfig(session, depthWanted)

    /** `sessionConfiguration` callback. */
    fun configureSession(session: Session, config: Config) {
        val caps = SessionSetup.detect(session, depthWanted)
        capabilities = caps
        SessionSetup.configure(config, caps.depthActive)
        rebuildModel()
        _state.update { it.copy(capabilities = caps) }
    }

    @MainThread
    fun onSessionCreated(session: Session) {
        this.session = session
    }

    @MainThread
    fun onSessionResumed(session: Session) {
        this.session = session
        _state.update { it.copy(sessionRunning = true) }
    }

    /** Also called by SceneView right before it closes the session. */
    @MainThread
    fun onSessionPaused(session: Session) {
        commitRequestedAtMs = -1L
        freezeRequested = false
        _state.update { it.copy(sessionRunning = false, pendingCommit = false) }
    }

    /** `onSessionUpdated`: once per ARCore frame, main thread. */
    @MainThread
    fun onFrame(session: Session, frame: Frame) {
        if (closed || viewW <= 0 || viewH <= 0) return
        this.session = session
        val camera = frame.camera
        val tracking = FrameReaders.tracking(camera.trackingState)
        lastTracking = tracking
        val reason = FrameReaders.reason(camera.trackingFailureReason)
        val viewM = FloatArray(16)
        val projM = FloatArray(16)
        camera.getViewMatrix(viewM, 0)
        camera.getProjectionMatrix(projM, 0, NEAR_M, FAR_M)
        val vp = ViewProjection(viewM, projM, viewW, viewH)
        val centreX = viewW / 2f
        val centreY = viewH / 2f
        val light = frame.lightEstimate.let { if (it.state == com.google.ar.core.LightEstimate.State.VALID) it.pixelIntensity else Float.NaN }

        var hit: ArHit? = null
        var cloudCount = 0
        var cloud: FloatArray? = null
        var depth: DepthPatch? = null
        if (tracking == CameraTracking.TRACKING) {
            // Focal length in *view* pixels (the hit-test resolution): P[1][1] = 2·fy / H.
            val fyView = projM[5].toDouble() * viewH / 2.0
            if (fyView.isFinite() && fyView > 0) lastFocalPx = fyView
            hit = FrameReaders.centreHit(frame, centreX, centreY)
            if (hit?.kind == HitKind.PLANE) lastPlaneHit = hit
            FrameReaders.pointCloud(frame, lastPointCloudTs, cloudBuffer)?.let { (n, ts) ->
                lastPointCloudTs = ts
                cloudCount = n
                cloud = cloudBuffer.copyOf(n * 3)
            }
            if (capabilities?.depthActive == true) depth = FrameReaders.depthPatch(frame, centreX, centreY)
        }

        // Anchor refinement: read the current pose of every committed point.
        var allTracking = true
        for (e in endpoints) {
            if (e.anchor.trackingState == TrackingState.TRACKING) {
                e.data = e.data.movedTo(e.anchor.pose.position())
            } else {
                allTracking = false
            }
        }

        processCommitRequest(session, tracking)
        if (freezeRequested) captureFreeze(frame, vp, tracking)

        val sample = FrameSample(
            timestampNs = frame.timestamp,
            tracking = tracking,
            reason = reason,
            cameraPose = camera.pose.toEngine(),
            light = light,
            centreRay = vp.centreRay(),
            hit = hit?.let { HitData(it.position, it.normal, it.kind, it.distance) },
            cloud = cloud,
            cloudCount = cloudCount,
            depth = depth,
            endpoints = endpoints.map { it.data },
            pointsTracking = allTracking,
            focalPx = lastFocalPx,
            mode = mode,
            frozen = frozenCapture != null,
            pendingCommit = commitRequestedAtMs >= 0,
        )
        frames.trySend(sample)

        overlay.projection = vp
        overlay.points.clear()
        for (e in endpoints) overlay.points += e.data.point.position
        overlay.live = if (endpoints.size < mode.requiredPoints && tracking == CameraTracking.TRACKING) {
            candidate?.takeIf { frame.timestamp - it.timestampNs < STALE_NS }?.data?.point?.position ?: hit?.position
        } else {
            null
        }
        overlay.pointsVisible = allTracking && tracking == CameraTracking.TRACKING
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
        if (frozenCapture != null) return
        if (endpoints.size >= mode.requiredPoints) clear()
        if (lastTracking != CameraTracking.TRACKING) {
            _events.tryEmit(MeasureEvent.CommitRejected(CommitFailure.NOT_TRACKING))
            return
        }
        commitRequestedAtMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(pendingCommit = true) }
    }

    /** Removes the last point. */
    @MainThread
    fun undo() {
        if (frozenCapture != null) return
        commitRequestedAtMs = -1L
        endpoints.removeLastOrNull()?.anchor?.detach()
        _state.update { it.copy(pointCount = endpoints.size, isComplete = false, tip = null, pendingCommit = false) }
    }

    /** Removes all points. */
    @MainThread
    fun clear() {
        commitRequestedAtMs = -1L
        for (e in endpoints) e.anchor.detach()
        endpoints.clear()
        _state.update { it.copy(pointCount = 0, isComplete = false, tip = null, reading = null, pendingCommit = false) }
    }

    /** Committed endpoints (for saving). */
    @MainThread
    fun committedPoints(): List<EndpointData> = endpoints.map { it.data }

    /** Final reading recomputed now from the current anchor poses (call at save time). */
    @MainThread
    fun finalReading(): Reading? {
        if (endpoints.size < mode.requiredPoints) return null
        return runCatching { MeasurementComposer.compose(mode, endpoints.map { it.data }, null, model) }.getOrNull()
    }

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

    /** Freezes the current camera frame so the last point can be adjusted with the magnifier. */
    @MainThread
    fun freeze() {
        if (endpoints.isEmpty() || frozenCapture != null) {
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
        val adjusted = adjustedEndpoint(last.data, p, cap.cameraPosition)
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
        if (apply && cap != null && p != null && s != null && endpoints.isNotEmpty()) {
            val last = endpoints.last()
            val anchor = runCatching { s.createAnchor(translationPose(p)) }.getOrNull()
            if (anchor != null) {
                last.anchor.detach()
                endpoints[endpoints.size - 1] = Endpoint(anchor, adjustedEndpoint(last.data, p, cap.cameraPosition))
            }
        }
        frozenCapture = null
        pendingAdjust = null
        _frozen.value = null
        _state.update { it.copy(isFrozen = false, pointCount = endpoints.size) }
    }

    /** Releases everything. Call when the AR screen leaves composition. Idempotent. */
    @MainThread
    fun close() {
        if (closed) return
        closed = true
        for (e in endpoints) runCatching { e.anchor.detach() }
        endpoints.clear()
        session = null
        lastPlaneHit = null
        frozenCapture = null
        _frozen.value = null
        onOverlayChanged = null
        frames.close()
        scope.cancel()
    }

    // ---- internals (main thread) ---------------------------------------------------------------

    private fun processCommitRequest(session: Session, tracking: CameraTracking) {
        val requested = commitRequestedAtMs
        if (requested < 0) return
        val now = SystemClock.elapsedRealtime()
        val c = candidate
        val fresh = c != null && SystemClock.elapsedRealtimeNanos() - c.wallNs < STALE_NS
        if (tracking == CameraTracking.TRACKING && fresh && c.locked) {
            val data = c.data
            if (endpoints.any { it.data.point.position.distanceTo(data.point.position) < MeasurementComposer.MIN_LENGTH_M }) {
                finishCommit(CommitFailure.SAME_SPOT)
                return
            }
            val anchor = createAnchor(session, data) ?: run { finishCommit(CommitFailure.NOT_TRACKING); return }
            endpoints += Endpoint(anchor, data)
            commitRequestedAtMs = -1L
            val complete = endpoints.size >= mode.requiredPoints
            _state.update { it.copy(pendingCommit = false, pointCount = endpoints.size, isComplete = complete) }
            _events.tryEmit(MeasureEvent.PointAdded(endpoints.size - 1, complete))
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

    /** Plane-attached anchor when the point is on the tracked plane, else a world anchor. */
    private fun createAnchor(session: Session, data: EndpointData): Anchor? {
        val pose = translationPose(data.point.position)
        val plane = lastPlaneHit?.trackable
        if (data.kind == HitKind.PLANE && plane is com.google.ar.core.Plane &&
            plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null &&
            runCatching { plane.isPoseInPolygon(pose) }.getOrDefault(false)
        ) {
            runCatching { return plane.createAnchor(pose) }
        }
        return runCatching { session.createAnchor(pose) }.getOrNull()
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
        val cap = FrozenCaptureState(vp, frame.camera.pose.position())
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

    private fun adjustedEndpoint(original: EndpointData, p: Vec3, cameraPosition: Vec3): EndpointData {
        val range = cameraPosition.distanceTo(p)
        // Conservative: the original aim term stays and the loupe placement error is added on top.
        val sigma = LocalisationInput(range, lastFocalPx, ADJUST_PIXELS).sigmaMeters
        return EndpointData(
            point = original.point.movedTo(p).withLocalisationSigma(sigma),
            normal = original.normal,
            kind = HitKind.ADJUSTED,
            rangeM = range,
            planeBiasRel = original.planeBiasRel,
            spreadRms = original.spreadRms,
            incidenceCos = original.incidenceCos,
            focalPx = lastFocalPx,
            pixelErrorPx = ADJUST_PIXELS,
        )
    }

    private fun rebuildModel() {
        val tier = capabilities?.tier ?: DeviceTier.NO_DEPTH
        model = UncertaintyModel(tier, calibration, calibratedScaleFloorRel = calibrationFloorRel)
    }

    // ---- worker -----------------------------------------------------------------------------------

    private suspend fun runWorker() {
        val gate = TrackingGate()
        val stabilizer = SampleStabilizer()
        val surface = LocalSurfaceModel()
        val depthPoints = FloatArray(3 * MAX_DEPTH_POINTS)
        var lastPublishMs = 0L
        var lastPointCount = -1
        var lastStatus: TrackingStatus? = null
        var lowLightSeen = false

        for (s in frames) {
            val gateResult = gate.update(s.timestampNs, s.tracking, s.reason, s.cameraPose, s.light)
            if (s.endpoints.isEmpty()) lowLightSeen = false
            if (gateResult.lowLight) lowLightSeen = true

            // Raw 3-D points for our own plane fit.
            val cloud = s.cloud
            if (cloud != null && s.cloudCount > 0) surface.addPoints(cloud, s.cloudCount, s.timestampNs)
            s.depth?.let { patch ->
                val n = patch.toWorldPoints(depthPoints)
                if (n > 0) surface.addPoints(depthPoints, n, s.timestampNs)
            }

            val ray = s.centreRay
            var livePoint: Vec3? = null
            var normal: Vec3? = null
            var kind: HitKind? = null
            var range = 0.0
            var planeBias = 0.0
            if (gateResult.sampleQuality != TrackingQuality.NOT_TRACKING && ray != null) {
                val hit = s.hit
                if (hit != null && hit.kind == HitKind.PLANE) {
                    livePoint = hit.position; normal = hit.normal; kind = HitKind.PLANE; range = hit.distance
                } else {
                    surface.update(ray, s.timestampNs)
                    val fitted = surface.intersect(ray, s.cameraPose.translation)
                    if (fitted != null) {
                        livePoint = fitted.position; normal = fitted.normal; kind = HitKind.FITTED_SURFACE
                        range = fitted.range; planeBias = fitted.planeBiasRel
                    } else if (hit != null) {
                        livePoint = hit.position; normal = hit.normal; kind = hit.kind; range = hit.distance
                    }
                }
            }
            val loc = if (range > 0 && s.focalPx > 0) LocalisationInput(range, s.focalPx, AIM_PIXELS) else null
            if (!s.frozen) {
                // While the user works on a frozen still, tracking stays fresh but nothing is averaged.
                stabilizer.add(livePoint, range, gateResult.sampleQuality, loc)
            }

            val est = stabilizer.estimate
            val liveData: EndpointData? = if (livePoint != null && kind != null) {
                val incidence = if (normal != null && ray != null) kotlin.math.abs(normal dot ray.direction) else null
                // The estimate already carries the aim term (engine-localised); a raw live point gets it here.
                val point = est?.point ?: loc?.let { UncertainPoint(livePoint).withLocalisation(it) } ?: UncertainPoint(livePoint)
                EndpointData(point, normal, kind, range, planeBias, est?.spreadRms ?: 0.0, incidence, s.focalPx, AIM_PIXELS)
            } else {
                null
            }
            candidate = if (liveData != null && est != null) {
                CommitCandidate(liveData, est.isStable, s.timestampNs, SystemClock.elapsedRealtimeNanos())
            } else {
                null
            }

            val now = SystemClock.elapsedRealtime()
            val discreteChange = s.endpoints.size != lastPointCount || gateResult.status != lastStatus
            if (!discreteChange && now - lastPublishMs < PUBLISH_INTERVAL_MS) continue
            lastPublishMs = now
            lastPointCount = s.endpoints.size
            lastStatus = gateResult.status

            val complete = s.endpoints.size >= s.mode.requiredPoints
            val reading = runCatching {
                MeasurementComposer.compose(s.mode, s.endpoints, if (complete) null else liveData, model)
            }.getOrNull()
            val tip = if (complete && reading != null) MeasurementComposer.tipFor(reading, s.endpoints, lowLightSeen) else null
            val next = MeasureState(
                mode = s.mode,
                sessionRunning = true,
                tracking = gateResult.status,
                hasSurface = livePoint != null,
                surfaceKind = kind,
                targetDistanceCm = if (livePoint != null) (range * 100).toInt() else null,
                stability = if (livePoint == null) 0f else ((stabilizer.progress * 10).toInt() / 10f),
                locked = stabilizer.isLocked,
                pointCount = s.endpoints.size,
                reading = reading,
                isComplete = complete,
                tip = tip,
                pointsTracking = s.pointsTracking,
                lowLight = gateResult.lowLight,
                capabilities = capabilities,
                isFrozen = s.frozen,
                pendingCommit = s.pendingCommit,
            )
            _state.value = next
        }
    }

    private fun decodeStill(still: StillCapture): Bitmap? = runCatching {
        val yuv = YuvImage(still.nv21, android.graphics.ImageFormat.NV21, still.width, still.height, null)
        val out = ByteArrayOutputStream(still.width * still.height / 2)
        yuv.compressToJpeg(Rect(0, 0, still.width, still.height), STILL_JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    // ---- types ------------------------------------------------------------------------------------

    private class Endpoint(val anchor: Anchor, var data: EndpointData)

    private class FrozenCaptureState(val projection: ViewProjection, val cameraPosition: Vec3)

    private class HitData(val position: Vec3, val normal: Vec3?, val kind: HitKind, val distance: Double)

    private class CommitCandidate(val data: EndpointData, val locked: Boolean, val timestampNs: Long, val wallNs: Long)

    private class FrameSample(
        val timestampNs: Long,
        val tracking: CameraTracking,
        val reason: FailureReason,
        val cameraPose: com.spaceview.engine.Pose,
        val light: Float,
        val centreRay: com.spaceview.engine.Ray?,
        val hit: HitData?,
        val cloud: FloatArray?,
        val cloudCount: Int,
        val depth: DepthPatch?,
        val endpoints: List<EndpointData>,
        val pointsTracking: Boolean,
        val focalPx: Double,
        val mode: MeasureMode,
        val frozen: Boolean,
        val pendingCommit: Boolean,
    )

    companion object {
        /** UI publish interval: ≤ 10 Hz. */
        const val PUBLISH_INTERVAL_MS = 100L

        /** How long "+" waits for a steady point. */
        const val COMMIT_TIMEOUT_MS = 2500L

        /** Aiming error of the centre crosshair, in view pixels (placeholder to tune). */
        const val AIM_PIXELS = 2.0

        /** Placement error on a 2× magnified frozen frame, in view pixels (placeholder to tune). */
        const val ADJUST_PIXELS = 1.0

        /** A frozen-frame adjustment may move the point at most this far along its surface. */
        const val MAX_ADJUST_M = 0.3

        private const val MIN_ADJUST_COS = 0.2
        private const val STALE_NS = 300_000_000L
        private const val NEAR_M = 0.05f
        private const val FAR_M = 30f
        private const val MAX_CLOUD_POINTS = 2048
        private const val MAX_DEPTH_POINTS = 1024
        private const val STILL_JPEG_QUALITY = 92
    }
}
