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
 * - [TEMPLATE]: a parametric furniture model (`GlbWriter.writeParts`); regenerated from its parameters.
 * - [MESH_DEVICE]: an on-device 3D scan (`GlbWriter.writeMesh`); never regenerated, never rescaled.
 * - [MESH_CLOUD]: a cloud reconstruction (later); never regenerated, never rescaled.
 */
enum class ModelKind {
    PROXY_BOX, TEMPLATE, MESH_DEVICE, MESH_CLOUD;

    /** False for meshes: their geometry cannot be rebuilt from the database row (ADR-014). */
    val regenerable: Boolean get() = this == PROXY_BOX || this == TEMPLATE
}

/**
 * Surface materials of a furniture part (K9). Each maps to one glTF material through a [Palette]; parts that
 * share a material share one primitive. Stored by name nowhere (only colours reach the GLB), so entries may be
 * added later without a migration.
 */
enum class PartMaterial {
    /** Main upholstery or carcass (sofa base, wardrobe body). */
    BODY,

    /** Soft parts (seat and back cushions, mattress). */
    SOFT,

    /** Rigid frame parts (bed frame, headboard, table top). */
    FRAME,

    /** Legs and plinths. */
    LEG,

    /** Doors and drawer fronts. */
    DOOR,

    /** Small details (handles, piping). */
    ACCENT,
}

/**
 * One axis-aligned box of a parametric model, in the model frame: metres, +Y up, +Z towards the viewer (the
 * front), +X to the viewer's right.
 *
 * @property center centre of the box.
 * @property size full extents along the part's own X, Y, Z (all > 0).
 * @property yawRad rotation about +Y (right-handed: positive turns +X towards −Z), applied about [center].
 */
data class Part(
    val name: String,
    val center: Vec3,
    val size: Vec3,
    val yawRad: Double = 0.0,
    val material: PartMaterial = PartMaterial.BODY,
)

/**
 * Colours of [PartMaterial]s as sRGB `0xRRGGBB` (converted to linear for glTF). Missing entries use
 * [DEFAULT]'s colour.
 */
class Palette(private val colors: Map<PartMaterial, Int> = emptyMap()) {
    fun colorOf(m: PartMaterial): Int = colors[m] ?: DEFAULT_COLORS.getValue(m)

    companion object {
        private val DEFAULT_COLORS = mapOf(
            PartMaterial.BODY to 0x8C8A85,
            PartMaterial.SOFT to 0xB3AFA6,
            PartMaterial.FRAME to 0x7A5C43,
            PartMaterial.LEG to 0x3E3A36,
            PartMaterial.DOOR to 0xC9C3B6,
            PartMaterial.ACCENT to 0x5A5752,
        )

        /** Neutral warm greys and a wood brown. */
        val DEFAULT = Palette()
    }
}

/**
 * Extras for [GlbWriter.writeMesh]. The writer always adds `widthM/heightM/depthM` (the mesh's own bounding
 * box), `origin`, `glbVersion` and `modelKind` itself; [values] adds further numeric diagnostics (for example
 * `voxelM`, `scaleResidualM`). Keys must match `[A-Za-z][A-Za-z0-9_]*` and may not reuse a reserved key.
 */
class MeshExtras(
    val modelKind: ModelKind = ModelKind.MESH_DEVICE,
    val name: String = "SpaceView 3D scan",
    val values: Map<String, Double> = emptyMap(),
)

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
