package com.spaceview.ar

import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import java.util.EnumSet
import com.google.ar.core.Pose as ArPose
import com.spaceview.engine.Pose as EnginePose

/**
 * What this device's ARCore can do, detected once per session.
 *
 * @property depthSupported Depth API (depth-from-motion or hardware) is available.
 * @property hardwareDepth a ToF depth sensor is available.
 * @property depthActive depth is actually enabled in this session (user setting + support).
 * @property tier device tier used for the uncertainty prior (follows what is *active*).
 */
data class ArCapabilities(
    val depthSupported: Boolean,
    val hardwareDepth: Boolean,
    val depthActive: Boolean,
    val tier: DeviceTier,
)

/** Session configuration and camera-config selection (docs/research/android-performance.md §2.4–2.5). */
object SessionSetup {

    /**
     * Back camera at a locked 30 fps. Among those, the largest GPU texture (sharp preview and
     * freeze-frame) whose CPU image's long side is ≤ [MAX_CPU_LONG_SIDE] (the CPU stream costs CPU on
     * every frame; it is only read for the freeze-frame still). The hardware depth sensor is used
     * only when depth is wanted.
     */
    fun selectCameraConfig(session: Session, wantDepth: Boolean): CameraConfig = runCatching {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
        if (!wantDepth) filter.setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
        val configs = session.getSupportedCameraConfigs(filter)
        val withinCpuBudget = configs.filter { maxOf(it.imageSize.width, it.imageSize.height) <= MAX_CPU_LONG_SIDE }
        val pool = withinCpuBudget.ifEmpty { configs }
        pool.maxWithOrNull(
            compareBy<CameraConfig> { if (wantDepth && it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE) 1 else 0 }
                .thenBy { it.textureSize.width.toLong() * it.textureSize.height }
                .thenBy { it.imageSize.width.toLong() * it.imageSize.height },
        )
    }.getOrNull() ?: session.cameraConfig

    /** Detects capabilities; [depthWanted] is the user's setting (off by default). */
    fun detect(session: Session, depthWanted: Boolean): ArCapabilities {
        val depthSupported = runCatching { session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
        val hardware = runCatching {
            val f = CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE))
            session.getSupportedCameraConfigs(f).isNotEmpty()
        }.getOrDefault(false)
        val active = depthWanted && depthSupported
        val tier = when {
            active && hardware -> DeviceTier.DEPTH_SENSOR
            active -> DeviceTier.DEPTH
            else -> DeviceTier.NO_DEPTH
        }
        return ArCapabilities(depthSupported, hardware, active, tier)
    }

    /**
     * Applies SpaceView's measuring configuration. Runs inside SceneView's `sessionConfiguration`
     * callback, i.e. after its typed defaults, so these values win.
     */
    fun configure(config: Config, depthActive: Boolean) {
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        // Instant Placement is scale-less until it converges: never for measuring.
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.depthMode = if (depthActive) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        // Ambient intensity is enough for the low-light warning and far cheaper than HDR cubemaps.
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        // Close-range targets (0.3–1 m) need autofocus to stay sharp.
        config.focusMode = Config.FocusMode.AUTO
        config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
        config.geospatialMode = Config.GeospatialMode.DISABLED
    }

    /** CPU-image long-side budget for the camera config (720p class). */
    const val MAX_CPU_LONG_SIDE = 1280
}

/** ARCore pose → engine pose. */
internal fun ArPose.toEngine(): EnginePose =
    EnginePose(Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble()), Quaternion(qx().toDouble(), qy().toDouble(), qz().toDouble(), qw().toDouble()))

/** Translation of an ARCore pose. */
internal fun ArPose.position(): Vec3 = Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

/** Local +Y axis of an ARCore pose (the surface normal for hit poses). */
internal fun ArPose.yAxisVec(): Vec3 {
    val a = yAxis
    return Vec3(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())
}

/** Pure translation pose at [p]. */
internal fun translationPose(p: Vec3): ArPose = ArPose.makeTranslation(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
