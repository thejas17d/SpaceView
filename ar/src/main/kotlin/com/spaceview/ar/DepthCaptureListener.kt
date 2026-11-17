package com.spaceview.ar

import android.graphics.ImageFormat
import android.media.Image
import android.os.SystemClock
import android.os.Trace
import androidx.annotation.MainThread
import com.google.ar.core.CameraConfig
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.spaceview.engine.CameraIntrinsics
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import com.spaceview.engine.recon.DepthSource
import com.spaceview.engine.recon.TsdfVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why a 3D-scan capture stopped. */
enum class CaptureStop { USER, TIME_CAP, THERMAL_SEVERE, SESSION_PAUSED, SCREEN_LEFT }

/**
 * What the capture screen shows (published at most [DepthCaptureListener.PUBLISH_INTERVAL_MS]).
 *
 * @property source the depth source in use (null: this phone or session has no depth).
 * @property observedFraction share of the volume's voxels observed so far (a coarse coverage figure).
 * @property thermal the last thermal status passed to [DepthCaptureListener.onThermal].
 */
data class CaptureProgress(
    val running: Boolean = false,
    val elapsedMs: Long = 0,
    val integrated: Int = 0,
    val rateHz: Int = 0,
    val source: DepthSource? = null,
    val depthModeOn: Boolean = false,
    val tracking: Boolean = false,
    val observedFraction: Double = 0.0,
    val lastFusionMs: Double = 0.0,
    val thermal: Int = 0,
    val stop: CaptureStop? = null,
)

/** Per-capture counters (main-thread writes; read after [DepthCaptureListener.finish]). */
class CaptureCounters {
    var framesSeen = 0
    var repeatedFrames = 0
    var notTracking = 0
    var blurSkipped = 0
    var sameView = 0
    var noDepthYet = 0
    var repeatedDepth = 0
    var staleDepth = 0
    var depthErrors = 0
    var noConfidence = 0
    var sizeMismatch = 0
    var backlog = 0
    var handedOff = 0
    var colourFrames = 0
    var colourMisses = 0
}

