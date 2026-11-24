package com.spaceview.data.gallery

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.ModelKind
import java.io.File

/**
 * The extents of a scanned mesh along the measured box's axes, each with its 95% ± (§7.1.5), for legacy 3D-scan rows
 * saved by earlier test builds (PLAN v3: no new meshes in v1). Never used for the item's size.
 */
data class MeshExtents(
    val widthM: Double,
    val heightM: Double,
    val depthM: Double,
    val widthBound95: Double,
    val heightBound95: Double,
    val depthBound95: Double,
)

/** A GLB's own bounding box (its K8 `extras.{widthM,heightM,depthM}`). */
data class GlbExtents(val widthM: Double, val heightM: Double, val depthM: Double)

/**
 * What Place needs to put an item in the room (contract K4).
 *
 * @property file the GLB to render: the item's own model (regenerated first when it was stale), or, for a model
 *   that cannot be used, a proxy box built in the cache from the row's size ([meshFallback]).
 * @property widthM / [heightM] / [depthM] the rendered GLB's own extents (its extras); placement never rescales. A
 *   cutout ([ModelKind.CUTOUT]) is flat: its [depthM] is 0 and its measured depth is [depth].
 * @property origin the GLB's origin: CENTER for a cutout (a flat quad), BACK_CENTER for legacy wall boxes (frame,
 *   TV), else BOTTOM_CENTER.
 * @property width / [height] / [depth] the row's dimensions with their sources: what the fit verdict uses.
 * @property modelKind the row's kind (a fallback box keeps the mesh kind here).
 * @property meshFallback true when the stored model was unusable and could not be rebuilt, and [file] is the row's box instead.
 * @property centre the rendered GLB's own bounding-box centre in its frame when the GLB declares one (a 3D scan kept in
 *   its measured-box frame, whose box is not centred on [origin]; M4-P1 gate N3), else null (centre = the [origin] rule).
 */
class PlacementModel(
    val id: Long,
    val file: File,
    val widthM: Double,
    val heightM: Double,
    val depthM: Double,
    val origin: BoxOrigin,
    val kind: ObjectKind,
    val width: Dimension,
    val height: Dimension,
    val depth: Dimension,
    val modelKind: ModelKind,
    val meshFallback: Boolean,
    val label: String?,
    val centre: com.spaceview.engine.Vec3? = null,
)

/**
 * The outcome of [GalleryRepository.placementModel]: a storage failure is never reported as a missing item
 * (M4 review MINOR 5).
 */
sealed interface PlacementResult {
    data class Ready(val model: PlacementModel) : PlacementResult

    /** No row with this id (deleted). */
    data object Missing : PlacementResult

    /** The row is unreadable, or reading, regenerating or writing the model failed. */
    data object Failed : PlacementResult
}
