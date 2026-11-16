package com.spaceview.engine

import com.spaceview.engine.GlbTestKit.extent
import com.spaceview.engine.GlbTestKit.int
import com.spaceview.engine.GlbTestKit.parse
import com.spaceview.engine.recon.TriMesh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Phase 0 (K8, K9, ADR-003, ADR-014): unlit photo face, glbVersion, writeParts, writeMesh, GlbReader. */
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

    // ---- writeParts (K9) -------------------------------------------------------------------------------

    /** A 2.0 × 0.85 × 0.9 m sofa built like a template: union box exactly W×H×D, floor at y = 0. */
    private fun sofa(): List<Part> = listOf(
        Part("base", Vec3(0.0, 0.25, 0.0), Vec3(1.7, 0.3, 0.9), material = PartMaterial.BODY),
        Part("seat", Vec3(0.0, 0.45, 0.05), Vec3(1.7, 0.1, 0.8), material = PartMaterial.SOFT),
        Part("back", Vec3(0.0, 0.575, -0.35), Vec3(1.7, 0.55, 0.2), material = PartMaterial.SOFT),
        Part("armL", Vec3(-0.925, 0.45, 0.0), Vec3(0.15, 0.7, 0.9), material = PartMaterial.BODY),
        Part("armR", Vec3(0.925, 0.45, 0.0), Vec3(0.15, 0.7, 0.9), material = PartMaterial.BODY),
        Part("leg1", Vec3(-0.9, 0.05, 0.4), Vec3(0.05, 0.1, 0.05), material = PartMaterial.LEG),
        Part("leg2", Vec3(0.9, 0.05, 0.4), Vec3(0.05, 0.1, 0.05), material = PartMaterial.LEG),
        Part("leg3", Vec3(-0.9, 0.05, -0.4), Vec3(0.05, 0.1, 0.05), material = PartMaterial.LEG),
        Part("leg4", Vec3(0.9, 0.05, -0.4), Vec3(0.05, 0.1, 0.05), material = PartMaterial.LEG),
    )

    @Test fun partsUnionIsTheTrueSizeAndExtrasMatch() {
        val g = parse(GlbWriter.writeParts(sofa(), name = "Sofa"))
        val pos = g.floats(0)
        assertEquals(2.0, extent(pos, 0), 1e-6)
        assertEquals(0.85, extent(pos, 1), 1e-6)
        assertEquals(0.9, extent(pos, 2), 1e-6)
        val e = g.node0Extras
        assertEquals(2.0, e["widthM"] as Double, 1e-9)
        assertEquals(0.85, e["heightM"] as Double, 1e-9)
        assertEquals(0.9, e["depthM"] as Double, 1e-9)
        assertEquals("TEMPLATE", e["modelKind"])
        assertEquals("BOTTOM_CENTER", e["origin"])
        assertEquals(2.0, e["glbVersion"])
        // BOTTOM_CENTER: floor at 0, centred on X and Z.
        assertEquals(0f, (1 until pos.size step 3).minOf { pos[it] }, 1e-7f)
        assertEquals(0.0, (0 until pos.size step 3).minOf { pos[it] } + (0 until pos.size step 3).maxOf { pos[it] }.toDouble(), 1e-6)
        assertEquals(0.0, (2 until pos.size step 3).minOf { pos[it] } + (2 until pos.size step 3).maxOf { pos[it] }.toDouble(), 1e-6)
        assertEquals(9 * 24, int(g.accessor(0), "count"))
    }

    @Test fun onePrimitivePerMaterialInEnumOrder() {
        val g = parse(GlbWriter.writeParts(sofa()))
        assertEquals(3, g.primitives.size)
        assertEquals(listOf("body", "soft", "leg"), g.list("materials").map { it["name"] })
        assertEquals(listOf(0, 1, 2), g.primitives.map { int(it, "material") })
        // BODY has 3 parts, SOFT 2, LEG 4: 36 indices each.
        assertEquals(listOf(3 * 36, 2 * 36, 4 * 36), g.primitives.map { g.ints(int(it, "indices")).size })
        g.primitives.forEach { GlbTestKit.assertOutwardWinding(g, it) }
        assertNull("templates are lit", g.json["extensionsUsed"])
    }

    @Test fun paletteColoursAreLinearised() {
        val g = parse(GlbWriter.writeParts(listOf(Part("a", Vec3.ZERO, Vec3(1.0, 1.0, 1.0))), Palette(mapOf(PartMaterial.BODY to 0xFF0000))))
        val c = (g.list("materials")[0]["pbrMetallicRoughness"] as Map<String, Any?>)["baseColorFactor"] as List<Double>
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), c)
        assertEquals(0x7A5C43, Palette.DEFAULT.colorOf(PartMaterial.FRAME))
        assertEquals(0x123456, Palette(mapOf(PartMaterial.LEG to 0x123456)).colorOf(PartMaterial.LEG))
        assertEquals(Palette.DEFAULT.colorOf(PartMaterial.BODY), Palette(mapOf(PartMaterial.LEG to 1)).colorOf(PartMaterial.BODY))
    }

    @Test fun yawedPartSwapsItsFootprint() {
        val g = parse(GlbWriter.writeParts(listOf(Part("a", Vec3.ZERO, Vec3(2.0, 0.5, 0.4), yawRad = PI / 2)), origin = BoxOrigin.CENTER))
        val pos = g.floats(0)
        assertEquals(0.4, extent(pos, 0), 1e-6)
        assertEquals(2.0, extent(pos, 2), 1e-6)
        assertEquals(0.4, g.node0Extras["widthM"] as Double, 1e-9)
        g.primitives.forEach { GlbTestKit.assertOutwardWinding(g, it) }
        // 45°: the footprint grows to (w + d)/√2 on both axes.
        val g45 = parse(GlbWriter.writeParts(listOf(Part("a", Vec3.ZERO, Vec3(1.0, 1.0, 1.0), yawRad = PI / 4))))
        assertEquals(Math.sqrt(2.0), g45.node0Extras["widthM"] as Double, 1e-9)
    }

    @Test fun partsBackCentreOrigin() {
        val g = parse(GlbWriter.writeParts(listOf(Part("panel", Vec3(5.0, 5.0, 5.0), Vec3(1.0, 0.5, 0.1))), origin = BoxOrigin.BACK_CENTER))
        val pos = g.floats(0)
        assertEquals(0f, (2 until pos.size step 3).minOf { pos[it] }, 0f)
        assertEquals(0.1f, (2 until pos.size step 3).maxOf { pos[it] }, 1e-7f)
        assertEquals(0.25f, (1 until pos.size step 3).maxOf { pos[it] }, 1e-7f)
    }

    @Test fun partsAreDeterministic() {
        assertArrayEquals(GlbWriter.writeParts(sofa()), GlbWriter.writeParts(sofa()))
    }

    @Test fun partsValidation() {
        fun rejects(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(rejects { GlbWriter.writeParts(emptyList()) })
        assertTrue(rejects { GlbWriter.writeParts(listOf(Part("a", Vec3.ZERO, Vec3(0.0, 1.0, 1.0)))) })
        assertTrue(rejects { GlbWriter.writeParts(listOf(Part("a", Vec3(Double.NaN, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)))) })
        assertTrue(rejects { GlbWriter.writeParts(listOf(Part("a", Vec3.ZERO, Vec3(1.0, 1.0, 1.0), yawRad = Double.POSITIVE_INFINITY))) })
        assertTrue(rejects { GlbWriter.writeParts(listOf(Part("a", Vec3(-30.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)), Part("b", Vec3(30.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)))) })
        assertTrue(rejects { GlbWriter.writeParts(List(GlbWriter.MAX_PARTS + 1) { Part("p", Vec3.ZERO, Vec3(1.0, 1.0, 1.0)) }) })
    }

    // ---- writeMesh (K9, ADR-014) -----------------------------------------------------------------------

    @Test fun sphereMeshWithColours() {
        val r = 0.25
        val mesh = sphere(r, Vec3(3.0, 1.0, -2.0), 24, 48, rgba = false)
        val g = parse(GlbWriter.writeMesh(mesh, BoxOrigin.BOTTOM_CENTER, unlit = true, MeshExtras(values = mapOf("voxelM" to 0.01))))
        val prim = g.primitives.single()
        val attrs = prim["attributes"] as Map<String, Any?>
        // Colours: RGBA8 normalised, alpha 255 for RGB input.
        val col = g.accessor((attrs["COLOR_0"] as Double).toInt())
        assertEquals(5121, int(col, "componentType"))
        assertEquals(true, col["normalized"])
        assertEquals("VEC4", col["type"])
        val bytes = g.bytes((attrs["COLOR_0"] as Double).toInt())
        assertEquals(mesh.colours[0], bytes[0]); assertEquals(mesh.colours[1], bytes[1]); assertEquals(mesh.colours[2], bytes[2])
        assertEquals(0xFF.toByte(), bytes[3])
        // u16 indices below 65,536 vertices.
        assertEquals(5123, int(g.accessor(int(prim, "indices")), "componentType"))
        // Unlit, double-sided, white × vertex colour.
        val mat = g.list("materials")[0]
        assertEquals(true, mat["doubleSided"])
        assertTrue((mat["extensions"] as Map<String, Any?>).containsKey("KHR_materials_unlit"))
        // Recentred on its own bounding box; extras = that box; never scaled.
        val pos = g.floats(0)
        val e = g.node0Extras
        for ((k, key) in listOf("widthM", "heightM", "depthM").withIndex()) {
            assertEquals(extent(pos, k), e[key] as Double, 1e-6)
            assertEquals(2 * r, e[key] as Double, 0.01)
        }
        assertEquals(0f, (1 until pos.size step 3).minOf { pos[it] }, 1e-6f)
        assertEquals("MESH_DEVICE", e["modelKind"])
        assertEquals(0.01, e["voxelM"] as Double, 0.0)
        // Normals: unit length and outward (centre now at (0, r, 0)).
        val nrm = g.floats(1)
        for (v in mesh.indices.toSet()) {
            val n = Vec3(nrm[3 * v].toDouble(), nrm[3 * v + 1].toDouble(), nrm[3 * v + 2].toDouble())
            assertEquals(1.0, n.length, 1e-5)
            val out = Vec3(pos[3 * v].toDouble(), pos[3 * v + 1] - r, pos[3 * v + 2].toDouble())
            assertTrue((n dot out) > 0)
        }
    }

    @Test fun meshWithoutColoursIsGreyAndLitWhenAsked() {
        val m = sphere(0.1, Vec3.ZERO, 8, 16, rgba = false).let { TriMesh(it.positions, ByteArray(0), it.indices) }
        val g = parse(GlbWriter.writeMesh(m, unlit = false))
        assertNull((g.primitives[0]["attributes"] as Map<String, Any?>)["COLOR_0"])
        assertNull(g.json["extensionsUsed"])
        val c = (g.list("materials")[0]["pbrMetallicRoughness"] as Map<String, Any?>)["baseColorFactor"] as List<Double>
        assertTrue(c[0] < 1.0)
    }

    @Test fun rgbaColoursPassThrough() {
        val m = sphere(0.1, Vec3.ZERO, 8, 16, rgba = true)
        val g = parse(GlbWriter.writeMesh(m))
        val bytes = g.bytes(2)
        assertArrayEquals(m.colours, bytes)
    }

    @Test fun largeMeshUsesU32Indices() {
        // A 300 × 250 vertex height field (75,000 vertices) on top of a thin slab.
        val nx = 300; val nz = 250
        val pos = FloatArray(nx * nz * 3)
        for (i in 0 until nx) for (k in 0 until nz) {
            val v = i * nz + k
            pos[3 * v] = i * 0.002f; pos[3 * v + 1] = ((i + k) % 7) * 0.001f; pos[3 * v + 2] = k * 0.002f
        }
        val idx = ArrayList<Int>()
        for (i in 0 until nx - 1) for (k in 0 until nz - 1) {
            val a = i * nz + k; val b = a + 1; val c = a + nz; val d = c + 1
            idx += listOf(a, b, d, a, d, c)
        }
        val g = parse(GlbWriter.writeMesh(TriMesh(pos, ByteArray(0), idx.toIntArray())))
        assertEquals(5125, int(g.accessor(int(g.primitives[0], "indices")), "componentType"))
        assertEquals(nx * nz - 1, ((g.accessor(int(g.primitives[0], "indices"))["max"]) as List<Double>)[0].toInt())
    }

    @Test fun sofaSizeMeshFitsTheItemBudget() {
        // ~15k triangles (the §7.1.7 aim) with colours stays under the 0.5 MB per-item budget.
        val m = sphere(1.0, Vec3.ZERO, 87, 87, rgba = false)
        assertTrue(m.triangleCount in 14_000..16_000)
        val glb = GlbWriter.writeMesh(m)
        assertTrue("${glb.size} B", glb.size < 400_000)
    }

    @Test fun meshIsDeterministic() {
        val m = sphere(0.3, Vec3(1.0, 2.0, 3.0), 12, 24, rgba = false)
        assertArrayEquals(GlbWriter.writeMesh(m), GlbWriter.writeMesh(m))
    }

    @Test fun meshValidation() {
        val good = sphere(0.1, Vec3.ZERO, 6, 8, rgba = false)
        fun rejects(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(good.positions, ByteArray(5), good.indices)) })
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(good.positions, good.colours, intArrayOf(0, 1, good.vertexCount))) })
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(good.positions, good.colours, intArrayOf(0, 1))) })
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f), ByteArray(0), intArrayOf(0, 0, 0))) })
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(good.positions.copyOf().also { it[4] = Float.NaN }, good.colours, good.indices)) })
        // Flat: every vertex on y = 0.
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), ByteArray(0), intArrayOf(0, 1, 2))) })
        assertTrue(rejects { GlbWriter.writeMesh(good, extras = MeshExtras(values = mapOf("widthM" to 1.0))) })
        assertTrue(rejects { GlbWriter.writeMesh(good, extras = MeshExtras(values = mapOf("bad key" to 1.0))) })
        assertTrue(rejects { GlbWriter.writeMesh(good, extras = MeshExtras(values = mapOf("ok" to Double.NaN))) })
        assertTrue(rejects { GlbWriter.writeMesh(TriMesh(floatArrayOf(0f, 0f, 0f, 60f, 1f, 1f, 0f, 1f, 0f), ByteArray(0), intArrayOf(0, 1, 2))) })
    }

    @Test fun cloudMeshKindAndUnreferencedVertices() {
        // A degenerate (zero-area) triangle gives its vertices the fallback +Y normal.
        val m = TriMesh(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0.5f, 0.5f, 0.5f),
            ByteArray(0),
            intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 4, 4, 4),
        )
        val g = parse(GlbWriter.writeMesh(m, origin = BoxOrigin.CENTER, extras = MeshExtras(ModelKind.MESH_CLOUD, "Cloud")))
        assertEquals("MESH_CLOUD", g.node0Extras["modelKind"])
        assertEquals("Cloud", g.list("nodes")[0]["name"])
        val nrm = g.floats(1)
        assertEquals(1f, nrm[3 * 4 + 1], 0f)
    }

    @Test fun keepInputOriginWritesPositionsUnchanged() {
        val m = sphere(0.2, Vec3(0.05, 0.21, -0.03), 8, 16, rgba = false)
        val g = parse(GlbWriter.writeMesh(m, keepInputOrigin = true))
        val pos = g.floats(0)
        for (i in pos.indices) assertEquals(m.positions[i], pos[i], 0f)
        // Extras still describe the mesh's own bounding box.
        assertEquals(extent(pos, 1), g.node0Extras["heightM"] as Double, 1e-6)
        assertEquals("BOTTOM_CENTER", g.node0Extras["origin"])
        // A mesh far from its frame origin is a bug upstream (it would be placed metres away).
        val far = sphere(0.2, Vec3(3.0, 0.2, 0.0), 8, 16, rgba = false)
        try {
            GlbWriter.writeMesh(far, keepInputOrigin = true)
            throw AssertionError("accepted a mesh 3 m from its origin")
        } catch (expected: IllegalArgumentException) {
        }
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

        val parts = GlbReader.readInfo(GlbWriter.writeParts(sofa()))!!
        assertEquals(ModelKind.TEMPLATE, parts.modelKind)
        assertEquals(2.0, parts.widthM, 1e-9)

        val meshGlb = GlbWriter.writeMesh(sphere(0.2, Vec3.ZERO, 8, 16, rgba = false))
        val stream = ByteArrayInputStream(meshGlb)
        val mesh = GlbReader.readInfo(stream)!!
        assertEquals(ModelKind.MESH_DEVICE, mesh.modelKind)
        assertEquals(BoxOrigin.BOTTOM_CENTER, mesh.origin)
        // Only the header and the JSON chunk were read: the BIN chunk is untouched.
        val jsonLen = GlbTestKit.u32(meshGlb, 12)
        assertEquals(meshGlb.size - 20 - jsonLen, stream.available())
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
        assertTrue(ModelKind.TEMPLATE.regenerable)
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

    /** UV sphere of radius [r] around [c], outward-wound, with per-vertex colours. */
    private fun sphere(r: Double, c: Vec3, stacks: Int, slices: Int, rgba: Boolean): TriMesh {
        val pos = ArrayList<Float>()
        val col = ArrayList<Byte>()
        for (i in 0..stacks) {
            val th = PI * i / stacks
            for (j in 0..slices) {
                val ph = 2 * PI * j / slices
                pos += (c.x + r * sin(th) * cos(ph)).toFloat(); pos += (c.y + r * cos(th)).toFloat(); pos += (c.z + r * sin(th) * sin(ph)).toFloat()
                col += (i * 9).toByte(); col += (j * 5).toByte(); col += 77.toByte()
                if (rgba) col += (200 - i).toByte()
            }
        }
        val p = pos.toFloatArray()
        fun v(i: Int) = Vec3(p[3 * i].toDouble(), p[3 * i + 1].toDouble(), p[3 * i + 2].toDouble())
        val idx = ArrayList<Int>()
        val w = slices + 1
        for (i in 0 until stacks) for (j in 0 until slices) {
            val a = i * w + j; val b = (i + 1) * w + j; val cc = (i + 1) * w + j + 1; val d = i * w + j + 1
            for (t in listOf(intArrayOf(a, b, cc), intArrayOf(a, cc, d))) {
                val geo = (v(t[1]) - v(t[0])) cross (v(t[2]) - v(t[0]))
                if (geo.length < 1e-12) continue // pole triangles collapse
                val centroid = (v(t[0]) + v(t[1]) + v(t[2])) / 3.0
                if ((geo dot (centroid - c)) > 0) idx += t.toList() else idx += listOf(t[0], t[2], t[1])
            }
        }
        return TriMesh(p, col.toByteArray(), idx.toIntArray())
    }
}
