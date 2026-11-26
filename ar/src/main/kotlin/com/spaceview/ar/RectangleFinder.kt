package com.spaceview.ar

import android.os.SystemClock
import androidx.annotation.MainThread
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.spaceview.engine.CaptureCamera
import com.spaceview.engine.Cov3
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Plane
import com.spaceview.engine.SupportPlane
import com.spaceview.engine.TrackingQuality
import com.spaceview.engine.UncertainPoint
import com.spaceview.engine.Vec3
import com.spaceview.engine.rect.GrayImage
import com.spaceview.engine.rect.QuadDetector
import com.spaceview.engine.rect.QuadLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/** What the rectangle finder tells the user (W9). */
enum class RectHint {
    /** Looking for a rectangle (none found yet, or it was lost). */
    SEARCHING,

    /** No surface under the crosshair yet: the rectangle cannot be lifted onto a plane. */
    NO_PLANE,

    /** Moving, blurred or refocusing: hold steady. */
    HOLD_STEADY,

    /** Viewed too obliquely for the lock (> 45°): face the object more. */
    TOO_STEEP,

    /** Found, holding: the outline is filling its ring. */
    HOLDING,
}

/**
 * Throttled state of the finder.
 *
 * @property outline the found quad in view pixels (x, y × 4), null when none; drawn by the HUD.
 * @property progress 0..1 steady frames towards the lock.
 */
class RectFinderState(val outline: FloatArray? = null, val progress: Float = 0f, val hint: RectHint = RectHint.SEARCHING) {
    override fun equals(other: Any?): Boolean =
        other is RectFinderState && progress == other.progress && hint == other.hint && outline.contentEquals(other.outline)
    override fun hashCode(): Int = (outline?.contentHashCode() ?: 0) * 31 + progress.hashCode() * 7 + hint.hashCode()
}

