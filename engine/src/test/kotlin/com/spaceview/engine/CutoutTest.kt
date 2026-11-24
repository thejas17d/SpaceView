package com.spaceview.engine

import com.spaceview.engine.GlbTestKit.int
import com.spaceview.engine.GlbTestKit.parse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** PLAN v3 W2: the flat cutout GLB, the straight-alpha PNG encoder and the cutout pixel steps. */
@Suppress("UNCHECKED_CAST")
class CutoutTest {
    private val fakeJpeg = ByteArray(1500) { i -> if (i == 0) 0xFF.toByte() else if (i == 1) 0xD8.toByte() else (i * 5).toByte() }

    // ---- GlbWriter.writeCutout -----------------------------------------------------------------------------

    @Test fun opaqueCutoutIsATrueSizeUnlitDoubleSidedQuad() {
        val g = parse(GlbWriter.writeCutout(0.62, 0.41, fakeJpeg, name = "Frame \"A\""))
        val pos = g.floats(0)
        assertEquals(12, pos.size)
        assertEquals(0.62, GlbTestKit.extent(pos, 0), 1e-6)
        assertEquals(0.41, GlbTestKit.extent(pos, 1), 1e-6)
        assertEquals(0.0, GlbTestKit.extent(pos, 2), 0.0)
        assertEquals("centred on the origin", -0.205f, (1 until 12 step 3).minOf { pos[it] }, 1e-6f)
        val mat = g.list("materials")[0]
        assertEquals(true, mat["doubleSided"])
        assertNull("opaque: no blending", mat["alphaMode"])
        assertTrue((mat["extensions"] as Map<String, Any?>).containsKey("KHR_materials_unlit"))
        assertEquals("image/jpeg", g.list("images")[0]["mimeType"])
        val e = g.node0Extras
        assertEquals("CUTOUT", e["modelKind"]); assertEquals("CENTER", e["origin"]); assertEquals(0.0, e["depthM"])
        assertEquals(2.0, e["glbVersion"])
        g.primitives.forEach { GlbTestKit.assertOutwardWinding(g, it) }
        // UV (0,0) is the picture's top-left: the vertex with the highest y and lowest x.
        val uv = g.floats(2)
        assertEquals(0f, uv[0]); assertEquals(0f, uv[1])
        assertTrue(pos[0] < 0 && pos[1] > 0)
        // The image bytes are embedded unchanged.
        val view = g.list("bufferViews")[int(g.list("images")[0], "bufferView")]
        assertArrayEquals(fakeJpeg, g.bin.copyOfRange(int(view, "byteOffset"), int(view, "byteOffset") + int(view, "byteLength")))
    }

    @Test fun alphaCutoutBlendsAPng() {
        val png = PngEncoder.encodeRgba(2, 2, intArrayOf(0xFF102030.toInt(), 0x00102030, 0x80FFFFFF.toInt(), 0xFF000000.toInt()))
        val g = parse(GlbWriter.writeCutout(1.0, 0.5, png, GlbWriter.CutoutAlpha.BLEND))
        assertEquals("BLEND", g.list("materials")[0]["alphaMode"])
        assertEquals("image/png", g.list("images")[0]["mimeType"])
    }

    @Test fun plainCutoutWithoutAPhoto() {
        val g = parse(GlbWriter.writeCutout(0.3, 0.2, null))
        assertNull(g.json["images"])
        val pbr = g.list("materials")[0]["pbrMetallicRoughness"] as Map<String, Any?>
        assertNull(pbr["baseColorTexture"])
        assertNotNull(pbr["baseColorFactor"])
    }

