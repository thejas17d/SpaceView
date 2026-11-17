package com.spaceview.ar

import com.spaceview.engine.recon.DepthFrame
import com.spaceview.engine.recon.YuvFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

// Pure pieces of the 3D-scan depth copy-out (ARCHITECTURE-v2 §7.1.3 "DepthFrameTap", §7.1.6), JVM-tested. The ARCore
// adapter is DepthCaptureListener.

/**
 * One pooled capture slot: a depth frame (with its confidence) and the matching colour, refilled per integrated frame
 * on the main thread and read by the fusion worker. [busy] is the hand-off: main sets it before filling and sending the
 * slot; the worker clears it (a volatile write) only after it has finished reading, so main never overwrites a slot
 * the worker is still reading.
 */
class DepthSlot(depthWidth: Int, depthHeight: Int) {
    val depth = DepthFrame(depthWidth, depthHeight)

    /** Colour at 2× the depth resolution (§7.1.3), the same field of view. */
    val colour = YuvFrame(2 * depthWidth, 2 * depthHeight)

    /** The colour was filled for this use (false: integrate depth only). */
    var hasColour = false

    /** The depth source's sample weight for this frame ([com.spaceview.engine.recon.DepthSource.sampleWeight]). */
    var sampleWeight = 1f

    @Volatile var busy = false
}

/** A fixed ring of [DepthSlot]s that never hands out a busy slot (§7.1.11 `:ar` row). */
class DepthSlotRing(private val slots: Array<DepthSlot>) {
    init {
        require(slots.isNotEmpty()) { "at least one slot" }
    }

    private var next = 0

    /** A free slot, marked busy, or null when the worker holds them all (a backlog). Main thread. */
    fun acquire(): DepthSlot? {
        for (k in slots.indices) {
            val s = slots[(next + k) % slots.size]
            if (!s.busy) {
                s.busy = true
                next = (next + k + 1) % slots.size
                return s
            }
        }
        return null
    }

    /** True when at least one slot is free. */
    fun hasFree(): Boolean {
        for (s in slots) if (!s.busy) return true
        return false
    }

    /** Gives a slot back (worker after integrating, or main when a copy failed). */
    fun release(slot: DepthSlot) {
        slot.busy = false
    }

    val size: Int get() = slots.size

    /** The depth size the slots were made for. */
    val depthWidth: Int get() = slots[0].depth.width
    val depthHeight: Int get() = slots[0].depth.height
}

/**
 * Which camera poses are worth integrating (§7.1.3 sample gate), from raw pose floats so the per-frame path allocates
 * nothing:
 * - **motion blur:** the angular speed since the previous AR frame must be below [MAX_ANGULAR_SPEED] (0.3 rad/s);
 * - **view diversity:** the camera must have moved ≥ [MIN_MOVE_M] (2 cm) or turned ≥ [MIN_TURN_RAD] (3°) since the
 *   last *integrated* pose.
 */
class ViewGate {
    private val prevQ = FloatArray(4)
    private var prevNs = Long.MIN_VALUE
    private val lastT = FloatArray(3)
    private val lastQ = FloatArray(4)
    private var hasLast = false

    /** Angular speed (rad/s) of the latest [onFrame]; 0 for the first frame. */
    var angularSpeed = 0.0
        private set

    /** Every new AR frame: camera orientation [q] (x, y, z, w) at [timestampNs]. */
    fun onFrame(q: FloatArray, timestampNs: Long) {
        angularSpeed = if (prevNs == Long.MIN_VALUE || timestampNs <= prevNs) {
            0.0
        } else {
            angleBetween(prevQ, q) / ((timestampNs - prevNs) / 1e9)
        }
        q.copyInto(prevQ)
        prevNs = timestampNs
    }

    /** True when the latest frame is sharp enough ([angularSpeed] below the limit). */
    val steady: Boolean get() = angularSpeed < MAX_ANGULAR_SPEED

    /** True when pose ([t], [q]) adds a new viewpoint compared with the last integrated one. */
    fun isNewView(t: FloatArray, q: FloatArray): Boolean {
        if (!hasLast) return true
        val dx = (t[0] - lastT[0]).toDouble(); val dy = (t[1] - lastT[1]).toDouble(); val dz = (t[2] - lastT[2]).toDouble()
        if (dx * dx + dy * dy + dz * dz >= MIN_MOVE_M * MIN_MOVE_M) return true
        return angleBetween(lastQ, q) >= MIN_TURN_RAD
    }

    /** The pose ([t], [q]) was integrated. */
    fun onIntegrated(t: FloatArray, q: FloatArray) {
        t.copyInto(lastT)
        q.copyInto(lastQ)
        hasLast = true
    }

    /** Forgets everything (new capture). */
    fun reset() {
        prevNs = Long.MIN_VALUE
        hasLast = false
        angularSpeed = 0.0
    }

