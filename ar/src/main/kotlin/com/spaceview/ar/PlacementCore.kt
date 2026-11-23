package com.spaceview.ar

import com.spaceview.engine.Plane
import com.spaceview.engine.Pose
import com.spaceview.engine.Ray
import com.spaceview.engine.Vec3

/** Placement state (§3.3). Poses are the **model** (GLB origin) pose in world space. */
sealed interface PlacementState {
    /** No usable surface under the reticle for the current target. */
    data object Searching : PlacementState

    /** The item follows the reticle on a valid surface. */
    data class Preview(val pose: Pose, val surface: SurfaceTarget) : PlacementState

    /**
     * The item is dropped. [anchored] is false only while the anchor request is outstanding; [tracking] is false
     * while ARCore is not tracking the anchor (the item is then hidden at its last pose).
     */
    data class Placed(val pose: Pose, val anchored: Boolean, val surface: SurfaceTarget, val tracking: Boolean = true) : PlacementState

    /** Building the wall fallback: [pointsTapped] of 2 points on the wall–floor line. */
    data class FallbackLine(val pointsTapped: Int) : PlacementState
}

/**
 * What the Place screen shows (throttled to ≤ 10 Hz by [PlacementController]).
 *
 * @property offerWallFallback no wall found for [PlacementCore.FALLBACK_OFFER_NS]: offer "Use the wall–floor line".
 * @property fallbackRejected the last two taps did not make a usable line (too short or not on the floor).
 * @property spinDeg rotation about the surface normal, whole degrees in (−180, 180].
 * @property centreHeightM wall items: the item's centre height above the floor, when the floor is known.
 * @property pauses how many times the session paused or was re-created (a reading taken before the latest one is
 *   "measured before the pause").
 */
data class PlacementStatus(
    val state: PlacementState = PlacementState.Searching,
    val target: SurfaceTarget = SurfaceTarget.FLOOR,
    val offerWallFallback: Boolean = false,
    val fallbackRejected: Boolean = false,
    val spinDeg: Int = 0,
    val centreHeightM: Double? = null,
    val cameraTracking: Boolean = false,
    val pauses: Int = 0,
)

/**
 * One frame's surface under the reticle (copied out of ARCore).
 *
 * @property onPlane the hit lies on a tracked ARCore plane (the floor estimate trusts these more than single points).
 */
class SurfaceHit(val position: Vec3, val normal: Vec3, val onPlane: Boolean = true)

/** What the controller must anchor for a drop: the **surface** pose (touching point, surface frame). */
class DropRequest(val surfacePose: Pose)

/**
 * The placement state machine, free of ARCore types so it is JVM-tested ([PlacementController] is the thin ARCore
 * adapter). Main thread only. Time comes only from frame timestamps; a frame whose timestamp equals the previous
 * one (SceneView repeats the camera frame at display rate, the M3 B1 lesson) changes nothing.
 *
 * Placed geometry: the anchor carries the surface pose at drop time; the model pose is
 * `anchorPose * Pose(localOffset) * spin * attachOffset`, so nudges and spins are relative to the anchor and follow
 * it when ARCore refines it. Scale never appears: the model is always 1:1.
 */
class PlacementCore(val item: PlacedItem, initialTarget: SurfaceTarget) {
    var target: SurfaceTarget = initialTarget
        private set
    private var currentState: PlacementState = PlacementState.Searching
    private var spinRad = 0.0
    private var lastTimestamp = Long.MIN_VALUE
    private var cameraTracking = false
    private var cameraPose: Pose? = null

    /** Last valid surface pose under the reticle (what a drop anchors). */
    private var previewSurface: Pose? = null
    private var anchorPose: Pose? = null
    private var localOffset = Vec3.ZERO
    private var placedSurface: SurfaceTarget = initialTarget

    /** The floor (robust lowest horizontal surface, N1), for the wall height readout and the fallback. */
    private val floor = FloorEstimator()
    private var lastHitPos: Vec3? = null
    private var lastHitNormal: Vec3? = null
    private var pauses = 0

    private var wallSearchSinceNs = Long.MIN_VALUE
    private var offerFallback = false
    private var fallbackRejected = false
    private val fallbackPoints = ArrayList<Vec3>(2)
    private var fallbackPlane: Plane? = null

    /**
     * N5: the placed model pose is recomputed only when its inputs change (a new anchor pose, a command), so a steady
     * placed item costs no allocation per frame and [modelPose] keeps the same instance (the node is not re-set).
     */
    private var placedCache: Pose? = null

