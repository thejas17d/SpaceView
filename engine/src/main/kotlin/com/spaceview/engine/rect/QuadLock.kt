package com.spaceview.engine.rect

import com.spaceview.engine.CaptureCamera
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.FaceReprojection
import com.spaceview.engine.Plane
import com.spaceview.engine.Vec2
import com.spaceview.engine.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max

/** Metric check of a lifted quad (research §4.3 item 6: what kills table edges, shadows and inner borders). */
class Squareness(val maxAngleDevDeg: Double, val sideMismatch: Double) {
    val isRectangle: Boolean get() = maxAngleDevDeg <= QuadLock.MAX_ANGLE_DEV_DEG && sideMismatch <= QuadLock.MAX_SIDE_MISMATCH
}

/**
 * W9 temporal lock and fusion of a detected rectangle (research §4.3 item 8, §5.1 W9.3).
 *
 * Every frame the caller [offer]s the detector's best quad (pixels, sub-pixel σ), the frame's camera
 * ([CaptureCamera]: pose + its own intrinsics) and the plane the object lies on (W10 `SupportPlane`). The quad is
 * lifted to 3-D, checked to be a metric rectangle and viewed within [MAX_LOCK_VIEW_DEG] of its normal. After
 * [STABLE_FRAMES] consecutive frames that agree within max([STABLE_ABS_M], [STABLE_REL] × range) per corner, it is
 * **locked**; further frames are fused (3-D mean per corner, up to [MAX_FUSED]). Anything else restarts the count, so a
 * moving, unstable or non-rectangular quad never locks and the caller falls back to the guided flow with a hint.
 *
 * Not thread-safe; one instance per AR session (owned by the measuring worker).
 */
class QuadLock(private val tier: DeviceTier = DeviceTier.NO_DEPTH) {
    /** Why the last offer did not count. */
    enum class Reject { NOT_ON_PLANE, NOT_RECTANGLE, TOO_STEEP, MOVED }

    /**
     * A locked, fused rectangle.
     *
     * @property corners fused 3-D corners: top-left, top-right, bottom-right, bottom-left as seen in the image.
     * @property face W × H with their ± (`FaceReprojection.measure` of the latest frame: corner, plane, scale and floor
     *   terms; the fused value is never given a smaller ± than one frame's).
     */
    class Locked(val corners: List<Vec3>, val face: FaceReprojection.Face, val frames: Int)

    private val sums = Array(4) { doubleArrayOf(0.0, 0.0, 0.0) }
    private var count = 0
    private var stable = 0
    private var last: List<Vec3>? = null

    /** Consecutive agreeing frames so far (0..[STABLE_FRAMES]) for a progress ring. */
    val progress: Double get() = (stable.toDouble() / STABLE_FRAMES).coerceAtMost(1.0)

    /** The last rejection reason (null after an accepted frame). */
    var lastReject: Reject? = null
        private set

    /** The 3-D corners of the last accepted frame (for drawing the outline), null when none. */
    var lastCorners: List<Vec3>? = null
        private set

    /** The locked result, or null while not locked. */
    var locked: Locked? = null
        private set

    fun reset() {
        for (s in sums) s.fill(0.0)
        count = 0
        stable = 0
        last = null
        locked = null
        lastReject = null
        lastCorners = null
    }

    /**
     * Offers one frame. [cornersPx] in [camera]'s pixels (any order), [sigmaPx] the worst corner σ, [plane] the plane
     * the object lies on with its σ [planeSigmaM]. Returns the locked result (after this frame) or null.
     */
    fun offer(cornersPx: List<Vec2>, sigmaPx: Double, camera: CaptureCamera, plane: Plane, planeSigmaM: Double): Locked? {
        val ordered = orderCorners(cornersPx)
        val flat = DoubleArray(8) { i -> if (i % 2 == 0) ordered[i / 2].x else ordered[i / 2].y }
        val face = FaceReprojection.measure(flat, camera, plane, max(sigmaPx, EdgeLine.MIN_SIGMA_PX), planeSigmaM, tier)
            ?: return reject(Reject.NOT_ON_PLANE)
        val c = face.corners
        if (!squareness(c).isRectangle) return reject(Reject.NOT_RECTANGLE)
        val centre = (c[0] + c[1] + c[2] + c[3]) / 4.0
        val view = (centre - camera.pose.translation).normalized()
        val viewDeg = Math.toDegrees(acos(abs(view dot plane.normal).coerceIn(0.0, 1.0)))
        if (viewDeg > MAX_LOCK_VIEW_DEG) return reject(Reject.TOO_STEEP)
        val tol = max(STABLE_ABS_M, STABLE_REL * face.meanRangeM)
        val prev = mean() ?: last
        if (prev != null && (0 until 4).any { prev[it].distanceTo(c[it]) > tol }) {
            // Once locked, a frame that disagrees is an outlier and is skipped; before, a different quad (or a jump)
            // starts the count again from this frame.
            if (locked != null) return reject(Reject.MOVED)
            reset()
        }
        lastReject = null
        last = c
        lastCorners = c
        stable++
        if (stable >= STABLE_FRAMES && count < MAX_FUSED) {
            for (k in 0 until 4) {
                sums[k][0] += c[k].x; sums[k][1] += c[k].y; sums[k][2] += c[k].z
            }
            count++
        }
        if (count > 0) {
            val m = mean()!!
            val w = ((m[1] - m[0]).length + (m[2] - m[3]).length) / 2
            val h = ((m[3] - m[0]).length + (m[2] - m[1]).length) / 2
            val fused = FaceReprojection.Face(
                m, com.spaceview.engine.Measurement(w, face.width.sigma), com.spaceview.engine.Measurement(h, face.height.sigma), face.meanRangeM,
            )
            locked = Locked(m, fused, count)
        }
        return locked
    }

