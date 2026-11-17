package com.spaceview.app.scan3d

import java.util.Locale
import kotlin.math.abs

/**
 * Everything one 3D Scan Lab run measured (ARCHITECTURE-v2 §7.1.10 spike exit criteria and the R2 two-distance carton
 * bench). Plain numbers only, gathered by [LabViewModel]; [LabMetrics.build] turns them into the flat CSV row and the
 * JSON document. Lengths in metres, times in milliseconds, memory in kB. Null = not measured / not applicable.
 *
 * Debug builds only (the lab is compiled out of release).
 */
class LabRunInputs(
    val runId: String,
    val timestampIso: String,
    val label: String,
    val appVersion: String,
    val device: String,
    val sdk: Int,
    // capture
    val depthSource: String?,
    val sourceFellBack: Boolean,
    val depthWidth: Int,
    val depthHeight: Int,
    val stopReason: String?,
    val captureMs: Long,
    val framesSeen: Int,
    val framesIntegrated: Int,
    val colourFrames: Int,
    val fusionMs: Quantiles,
    val copyMs: Quantiles,
    val choreographerMs: Quantiles,
    val choreographerFrames: Int,
    val choreographerJanky: Int,
    val cameraFrameMs: Quantiles,
    val updateMs: Quantiles,
    val governorFinalHz: Int,
    val governorEvents: List<Pair<Long, String>>,
    val skippedBlur: Int,
    val skippedSameView: Int,
    val staleDepth: Int,
    val repeatedDepth: Int,
    val noDepthYet: Int,
    val depthErrors: Int,
    val noConfidence: Int,
    val backlog: Int,
    val thermalTimeline: List<Pair<Long, Int>>,
    val pssBaselineKb: Long?,
    val pssCaptureSamplesKb: List<Long>,
    val pssProcessingBaselineKb: Long?,
    // volume and processing
    val voxelM: Double?,
    val volumeBytes: Long?,
    val coverage: Double?,
    val meanRangeM: Double?,
    val observations: Int?,
    val stages: List<LabStage>,
    val glbBytes: Int?,
    val glbError: String?,
    // sizes
    val box: Triple<Measured, Measured, Measured>,
    val mesh: Triple<Measured, Measured, Measured>?,
    val tape: Triple<Double?, Double?, Double?>,
    val crossCheck: LabCrossCheck?,
    // appended after the first review (T-D0): new inputs only ever go at the end
    /** Time-weighted governor rate over the capture (Hz): what criterion 2's "at >= 3 Hz" is about. */
    val governorMeanHz: Double? = null,
    val textureWidth: Int = 0,
    val textureHeight: Int = 0,
    val cpuWidth: Int = 0,
    val cpuHeight: Int = 0,
)

/** A length with its 95% ± (m). */
class Measured(val value: Double, val bound95: Double)

/** p50 / p95 / max (ms) and the number of samples. */
class Quantiles(val p50: Double?, val p95: Double?, val max: Double?, val count: Int) {
    companion object {
        val NONE = Quantiles(null, null, null, 0)
    }
}

/** One processing stage: name, wall time, PSS right after it, triangles out. */
class LabStage(val name: String, val ms: Double, val pssKb: Long, val triangles: Int)

/** The engine's cross-check (mesh − box per axis, tolerances, badge). */
class LabCrossCheck(
    val agrees: Boolean,
    val badge: String,
    val worstAxis: Int,
    val diff: Triple<Double, Double, Double>,
    val tol: Triple<Double, Double, Double>,
)

/** The flat metrics of a run, in [COLUMNS] order, plus the JSON document. */
class LabMetrics private constructor(val values: LinkedHashMap<String, String>, val json: String) {
    /** One CSV row (RFC 4180 quoting) in [COLUMNS] order. */
    fun csvRow(): String = COLUMNS.joinToString(",") { csv(values[it] ?: "") }

    /** Value of a column ("" when not measured). */
    operator fun get(column: String): String = values[column] ?: ""