    /** The pose to render now, or null to hide the item. */
    var modelPose: Pose? = null
        private set

    val status: PlacementStatus
        get() = PlacementStatus(
            state = currentState,
            target = target,
            offerWallFallback = offerFallback && target == SurfaceTarget.WALL,
            fallbackRejected = fallbackRejected,
            spinDeg = Math.round(Math.toDegrees(spinRad)).toInt(),
            centreHeightM = centreHeight(),
            cameraTracking = cameraTracking,
            pauses = pauses,
        )

    /** The current state (no allocation; [status] builds a full snapshot). */
    val state: PlacementState get() = currentState

    /** Whether the camera tracked on the last frame (no allocation). */
    val cameraTrackingNow: Boolean get() = cameraTracking


    /**
     * One ARCore frame. [hit] is the reticle hit (any surface); [anchorPose] the current pose of the drop anchor
     * when it is tracked (null otherwise). Returns false (and changes nothing) for a repeated timestamp.
     */
    fun onFrame(timestampNs: Long, tracking: Boolean, camera: Pose, hit: SurfaceHit?, anchorPose: Pose?): Boolean =
        onFrame(timestampNs, tracking, camera, hit?.position, hit?.normal, anchorPose, hit?.onPlane ?: false)

    /**
     * [onFrame] without a [SurfaceHit] wrapper (the per-frame path: no allocation for the hit, R3). [hitOnPlane]: the
     * hit lies on a tracked ARCore plane. Pass the same [anchorPose] instance while the anchor has not moved (N5).
     */
    fun onFrame(
        timestampNs: Long,
        tracking: Boolean,
        camera: Pose,
        hitPos: Vec3?,
        hitNormal: Vec3?,
        anchorPose: Pose?,
        hitOnPlane: Boolean = false,
    ): Boolean {
        if (timestampNs == lastTimestamp) return false
        lastTimestamp = timestampNs
        cameraTracking = tracking
        cameraPose = camera
        val usable = tracking && hitPos != null && hitNormal != null
        lastHitPos = if (usable) hitPos else null
        lastHitNormal = if (usable) hitNormal else null
        if (usable && PlacementMath.isHorizontal(hitNormal!!)) floor.add(hitPos!!.x, hitPos.y, hitPos.z, hitOnPlane, timestampNs)
        when (val s = currentState) {
            is PlacementState.Placed -> {
                if (anchorPose != null && anchorPose !== this.anchorPose) {
                    this.anchorPose = anchorPose
                    placedCache = null
                }
                val visible = s.anchored && anchorPose != null && tracking
                val pose = placedCache ?: placedPose()?.also { placedCache = it } ?: s.pose
                // Allocation-free when nothing moved (N5): same pose instance, same visibility.
                if (pose !== s.pose || visible != s.tracking) currentState = s.copy(pose = pose, tracking = visible)
                modelPose = if (visible) pose else null
            }
            is PlacementState.FallbackLine -> {
                previewSurface = null
                modelPose = null
            }
            else -> updatePreview(timestampNs, tracking, camera)
        }
        return true
    }

    private fun updatePreview(timestampNs: Long, tracking: Boolean, camera: Pose) {
        val surface = if (tracking) surfacePoseNow(camera) else null
        if (target == SurfaceTarget.WALL) {
            if (surface != null) {
                wallSearchSinceNs = timestampNs
            } else if (wallSearchSinceNs == Long.MIN_VALUE) {
                wallSearchSinceNs = timestampNs
            } else if (timestampNs - wallSearchSinceNs >= FALLBACK_OFFER_NS) {
                offerFallback = true
            }
        }
        previewSurface = surface
        if (surface == null) {
            currentState = PlacementState.Searching
            modelPose = null
        } else {
            val pose = PlacementMath.modelPose(surface, item, target, spinRad)
            currentState = PlacementState.Preview(pose, target)
            modelPose = pose
        }
    }

