package com.spaceview.app.scan3d

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spaceview.app.BuildConfig
import com.spaceview.app.appContainer
import com.spaceview.app.pro.GateState
import com.spaceview.ar.CaptureStop
import com.spaceview.ar.DepthCaptureListener
import com.spaceview.ar.Reading
import com.spaceview.ar.ShapeScanBox
import com.spaceview.ar.ShapeScanPipeline
import com.spaceview.ar.ShapeScanResult
import com.spaceview.ar.VolumeHolder
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.GalleryRepository
import com.spaceview.data.gallery.GlbExtents
import com.spaceview.data.gallery.MeshDraft
import com.spaceview.data.gallery.MeshExtents
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.BoxMeasurement
import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.GlbReader
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import com.spaceview.engine.UncertaintyModel
import com.spaceview.engine.Vec3
import com.spaceview.engine.recon.DepthSource
import com.spaceview.engine.recon.MeshExtent
import com.spaceview.engine.recon.OrientedBox
import com.spaceview.engine.recon.TsdfVolume
import com.spaceview.engine.recon.VertexClusterDecimator
import com.spaceview.engine.recon.VolumeSizing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The 3D Scan Lab (debug builds only): the device spike harness of ARCHITECTURE-v2 §7.1.10 / M4-P1 gate §8 D1.
 *
 * Flow: protocol + run label + optional tape values → AR (mark the box with BOX mode, then capture depth into a TSDF on
 * the fusion worker) → processing in the `ReconMemoryTest` order on a background thread, with the AR session gone →
 * metrics on screen, written to `files/scan3d_lab/` (CSV + JSON), shareable, the mesh viewable and optionally saved.
 *
 * Threads: UI state and every AR / capture call on main; `Debug.getPss` on IO; volume allocation and processing on
 * Default (never main or GL). The volume is touched by the capture's fusion worker until [DepthCaptureListener.finish]
 * returns, and only by processing after that.
 *
 * Save: **debug-only direct save**, gated by `gallerySaveGate.checkDeviceScan()` (the Free 3-scan / 20-item caps), then
 * `GalleryRepository.saveMesh`. It is not the production `GatedGalleryStore` (gate §8 D4), which D integration adds.
 */
internal class LabViewModel(app: Application) : AndroidViewModel(app) {
    enum class Step { INTRO, AR, PROCESSING, RESULT }
    enum class StartError { BAD_BOX, TOO_BIG, NO_DEPTH, NO_MEMORY, TOO_WARM }
    enum class SaveState { IDLE, SAVING, SAVED, NEEDS_PRO, FAILED }

    /** A finished run as the result screen shows it. */
    class LabResult(val id: String, val metrics: LabMetrics, val glb: ByteArray?, val error: String?)

