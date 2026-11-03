package com.spaceview.ar

import android.graphics.ImageFormat
import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.spaceview.engine.Vec3
import kotlin.math.max
import kotlin.math.roundToInt

/** Best surface under the reticle in one frame (main-thread only: holds an ARCore trackable). */
internal class ArHit(
    val position: Vec3,
    val normal: Vec3?,
    val kind: HitKind,
    val distance: Double,
    val trackable: Trackable?,
)

/**
 * Reads ARCore frame data on the frame (main) thread. Each function does only the minimum native
 * work and copies data out; every acquired image / point cloud is closed before returning.
 */
internal object FrameReaders {

    fun tracking(state: TrackingState): CameraTracking = when (state) {
        TrackingState.TRACKING -> CameraTracking.TRACKING
        TrackingState.PAUSED -> CameraTracking.PAUSED
        TrackingState.STOPPED -> CameraTracking.STOPPED
    }

    fun reason(r: TrackingFailureReason?): FailureReason = when (r) {
        TrackingFailureReason.BAD_STATE -> FailureReason.BAD_STATE
        TrackingFailureReason.INSUFFICIENT_LIGHT -> FailureReason.INSUFFICIENT_LIGHT
        TrackingFailureReason.EXCESSIVE_MOTION -> FailureReason.EXCESSIVE_MOTION
        TrackingFailureReason.INSUFFICIENT_FEATURES -> FailureReason.INSUFFICIENT_FEATURES
        TrackingFailureReason.CAMERA_UNAVAILABLE -> FailureReason.CAMERA_UNAVAILABLE
        TrackingFailureReason.NONE, null -> FailureReason.NONE
    }

    /**
     * Hit test at view pixel ([x], [y]) with SpaceView's priority (research §2 item 2):
     * a tracked, non-subsumed plane hit inside its polygon is preferred, **unless** a depth or
     * feature-point hit is clearly nearer (then the plane lies behind the real surface, e.g. the
     * floor under a box). Instant Placement points are never used.
     */
    fun centreHit(frame: Frame, x: Float, y: Float): ArHit? {
        val hits = try { frame.hitTest(x, y) } catch (e: Exception) { return null }
        var plane: ArHit? = null
        var depth: ArHit? = null
        var feature: ArHit? = null
        for (hit in hits) {
            val pose = hit.hitPose
            when (val t = hit.trackable) {
                is Plane -> if (plane == null && t.trackingState == TrackingState.TRACKING && t.subsumedBy == null && t.isPoseInPolygon(pose)) {
                    plane = ArHit(pose.position(), pose.yAxisVec(), HitKind.PLANE, hit.distance.toDouble(), t)
                }
                is DepthPoint -> if (depth == null && t.trackingState == TrackingState.TRACKING) {
                    depth = ArHit(pose.position(), pose.yAxisVec(), HitKind.DEPTH_POINT, hit.distance.toDouble(), t)
                }
                is InstantPlacementPoint -> Unit
                is Point -> if (feature == null && t.trackingState == TrackingState.TRACKING &&
                    t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                ) {
                    feature = ArHit(pose.position(), pose.yAxisVec(), HitKind.FEATURE_POINT, hit.distance.toDouble(), t)
                }
                else -> Unit
            }
        }
        val nonPlane = depth ?: feature
        if (plane == null) return nonPlane
        if (nonPlane == null) return plane
        val tol = max(NEARER_ABS_M, NEARER_REL * plane.distance)
        return if (nonPlane.distance < plane.distance - tol) nonPlane else plane
    }

