package com.spaceview.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The camera that took a scan's front photo (PLAN v3.1 W8): its **sensor** pose (ARCore `Camera.getPose()`) and its
 * pinhole intrinsics in the pixels of that photo ([widthPx] × [heightPx], sensor orientation). Kept with the photo so
 * a corner the user drags can be turned back into a point in the room.
 */
class CaptureCamera(val pose: Pose, val intrinsics: CameraIntrinsics, val widthPx: Int, val heightPx: Int) {
    init {
        require(widthPx > 0 && heightPx > 0)
    }
}

/**
 * Re-measures a face from four corner pixels (PLAN v3.1 W8, "Adjust corners"): each pixel → a camera ray → its
 * intersection with the measured plane of the face → metres. Width and height come from that **geometry**, never
 * from pixel ratios, with a ± from the corner placement (pixel error at each corner's range, widened when the ray
 * meets the face obliquely), the face plane's own uncertainty, the device's scale error and its absolute floor.
 *
 * Conventions are those of `Ray.fromPixel` / the `:ar` `ImageProjection`: the sensor camera looks down −Z with +Y up
 * the image, and image rows grow downwards. Pure (JVM-tested).
 */
object FaceReprojection {
    /** The measured face: its corners in the room and both side lengths with their 1σ. */
    class Face(val corners: List<Vec3>, val width: Measurement, val height: Measurement, val meanRangeM: Double)

    /** Pixel (u, v) of world point [p] in [camera]'s photo, or null when it is behind the camera. */
    fun project(p: Vec3, camera: CaptureCamera): DoubleArray? {
        val local = camera.pose.rotation.inverse().rotate(p - camera.pose.translation)
        val depth = -local.z
        if (depth < MIN_DEPTH_M) return null
        val i = camera.intrinsics
        return doubleArrayOf(i.cx + i.fx * local.x / depth, i.cy - i.fy * local.y / depth)
    }

    /** The point of [plane] seen at pixel ([u], [v]), or null for a ray that misses it or grazes it. */
    fun lift(u: Double, v: Double, camera: CaptureCamera, plane: Plane): Vec3? =
        plane.intersect(Ray.fromPixel(camera.pose, camera.intrinsics, u, v), MIN_COS_INCIDENCE)

    /**
     * Width and height of the quad whose corners are at [cornersPx] (x, y pairs: top-left, top-right, bottom-right,
     * bottom-left as seen from the front) on [plane]. Width = mean of the top and bottom edges, height = mean of the
     * left and right edges. Null when a corner misses the plane, grazes it, or the quad is degenerate.
     *
     * @param pixelSigma 1σ placement error of each corner in photo pixels (`AimModel.adjust`).
     * @param cornerPixelSigmas per-corner override of [pixelSigma] (e.g. a corner snapped by the sub-pixel refiner);
     *   a non-finite entry uses [pixelSigma].
     * @param planeSigmaM 1σ of the plane's position along its normal (from the original measurement).
     * @param tier scale error and absolute floor of the device (the same terms every measured length gets).
     */
    fun measure(
        cornersPx: DoubleArray,
        camera: CaptureCamera,
        plane: Plane,
        pixelSigma: Double,
        planeSigmaM: Double,
        tier: DeviceTier,
        cornerPixelSigmas: DoubleArray? = null,
    ): Face? {
        require(cornersPx.size == 8) { "Four corners (x, y) expected" }
        require(cornerPixelSigmas == null || cornerPixelSigmas.size == 4) { "Four corner σ expected" }
        require(pixelSigma.isFinite() && pixelSigma >= 0 && planeSigmaM.isFinite() && planeSigmaM >= 0)
        if (cornersPx.any { !it.isFinite() }) return null
        val corners = ArrayList<Vec3>(4)
        val cornerSigma = DoubleArray(4)
        var rangeSum = 0.0
        val f = sqrt(camera.intrinsics.fx * camera.intrinsics.fy)
        for (k in 0 until 4) {
            val ray = Ray.fromPixel(camera.pose, camera.intrinsics, cornersPx[2 * k], cornersPx[2 * k + 1])
            val t = plane.intersectDistance(ray, MIN_COS_INCIDENCE) ?: return null
            corners += ray.pointAt(t)
            val cos = abs(plane.normal dot ray.direction)
            // Lateral error on the face: angle × range, stretched by the obliquity (conservative: along the tilt).
            val sigmaPx = cornerPixelSigmas?.get(k)?.takeIf { it.isFinite() && it >= 0 } ?: pixelSigma
            cornerSigma[k] = sigmaPx / f * t / cos
            rangeSum += t
        }
        fun len(a: Int, b: Int) = (corners[b] - corners[a]).length
        val top = len(0, 1); val bottom = len(3, 2); val left = len(0, 3); val right = len(1, 2)
        if (minOf(top, bottom, left, right) < MIN_SIDE_M) return null
        val w = (top + bottom) / 2
        val h = (left + right) / 2
        val range = rangeSum / 4
        fun sq(x: Double) = x * x
        // Each edge: its two corners; the mean of two edges halves the variance of the sum.
        val cornersVar = (sq(cornerSigma[0]) + sq(cornerSigma[1]) + sq(cornerSigma[2]) + sq(cornerSigma[3])) / 4
        fun sigma(len: Double, cornersVar: Double): Double {
            val planeTerm = len * planeSigmaM / range // a plane shift scales the face by (range + δ) / range
            val scaleTerm = len * tier.scaleSigmaRel
            return sqrt(cornersVar + sq(planeTerm) + sq(scaleTerm) + sq(tier.floorLengthSigma))
        }
        return Face(corners, Measurement(w, sigma(w, cornersVar)), Measurement(h, sigma(h, cornersVar)), range)
    }

    /**
     * The face plane through four measured corners: the least-squares normal (Newell's method) through their centroid,
     * oriented like [towards] (the side the face is viewed from). Null for degenerate corners.
     */
    fun planeOf(corners: List<Vec3>, towards: Vec3? = null): Plane? {
        require(corners.size == 4)
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in 0 until 4) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        val n = Vec3(nx, ny, nz)
        if (n.length < 1e-12) return null
        val c = (corners[0] + corners[1] + corners[2] + corners[3]) / 4.0
        val plane = Plane.fromPointNormal(c, n)
        return if (towards != null && (plane.normal dot towards) < 0) plane.flipped() else plane
    }

    /** Largest distance of [corners] from [plane] (m): how non-planar the measured quad was. */
    fun planeResidual(corners: List<Vec3>, plane: Plane): Double = corners.maxOf { abs(plane.signedDistance(it)) }

    /** A corner ray must meet the face at least this steeply (cos of the incidence angle; ≈ 84°). */
    const val MIN_COS_INCIDENCE = 0.1

    /** Corners closer than this along the view axis are behind the camera. */
    const val MIN_DEPTH_M = 0.05

    /** Sides shorter than this (m) make a degenerate quad. */
    const val MIN_SIDE_M = 0.005
}