    private fun reject(r: Reject): Locked? {
        lastReject = r
        if (locked == null) {
            stable = 0
            last = null
        }
        return locked
    }

    private fun mean(): List<Vec3>? =
        if (count == 0) null else List(4) { k -> Vec3(sums[k][0] / count, sums[k][1] / count, sums[k][2] / count) }

    companion object {
        /** Consecutive agreeing frames before the lock (research: N = 8). */
        const val STABLE_FRAMES = 8

        /** Frames fused at most after the lock. */
        const val MAX_FUSED = 30

        /** Per-corner agreement between frames: absolute part (m) … */
        const val STABLE_ABS_M = 0.0015

        /** … and relative to the range. */
        const val STABLE_REL = 0.003

        /** The lock needs the view within this angle of the face normal (detection alone allows 60°). */
        const val MAX_LOCK_VIEW_DEG = 45.0

        /** Metric rectangle: every 3-D corner angle within 90° ± this … */
        const val MAX_ANGLE_DEV_DEG = 4.0

        /** … and opposite sides equal within this fraction. */
        const val MAX_SIDE_MISMATCH = 0.03

        /**
         * The detector's candidates are outermost first; the one to use is the first that is a **metric rectangle** on
         * [plane] (research §4.3 items 6 and 9): this drops quads closed by texture, shadows or a table edge and keeps a
         * frame's outer border over its inner one. Null when none is.
         */
        fun pickRectangle(quads: List<DetectedQuad>, camera: CaptureCamera, plane: Plane): DetectedQuad? =
            quads.firstOrNull { isMetricRectangle(it.corners, camera, plane, 1.0) }

        /**
         * Detects and picks in one pass (W9 budget): candidates are pre-checked on their coarse corners (with twice the
         * tolerance, since coarse corners are ± a few px) before refinement, and the refined one is checked again.
         */
        fun detectRectangle(detector: QuadDetector, image: GrayImage, camera: CaptureCamera, plane: Plane): DetectedQuad? =
            detector.detect(image) { coarse -> isMetricRectangle(coarse, camera, plane, 2.0) }
                .firstOrNull { isMetricRectangle(it.corners, camera, plane, 1.0) }

        /** True when [cornersPx] lift onto [plane] as a rectangle within [slack] × the tolerances. */
        fun isMetricRectangle(cornersPx: List<Vec2>, camera: CaptureCamera, plane: Plane, slack: Double): Boolean {
            val c = orderCorners(cornersPx).map { FaceReprojection.lift(it.x, it.y, camera, plane) ?: return false }
            val sq = squareness(c)
            return sq.maxAngleDevDeg <= MAX_ANGLE_DEV_DEG * slack && sq.sideMismatch <= MAX_SIDE_MISMATCH * slack
        }

        /** Corner angles and opposite-side mismatch of a 3-D quad (in order). */
        fun squareness(c: List<Vec3>): Squareness {
            var dev = 0.0
            for (i in 0 until 4) {
                val u = (c[(i + 3) % 4] - c[i]).normalized()
                val v = (c[(i + 1) % 4] - c[i]).normalized()
                dev = max(dev, abs(Math.toDegrees(acos((u dot v).coerceIn(-1.0, 1.0))) - 90.0))
            }
            fun len(a: Int, b: Int) = (c[b] - c[a]).length
            val m1 = abs(len(0, 1) - len(3, 2)) / max(len(0, 1), len(3, 2))
            val m2 = abs(len(0, 3) - len(1, 2)) / max(len(0, 3), len(1, 2))
            return Squareness(dev, max(m1, m2))
        }

        /**
         * The 4 corners clockwise on screen (image y down) starting at the top-left one (smallest x + y): top-left,
         * top-right, bottom-right, bottom-left as seen. The order `FaceReprojection.measure` expects.
         */
        fun orderCorners(c: List<Vec2>): List<Vec2> {
            require(c.size == 4)
            val cx = c.sumOf { it.x } / 4
            val cy = c.sumOf { it.y } / 4
            val cw = c.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = cw.indices.minBy { cw[it].x + cw[it].y }
            return List(4) { cw[(start + it) % 4] }
        }
    }
}
