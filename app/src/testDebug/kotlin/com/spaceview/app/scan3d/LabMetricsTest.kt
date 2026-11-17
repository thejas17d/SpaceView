package com.spaceview.app.scan3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3D Scan Lab's metrics file format (debug source set; §7.1.10 spike criteria and the R2 carton bench). */
class LabMetricsTest {
    private fun inputs(
        choreographerMs: Quantiles = Quantiles(16.7, 30.1, 70.0, 3600),
        mesh: Triple<Measured, Measured, Measured>? = Triple(Measured(0.612, 0.040), Measured(0.405, 0.019), Measured(0.396, 0.039)),
        tape: Triple<Double?, Double?, Double?> = Triple(0.600, 0.400, null),
        label: String = "carton-0.7m",
        thermal: List<Pair<Long, Int>> = listOf(0L to 0, 45_000L to 1, 90_000L to 1),
        governorMeanHz: Double? = 4.6,
        captureMs: Long = 60_000,
        volumeBytes: Long? = 1_600_000,
        pssProcessingBaselineKb: Long? = 195_000,
        texture: Pair<Int, Int> = 1920 to 1080,
        cpu: Pair<Int, Int> = 640 to 480,
        stages: List<LabStage> = listOf(
            LabStage("noise", 1.0, 200_000, 0), LabStage("marching_cubes", 400.0, 230_000, 60_000),
            LabStage("floor_clip", 50.0, 215_000, 55_000), LabStage("components", 80.0, 215_000, 50_000),
            LabStage("decimate", 900.0, 220_000, 15_000), LabStage("extent", 5.0, 210_000, 15_000),
            LabStage("glb", 30.0, 210_000, 15_000),
        ),
    ) = LabRunInputs(
        runId = "20261116-120000-ab12", timestampIso = "2026-11-16T06:30:00Z", label = label, appVersion = "0.1.0-debug",
        device = "Google Pixel 7", sdk = 35, depthSource = "RAW", sourceFellBack = false, depthWidth = 160, depthHeight = 120,
        stopReason = "USER", captureMs = captureMs, framesSeen = 1800, framesIntegrated = 240, colourFrames = 238,
        fusionMs = Quantiles(6.5, 11.2, 14.0, 240), copyMs = Quantiles(0.8, 1.3, 2.0, 240),
        choreographerMs = choreographerMs, choreographerFrames = 3600, choreographerJanky = 12,
        cameraFrameMs = Quantiles(33.3, 33.4, 66.7, 1800), updateMs = Quantiles(33.3, 34.0, 70.0, 1800),
        governorFinalHz = 5, governorEvents = listOf(12_000L to "BACKLOG"),
        skippedBlur = 10, skippedSameView = 30, staleDepth = 5, repeatedDepth = 3, noDepthYet = 2, depthErrors = 0, noConfidence = 0, backlog = 1,
        thermalTimeline = thermal,
        pssBaselineKb = 180_000, pssCaptureSamplesKb = listOf(190_000, 200_000, 205_000), pssProcessingBaselineKb = pssProcessingBaselineKb,
        voxelM = 0.0101, volumeBytes = volumeBytes, coverage = 0.42, meanRangeM = 0.71, observations = 9,
        stages = stages, glbBytes = 301_234, glbError = null,
        box = Triple(Measured(0.605, 0.008), Measured(0.402, 0.006), Measured(0.398, 0.008)),
        mesh = mesh, tape = tape,
        crossCheck = LabCrossCheck(true, "APPROXIMATE", 0, Triple(0.007, 0.003, -0.002), Triple(0.041, 0.03, 0.04)),
        governorMeanHz = governorMeanHz,
        textureWidth = texture.first, textureHeight = texture.second, cpuWidth = cpu.first, cpuHeight = cpu.second,
    )

    @Test fun headerIsStableAndEveryRowMatchesIt() {
        val cols = LabMetrics.COLUMNS
        assertEquals("no duplicate columns", cols.size, cols.toSet().size)
        assertEquals(cols.joinToString(","), LabMetrics.csvHeader())
        // The R2 bench and every §7.1.10 criterion have a column.
        for (k in listOf("depth_source", "frames_integrated", "fusion_ms_p50", "fusion_ms_p95", "ar_frame_ms_p95", "peak_extra_pss_mb",
            "thermal_max", "tris_final", "glb_bytes", "mesh_minus_tape_w_cm", "crosscheck_badge", "mean_range_m",
            "c1_mesh_within_pm_of_box", "c2_fusion_le_15ms", "c3_extra_pss_le_40mb", "c4_processing_le_5s")) assertTrue(k, k in cols)
        val m = LabMetrics.build(inputs())
        assertEquals(cols.size, m.csvRow().split(',').size)
    }