/**
 * W9 smart hands-free rectangles: an [ArFrameListener] (ADR-001) placed **after** [session] in the host's list. While
 * [enabled] and the session is in [MeasureMode.RECTANGLE] with no point placed, it runs the pure-Kotlin
 * [QuadDetector] on the CPU image at most every [DETECT_INTERVAL_MS] (one at a time, on its own worker; Apple's Vision
 * guidance: ≤ 10 Hz, serial), only on frames that pass the blur and focus gate ([BlurGate.MAX_BLUR_DETECT_PX]).
 * Candidates are lifted onto the plane under the crosshair (its σ from [SupportPlane]) and the first metric rectangle
 * goes to a [QuadLock]. When locked, its 4 fused corners are committed to the session
 * ([MeasureSession.commitDetectedRectangle]): the normal result card, Save, ± and badge follow.
 *
 * Main thread for the callbacks; detection on the worker. With [logTiming] (debug builds) every detection's time is
 * logged under [LOG_TAG].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RectangleFinder(private val session: MeasureSession, private val logTiming: Boolean = false) : ArFrameListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val detector = QuadDetector()
    private var lock = QuadLock(DeviceTier.NO_DEPTH)
    private val gate = TrackingGate()
    private var lastTs = Long.MIN_VALUE
    private var lastDetectMs = 0L
    private var busy = false
    private var closed = false
    private var committed = false

    private val _state = MutableStateFlow(RectFinderState())
    val state: StateFlow<RectFinderState> = _state.asStateFlow()

    /** Hands-free rectangle mode is on (UI switch); off clears everything. */
    @Volatile var enabled: Boolean = false
        @MainThread set(value) {
            if (field == value) return
            field = value
            restart()
        }

    /** Starts a fresh search (after the measurement was cleared or saved). */
    @MainThread
    fun restart() {
        lock = QuadLock(session.state.value.capabilities?.tier ?: DeviceTier.NO_DEPTH)
        committed = false
        _state.value = RectFinderState()
    }

    override fun onSessionCreated(session: Session) = restartIfOpen()
    override fun onSessionPaused(session: Session) = restartIfOpen()

    private fun restartIfOpen() {
        if (!closed) restart()
    }

    override fun onFrame(session: Session, frame: Frame) {
        if (closed || !enabled || frame.timestamp == lastTs) return
        lastTs = frame.timestamp
        val st = this.session.state.value
        if (st.mode != MeasureMode.RECTANGLE || st.pointCount > 0 || st.isFrozen) {
            if (st.pointCount == 0 && committed) restart() // the result was cleared or saved: look again
            if (_state.value.outline != null && st.pointCount > 0) _state.value = RectFinderState()
            return
        }
        val camera = frame.camera
        val tracking = FrameReaders.tracking(camera.trackingState)
        val pose = camera.pose.toEngine()
        val hit = this.session.latestCentreHit
        val optics = FrameReaders.optics(frame)
        val g = gate.update(frame.timestamp, tracking, FrameReaders.reason(camera.trackingFailureReason), pose, Float.NaN, optics, hit?.distance ?: Double.NaN)
        drawOutline()
        if (tracking != CameraTracking.TRACKING) return
        val normal = hit?.normal
        if (hit == null || normal == null) return publishHint(RectHint.NO_PLANE)
        val blurOk = optics == null || (!optics.lensMoving && !(g.blurPx > BlurGate.MAX_BLUR_DETECT_PX))
        if (g.sampleQuality != TrackingQuality.TRACKING || !blurOk) return publishHint(RectHint.HOLD_STEADY)
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastDetectMs < DETECT_INTERVAL_MS) return
        val image = FrameReaders.cpuImage(frame) ?: return
        val cam = FrameReaders.cpuImageCamera(frame)?.scaledTo(image.width, image.height) ?: return
        val capture = CaptureCamera(pose, cam.intrinsics, image.width, image.height)
        val plane = Plane.fromPointNormal(hit.position, normal)
        val planeSigma = SupportPlane.planeSigma(hit.distance)
        lastDetectMs = now
        busy = true
        val l = lock
        scope.launch {
            val found = withContext(worker) {
                runCatching {
                    val t0 = System.nanoTime()
                    val gray = GrayImage.fromLuma(image.bytes, image.width, image.height)
                    val q = QuadLock.detectRectangle(detector, gray, capture, plane)
                    if (logTiming) {
                        android.util.Log.d(LOG_TAG, "detect ${image.width}x${image.height}: ${(System.nanoTime() - t0) / 1_000_000} ms, found=${q != null}")
                    }
                    q
                }.getOrNull()
            }
            busy = false
            if (closed || l !== lock || !enabled) return@launch
            if (found == null) return@launch publishHint(RectHint.SEARCHING)
            val locked = l.offer(found.corners, found.sigmaPx.max(), capture, plane, planeSigma)
            publishHint(if (l.lastReject == QuadLock.Reject.TOO_STEEP) RectHint.TOO_STEEP else RectHint.HOLDING)
            if (locked != null && !committed) commit(locked, capture, plane, planeSigma, found.sigmaPx.max())
        }
    }

    private fun commit(locked: QuadLock.Locked, camera: CaptureCamera, plane: Plane, planeSigma: Double, sigmaPx: Double) {
        val f = kotlin.math.sqrt(camera.intrinsics.fx * camera.intrinsics.fy)
        val origin = camera.pose.translation
        val ends = locked.corners.map { c ->
            val range = origin.distanceTo(c)
            val cos = max(0.2, abs(plane.normal dot (c - origin).normalized()))
            // Corner placement (sub-pixel line intersection, never below 0.3 px) at its range, widened by the obliquity.
            val sigma = max(sigmaPx, MIN_CORNER_SIGMA_PX) * range / f / cos
            EndpointData(
                point = UncertainPoint(c, Cov3.isotropic(sigma), includesLocalisation = true),
                normal = plane.normal,
                kind = HitKind.DETECTED_CORNER,
                rangeM = range,
                planeBiasRel = planeSigma / range,
            )
        }
        committed = session.commitDetectedRectangle(ends)
        if (committed) _state.value = RectFinderState()
    }

    private fun drawOutline() {
        val corners = lock.locked?.corners ?: lock.lastCorners
        val vp = session.overlay.projection
        val outline = if (corners != null && vp != null) {
            val pts = corners.map { vp.project(it) ?: return publishOutline(null) }
            FloatArray(8) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y }
        } else {
            null
        }
        publishOutline(outline)
    }

    private fun publishOutline(outline: FloatArray?) {
        val cur = _state.value
        val p = ((lock.progress * 10).toInt() / 10f)
        val next = RectFinderState(outline, p, cur.hint)
        if (next != cur) _state.value = next
    }

    private fun publishHint(h: RectHint) {
        val cur = _state.value
        if (cur.hint != h) _state.value = RectFinderState(cur.outline, cur.progress, h)
    }

    /** Releases the worker. Idempotent. */
    @MainThread
    fun close() {
        closed = true
        scope.cancel()
    }

    companion object {
        /** Logcat tag of the per-detection timing (debug builds). */
        const val LOG_TAG = "SpaceViewRect"

        /** At most one detection per this interval (≤ 10 Hz). */
        const val DETECT_INTERVAL_MS = 100L

        /** Floor of a detected corner's σ (px) when lifting: bench-tunable. */
        const val MIN_CORNER_SIGMA_PX = 0.3
    }
}
