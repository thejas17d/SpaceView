package com.spaceview.ar

import com.spaceview.engine.recon.YuvFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** §7.1.11 `:ar` rows for the 3D-scan capture: governor, slot ring, view gate, plane copies, sample stats. */
class ShapeScanCaptureTest {
    private val ms = 1_000_000L
    private val s = 1_000_000_000L

    // ---- ShapeScanGovernor -----------------------------------------------------------------------------

    @Test fun governorStartsAt5HzAndRateLimitsIntegration() {
        val g = ShapeScanGovernor()
        assertEquals(5, g.rateHz)
        assertTrue(g.due(0))
        g.onIntegrated(0)
        assertFalse(g.due(199 * ms))
        assertTrue(g.due(200 * ms))
    }

    /**
     * T-D0 review BLOCKER 1: a healthy 30 fps camera (33.33 ms with jitter, and a few late frames) must stay at 5 Hz.
     * The old "p90 > 33 ms" rule stepped down on every real session.
     */
    @Test fun governorStaysAt5HzOnAHealthy30fpsCamera() {
        val g = ShapeScanGovernor()
        assertEquals(50.0, g.dropThresholdNs / 1e6, 0.001) // 1.5 × 33.33 ms
        val rng = java.util.Random(7)
        var now = 0L
        repeat(2700) { i -> // 90 s
            val dt = 33_333_333L + (rng.nextInt(2_000_001) - 1_000_000) + if (i % 40 == 0) 33_333_333L else 0L // 2.5% single drops
            now += dt
            g.onFrame(now, dt)
        }
        assertEquals(5, g.rateHz)
        assertEquals(0, g.eventCount)
    }

    @Test fun governorStepsDownOnDroppedFramesWithACooldownAndNeverStepsUp() {
        val g = ShapeScanGovernor()
        var now = 0L
        // 3 s healthy with one 200 ms hiccup: p90 stays at the frame period.
        repeat(90) { i -> val dt = if (i == 45) 200 * ms else 33_333_333L; now += dt; g.onFrame(now, dt) }
        assertEquals(5, g.rateHz)
        // One frame dropped in two (intervals alternate 33.3 / 66.7 ms): p90 = 66.7 ms > 50 ms → step down.
        repeat(60) { i -> val dt = if (i % 2 == 0) 33_333_333L else 66_666_667L; now += dt; g.onFrame(now, dt) }
        assertEquals(3, g.rateHz)
        repeat(120) { i -> val dt = if (i % 2 == 0) 33_333_333L else 66_666_667L; now += dt; g.onFrame(now, dt) }
        assertEquals(2, g.rateHz)
        assertTrue("cooldown between frame-time steps", g.eventTimeNs(1) - g.eventTimeNs(0) >= ShapeScanGovernor.COOLDOWN_NS)
        // Healthy frames again: it never steps back up during the scan.
        repeat(200) { now += 33_333_333L; g.onFrame(now, 33_333_333L) }
        assertEquals(2, g.rateHz)
        assertEquals(GovernorEvent.FRAME_TIME, g.eventKind(0))
        assertEquals(2, g.eventCount)
    }

    /** The drop threshold follows the locked camera rate: 60 fps frames are fine at 60 fps, 30 fps frames are drops. */
    @Test fun governorDropThresholdFollowsTheCameraRate() {
        val g = ShapeScanGovernor().apply { framePeriodNs = 16_666_667L }
        assertEquals(25.0, g.dropThresholdNs / 1e6, 0.001) // 1.5 × 16.67 ms
        var now = 0L
        repeat(300) { now += 16_666_667L; g.onFrame(now, 16_666_667L) }
        assertEquals(5, g.rateHz)
        repeat(60) { now += 33_333_333L; g.onFrame(now, 33_333_333L) } // every other 60 fps frame missing
        assertEquals(3, g.rateHz)
    }