    @Test fun derivedValuesAndSpikeChecks() {
        val m = LabMetrics.build(inputs())
        assertEquals("4.000", m["integration_hz"]) // 240 frames in 60 s
        assertEquals("0.700", m["mesh_minus_box_w_cm"])
        assertEquals("1.200", m["mesh_minus_tape_w_cm"])
        assertEquals("", m["mesh_minus_tape_d_cm"]) // no tape depth typed
        assertEquals("0.500", m["box_minus_tape_w_cm"])
        assertEquals("true", m["mesh_pm_covers_tape"]) // 1.2 ≤ 4.0 and 0.5 ≤ 1.9
        assertEquals("true", m["box_pm_covers_tape"]) // 0.5 ≤ 0.8 and 0.2 ≤ 0.6
        assertEquals("1466.000", m["processing_ms"])
        assertEquals("15000", m["tris_final"])
        assertEquals("60000", m["tris_marching_cubes"])
        // Peak extra PSS = max(capture (205000 − 180000) kB, processing (230000 − 195000) kB + the 1.6 MB volume).
        assertEquals("35.706", m["pss_extra_processing_incl_volume_mb"])
        assertEquals("35.706", m["peak_extra_pss_mb"])
        assertEquals("true", m["c1_mesh_within_pm_of_box"])
        assertEquals("true", m["c2_fusion_le_15ms"])
        assertEquals("true", m["c2_ar_p95_le_33ms"])
        assertEquals("true", m["c2_rate_ge_3hz"])
        assertEquals("4.600", m["governor_mean_hz"])
        assertEquals("true", m["c2_achieved_rate_ge_3hz"])
        assertEquals("true", m["c3_no_severe"])
        assertEquals("true", m["c3_extra_pss_le_40mb"])
        assertEquals("true", m["c4_processing_le_5s"])
        assertEquals("0", m["thermal_start"]); assertEquals("1", m["thermal_max"])
        assertEquals("BACKLOG@12000ms", m["governor_events"])
        assertEquals("10.100", m["voxel_mm"])
    }

    @Test fun failingRunsSayWhy() {
        val severe = LabMetrics.build(
            inputs(
                mesh = Triple(Measured(0.70, 0.04), Measured(0.40, 0.02), Measured(0.40, 0.04)),
                thermal = listOf(0L to 1, 80_000L to 3),
                stages = listOf(LabStage("marching_cubes", 6000.0, 0, 10)),
            ),
        )
        assertEquals("false", severe["c1_mesh_within_pm_of_box"]) // W is 9.5 cm off with ± 4
        assertEquals("false", severe["c3_no_severe"])
        assertEquals("false", severe["c4_processing_le_5s"])
        assertEquals("false", severe["mesh_pm_covers_tape"])
        // No mesh (nothing integrated): empty mesh cells, no checks that need one, no tape comparison for the mesh.
        val none = LabMetrics.build(inputs(mesh = null, tape = Triple(null, null, null), stages = emptyList()))
        assertEquals("", none["mesh_w_cm"])
        assertEquals("", none["c1_mesh_within_pm_of_box"])
        assertEquals("", none["mesh_pm_covers_tape"])
        assertEquals("", none["processing_ms"])
    }

    @Test fun csvAndJsonEscapeUserText() {
        val m = LabMetrics.build(inputs(label = "sofa, \"blue\"\n2"))
        assertTrue(m.csvRow().contains("\"sofa, \"\"blue\"\"\n2\""))
        assertTrue(m.json.contains("\"label\": \"sofa, \\\"blue\\\"\\n2\""))
        // A label that looks like a number stays a JSON string; numbers and booleans are bare.
        val n = LabMetrics.build(inputs(label = "5"))
        assertTrue(n.json.contains("\"label\": \"5\""))
        assertTrue(n.json.contains("\"frames_integrated\": 240"))
        assertTrue(n.json.contains("\"c2_fusion_le_15ms\": true"))
        assertTrue(n.json.contains("\"mesh_minus_tape_d_cm\": null"))
        assertTrue(n.json.contains("{\"name\": \"decimate\", \"ms\": 900.000"))
        assertTrue(n.json.contains("\"thermal_timeline\": [{\"t_ms\": 0, \"status\": 0}"))
        // Balanced braces and brackets (a cheap well-formedness check; no JSON library on the JVM test classpath).
        assertEquals(n.json.count { it == '{' }, n.json.count { it == '}' })
        assertEquals(n.json.count { it == '[' }, n.json.count { it == ']' })
    }

