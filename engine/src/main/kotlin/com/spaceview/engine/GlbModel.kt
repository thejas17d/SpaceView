package com.spaceview.engine

/**
 * Version of the GLB contract written into every model's `extras.glbVersion` (ADR-003, K8).
 * - 1 (M3, implicit: the key is absent): lit front face.
 * - 2 (M4): unlit photo face (`KHR_materials_unlit`), `extras.glbVersion` and `extras.modelKind`.
 *
 * Placement regenerates a regenerable model (proxy box, template) whose version is below this.
 */
const val GLB_VERSION = 2

/**
 * Where a placed model's geometry came from (S4). Stored by name in GLB extras and in Room: never rename.
 * - [PROXY_BOX]: the M3 measured box (`GlbWriter.writeBox`); regenerated from the row when needed.
 * - [TEMPLATE]: a parametric furniture model saved by earlier test builds. v1 (PLAN v3, 2D) has no template library,
 *   so it is never regenerated: its stored GLB is placed as it is (or the measured box when that is unusable).
 * - [MESH_DEVICE]: an on-device 3D scan saved by earlier test builds; never regenerated, never rescaled.
 * - [MESH_CLOUD]: a cloud reconstruction (later); never regenerated, never rescaled.
 * - [CUTOUT]: the v1 scan model (PLAN v3 W2): a flat, background-free, true-size picture of the object
 *   (`GlbWriter.writeCutout`); regenerated from the row and the stored cutout image when needed.
 */
enum class ModelKind {
    PROXY_BOX, TEMPLATE, MESH_DEVICE, MESH_CLOUD, CUTOUT;

    /** True when the model can be rebuilt from the database row and the item's own files (ADR-014). */
    val regenerable: Boolean get() = this == PROXY_BOX || this == CUTOUT
}

/**
 * What a GLB says about itself (node 0 `extras`), read by [GlbReader] without touching the binary chunk.
 *
 * @property glbVersion 1 when the key is absent (M3 models).
 * @property modelKind null when absent (M3 models, which are always [ModelKind.PROXY_BOX]).
 * @property origin null when absent or unknown.
 * @property centre the bounding-box centre in the GLB frame when the GLB declares it (a `keepInputOrigin` mesh,
 *   [GlbWriter.AABB_CX]; M4-P1 gate N3); null otherwise, when the centre is `origin.centreOffset(w, h, d)`.
 */
class GlbInfo(
    val widthM: Double,
    val heightM: Double,
    val depthM: Double,
    val origin: BoxOrigin?,
    val glbVersion: Int,
    val modelKind: ModelKind?,
    val centre: Vec3? = null,
)
