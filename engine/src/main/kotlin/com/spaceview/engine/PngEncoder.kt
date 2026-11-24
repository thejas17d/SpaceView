package com.spaceview.engine

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * A small PNG encoder (8-bit RGBA, non-interlaced) for cutout textures (PLAN v3 W2). It writes **straight** (not
 * premultiplied) alpha exactly as given, so the colours bled into transparent pixels survive; Android's
 * `Bitmap.compress(PNG)` cannot do that (a premultiplied bitmap has lost the colour of every fully transparent pixel,
 * which is what makes the dark halo). Each row picks, among Sub, Up and Paeth, the PNG filter with the smallest sum of
 * absolute residuals (the libpng heuristic). Pure JVM; deterministic.
 */
object PngEncoder {
    /**
     * zlib level: 5 with the FILTERED strategy (made for PNG-filtered data). Measured on a 1000 px photo-like cutout
     * (JVM, 2026-11-24): several times faster than level 6 / default strategy, for a PNG about 5 % larger (PLAN v3 gate
     * W1, cutout save time).
     */
    const val DEFAULT_LEVEL = 5

    /** PNG filter types tried per row: 1 Sub, 2 Up, 4 Paeth. */
    private val FILTERS = intArrayOf(1, 2, 4)

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Largest side accepted (the textures are ≤ 1024 px; this only guards an int overflow). */
    const val MAX_SIDE = 8192

    /**
     * @param argb [width] × [height] pixels, row-major, `0xAARRGGBB` with straight alpha.
     * @param level zlib level 0–9.
     */
    fun encodeRgba(width: Int, height: Int, argb: IntArray, level: Int = DEFAULT_LEVEL): ByteArray {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "PNG size must be 1..$MAX_SIDE px, got $width x $height" }
        require(argb.size == width * height) { "Expected ${width * height} pixels, got ${argb.size}" }
        val stride = width * 4
        val raw = ByteArray((stride + 1) * height)
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        val candidate = Array(5) { ByteArray(stride) }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val p = argb[row + x]
                val o = x * 4
                cur[o] = (p shr 16).toByte(); cur[o + 1] = (p shr 8).toByte(); cur[o + 2] = p.toByte(); cur[o + 3] = (p ushr 24).toByte()
            }
            // Sub, Up and Paeth only: None and Average almost never win on photos and cost a full pass each
            // (PLAN v3 gate W1, save time).
            var best = 0
            var bestCost = Long.MAX_VALUE
            for (f in FILTERS) {
                val out = candidate[f]
                var cost = 0L
                for (i in 0 until stride) {
                    val x = cur[i].toInt() and 0xFF
                    val pred = when (f) {
                        1 -> if (i >= 4) cur[i - 4].toInt() and 0xFF else 0
                        2 -> if (y > 0) prev[i].toInt() and 0xFF else 0
                        else -> {
                            val a = if (i >= 4) cur[i - 4].toInt() and 0xFF else 0
                            val b = if (y > 0) prev[i].toInt() and 0xFF else 0
                            val c = if (i >= 4 && y > 0) prev[i - 4].toInt() and 0xFF else 0
                            paeth(a, b, c)
                        }
                    }
                    val r = (x - pred) and 0xFF
                    out[i] = r.toByte()
                    cost += if (r < 128) r else 256 - r
                }
                if (cost < bestCost) { bestCost = cost; best = f }
            }
            val o = y * (stride + 1)
            raw[o] = best.toByte()
            System.arraycopy(candidate[best], 0, raw, o + 1, stride)
            System.arraycopy(cur, 0, prev, 0, stride)
        }
        val deflater = Deflater(level.coerceIn(0, 9)).apply { setStrategy(Deflater.FILTERED) }
        val idat = try {
            deflater.setInput(raw)
            deflater.finish()
            val buf = ByteArrayOutputStream(raw.size / 4 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        } finally {
            deflater.end()
        }
        val out = ByteArrayOutputStream(idat.size + 64)
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        be32(ihdr, 0, width); be32(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 6 // colour type RGBA
        // compression 0, filter 0, interlace 0
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", idat)
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        be32(len, 0, data.size)
        out.write(len)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        val c = ByteArray(4)
        be32(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun be32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
