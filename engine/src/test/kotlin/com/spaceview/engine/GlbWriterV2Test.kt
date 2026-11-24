package com.spaceview.engine

import com.spaceview.engine.GlbTestKit.extent
import com.spaceview.engine.GlbTestKit.int
import com.spaceview.engine.GlbTestKit.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Phase 0 (K8, K9, ADR-003): unlit photo face, glbVersion, GlbReader (incl. legacy template / mesh GLBs). */
@Suppress("UNCHECKED_CAST")
class GlbWriterV2Test {

    private val fakeJpeg = ByteArray(2000) { i -> if (i == 0) 0xFF.toByte() else if (i == 1) 0xD8.toByte() else (i * 7).toByte() }

    // ---- writeBox (ADR-003) ----------------------------------------------------------------------------

    @Test fun texturedBoxFrontIsUnlitAndSidesLit() {
        val g = parse(GlbWriter.writeBox(0.6, 0.4, 0.03, fakeJpeg, origin = BoxOrigin.BACK_CENTER))
        assertEquals(listOf("KHR_materials_unlit"), g.json["extensionsUsed"])
        val mats = g.list("materials")
        assertTrue((mats[0]["extensions"] as Map<String, Any?>).containsKey("KHR_materials_unlit"))
        assertNull("sides stay lit", mats[1]["extensions"])
        assertEquals(0, int((mats[0]["pbrMetallicRoughness"] as Map<String, Any?>)["baseColorTexture"] as Map<String, Any?>, "index"))
        val e = g.node0Extras
        assertEquals(2.0, e["glbVersion"])
        assertEquals("PROXY_BOX", e["modelKind"])
        assertEquals("BACK_CENTER", e["origin"])
        assertEquals(0.6, e["widthM"] as Double, 0.0)
        assertEquals(0.4, e["heightM"] as Double, 0.0)
        assertEquals(0.03, e["depthM"] as Double, 0.0)
        g.primitives.forEach { GlbTestKit.assertOutwardWinding(g, it) }
    }

    @Test fun untexturedBoxHasNoExtension() {
        val g = parse(GlbWriter.writeBox(0.45, 0.26, 0.35))
        assertNull(g.json["extensionsUsed"])
        assertTrue(g.list("materials").all { it["extensions"] == null })
        assertEquals(2.0, g.node0Extras["glbVersion"])
    }

    @Test fun boxDimensionsUnchangedByTheNewContract() {
        val g = parse(GlbWriter.writeBox(1.234, 0.567, 0.089, origin = BoxOrigin.BOTTOM_CENTER))
        val pos = g.floats(0)
        assertEquals(1.234, extent(pos, 0), 1e-6)
        assertEquals(0.567, extent(pos, 1), 1e-6)
        assertEquals(0.089, extent(pos, 2), 1e-6)
        assertEquals(0f, (1 until pos.size step 3).minOf { pos[it] }, 0f)
    }

    @Test fun boxOriginCentreOffsetIsTheSingleRule() {
        assertTrue(BoxOrigin.CENTER.centreOffset(1.0, 2.0, 3.0).approxEquals(Vec3.ZERO))
        assertTrue(BoxOrigin.BOTTOM_CENTER.centreOffset(1.0, 2.0, 3.0).approxEquals(Vec3(0.0, 1.0, 0.0)))
        assertTrue(BoxOrigin.BACK_CENTER.centreOffset(1.0, 2.0, 3.0).approxEquals(Vec3(0.0, 0.0, 1.5)))
    }

    // ---- GlbReader ---------------------------------------------------------------------------------------

    @Test fun readerRoundTripsEveryKind() {
        val box = GlbReader.readInfo(GlbWriter.writeBox(0.6, 0.4, 0.03, fakeJpeg, origin = BoxOrigin.BACK_CENTER))!!
        assertEquals(0.6, box.widthM, 0.0); assertEquals(0.4, box.heightM, 0.0); assertEquals(0.03, box.depthM, 0.0)
        assertEquals(BoxOrigin.BACK_CENTER, box.origin)
        assertEquals(GLB_VERSION, box.glbVersion)
        assertEquals(ModelKind.PROXY_BOX, box.modelKind)

        val cutoutGlb = GlbWriter.writeCutout(0.8, 1.1, fakeJpeg)
        val stream = ByteArrayInputStream(cutoutGlb)
        val cutout = GlbReader.readInfo(stream)!!
        assertEquals(ModelKind.CUTOUT, cutout.modelKind)
        assertEquals(BoxOrigin.CENTER, cutout.origin)
        assertEquals(0.8, cutout.widthM, 0.0); assertEquals(1.1, cutout.heightM, 0.0); assertEquals(0.0, cutout.depthM, 0.0)
        // Only the header and the JSON chunk were read: the BIN chunk is untouched.
        val jsonLen = GlbTestKit.u32(cutoutGlb, 12)
        assertEquals(cutoutGlb.size - 20 - jsonLen, stream.available())
    }