/**
 * The 3D-scan capture as an [ArFrameListener] on the shared session (S1; ARCHITECTURE-v2 §7.1.3, §7.1.6, §7.1.9):
 * the main-thread depth copy-out ("DepthFrameTap") plus the fusion worker. It never configures the session: list it
 * after a `MeasureSession(…, depthWanted = true)`, which turns the Depth API on.
 *
 * - **Depth source:** RAW (raw depth + confidence) when `isDepthModeSupported(RAW_DEPTH_ONLY)`, HARDWARE when the
 *   camera config uses a ToF sensor, otherwise SMOOTHED (`acquireDepthImage16Bits`, confidence filled with 255, half
 *   weight). If raw images keep failing before the first one arrives, it falls back to SMOOTHED once.
 * - **Copy-out (main):** only while capturing, tracking, at the [ShapeScanGovernor]'s rate, for a sharp new viewpoint
 *   ([ViewGate]), and only a depth image whose timestamp is this frame's (an older image would not match the camera
 *   pose; the same rule as `FrameReaders.depthPatch`). Pose = `Camera.getPose()` (the physical camera, not the
 *   display-oriented pose); intrinsics = texture intrinsics scaled to the depth resolution; colour = the CPU image
 *   decimated to 2× the depth size. Two pooled [DepthSlot]s, allocated once at the first depth image; a repeated
 *   frame timestamp returns before touching any state.
 * - **Fusion:** [TsdfVolume.integrate] on one dedicated worker (`limitedParallelism(1)`), never on main or GL. The
 *   worker gives a slot back after integrating; when both are still busy at the next copy, that is a backlog and the
 *   governor steps down.
 * - **Stops:** [stop] (user), the [MAX_CAPTURE_NS] cap (90 s), thermal SEVERE, a session pause, or [detach]. After a
 *   stop, [finish] waits for the worker, so the caller may then use the volume on another thread.
 *
 * Per AR frame it allocates nothing of its own (ARCore's `Camera.getPose()` allocates); per integrated frame (≤ 5 Hz)
 * it allocates the frame's `Pose` and intrinsics objects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DepthCaptureListener(
    private val clockNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ArFrameListener {
    override val requirements = ArRequirements(depth = true, lights = false, planeRenderer = true)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private val _progress = MutableStateFlow(CaptureProgress())
    val progress: StateFlow<CaptureProgress> = _progress.asStateFlow()

    // ---- results (read after finish()) ----
    val counters = CaptureCounters()
    val governor = ShapeScanGovernor()

    /** Fusion time per integrated frame (worker). */
    val fusionNs = NanoSamples(MAX_INTEGRATIONS)

    /** Main-thread copy-out time per handed-off frame (§7.1.3 budget ≤ 1.5 ms). */
    val copyNs = NanoSamples(MAX_INTEGRATIONS)

    /** Camera-timestamp interval between consecutive new AR frames during capture. */
    val cameraIntervalNs = NanoSamples(MAX_FRAMES)

    /** Wall-clock interval between consecutive new AR frames reaching this listener during capture. */
    val updateIntervalNs = NanoSamples(MAX_FRAMES)

    /** The depth source used for the last handed-off frame. */
    var source: DepthSource? = null
        private set

    /** True when raw depth failed and the capture fell back to smoothed depth. */
    var sourceFellBack = false
        private set

    var depthWidth = 0
        private set
    var depthHeight = 0
        private set

    /** The camera texture size the depth intrinsics were scaled from (review MINOR 7); 0 until the first frame. */
    var textureWidth = 0
        private set
    var textureHeight = 0
        private set

    /** The CPU image size the colour was decimated from; 0 until the first colour frame. */
    var cpuWidth = 0
        private set
    var cpuHeight = 0
        private set

    // ---- main-thread state ----
    private var rawSupported = false
    private var depthSupported = false
    private var hardware = false
    private var depthModeOn = false
    private var volume: TsdfVolume? = null
    private var ring: DepthSlotRing? = null
    private var channel: Channel<DepthSlot>? = null
    private var job: Job? = null
    private var running = false
    private var stopReason: CaptureStop? = null
    private var startNs = 0L
    private var lastFrameTs = Long.MIN_VALUE
    private var lastFrameWallNs = Long.MIN_VALUE
    private var lastDepthTs = Long.MIN_VALUE
    private var rawFailures = 0
    private var rawSuccesses = 0
    private var lastPublishMs = 0L
    private var thermal = 0
    private var tracking = false
    private val gate = ViewGate()
    private val t = FloatArray(3)
    private val q = FloatArray(4)

    // ---- worker → main (volatile) ----
    @Volatile private var integrated = 0
    @Volatile private var observed = 0.0
    @Volatile private var lastFusionNs = 0L

    // ---- session callbacks ----

    @MainThread
    override fun onSessionCreated(session: Session) {
        rawSupported = runCatching { session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) }.getOrDefault(false)
        depthSupported = runCatching { session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
        if (running) stop(CaptureStop.SESSION_PAUSED) // a new world frame: the volume no longer matches
    }

    @MainThread
    override fun onSessionResumed(session: Session) {
        hardware = runCatching { session.cameraConfig.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE }.getOrDefault(false)
        // The governor's dropped-frame rule is relative to the locked camera rate (30 fps unless the config says otherwise).
        runCatching { session.cameraConfig.fpsRange.upper }.getOrNull()?.takeIf { it in 1..240 }?.let { fps ->
            // Clamped to 30 fps (gate G4): a 60 fps or variable range must not make the dropped-frame rule stricter.
            if (job == null) governor.framePeriodNs = 1_000_000_000L / minOf(fps, 30)
        }
        depthModeOn = runCatching { session.config.depthMode != Config.DepthMode.DISABLED }.getOrDefault(false)
        if (source == null) source = plannedSource()
        publish(force = true)
    }

    @MainThread
    override fun onSessionPaused(session: Session) {
        // §7.1.6: the fusion is never continued across a pause (the world frame after resume may not match).
        if (running) stop(CaptureStop.SESSION_PAUSED)
        tracking = false
        publish(force = true)
    }

    /** The source this session will use (before any fallback). */
    private fun plannedSource(): DepthSource? = when {
        !depthSupported -> null
        hardware -> DepthSource.HARDWARE
        rawSupported -> DepthSource.RAW
        else -> DepthSource.SMOOTHED
    }

    // ---- commands (main thread) ----

    /**
     * Starts fusing into [volume] (allocated by the caller, in the measured box's world frame). False when already
     * started once (one listener per capture) or this session has no depth.
     */
    @MainThread
    fun start(volume: TsdfVolume): Boolean {
        if (job != null || plannedSource() == null && source == null) return false
        // Already SEVERE (reported before start): refuse instead of a capture that can never integrate (review MINOR 5).
        if (governor.stopped) return false
        this.volume = volume
        val ch = Channel<DepthSlot>(capacity = SLOTS)
        channel = ch
        running = true
        stopReason = null
        startNs = clockNs()
        lastFrameTs = Long.MIN_VALUE
        lastFrameWallNs = Long.MIN_VALUE
        gate.reset()
        job = scope.launch(worker) { fuse(ch, volume) }
        publish(force = true)
        return true
    }

    /** Stops capturing (the worker finishes the frames it already has). Idempotent. */
    @MainThread
    fun stop(reason: CaptureStop) {
        if (!running) return
        running = false
        stopReason = reason
        stopNs = clockNs()
        channel?.close()
        publish(force = true)
    }

    /** The thermal status changed (`PowerManager.THERMAL_STATUS_*`); SEVERE stops the capture. */
    @MainThread
    fun onThermal(status: Int) {
        thermal = status
        governor.onThermal(status, clockNs())
        if (governor.stopped) stop(CaptureStop.THERMAL_SEVERE)
        publish(force = true)
    }

    /** The AR screen is going away: stop capturing. Never touches ARCore. */
    @MainThread
    fun detach() {
        stop(CaptureStop.SCREEN_LEFT)
    }

    /**
     * Waits until the fusion worker has integrated its last frame and lets go of the volume. After this returns the
     * caller owns the volume exclusively (happens-before through the join). Call after [stop].
     */
    suspend fun finish() {
        job?.join()
        volume = null
    }

    /** Why the capture stopped, or null while running / never started. */
    val stop: CaptureStop? get() = stopReason

    /** When [start] ran, on the capture clock (the clock of the governor's event times); 0 before. */
    val startedAtNs: Long get() = startNs

    /** Capture duration so far (ms). */
    fun elapsedMs(): Long = if (startNs == 0L) 0 else ((if (running) clockNs() else stopNs) - startNs) / 1_000_000

    private var stopNs = 0L

    // ---- per frame (main thread) ----

    @MainThread
    override fun onFrame(session: Session, frame: Frame) {
        val ts = frame.timestamp
        if (!running) {
            if (ts != lastFrameTs) {
                lastFrameTs = ts
                val camTracking = frame.camera.trackingState == TrackingState.TRACKING
                if (camTracking != tracking) {
                    tracking = camTracking
                    publish(force = true)
                }
            }
            return
        }
        // SceneView repeats the camera frame at display rate: a repeated timestamp changes nothing (M3 B1).
        if (ts == lastFrameTs) {
            counters.repeatedFrames++
            return
        }
        val now = clockNs()
        if (lastFrameTs != Long.MIN_VALUE) {
            val dt = ts - lastFrameTs
            cameraIntervalNs.add(dt)
            updateIntervalNs.add(now - lastFrameWallNs)
            governor.onFrame(now, dt)
        }
        lastFrameTs = ts
        lastFrameWallNs = now
        counters.framesSeen++
        if (now - startNs >= MAX_CAPTURE_NS) {
            stop(CaptureStop.TIME_CAP)
            return
        }
        val camera = frame.camera
        tracking = camera.trackingState == TrackingState.TRACKING
        if (!tracking) {
            counters.notTracking++
            publish(force = false)
            return
        }
        val pose = camera.pose // the physical camera (sensor orientation), not the display-oriented pose
        pose.getTranslation(t, 0)
        pose.getRotationQuaternion(q, 0)
        gate.onFrame(q, ts)
        if (governor.due(now)) tryCapture(frame, now)
        publish(force = false)
    }

    private fun tryCapture(frame: Frame, now: Long) {
        if (!gate.steady) {
            counters.blurSkipped++
            return
        }
        if (!gate.isNewView(t, q)) {
            counters.sameView++
            return
        }
        // Cheap backlog check before acquiring any image: the worker still holds both slots.
        if (ring?.hasFree() == false) {
            counters.backlog++
            governor.onBacklog(now)
            return
        }
        val t0 = System.nanoTime()
        Trace.beginSection("SV.depthCopy")
        try {
            val slot = copyDepth(frame) ?: return
            fillColour(frame, slot)
            val k = frame.camera.textureIntrinsics
            val f = k.focalLength
            val pp = k.principalPoint
            val dims = k.imageDimensions
            textureWidth = dims[0]
            textureHeight = dims[1]
            val sx = slot.depth.width.toDouble() / dims[0]
            val sy = slot.depth.height.toDouble() / dims[1]
            slot.depth.intrinsics = CameraIntrinsics(f[0] * sx, f[1] * sy, pp[0] * sx, pp[1] * sy)
            slot.depth.cameraToWorld = Pose(
                Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
                Quaternion(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble()),
            )
            if (channel?.trySend(slot)?.isSuccess != true) {
                ring?.release(slot)
                return
            }
            counters.handedOff++
            gate.onIntegrated(t, q)
            governor.onIntegrated(now)
            copyNs.add(System.nanoTime() - t0)
        } finally {
            Trace.endSection()
        }
    }

    /** Copies this frame's depth (and confidence) into a free slot; null when there is nothing new to integrate. */
    private fun copyDepth(frame: Frame): DepthSlot? {
        val planned = source ?: plannedSource() ?: return null
        val useRaw = planned != DepthSource.SMOOTHED
        val image: Image = try {
            if (useRaw) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            counters.noDepthYet++
            return null
        } catch (e: Exception) {
            counters.depthErrors++
            if (useRaw && rawSuccesses == 0 && ++rawFailures >= RAW_FAILURES_BEFORE_FALLBACK) {
                source = DepthSource.SMOOTHED
                sourceFellBack = true
            }
            return null
        }
        image.use { depth ->
            if (useRaw) rawSuccesses++
            val dts = depth.timestamp
            if (dts == lastDepthTs) {
                counters.repeatedDepth++
                return null
            }
            if (dts != frame.timestamp) {
                counters.staleDepth++
                return null
            }
            val r = ring ?: DepthSlotRing(Array(SLOTS) { DepthSlot(depth.width, depth.height) }).also {
                ring = it
                depthWidth = depth.width
                depthHeight = depth.height
            }
            if (depth.width != r.depthWidth || depth.height != r.depthHeight) {
                counters.sizeMismatch++
                return null
            }
            val slot = r.acquire() ?: run {
                counters.backlog++
                return null
            }
            val p = depth.planes[0]
            PlaneCopy.copyU16(p.buffer, p.rowStride, p.pixelStride, depth.width, depth.height, slot.depth.depthMm)
            val src = if (useRaw) planned else DepthSource.SMOOTHED
            if (useRaw) {
                val ok = try {
                    frame.acquireRawDepthConfidenceImage().use { c ->
                        if (c.width != depth.width || c.height != depth.height) {
                            false
                        } else {
                            val cp = c.planes[0]
                            PlaneCopy.copyU8(cp.buffer, cp.rowStride, cp.pixelStride, c.width, c.height, slot.depth.confidence)
                            true
                        }
                    }
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    counters.noConfidence++
                    r.release(slot)
                    return null
                }
            } else {
                // Smoothed depth has no confidence image: full confidence, half weight (DepthSource.SMOOTHED).
                slot.depth.confidence.fill(0xFF.toByte())
            }
            slot.depth.timestampNs = dts
            slot.sampleWeight = src.sampleWeight
            source = src
            lastDepthTs = dts
            return slot
        }
    }

    private fun fillColour(frame: Frame, slot: DepthSlot) {
        slot.hasColour = try {
            frame.acquireCameraImage().use { img ->
                if (img.format != ImageFormat.YUV_420_888) {
                    false
                } else {
                    cpuWidth = img.width
                    cpuHeight = img.height
                    val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
                    PlaneCopy.decimateYuv(
                        y.buffer, y.rowStride, y.pixelStride,
                        u.buffer, u.rowStride, u.pixelStride,
                        v.buffer, v.rowStride, v.pixelStride,
                        img.width, img.height, slot.colour,
                    )
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
        if (slot.hasColour) counters.colourFrames++ else counters.colourMisses++
    }

    // ---- worker ----

    private suspend fun fuse(ch: Channel<DepthSlot>, vol: TsdfVolume) {
        for (slot in ch) {
            val t0 = System.nanoTime()
            Trace.beginSection("SV.fusion")
            try {
                vol.integrate(slot.depth, if (slot.hasColour) slot.colour else null, slot.sampleWeight)
            } finally {
                Trace.endSection()
            }
            val dt = System.nanoTime() - t0
            fusionNs.add(dt)
            lastFusionNs = dt
            observed = vol.observedFraction()
            integrated++
            ring?.release(slot)
        }
    }

    private fun publish(force: Boolean) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastPublishMs < PUBLISH_INTERVAL_MS) return
        lastPublishMs = nowMs
        _progress.value = CaptureProgress(
            running = running,
            elapsedMs = elapsedMs(),
            integrated = integrated,
            rateHz = governor.rateHz,
            source = source ?: plannedSource(),
            depthModeOn = depthModeOn,
            tracking = tracking,
            observedFraction = observed,
            lastFusionMs = lastFusionNs / 1e6,
            thermal = thermal,
            stop = stopReason,
        )
    }

    companion object {
        /** §7.1.6 hard cap. */
        const val MAX_CAPTURE_NS = 90_000_000_000L

        /** Two pooled slots (§7.1.3). */
        const val SLOTS = 2

        const val PUBLISH_INTERVAL_MS = 250L

        /** 90 s at 5 Hz, with headroom. */
        const val MAX_INTEGRATIONS = 512

        /** 90 s at 60 Hz, with headroom. */
        const val MAX_FRAMES = 8192

        private const val RAW_FAILURES_BEFORE_FALLBACK = 10
    }
}