    /**
     * Copies the point cloud (x, y, z per point, confidence ≥ [minConfidence]) into [out] when its
     * timestamp differs from [lastTimestamp]. Returns (count, timestamp) or null when unchanged.
     */
    fun pointCloud(frame: Frame, lastTimestamp: Long, out: FloatArray, minConfidence: Float = 0.2f): Pair<Int, Long>? {
        return try {
            frame.acquirePointCloud().use { cloud ->
                val ts = cloud.timestamp
                if (ts == lastTimestamp) return null
                val buf = cloud.points
                val n = buf.remaining() / 4
                var k = 0
                val cap = out.size / 3
                for (i in 0 until n) {
                    if (k >= cap) break
                    val px = buf.get(i * 4); val py = buf.get(i * 4 + 1); val pz = buf.get(i * 4 + 2); val c = buf.get(i * 4 + 3)
                    if (c < minConfidence) continue
                    out[k * 3] = px; out[k * 3 + 1] = py; out[k * 3 + 2] = pz
                    k++
                }
                k to ts
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies a window of raw depth around view pixel ([x], [y]) when the raw depth image belongs to
     * this very frame (an older depth image would not match the current camera pose).
     */
    fun depthPatch(frame: Frame, x: Float, y: Float): DepthPatch? {
        return try {
            frame.acquireRawDepthImage16Bits().use { depth ->
                if (depth.timestamp != frame.timestamp) return null
                frame.acquireRawDepthConfidenceImage().use { conf -> copyPatch(frame, depth, conf, x, y) }
            }
        } catch (e: NotYetAvailableException) {
            null
        } catch (e: IllegalStateException) {
            null // depth not enabled
        } catch (e: Exception) {
            null
        }
    }

    private fun copyPatch(frame: Frame, depth: Image, conf: Image, x: Float, y: Float): DepthPatch? {
        val dw = depth.width
        val dh = depth.height
        val norm = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, floatArrayOf(x, y), Coordinates2d.TEXTURE_NORMALIZED, norm)
        val cxPix = (norm[0] * dw).roundToInt()
        val cyPix = (norm[1] * dh).roundToInt()
        val half = max(4, (PATCH_HALF_AT_160 * dw / 160f).roundToInt())
        val x0 = (cxPix - half).coerceIn(0, dw - 1)
        val y0 = (cyPix - half).coerceIn(0, dh - 1)
        val x1 = (cxPix + half).coerceIn(0, dw - 1)
        val y1 = (cyPix + half).coerceIn(0, dh - 1)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w < 2 || h < 2) return null

        val dPlane = depth.planes[0]
        val dBuf = dPlane.buffer
        val dRow = dPlane.rowStride
        val dPix = dPlane.pixelStride
        val cPlane = conf.planes[0]
        val cBuf = cPlane.buffer
        val cRow = cPlane.rowStride
        val cPix = cPlane.pixelStride
        val mm = ShortArray(w * h)
        val cf = ByteArray(w * h)
        val order = java.nio.ByteOrder.nativeOrder()
        dBuf.order(order)
        for (r in 0 until h) for (c in 0 until w) {
            val di = (y0 + r) * dRow + (x0 + c) * dPix
            mm[r * w + c] = dBuf.getShort(di)
            cf[r * w + c] = cBuf.get((y0 + r) * cRow + (x0 + c) * cPix)
        }

        val intr = frame.camera.textureIntrinsics
        val f = intr.focalLength
        val pp = intr.principalPoint
        val dims = intr.imageDimensions
        val sx = dw.toDouble() / dims[0]
        val sy = dh.toDouble() / dims[1]
        return DepthPatch(
            width = w, height = h, x0 = x0, y0 = y0, depthMm = mm, confidence = cf,
            fx = f[0] * sx, fy = f[1] * sy, cx = pp[0] * sx, cy = pp[1] * sy,
            sensorPose = frame.camera.pose.toEngine(),
        )
    }

    /**
     * Intrinsics of the CPU camera image (`Camera.getImageIntrinsics()`), the pixels the aim and
     * freeze-frame errors are defined in (M2 review B1). Null when ARCore cannot provide them.
     */
    fun imageIntrinsics(frame: Frame): ImageIntrinsics? = try {
        val intr = frame.camera.imageIntrinsics
        val f = intr.focalLength
        val dims = intr.imageDimensions
        val focal = (f[0].toDouble() + f[1].toDouble()) / 2
        if (focal.isFinite() && focal > 0 && dims[0] > 0 && dims[1] > 0) ImageIntrinsics(focal, dims[0], dims[1]) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Copies the CPU camera image as NV21 and the mapping from image pixels to view pixels
     * (3 view corners → image), for the freeze-frame. The image is closed before returning. Any
     * failure (conversion, coordinate transform) returns null and never escapes into SceneView's
     * frame callback (M2 review minor 7).
     */
    fun captureStill(frame: Frame, viewW: Int, viewH: Int): StillCapture? = try {
        frame.acquireCameraImage().use { img ->
            if (img.format != ImageFormat.YUV_420_888) {
                null
            } else {
                val nv21 = toNv21(img)
                val view = floatArrayOf(0f, 0f, viewW.toFloat(), 0f, 0f, viewH.toFloat())
                val imagePts = FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.VIEW, view, Coordinates2d.IMAGE_PIXELS, imagePts)
                // View pixels per still pixel along the view's top edge.
                val dx = (imagePts[2] - imagePts[0]).toDouble()
                val dy = (imagePts[3] - imagePts[1]).toDouble()
                val imageSpan = kotlin.math.sqrt(dx * dx + dy * dy)
                val scale = if (imageSpan > 0) viewW / imageSpan else Double.POSITIVE_INFINITY
                StillCapture(nv21, img.width, img.height, imagePts, view, imageIntrinsics(frame), scale)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun toNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val yBuf = y.buffer
        var o = 0
        for (r in 0 until h) {
            val rowStart = r * y.rowStride
            if (y.pixelStride == 1) {
                yBuf.position(rowStart)
                yBuf.get(out, o, w)
                o += w
            } else {
                for (c in 0 until w) out[o++] = yBuf.get(rowStart + c * y.pixelStride)
            }
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val uBuf = u.buffer
        val vBuf = v.buffer
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            out[o++] = vBuf.get(r * v.rowStride + c * v.pixelStride)
            out[o++] = uBuf.get(r * u.rowStride + c * u.pixelStride)
        }
        return out
    }

    /** A depth / feature hit this much nearer than a plane hit wins over it. */
    private const val NEARER_ABS_M = 0.03
    private const val NEARER_REL = 0.03

    /** Depth window half-size, in pixels of a 160-px-wide depth image (≈ ±5° around the reticle). */
    private const val PATCH_HALF_AT_160 = 10f

}

/** CPU camera still for the freeze-frame: NV21 bytes plus image-pixel ↔ view-pixel mapping. */
class StillCapture(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    /** Image-pixel coordinates of the view corners (0,0), (W,0), (0,H), packed x, y. */
    val imagePoints: FloatArray,
    /** The same three view corners in view pixels. */
    val viewPoints: FloatArray,
    /** Intrinsics of this still (it is the CPU image), for the placement error; null if unknown. */
    val intrinsics: ImageIntrinsics? = null,
    /** View pixels per still pixel as drawn (≈3–4 for a VGA still on a phone). */
    val viewPxPerImagePx: Double = Double.POSITIVE_INFINITY,
)
