package com.spaceview.ar

import org.junit.Assert.assertEquals
import org.junit.Test

class Nv21Test {
    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun image(y: Int, u: Int, v: Int, w: Int = 4, h: Int = 2): Nv21Image {
        val bytes = ByteArray(w * h * 3 / 2)
        for (i in 0 until w * h) bytes[i] = y.toByte()
        var i = w * h
        while (i < bytes.size) {
            bytes[i] = v.toByte()
            bytes[i + 1] = u.toByte()
            i += 2
        }
        return Nv21Image(bytes, w, h)
    }

    @Test
    fun neutralChromaIsGrey() {
        val px = Nv21.toArgb(image(128, 128, 128))
        assertEquals(8, px.size)
        for (p in px) {
            assertEquals(0xFF, (p ushr 24) and 0xFF)
            assertEquals(Triple(128, 128, 128), rgb(p))
        }
    }

    @Test
    fun fullRangeRed() {
        // JFIF red: Y 76, U 85, V 255.
        val (r, g, b) = rgb(Nv21.toArgb(image(76, 85, 255))[0])
        assertEquals(254.0, r.toDouble(), 2.0)
        assertEquals(0.0, g.toDouble(), 2.0)
        assertEquals(0.0, b.toDouble(), 2.0)
    }

    @Test
    fun clampsAndKeepsBlackAndWhite() {
        assertEquals(Triple(0, 0, 0), rgb(Nv21.toArgb(image(0, 128, 128))[3]))
        assertEquals(Triple(255, 255, 255), rgb(Nv21.toArgb(image(255, 128, 128))[5]))
        // Extreme chroma never wraps around.
        val (r, _, b) = rgb(Nv21.toArgb(image(250, 255, 255))[0])
        assertEquals(255, r)
        assertEquals(255, b)
    }

    @Test
    fun chromaIsSharedPerTwoByTwoBlock() {
        val w = 4; val h = 2
        val bytes = ByteArray(w * h * 3 / 2) { 128.toByte() }
        // Second chroma block (columns 2–3): strong blue (U high).
        bytes[w * h + 2] = 128.toByte()
        bytes[w * h + 3] = 255.toByte()
        val px = Nv21.toArgb(Nv21Image(bytes, w, h))
        assertEquals(rgb(px[0]), rgb(px[1]))
        assertEquals(rgb(px[2]), rgb(px[7]))
        assertEquals(255, rgb(px[2]).third)
        assertEquals(128, rgb(px[0]).third)
    }

    /** M3 review m16: odd sizes have ⌈w/2⌉ × ⌈h/2⌉ chroma samples; the last row / column uses the last one. */
    @Test
    fun oddSizesConvertWithoutOverrun() {
        val w = 3; val h = 3
        val bytes = ByteArray(w * h + 2 * 2 * 2) { 128.toByte() }
        // Chroma sample (1, 1) — covering only the last column and row — is strong red.
        val last = w * h + (1 * 2 + 1) * 2
        bytes[last] = 255.toByte() // V
        val px = Nv21.toArgb(Nv21Image(bytes, w, h))
        assertEquals(9, px.size)
        assertEquals(Triple(128, 128, 128), rgb(px[0]))
        assertEquals(255, rgb(px[8]).first) // bottom-right pixel uses the last chroma sample
        assertEquals(128, rgb(px[1]).first)
    }

    /** Padded rows (rowStride > width) and interleaved chroma (pixelStride 2), as camera images deliver them. */
    @Test
    fun fromPlanesHonoursStrides() {
        val w = 4; val h = 2; val rowStride = 8
        val y = java.nio.ByteBuffer.allocate(rowStride * h)
        for (r in 0 until h) for (c in 0 until rowStride) y.put(r * rowStride + c, (if (c < w) 10 * r + c else 99).toByte())
        // Interleaved UV: U at even, V at odd offsets of one buffer; one chroma row, padded.
        val uv = java.nio.ByteBuffer.allocate(rowStride)
        for (c in 0 until rowStride) uv.put(c, (if (c % 2 == 0) 50 + c else 200 + c).toByte())
        val u = uv.duplicate()
        val v = java.nio.ByteBuffer.wrap(uv.array(), 1, rowStride - 1).slice()
        val nv21 = Nv21.fromPlanes(w, h, y, rowStride, 1, u, rowStride, 2, v, rowStride, 2)
        assertEquals(w * h * 3 / 2, nv21.size)
        assertEquals(listOf(0, 1, 2, 3, 10, 11, 12, 13), nv21.take(8).map { it.toInt() and 0xFF })
        // NV21 = V, U pairs.
        assertEquals(listOf(201, 50, 203, 52), nv21.drop(8).map { it.toInt() and 0xFF })
        // The caller's buffer position is untouched.
        assertEquals(0, y.position())
    }

    @Test
    fun fromPlanesPlanarChromaAndOddSize() {
        val w = 3; val h = 1
        val y = java.nio.ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val u = java.nio.ByteBuffer.wrap(byteArrayOf(20, 21))
        val v = java.nio.ByteBuffer.wrap(byteArrayOf(30, 31))
        val nv21 = Nv21.fromPlanes(w, h, y, 3, 1, u, 2, 1, v, 2, 1)
        assertEquals(listOf(1, 2, 3, 30, 20, 31, 21), nv21.map { it.toInt() })
        Nv21.toArgb(Nv21Image(nv21, w, h)) // no overrun
    }
}
