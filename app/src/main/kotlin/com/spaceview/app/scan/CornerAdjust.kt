package com.spaceview.app.scan

import com.spaceview.app.scan.imaging.CornerSnap
import com.spaceview.ar.AimModel
import com.spaceview.ar.FrontFace
import com.spaceview.ar.ImageIntrinsics
import com.spaceview.ar.ScanOutline
import com.spaceview.ar.ScanShape
import com.spaceview.engine.CaptureCamera
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.FaceReprojection
import com.spaceview.engine.Measurement
import com.spaceview.engine.Quality
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Adjust corners" (PLAN v3.1 W8): the outline the Review uses when the user moved the corners of the front face on
 * the frozen photo. Width and height are **re-measured from geometry**: every corner pixel → a ray of the capture
 * camera → the measured face plane → metres ([FaceReprojection]), never scaled from pixel ratios. Depth (a box's
 * measured base, or a frame's preset) is unchanged. Pure (JVM-tested).
 */
object CornerAdjust {
    /**
     * Loupe magnification of the corner editor. With it a finger places a corner to about one photo pixel, so the
     * corner error is `AimModel`'s freeze-frame placement error ([AimModel.ADJUST_IMAGE_PIXELS]).
     */
    const val LOUPE_ZOOM = 3.0

    /** 1σ placement error of a dragged corner, in pixels of the capture photo (`AimModel.adjust`). */
    fun pixelSigma(camera: CaptureCamera): Double {
        val f = sqrt(camera.intrinsics.fx * camera.intrinsics.fy)
        val loc = AimModel.adjust(1.0, ImageIntrinsics(f, camera.widthPx, camera.heightPx), LOUPE_ZOOM)
        return loc?.pixelErrorPx ?: AimModel.ADJUST_IMAGE_PIXELS
    }

    /**
     * The outline for [cornersPx] (x, y pairs in the capture photo: top-left, top-right, bottom-right, bottom-left as
     * seen from the front), or null when a corner misses or grazes the face plane or the quad is degenerate (the
     * editor then keeps the previous corners and says so).
     *
     * The plane is the one through the automatic outline's measured corners; its 1σ along the normal is taken as the
     * measured corners' own 1σ (each side's σ / √2), and never below how far those corners are from planar.
     */
    fun outline(auto: ScanOutline, cornersPx: FloatArray, camera: CaptureCamera, tier: DeviceTier, snapSigmaPx: FloatArray? = null): ScanOutline? {
        require(cornersPx.size == 8)
        if (!isConvexInOrder(cornersPx)) return null
        val plane = FaceReprojection.planeOf(auto.face.corners, auto.face.normal) ?: return null
        val planeSigma = max(
            max(auto.width.sigma, auto.height.sigma) / sqrt(2.0),
            FaceReprojection.planeResidual(auto.face.corners, plane),
        )
        val px = DoubleArray(8) { cornersPx[it].toDouble() }
        // A corner snapped by the sub-pixel refiner uses the refiner's σ (never below its 0.5 px floor) instead of the
        // finger's placement error; every other corner keeps AimModel's.
        val perCorner = snapSigmaPx?.takeIf { it.size == 4 }?.let { s ->
            DoubleArray(4) { k -> s[k].toDouble().takeIf { it.isFinite() }?.coerceAtLeast(CornerSnap.MIN_SIGMA_PX) ?: Double.NaN }
        }
        val face = FaceReprojection.measure(px, camera, plane, pixelSigma(camera), planeSigma, tier, perCorner) ?: return null
        val w = face.width
        val h = face.height
        val diagonal = if (auto.shape == ScanShape.FLAT) {
            val d = sqrt(w.value * w.value + h.value * h.value)
            // Fully correlated errors (an upper bound), as the Review and the Gallery state a diagonal's ±.
            Measurement(d, (w.value * w.sigma + h.value * h.sigma) / d)
        } else {
            null
        }
        val quality = Quality.worst(*listOfNotNull(w, h, auto.depth).map { Quality.of(it) }.toTypedArray())
        return ScanOutline(
            shape = auto.shape,
            width = w,
            height = h,
            depth = auto.depth,
            diagonal = diagonal,
            quality = if (auto.geometryConsistent) quality else Quality.ROUGH,
            calibrated = auto.calibrated,
            geometryConsistent = auto.geometryConsistent,
            face = FrontFace(face.corners, auto.face.normal),
            choice = auto.choice,
        )
    }

    /**
     * True when the corners make a convex quad in their order (no crossed sides, no corner folded inside): every turn
     * has the same sign. A crossed quad would still give numbers, but not the object's width and height.
     */
    fun isConvexInOrder(c: FloatArray): Boolean {
        if (c.size != 8 || c.any { !it.isFinite() }) return false
        var sign = 0
        for (k in 0 until 4) {
            val ax = c[2 * k]; val ay = c[2 * k + 1]
            val bx = c[(2 * k + 2) % 8]; val by = c[(2 * k + 3) % 8]
            val cx = c[(2 * k + 4) % 8]; val cy = c[(2 * k + 5) % 8]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (kotlin.math.abs(cross) < 1e-3f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }
}