    private fun surfacePoseNow(camera: Pose): Pose? {
        if (target == SurfaceTarget.WALL_FALLBACK) {
            val plane = fallbackPlane ?: return null
            val p = plane.intersect(Ray(camera.translation, camera.forward), MIN_FALLBACK_COS) ?: return null
            if (p.distanceTo(camera.translation) > MAX_FALLBACK_RANGE_M) return null
            return PlacementMath.wallSurfacePose(p, plane.normal, camera.translation)
        }
        val p = lastHitPos ?: return null
        val n = lastHitNormal ?: return null
        if (!PlacementMath.accepts(target, n)) return null
        return if (target.isWall) {
            PlacementMath.wallSurfacePose(p, n, camera.translation)
        } else {
            PlacementMath.horizontalSurfacePose(p, camera.translation)
        }
    }

    // ---- commands ----------------------------------------------------------------------------------

    /** Switches the surface; a placed item is picked up first (the caller detaches its anchor). */
    fun setTarget(t: SurfaceTarget) {
        if (t == target) return
        require(t != SurfaceTarget.WALL_FALLBACK || fallbackPlane != null) { "Build the fallback line first" }
        target = t
        if (currentState is PlacementState.Placed || currentState is PlacementState.FallbackLine) clearPlacement()
        currentState = PlacementState.Searching
        modelPose = null
        wallSearchSinceNs = Long.MIN_VALUE
        fallbackRejected = false
    }

    /** The anchor to create for "Place here", or null when nothing valid is under the reticle. */
    fun drop(): DropRequest? {
        if (currentState !is PlacementState.Preview || !cameraTracking) return null
        val surface = previewSurface ?: return null
        anchorPose = surface
        localOffset = Vec3.ZERO
        placedSurface = target
        val pose = PlacementMath.modelPose(surface, item, target, spinRad)
        placedCache = pose
        currentState = PlacementState.Placed(pose, anchored = false, surface = target, tracking = true)
        modelPose = pose
        return DropRequest(surface)
    }

    /** Result of the anchor request for the last [drop]. On failure the item goes back to following the reticle. */
    fun onAnchored(success: Boolean) {
        val s = currentState as? PlacementState.Placed ?: return
        if (success) currentState = s.copy(anchored = true) else clearPlacement()
    }

    /** "Move": back to following the reticle (the caller detaches the anchor). Spin is kept. */
    fun pickUp() {
        if (currentState !is PlacementState.Placed) return
        clearPlacement()
    }

    /** Moves the placed item by whole 1 cm steps, screen-mapped (§3.3). No-op unless placed. */
    fun nudge(rightSteps: Int, upSteps: Int) {
        val a = anchorPose ?: return
        val cam = cameraPose ?: return
        if (currentState !is PlacementState.Placed) return
        val normal = if (placedSurface.isWall) a.rotateVector(Vec3.UNIT_Z) else Vec3.UP
        val delta = PlacementMath.nudgeDelta(placedSurface, normal, cam, rightSteps, upSteps)
        moveWorld(delta)
    }

    /**
     * Wall items: moves the placed item so its centre is [heightM] above the floor (e.g. the 145 cm eye-level guide).
     * False when not placed on a wall or the floor is unknown.
     */
    fun setCentreHeight(heightM: Double): Boolean {
        val current = centreHeight() ?: return false
        if (currentState !is PlacementState.Placed || !placedSurface.isWall || !heightM.isFinite()) return false
        moveWorld(Vec3(0.0, heightM - current, 0.0))
        return true
    }

    private fun moveWorld(delta: Vec3) {
        val a = anchorPose ?: return
        localOffset = localOffset + a.rotation.inverse().rotate(delta)
        refreshPlaced()
    }

    /** A quarter turn about the surface normal (portrait ↔ landscape on a wall). */
    fun rotate90() {
        spinRad = PlacementMath.rotate90(spinRad)
        refreshPlaced()
    }

    /** Free rotation about the surface normal (two-finger twist), never scale. */
    fun twist(deltaRad: Double) {
        if (!deltaRad.isFinite()) return
        spinRad = PlacementMath.wrap(spinRad + deltaRad)
        refreshPlaced()
    }

    /** Starts the two-tap wall–floor line (ui-ux §3.5). */
    fun startFallback() {
        clearPlacement()
        fallbackPoints.clear()
        fallbackRejected = false
        currentState = PlacementState.FallbackLine(0)
        modelPose = null
    }