    /** PLAN v3 W1: template and mesh GLBs saved by earlier test builds still read (kind, origin, declared centre). */
    @Test fun readerLegacyTemplateAndMeshModels() {
        val template = GlbReader.readInfo(GlbWriter.writeBox(2.0, 0.85, 0.9, modelKind = ModelKind.TEMPLATE))!!
        assertEquals(ModelKind.TEMPLATE, template.modelKind)
        assertNull(template.centre)
        val mesh = GlbReader.readInfo(rawGlb("{\"nodes\":[{\"extras\":{\"widthM\":0.4,\"heightM\":0.39,\"depthM\":0.4,\"origin\":\"BOTTOM_CENTER\",\"glbVersion\":2,\"modelKind\":\"MESH_DEVICE\",\"aabbCx\":0.01,\"aabbCy\":0.205,\"aabbCz\":-0.02}}]}"))!!
        assertEquals(ModelKind.MESH_DEVICE, mesh.modelKind)
        assertTrue(mesh.centre!!.approxEquals(Vec3(0.01, 0.205, -0.02)))
        assertNull("one key missing: no centre", GlbReader.readInfo(rawGlb("{\"nodes\":[{\"extras\":{\"widthM\":1,\"heightM\":1,\"depthM\":1,\"aabbCx\":0,\"aabbCy\":0}}]}"))!!.centre)
    }

    @Test fun readerLegacyM3Model() {
        val glb = rawGlb("{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"extras\":{\"widthM\":0.5,\"heightM\":0.4,\"depthM\":0.03,\"origin\":\"BACK_CENTER\"}}]}")
        val info = GlbReader.readInfo(glb)!!
        assertEquals(1, info.glbVersion)
        assertNull(info.modelKind)
        assertEquals(BoxOrigin.BACK_CENTER, info.origin)
        assertNotNull(GlbReader.readInfo(ByteArrayInputStream(glb)))
    }

    @Test fun readerRejectsGarbage() {
        assertNull(GlbReader.readInfo(ByteArray(0)))
        assertNull(GlbReader.readInfo(ByteArray(64) { 7 }))
        assertNull(GlbReader.readInfo(ByteArrayInputStream(ByteArray(10))))
        val ok = GlbWriter.writeBox(0.1, 0.1, 0.1)
        assertNull("truncated JSON", GlbReader.readInfo(ok.copyOf(40)))
        assertNull("truncated stream", GlbReader.readInfo(ByteArrayInputStream(ok.copyOf(40))))
        assertNull("no extras", GlbReader.readInfo(rawGlb("{\"nodes\":[{\"mesh\":0}]}")))
        assertNull("non-numeric", GlbReader.readInfo(rawGlb("{\"nodes\":[{\"extras\":{\"widthM\":\"a\",\"heightM\":1,\"depthM\":1}}]}")))
        assertNull("bad json", GlbReader.readInfo(rawGlb("{\"nodes\":[")))
        assertNull("too deep", GlbReader.readInfo(rawGlb("[".repeat(200) + "]".repeat(200))))
        val unknown = GlbReader.readInfo(rawGlb("{\"nodes\":[{\"extras\":{\"widthM\":1,\"heightM\":1,\"depthM\":1,\"origin\":\"X\",\"modelKind\":\"Y\",\"glbVersion\":7}}]}"))!!
        assertNull(unknown.origin); assertNull(unknown.modelKind); assertEquals(7, unknown.glbVersion)
        assertFalse(ModelKind.MESH_DEVICE.regenerable)
        assertFalse("no template library in v1 (PLAN v3)", ModelKind.TEMPLATE.regenerable)
        assertTrue(ModelKind.PROXY_BOX.regenerable)
        assertTrue(ModelKind.CUTOUT.regenerable)
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** A GLB with only a JSON chunk (padded with spaces) and an empty BIN chunk. */
    private fun rawGlb(json: String): ByteArray {
        val j = json.toByteArray(Charsets.UTF_8)
        val jl = (j.size + 3) and 3.inv()
        val total = 12 + 8 + jl + 8
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
        u32(0x46546C67); u32(2); u32(total); u32(jl); u32(0x4E4F534A)
        out.write(j); repeat(jl - j.size) { out.write(0x20) }
        u32(0); u32(0x004E4942)
        return out.toByteArray()
    }
}
