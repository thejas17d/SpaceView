package com.spaceview.ar

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.Pose
import com.spaceview.engine.Quaternion
import com.spaceview.engine.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Where the item is being placed. */
enum class SurfaceTarget {
    /** On the floor (horizontal, facing up). */
    FLOOR,

    /** On a table or other raised horizontal surface (same geometry as [FLOOR]; different wording). */
    TABLE,

    /** On a detected vertical plane. */
    WALL,

    /** On the vertical plane built from two taps on the wall–floor line (plain walls). */
    WALL_FALLBACK;

    val isWall: Boolean get() = this == WALL || this == WALL_FALLBACK
}

/**
 * The item as placement sees it: its GLB extents (metres), where the GLB's origin sits and, for a mesh kept in its
 * measured-box frame, its own bounding-box centre.
 *
 * @param aabbCentre the GLB's declared bounding-box centre (`GlbInfo.centre`, M4-P1 gate N3), or null when the box is
 *   centred on the declared [origin] (every proxy box and template, and re-centred meshes).
 */
class PlacedItem(val widthM: Double, val heightM: Double, val depthM: Double, val origin: BoxOrigin, aabbCentre: Vec3? = null) {
    init {
        require(listOf(widthM, heightM, depthM).all { it.isFinite() && it > 0 }) { "Item extents must be > 0" }
        require(aabbCentre == null || (aabbCentre.x.isFinite() && aabbCentre.y.isFinite() && aabbCentre.z.isFinite())) {
            "Item centre must be finite"
        }
    }

    /** Where the declared [origin] says the box centre is: [BoxOrigin.centreOffset], the one origin rule. */
    val declaredCentre: Vec3 = origin.centreOffset(widthM, heightM, depthM)

    /**
     * The item's real bounding-box centre in its own (GLB) frame. For a 3D scan it differs from [declaredCentre] by the
     * floor cut and any asymmetry of a partial scan (N3); for everything else the two are equal.
     */
    val centre: Vec3 = aabbCentre ?: declaredCentre
}

/**
 * Pure placement geometry (no ARCore types; JVM-tested, same pattern as [ScanComposer]).
 *
 * Frames: the item frame is the GLB's (+Y up, +Z front). A **surface pose** is where the item touches the
 * surface: on a horizontal surface the centre of the item's bottom face, with +Y = world up and the item's front
 * turned towards the camera; on a wall the centre of the item's back face, with +Z = the wall's normal (into the
 * room) and +Y = world up projected onto the wall. The model pose is `surfacePose * spin * attachOffset`, where
 * spin turns about the surface normal (yaw on a floor, roll on a wall) and attachOffset moves the GLB origin from
 * the touching point (so a BACK_CENTER item stands on a table and a BOTTOM_CENTER item hangs flush on a wall).
 * Scale is never part of any pose: the model is always rendered at 1:1.
 */
object PlacementMath {
    /** A surface whose normal is within this angle of world up counts as horizontal (floor / table). */
    const val HORIZONTAL_MAX_TILT_DEG = 15.0

    /** A surface whose normal is within this angle of horizontal counts as a wall. */
    const val WALL_MAX_TILT_DEG = 15.0

    /** One nudge step (ui-ux §2.6). */
    const val NUDGE_STEP_M = 0.01

    private val COS_HORIZONTAL = kotlin.math.cos(Math.toRadians(HORIZONTAL_MAX_TILT_DEG))
    private val SIN_WALL = kotlin.math.sin(Math.toRadians(WALL_MAX_TILT_DEG))

    /** True when [normal] is an upward-facing horizontal surface (floor, table). */
    fun isHorizontal(normal: Vec3): Boolean {
        val n = normal.normalized()
        return n.y >= COS_HORIZONTAL
    }

    /** True when [normal] is a vertical surface (wall). */
    fun isVertical(normal: Vec3): Boolean = abs(normal.normalized().y) <= SIN_WALL

    /** Whether a hit with [normal] can carry an item for [target]. */
    fun accepts(target: SurfaceTarget, normal: Vec3): Boolean =
        if (target.isWall) isVertical(normal) else isHorizontal(normal)

    /**
     * Surface pose on a horizontal surface at [hit]: +Y up, the item's front (+Z) turned towards [camera] (projected
     * onto the floor). A camera straight above the point keeps the front along world +Z.
     */
    fun horizontalSurfacePose(hit: Vec3, camera: Vec3): Pose {
        val dx = camera.x - hit.x
        val dz = camera.z - hit.z
        val yaw = if (dx * dx + dz * dz < 1e-12) 0.0 else atan2(dx, dz)
        return Pose(hit, Quaternion.fromAxisAngle(Vec3.UP, yaw))
    }