    companion object {
        const val MAX_ANGULAR_SPEED = 0.3
        const val MIN_MOVE_M = 0.02
        val MIN_TURN_RAD = Math.toRadians(3.0)

        /** Rotation angle (rad, 0…π) between unit quaternions [a] and [b] (x, y, z, w). */
        fun angleBetween(a: FloatArray, b: FloatArray): Double {
            val dot = abs(a[0].toDouble() * b[0] + a[1].toDouble() * b[1] + a[2].toDouble() * b[2] + a[3].toDouble() * b[3])
            val na = sqrt(a[0].toDouble() * a[0] + a[1].toDouble() * a[1] + a[2].toDouble() * a[2] + a[3].toDouble() * a[3])
            val nb = sqrt(b[0].toDouble() * b[0] + b[1].toDouble() * b[1] + b[2].toDouble() * b[2] + b[3].toDouble() * b[3])
            if (na == 0.0 || nb == 0.0) return 0.0
            return 2 * acos(min(1.0, dot / (na * nb)))
        }
    }
}

/** Allocation-free copies out of `android.media.Image` planes (row / pixel strides honoured), JVM-testable. */
object PlaneCopy {
    /**
     * A 16-bit plane (ARCore depth: unsigned millimetres, native byte order) into [out] (`w × h`, row-major). Changes
     * [buf]'s byte order to native.
     */
    fun copyU16(buf: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, out: ShortArray) {
        require(out.size >= w * h) { "out too small" }
        buf.order(ByteOrder.nativeOrder())
        for (r in 0 until h) {
            val row = r * rowStride
            val o = r * w
            for (c in 0 until w) out[o + c] = buf.getShort(row + c * pixelStride)
        }
    }

    /** An 8-bit plane (ARCore raw-depth confidence 0–255) into [out] (`w × h`). */
    fun copyU8(buf: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, out: ByteArray) {
        require(out.size >= w * h) { "out too small" }
        for (r in 0 until h) {
            val row = r * rowStride
            val o = r * w
            for (c in 0 until w) out[o + c] = buf.get(row + c * pixelStride)
        }
    }

    /**
     * Nearest-sample decimation of a YUV_420_888 image ([srcW] × [srcH]; chroma planes at half resolution, any strides,
     * planar or interleaved) into [out], which has the same field of view at its own size. Samples pixel centres.
     */
    fun decimateYuv(
        y: ByteBuffer, yRow: Int, yPix: Int,
        u: ByteBuffer, uRow: Int, uPix: Int,
        v: ByteBuffer, vRow: Int, vPix: Int,
        srcW: Int, srcH: Int, out: YuvFrame,
    ) {
        val ow = out.width
        val oh = out.height
        for (r in 0 until oh) {
            val sy = ((2 * r + 1) * srcH) / (2 * oh)
            val row = sy * yRow
            val o = r * ow
            for (c in 0 until ow) out.y[o + c] = y.get(row + (((2 * c + 1) * srcW) / (2 * ow)) * yPix)
        }
        val cw = ow / 2
        val ch = oh / 2
        val scw = (srcW + 1) / 2
        val sch = (srcH + 1) / 2
        for (r in 0 until ch) {
            val sy = ((2 * r + 1) * sch) / (2 * ch)
            val o = r * cw
            for (c in 0 until cw) {
                val sx = ((2 * c + 1) * scw) / (2 * cw)
                out.u[o + c] = u.get(sy * uRow + sx * uPix)
                out.v[o + c] = v.get(sy * vRow + sx * vPix)
            }
        }
    }
}

/** Fixed-capacity sample log (nanoseconds) with order statistics computed after the run. */
class NanoSamples(capacity: Int) {
    private val values = LongArray(capacity)

    /** Samples offered (may exceed the capacity; only the first [capacity] are kept). */
    @Volatile var offered = 0
        private set

    val kept: Int get() = min(offered, values.size)

    /** Adds one sample (single writer). Allocation-free. */
    fun add(ns: Long) {
        val n = offered
        if (n < values.size) values[n] = ns
        offered = n + 1
    }

    /** The [q]-quantile (0…1, nearest rank) in milliseconds, or null without samples. Allocates a sorted copy. */
    fun quantileMs(q: Double): Double? {
        val n = kept
        if (n == 0) return null
        val sorted = values.copyOf(n).also { it.sort() }
        val rank = Math.ceil(q * n).toInt().coerceIn(1, n) - 1
        return sorted[rank] / 1e6
    }

    /** Largest sample (ms), or null. */
    fun maxMs(): Double? = if (kept == 0) null else (0 until kept).maxOf { values[it] } / 1e6

    /** Samples above [thresholdNs]. */
    fun countAbove(thresholdNs: Long): Int = (0 until kept).count { values[it] > thresholdNs }

    fun clear() {
        offered = 0
    }
}
