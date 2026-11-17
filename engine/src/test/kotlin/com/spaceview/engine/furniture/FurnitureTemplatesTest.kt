package com.spaceview.engine.furniture

import com.spaceview.engine.BoxOrigin
import com.spaceview.engine.GlbReader
import com.spaceview.engine.GlbTestKit
import com.spaceview.engine.GlbWriter
import com.spaceview.engine.ModelKind
import com.spaceview.engine.Part
import com.spaceview.engine.PartMaterial
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * ADR-010 / gate §8 T4: every template's parts union to exactly W × H × D over its whole parameter space (property
 * test, fixed seed), parameters are validated at their boundaries, and the part count stays under MAX_PARTS.
 */
class FurnitureTemplatesTest {

    /** A random valid parameter map: each parameter drawn inside its range given the earlier ones. */
    private fun sample(t: FurnitureTemplate, rnd: Random): Params {
        val p = LinkedHashMap<String, Double>()
        for (spec in t.params) {
            if (!spec.appliesWhen(p)) continue
            val r = spec.range(p)
            assertTrue("${t.id}.${spec.id}: empty range $r for $p", r.start <= r.endInclusive + 1e-12)
            p[spec.id] = when (spec.kind) {
                ParamKind.LENGTH -> when (rnd.nextInt(10)) {
                    0 -> r.start // the boundaries are part of the space
                    1 -> r.endInclusive
                    else -> r.start + rnd.nextDouble() * (r.endInclusive - r.start)
                }
                else -> spec.options(p).random(rnd).toDouble()
            }
        }
        return p
    }

    private fun assertUnion(t: FurnitureTemplate, p: Params, parts: List<Part>) {
        val box = FurnitureTemplate.unionBox(parts)
        assertEquals("${t.id} W for $p", p.getValue(W), box.x, 1e-9)
        assertEquals("${t.id} H for $p", p.getValue(H), box.y, 1e-9)
        assertEquals("${t.id} D for $p", p.getValue(D), box.z, 1e-9)
        // Nothing below the floor or outside the footprint (union = box and all parts inside it).
        for (part in parts) {
            assertTrue(part.size.x > 0 && part.size.y > 0 && part.size.z > 0)
            assertEquals(0.0, part.yawRad, 0.0)
            assertTrue("${t.id} ${part.name} below the floor", part.center.y - part.size.y / 2 >= -1e-12)
            assertTrue(abs(part.center.x) + part.size.x / 2 <= p.getValue(W) / 2 + 1e-12)
            assertTrue(abs(part.center.z) + part.size.z / 2 <= p.getValue(D) / 2 + 1e-12)
        }
        assertTrue("${t.id}: ${parts.size} parts", parts.size in 1..MAX_TEMPLATE_PARTS)
    }

    @Test
    fun `every template unions to W x H x D over random valid parameters`() {
        val rnd = Random(20261116)
        for (t in Templates.all) {
            repeat(SAMPLES) {
                val p = sample(t, rnd)
                assertEquals("${t.id} sample must validate: $p", emptyList<ParamError>(), t.validate(p))
                assertUnion(t, p, t.build(p))
            }
        }
    }

    @Test
    fun `the GLB of every sample has the item's size to 0_1 mm`() {
        val rnd = Random(7)
        for (t in Templates.all) {
            repeat(40) {
                val p = sample(t, rnd)
                val glb = GlbWriter.writeParts(t.build(p), FurniturePalettes.of(rnd.nextInt(FurniturePalettes.count)))
                val info = GlbReader.readInfo(glb)!!
                assertEquals(p.getValue(W), info.widthM, 1e-4)
                assertEquals(p.getValue(H), info.heightM, 1e-4)
                assertEquals(p.getValue(D), info.depthM, 1e-4)
                assertEquals(ModelKind.TEMPLATE, info.modelKind)
                assertEquals(BoxOrigin.BOTTOM_CENTER, info.origin)
            }
        }
    }

