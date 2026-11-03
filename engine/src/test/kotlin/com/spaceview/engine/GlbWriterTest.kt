package com.spaceview.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class GlbWriterTest {

    /** Parsed GLB: JSON root and BIN chunk bytes. */
    private class Glb(val json: Map<String, Any?>, val bin: ByteArray, val jsonChunkLength: Int)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun f32(b: ByteArray, off: Int): Float = java.lang.Float.intBitsToFloat(u32(b, off))

    /** Validates the container per glTF 2.0 §4.4 and returns its parts. */
    private fun parse(glb: ByteArray): Glb {
        assertEquals("magic", 0x46546C67, u32(glb, 0))
        assertEquals("version", 2, u32(glb, 4))
        assertEquals("total length", glb.size, u32(glb, 8))
        assertEquals(0, glb.size % 4)
        val jsonLen = u32(glb, 12)
        assertEquals(0x4E4F534A, u32(glb, 16))
        assertEquals("JSON chunk 4-byte aligned", 0, jsonLen % 4)
        val jsonText = String(glb, 20, jsonLen, Charsets.UTF_8)
        // Padding must be spaces, so the JSON text must parse as is.
        val json = MiniJson.parse(jsonText) as Map<String, Any?>
        val binHeader = 20 + jsonLen
        val binLen = u32(glb, binHeader)
        assertEquals(0x004E4942, u32(glb, binHeader + 4))
        assertEquals("BIN chunk 4-byte aligned", 0, binLen % 4)
        assertEquals("no trailing bytes", glb.size, binHeader + 8 + binLen)
        return Glb(json, glb.copyOfRange(binHeader + 8, binHeader + 8 + binLen), jsonLen)
    }

    private fun list(m: Map<String, Any?>, k: String) = m[k] as List<Map<String, Any?>>
    private fun int(m: Map<String, Any?>, k: String) = (m[k] as Double).toInt()

    private val fakeJpeg = ByteArray(5003) { i ->
        when (i) {
            0 -> 0xFF.toByte()
            1 -> 0xD8.toByte()
            5001 -> 0xFF.toByte()
            5002 -> 0xD9.toByte()
            else -> (i * 31).toByte()
        }
    }

    @Test fun wellFormedTexturedBox() {
        val glb = GlbWriter.writeBox(0.6, 0.4, 0.03, fakeJpeg, sideColorSrgb = 0x3A2B1C, origin = BoxOrigin.BACK_CENTER, name = "Café \"frame\"")
        val g = parse(glb)
        val json = g.json
        assertEquals("2.0", (json["asset"] as Map<String, Any?>)["version"])
        assertEquals(0, int(json, "scene"))

        // Buffer and views stay inside the BIN chunk, aligned for their component types.
        val buffers = list(json, "buffers")
        assertEquals(1, buffers.size)
        val byteLength = int(buffers[0], "byteLength")
        assertTrue(byteLength <= g.bin.size && g.bin.size - byteLength < 4)
        val views = list(json, "bufferViews")
        for (v in views) {
            val off = int(v, "byteOffset"); val len = int(v, "byteLength")
            assertTrue(off >= 0 && off + len <= byteLength)
            assertEquals(0, off % 4)
        }

        // Accessors: counts, types, bounds.
        val acc = list(json, "accessors")
        assertEquals(5, acc.size)
        assertEquals(24, int(acc[0], "count")); assertEquals("VEC3", acc[0]["type"])
        assertEquals(24, int(acc[1], "count")); assertEquals("VEC3", acc[1]["type"])
        assertEquals(24, int(acc[2], "count")); assertEquals("VEC2", acc[2]["type"])
        assertEquals(6, int(acc[3], "count"))
        assertEquals(30, int(acc[4], "count"))
        for (a in acc) {
            val view = views[int(a, "bufferView")]
            val compSize = if (int(a, "componentType") == 5126) 4 else 2
            val comps = mapOf("SCALAR" to 1, "VEC2" to 2, "VEC3" to 3)[a["type"]]!!
            val aOff = (a["byteOffset"] as Double?)?.toInt() ?: 0
            assertEquals(0, aOff % compSize)
            assertTrue(aOff + int(a, "count") * comps * compSize <= int(view, "byteLength"))
        }
        val min = (acc[0]["min"] as List<Double>)
        val max = (acc[0]["max"] as List<Double>)
        assertEquals(listOf(-0.3, -0.2, 0.0), min.map { Math.round(it * 1e6) / 1e6 })
        assertEquals(listOf(0.3, 0.2, 0.03), max.map { Math.round(it * 1e6) / 1e6 })

        // Read the actual vertex data and check it matches the declared bounds exactly.
        val posOff = int(views[int(acc[0], "bufferView")], "byteOffset")
        val pos = Array(24) { v -> FloatArray(3) { a -> f32(g.bin, posOff + (v * 3 + a) * 4) } }
        for (a in 0 until 3) {
            assertEquals(min[a].toFloat(), pos.minOf { it[a] }, 0f)
            assertEquals(max[a].toFloat(), pos.maxOf { it[a] }, 0f)
        }
        // True size in metres.
        assertEquals(0.6f, pos.maxOf { it[0] } - pos.minOf { it[0] }, 1e-6f)
        assertEquals(0.4f, pos.maxOf { it[1] } - pos.minOf { it[1] }, 1e-6f)
        assertEquals(0.03f, pos.maxOf { it[2] } - pos.minOf { it[2] }, 1e-6f)

        // Normals are unit length; every triangle is CCW from outside (geometric normal agrees).
        val nOff = int(views[int(acc[1], "bufferView")], "byteOffset")
        val nrm = Array(24) { v -> Vec3(f32(g.bin, nOff + v * 12).toDouble(), f32(g.bin, nOff + v * 12 + 4).toDouble(), f32(g.bin, nOff + v * 12 + 8).toDouble()) }
        for (n in nrm) assertEquals(1.0, n.length, 1e-6)
        val iView = views[int(acc[3], "bufferView")]
        val iOff = int(iView, "byteOffset")
        val indices = IntArray(36) { u16(g.bin, iOff + it * 2) }
        val p = { i: Int -> Vec3(pos[i][0].toDouble(), pos[i][1].toDouble(), pos[i][2].toDouble()) }
        for (t in 0 until 12) {
            val a = indices[3 * t]; val b = indices[3 * t + 1]; val c = indices[3 * t + 2]
            assertTrue(a < 24 && b < 24 && c < 24)
            val geo = (p(b) - p(a)) cross (p(c) - p(a))
            assertTrue("triangle $t winding", (geo dot nrm[a]) > 0)
        }
        // Front primitive uses only front (+Z) vertices.
        for (i in 0 until 6) assertEquals(1.0, nrm[indices[i]].z, 1e-6)

        // UVs in [0,1]; the front face's top-left vertex has UV (0,0).
        val uvOff = int(views[int(acc[2], "bufferView")], "byteOffset")
        for (k in 0 until 48) assertTrue(f32(g.bin, uvOff + k * 4) in 0f..1f)
        val topLeft = (0 until 4).first { pos[it][0] < 0 && pos[it][1] > 0f }
        assertEquals(0f, f32(g.bin, uvOff + topLeft * 8), 0f)
        assertEquals(0f, f32(g.bin, uvOff + topLeft * 8 + 4), 0f)

        // Texture wiring and the embedded JPEG round-trips byte for byte.
        val images = list(json, "images")
        assertEquals("image/jpeg", images[0]["mimeType"])
        val imgView = views[int(images[0], "bufferView")]
        val jpegBack = g.bin.copyOfRange(int(imgView, "byteOffset"), int(imgView, "byteOffset") + int(imgView, "byteLength"))
        assertArrayEquals(fakeJpeg, jpegBack)
        val mats = list(json, "materials")
        val front = mats[0]["pbrMetallicRoughness"] as Map<String, Any?>
        assertEquals(0, int(front["baseColorTexture"] as Map<String, Any?>, "index"))
        val sides = mats[1]["pbrMetallicRoughness"] as Map<String, Any?>
        val color = sides["baseColorFactor"] as List<Double>
        assertEquals(4, color.size)
        assertTrue(color.all { it in 0.0..1.0 })
        assertEquals(1, list(json, "textures").size)
        assertEquals(1, list(json, "samplers").size)

        // Mesh: two primitives (front textured + sides), name escaped correctly.
        val prims = list(list(json, "meshes")[0], "primitives")
        assertEquals(2, prims.size)
        assertEquals(listOf(0, 1), prims.map { int(it, "material") })
        assertEquals("Café \"frame\"", list(json, "nodes")[0]["name"])
        val extras = list(json, "nodes")[0]["extras"] as Map<String, Any?>
        assertEquals(0.6, extras["widthM"] as Double, 0.0)

        // Size budget: geometry + JSON overhead well under 0.5 MB.
        assertTrue("overhead=${glb.size - fakeJpeg.size}", glb.size - fakeJpeg.size < 4096)
    }

    @Test fun untexturedBoxHasNoImages() {
        val glb = GlbWriter.writeBox(0.45, 0.26, 0.35)
        val g = parse(glb)
        assertNull(g.json["images"])
        assertNull(g.json["textures"])
        assertEquals(4, list(g.json, "bufferViews").size)
        val front = list(g.json, "materials")[0]["pbrMetallicRoughness"] as Map<String, Any?>
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), front["baseColorFactor"])
        assertTrue(glb.size < 4096)
    }

    @Test fun originPlacement() {
        fun bounds(origin: BoxOrigin): Pair<List<Double>, List<Double>> {
            val acc = list(parse(GlbWriter.writeBox(1.0, 2.0, 0.5, origin = origin)).json, "accessors")[0]
            return (acc["min"] as List<Double>) to (acc["max"] as List<Double>)
        }
        assertEquals(listOf(-0.5, -1.0, -0.25) to listOf(0.5, 1.0, 0.25), bounds(BoxOrigin.CENTER))
        assertEquals(listOf(-0.5, 0.0, -0.25) to listOf(0.5, 2.0, 0.25), bounds(BoxOrigin.BOTTOM_CENTER))
        assertEquals(listOf(-0.5, -1.0, 0.0) to listOf(0.5, 1.0, 0.5), bounds(BoxOrigin.BACK_CENTER))
    }

    @Test fun jsonPaddingIsSpaces() {
        // Try names of several lengths so every padding amount (0–3) occurs.
        for (n in 0 until 4) {
            val glb = GlbWriter.writeBox(0.1, 0.1, 0.1, name = "x".repeat(n))
            val g = parse(glb)
            val text = String(glb, 20, g.jsonChunkLength, Charsets.UTF_8)
            val trimmed = text.trimEnd(' ')
            assertTrue(text.length - trimmed.length < 4)
            assertTrue(trimmed.endsWith("}"))
        }
    }

    @Test fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", GlbWriter.jsonString("a\"b\\c\n"))
        assertEquals("\"नमस्ते\"", GlbWriter.jsonString("नमस्ते"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJpeg() { GlbWriter.writeBox(0.1, 0.1, 0.1, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroSide() { GlbWriter.writeBox(0.0, 0.1, 0.1) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHugeSide() { GlbWriter.writeBox(51.0, 0.1, 0.1) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNaN() { GlbWriter.writeBox(Double.NaN, 0.1, 0.1) }

    @Test fun deterministicOutput() {
        assertArrayEquals(GlbWriter.writeBox(0.5, 0.5, 0.5, fakeJpeg), GlbWriter.writeBox(0.5, 0.5, 0.5, fakeJpeg))
        assertFalse(GlbWriter.writeBox(0.5, 0.5, 0.5).contentEquals(GlbWriter.writeBox(0.5, 0.5, 0.6)))
    }
}