    @Test fun cutoutValidation() {
        fun rejects(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(rejects { GlbWriter.writeCutout(0.0, 1.0, null) })
        assertTrue(rejects { GlbWriter.writeCutout(1.0, Double.NaN, null) })
        assertTrue(rejects { GlbWriter.writeCutout(51.0, 1.0, null) })
        assertTrue("not an image", rejects { GlbWriter.writeCutout(1.0, 1.0, ByteArray(10) { 1 }) })
        assertTrue("blend needs a PNG", rejects { GlbWriter.writeCutout(1.0, 1.0, fakeJpeg, GlbWriter.CutoutAlpha.BLEND) })
        assertTrue(GlbWriter.writeCutout(1.0, 1.0, fakeJpeg).contentEquals(GlbWriter.writeCutout(1.0, 1.0, fakeJpeg)))
    }

    // ---- PngEncoder ----------------------------------------------------------------------------------------

    @Test fun pngRoundTripsStraightAlphaExactly() {
        val w = 37; val h = 23
        val rnd = java.util.Random(5)
        val px = IntArray(w * h) { rnd.nextInt() }
        // Fully transparent pixels keep their colour (what edge bleeding relies on).
        px[0] = 0x00ABCDEF
        val img = ImageIO.read(ByteArrayInputStream(PngEncoder.encodeRgba(w, h, px)))!!
        assertEquals(w, img.width); assertEquals(h, img.height)
        for (y in 0 until h) for (x in 0 until w) assertEquals("($x,$y)", px[y * w + x], img.getRGB(x, y))
    }

    @Test fun pngCompressesFlatAreas() {
        val png = PngEncoder.encodeRgba(512, 512, IntArray(512 * 512) { 0x00808080 })
        assertTrue("a transparent 512² is tiny: ${png.size}", png.size < 4_000)
        fun rejects(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(rejects { PngEncoder.encodeRgba(0, 1, IntArray(0)) })
        assertTrue(rejects { PngEncoder.encodeRgba(2, 2, IntArray(3)) })
    }

    // ---- CutoutPixels --------------------------------------------------------------------------------------

    @Test fun maskBecomesASoftAlphaAndCropsToTheSubject() {
        // 10 × 8 photo, subject = a 4 × 3 block at (3, 2); a soft one-pixel rim around it.
        val w = 10; val h = 8
        val photo = ArgbImage(w, h, IntArray(w * h) { 0xFF000000.toInt() or (it * 3) })
        val conf = FloatArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x in 3..6 && y in 2..4 -> 0.97f
                x in 2..7 && y in 1..5 -> 0.5f
                else -> 0.02f
            }
        }
        val cut = CutoutPixels.applyMask(photo, conf)
        assertEquals(255, cut.argb[2 * w + 3] ushr 24)
        assertEquals(0, cut.argb[0] ushr 24)
        assertEquals("colour kept under alpha 0", photo.argb[0] and 0xFFFFFF, cut.argb[0] and 0xFFFFFF)
        assertEquals(128.0, CutoutPixels.alphaOf(0.5f).toDouble(), 1.0)
        assertArrayEquals(intArrayOf(2, 1, 8, 6), CutoutPixels.alphaBounds(cut))
        assertArrayEquals("solid part only", intArrayOf(3, 2, 7, 5), CutoutPixels.alphaBounds(cut, 255))
        val cropped = CutoutPixels.crop(cut, intArrayOf(3, 2, 7, 5))
        assertEquals(4, cropped.width); assertEquals(3, cropped.height)
        assertEquals(cut.argb[2 * w + 3], cropped.argb[0])
        assertTrue(cropped.isOpaque)
        assertFalse(cut.isOpaque)
        assertNull(CutoutPixels.alphaBounds(ArgbImage(2, 1, intArrayOf(0, 0))))
    }

    @Test fun maskTrust() {
        assertTrue(CutoutPixels.trusted(CutoutPixels.stats(FloatArray(100) { if (it < 40) 0.95f else 0.03f })))
        assertFalse("object not found", CutoutPixels.trusted(CutoutPixels.stats(FloatArray(100) { if (it < 2) 0.95f else 0.0f })))
        assertFalse("confused", CutoutPixels.trusted(CutoutPixels.stats(FloatArray(100) { if (it < 60) 0.55f else 0.1f })))
        assertFalse(CutoutPixels.trusted(CutoutPixels.stats(FloatArray(0))))
        assertEquals(0, CutoutPixels.alphaOf(Float.NaN))
    }

    @Test fun downscaleIsAlphaWeighted() {
        // Red opaque next to a transparent black pixel: the average stays pure red, half transparent (no dark fringe).
        val img = ArgbImage(2, 1, intArrayOf(0xFFFF0000.toInt(), 0x00000000))
        val s = CutoutPixels.downscale(img, 1)
        assertEquals(1, s.width)
        assertEquals(0xFF0000, s.argb[0] and 0xFFFFFF)
        assertEquals(128.0, (s.argb[0] ushr 24).toDouble(), 1.0)
        assertTrue("small enough: unchanged", CutoutPixels.downscale(img, 4) === img)
        val big = CutoutPixels.downscale(ArgbImage(1000, 500, IntArray(500_000) { 0xFF336699.toInt() }), 256)
        assertEquals(256, big.width); assertEquals(128, big.height)
        assertEquals(0xFF336699.toInt(), big.argb[1000])
        assertEquals(0, CutoutPixels.downscale(ArgbImage(4, 4, IntArray(16)), 2).argb[0])
    }

    @Test fun bleedingColoursTransparentPixelsWithoutTouchingAlpha() {
        val w = 30; val h = 1
        val px = IntArray(w) { 0x00000000 }
        px[0] = 0xFF00FF00.toInt()
        val out = CutoutPixels.bleedEdges(ArgbImage(w, h, px), passes = 3)
        for (x in 1..3) assertEquals("ring $x is green", 0x00FF00, out.argb[x] and 0xFFFFFF)
        for (x in 1 until w) assertEquals("alpha unchanged", 0, out.argb[x] ushr 24)
        assertEquals("beyond the rings: the mean visible colour", 0x00FF00, out.argb[w - 1] and 0xFFFFFF)
        val opaque = ArgbImage(1, 1, intArrayOf(0xFF123456.toInt()))
        assertTrue(CutoutPixels.bleedEdges(opaque) === opaque)
        assertEquals(0x808080, CutoutPixels.bleedEdges(ArgbImage(1, 1, intArrayOf(0))).argb[0] and 0xFFFFFF)
    }
}
