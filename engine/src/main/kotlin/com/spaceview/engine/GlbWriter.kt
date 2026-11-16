package com.spaceview.engine

import com.spaceview.engine.recon.TriMesh
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Where the model origin sits, so the Android layer can anchor it without offsets.
 * glTF axes: +Y up, the front face looks down +Z (towards the viewer).
 */
enum class BoxOrigin {
    /** Geometric centre. */
    CENTER,

    /** Centre of the bottom face (free-standing items placed on a floor anchor). */
    BOTTOM_CENTER,

    /** Centre of the back face (wall-hung frames/TVs placed on a wall anchor; the box extends along +Z). */
    BACK_CENTER;

    /**
     * Where the centre of a [w] × [h] × [d] box lies in model space when this is its origin. The single owner of
     * the origin rule (M4 review MINOR 12): the writer, placement and the height readout all use it.
     */
    fun centreOffset(w: Double, h: Double, d: Double): Vec3 = when (this) {
        CENTER -> Vec3.ZERO
        BOTTOM_CENTER -> Vec3(0.0, h / 2, 0.0)
        BACK_CENTER -> Vec3(0.0, 0.0, d / 2)
    }
}

/**
 * Writes SpaceView's true-size models as glTF 2.0 binaries (.glb), 1 unit = 1 metre, +Y up, front = +Z.
 * The API is frozen by contract K9 (docs/contracts/phase0.md):
 * - [writeBox]: the M3 measured proxy box; the photo face is unlit (ADR-003).
 * - [writeParts]: a parametric multi-part model (furniture templates, ADR-010).
 * - [writeMesh]: an indexed triangle mesh with optional vertex colours (on-device 3D scan, ADR-013/014).
 *
 * Every model carries node-0 `extras` (K8): `widthM`, `heightM`, `depthM` = the GLB's **own** bounding box,
 * `origin` ([BoxOrigin] name), `glbVersion` ([GLB_VERSION]) and `modelKind` ([ModelKind] name). The geometry is
 * translated so that the bounding box's [BoxOrigin] point is the model origin. Output is deterministic.
 * Triangles are counter-clockwise seen from outside (glTF front faces).
 */
object GlbWriter {
    private const val MAGIC = 0x46546C67 // "glTF"
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val UNLIT = "KHR_materials_unlit"

    /** Largest accepted side (m); anything bigger is a bug upstream, not furniture. */
    const val MAX_SIDE_M = 50.0

    /** Most parts [writeParts] accepts (u16 indices: 24 vertices per part). */
    const val MAX_PARTS = 512

    /** Most vertices [writeMesh] accepts (far above the 0.5 MB item budget; guards int overflow). */
    const val MAX_MESH_VERTICES = 4_000_000

    private val EXTRA_KEY = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val RESERVED_KEYS = setOf("widthM", "heightM", "depthM", "origin", "glbVersion", "modelKind")

    // ---- box (M3 proxy) -------------------------------------------------------------------------------

