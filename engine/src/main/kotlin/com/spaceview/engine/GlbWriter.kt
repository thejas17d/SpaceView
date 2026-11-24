package com.spaceview.engine

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
 * The API (contract K9, docs/contracts/phase0.md; PLAN v3 for v1):
 * - [writeCutout]: the v1 scan model, a flat background-free picture of the object at its true W × H (PLAN v3 W2).
 * - [writeBox]: the M3 measured proxy box; the photo face is unlit (ADR-003). Still used for legacy rows and as the
 *   placement fallback.
 * The template (`writeParts`) and mesh (`writeMesh`) writers left v1 with the 3D features (branch next/3d-engine);
 * GLBs they wrote earlier are still read and placed.
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
    private const val UNSIGNED_SHORT = 5123
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val UNLIT = "KHR_materials_unlit"

    /** Largest accepted side (m); anything bigger is a bug upstream, not an object. */
    const val MAX_SIDE_M = 50.0

    /**
     * Extras keys of a legacy 3D-scan mesh's bounding-box centre in the GLB frame (M4-P1 gate N3), written by the
     * mesh writer of earlier test builds. Such a mesh's box is not centred on its declared origin, so a reader must not
     * derive the centre from the origin. Absent on every other model, whose centre is [BoxOrigin.centreOffset].
     */
    const val AABB_CX = "aabbCx"

    /** See [AABB_CX]. */
    const val AABB_CY = "aabbCy"

    /** See [AABB_CX]. */
    const val AABB_CZ = "aabbCz"

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

    // ---- cutout (v1 scan model, PLAN v3 W2) ------------------------------------------------------------

    /** How a cutout's picture covers its quad. */
    enum class CutoutAlpha {
        /** The picture fills the whole rectangle (a flat object cropped to its corners, or "Undo cutout"). */
        OPAQUE,

        /**
         * The picture has a background-free alpha channel (PNG, straight alpha). glTF `alphaMode: BLEND`; the
         * renderer premultiplies, and the picture's transparent pixels must carry the colour of their nearest
         * opaque neighbour (edge bleeding), so filtering never pulls black into the edge (no dark halo).
         */
        BLEND,
    }

    /**
     * The v1 scan model: one flat, true-size, double-sided, unlit, textured quad. It is [widthM] × [heightM] in the
     * XY plane (z = 0), facing +Z, centred on the origin ([BoxOrigin.CENTER]), so placement can stand it on a floor
     * (its bottom edge at −H/2) or hang it on a wall (flat against it). UV (0,0) is the picture's top-left corner.
     * Extras: `widthM`, `heightM`, `depthM` = 0 (the quad's own bounding box; the item's measured depth is kept by the
     * gallery row, never rendered), `origin` CENTER, `glbVersion`, `modelKind` [modelKind].
     *
     * @param image JPEG (FF D8) or PNG (89 50 4E 47) bytes, or null for a plain [colorSrgb] quad (a scan without a photo).
     * @param alpha [CutoutAlpha.BLEND] needs a PNG; [CutoutAlpha.OPAQUE] accepts either (alpha is ignored).
     * @throws IllegalArgumentException for a side outside (0, [MAX_SIDE_M]], an unknown image format, or BLEND
     *   without a PNG.
     */
    fun writeCutout(
        widthM: Double,
        heightM: Double,
        image: ByteArray?,
        alpha: CutoutAlpha = CutoutAlpha.OPAQUE,
        colorSrgb: Int = 0xD8D5CC,
        name: String = "SpaceView item",
        modelKind: ModelKind = ModelKind.CUTOUT,
    ): ByteArray {
        for (side in doubleArrayOf(widthM, heightM)) {
            require(side.isFinite() && side > 0 && side <= MAX_SIDE_M) { "Cutout sides must be in (0, $MAX_SIDE_M] m, got $side" }
        }
        val mime = image?.let { imageMime(it) ?: throw IllegalArgumentException("Cutout image is neither JPEG nor PNG") }
        require(alpha == CutoutAlpha.OPAQUE || mime == "image/png") { "An alpha cutout needs a PNG image" }

        val hw = widthM / 2; val hh = heightM / 2
        // TL, TR, BR, BL (counter-clockwise from +Z: TL, BL, BR and TL, BR, TR).
        val pos = doubleArrayOf(-hw, hh, 0.0, hw, hh, 0.0, hw, -hh, 0.0, -hw, -hh, 0.0)
        val uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val idx = intArrayOf(0, 3, 2, 0, 2, 1)

        val bin = Bin()
        val posView = bin.view(ARRAY_BUFFER) { for (v in pos) f32(v.toFloat()) }
        val nrmView = bin.view(ARRAY_BUFFER) { repeat(4) { f32(0f); f32(0f); f32(1f) } }
        val uvView = bin.view(ARRAY_BUFFER) { for (v in uv) f32(v) }
        val idxView = bin.view(ELEMENT_ARRAY_BUFFER) { for (i in idx) u16(i) }
        val imgView = if (image != null) bin.view(null) { bytes(image) } else -1

        val j = StringBuilder(1536)
        j.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"SpaceView engine\"},")
        j.append("\"extensionsUsed\":[\"").append(UNLIT).append("\"],")
        j.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
        j.append("\"nodes\":[{\"mesh\":0,\"name\":").append(jsonString(name)).append(",\"extras\":")
        writeExtras(j, widthM, heightM, 0.0, BoxOrigin.CENTER, modelKind, emptyMap())
        j.append("}],")
        j.append("\"meshes\":[{\"name\":").append(jsonString(name)).append(",\"primitives\":[")
        j.append("{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"material\":0,\"mode\":4}]}],")
        j.append("\"materials\":[{\"name\":\"cutout\",\"doubleSided\":true,")
        if (alpha == CutoutAlpha.BLEND) j.append("\"alphaMode\":\"BLEND\",")
        j.append("\"pbrMetallicRoughness\":{")
        if (image != null) {
            j.append("\"baseColorTexture\":{\"index\":0},")
        } else {
            j.append("\"baseColorFactor\":").append(linearRgba(colorSrgb)).append(',')
        }
        j.append("\"metallicFactor\":0.0,\"roughnessFactor\":1.0},\"extensions\":{\"").append(UNLIT).append("\":{}}}],")
        if (image != null) {
            j.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],")
            j.append("\"images\":[{\"bufferView\":").append(imgView).append(",\"mimeType\":\"").append(mime).append("\"}],")
            j.append("\"textures\":[{\"sampler\":0,\"source\":0}],")
        }
        bin.appendBuffersAndViews(j)
        j.append(",\"accessors\":[")
        j.append(vec3Accessor(posView, 4, pos)).append(',')
        j.append("{\"bufferView\":$nrmView,\"componentType\":$FLOAT,\"count\":4,\"type\":\"VEC3\"},")
        j.append("{\"bufferView\":$uvView,\"componentType\":$FLOAT,\"count\":4,\"type\":\"VEC2\"},")
        j.append("{\"bufferView\":$idxView,\"componentType\":$UNSIGNED_SHORT,\"count\":6,\"type\":\"SCALAR\",\"min\":[0],\"max\":[3]}")
        j.append("]}")
        return container(j, bin)
    }

    /** `image/jpeg` or `image/png` from the magic bytes, else null. */
    internal fun imageMime(b: ByteArray): String? = when {
        b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
        else -> null
    }

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
