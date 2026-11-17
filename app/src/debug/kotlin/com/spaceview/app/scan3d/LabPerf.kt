package com.spaceview.app.scan3d

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.view.Choreographer
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.spaceview.ar.NanoSamples
import java.io.File

/**
 * UI frame intervals from `Choreographer` frame callbacks (SceneView renders the AR view on the main thread's
 * Choreographer, so these are the AR frame times the user sees). Allocation-free per frame. Main thread only.
 */
internal class FrameIntervalRecorder : Choreographer.FrameCallback {
    val intervals = NanoSamples(MAX_FRAMES)
    private var lastNs = 0L
    private var running = false

    /** Starts a fresh recording (a recording still running is cleared, never mixed in; review MINOR 8). */
    @MainThread
    fun start() {
        val wasRunning = running
        running = true
        lastNs = 0L
        intervals.clear()
        if (!wasRunning) Choreographer.getInstance().postFrameCallback(this)
    }

    @MainThread
    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastNs != 0L) intervals.add(frameTimeNanos - lastNs)
        lastNs = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    companion object {
        /** 90 s at 120 Hz, with headroom. */
        const val MAX_FRAMES = 16_384
    }
}

/**
 * Thermal status (API 29+; `PowerManager.THERMAL_STATUS_*`). Records a timeline of changes relative to [start] and
 * forwards each status to [onStatus]. Below API 29 the status is unknown (-1) and only the frame-time rule governs.
 */
internal class ThermalMonitor(context: Context, private val clockMs: () -> Long, private val onStatus: (Int) -> Unit) {
    private val pm = ContextCompat.getSystemService(context, PowerManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private var startMs = 0L
    private val listener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        PowerManager.OnThermalStatusChangedListener { status -> record(status) }
    } else {
        null
    }

    /** (ms since [start], status) at start, on every change, and at [stop]. */
    val timeline = ArrayList<Pair<Long, Int>>()

    /** The current status, or -1 when the platform cannot tell (API < 29). */
    fun current(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm?.currentThermalStatus ?: -1 else -1

    @MainThread
    fun start() {
        startMs = clockMs()
        timeline.clear()
        record(current())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (listener as? PowerManager.OnThermalStatusChangedListener)?.let { pm?.addThermalStatusListener(executor, it) }
        }
    }

    @MainThread
    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (listener as? PowerManager.OnThermalStatusChangedListener)?.let { runCatching { pm?.removeThermalStatusListener(it) } }
        }
        record(current())
    }

    private fun record(status: Int) {
        timeline += (clockMs() - startMs) to status
        if (status >= 0) onStatus(status)
    }
}

/** Process PSS in kB (`Debug.getPss()`; reads /proc, ~10–100 ms: never call it on the main thread). */
internal object Pss {
    fun kb(): Long = Debug.getPss()
}

/**
 * The lab's files in app-private storage: `files/scan3d_lab/runs.csv` (one row per run, header once) and
 * `files/scan3d_lab/<run id>/metrics.json` + `mesh.glb`. Sharing copies them under `cache/share/scan3d-lab/`, the only
 * FileProvider root (`res/xml/file_paths.xml`), and opens the share sheet.
 */
internal class LabStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, DIR)

    val csv: File get() = File(dir, "runs.csv")

    fun runDir(id: String): File = File(dir, id)

    /** Appends the run's CSV row (writing the header first when the file is new or from an older column set). */
    fun save(id: String, metrics: LabMetrics, glb: ByteArray?) {
        val run = runDir(id)
        run.mkdirs()
        File(run, "metrics.json").writeText(metrics.json)
        if (glb != null) File(run, "mesh.glb").writeBytes(glb)
        val header = LabMetrics.csvHeader()
        val existing = if (csv.isFile) csv.bufferedReader().use { it.readLine() } else null
        if (existing != header) {
            // A different column set: keep the old file beside it instead of mixing columns.
            if (csv.isFile) csv.renameTo(File(dir, "runs-${System.currentTimeMillis()}.csv"))
            csv.writeText(header + "\n")
        }
        csv.appendText(metrics.csvRow() + "\n")
    }

    /** Number of runs in runs.csv. */
    fun runCount(): Int = if (csv.isFile) (csv.readLines().size - 1).coerceAtLeast(0) else 0

    /** The run's GLB, or null. */
    fun glb(id: String): File? = File(runDir(id), "mesh.glb").takeIf { it.isFile }

    /**
     * Shares (from the [from] context, an activity) runs.csv and, when [id] is given, that run's metrics.json (and its mesh.glb when [withMesh]). Returns
     * false when nothing could be shared.
     */
    fun share(from: Context, id: String?, withMesh: Boolean, chooserTitle: String): Boolean = try {
        val out = File(context.cacheDir, "share/scan3d-lab").apply { deleteRecursively(); mkdirs() }
        val files = buildList {
            if (csv.isFile) add(csv.copyTo(File(out, "spaceview-scan3d-runs.csv"), overwrite = true))
            if (id != null) {
                File(runDir(id), "metrics.json").takeIf { it.isFile }?.let { add(it.copyTo(File(out, "scan3d-$id.json"), overwrite = true)) }
                if (withMesh) glb(id)?.let { add(it.copyTo(File(out, "scan3d-$id.glb"), overwrite = true)) }
            }
        }
        if (files.isEmpty()) {
            false
        } else {
            val authority = context.packageName + ".fileprovider"
            val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newRawUri(files[0].name, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, chooserTitle)
            if (from !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(chooser)
            true
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        const val DIR = "scan3d_lab"
    }
}