    data class Ui(
        val step: Step = Step.INTRO,
        val label: String = DEFAULT_LABEL,
        val tapeW: String = "",
        val tapeH: String = "",
        val tapeD: String = "",
        val starting: Boolean = false,
        val startError: StartError? = null,
        val stage: String? = null,
        val result: LabResult? = null,
        val save: SaveState = SaveState.IDLE,
        val runCount: Int = 0,
        val viewing: Boolean = false,
        val shareFailed: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val store = LabStore(app)
    private val frames = FrameIntervalRecorder()
    private val thermal = ThermalMonitor(app, SystemClock::elapsedRealtime) { status -> capture?.onThermal(status) }
    private val holder = VolumeHolder(null)

    /** The capture listener of the current AR attempt (one per attempt). */
    var capture: DepthCaptureListener? = null
        private set

    // The run being captured / processed.
    private var capturing = false
    private var stopJob: Job? = null
    private var pssJob: Job? = null
    private var box: OrientedBox? = null
    private var measured: BoxMeasurement? = null
    private var quality: Quality = Quality.ROUGH
    private var tier: DeviceTier = DeviceTier.DEPTH
    private var pssBaseline: Long? = null
    private val pssSamples = ArrayList<Long>()
    private var volumeBytes: Long? = null
    private var pipeline: ShapeScanResult? = null
    private var pipelineSource: DepthSource? = null

    init {
        viewModelScope.launch { refreshRunCount() }
    }

    // ---- intro ----

    fun setLabel(s: String) = _ui.update { it.copy(label = s.take(40)) }
    fun setTape(w: String? = null, h: String? = null, d: String? = null) = _ui.update {
        it.copy(tapeW = w?.take(8) ?: it.tapeW, tapeH = h?.take(8) ?: it.tapeH, tapeD = d?.take(8) ?: it.tapeD)
    }

    /** Opens the AR step with a fresh capture listener. */
    @MainThread
    fun beginAr() {
        capture?.detach()
        val c = DepthCaptureListener()
        capture = c
        capturing = false
        pipeline = null
        stopJob?.cancel()
        stopJob = viewModelScope.launch {
            c.progress.collect { p -> if (p.stop != null && capturing) onCaptureStopped(c) }
        }
        _ui.update { it.copy(step = Step.AR, startError = null, starting = false, result = null, save = SaveState.IDLE, viewing = false) }
    }

    /**
     * Back to the intro, abandoning any capture in progress or still starting (T-D0 review MINOR 8): the listener is
     * dropped (so a pending start sees `capture !== c` and gives up), the recorders stop, and nothing is processed.
     */
    @MainThread
    fun backToIntro() {
        capturing = false // first, so the stop below is not processed
        capture?.detach()
        capture = null
        stopJob?.cancel()
        stopRecorders()
        holder.volume = null
        _ui.update { it.copy(step = Step.INTRO, startError = null, starting = false, viewing = false) }
    }

    private fun stopRecorders() {
        frames.stop()
        thermal.stop()
        pssJob?.cancel()
        pssJob = null
    }

    // ---- capture ----

    /**
     * Starts the depth capture for the finished BOX outline: [points] are the committed base0, base1, base2, top and
     * [reading] the BOX reading (width = primary, depth = secondary, height = boxHeight).
     */
    @MainThread
    fun startCapture(points: List<Vec3>, reading: Reading, deviceTier: DeviceTier?) {
        val c = capture ?: return
        if (capturing || _ui.value.starting) return
        val b = if (points.size >= 4) ShapeScanBox.fromPoints(points[0], points[1], points[2], points[3]) else null
        val bh = reading.boxHeight
        val bd = reading.secondary
        if (b == null || bh == null || bd == null) {
            _ui.update { it.copy(startError = StartError.BAD_BOX) }
            return
        }
        if (c.progress.value.source == null) {
            _ui.update { it.copy(startError = StartError.NO_DEPTH) }
            return
        }
        // Already SEVERE: refuse up front rather than run a capture that can never integrate (review MINOR 5).
        if (thermal.current() >= THERMAL_SEVERE) {
            _ui.update { it.copy(startError = StartError.TOO_WARM) }
            return
        }
        box = b
        measured = BoxMeasurement(reading.primary, bh, bd, Measurement(reading.primary.value * bh.value * bd.value, 0.0))
        quality = reading.quality
        tier = deviceTier?.takeIf { it != DeviceTier.NO_DEPTH } ?: DeviceTier.DEPTH
        _ui.update { it.copy(starting = true, startError = null) }
        viewModelScope.launch {
            pssBaseline = withContext(Dispatchers.IO) { Pss.kb() }
            val vol = withContext(Dispatchers.Default) {
                try {
                    VolumeSizing.spec(b, VolumeSizing.DEFAULT_MARGIN_M, lowRam())?.let { TsdfVolume(it) } ?: StartError.TOO_BIG
                } catch (e: OutOfMemoryError) {
                    StartError.NO_MEMORY
                }
            }
            if (vol !is TsdfVolume) {
                _ui.update { it.copy(starting = false, startError = vol as StartError) }
                return@launch
            }
            if (capture !== c) return@launch // left the AR step meanwhile (backToIntro): nothing was started
            holder.volume = vol
            volumeBytes = vol.bytes
            synchronized(pssSamples) { pssSamples.clear() }
            if (!c.start(vol)) {
                holder.volume = null
                _ui.update { it.copy(starting = false, startError = StartError.NO_DEPTH) }
                return@launch
            }
            capturing = true
            // After a successful start, so a SEVERE reading reaches a running capture and stops it (MINOR 5).
            frames.start()
            thermal.start()
            pssJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val kb = Pss.kb()
                    synchronized(pssSamples) { pssSamples += kb }
                    delay(PSS_EVERY_MS)
                }
            }
            _ui.update { it.copy(starting = false) }
        }
    }

    /** "Finish": stop capturing and process what was captured. */
    @MainThread
    fun finishCapture() {
        capture?.stop(CaptureStop.USER)
    }

    private fun onCaptureStopped(c: DepthCaptureListener) {
        capturing = false
        stopRecorders()
        _ui.update { it.copy(step = Step.PROCESSING, stage = ShapeScanPipeline.STAGES.first()) }
        viewModelScope.launch {
            c.finish() // the fusion worker is done with the volume (happens-before through the join)
            process(c)
        }
    }

    private suspend fun process(c: DepthCaptureListener) {
        val b = box ?: return
        val m = measured ?: return
        val source = c.source
        pipelineSource = source
        var error: String? = null
        // The PROCESSING step has replaced the AR screen; SceneView closes the session and camera asynchronously, so
        // wait for PSS to settle before the baseline, or the release would hide meshing memory (review MAJOR 2). The
        // volume is still allocated here: LabMetrics adds it back to the processing figure.
        val processingBaseline = withContext(Dispatchers.IO) { settledPss() }
        var stageIndex = 0
        val result = if (source == null || c.fusionNs.offered == 0) {
            error = "no depth frames were integrated"
            null
        } else {
            withContext(Dispatchers.Default) {
                try {
                    ShapeScanPipeline.process(holder, source, UncertaintyModel(tier), b.width, b.height, b.depth, m, pss = {
                        val kb = Pss.kb()
                        stageIndex++
                        _ui.update { it.copy(stage = ShapeScanPipeline.STAGES.getOrNull(stageIndex)) }
                        kb
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    error = "out of memory while processing"
                    null
                } catch (e: Exception) {
                    error = e.javaClass.simpleName + ": " + e.message
                    null
                }
            }
        }
        holder.volume = null
        pipeline = result
        val id = runId()
        val metrics = LabMetrics.build(inputs(id, c, result, processingBaseline, error ?: result?.glbError))
        val glb = result?.glb
        withContext(Dispatchers.IO) { runCatching { store.save(id, metrics, glb) } }
        refreshRunCount()
        _ui.update { it.copy(step = Step.RESULT, stage = null, result = LabResult(id, metrics, glb, error), save = SaveState.IDLE) }
    }

    /** PSS once it has stopped falling (two readings 500 ms apart within 1 MB), after at least 1 s; at most ~4 s. */
    private suspend fun settledPss(): Long {
        delay(SETTLE_FIRST_MS)
        var last = Pss.kb()
        repeat(SETTLE_MAX_READS) {
            delay(SETTLE_EVERY_MS)
            val now = Pss.kb()
            if (kotlin.math.abs(now - last) < SETTLE_DELTA_KB) return now
            last = now
        }
        return last
    }

    private fun inputs(id: String, c: DepthCaptureListener, r: ShapeScanResult?, processingBaseline: Long, error: String?): LabRunInputs {
        val k = c.counters
        val g = c.governor
        val t0 = c.startedAtNs
        val events = (0 until minOf(g.eventCount, 16)).map { (g.eventTimeNs(it) - t0) / 1_000_000 to g.eventKind(it).name }
        val m = measured!!
        val tape = _ui.value.let { Triple(LabMetrics.parseCm(it.tapeW), LabMetrics.parseCm(it.tapeH), LabMetrics.parseCm(it.tapeD)) }
        val cc = r?.check
        return LabRunInputs(
            runId = id,
            timestampIso = iso(System.currentTimeMillis()),
            label = _ui.value.label.ifBlank { DEFAULT_LABEL },
            appVersion = BuildConfig.VERSION_NAME,
            device = Build.MANUFACTURER + " " + Build.MODEL,
            sdk = Build.VERSION.SDK_INT,
            depthSource = c.source?.name,
            sourceFellBack = c.sourceFellBack,
            depthWidth = c.depthWidth,
            depthHeight = c.depthHeight,
            stopReason = c.stop?.name,
            captureMs = c.elapsedMs(),
            framesSeen = k.framesSeen,
            framesIntegrated = c.fusionNs.offered,
            colourFrames = k.colourFrames,
            fusionMs = quantiles(c.fusionNs),
            copyMs = quantiles(c.copyNs),
            choreographerMs = quantiles(frames.intervals),
            choreographerFrames = frames.intervals.kept,
            choreographerJanky = frames.intervals.countAbove(JANK_NS),
            cameraFrameMs = quantiles(c.cameraIntervalNs),
            updateMs = quantiles(c.updateIntervalNs),
            governorFinalHz = g.rateHz,
            governorEvents = events,
            skippedBlur = k.blurSkipped,
            skippedSameView = k.sameView,
            staleDepth = k.staleDepth,
            repeatedDepth = k.repeatedDepth,
            noDepthYet = k.noDepthYet,
            depthErrors = k.depthErrors,
            noConfidence = k.noConfidence,
            backlog = k.backlog,
            thermalTimeline = ArrayList(thermal.timeline),
            pssBaselineKb = pssBaseline,
            pssCaptureSamplesKb = synchronized(pssSamples) { ArrayList(pssSamples) },
            pssProcessingBaselineKb = processingBaseline,
            voxelM = r?.voxelM,
            volumeBytes = volumeBytes,
            coverage = r?.coverage,
            meanRangeM = r?.noise?.meanRangeM,
            observations = r?.noise?.observations,
            stages = r?.stages?.map { LabStage(it.name, it.nanos / 1e6, it.pssKb, it.trianglesOut) } ?: emptyList(),
            glbBytes = r?.glb?.size,
            glbError = error,
            box = Triple(Measured(m.width.value, m.width.bound95), Measured(m.height.value, m.height.bound95), Measured(m.depth.value, m.depth.bound95)),
            mesh = r?.extent?.takeIf { !it.isEmpty }?.let {
                Triple(Measured(it.width.value, it.width.bound95), Measured(it.height.value, it.height.bound95), Measured(it.depth.value, it.depth.bound95))
            },
            tape = tape,
            crossCheck = cc?.let {
                LabCrossCheck(it.agrees, it.badge.name, it.worstAxis, Triple(it.diffW, it.diffH, it.diffD), Triple(it.tolW, it.tolH, it.tolD))
            },
            governorMeanHz = c.startedAtNs.takeIf { it != 0L }?.let { g.meanRateHz(it, it + c.elapsedMs() * 1_000_000) },
            textureWidth = c.textureWidth,
            textureHeight = c.textureHeight,
            cpuWidth = c.cpuWidth,
            cpuHeight = c.cpuHeight,
        )
    }

    // ---- result actions ----

    fun setViewing(v: Boolean) = _ui.update { it.copy(viewing = v) }

    fun share(from: android.content.Context, chooserTitle: String, withMesh: Boolean) {
        val id = _ui.value.result?.id
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { store.share(from, id, withMesh, chooserTitle) }
            _ui.update { it.copy(shareFailed = !ok) }
        }
    }

    /**
     * **Lab only: not the production save path** (T-D0 review MINOR 9). D integration must not copy it: the shipped
     * flow saves through `GatedGalleryStore` (gate §8 D4, once it has a mesh path) and records the completed scan for
     * ads (`recordCompletedScan`, the visit tracker; gate §8 D7, `MeasureVisitTrackerTest`). This lab has no ads.
     *
     * Saves the mesh to the gallery: `gallerySaveGate.checkDeviceScan()` first (NeedsPro keeps everything and says so),
     * then `saveMesh`. If the byte budget refuses it, decimates again to [RETRY_TRIANGLES] and retries once (gate §8 D3).
     */
    fun save() {
        val r = pipeline ?: return
        val glb = r.glb ?: return
        val m = measured ?: return
        val source = pipelineSource ?: return
        if (_ui.value.save == SaveState.SAVING || _ui.value.save == SaveState.SAVED) return
        val container = getApplication<Application>().appContainer
        _ui.update { it.copy(save = SaveState.SAVING) }
        viewModelScope.launch {
            val gate = container.gallerySaveGate.checkDeviceScan()
            if (gate is GateState.NeedsPro) {
                _ui.update { it.copy(save = SaveState.NEEDS_PRO) }
                return@launch
            }
            val ok = try {
                val repo = container.galleryRepository
                try {
                    repo.saveMesh(draft(glb, r.extent, m, source, r))
                } catch (e: IllegalArgumentException) {
                    if (glb.size <= GalleryRepository.MAX_SCAN_BYTES) throw e
                    val (glb2, ext2) = withContext(Dispatchers.Default) {
                        val b = box!!
                        val mesh = VertexClusterDecimator.decimate(r.mesh, RETRY_TRIANGLES)
                        GlbWriter.writeMesh(mesh, BoxOrigin.BOTTOM_CENTER, unlit = true, ShapeScanPipeline.extrasFor(source, r.voxelM, r.coverage), keepInputOrigin = true) to
                            MeshExtent.estimate(mesh, OrientedBox.local(b.width, b.height, b.depth), r.noise)
                    }
                    repo.saveMesh(draft(glb2, ext2, m, source, r))
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _ui.update { it.copy(save = if (ok) SaveState.SAVED else SaveState.FAILED) }
        }
    }

    private fun draft(glb: ByteArray, e: com.spaceview.engine.recon.Extent3, m: BoxMeasurement, source: DepthSource, r: ShapeScanResult): MeshDraft {
        val info = requireNotNull(GlbReader.readInfo(glb)) { "unreadable GLB" }
        return MeshDraft(
            kind = ObjectKind.BOX,
            width = Dimension.measured(m.width.value, m.width.bound95),
            height = Dimension.measured(m.height.value, m.height.bound95),
            depth = Dimension.measured(m.depth.value, m.depth.bound95),
            quality = quality,
            calibrated = false,
            deviceTier = tier,
            glb = glb,
            glbExtents = GlbExtents(info.widthM, info.heightM, info.depthM),
            meshExtents = MeshExtents(e.width.value, e.height.value, e.depth.value, e.width.bound95, e.height.bound95, e.depth.bound95),
            meshInfo = "{\"source\":\"${source.name}\",\"voxelM\":${r.voxelM},\"coverage\":${r.coverage},\"lab\":true}",
            thumbnailSource = null,
            label = "3D lab " + _ui.value.label.ifBlank { DEFAULT_LABEL },
            arcoreVersion = getApplication<Application>().appContainer.arcoreVersion(),
        )
    }

    // ---- helpers ----

    private suspend fun refreshRunCount() {
        val n = withContext(Dispatchers.IO) { runCatching { store.runCount() }.getOrDefault(0) }
        _ui.update { it.copy(runCount = n) }
    }

    private fun lowRam(): Boolean {
        val am = ContextCompat.getSystemService(getApplication(), ActivityManager::class.java) ?: return true
        return am.isLowRamDevice || am.memoryClass < LOW_RAM_MEMORY_CLASS_MB
    }

    override fun onCleared() {
        capturing = false
        capture?.detach()
        stopRecorders()
    }

    companion object {
        const val DEFAULT_LABEL = "carton-0.7m"
        val LABELS = listOf("carton-0.7m", "carton-1.5m", "armchair", "sofa")
        const val PSS_EVERY_MS = 1000L
        /** A frame is janky only above one 30 fps period plus 0.5 ms (gate G1); a steady 30 fps (33.33 ms) is not jank. */
        const val JANK_NS = 33_833_333L
        const val RETRY_TRIANGLES = 12_000

        /** `PowerManager.THERMAL_STATUS_SEVERE`. */
        const val THERMAL_SEVERE = 3
        const val SETTLE_FIRST_MS = 1000L
        const val SETTLE_EVERY_MS = 500L
        const val SETTLE_MAX_READS = 6
        const val SETTLE_DELTA_KB = 1024L

        /** §7.1.4: memoryClass below 192 MB uses the low-RAM voxel cap. */
        const val LOW_RAM_MEMORY_CLASS_MB = 192

        private fun quantiles(s: com.spaceview.ar.NanoSamples) =
            if (s.kept == 0) Quantiles.NONE else Quantiles(s.quantileMs(0.5), s.quantileMs(0.95), s.maxMs(), s.kept)


        private fun iso(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        private fun runId(): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + "-" + UUID.randomUUID().toString().take(4)
    }
}