    @Test fun governorMeanRateIsTimeWeighted() {
        val g = ShapeScanGovernor()
        assertEquals(5.0, g.meanRateHz(0, 10 * s), 1e-9)
        g.onBacklog(4 * s) // 5 Hz for 4 s, then 3 Hz
        g.onThermal(2, 6 * s) // then 2 Hz
        // (5·4 + 3·2 + 2·4) / 10 = 3.4 Hz
        assertEquals(3.4, g.meanRateHz(0, 10 * s), 1e-9)
        g.onThermal(3, 8 * s) // SEVERE: 0 Hz after
        assertEquals((5.0 * 4 + 3 * 2 + 2 * 2) / 10, g.meanRateHz(0, 10 * s), 1e-9)
    }

    @Test fun governorBacklogAndThermal() {
        val g = ShapeScanGovernor()
        g.onBacklog(0)
        assertEquals(3, g.rateHz)
        g.onBacklog(1 * s) // within the cooldown
        assertEquals(3, g.rateHz)
        g.onThermal(1, 2 * s) // LIGHT: nothing
        assertEquals(3, g.rateHz)
        g.onThermal(2, 2 * s) // MODERATE: at once, no cooldown
        assertEquals(2, g.rateHz)
        assertFalse(g.stopped)
        g.onThermal(3, 3 * s) // SEVERE: stop
        assertTrue(g.stopped)
        assertFalse(g.due(10 * s))
        assertEquals(listOf(GovernorEvent.BACKLOG, GovernorEvent.THERMAL_MODERATE, GovernorEvent.THERMAL_SEVERE), (0 until g.eventCount).map { g.eventKind(it) })
        assertEquals(3 * s, g.eventTimeNs(2))
    }

    @Test fun governorFrameTimeP90NeedsEnoughRecentSamples() {
        val g = ShapeScanGovernor()
        repeat(10) { g.onFrame(it * 33 * ms, 50 * ms) }
        assertEquals(0L, g.frameTimeP90(10 * 33 * ms))
        repeat(30) { g.onFrame((10 + it) * 33 * ms, if (it < 27) 30 * ms else 90 * ms) }
        // 40 samples in the window: 10 × 50, 27 × 30, 3 × 90 → p90 (index 35 of 40 sorted) = 50 ms.
        assertEquals(50 * ms, g.frameTimeP90(40 * 33 * ms))
        // 3 s later the window is empty again.
        assertEquals(0L, g.frameTimeP90(40 * 33 * ms + 3 * s))
    }

    // ---- DepthSlotRing ---------------------------------------------------------------------------------

    @Test fun slotRingNeverHandsOutABusySlot() {
        val r = DepthSlotRing(Array(2) { DepthSlot(4, 2) })
        val a = r.acquire()!!
        val b = r.acquire()!!
        assertFalse(a === b)
        assertNull("both busy: a backlog", r.acquire())
        assertFalse(r.hasFree())
        r.release(a)
        assertTrue(r.hasFree())
        assertSame(a, r.acquire())
        assertEquals(8, a.colour.width) // colour at 2× the depth size
        assertEquals(4, r.depthWidth)
    }

    // ---- ViewGate ---------------------------------------------------------------------------------------

    private fun yaw(deg: Double): FloatArray {
        val h = Math.toRadians(deg) / 2
        return floatArrayOf(0f, Math.sin(h).toFloat(), 0f, Math.cos(h).toFloat())
    }

    @Test fun viewGateRejectsFastTurnsAndRepeatedViewpoints() {
        val g = ViewGate()
        val t0 = floatArrayOf(0f, 0f, 0f)
        g.onFrame(yaw(0.0), 0)
        assertTrue(g.steady)
        assertTrue("the first view is always new", g.isNewView(t0, yaw(0.0)))
        g.onIntegrated(t0, yaw(0.0))
        // 1° in 33 ms ≈ 0.53 rad/s: blurred.
        g.onFrame(yaw(1.0), 33 * ms)
        assertFalse(g.steady)
        // 0.3° in 33 ms ≈ 0.16 rad/s: sharp, but not a new viewpoint (1 cm, 1.3° from the last integrated).
        g.onFrame(yaw(1.3), 66 * ms)
        assertTrue(g.steady)
        assertFalse(g.isNewView(floatArrayOf(0.01f, 0f, 0f), yaw(1.3)))
        assertTrue("moved 2 cm", g.isNewView(floatArrayOf(0.021f, 0f, 0f), yaw(1.3)))
        assertTrue("turned 3°", g.isNewView(t0, yaw(3.1)))
        assertEquals(Math.toRadians(90.0), ViewGate.angleBetween(yaw(0.0), yaw(90.0)), 1e-6)
        assertEquals("q and −q are the same rotation", 0.0, ViewGate.angleBetween(yaw(30.0), yaw(30.0).map { -it }.toFloatArray()), 1e-3)
        g.reset()
        assertTrue(g.isNewView(t0, yaw(0.0)))
    }

