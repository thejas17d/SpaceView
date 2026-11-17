package com.spaceview.app.furniture

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.furniture.BedTemplate
import com.spaceview.engine.furniture.FurniturePalettes
import com.spaceview.engine.furniture.FurnitureParams
import com.spaceview.engine.furniture.FurnitureTemplate
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.D
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.H
import com.spaceview.engine.furniture.FurnitureTemplate.Companion.W
import com.spaceview.engine.furniture.ParamError
import com.spaceview.engine.furniture.SofaTemplate
import com.spaceview.engine.furniture.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The flow's pure rules: value precedence, guided steps, Save readiness, the draft and the saved-state codec. */
class FurnitureFlowTest {

    @Test
    fun `explicit values beat options, options beat the preset, the preset beats defaults`() {
        val inputs = FurnitureInputs(
            templateId = "sofa_v1",
            lengths = mapOf(W to Dimension.measured(2.4, 0.02)),
            options = mapOf(FurnitureParams.SEATS to 2),
            presetId = "three_seat", // seats 3, W 2.1
        )
        val p = FurnitureFlow.params(SofaTemplate, inputs)
        assertEquals(2.4, p.getValue(W), 0.0)
        assertEquals(2.0, p.getValue(FurnitureParams.SEATS), 0.0)
        val presetOnly = FurnitureFlow.params(SofaTemplate, FurnitureInputs("sofa_v1", presetId = "three_seat"))
        assertEquals(2.1, presetOnly.getValue(W), 0.0)
        assertEquals(3.0, presetOnly.getValue(FurnitureParams.SEATS), 0.0)
    }

    @Test
    fun `the bed asks for its bed height only with a headboard`() {
        val withHb = FurnitureFlow.measureSteps(BedTemplate, FurnitureFlow.params(BedTemplate, FurnitureInputs("bed_v1")))
        assertEquals(listOf(W, D, H, FurnitureParams.BED_HEIGHT, FurnitureParams.MATTRESS), withHb.map { it.id })
        val none = FurnitureInputs("bed_v1", options = mapOf(FurnitureParams.HEADBOARD to 0))
        assertEquals(listOf(W, D, H, FurnitureParams.MATTRESS), FurnitureFlow.measureSteps(BedTemplate, FurnitureFlow.params(BedTemplate, none)).map { it.id })
    }

    @Test
    fun `every template has W D H and one to three key extras to measure`() {
        for (t in Templates.all) {
            val steps = FurnitureFlow.measureSteps(t, t.resolve(emptyMap()))
            assertEquals(t.id, listOf(W, D, H), steps.take(3).map { it.id })
            assertTrue(t.id, steps.size - 3 in 1..3)
            // Every measured parameter has a label and a "how to measure" line.
            steps.forEach { assertTrue("${t.id}.${it.id}", FurnitureLabels.hint(t, it.id) != null) }
        }
    }

    @Test
    fun `defaults show as estimates and W D H are required`() {
        val inputs = FurnitureInputs("sofa_v1", lengths = mapOf(W to Dimension.typed(2.0)))
        val p = FurnitureFlow.params(SofaTemplate, inputs)
        assertEquals(DimensionSource.PRESET, FurnitureFlow.dimension(inputs, p, FurnitureParams.SEAT)!!.source)
        assertEquals(DimensionSource.TYPED, FurnitureFlow.dimension(inputs, p, W)!!.source)
        assertEquals(listOf(D, H), FurnitureFlow.missingRequired(inputs))
        assertFalse(FurnitureFlow.canSave(SofaTemplate, inputs))
        try {
            FurnitureFlow.draft(SofaTemplate, inputs, "Sofa", DeviceTier.NO_DEPTH, null)
            fail("drafted without D and H")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `an out-of-range value blocks Save and names its limit`() {
        val inputs = FurnitureInputs("sofa_v1", lengths = mapOf(W to Dimension.typed(2.0), D to Dimension.typed(0.9), H to Dimension.typed(0.85)))
        assertTrue(FurnitureFlow.canSave(SofaTemplate, inputs))
        val e = FurnitureFlow.errorIfSet(SofaTemplate, inputs, FurnitureParams.SEAT, 0.8)!!
        assertEquals(ParamError.Reason.TOO_LARGE, e.reason)
        assertEquals(0.70, e.limit!!, 1e-12)
        val bad = inputs.copy(lengths = inputs.lengths + (FurnitureParams.SEAT to Dimension.typed(0.8)))
        assertFalse(FurnitureFlow.canSave(SofaTemplate, bad))
        assertNull(FurnitureFlow.errorIfSet(SofaTemplate, inputs, FurnitureParams.SEAT, 0.44))
    }

    @Test
    fun `the draft's parts are exactly W x H x D and carry the palette`() {
        val inputs = FurnitureInputs(
            "sofa_v1",
            lengths = mapOf(W to Dimension.measured(2.03, 0.02), D to Dimension.measured(0.91, 0.012), H to Dimension.typed(0.84)),
            paletteIndex = 4,
        )
        val draft = FurnitureFlow.draft(SofaTemplate, inputs, "Sofa", DeviceTier.NO_DEPTH, "1.57.0")
        val box = FurnitureTemplate.unionBox(draft.parts)
        assertEquals(2.03, box.x, 1e-9)
        assertEquals(0.84, box.y, 1e-9)
        assertEquals(0.91, box.z, 1e-9)
        assertEquals(4.0, draft.params.getValue(FurniturePalettes.KEY), 0.0)
        assertEquals(FurniturePalettes.of(4).colorOf(com.spaceview.engine.PartMaterial.SOFT), draft.palette.colorOf(com.spaceview.engine.PartMaterial.SOFT))
        // Stored params rebuild the same parts (regeneration).
        assertEquals(draft.parts, Templates.partsOrNull(draft.templateId, draft.params))
    }

    @Test
    fun `saved-state codec round trips and drops malformed entries`() {
        val lengths = mapOf(W to Dimension.measured(2.1234, 0.0121), D to Dimension.typed(0.9))
        assertEquals(lengths, FurnitureFlow.decodeLengths(FurnitureFlow.encodeLengths(lengths)))
        assertEquals(emptyMap<String, Dimension>(), FurnitureFlow.decodeLengths(null))
        assertEquals(mapOf(W to Dimension.typed(1.0)), FurnitureFlow.decodeLengths("W=1.0,0.0,TYPED;D=x,0,TYPED;H=1,NaN,TYPED;Q=1,0,BOGUS;junk"))
        val options = mapOf(FurnitureParams.SEATS to 3, FurnitureParams.SIDE to 0)
        assertEquals(options, FurnitureFlow.decodeOptions(FurnitureFlow.encodeOptions(options)))
        assertEquals(mapOf("a" to 1), FurnitureFlow.decodeOptions("a=1;b=x;c"))
    }

    @Test
    fun `registry rebuilds stored rows with their palette and refuses unknown ids`() {
        val params = SofaTemplate.resolve(mapOf(W to 2.0)) + (FurniturePalettes.KEY to 1.0)
        assertEquals(SofaTemplate.build(params), FurnitureTemplateRegistry.parts("sofa_v1", params))
        assertEquals(FurniturePalettes.of(1).colorOf(com.spaceview.engine.PartMaterial.BODY), FurnitureTemplateRegistry.palette("sofa_v1", params)!!.colorOf(com.spaceview.engine.PartMaterial.BODY))
        assertNull(FurnitureTemplateRegistry.parts("sofa_v7", params))
        assertNull(FurnitureTemplateRegistry.palette("sofa_v7", params))
    }
}
