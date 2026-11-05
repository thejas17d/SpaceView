package com.spaceview.app.scan.imaging

import com.spaceview.engine.Homography
import com.spaceview.engine.Vec2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RectifyMathTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    private fun quad(vararg xy: Double) = List(4) { Vec2(xy[2 * it], xy[2 * it + 1]) }

    @Test
    fun axisAlignedFullImageIsIdentity() {
        val w = 64; val h = 64
        val src = IntArray(w * h) { argb(it % w * 4, it / w * 4, (it * 7) and 0xFF) }
        val (out, ow, oh) = RectifyMath.rectify(src, w, h, quad(0.0, 0.0, 64.0, 0.0, 64.0, 64.0, 0.0, 64.0), 1.0, 1024)!!
        assertEquals(64, ow)
        assertEquals(64, oh)
        assertArrayEquals(src, out)
    }

    @Test
    fun counterClockwiseCornersAreNotMirrored() {
        val w = 80; val h = 60
        val src = IntArray(w * h) { argb(it % w * 3, it / w * 4, 0) }
        val cw = quad(10.0, 5.0, 70.0, 8.0, 68.0, 55.0, 12.0, 50.0)
        val ccw = listOf(cw[0], cw[3], cw[2], cw[1])
        val a = RectifyMath.rectify(src, w, h, cw, 1.2, 1024)!!
        val b = RectifyMath.rectify(src, w, h, ccw, 1.2, 1024)!!
        assertArrayEquals(a.first, b.first)
        // Red grows left → right in the source, so it must also grow left → right in the output.
        val row = a.third / 2 * a.second
        assertTrue(r(a.first[row + 2]) < r(a.first[row + a.second - 3]))
    }

    /**
     * Renders a checkerboard texture into a "photo" through a known perspective, then rectifies it back:
     * every cell centre must come back in its own colour.
     */
    @Test
    fun perspectiveQuadIsStraightenedBack() {
        val tw = 400; val th = 200 // W:H = 2, 8 × 4 cells of 50 px
        fun cellColor(cx: Int, cy: Int) = argb(30 + cx * 28, 40 + cy * 50, if ((cx + cy) % 2 == 0) 230 else 20)
        val texCorners = listOf(Vec2(0.0, 0.0), Vec2(tw.toDouble(), 0.0), Vec2(tw.toDouble(), th.toDouble()), Vec2(0.0, th.toDouble()))
        val imgCorners = quad(120.0, 90.0, 520.0, 140.0, 500.0, 330.0, 140.0, 360.0) // keystoned, rotated
        val texToImg = Homography.fromCorrespondences(texCorners, imgCorners)!!
        val imgToTex = texToImg.inverse()
        val iw = 640; val ih = 480
        val photo = IntArray(iw * ih) { i ->
            val t = imgToTex.map(i % iw + 0.5, i / iw + 0.5)
            if (t.x < 0 || t.y < 0 || t.x >= tw || t.y >= th) argb(128, 128, 128)
            else cellColor((t.x / 50).toInt(), (t.y / 50).toInt())
        }
        val (out, ow, oh) = RectifyMath.rectify(photo, iw, ih, imgCorners, 2.0, 1024)!!
        assertEquals(2.0, ow.toDouble() / oh, 0.02)
        for (cy in 0 until 4) for (cx in 0 until 8) {
            val ox = ((cx + 0.5) / 8 * ow).toInt()
            val oy = ((cy + 0.5) / 4 * oh).toInt()
            val got = out[oy * ow + ox]
            val want = cellColor(cx, cy)
            assertTrue("cell $cx,$cy", abs(r(got) - r(want)) <= 2 && abs(g(got) - g(want)) <= 2 && abs(b(got) - b(want)) <= 2)
        }
    }

    @Test
    fun outputNeverExceedsPhotoDetailOrTheCap() {
        val small = RectifyMath.plan(4000, 3000, quad(100.0, 100.0, 400.0, 100.0, 400.0, 300.0, 100.0, 300.0), 1.5, 1024)
        assertEquals(300, small.outWidth)
        assertEquals(200, small.outHeight)
        assertEquals(1, small.prefilter)
        val capped = RectifyMath.plan(4000, 3000, quad(0.0, 0.0, 1800.0, 0.0, 1800.0, 900.0, 0.0, 900.0), 2.0, 1024)
        assertEquals(1024, capped.outWidth)
        assertEquals(512, capped.outHeight)
        assertEquals(1, capped.prefilter) // 1.76:1 is fine for bilinear
        val huge = RectifyMath.plan(4000, 3000, quad(0.0, 0.0, 3900.0, 0.0, 3900.0, 2900.0, 0.0, 2900.0), 1.0, 1024)
        assertEquals(1024, huge.outWidth)
        assertEquals(3, huge.prefilter)
        assertEquals(0, huge.cropLeft % huge.prefilter)
    }

    /** M3 review minor 9: a foreshortened face is prefiltered only along the minified axis. */
    @Test
    fun prefilterIsPerAxis() {
        // 3000 px wide, 300 px tall in the photo, true aspect 1:1 → 1024 × 1024 output.
        val wide = RectifyMath.plan(4000, 3000, quad(100.0, 100.0, 3100.0, 100.0, 3100.0, 400.0, 100.0, 400.0), 1.0, 1024)
        assertEquals(1024, wide.outWidth)
        assertEquals(1024, wide.outHeight)
        assertEquals(2, wide.prefilterX)
        assertEquals(1, wide.prefilterY) // was 2: the short direction lost half its detail
        assertEquals(0, wide.cropLeft % wide.prefilterX)
        // The same face on a sensor image rotated by 90°: the factors swap.
        val tall = RectifyMath.plan(3000, 4000, quad(400.0, 100.0, 400.0, 3100.0, 100.0, 3100.0, 100.0, 100.0), 1.0, 1024)
        assertEquals(1, tall.prefilterX)
        assertEquals(2, tall.prefilterY)
        // And the warp stays aligned: a two-colour source (left half red) keeps the split in the middle.
        val w = 3200; val h = 500
        val src = IntArray(w * h) { i -> if (i % w < 1600) argb(255, 0, 0) else argb(0, 0, 255) }
        val (out, ow, _) = RectifyMath.rectify(src, w, h, quad(100.0, 100.0, 3100.0, 100.0, 3100.0, 400.0, 100.0, 400.0), 1.0, 1024)!!
        assertEquals(argb(255, 0, 0), out[500 * ow + ow / 2 - 20])
        assertEquals(argb(0, 0, 255), out[500 * ow + ow / 2 + 20])
    }

    @Test
    fun portraitAspect() {
        val p = RectifyMath.plan(1000, 1000, quad(0.0, 0.0, 300.0, 0.0, 300.0, 600.0, 0.0, 600.0), 0.5, 1024)
        assertEquals(300, p.outWidth)
        assertEquals(600, p.outHeight)
    }

    @Test
    fun cropIsClampedToTheImage() {
        val p = RectifyMath.plan(100, 80, quad(-5.0, -5.0, 120.0, 0.0, 110.0, 90.0, 0.0, 85.0), 1.0, 1024)
        assertEquals(0, p.cropLeft)
        assertEquals(0, p.cropTop)
        assertEquals(100, p.cropWidth)
        assertEquals(80, p.cropHeight)
        val src = IntArray(100 * 80) { argb(10, 20, 30) }
        val out = RectifyMath.rectify(src, 100, 80, quad(-5.0, -5.0, 120.0, 0.0, 110.0, 90.0, 0.0, 85.0), 1.0, 1024)!!
        assertTrue(out.first.all { it == argb(10, 20, 30) })
    }

    @Test
    fun prefilteredWarpMatchesTheCorners() {
        // A big quad (2.3× the cap) with a distinct colour per quadrant: prefiltering must keep geometry aligned.
        val w = 2400; val h = 2400
        val src = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x < 1200 && y < 1200 -> argb(255, 0, 0)
                x >= 1200 && y < 1200 -> argb(0, 255, 0)
                x < 1200 -> argb(0, 0, 255)
                else -> argb(255, 255, 0)
            }
        }
        val (out, ow, oh) = RectifyMath.rectify(src, w, h, quad(0.0, 0.0, 2400.0, 0.0, 2400.0, 2400.0, 0.0, 2400.0), 1.0, 1024)!!
        assertEquals(1024, ow)
        assertEquals(argb(255, 0, 0), out[100 * ow + 100])
        assertEquals(argb(0, 255, 0), out[100 * ow + ow - 100])
        assertEquals(argb(0, 0, 255), out[(oh - 100) * ow + 100])
        assertEquals(argb(255, 255, 0), out[(oh - 100) * ow + ow - 100])
    }

    @Test
    fun degenerateQuadsAreRejected() {
        val src = IntArray(100 * 100)
        assertNull(RectifyMath.rectify(src, 100, 100, quad(0.0, 0.0, 50.0, 50.0, 100.0, 100.0, 0.0, 100.0), 1.0, 1024)) // collinear
        assertNull(RectifyMath.rectify(src, 100, 100, quad(0.0, 0.0, 100.0, 100.0, 100.0, 0.0, 0.0, 100.0), 1.0, 1024)) // bow-tie
    }

    @Test
    fun boxDownsampleAveragesBlocksIncludingPartialEdges() {
        val src = intArrayOf(
            argb(0, 0, 0), argb(100, 0, 0), argb(50, 0, 0),
            argb(200, 0, 0), argb(100, 0, 0), argb(70, 0, 0),
        )
        val (out, w, h) = RectifyMath.downsampleBox(src, 3, 2, 2)
        assertEquals(2, w)
        assertEquals(1, h)
        assertEquals(100, r(out[0]))
        assertEquals(60, r(out[1]))
        assertEquals(0xFF, out[0] ushr 24)
    }

    @Test
    fun bilinearBlendIsExactAtEndsAndMidpoint() {
        val a = argb(0, 100, 200); val b = argb(200, 100, 0)
        assertEquals(a, RectifyMath.bilerp(a, b, a, b, 0, 0))
        assertEquals(b, RectifyMath.bilerp(a, b, a, b, 256, 256))
        assertEquals(argb(100, 100, 100), RectifyMath.bilerp(a, b, a, b, 128, 0))
    }

    /** Desktop JVM timing of the full 1024 px pipeline from a 1920 × 1440 frame (printed for the report). */
    @Test
    fun timing1024FromFullFrame() {
        val w = 1920; val h = 1440
        val src = IntArray(w * h) { argb(it % 251, it % 241, it % 239) }
        val corners = quad(200.0, 150.0, 1700.0, 220.0, 1650.0, 1300.0, 260.0, 1250.0)
        val cold0 = System.nanoTime()
        var out = RectifyMath.rectify(src, w, h, corners, 1.4, 1024)!!
        val coldMs = (System.nanoTime() - cold0) / 1e6
        repeat(5) { RectifyMath.rectify(src, w, h, corners, 1.4, 1024) } // warm-up (JIT)
        val runs = 5
        val t0 = System.nanoTime()
        repeat(runs) { out = RectifyMath.rectify(src, w, h, corners, 1.4, 1024)!! }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        println("RECTIFY TIMING: ${out.second}x${out.third} from ${w}x$h: first run %.1f ms, warm %.1f ms (desktop JVM)".format(coldMs, ms))
        assertEquals(1024, out.second)
    }
}