    /**
     * Builds the proxy box GLB: one mesh, 24 vertices (4 per face, so normals and UVs are per face),
     * POSITION / NORMAL / TEXCOORD_0, two primitives sharing the vertices: the front (+Z) face and the other five
     * faces in a flat colour. With a photo the front material is `KHR_materials_unlit` (the photo already contains
     * the room's light, ADR-003); the sides stay lit so the box keeps its depth cues. Geometry + JSON are ~2–3 KB;
     * the file is that plus the JPEG.
     *
     * @param widthM X extent (m), [heightM] Y extent, [depthM] Z extent; all in (0, [MAX_SIDE_M]].
     * @param frontJpeg rectified front photo (JPEG bytes, must start with FF D8), or null for an untextured front.
     * @param sideColorSrgb 0xRRGGBB colour (sRGB) of the other faces, e.g. sampled from the photo edges.
     * @param frontColorSrgb front colour used only when [frontJpeg] is null.
     * @param name node/mesh name (escaped into JSON).
     * @param modelKind written to `extras.modelKind`.
     */
    fun writeBox(
        widthM: Double,
        heightM: Double,
        depthM: Double,
        frontJpeg: ByteArray? = null,
        sideColorSrgb: Int = 0x808080,
        frontColorSrgb: Int = 0xFFFFFF,
        origin: BoxOrigin = BoxOrigin.BOTTOM_CENTER,
        name: String = "SpaceView item",
        modelKind: ModelKind = ModelKind.PROXY_BOX,
    ): ByteArray {
        for (s in doubleArrayOf(widthM, heightM, depthM)) {
            require(s.isFinite() && s > 0 && s <= MAX_SIDE_M) { "Box sides must be in (0, $MAX_SIDE_M] m, got $s" }
        }
        if (frontJpeg != null) {
            require(frontJpeg.size >= 4 && frontJpeg[0] == 0xFF.toByte() && frontJpeg[1] == 0xD8.toByte()) {
                "frontJpeg is not a JPEG (missing FF D8 SOI marker)"
            }
        }

        val half = Vec3(widthM / 2, heightM / 2, depthM / 2)
        val center = origin.centreOffset(widthM, heightM, depthM)
        val pos = DoubleArray(24 * 3)
        val nrm = FloatArray(24 * 3)
        val uv = FloatArray(24 * 2)
        val idx = IntArray(36)
        emitBox(center, half, 0.0, pos, nrm, uv, 0, idx, 0)

        val bin = Bin()
        val posView = bin.view(ARRAY_BUFFER) { for (v in pos) f32(v.toFloat()) }
        val nrmView = bin.view(ARRAY_BUFFER) { for (v in nrm) f32(v) }
        val uvView = bin.view(ARRAY_BUFFER) { for (v in uv) f32(v) }
        val idxView = bin.view(ELEMENT_ARRAY_BUFFER) { for (i in idx) u16(i) }
        val imgView = if (frontJpeg != null) bin.view(null) { bytes(frontJpeg) } else -1

        val textured = frontJpeg != null
        val j = StringBuilder(2048)
        j.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"SpaceView engine\"},")
        if (textured) j.append("\"extensionsUsed\":[\"").append(UNLIT).append("\"],")
        j.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
        j.append("\"nodes\":[{\"mesh\":0,\"name\":").append(jsonString(name)).append(",\"extras\":")
        writeExtras(j, widthM, heightM, depthM, origin, modelKind, emptyMap())
        j.append("}],")
        val attrs = "{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2}"
        j.append("\"meshes\":[{\"name\":").append(jsonString(name)).append(",\"primitives\":[")
        j.append("{\"attributes\":").append(attrs).append(",\"indices\":3,\"material\":0,\"mode\":4},")
        j.append("{\"attributes\":").append(attrs).append(",\"indices\":4,\"material\":1,\"mode\":4}]}],")
        j.append("\"materials\":[")
        if (textured) {
            j.append("{\"name\":\"front\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},")
                .append("\"metallicFactor\":0.0,\"roughnessFactor\":1.0},\"extensions\":{\"").append(UNLIT).append("\":{}}},")
        } else {
            j.append("{\"name\":\"front\",\"pbrMetallicRoughness\":{\"baseColorFactor\":").append(linearRgba(frontColorSrgb))
                .append(",\"metallicFactor\":0.0,\"roughnessFactor\":0.6}},")
        }
        j.append("{\"name\":\"sides\",\"pbrMetallicRoughness\":{\"baseColorFactor\":").append(linearRgba(sideColorSrgb))
            .append(",\"metallicFactor\":0.0,\"roughnessFactor\":0.8}}],")
        if (textured) {
            j.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],")
            j.append("\"images\":[{\"bufferView\":").append(imgView).append(",\"mimeType\":\"image/jpeg\"}],")
            j.append("\"textures\":[{\"sampler\":0,\"source\":0}],")
        }
        bin.appendBuffersAndViews(j)
        j.append(",\"accessors\":[")
        j.append(vec3Accessor(posView, 24, pos)).append(',')
        j.append("{\"bufferView\":$nrmView,\"componentType\":$FLOAT,\"count\":24,\"type\":\"VEC3\"},")
        j.append("{\"bufferView\":$uvView,\"componentType\":$FLOAT,\"count\":24,\"type\":\"VEC2\"},")
        j.append("{\"bufferView\":$idxView,\"byteOffset\":0,\"componentType\":$UNSIGNED_SHORT,\"count\":6,\"type\":\"SCALAR\",\"min\":[0],\"max\":[3]},")
        j.append("{\"bufferView\":$idxView,\"byteOffset\":12,\"componentType\":$UNSIGNED_SHORT,\"count\":30,\"type\":\"SCALAR\",\"min\":[4],\"max\":[23]}")
        j.append("]}")
        return container(j, bin)
    }

    // ---- parts (furniture templates) --------------------------------------------------------------------

    /**
     * A multi-part model: every [Part] is a box (24 vertices, POSITION + NORMAL), all in one mesh with **one
     * primitive per material used** (in [PartMaterial] order), lit PBR materials coloured by [palette].
     * The union bounding box of all parts is translated so its [origin] point is the model origin, and
     * `extras.{widthM,heightM,depthM}` are that union box (templates guarantee it equals W×H×D).
     *
     * @throws IllegalArgumentException for no parts, more than [MAX_PARTS], a non-finite centre or yaw, a part side
     *   ≤ 0, or a union side over [MAX_SIDE_M].
     */
    fun writeParts(
        parts: List<Part>,
        palette: Palette = Palette.DEFAULT,
        origin: BoxOrigin = BoxOrigin.BOTTOM_CENTER,
        name: String = "SpaceView furniture",
        modelKind: ModelKind = ModelKind.TEMPLATE,
    ): ByteArray {
        require(parts.isNotEmpty() && parts.size <= MAX_PARTS) { "A model needs 1..$MAX_PARTS parts, got ${parts.size}" }
        for (p in parts) {
            require(p.center.isFinite && p.yawRad.isFinite()) { "Part '${p.name}' has a non-finite centre or yaw" }
            require(p.size.isFinite && p.size.x > 0 && p.size.y > 0 && p.size.z > 0) { "Part '${p.name}' sides must be > 0" }
        }
        // Parts grouped by material so each primitive's indices are one contiguous range.
        val ordered = parts.withIndex().sortedWith(compareBy({ it.value.material.ordinal }, { it.index })).map { it.value }
        val n = ordered.size
        val pos = DoubleArray(n * 24 * 3)
        val nrm = FloatArray(n * 24 * 3)
        val idx = IntArray(n * 36)
        for ((k, p) in ordered.withIndex()) {
            emitBox(p.center, p.size * 0.5, p.yawRad, pos, nrm, null, k * 24, idx, k * 36)
        }
        val (w, h, d) = recentre(pos, origin)
        for (s in doubleArrayOf(w, h, d)) require(s <= MAX_SIDE_M) { "Model side $s m exceeds $MAX_SIDE_M m" }

        val materials = ordered.map { it.material }.distinct()
        val bin = Bin()
        val posView = bin.view(ARRAY_BUFFER) { for (v in pos) f32(v.toFloat()) }
        val nrmView = bin.view(ARRAY_BUFFER) { for (v in nrm) f32(v) }
        val idxView = bin.view(ELEMENT_ARRAY_BUFFER) { for (i in idx) u16(i) }

        val j = StringBuilder(4096)
        j.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"SpaceView engine\"},")
        j.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
        j.append("\"nodes\":[{\"mesh\":0,\"name\":").append(jsonString(name)).append(",\"extras\":")
        writeExtras(j, w, h, d, origin, modelKind, emptyMap())
        j.append("}],")
        j.append("\"meshes\":[{\"name\":").append(jsonString(name)).append(",\"primitives\":[")
        materials.forEachIndexed { m, _ ->
            if (m > 0) j.append(',')
            j.append("{\"attributes\":{\"POSITION\":0,\"NORMAL\":1},\"indices\":").append(2 + m)
                .append(",\"material\":").append(m).append(",\"mode\":4}")
        }
        j.append("]}],\"materials\":[")
        materials.forEachIndexed { m, mat ->
            if (m > 0) j.append(',')
            j.append("{\"name\":").append(jsonString(mat.name.lowercase())).append(",\"pbrMetallicRoughness\":{\"baseColorFactor\":")
                .append(linearRgba(palette.colorOf(mat))).append(",\"metallicFactor\":0.0,\"roughnessFactor\":0.85}}")
        }
        j.append("],")
        bin.appendBuffersAndViews(j)
        j.append(",\"accessors\":[")
        j.append(vec3Accessor(posView, n * 24, pos)).append(',')
        j.append("{\"bufferView\":$nrmView,\"componentType\":$FLOAT,\"count\":${n * 24},\"type\":\"VEC3\"}")
        var start = 0
        for (mat in materials) {
            val count = ordered.count { it.material == mat }
            val first = start * 24
            val last = (start + count) * 24 - 1
            j.append(",{\"bufferView\":").append(idxView).append(",\"byteOffset\":").append(start * 36 * 2)
                .append(",\"componentType\":$UNSIGNED_SHORT,\"count\":").append(count * 36)
                .append(",\"type\":\"SCALAR\",\"min\":[").append(first).append("],\"max\":[").append(last).append("]}")
            start += count
        }
        j.append("]}")
        return container(j, bin)
    }

    // ---- mesh (on-device / cloud scan) -----------------------------------------------------------------

    /**
     * An indexed triangle mesh: POSITION, NORMAL (area-weighted vertex normals computed here) and, when the mesh
     * has colours, COLOR_0 (RGBA8, normalised). Indices are u16 when the mesh has at most 65,535 vertices, else
     * u32. One `doubleSided` material (the scan's open bottom never shows a hole), white × vertex colour, or a
     * neutral grey without colours; [unlit] adds `KHR_materials_unlit` (the captured colour already contains the
     * room's light). The input's axes must be the item frame (+Y up, +Z front); it is never scaled or rotated, and
     * the extras' W/H/D always describe the mesh's own bounding box (ADR-014, K8).
     *
     * Origin: by default the mesh is translated so its own bounding box's [origin] point is the model origin. With
     * [keepInputOrigin] the positions are written **unchanged**: the input frame's origin already is the [origin]
     * point of the *measured* box (a 3D scan is fused in the box frame with the origin at the centre of the box base
     * on the support plane, §7.1.8). Placement then stands the scan on the floor at its measured footprint instead
     * of dropping it by the floor cut (~1 cm) or re-centring a partial scan on its own bounding box (M4 review
     * MAJOR 2).
     *
     * @throws IllegalArgumentException for an empty or malformed mesh (array sizes, an index out of range, a
     *   non-finite position, a flat bounding box, a side over [MAX_SIDE_M]) or an invalid [MeshExtras] key.
     */
    fun writeMesh(
        mesh: TriMesh,
        origin: BoxOrigin = BoxOrigin.BOTTOM_CENTER,
        unlit: Boolean = true,
        extras: MeshExtras = MeshExtras(),
        keepInputOrigin: Boolean = false,
    ): ByteArray {
        val vCount = mesh.positions.size / 3
        require(mesh.positions.size % 3 == 0 && vCount >= 3) { "positions must hold >= 3 vertices (x, y, z each)" }
        require(vCount <= MAX_MESH_VERTICES) { "Mesh has $vCount vertices, over $MAX_MESH_VERTICES" }
        require(mesh.indices.size % 3 == 0 && mesh.indices.isNotEmpty()) { "indices must hold >= 1 triangle" }
        for (i in mesh.indices) require(i in 0 until vCount) { "Index $i out of range 0..${vCount - 1}" }
        val colourStride = when (mesh.colours.size) {
            0 -> 0
            3 * vCount -> 3
            4 * vCount -> 4
            else -> throw IllegalArgumentException("colours must be empty, RGB or RGBA per vertex, got ${mesh.colours.size} bytes")
        }
        for (k in extras.values.keys) {
            require(EXTRA_KEY.matches(k) && k !in RESERVED_KEYS) { "Invalid or reserved extras key '$k'" }
        }
        for (v in extras.values.values) require(v.isFinite()) { "Extras values must be finite" }

        val pos = DoubleArray(vCount * 3)
        for (i in pos.indices) {
            val v = mesh.positions[i]
            require(v.isFinite()) { "Non-finite position at component $i" }
            pos[i] = v.toDouble()
        }
        val (w, h, d) = if (keepInputOrigin) extents(pos) else recentre(pos, origin)
        for (s in doubleArrayOf(w, h, d)) require(s > 0 && s <= MAX_SIDE_M) { "Mesh sides must be in (0, $MAX_SIDE_M] m, got $s" }
        if (keepInputOrigin) {
            // The origin must lie near the mesh: a stray frame would place the scan metres away.
            for (a in 0 until 3) {
                var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
                for (i in a until pos.size step 3) { if (pos[i] < lo) lo = pos[i]; if (pos[i] > hi) hi = pos[i] }
                require(lo - ORIGIN_SLACK_M <= 0.0 && hi + ORIGIN_SLACK_M >= 0.0) {
                    "keepInputOrigin: the mesh is not near its frame origin"
                }
            }
        }
        val nrm = vertexNormals(pos, mesh.indices)
        val wide = vCount > 65_535

        val bin = Bin()
        val posView = bin.view(ARRAY_BUFFER) { for (v in pos) f32(v.toFloat()) }
        val nrmView = bin.view(ARRAY_BUFFER) { for (v in nrm) f32(v) }
        val colView = if (colourStride == 0) -1 else bin.view(ARRAY_BUFFER) {
            for (v in 0 until vCount) {
                val o = v * colourStride
                byte(mesh.colours[o]); byte(mesh.colours[o + 1]); byte(mesh.colours[o + 2])
                byte(if (colourStride == 4) mesh.colours[o + 3] else 0xFF.toByte())
            }
        }
        val idxView = bin.view(ELEMENT_ARRAY_BUFFER) { if (wide) for (i in mesh.indices) u32(i) else for (i in mesh.indices) u16(i) }

        val j = StringBuilder(2048)
        j.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"SpaceView engine\"},")
        if (unlit) j.append("\"extensionsUsed\":[\"").append(UNLIT).append("\"],")
        j.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
        j.append("\"nodes\":[{\"mesh\":0,\"name\":").append(jsonString(extras.name)).append(",\"extras\":")
        writeExtras(j, w, h, d, origin, extras.modelKind, extras.values)
        j.append("}],")
        j.append("\"meshes\":[{\"name\":").append(jsonString(extras.name)).append(",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1")
        if (colourStride != 0) j.append(",\"COLOR_0\":2")
        j.append("},\"indices\":").append(if (colourStride != 0) 3 else 2).append(",\"material\":0,\"mode\":4}]}],")
        j.append("\"materials\":[{\"name\":\"scan\",\"doubleSided\":true,\"pbrMetallicRoughness\":{\"baseColorFactor\":")
            .append(if (colourStride != 0) "[1.0,1.0,1.0,1.0]" else linearRgba(MESH_GREY))
            .append(",\"metallicFactor\":0.0,\"roughnessFactor\":0.9}")
        if (unlit) j.append(",\"extensions\":{\"").append(UNLIT).append("\":{}}")
        j.append("}],")
        bin.appendBuffersAndViews(j)
        j.append(",\"accessors\":[")
        j.append(vec3Accessor(posView, vCount, pos)).append(',')
        j.append("{\"bufferView\":$nrmView,\"componentType\":$FLOAT,\"count\":$vCount,\"type\":\"VEC3\"}")
        if (colourStride != 0) {
            j.append(",{\"bufferView\":$colView,\"componentType\":$UNSIGNED_BYTE,\"normalized\":true,\"count\":$vCount,\"type\":\"VEC4\"}")
        }
        var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
        for (i in mesh.indices) { if (i < lo) lo = i; if (i > hi) hi = i }
        j.append(",{\"bufferView\":$idxView,\"componentType\":").append(if (wide) UNSIGNED_INT else UNSIGNED_SHORT)
            .append(",\"count\":").append(mesh.indices.size).append(",\"type\":\"SCALAR\",\"min\":[").append(lo)
            .append("],\"max\":[").append(hi).append("]}")
        j.append("]}")
        return container(j, bin)
    }

    /** Neutral grey of an uncoloured mesh. */
    private const val MESH_GREY = 0xA8A49C

    /** keepInputOrigin: the frame origin may sit at most this far outside the mesh's bounding box on any axis. */
    const val ORIGIN_SLACK_M = 0.5

    // ---- geometry helpers ----------------------------------------------------------------------------

    /**
     * Writes one box (24 vertices from [v0], 36 indices from [i0]): faces as (normal, right, up) seen from
     * outside, front (+Z) first, rotated by [yaw] about +Y around [center]. [uv] may be null.
     */
    private fun emitBox(center: Vec3, half: Vec3, yaw: Double, pos: DoubleArray, nrm: FloatArray, uv: FloatArray?, v0: Int, idx: IntArray, i0: Int) {
        val c = cos(yaw); val s = sin(yaw)
        fun rot(v: Vec3): Vec3 = if (yaw == 0.0) v else Vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z)
        for ((f, face) in FACES.withIndex()) {
            val n = face[0]; val r = face[1]; val u = face[2]
            val local = n * ext(n, half)
            val er = ext(r, half); val eu = ext(u, half)
            val rn = rot(n)
            for ((k, sg) in CORNERS.withIndex()) {
                val v = v0 + f * 4 + k
                val p = center + rot(local + r * (sg[0] * er) + u * (sg[1] * eu))
                // "+ 0.0" turns -0.0 into 0.0 so bounds never print as "-0.0".
                pos[3 * v] = p.x + 0.0; pos[3 * v + 1] = p.y + 0.0; pos[3 * v + 2] = p.z + 0.0
                nrm[3 * v] = rn.x.toFloat(); nrm[3 * v + 1] = rn.y.toFloat(); nrm[3 * v + 2] = rn.z.toFloat()
                if (uv != null) { uv[2 * v] = sg[2].toFloat(); uv[2 * v + 1] = sg[3].toFloat() }
            }
            // CCW from outside: TL, BL, BR and TL, BR, TR.
            val b = v0 + f * 4
            val o = i0 + f * 6
            idx[o] = b; idx[o + 1] = b + 3; idx[o + 2] = b + 2
            idx[o + 3] = b; idx[o + 4] = b + 2; idx[o + 5] = b + 1
        }
    }

    private fun ext(axis: Vec3, half: Vec3) = abs(axis.x) * half.x + abs(axis.y) * half.y + abs(axis.z) * half.z

    private val FACES = arrayOf(
        arrayOf(Vec3.UNIT_Z, Vec3.UNIT_X, Vec3.UNIT_Y), // front +Z
        arrayOf(-Vec3.UNIT_Z, -Vec3.UNIT_X, Vec3.UNIT_Y), // back −Z
        arrayOf(Vec3.UNIT_X, -Vec3.UNIT_Z, Vec3.UNIT_Y), // right +X
        arrayOf(-Vec3.UNIT_X, Vec3.UNIT_Z, Vec3.UNIT_Y), // left −X
        arrayOf(Vec3.UNIT_Y, Vec3.UNIT_X, -Vec3.UNIT_Z), // top +Y
        arrayOf(-Vec3.UNIT_Y, Vec3.UNIT_X, Vec3.UNIT_Z), // bottom −Y
    )

    /** (right sign, up sign, u, v) of the four corners: TL (0,0), TR, BR, BL. */
    private val CORNERS = arrayOf(
        doubleArrayOf(-1.0, 1.0, 0.0, 0.0),
        doubleArrayOf(1.0, 1.0, 1.0, 0.0),
        doubleArrayOf(1.0, -1.0, 1.0, 1.0),
        doubleArrayOf(-1.0, -1.0, 0.0, 1.0),
    )

    /**
     * Translates [pos] (x, y, z triples) so the [origin] point of its bounding box is 0; returns the box's
     * extents (W, H, D). Values are snapped with "+ 0.0" so −0.0 never appears.
     */
    private fun recentre(pos: DoubleArray, origin: BoxOrigin): Triple<Double, Double, Double> {
        val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (i in pos.indices) {
            val a = i % 3
            if (pos[i] < min[a]) min[a] = pos[i]
            if (pos[i] > max[a]) max[a] = pos[i]
        }
        val anchor = doubleArrayOf(
            (min[0] + max[0]) / 2,
            if (origin == BoxOrigin.BOTTOM_CENTER) min[1] else (min[1] + max[1]) / 2,
            if (origin == BoxOrigin.BACK_CENTER) min[2] else (min[2] + max[2]) / 2,
        )
        if (anchor.any { it != 0.0 }) for (i in pos.indices) pos[i] = pos[i] - anchor[i % 3] + 0.0
        return Triple(max[0] - min[0], max[1] - min[1], max[2] - min[2])
    }

    /** Extents (W, H, D) of [pos] without moving it. */
    private fun extents(pos: DoubleArray): Triple<Double, Double, Double> {
        val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (i in pos.indices) {
            val a = i % 3
            if (pos[i] < min[a]) min[a] = pos[i]
            if (pos[i] > max[a]) max[a] = pos[i]
        }
        return Triple(max[0] - min[0], max[1] - min[1], max[2] - min[2])
    }

    /** Area-weighted vertex normals; a vertex of only degenerate triangles gets +Y. */
    private fun vertexNormals(pos: DoubleArray, indices: IntArray): FloatArray {
        val acc = DoubleArray(pos.size)
        var t = 0
        while (t < indices.size) {
            val a = 3 * indices[t]; val b = 3 * indices[t + 1]; val c = 3 * indices[t + 2]
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (o in intArrayOf(a, b, c)) { acc[o] += nx; acc[o + 1] += ny; acc[o + 2] += nz }
            t += 3
        }
        val out = FloatArray(pos.size)
        for (v in 0 until pos.size / 3) {
            val x = acc[3 * v]; val y = acc[3 * v + 1]; val z = acc[3 * v + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            if (len > 1e-20) {
                out[3 * v] = (x / len).toFloat(); out[3 * v + 1] = (y / len).toFloat(); out[3 * v + 2] = (z / len).toFloat()
            } else {
                out[3 * v + 1] = 1f
            }
        }
        return out
    }

    // ---- JSON helpers --------------------------------------------------------------------------------

    private fun writeExtras(j: StringBuilder, w: Double, h: Double, d: Double, origin: BoxOrigin, kind: ModelKind, values: Map<String, Double>) {
        j.append("{\"widthM\":").append(w).append(",\"heightM\":").append(h).append(",\"depthM\":").append(d)
            .append(",\"origin\":\"").append(origin.name).append("\",\"glbVersion\":").append(GLB_VERSION)
            .append(",\"modelKind\":\"").append(kind.name).append('"')
        for ((k, v) in values.entries.sortedBy { it.key }) j.append(",\"").append(k).append("\":").append(v)
        j.append('}')
    }

    /** POSITION accessor with min/max taken from the float values actually written. */
    private fun vec3Accessor(view: Int, count: Int, pos: DoubleArray): String {
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (i in 0 until count * 3) {
            val x = pos[i].toFloat()
            val a = i % 3
            if (x < min[a]) min[a] = x
            if (x > max[a]) max[a] = x
        }
        return "{\"bufferView\":$view,\"componentType\":$FLOAT,\"count\":$count,\"type\":\"VEC3\",\"min\":${floats(min)},\"max\":${floats(max)}}"
    }

    private fun floats(a: FloatArray) = a.joinToString(",", "[", "]") { (it + 0f).toString() }

    /** sRGB 0xRRGGBB → linear RGBA JSON array (glTF baseColorFactor is linear). */
    private fun linearRgba(srgb: Int): String {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((srgb shr 16) and 0xFF); val g = lin((srgb shr 8) and 0xFF); val b = lin(srgb and 0xFF)
        return "[${r.toFloat()},${g.toFloat()},${b.toFloat()},1.0]"
    }

    internal fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    // ---- container -----------------------------------------------------------------------------------

    /** Wraps the JSON and the binary chunk into a GLB (JSON padded with spaces, BIN with zeros). */
    private fun container(j: StringBuilder, bin: Bin): ByteArray {
        val json = j.toString().toByteArray(Charsets.UTF_8)
        val jsonLen = align4(json.size)
        val binBytes = bin.finish()
        val total = 12 + 8 + jsonLen + 8 + binBytes.size
        val out = Le(total)
        out.u32(MAGIC); out.u32(2); out.u32(total)
        out.u32(jsonLen); out.u32(CHUNK_JSON); out.bytes(json); out.padTo(12 + 8 + jsonLen, 0x20)
        out.u32(binBytes.size); out.u32(CHUNK_BIN); out.bytes(binBytes)
        check(out.pos == total)
        return out.buf
    }

    private fun align4(n: Int) = (n + 3) and 3.inv()

    /** Growable little-endian binary chunk with 4-byte-aligned buffer views. */
    private class Bin {
        private var buf = ByteArray(4096)
        private var cursor = 0
        private val views = ArrayList<IntArray>() // offset, length, target (0 = none)

        fun view(target: Int?, write: Bin.() -> Unit): Int {
            while (cursor % 4 != 0) byte(0)
            val start = cursor
            write()
            views += intArrayOf(start, cursor - start, target ?: 0)
            return views.size - 1
        }

        private fun ensure(n: Int) {
            if (cursor + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, cursor + n))
        }

        fun byte(b: Byte) { ensure(1); buf[cursor++] = b }
        fun byte(b: Int) = byte(b.toByte())
        fun u16(v: Int) { ensure(2); buf[cursor++] = v.toByte(); buf[cursor++] = (v shr 8).toByte() }
        fun u32(v: Int) { ensure(4); buf[cursor++] = v.toByte(); buf[cursor++] = (v shr 8).toByte(); buf[cursor++] = (v shr 16).toByte(); buf[cursor++] = (v shr 24).toByte() }
        fun f32(v: Float) = u32(java.lang.Float.floatToRawIntBits(v))
        fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, buf, cursor, b.size); cursor += b.size }

        /** Total (padded) length; also the `buffers[0].byteLength`. */
        val length: Int get() = (cursor + 3) and 3.inv()

        fun appendBuffersAndViews(j: StringBuilder) {
            j.append("\"buffers\":[{\"byteLength\":").append(length).append("}],\"bufferViews\":[")
            views.forEachIndexed { i, v ->
                if (i > 0) j.append(',')
                j.append("{\"buffer\":0,\"byteOffset\":").append(v[0]).append(",\"byteLength\":").append(v[1])
                if (v[2] != 0) j.append(",\"target\":").append(v[2])
                j.append('}')
            }
            j.append(']')
        }

        fun finish(): ByteArray = buf.copyOf(length)
    }

    /** Minimal little-endian writer over a fixed array (avoids ByteBuffer API differences on old Android). */
    private class Le(size: Int) {
        val buf = ByteArray(size)
        var pos = 0
        fun u32(v: Int) { buf[pos++] = v.toByte(); buf[pos++] = (v shr 8).toByte(); buf[pos++] = (v shr 16).toByte(); buf[pos++] = (v shr 24).toByte() }
        fun bytes(b: ByteArray) { System.arraycopy(b, 0, buf, pos, b.size); pos += b.size }
        fun padTo(end: Int, fill: Int) { while (pos < end) buf[pos++] = fill.toByte() }
    }
}