    // ---- PlaneCopy ----------------------------------------------------------------------------------------

    @Test fun planeCopiesHonourStrides() {
        // 3 × 2 depth image, pixel stride 2 B, row stride 8 B (2 B padding), native byte order.
        val buf = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        val values = arrayOf(shortArrayOf(1000, 2000, -1), shortArrayOf(7, 0, 1234))
        for (r in 0 until 2) for (c in 0 until 3) buf.putShort(r * 8 + c * 2, values[r][c])
        val out = ShortArray(6)
        PlaneCopy.copyU16(buf.order(ByteOrder.BIG_ENDIAN), 8, 2, 3, 2, out)
        assertEquals(listOf<Short>(1000, 2000, -1, 7, 0, 1234), out.toList())
        assertEquals("65535 mm stays unsigned in the TSDF's reading", 65535, out[2].toInt() and 0xFFFF)
        // Confidence with pixel stride 2 (interleaved) and a padded row.
        val cb = ByteBuffer.wrap(byteArrayOf(10, 0, 20, 0, 30, 0, 9, 9, 40, 0, 50, 0, 60, 0, 9, 9))
        val conf = ByteArray(6)
        PlaneCopy.copyU8(cb, 8, 2, 3, 2, conf)
        assertEquals(listOf<Byte>(10, 20, 30, 40, 50, 60), conf.toList())
    }

    @Test fun yuvDecimationSamplesPixelCentresOfTheSameFieldOfView() {
        // 8 × 4 source: Y = column index × 10 + row; chroma 4 × 2 interleaved (pixel stride 2, NV12-like), U = 100 + c, V = 200 + r.
        val srcW = 8; val srcH = 4
        val y = ByteBuffer.allocate(srcW * srcH)
        for (r in 0 until srcH) for (c in 0 until srcW) y.put(r * srcW + c, (c * 10 + r).toByte())
        val uv = ByteBuffer.allocate(16)
        for (r in 0 until 2) for (c in 0 until 4) { uv.put(r * 8 + 2 * c, (100 + c).toByte()); uv.put(r * 8 + 2 * c + 1, (200 + r).toByte()) }
        val u = uv.duplicate()
        val v = (uv.duplicate().position(1) as ByteBuffer).slice()
        val out = YuvFrame(4, 2)
        PlaneCopy.decimateYuv(y, srcW, 1, u, 8, 2, v, 8, 2, srcW, srcH, out)
        // Output pixel (c, r) samples source ((2c+1)·8/8, (2r+1)·4/4) = (2c+1, 2r+1).
        for (r in 0 until 2) for (c in 0 until 4) assertEquals(((2 * c + 1) * 10 + 2 * r + 1).toByte(), out.y[r * 4 + c])
        // Chroma 2 × 1 from the 4 × 2 source chroma: (2c+1, 1).
        assertEquals(listOf<Byte>(101, 103), out.u.toList())
        assertEquals(listOf(201.toByte(), 201.toByte()), out.v.toList())
    }

    // ---- NanoSamples ------------------------------------------------------------------------------------

    @Test fun sampleQuantilesAreNearestRank() {
        val n = NanoSamples(4)
        assertNull(n.quantileMs(0.5))
        for (v in listOf(4L, 1L, 3L, 2L, 99L)) n.add(v * ms)
        assertEquals(5, n.offered)
        assertEquals(4, n.kept) // capacity: the fifth is counted, not kept
        assertEquals(2.0, n.quantileMs(0.5)!!, 0.0)
        assertEquals(4.0, n.quantileMs(0.95)!!, 0.0)
        assertEquals(4.0, n.maxMs()!!, 0.0)
        assertEquals(2, n.countAbove(2 * ms))
        n.clear()
        assertEquals(0, n.kept)
    }
}