    /**
     * Surface pose on a wall at [hit] with wall normal [wallNormal], flipped if needed so it faces [camera]: +Z =
     * the normal, +Y = world up projected onto the wall, +X = Y × Z. Null for a non-vertical normal.
     */
    fun wallSurfacePose(hit: Vec3, wallNormal: Vec3, camera: Vec3): Pose? {
        var z = Vec3(wallNormal.x, 0.0, wallNormal.z)
        if (z.length < 1e-9 || !isVertical(wallNormal)) return null
        z = z.normalized()
        if ((camera - hit) dot z < 0) z = -z
        val y = Vec3.UP
        val x = y cross z
        return Pose(hit, fromAxes(x, y, z))
    }

    /** Rotation about the surface normal (local +Y on a floor, local +Z on a wall) by [spinRad]. */
    fun spin(target: SurfaceTarget, spinRad: Double): Quaternion =
        Quaternion.fromAxisAngle(if (target.isWall) Vec3.UNIT_Z else Vec3.UNIT_Y, spinRad)

    /**
     * Offset (item frame) from the touching point to the GLB origin.
     * - On a horizontal surface the touching point is the centre of the bottom face of the **declared** box. For a 3D
     *   scan that is the measured box's base centre (its GLB origin): the scan stands where the measured object stood,
     *   its open bottom the floor cut above the floor, and its footprint is the one the verdict uses (§7.1.8).
     * - On a wall it is the centre of the back face of the item's **real** bounding box ([PlacedItem.centre]), so a
     *   scan hangs flush and centred on the reticle whatever the floor cut or a partial scan did to its box (N3).
     * For proxy boxes and templates the two boxes are the same.
     */
    fun attachOffset(item: PlacedItem, target: SurfaceTarget): Vec3 {
        val touch = if (target.isWall) {
            item.centre - Vec3(0.0, 0.0, item.depthM / 2)
        } else {
            item.declaredCentre - Vec3(0.0, item.heightM / 2, 0.0)
        }
        return -touch
    }

    /** The model (GLB origin) pose for a surface pose, spin and the item's attachment. */
    fun modelPose(surface: Pose, item: PlacedItem, target: SurfaceTarget, spinRad: Double): Pose {
        val s = spin(target, spinRad)
        val rot = surface.rotation * s
        return Pose(surface.translation + rot.rotate(attachOffset(item, target)), rot)
    }

    /**
     * World displacement for a nudge of ([steps right], [steps up]) seen from [cameraPose]: on a horizontal surface
     * "right" is the camera's right and "up" is away from the camera, both projected onto the floor; on a wall
     * "right" is the camera's right projected onto the wall and "up" is world up. Each step is [NUDGE_STEP_M].
     * Zero when the camera looks straight along the surface axis.
     */
    fun nudgeDelta(target: SurfaceTarget, surfaceNormal: Vec3, cameraPose: Pose, rightSteps: Int, upSteps: Int): Vec3 {
        val n = surfaceNormal.normalized()
        val camRight = cameraPose.rotateVector(Vec3.UNIT_X)
        val right = projectOnto(camRight, n)
        val up = if (target.isWall) projectOnto(Vec3.UP, n) else projectOnto(cameraPose.forward, n)
        return right * (rightSteps * NUDGE_STEP_M) + up * (upSteps * NUDGE_STEP_M)
    }

    /** Spin after "rotate 90°" (quarter turns, wrapped to (−π, π]). */
    fun rotate90(spinRad: Double): Double = wrap(spinRad + PI / 2)

    /** Wraps an angle to (−π, π]. */
    fun wrap(a: Double): Double {
        var x = a % (2 * PI)
        if (x <= -PI) x += 2 * PI
        if (x > PI) x -= 2 * PI
        return x
    }

    private fun projectOnto(v: Vec3, normal: Vec3): Vec3 {
        val p = v - normal * (v dot normal)
        val l = p.length
        return if (l < 1e-6) Vec3.ZERO else p / l
    }

    /** Rotation whose columns are the orthonormal axes [x], [y], [z]. */
    internal fun fromAxes(x: Vec3, y: Vec3, z: Vec3): Quaternion {
        val m00 = x.x; val m10 = x.y; val m20 = x.z
        val m01 = y.x; val m11 = y.y; val m21 = y.z
        val m02 = z.x; val m12 = z.y; val m22 = z.z
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            Quaternion(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            Quaternion((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
        }
    }
}