    @Test
    fun `defaults and presets are valid at the corners of W, D and H`() {
        for (t in Templates.all) {
            val (w, d, h) = t.params.take(3)
            for (wv in listOf(w.min, w.max)) for (dv in listOf(d.min, d.max)) for (hv in listOf(h.min, h.max)) {
                val base = mapOf(W to wv, D to dv, H to hv)
                // Every option combination at this corner, with the other lengths at their defaults.
                val combos = optionCombos(t, base)
                assertTrue(combos.isNotEmpty())
                for (combo in combos) {
                    val p = t.resolve(base + combo)
                    assertEquals("${t.id} $p", emptyList<ParamError>(), t.validate(p))
                    assertUnion(t, p, t.build(p))
                }
            }
            assertEquals(emptyList<ParamError>(), t.validate(t.resolve(emptyMap())))
            for (preset in t.presets) {
                val p = t.resolve(preset.values)
                assertEquals("${t.id} preset ${preset.id}", emptyList<ParamError>(), t.validate(p))
                preset.values.forEach { (k, v) -> assertEquals(v, p.getValue(k), 0.0) }
            }
        }
    }

    /** Every combination of the template's options valid for [base] (lengths before an option use defaults). */
    private fun optionCombos(t: FurnitureTemplate, base: Params): List<Params> {
        var combos = listOf<Params>(emptyMap())
        for (spec in t.options) {
            combos = combos.flatMap { c ->
                val p = t.resolve(base + c)
                if (!spec.appliesWhen(p)) listOf(c) else spec.options(p).map { c + (spec.id to it.toDouble()) }
            }
        }
        return combos
    }

    @Test
    fun `every length is refused just outside its range and accepted on it`() {
        val rnd = Random(3)
        for (t in Templates.all) {
            repeat(30) {
                val p = sample(t, rnd)
                for ((i, spec) in t.params.withIndex()) {
                    if (spec.id !in p) continue
                    val earlier = p.filterKeys { k -> t.params.indexOfFirst { it.id == k } < i }
                    val r = spec.range(earlier)
                    val step = if (spec.kind == ParamKind.LENGTH) 1e-6 else 1.0
                    val low = t.validate(p + (spec.id to r.start - step)).firstOrNull { it.paramId == spec.id }
                    assertEquals("${t.id}.${spec.id} below ${r.start}", ParamError(spec.id, ParamError.Reason.TOO_SMALL, r.start), low)
                    val high = t.validate(p + (spec.id to r.endInclusive + step)).firstOrNull { it.paramId == spec.id }
                    assertEquals("${t.id}.${spec.id} above ${r.endInclusive}", ParamError(spec.id, ParamError.Reason.TOO_LARGE, r.endInclusive), high)
                    assertNull(t.validate(p + (spec.id to r.start)).firstOrNull { it.paramId == spec.id })
                    assertNull(t.validate(p + (spec.id to r.endInclusive)).firstOrNull { it.paramId == spec.id })
                }
            }
        }
    }