    /**
     * Adds the reticle point (it must be on the floor) to the wall–floor line. After the second point the vertical
     * plane is built and the target becomes [SurfaceTarget.WALL_FALLBACK]; a bad line resets to 0 points with
     * [PlacementStatus.fallbackRejected]. False when the reticle is not on a horizontal surface.
     */
    fun addFallbackPoint(): Boolean {
        if (currentState !is PlacementState.FallbackLine) return false
        val hitPos = lastHitPos ?: return false
        val n = lastHitNormal ?: return false
        val cam = cameraPose ?: return false
        if (!cameraTracking || !PlacementMath.isHorizontal(n)) return false
        fallbackRejected = false
        fallbackPoints += hitPos
        if (fallbackPoints.size < 2) {
            currentState = PlacementState.FallbackLine(fallbackPoints.size)
            return true
        }
        val a = fallbackPoints[0]; val b = fallbackPoints[1]
        fallbackPoints.clear()
        // The floor is the robust floor estimate (N1), not the first tap (M4 review MINOR 4): two taps on a table edge
        // are more than MAX_OFF_FLOOR_M above it and are rejected, and a stray point below the floor cannot reject
        // genuine floor taps.
        val floorPlane = Plane.fromPointNormal(Vec3(a.x, floor.floorY() ?: a.y, a.z), Vec3.UP)
        val plane = Plane.verticalThroughFloorLine(a, b, cam.translation, floorPlane)
        if (plane == null) {
            fallbackRejected = true
            currentState = PlacementState.FallbackLine(0)
            return true
        }
        fallbackPlane = plane
        floor.confirm((a.y + b.y) / 2)
        target = SurfaceTarget.WALL_FALLBACK
        currentState = PlacementState.Searching
        return true
    }

    /** Leaves the fallback line without building it. */
    fun cancelFallback() {
        if (currentState is PlacementState.FallbackLine) {
            fallbackPoints.clear()
            currentState = PlacementState.Searching
        }
    }

    /**
     * The session paused: anchors are detached by the caller (M2 M5 rule). Back to [PlacementState.Searching] with
     * the item, target and spin kept; the fallback plane (world coordinates) is dropped.
     */
    fun onPaused() {
        clearPlacement()
        fallbackPoints.clear()
        if (fallbackPlane != null) {
            fallbackPlane = null
            if (target == SurfaceTarget.WALL_FALLBACK) {
                target = SurfaceTarget.WALL
                offerFallback = true
            }
        }
        currentState = PlacementState.Searching
        modelPose = null
        lastHitPos = null
        lastHitNormal = null
        cameraTracking = false
        pauses++
    }

    /** A new ARCore session (new world frame): as [onPaused], and the floor is forgotten. */
    fun onSessionCreated() {
        onPaused()
        floor.reset()
        lastTimestamp = Long.MIN_VALUE
        wallSearchSinceNs = Long.MIN_VALUE
    }

    private fun clearPlacement() {
        anchorPose = null
        placedCache = null
        localOffset = Vec3.ZERO
        previewSurface = null
        if (currentState is PlacementState.Placed) currentState = PlacementState.Searching
        modelPose = null
    }

    private fun placedPose(): Pose? {
        val a = anchorPose ?: return null
        val surface = Pose(a.translation + a.rotation.rotate(localOffset), a.rotation)
        return PlacementMath.modelPose(surface, item, placedSurface, spinRad)
    }

    private fun refreshPlaced() {
        val s = currentState
        if (s is PlacementState.Placed) {
            val pose = placedPose() ?: return
            placedCache = pose
            currentState = s.copy(pose = pose)
            if (modelPose != null) modelPose = pose
        } else if (s is PlacementState.Preview) {
            val surface = previewSurface ?: return
            val pose = PlacementMath.modelPose(surface, item, target, spinRad)
            currentState = PlacementState.Preview(pose, target)
            modelPose = pose
        }
    }

    /** Centre height of a wall item above the floor (m), when both are known. */
    private fun centreHeight(): Double? {
        val y0 = floor.floorY() ?: return null
        val pose = when (val s = currentState) {
            is PlacementState.Placed -> s.pose.takeIf { placedSurface.isWall }
            is PlacementState.Preview -> s.pose.takeIf { s.surface.isWall }
            else -> null
        } ?: return null
        return pose.transformPoint(item.centre).y - y0
    }

    companion object {
        /** No wall for this long while targeting a wall: offer the wall–floor line (§3.4: 5 s). */
        const val FALLBACK_OFFER_NS = 5_000_000_000L

        /** The fallback plane is used only up to this distance (a far grazing hit is useless). */
        const val MAX_FALLBACK_RANGE_M = 6.0

        private const val MIN_FALLBACK_COS = 0.15
    }
}
