package com.spaceview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Validator-style GLB checks shared by the writer tests: the container (glTF 2.0 §4.4), buffer views inside the
 * BIN chunk and 4-byte aligned, accessors inside their views and aligned to their component size, POSITION
 * min/max equal to the data, every index in range and every triangle counter-clockwise against the normals of
 * its vertices (for faceted geometry).
 */
@Suppress("UNCHECKED_CAST")
object GlbTestKit {
    class Glb(val json: Map<String, Any?>, val bin: ByteArray) {
        fun list(k: String) = json[k] as List<Map<String, Any?>>
        val node0Extras: Map<String, Any?> get() = list("nodes")[0]["extras"] as Map<String, Any?>
        val primitives: List<Map<String, Any?>> get() = list("meshes")[0]["primitives"] as List<Map<String, Any?>>

        fun accessor(i: Int) = list("accessors")[i]

        /** Floats of a FLOAT accessor. */
        fun floats(i: Int): FloatArray {
            val a = accessor(i)
            val v = list("bufferViews")[int(a, "bufferView")]
            val off = int(v, "byteOffset") + ((a["byteOffset"] as Double?)?.toInt() ?: 0)
            val n = int(a, "count") * comps(a["type"] as String)
            return FloatArray(n) { java.lang.Float.intBitsToFloat(u32(bin, off + 4 * it)) }
        }

        /** Unsigned ints of an index accessor (u16 or u32). */
        fun ints(i: Int): IntArray {
            val a = accessor(i)
            val v = list("bufferViews")[int(a, "bufferView")]
            val off = int(v, "byteOffset") + ((a["byteOffset"] as Double?)?.toInt() ?: 0)
            val wide = int(a, "componentType") == 5125
            return IntArray(int(a, "count")) { if (wide) u32(bin, off + 4 * it) else u16(bin, off + 2 * it) }
        }

        /** Raw bytes of a UNSIGNED_BYTE accessor. */
        fun bytes(i: Int): ByteArray {
            val a = accessor(i)
            val v = list("bufferViews")[int(a, "bufferView")]
            val off = int(v, "byteOffset")
            return bin.copyOfRange(off, off + int(a, "count") * comps(a["type"] as String))
        }
    }

    fun int(m: Map<String, Any?>, k: String) = (m[k] as Double).toInt()

    private fun comps(type: String) = mapOf("SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4).getValue(type)

    fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** Parses and validates; fails the test on any structural problem. */
    fun parse(glb: ByteArray): Glb {
        assertEquals("magic", 0x46546C67, u32(glb, 0))
        assertEquals("version", 2, u32(glb, 4))
        assertEquals("total length", glb.size, u32(glb, 8))
        val jsonLen = u32(glb, 12)
        assertEquals(0x4E4F534A, u32(glb, 16))
        assertEquals(0, jsonLen % 4)
        val json = MiniJson.parse(String(glb, 20, jsonLen, Charsets.UTF_8)) as Map<String, Any?>
        val binHeader = 20 + jsonLen
        val binLen = u32(glb, binHeader)
        assertEquals(0x004E4942, u32(glb, binHeader + 4))
        assertEquals(0, binLen % 4)
        assertEquals(glb.size, binHeader + 8 + binLen)
        val g = Glb(json, glb.copyOfRange(binHeader + 8, binHeader + 8 + binLen))

        val byteLength = int(g.list("buffers")[0], "byteLength")
        assertTrue(byteLength <= binLen)
        val views = g.list("bufferViews")
        for (v in views) {
            val off = int(v, "byteOffset"); val len = int(v, "byteLength")
            assertTrue(off >= 0 && off + len <= byteLength)
            assertEquals(0, off % 4)
        }
        val accessors = g.list("accessors")
        for (a in accessors) {
            val view = views[int(a, "bufferView")]
            val compSize = when (int(a, "componentType")) { 5126, 5125 -> 4; 5123 -> 2; else -> 1 }
            val aOff = (a["byteOffset"] as Double?)?.toInt() ?: 0
            assertEquals(0, aOff % compSize)
            assertTrue(aOff + int(a, "count") * comps(a["type"] as String) * compSize <= int(view, "byteLength"))
        }
        // Extensions used are declared.
        val used = (json["extensionsUsed"] as List<String>?).orEmpty()
        for (m in g.list("materials")) {
            val ext = m["extensions"] as Map<String, Any?>?
            ext?.keys?.forEach { assertTrue("extension $it not declared", it in used) }
        }
        // POSITION bounds match the data; indices in range.
        for (p in g.primitives) {
            val attrs = p["attributes"] as Map<String, Any?>
            val posIdx = (attrs["POSITION"] as Double).toInt()
            val pos = g.floats(posIdx)
            val a = accessors[posIdx]
            val min = a["min"] as List<Double>; val max = a["max"] as List<Double>
            for (k in 0 until 3) {
                assertEquals(min[k].toFloat(), (k until pos.size step 3).minOf { pos[it] }, 0f)
                assertEquals(max[k].toFloat(), (k until pos.size step 3).maxOf { pos[it] }, 0f)
            }
            val idx = g.ints((p["indices"] as Double).toInt())
            val count = int(a, "count")
            for (i in idx) assertTrue(i in 0 until count)
            assertEquals(0, idx.size % 3)
        }
        return g
    }

    /** Every triangle of [prim] winds counter-clockwise against its first vertex's normal. */
    fun assertOutwardWinding(g: Glb, prim: Map<String, Any?>) {
        val attrs = prim["attributes"] as Map<String, Any?>
        val pos = g.floats((attrs["POSITION"] as Double).toInt())
        val nrm = g.floats((attrs["NORMAL"] as Double).toInt())
        val idx = g.ints((prim["indices"] as Double).toInt())
        fun p(i: Int) = Vec3(pos[3 * i].toDouble(), pos[3 * i + 1].toDouble(), pos[3 * i + 2].toDouble())
        for (t in 0 until idx.size / 3) {
            val a = idx[3 * t]; val b = idx[3 * t + 1]; val c = idx[3 * t + 2]
            val geo = (p(b) - p(a)) cross (p(c) - p(a))
            val n = Vec3(nrm[3 * a].toDouble(), nrm[3 * a + 1].toDouble(), nrm[3 * a + 2].toDouble())
            assertTrue("triangle $t winding", (geo dot n) > 0)
        }
    }

    fun extent(pos: FloatArray, axis: Int): Double =
        (axis until pos.size step 3).maxOf { pos[it] }.toDouble() - (axis until pos.size step 3).minOf { pos[it] }.toDouble()
}