    @Test
    fun `missing, non-finite and fractional values are refused, and build refuses invalid parameters`() {
        val p = SofaTemplate.resolve(emptyMap())
        assertEquals(ParamError(W, ParamError.Reason.MISSING), SofaTemplate.validate(p - W).first())
        assertEquals(ParamError(H, ParamError.Reason.NOT_FINITE), SofaTemplate.validate(p + (H to Double.NaN)).first())
        assertEquals(
            ParamError(FurnitureParams.SEATS, ParamError.Reason.NOT_WHOLE),
            SofaTemplate.validate(p + (FurnitureParams.SEATS to 2.5)).first(),
        )
        try {
            SofaTemplate.build(p + (FurnitureParams.SEAT to 0.84))
            fail("built a seat above the back")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `sofa rules - seat below the back, three seats need room, arms leave 35 cm seats`() {
        val p = SofaTemplate.resolve(mapOf(W to 1.2, D to 0.9, H to 0.8))
        assertEquals(2.0, p.getValue(FurnitureParams.SEATS), 0.0) // 3 seats need W >= 1.3
        val tooHigh = SofaTemplate.validate(p + (FurnitureParams.SEAT to 0.7)).first()
        assertEquals(ParamError.Reason.TOO_LARGE, tooHigh.reason)
        assertEquals(0.65, tooHigh.limit!!, 1e-12) // H - 0.15
        val three = SofaTemplate.validate(p + (FurnitureParams.SEATS to 3.0)).first()
        assertEquals(ParamError(FurnitureParams.SEATS, ParamError.Reason.TOO_LARGE, 2.0), three)
        // 2 seats of 35 cm in 1.2 m leave at most 25 cm per arm.
        assertEquals(0.25, SofaTemplate.spec(FurnitureParams.ARM_W)!!.range(p).endInclusive, 1e-12)
        // Default seat height follows H.
        assertEquals(0.52 * 0.8, p.getValue(FurnitureParams.SEAT), 1e-12)
        assertEquals(listOf(FurnitureParams.SEAT, FurnitureParams.ARM_H, FurnitureParams.ARM_W), SofaTemplate.guidedExtras.map { it.id })
    }

    @Test
    fun `a 3-seat sofa has 3 seat cushions and 4 legs, a legless one none`() {
        val parts = SofaTemplate.build(mapOf(W to 2.1, D to 0.9, H to 0.85, FurnitureParams.SEATS to 3.0))
        assertEquals(3, parts.count { it.name.startsWith("seat") })
        assertEquals(4, parts.count { it.material == PartMaterial.LEG })
        val legless = SofaTemplate.build(mapOf(W to 2.1, D to 0.9, H to 0.85, FurnitureParams.LEG to 0.0))
        assertEquals(0, legless.count { it.material == PartMaterial.LEG })
        assertUnion(SofaTemplate, SofaTemplate.resolve(mapOf(W to 2.1, D to 0.9, H to 0.85, FurnitureParams.LEG to 0.0)), legless)
    }

    @Test
    fun `L-sofa left is the mirror image of right`() {
        val base = mapOf(W to 2.8, D to 1.7, H to 0.85)
        val right = LSofaTemplate.build(base + (FurnitureParams.SIDE to FurnitureParams.SIDE_RIGHT.toDouble()))
        val left = LSofaTemplate.build(base + (FurnitureParams.SIDE to FurnitureParams.SIDE_LEFT.toDouble()))
        assertEquals(right.size, left.size)
        for ((r, l) in right.zip(left)) {
            assertEquals(r.name, l.name)
            assertEquals(-r.center.x, l.center.x, 1e-12)
            assertEquals(r.center.y, l.center.y, 0.0)
            assertEquals(r.center.z, l.center.z, 0.0)
            assertTrue(r.size.approxEquals(l.size, 0.0))
        }
        // The return reaches the front (D) on its own side only.
        val returnSeat = right.single { it.name == "seatReturn" }
        assertTrue(returnSeat.center.x > 0)
        assertEquals(1.7 / 2, returnSeat.center.z + returnSeat.size.z / 2, 1e-12)
    }

    @Test
    fun `bed - no headboard means the mattress top is H and no pillows`() {
        val p = BedTemplate.resolve(mapOf(W to 1.6, D to 2.0, H to 0.45))
        assertEquals(0.0, p.getValue(FurnitureParams.HEADBOARD), 0.0)
        assertFalse(FurnitureParams.BED_HEIGHT in p)
        assertFalse(FurnitureParams.HEADBOARD_T in p)
        val parts = BedTemplate.build(p)
        val mattress = parts.single { it.name == "mattress" }
        assertEquals(0.45, mattress.center.y + mattress.size.y / 2, 1e-12)
        assertTrue(parts.none { it.name.startsWith("pillow") || it.name == "headboard" })
        // A headboard is not offered on a bed this low.
        assertEquals(listOf(0), BedTemplate.spec(FurnitureParams.HEADBOARD)!!.options(p))
        val king = BedTemplate.resolve(BedTemplate.presets.single { it.id == "king" }.values)
        assertEquals(1.88, king.getValue(W), 0.0)
        assertEquals(2, BedTemplate.build(king).count { it.name.startsWith("pillow") })
    }

    @Test
    fun `wardrobe doors, handles and a 3-door minimum width`() {
        val two = WardrobeTemplate.build(mapOf(W to 1.0, D to 0.6, H to 2.0, FurnitureParams.DOORS to 2.0))
        assertEquals(2, two.count { it.material == PartMaterial.DOOR })
        assertEquals(2, two.count { it.material == PartMaterial.ACCENT })
        val three = WardrobeTemplate.build(mapOf(W to 1.5, D to 0.6, H to 2.0, FurnitureParams.DOORS to 3.0))
        assertEquals(3, three.count { it.material == PartMaterial.DOOR })
        assertEquals(listOf(2), WardrobeTemplate.spec(FurnitureParams.DOORS)!!.options(mapOf(W to 0.8)))
        assertEquals(listOf(2, 3), WardrobeTemplate.spec(FurnitureParams.DOORS)!!.options(mapOf(W to 0.9)))
    }

    @Test
    fun `study table pedestal sides mirror and none drops its width`() {
        val none = StudyTableTemplate.resolve(mapOf(FurnitureParams.PEDESTAL to FurnitureParams.PEDESTAL_NONE.toDouble()))
        assertFalse(FurnitureParams.PEDESTAL_W in none)
        assertEquals(4, StudyTableTemplate.build(none).count { it.material == PartMaterial.LEG })
        val right = StudyTableTemplate.build(mapOf(FurnitureParams.PEDESTAL to FurnitureParams.PEDESTAL_RIGHT.toDouble()))
        val left = StudyTableTemplate.build(mapOf(FurnitureParams.PEDESTAL to FurnitureParams.PEDESTAL_LEFT.toDouble()))
        assertTrue(right.single { it.name == "pedestal" }.center.x > 0)
        assertEquals(-right.single { it.name == "pedestal" }.center.x, left.single { it.name == "pedestal" }.center.x, 1e-12)
    }

    @Test
    fun `bookshelf shelves keep 18 cm openings`() {
        val p = BookshelfTemplate.resolve(mapOf(H to 0.6, FurnitureParams.PLINTH to 0.15))
        val max = BookshelfTemplate.spec(FurnitureParams.SHELVES)!!.options(p).max()
        assertEquals(2, max) // (0.6 - 0.15 - 0.036) / 0.18 = 2.3
        val parts = BookshelfTemplate.build(p + (FurnitureParams.SHELVES to 2.0))
        assertEquals(2, parts.count { it.name.startsWith("shelf") })
    }

    @Test
    fun `registry - ids are versioned and unique, core templates exist, stored rows rebuild`() {
        val ids = Templates.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(ids.all { Regex("[a-z_]+_v[0-9]+").matches(it) })
        assertTrue(ids.containsAll(listOf("sofa_v1", "bed_v1", "wardrobe_v1")))
        assertEquals(9, ids.size)
        assertNull(Templates.byId("sofa_v9"))
        assertNull(Templates.partsOrNull("sofa_v9", emptyMap()))
        // A corrupt stored row (seat above the back) gives no parts: the gallery shows the box instead.
        assertNull(Templates.partsOrNull("sofa_v1", mapOf(W to 2.0, D to 0.9, H to 0.85, FurnitureParams.SEAT to 0.9)))
        // The palette key is ignored for geometry.
        val stored = SofaTemplate.resolve(mapOf(W to 2.0)) + (FurniturePalettes.KEY to 2.0)
        assertEquals(SofaTemplate.build(stored), Templates.partsOrNull("sofa_v1", stored))
        for (t in Templates.all) assertTrue(t.guidedExtras.size in 1..3)
    }

    @Test
    fun `templates are deterministic - same parameters, same parts and bytes`() {
        val rnd = Random(11)
        for (t in Templates.all) {
            val p = sample(t, rnd)
            assertEquals(t.build(p), t.build(p))
            assertArrayEquals(GlbWriter.writeParts(t.build(p)), GlbWriter.writeParts(t.build(p)))
        }
    }

    @Test
    fun `GLB is valid glTF with one primitive per material used`() {
        val parts = SofaTemplate.build(emptyMap())
        val g = GlbTestKit.parse(GlbWriter.writeParts(parts, FurniturePalettes.of(1)))
        val prims = g.list("meshes")[0]["primitives"] as List<*>
        assertEquals(parts.map { it.material }.distinct().size, prims.size)
        assertTrue(GlbWriter.writeParts(parts).size < 16_000)
    }

    @Test
    fun `palettes - stored index round trip, bad values fall back to the default`() {
        assertEquals(5, FurniturePalettes.count)
        assertEquals(2, FurniturePalettes.indexOf(mapOf(FurniturePalettes.KEY to 2.0)))
        assertEquals(0, FurniturePalettes.indexOf(emptyMap()))
        assertEquals(0, FurniturePalettes.indexOf(mapOf(FurniturePalettes.KEY to 99.0)))
        assertEquals(0, FurniturePalettes.indexOf(mapOf(FurniturePalettes.KEY to Double.NaN)))
        assertEquals(FurniturePalettes.of(0).colorOf(PartMaterial.SOFT), FurniturePalettes.fromParams(mapOf(FurniturePalettes.KEY to -1.0)).colorOf(PartMaterial.SOFT))
        assertFalse(FurniturePalettes.of(2).colorOf(PartMaterial.BODY) == FurniturePalettes.of(0).colorOf(PartMaterial.BODY))
        assertEquals(FurniturePalettes.count, FurniturePalettes.ids.size)
        assertNotNull(FurniturePalettes.of(-3))
    }

    @Test
    fun `spec guards - lengths only are measured, ids are keys, W D H come first`() {
        try {
            ParamSpec("bad id", ParamKind.LENGTH, 0.0, 1.0, default = { 0.0 })
            fail()
        } catch (expected: IllegalArgumentException) {
        }
        try {
            ParamSpec("n", ParamKind.COUNT, 0.0, 1.0, MeasureHint.HEIGHT, default = { 0.0 })
            fail()
        } catch (expected: IllegalArgumentException) {
        }
        try {
            object : FurnitureTemplate("x_v1", listOf(ParamSpec(H, ParamKind.LENGTH, 0.1, 1.0, default = { 0.5 }))) {
                override fun parts(p: Params, b: PartBuilder) = Unit
            }
            fail()
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `a template whose parts miss the box fails its own check`() {
        val broken = object : FurnitureTemplate(
            "broken_v1",
            listOf(
                ParamSpec(W, ParamKind.LENGTH, 0.1, 1.0, default = { 0.5 }),
                ParamSpec(D, ParamKind.LENGTH, 0.1, 1.0, default = { 0.5 }),
                ParamSpec(H, ParamKind.LENGTH, 0.1, 1.0, default = { 0.5 }),
            ),
        ) {
            override fun parts(p: Params, b: PartBuilder) {
                b.box("short", PartMaterial.BODY, b.left, b.right, 0.0, b.h * 0.9, b.back, b.front)
            }
        }
        try {
            broken.build(emptyMap())
            fail("a short model passed the union check")
        } catch (expected: IllegalStateException) {
        }
    }

    private companion object {
        const val SAMPLES = 500

        /** Templates stay tiny: the writer's MAX_PARTS (512) is never approached. */
        const val MAX_TEMPLATE_PARTS = 40

        init {
            check(MAX_TEMPLATE_PARTS <= GlbWriter.MAX_PARTS)
        }
    }
}