    @Test fun tapeInputParsing() {
        assertEquals(0.825, LabMetrics.parseCm("82.5")!!, 1e-12)
        assertEquals(0.825, LabMetrics.parseCm(" 82,5 ")!!, 1e-12)
        assertNull(LabMetrics.parseCm(""))
        assertNull(LabMetrics.parseCm("abc"))
        assertNull(LabMetrics.parseCm("-3"))
        assertNull(LabMetrics.parseCm("5000"))
        assertFalse(LabMetrics.csv("plain").startsWith("\""))
    }

    /**
     * T-D0 review MAJOR 2: the processing baseline is read with the TSDF still allocated, so the processing figure must
     * add the volume back. A 12.4 MB volume plus 30 MB of meshing is about 42 MB and must FAIL the 40 MB criterion.
     */
    @Test fun processingExtraCountsTheVolumeHeldInItsBaseline() {
        val volume = 12_400_000L
        val baselineWithVolume = 300_000L
        val m = LabMetrics.build(
            inputs(
                volumeBytes = volume, pssProcessingBaselineKb = baselineWithVolume,
                stages = listOf(LabStage("marching_cubes", 400.0, baselineWithVolume + 30 * 1024, 60_000)),
            ),
        )
        val extra = m["peak_extra_pss_mb"].toDouble()
        assertTrue("extra $extra MB counts the volume", extra >= volume / 1_048_576.0)
        assertEquals(30 + volume / 1_048_576.0, m["pss_extra_processing_incl_volume_mb"].toDouble(), 0.001)
        assertEquals("false", m["c3_extra_pss_le_40mb"])
    }

    /** Gate G1: a steady 30 fps camera (33.33 ms Choreographer p95) passes; one dropped frame per interval (50 ms) fails. */
    @Test fun arFrameTimeCheckAcceptsASteady30fpsSession() {
        val steady = LabMetrics.build(inputs(choreographerMs = Quantiles(33.333, 33.333, 50.0, 2700)))
        assertEquals("true", steady["c2_ar_p95_le_33ms"])
        val dropping = LabMetrics.build(inputs(choreographerMs = Quantiles(33.333, 50.0, 66.7, 2700)))
        assertEquals("false", dropping["c2_ar_p95_le_33ms"])
    }

    /** MINOR 6a: no thermal API (API < 29 records -1) is "unknown", never a pass; a SEVERE stop is always a fail. */
    @Test fun thermalWithoutAnApiIsUnknown() {
        val m = LabMetrics.build(inputs(thermal = listOf(0L to -1, 90_000L to -1)))
        assertEquals(LabMetrics.UNKNOWN, m["c3_no_severe"])
        assertEquals("", m["thermal_max"])
        assertTrue(m.json.contains("\"c3_no_severe\": \"unknown\""))
    }

    /** MINOR 6b: a slow careful walk integrates fewer frames than the governor allows; criterion 2 reads the governor. */
    @Test fun rateCheckUsesTheGovernorRateAndLogsTheAchievedOne() {
        val m = LabMetrics.build(inputs(governorMeanHz = 5.0, captureMs = 120_000)) // 240 frames in 120 s = 2 Hz achieved
        assertEquals("true", m["c2_rate_ge_3hz"])
        assertEquals("false", m["c2_achieved_rate_ge_3hz"])
        assertEquals("2.000", m["integration_hz"])
        val slow = LabMetrics.build(inputs(governorMeanHz = 2.4))
        assertEquals("false", slow["c2_rate_ge_3hz"])
    }

    /** MINOR 7: texture and CPU image sizes are logged, with a flag when their aspect ratios differ. */
    @Test fun imageSizesAndAspectMismatch() {
        val m = LabMetrics.build(inputs()) // depth 160 × 120 (4:3), texture 1920 × 1080 (16:9), CPU 640 × 480
        assertEquals("1920", m["texture_w"]); assertEquals("480", m["cpu_h"])
        assertEquals("true", m["aspect_mismatch"])
        assertEquals("false", LabMetrics.build(inputs(texture = 1440 to 1080))["aspect_mismatch"])
        assertEquals("", LabMetrics.build(inputs(texture = 0 to 0, cpu = 0 to 0))["aspect_mismatch"]) // depth only
        assertEquals(LabMetrics.COLUMNS.size, m.csvRow().split(',').size)
    }
}
