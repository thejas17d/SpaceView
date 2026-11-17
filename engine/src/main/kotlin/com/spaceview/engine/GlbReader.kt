package com.spaceview.engine

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads what a GLB says about itself ([GlbInfo]: node `extras`) from the header and the JSON chunk only, so a
 * placement check costs ~3 KB of I/O and never decodes the image (K4, §3.3). Anything that is not a well-formed
 * GLB 2.0 with numeric `widthM/heightM/depthM` extras gives null; it never throws on bad input.
 */
object GlbReader {
    /** Largest JSON chunk accepted (our models write 2–8 KB). */
    const val MAX_JSON_BYTES = 1 shl 20

    /** Reads from the start of [input] (which it does not close). Null if unreadable or not ours. */
    fun readInfo(input: InputStream): GlbInfo? = try {
        val data = DataInputStream(input)
        val header = ByteArray(20)
        data.readFully(header)
        val jsonLen = jsonLengthOrNull(header)
        if (jsonLen == null) {
            null
        } else {
            val json = ByteArray(jsonLen)
            data.readFully(json)
            parse(json)
        }
    } catch (e: EOFException) {
        null
    } catch (e: IOException) {
        null
    }

    /** Same as [readInfo] for a whole file already in memory. */
    fun readInfo(glb: ByteArray): GlbInfo? {
        if (glb.size < 20) return null
        val jsonLen = jsonLengthOrNull(glb) ?: return null
        if (20 + jsonLen > glb.size) return null
        return parse(glb.copyOfRange(20, 20 + jsonLen))
    }

    private fun jsonLengthOrNull(h: ByteArray): Int? {
        if (u32(h, 0) != 0x46546C67 || u32(h, 4) != 2) return null
        val len = u32(h, 12)
        if (u32(h, 16) != 0x4E4F534A || len <= 0 || len > MAX_JSON_BYTES) return null
        return len
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: ByteArray): GlbInfo? = runCatching {
        val root = JsonLite.parse(String(json, Charsets.UTF_8).trimEnd(' ', '\u0000')) as? Map<String, Any?> ?: return null
        val nodes = root["nodes"] as? List<Any?> ?: return null
        val scenes = root["scenes"] as? List<Any?>
        val sceneIndex = (root["scene"] as? Double)?.toInt() ?: 0
        val rootNode = ((scenes?.getOrNull(sceneIndex) as? Map<String, Any?>)?.get("nodes") as? List<Any?>)
            ?.firstOrNull()?.let { (it as? Double)?.toInt() } ?: 0
        val node = nodes.getOrNull(rootNode) as? Map<String, Any?> ?: return null
        val extras = node["extras"] as? Map<String, Any?> ?: return null
        val w = extras["widthM"] as? Double ?: return null
        val h = extras["heightM"] as? Double ?: return null
        val d = extras["depthM"] as? Double ?: return null
        if (!(w.isFinite() && h.isFinite() && d.isFinite())) return null
        GlbInfo(
            widthM = w,
            heightM = h,
            depthM = d,
            origin = (extras["origin"] as? String)?.let { o -> BoxOrigin.entries.firstOrNull { it.name == o } },
            glbVersion = (extras["glbVersion"] as? Double)?.toInt() ?: 1,
            modelKind = (extras["modelKind"] as? String)?.let { k -> ModelKind.entries.firstOrNull { it.name == k } },
            centre = centreOf(extras),
        )
    }.getOrNull()

    /** The declared bounding-box centre (N3): all three keys present and finite, else null. */
    private fun centreOf(extras: Map<String, Any?>): Vec3? {
        val x = extras[GlbWriter.AABB_CX] as? Double ?: return null
        val y = extras[GlbWriter.AABB_CY] as? Double ?: return null
        val z = extras[GlbWriter.AABB_CZ] as? Double ?: return null
        return if (x.isFinite() && y.isFinite() && z.isFinite()) Vec3(x, y, z) else null
    }
}

/**
 * Small strict JSON parser (objects → Map, arrays → List, numbers → Double, strings, booleans, null) with a
 * nesting limit. Throws [IllegalArgumentException] (or an index exception) on malformed input; callers catch.
 */
internal object JsonLite {
    private const val MAX_DEPTH = 64
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value(0)
        p.ws()
        require(p.i == s.length) { "Trailing characters at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value(depth: Int): Any? {
            require(depth < MAX_DEPTH && i < s.length) { "Too deep or unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Bad char at $i")
            }
        }

        fun lit(t: String, v: Any?): Any? {
            require(s.startsWith(t, i)) { "Bad literal at $i" }
            i += t.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                require(s[i] == '"') { "Expected key at $i" }
                val k = str()
                ws(); require(s[i] == ':') { "Expected ':' at $i" }; i++; ws()
                m[k] = value(depth + 1)
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value(depth + 1)); ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException("Bad escape")
                    }
                    c < ' ' -> throw IllegalArgumentException("Control char in string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val m = NUMBER.matchAt(s, i) ?: throw IllegalArgumentException("Bad number at $i")
            i += m.value.length
            return m.value.toDouble()
        }
    }
}
