package com.spaceview.app.measure

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import com.spaceview.ar.MeasureMode
import com.spaceview.ar.MeasurementComposer
import com.spaceview.ar.OverlayFrame
import com.spaceview.ar.ScreenPoint
import com.spaceview.ar.ViewProjection
import com.spaceview.engine.Vec3
import kotlin.math.hypot

/**
 * Draws drafting-style dimension lines (1.5 dp signal-yellow line on a graphite outline, end ticks,
 * endpoint dots) for 3-D points projected with a [ViewProjection]. Shared by the live AR overlay
 * and the freeze-frame view.
 */
class DimensionPainter(density: Float) {
    private val d = density
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRAPHITE; strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SIGNAL; strokeWidth = 1.75f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(6f * d, 5f * d), 0f) }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PAPER; style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRAPHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }

    /**
     * @param points committed points; [live] the moving reticle point (drawn as the last vertex).
     */
    fun draw(canvas: Canvas, vp: ViewProjection, mode: MeasureMode, points: List<Vec3>, live: Vec3?) {
        val all = if (live != null) points + live else points
        if (all.isEmpty()) return
        when (mode) {
            MeasureMode.DISTANCE -> if (all.size >= 2) segment(canvas, vp, all[0], all[1], ticks = true, dashedLine = false)
            MeasureMode.HEIGHT -> if (all.size >= 2) {
                val a = all[0]
                val b = all[1]
                val foot = Vec3(a.x, b.y, a.z)
                segment(canvas, vp, a, foot, ticks = true, dashedLine = false)
                segment(canvas, vp, foot, b, ticks = false, dashedLine = true)
            }
            MeasureMode.RECTANGLE -> {
                val outlinePts = MeasurementComposer.rectangleOutline(all.take(3))
                for (i in 0 until outlinePts.size - 1) segment(canvas, vp, outlinePts[i], outlinePts[i + 1], ticks = i == 0 || i == 1, dashedLine = false)
                if (outlinePts.size == 4) {
                    segment(canvas, vp, outlinePts[2], outlinePts[3], ticks = false, dashedLine = true)
                    segment(canvas, vp, outlinePts[3], outlinePts[0], ticks = false, dashedLine = true)
                }
            }
            MeasureMode.BOX -> {
                // Base outline (W along the first edge, D along the second), then a vertical to the top point.
                val base = MeasurementComposer.rectangleOutline(all.take(3))
                for (i in 0 until base.size - 1) segment(canvas, vp, base[i], base[i + 1], ticks = true, dashedLine = false)
                if (base.size == 4) {
                    segment(canvas, vp, base[2], base[3], ticks = false, dashedLine = true)
                    segment(canvas, vp, base[3], base[0], ticks = false, dashedLine = true)
                }
                if (all.size >= 4) {
                    val top = all[3]
                    // The same tapped base corner H is measured from (M3 review m8).
                    val foot = all[MeasurementComposer.boxFoot(all.take(3), top)]
                    val lifted = Vec3(foot.x, top.y, foot.z)
                    segment(canvas, vp, foot, lifted, ticks = true, dashedLine = false)
                    segment(canvas, vp, lifted, top, ticks = false, dashedLine = true)
                }
            }
        }
        for (p in points) vp.project(p)?.let { dot(canvas, it) }
    }

    private fun segment(canvas: Canvas, vp: ViewProjection, a: Vec3, b: Vec3, ticks: Boolean, dashedLine: Boolean) {
        val (sa, sb) = vp.projectSegment(a, b) ?: return
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, outline)
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, if (dashedLine) dashed else line)
        if (ticks) {
            tick(canvas, sa, sb)
            tick(canvas, sb, sa)
        }
    }

    /** Short extension tick at [at], perpendicular to the line towards [other]. */
    private fun tick(canvas: Canvas, at: ScreenPoint, other: ScreenPoint) {
        val dx = other.x - at.x
        val dy = other.y - at.y
        val len = hypot(dx, dy)
        if (len < 1f) return
        val nx = -dy / len * TICK_HALF_DP * d
        val ny = dx / len * TICK_HALF_DP * d
        canvas.drawLine(at.x - nx, at.y - ny, at.x + nx, at.y + ny, outline)
        canvas.drawLine(at.x - nx, at.y - ny, at.x + nx, at.y + ny, line)
    }

    fun dot(canvas: Canvas, p: ScreenPoint, radiusDp: Float = 5f) {
        canvas.drawCircle(p.x, p.y, radiusDp * d, dotFill)
        canvas.drawCircle(p.x, p.y, radiusDp * d, dotRing)
    }

    companion object {
        const val SIGNAL = 0xFFFFD400.toInt()
        const val GRAPHITE = 0xFF16181B.toInt()
        const val PAPER = 0xFFF6F5F1.toInt()
        private const val TICK_HALF_DP = 7f
    }
}

/**
 * Live AR overlay: redrawn from [OverlayFrame] whenever the measure session reports a new frame
 * (via [invalidate]); Compose is not involved, so the per-frame line never recomposes the UI.
 */
class MeasureOverlayView(context: Context) : View(context) {
    private val painter = DimensionPainter(resources.displayMetrics.density)
    var frame: OverlayFrame? = null

    init {
        // Purely visual; TalkBack gets the reading from the Compose HUD.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        if (f.hidden || !f.pointsVisible) return
        val vp = f.projection ?: return
        if (vp.widthPx != width || vp.heightPx != height) return
        painter.draw(canvas, vp, f.mode, f.points, f.live)
    }
}