    companion object {
        /** The CSV header, stable across runs and app versions (new columns are only ever appended). */
        val COLUMNS: List<String> = listOf(
            "run_id", "timestamp", "label", "app_version", "device", "sdk",
            "depth_source", "depth_source_fell_back", "depth_w", "depth_h",
            "stop_reason", "capture_s", "frames_seen", "frames_integrated", "colour_frames", "integration_hz",
            "fusion_ms_p50", "fusion_ms_p95", "fusion_ms_max",
            "copy_ms_p50", "copy_ms_p95",
            "ar_frame_ms_p50", "ar_frame_ms_p95", "ar_frame_ms_max", "ar_frames", "ar_janky_frames",
            "camera_frame_ms_p95", "ar_update_ms_p95",
            "governor_final_hz", "governor_events",
            "skipped_blur", "skipped_same_view", "stale_depth", "repeated_depth", "no_depth_yet", "depth_errors", "no_confidence", "backlog",
            "thermal_start", "thermal_max", "thermal_end",
            "pss_baseline_mb", "pss_peak_capture_mb", "pss_processing_baseline_mb", "pss_peak_processing_mb", "peak_extra_pss_mb",
            "voxel_mm", "volume_mb", "coverage", "mean_range_m", "observations",
            "noise_ms", "marching_cubes_ms", "floor_clip_ms", "components_ms", "decimate_ms", "extent_ms", "glb_ms", "processing_ms",
            "tris_marching_cubes", "tris_floor_clip", "tris_components", "tris_final", "glb_bytes", "glb_error",
            "box_w_cm", "box_h_cm", "box_d_cm", "box_w_pm_cm", "box_h_pm_cm", "box_d_pm_cm",
            "mesh_w_cm", "mesh_h_cm", "mesh_d_cm", "mesh_w_pm_cm", "mesh_h_pm_cm", "mesh_d_pm_cm",
            "tape_w_cm", "tape_h_cm", "tape_d_cm",
            "mesh_minus_box_w_cm", "mesh_minus_box_h_cm", "mesh_minus_box_d_cm",
            "mesh_minus_tape_w_cm", "mesh_minus_tape_h_cm", "mesh_minus_tape_d_cm",
            "box_minus_tape_w_cm", "box_minus_tape_h_cm", "box_minus_tape_d_cm",
            "mesh_pm_covers_tape", "box_pm_covers_tape",
            "crosscheck_badge", "crosscheck_agrees", "crosscheck_worst_axis", "crosscheck_tol_w_cm", "crosscheck_tol_h_cm", "crosscheck_tol_d_cm",
            "c1_mesh_within_pm_of_box", "c2_fusion_le_15ms", "c2_ar_p95_le_33ms", "c2_rate_ge_3hz", "c3_no_severe", "c3_extra_pss_le_40mb", "c4_processing_le_5s",
            // Appended (T-D0 review MAJOR 2, MINORs 6 and 7). Never reorder: older CSVs keep their meaning.
            "pss_extra_processing_incl_volume_mb", "governor_mean_hz", "c2_achieved_rate_ge_3hz",
            "texture_w", "texture_h", "cpu_w", "cpu_h", "aspect_mismatch",
        )

        /** Spike exit thresholds (§7.1.10). */
        const val MAX_FUSION_MS = 15.0
        /** One 30 fps frame plus 0.5 ms (gate G1): Choreographer intervals are whole refresh multiples, so a steady 30 fps reads 33.33 ms. */
        const val MAX_AR_P95_MS = 1000.0 / 30 + 0.5
        const val MIN_RATE_HZ = 3.0
        const val MAX_EXTRA_PSS_MB = 40.0
        const val MAX_PROCESSING_MS = 5000.0
        const val THERMAL_SEVERE = 3

        fun build(i: LabRunInputs): LabMetrics {
            val v = LinkedHashMap<String, String>()
            fun put(k: String, x: Any?) {
                require(k in COLUMNS) { "unknown column $k" }
                v[k] = when (x) {
                    null -> ""
                    is Double -> if (x.isFinite()) num(x) else ""
                    is Float -> if (x.isFinite()) num(x.toDouble()) else ""
                    else -> x.toString()
                }
            }
            put("run_id", i.runId); put("timestamp", i.timestampIso); put("label", i.label)
            put("app_version", i.appVersion); put("device", i.device); put("sdk", i.sdk)
            put("depth_source", i.depthSource ?: "NONE"); put("depth_source_fell_back", i.sourceFellBack)
            put("depth_w", i.depthWidth.takeIf { it > 0 }); put("depth_h", i.depthHeight.takeIf { it > 0 })
            put("stop_reason", i.stopReason)
            val captureS = i.captureMs / 1000.0
            put("capture_s", captureS); put("frames_seen", i.framesSeen); put("frames_integrated", i.framesIntegrated)
            put("colour_frames", i.colourFrames)
            val rate = if (captureS > 0) i.framesIntegrated / captureS else null
            put("integration_hz", rate)
            put("fusion_ms_p50", i.fusionMs.p50); put("fusion_ms_p95", i.fusionMs.p95); put("fusion_ms_max", i.fusionMs.max)
            put("copy_ms_p50", i.copyMs.p50); put("copy_ms_p95", i.copyMs.p95)
            put("ar_frame_ms_p50", i.choreographerMs.p50); put("ar_frame_ms_p95", i.choreographerMs.p95)
            put("ar_frame_ms_max", i.choreographerMs.max); put("ar_frames", i.choreographerFrames); put("ar_janky_frames", i.choreographerJanky)
            put("camera_frame_ms_p95", i.cameraFrameMs.p95); put("ar_update_ms_p95", i.updateMs.p95)
            put("governor_final_hz", i.governorFinalHz)
            put("governor_events", i.governorEvents.joinToString(" ") { "${it.second}@${it.first}ms" })
            put("skipped_blur", i.skippedBlur); put("skipped_same_view", i.skippedSameView); put("stale_depth", i.staleDepth)
            put("repeated_depth", i.repeatedDepth); put("no_depth_yet", i.noDepthYet); put("depth_errors", i.depthErrors)
            put("no_confidence", i.noConfidence); put("backlog", i.backlog)
            // Statuses < 0 mean "unknown" (API < 29 has no thermal status): they never count as a reading.
            val thermalKnown = i.thermalTimeline.filter { it.second >= 0 }
            val thermalMax = thermalKnown.maxOfOrNull { it.second }
            put("thermal_start", thermalKnown.firstOrNull()?.second); put("thermal_max", thermalMax)
            put("thermal_end", thermalKnown.lastOrNull()?.second)

            val peakCapture = i.pssCaptureSamplesKb.maxOrNull()
            val peakProcessing = i.stages.maxOfOrNull { it.pssKb }?.takeIf { it > 0 }
            put("pss_baseline_mb", i.pssBaselineKb?.let { it / 1024.0 })
            put("pss_peak_capture_mb", peakCapture?.let { it / 1024.0 })
            put("pss_processing_baseline_mb", i.pssProcessingBaselineKb?.let { it / 1024.0 })
            put("pss_peak_processing_mb", peakProcessing?.let { it / 1024.0 })
            // Peak extra PSS of the scan (§7.1.10 item 3), the larger of:
            // - capture: TSDF + slots over the AR baseline taken before the volume was allocated;
            // - processing: the meshing peak over the processing baseline, PLUS the volume, because that baseline is read
            //   (after the AR session has closed) while the volume is still allocated (T-D0 review MAJOR 2). Adding the
            //   whole volume is conservative: never-touched voxel pages are not resident.
            val extraCapture = if (i.pssBaselineKb != null && peakCapture != null) (peakCapture - i.pssBaselineKb) / 1024.0 else null
            val extraProcessing = if (i.pssProcessingBaselineKb != null && peakProcessing != null) {
                (peakProcessing - i.pssProcessingBaselineKb) / 1024.0 + (i.volumeBytes ?: 0L) / 1_048_576.0
            } else {
                null
            }
            val extra = listOfNotNull(extraCapture, extraProcessing).maxOrNull()
            put("peak_extra_pss_mb", extra)
            put("pss_extra_processing_incl_volume_mb", extraProcessing)

            put("voxel_mm", i.voxelM?.let { it * 1000 }); put("volume_mb", i.volumeBytes?.let { it / 1e6 })
            put("coverage", i.coverage); put("mean_range_m", i.meanRangeM); put("observations", i.observations)
            val stageMs = i.stages.associate { it.name to it.ms }
            for (name in listOf("noise", "marching_cubes", "floor_clip", "components", "decimate", "extent", "glb")) put("${name}_ms", stageMs[name])
            val processingMs = if (i.stages.isEmpty()) null else i.stages.sumOf { it.ms }
            put("processing_ms", processingMs)
            val tris = i.stages.associate { it.name to it.triangles }
            put("tris_marching_cubes", tris["marching_cubes"]); put("tris_floor_clip", tris["floor_clip"])
            put("tris_components", tris["components"]); put("tris_final", tris["decimate"])
            put("glb_bytes", i.glbBytes); put("glb_error", i.glbError)

            val axes = listOf("w", "h", "d")
            val box = i.box.toList()
            val mesh = i.mesh?.toList()
            val tape = i.tape.toList()
            for (a in 0 until 3) {
                val ax = axes[a]
                put("box_${ax}_cm", box[a].value * 100); put("box_${ax}_pm_cm", box[a].bound95 * 100)
                put("mesh_${ax}_cm", mesh?.get(a)?.value?.times(100)); put("mesh_${ax}_pm_cm", mesh?.get(a)?.bound95?.times(100))
                put("tape_${ax}_cm", tape[a]?.times(100))
                put("mesh_minus_box_${ax}_cm", mesh?.get(a)?.let { (it.value - box[a].value) * 100 })
                put("mesh_minus_tape_${ax}_cm", tape[a]?.let { t -> mesh?.get(a)?.let { (it.value - t) * 100 } })
                put("box_minus_tape_${ax}_cm", tape[a]?.let { (box[a].value - it) * 100 })
            }
            put("mesh_pm_covers_tape", covers(mesh, tape))
            put("box_pm_covers_tape", covers(box, tape))
            val c = i.crossCheck
            put("crosscheck_badge", c?.badge); put("crosscheck_agrees", c?.agrees)
            put("crosscheck_worst_axis", c?.let { axes[it.worstAxis] })
            put("crosscheck_tol_w_cm", c?.tol?.first?.times(100)); put("crosscheck_tol_h_cm", c?.tol?.second?.times(100))
            put("crosscheck_tol_d_cm", c?.tol?.third?.times(100))

            // §7.1.10 per-run checks (criterion 1 is "4 of 5 scans" over a set; this is the per-scan part).
            put("c1_mesh_within_pm_of_box", mesh?.let { m -> (0 until 3).all { abs(m[it].value - box[it].value) <= m[it].bound95 } })
            put("c2_fusion_le_15ms", i.fusionMs.p95?.let { it <= MAX_FUSION_MS })
            put("c2_ar_p95_le_33ms", i.choreographerMs.p95?.let { it <= MAX_AR_P95_MS })
            // Criterion 2 is about the integration rate the governor allowed, not the frames the view gates passed
            // (a slow, careful walk integrates fewer); both are logged (T-D0 review MINOR 6).
            put("c2_rate_ge_3hz", i.governorMeanHz?.let { it >= MIN_RATE_HZ })
            put("governor_mean_hz", i.governorMeanHz)
            put("c2_achieved_rate_ge_3hz", rate?.let { it >= MIN_RATE_HZ })
            put("c3_no_severe", when {
                i.stopReason == "THERMAL_SEVERE" -> false
                thermalMax != null -> thermalMax < THERMAL_SEVERE
                else -> UNKNOWN // no thermal API (< 29): no data is not a pass
            })
            put("c3_extra_pss_le_40mb", extra?.let { it <= MAX_EXTRA_PSS_MB })
            put("c4_processing_le_5s", processingMs?.let { it <= MAX_PROCESSING_MS })
            // Intrinsics are scaled from the texture, colour comes from the CPU image: if their aspect ratios (or the
            // depth image's) differ, criterion-1 errors may be intrinsics, not depth (T-D0 review MINOR 7).
            put("texture_w", i.textureWidth.takeIf { it > 0 }); put("texture_h", i.textureHeight.takeIf { it > 0 })
            put("cpu_w", i.cpuWidth.takeIf { it > 0 }); put("cpu_h", i.cpuHeight.takeIf { it > 0 })
            put("aspect_mismatch", aspectMismatch(i.depthWidth to i.depthHeight, i.textureWidth to i.textureHeight, i.cpuWidth to i.cpuHeight))
            return LabMetrics(v, json(i, v))
        }

        /** "unknown": the value could not be measured on this phone (it is neither a pass nor a fail). */
        const val UNKNOWN = "unknown"

        /** Aspect ratios differing by more than this (relative) count as a mismatch. */
        const val ASPECT_TOLERANCE = 0.01

        /** True when any two known (w, h) sizes have different aspect ratios; null with fewer than two known. */
        fun aspectMismatch(vararg sizes: Pair<Int, Int>): Boolean? {
            val aspects = sizes.filter { it.first > 0 && it.second > 0 }.map { it.first.toDouble() / it.second }
            if (aspects.size < 2) return null
            val lo = aspects.min()
            return aspects.max() / lo - 1 > ASPECT_TOLERANCE
        }

        /** True / false when a tape value exists for at least one axis: every typed axis inside the ±; null without tape. */
        private fun covers(m: List<Measured>?, tape: List<Double?>): Boolean? {
            if (m == null || tape.all { it == null }) return null
            return (0 until 3).all { a -> tape[a]?.let { abs(m[a].value - it) <= m[a].bound95 } ?: true }
        }

        private fun json(i: LabRunInputs, v: Map<String, String>): String {
            val j = StringBuilder(4096).append("{\n  \"schema\": 1,\n  \"metrics\": {")
            var first = true
            for (k in COLUMNS) {
                j.append(if (first) "\n" else ",\n").append("    ").append(str(k)).append(": ")
                val x = v[k] ?: ""
                j.append(if (x.isEmpty()) "null" else if (k !in TEXT_COLUMNS && isNumberOrBool(x)) x else str(x))
                first = false
            }
            j.append("\n  },\n  \"stages\": [")
            i.stages.forEachIndexed { n, s ->
                j.append(if (n == 0) "\n" else ",\n").append("    {\"name\": ").append(str(s.name)).append(", \"ms\": ").append(num(s.ms))
                    .append(", \"pss_mb\": ").append(num(s.pssKb / 1024.0)).append(", \"triangles\": ").append(s.triangles).append('}')
            }
            j.append("\n  ],\n  \"thermal_timeline\": [")
            i.thermalTimeline.forEachIndexed { n, (t, s) -> j.append(if (n == 0) "" else ", ").append("{\"t_ms\": $t, \"status\": $s}") }
            j.append("],\n  \"governor_events\": [")
            i.governorEvents.forEachIndexed { n, (t, s) -> j.append(if (n == 0) "" else ", ").append("{\"t_ms\": $t, \"event\": ").append(str(s)).append('}') }
            j.append("],\n  \"pss_capture_mb\": [")
            i.pssCaptureSamplesKb.forEachIndexed { n, kb -> j.append(if (n == 0) "" else ", ").append(num(kb / 1024.0)) }
            j.append("]\n}\n")
            return j.toString()
        }

        /** Columns that are always JSON strings, even when they look like a number (a label "5", a model "2201"). */
        private val TEXT_COLUMNS = setOf(
            "run_id", "timestamp", "label", "app_version", "device", "depth_source", "stop_reason", "governor_events",
            "glb_error", "crosscheck_badge", "crosscheck_worst_axis",
        )

        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")

        private fun isNumberOrBool(x: String) = x == "true" || x == "false" || NUMBER.matches(x)

        /** Fixed 3 decimals, dot separator whatever the phone's locale. */
        fun num(x: Double): String = String.format(Locale.ROOT, "%.3f", x)

        fun str(s: String): String {
            val b = StringBuilder(s.length + 2).append('"')
            for (c in s) {
                when {
                    c == '"' -> b.append("\\\"")
                    c == '\\' -> b.append("\\\\")
                    c == '\n' -> b.append("\\n")
                    c == '\r' -> b.append("\\r")
                    c == '\t' -> b.append("\\t")
                    c < ' ' -> b.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> b.append(c)
                }
            }
            return b.append('"').toString()
        }

        /** RFC 4180: quote fields with a comma, quote or line break; double the quotes. */
        fun csv(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** Centimetres typed by the user ("82", "82.5", "82,5") → metres, or null for blank / invalid / implausible. */
        fun parseCm(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 1000 }?.div(100)

        /** The CSV header line. */
        fun csvHeader(): String = COLUMNS.joinToString(",")
    }
}
