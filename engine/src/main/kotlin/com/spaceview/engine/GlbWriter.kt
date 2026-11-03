package com.spaceview.engine

import kotlin.math.pow

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
    BACK_CENTER,
}

/**
 * Writes a true-size textured box as a glTF 2.0 binary (.glb), 1 unit = 1 metre.
 *
 * Layout: one mesh, 24 vertices (4 per face, so normals and UVs are per face), POSITION / NORMAL /
 * TEXCOORD_0, two primitives sharing the vertices: the front (+Z) face with the JPEG texture, and the other
 * five faces in a flat colour. Geometry + JSON are ~2–3 KB; the file is that plus the JPEG.
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

    /** Largest accepted side (m); anything bigger is a bug upstream, not furniture. */
    const val MAX_SIDE_M = 50.0

    /**
     * Builds the GLB.
     *
     * @param widthM X extent (m), [heightM] Y extent, [depthM] Z extent; all > 0.
     * @param frontJpeg rectified front photo (JPEG bytes, must start with FF D8), or null for an untextured front.
     * @param sideColorSrgb 0xRRGGBB colour (sRGB) of the other faces, e.g. sampled from the photo edges.
     * @param frontColorSrgb front colour used only when [frontJpeg] is null.
     * @param name node/mesh name (escaped into JSON).
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
    ): ByteArray {
        for (s in doubleArrayOf(widthM, heightM, depthM)) {
            require(s.isFinite() && s > 0 && s <= MAX_SIDE_M) { "Box sides must be in (0, $MAX_SIDE_M] m, got $s" }
        }
        if (frontJpeg != null) {
            require(frontJpeg.size >= 4 && frontJpeg[0] == 0xFF.toByte() && frontJpeg[1] == 0xD8.toByte()) {
                "frontJpeg is not a JPEG (missing FF D8 SOI marker)"
            }
        }

        val hw = widthM / 2; val hh = heightM / 2; val hd = depthM / 2
        val center = when (origin) {
            BoxOrigin.CENTER -> Vec3.ZERO
            BoxOrigin.BOTTOM_CENTER -> Vec3(0.0, hh, 0.0)
            BoxOrigin.BACK_CENTER -> Vec3(0.0, 0.0, hd)
        }
        val half = Vec3(hw, hh, hd)

        // Faces as (normal, right, up) seen from outside; front first so it gets vertices 0..3.
        val faces = arrayOf(
            arrayOf(Vec3.UNIT_Z, Vec3.UNIT_X, Vec3.UNIT_Y), // front +Z
            arrayOf(-Vec3.UNIT_Z, -Vec3.UNIT_X, Vec3.UNIT_Y), // back −Z
            arrayOf(Vec3.UNIT_X, -Vec3.UNIT_Z, Vec3.UNIT_Y), // right +X
            arrayOf(-Vec3.UNIT_X, Vec3.UNIT_Z, Vec3.UNIT_Y), // left −X
            arrayOf(Vec3.UNIT_Y, Vec3.UNIT_X, -Vec3.UNIT_Z), // top +Y
            arrayOf(-Vec3.UNIT_Y, Vec3.UNIT_X, Vec3.UNIT_Z), // bottom −Y
        )
        val pos = FloatArray(24 * 3)
        val nrm = FloatArray(24 * 3)
        val uv = FloatArray(24 * 2)
        val idx = ShortArray(36)
        val cornerSigns = arrayOf(
            doubleArrayOf(-1.0, 1.0, 0.0, 0.0), // top-left  (u,v)=(0,0)
            doubleArrayOf(1.0, 1.0, 1.0, 0.0), // top-right
            doubleArrayOf(1.0, -1.0, 1.0, 1.0), // bottom-right
            doubleArrayOf(-1.0, -1.0, 0.0, 1.0), // bottom-left
        )
        for ((f, face) in faces.withIndex()) {
            val n = face[0]; val r = face[1]; val u = face[2]
            val ext = { axis: Vec3 -> kotlin.math.abs(axis.x) * half.x + kotlin.math.abs(axis.y) * half.y + kotlin.math.abs(axis.z) * half.z }
            val fc = center + n * ext(n)
            val er = ext(r); val eu = ext(u)
            for ((c, s) in cornerSigns.withIndex()) {
                val v = f * 4 + c
                val p = fc + r * (s[0] * er) + u * (s[1] * eu)
                // "+ 0.0" turns -0.0 into 0.0 so bounds never print as "-0.0".
                pos[3 * v] = (p.x + 0.0).toFloat(); pos[3 * v + 1] = (p.y + 0.0).toFloat(); pos[3 * v + 2] = (p.z + 0.0).toFloat()
                nrm[3 * v] = n.x.toFloat(); nrm[3 * v + 1] = n.y.toFloat(); nrm[3 * v + 2] = n.z.toFloat()
                uv[2 * v] = s[2].toFloat(); uv[2 * v + 1] = s[3].toFloat()
            }
            // CCW from outside: TL, BL, BR and TL, BR, TR.
            val b = f * 4
            val o = f * 6
            idx[o] = b.toShort(); idx[o + 1] = (b + 3).toShort(); idx[o + 2] = (b + 2).toShort()
            idx[o + 3] = b.toShort(); idx[o + 4] = (b + 2).toShort(); idx[o + 5] = (b + 1).toShort()
        }

        // ---- binary buffer ----
        val posOff = 0
        val nrmOff = posOff + pos.size * 4
        val uvOff = nrmOff + nrm.size * 4
        val idxOff = uvOff + uv.size * 4
        val idxLen = idx.size * 2
        val imgOff = align4(idxOff + idxLen)
        val imgLen = frontJpeg?.size ?: 0
        val binLen = align4(imgOff + imgLen)
        val bin = LeWriter(binLen)
        pos.forEach { bin.f32(it) }
        nrm.forEach { bin.f32(it) }
        uv.forEach { bin.f32(it) }
        idx.forEach { bin.u16(it.toInt()) }
        bin.padTo(imgOff, 0)
        if (frontJpeg != null) bin.bytes(frontJpeg)
        bin.padTo(binLen, 0)

        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (v in 0 until 24) for (a in 0 until 3) {
            val x = pos[3 * v + a]
            if (x < min[a]) min[a] = x
            if (x > max[a]) max[a] = x
        }

        // ---- JSON ----
        val textured = frontJpeg != null
        val j = StringBuilder(2048)
        j.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"SpaceView engine\"},")
        j.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],")
        j.append("\"nodes\":[{\"mesh\":0,\"name\":").append(jsonString(name))
        j.append(",\"extras\":{\"widthM\":").append(widthM).append(",\"heightM\":").append(heightM)
            .append(",\"depthM\":").append(depthM).append(",\"origin\":\"").append(origin.name).append("\"}}],")
        val attrs = "{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2}"
        j.append("\"meshes\":[{\"name\":").append(jsonString(name)).append(",\"primitives\":[")
        j.append("{\"attributes\":").append(attrs).append(",\"indices\":3,\"material\":0,\"mode\":4},")
        j.append("{\"attributes\":").append(attrs).append(",\"indices\":4,\"material\":1,\"mode\":4}]}],")
        j.append("\"materials\":[")
        if (textured) {
            j.append("{\"name\":\"front\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},")
                .append("\"metallicFactor\":0.0,\"roughnessFactor\":0.6}},")
        } else {
            j.append("{\"name\":\"front\",\"pbrMetallicRoughness\":{\"baseColorFactor\":").append(linearRgba(frontColorSrgb))
                .append(",\"metallicFactor\":0.0,\"roughnessFactor\":0.6}},")
        }
        j.append("{\"name\":\"sides\",\"pbrMetallicRoughness\":{\"baseColorFactor\":").append(linearRgba(sideColorSrgb))
            .append(",\"metallicFactor\":0.0,\"roughnessFactor\":0.8}}],")
        if (textured) {
            j.append("\"samplers\":[{\"magFilter\":9729,\"minFilter\":9987,\"wrapS\":33071,\"wrapT\":33071}],")
            j.append("\"images\":[{\"bufferView\":4,\"mimeType\":\"image/jpeg\"}],")
            j.append("\"textures\":[{\"sampler\":0,\"source\":0}],")
        }
        j.append("\"buffers\":[{\"byteLength\":").append(binLen).append("}],")
        j.append("\"bufferViews\":[")
        j.append(bufferView(posOff, pos.size * 4, ARRAY_BUFFER)).append(',')
        j.append(bufferView(nrmOff, nrm.size * 4, ARRAY_BUFFER)).append(',')
        j.append(bufferView(uvOff, uv.size * 4, ARRAY_BUFFER)).append(',')
        j.append(bufferView(idxOff, idxLen, ELEMENT_ARRAY_BUFFER))
        if (textured) j.append(',').append("{\"buffer\":0,\"byteOffset\":").append(imgOff).append(",\"byteLength\":").append(imgLen).append('}')
        j.append("],\"accessors\":[")
        j.append("{\"bufferView\":0,\"componentType\":$FLOAT,\"count\":24,\"type\":\"VEC3\",\"min\":")
            .append(floats(min)).append(",\"max\":").append(floats(max)).append("},")
        j.append("{\"bufferView\":1,\"componentType\":$FLOAT,\"count\":24,\"type\":\"VEC3\"},")
        j.append("{\"bufferView\":2,\"componentType\":$FLOAT,\"count\":24,\"type\":\"VEC2\"},")
        j.append("{\"bufferView\":3,\"byteOffset\":0,\"componentType\":$UNSIGNED_SHORT,\"count\":6,\"type\":\"SCALAR\",\"min\":[0],\"max\":[3]},")
        j.append("{\"bufferView\":3,\"byteOffset\":12,\"componentType\":$UNSIGNED_SHORT,\"count\":30,\"type\":\"SCALAR\",\"min\":[4],\"max\":[23]}")
        j.append("]}")

        val json = j.toString().toByteArray(Charsets.UTF_8)
        val jsonLen = align4(json.size)
        val total = 12 + 8 + jsonLen + 8 + binLen
        val out = LeWriter(total)
        out.u32(MAGIC); out.u32(2); out.u32(total)
        out.u32(jsonLen); out.u32(CHUNK_JSON); out.bytes(json); out.padTo(12 + 8 + jsonLen, 0x20)
        out.u32(binLen); out.u32(CHUNK_BIN); out.bytes(bin.buf)
        check(out.pos == total)
        return out.buf
    }

    private fun align4(n: Int) = (n + 3) and 3.inv()

    private fun bufferView(off: Int, len: Int, target: Int) =
        "{\"buffer\":0,\"byteOffset\":$off,\"byteLength\":$len,\"target\":$target}"

    private fun floats(a: FloatArray) = a.joinToString(",", "[", "]") { it.toString() }

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

    /** Minimal little-endian writer over a fixed array (avoids ByteBuffer API differences on old Android). */
    private class LeWriter(size: Int) {
        val buf = ByteArray(size)
        var pos = 0
        fun u16(v: Int) { buf[pos++] = v.toByte(); buf[pos++] = (v shr 8).toByte() }
        fun u32(v: Int) { buf[pos++] = v.toByte(); buf[pos++] = (v shr 8).toByte(); buf[pos++] = (v shr 16).toByte(); buf[pos++] = (v shr 24).toByte() }
        fun f32(v: Float) = u32(java.lang.Float.floatToRawIntBits(v))
        fun bytes(b: ByteArray) { System.arraycopy(b, 0, buf, pos, b.size); pos += b.size }
        fun padTo(end: Int, fill: Int) { while (pos < end) buf[pos++] = fill.toByte() }
    }
}
