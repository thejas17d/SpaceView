package com.spaceview.data.gallery

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Palette
import com.spaceview.engine.Part
import java.io.File

/**
 * A parametric furniture item to save (contract K10, Builder B → A). The repository writes its GLB with
 * `GlbWriter.writeParts(parts, palette, BOTTOM_CENTER, label, TEMPLATE)`, so the parts' union box must equal
 * [width] × [height] × [depth] (ADR-010; checked to 0.1 mm, else `IllegalArgumentException`).
 *
 * @property templateId versioned template name, e.g. `sofa_v1` (never renamed).
 * @property params the template parameters in metres (keys `[A-Za-z][A-Za-z0-9_]*`, finite values), stored as JSON.
 * @property width / [height] / [depth] the overall size with its sources (MEASURED with ±, or TYPED / PRESET).
 * @property parts the template's geometry for these parameters (the template's `build(params)`).
 * @property captureMethod how the measured sides were taken (null = unknown).
 */
class TemplateDraft(
    val templateId: String,
    val params: Map<String, Double>,
    val width: Dimension,
    val height: Dimension,
    val depth: Dimension,
    val label: String?,
    val parts: List<Part>,
    val palette: Palette = Palette.DEFAULT,
    val deviceTier: DeviceTier = DeviceTier.NO_DEPTH,
    val calibrated: Boolean = false,
    val calibrationFactor: Double? = null,
    val arcoreVersion: String? = null,
    val captureMethod: CaptureMethod? = null,
)

/**
 * The extents of a scanned mesh along the measured box's axes, each with its 95% ± (§7.1.5). Reported beside the
 * measured size; never used for it.
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
 * An on-device (or later cloud) 3D scan to save (contract K10, the D milestone → `:data`).
 *
 * @property width / [height] / [depth] the **measured box** (authoritative size, ADR-013); MEASURED with ±.
 * @property glb the mesh GLB from `GlbWriter.writeMesh(…, keepInputOrigin = true)` (origin BOTTOM_CENTER = the centre
 *   of the measured box's base, `modelKind` MESH_DEVICE or MESH_CLOUD). Its extras must equal [glbExtents] to 0.1 mm.
 * @property glbExtents the GLB's own bounding box (`GlbReader.readInfo(glb)`), stored in `mesh_glb_*`.
 * @property meshExtents the §7.1.5 measured extents and ± (`MeshExtent.estimate`), stored in `mesh_extent_*`. They
 *   differ from [glbExtents] (the floor cut, percentile trims); both are kept (M4 review MAJOR 2).
 * @property meshInfo JSON diagnostics (voxel size, depth source, frames, coverage, triangles, cross-check).
 * @property thumbnailSource a JPEG of the keyframe facing the front most squarely; encoded to a 256 px WebP.
 */
class MeshDraft(
    val kind: ObjectKind,
    val width: Dimension,
    val height: Dimension,
    val depth: Dimension,
    val quality: com.spaceview.engine.Quality,
    val calibrated: Boolean,
    val deviceTier: DeviceTier,
    val glb: ByteArray,
    val glbExtents: GlbExtents,
    val meshExtents: MeshExtents,
    val meshInfo: String?,
    val thumbnailSource: ByteArray?,
    val label: String?,
    val arcoreVersion: String?,
    val calibrationFactor: Double? = null,
    val modelKind: ModelKind = ModelKind.MESH_DEVICE,
    val captureMethod: CaptureMethod? = CaptureMethod.DEPTH_SCAN,
)

/**
 * What Place needs to put an item in the room (contract K4).
 *
 * @property file the GLB to render: the item's own model (regenerated first when it was stale), or, for a mesh
 *   that cannot be used, a proxy box built in the cache from the measured size ([meshFallback]).
 * @property widthM / [heightM] / [depthM] the rendered GLB's own extents (its extras); placement never rescales.
 * @property origin the GLB's origin: BACK_CENTER for wall items (frame, TV), else BOTTOM_CENTER.
 * @property width / [height] / [depth] the row's dimensions with their sources: what the fit verdict uses.
 * @property modelKind the row's kind (a fallback box keeps the mesh kind here).
 * @property meshFallback true when a mesh was unusable and [file] is the measured box instead.
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

/**
 * Rebuilds a template's parts from its stored id and parameters (for regenerating a stale or missing template
 * model). The app wires Builder B's template registry; null means unknown, and the repository then leaves the
 * stored model alone.
 */
fun interface TemplateModelSource {
    fun parts(templateId: String, params: Map<String, Double>): List<Part>?

    /**
     * The colours the item was saved with (Builder B, T: the palette index is stored among the parameters), so a
     * regenerated model keeps them. Null = [Palette.DEFAULT].
     */
    fun palette(templateId: String, params: Map<String, Double>): Palette? = null

    companion object {
        val NONE = TemplateModelSource { _, _ -> null }
    }
}

/** `{"key":1.0,...}` for template parameters; no dependency on a JSON library. */
internal object TemplateParamsCodec {
    private val KEY = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val ENTRY = Regex("\"([A-Za-z][A-Za-z0-9_]*)\"\\s*:\\s*(-?[0-9][0-9.eE+-]*)")

    fun validate(params: Map<String, Double>) {
        require(params.size <= 64) { "At most 64 template parameters" }
        for ((k, v) in params) require(KEY.matches(k) && v.isFinite()) { "Invalid template parameter '$k' = $v" }
    }

    fun encode(params: Map<String, Double>): String {
        validate(params)
        return params.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    /** Null for anything that is not a flat object of finite numbers. */
    fun decode(json: String?): Map<String, Double>? {
        val s = json?.trim() ?: return null
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        val body = s.substring(1, s.length - 1).trim()
        if (body.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (part in body.split(',')) {
            val m = ENTRY.matchEntire(part.trim()) ?: return null
            val v = m.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            out[m.groupValues[1]] = v
        }
        return out
    }
}
