package com.spaceview.app.gallery

import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.DeviceTier
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.Quality
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class GalleryFormatterTest {

    private fun item(
        kind: ObjectKind,
        w: Dimension,
        h: Dimension,
        d: Dimension,
        quality: Quality = Quality.FAIR,
    ) = GalleryItem(
        id = 1, kind = kind, width = w, height = h, depth = d, quality = quality, calibrated = false,
        deviceTier = DeviceTier.NO_DEPTH, arcoreVersion = null, label = null, createdAt = 0, updatedAt = 0,
        modelFile = File("m.glb"), photoFile = null, thumbnailFile = null, bytes = 0,
    )

    private val frame = item(
        ObjectKind.FRAME,
        Dimension(0.457, 0.005, false),
        Dimension(0.610, 0.006, false),
        Dimension(0.025, 0.0, true),
    )

    @Test
    fun frameReadsInInchesWithEstimateMark() {
        // AUTO: frames are sold in inches.
        assertEquals("18 × 24 × ~1 in", GalleryFormatter.compactDims(frame, UnitPreference.AUTO))
        assertEquals("45.7 × 61.0 × ~2.5 cm", GalleryFormatter.compactDims(frame, UnitPreference.METRIC))
    }

    @Test
    fun maxPlusMinusIsTheLargestMeasuredBoundRoundedUp() {
        // Largest measured ±: 6 mm → "± 0.6 cm" in metric, "± 1/4 in" in inches; the estimate is ignored.
        assertEquals("± 0.6 cm", GalleryFormatter.maxPlusMinus(frame, UnitPreference.METRIC))
        assertEquals("± 1/4 in", GalleryFormatter.maxPlusMinus(frame, UnitPreference.AUTO))
        val typed = item(ObjectKind.BOX, Dimension(0.5, 0.0, true), Dimension(0.5, 0.0, true), Dimension(0.5, 0.0, true))
        assertNull(GalleryFormatter.maxPlusMinus(typed, UnitPreference.AUTO))
    }

    @Test
    fun everyShownPlusMinusCoversEachMeasuredDimension() {
        for (pref in UnitPreference.entries) {
            val pm = GalleryFormatter.maxPlusMinus(frame, pref)!!
            val unit = GalleryFormatter.primaryUnit(frame.kind, pref)
            val number = pm.removePrefix("± ").removeSuffix(" ${unit.symbol}").removeSuffix("″")
            val inUnit = number.split(' ').sumOf { part ->
                if ('/' in part) part.split('/').let { it[0].toDouble() / it[1].toDouble() } else part.toDouble()
            }
            assertTrue("$pref $pm", Units.toMetres(inUnit, unit) >= 0.006 - 1e-12)
        }
    }

    @Test
    fun tvShowsDiagonalInInches() {
        // 43" class TV panel: 95.2 × 53.6 cm → 109.3 cm ≈ 43.0 in.
        val tv = item(ObjectKind.TV, Dimension(0.952, 0.01, false), Dimension(0.536, 0.01, false), Dimension(0.08, 0.01, false))
        val text = GalleryFormatter.diagonalShort(tv, UnitPreference.AUTO)!!
        assertTrue(text, text.endsWith(" in"))
        val inches = text.removeSuffix(" in").toDouble()
        assertEquals(sqrt(0.952 * 0.952 + 0.536 * 0.536) / 0.0254, inches, 1.0)
        // W × H × D of a TV reads in cm (object context), the diagonal detail in inches with ± and cm.
        assertTrue(GalleryFormatter.compactDims(tv, UnitPreference.AUTO).endsWith(" cm"))
        val detail = GalleryFormatter.diagonalDetail(tv, UnitPreference.AUTO)!!
        assertEquals(LengthUnit.INCH_DECIMAL, detail.primary.unit)
        assertTrue(detail.primary.plusMinus != null)
        assertNull(GalleryFormatter.diagonalShort(frame, UnitPreference.AUTO))
    }

    @Test
    fun diagonalUsesTheFullyCorrelatedBound() {
        val tv = item(ObjectKind.TV, Dimension(0.6, 0.0196, false), Dimension(0.8, 0.0196, false), Dimension(0.05, 0.0, true))
        val (m, estimate) = GalleryFormatter.diagonal(tv)
        assertEquals(1.0, m.value, 1e-12)
        // b_d = (0.6·0.0196 + 0.8·0.0196) / 1 = 0.02744 (the independent sum would give only 0.0196).
        assertEquals(0.02744, m.bound95, 1e-12)
        assertFalse(estimate)
    }

    /** M1: never below the engine's diagonal ± (shared scale applied once, random parts in quadrature, floor once). */
    @Test
    fun diagonalBoundCoversTheEngineDiagonalForTheReviewCases() {
        val scale95 = 0.0102 * 1.96 // σ_s = 1.02 %
        val random95 = 0.002 * 1.96 // 2 mm per side
        for ((w, h) in listOf(0.952 to 0.536, 0.60 to 0.45, 0.5 to 0.5)) {
            val bw = sqrt(random95 * random95 + (scale95 * w).let { it * it })
            val bh = sqrt(random95 * random95 + (scale95 * h).let { it * it })
            val d = sqrt(w * w + h * h)
            val engine = sqrt(((w * random95).let { it * it } + (h * random95).let { it * it }) / (d * d) + (scale95 * d).let { it * it })
            val tv = item(ObjectKind.TV, Dimension(w, bw, false), Dimension(h, bh, false), Dimension(0.05, 0.004, false))
            val shown = GalleryFormatter.diagonal(tv).first.bound95
            assertTrue("$w×$h: shown $shown < engine $engine", shown >= engine)
            assertEquals((w * bw + h * bh) / d, shown, 1e-12)
            // The ± the user reads (rounded up by Units) covers it too.
            val detail = GalleryFormatter.diagonalDetail(tv, UnitPreference.METRIC)!!
            val pm = detail.primary.plusMinus!!.toDouble()
            assertTrue(Units.toMetres(pm, LengthUnit.CM) >= engine)
        }
    }

    /** M1: a diagonal that rests on a typed or preset side is an estimate: "~" and no ±. */
    @Test
    fun diagonalOnAnEstimateHasNoPlusMinus() {
        val tv = item(ObjectKind.TV, Dimension(0.952, 0.02, false), Dimension(0.536, 0.015, false), Dimension(0.08, 0.0, true))
        assertNotNull(GalleryItemViewModel.toState(tv, UnitPreference.AUTO).rows.single { it.which == DimensionRow.Which.DIAGONAL }.formatted.primary.plusMinus)
        for (edited in listOf(tv.copy(width = Dimension.typed(0.95)), tv.copy(height = Dimension.preset(0.536)))) {
            val (m, estimate) = GalleryFormatter.diagonal(edited)
            assertTrue(estimate)
            assertEquals(0.0, m.sigma, 0.0)
            val row = GalleryItemViewModel.toState(edited, UnitPreference.AUTO).rows.single { it.which == DimensionRow.Which.DIAGONAL }
            assertTrue(row.isEstimate)
            assertNull(row.formatted.primary.plusMinus)
            assertTrue(GalleryFormatter.diagonalShort(edited, UnitPreference.AUTO)!!.startsWith("~"))
        }
        val typedRow = GalleryItemViewModel.toState(tv.copy(width = Dimension.typed(0.95)), UnitPreference.AUTO).rows
        assertTrue(typedRow.single { it.which == DimensionRow.Which.DIAGONAL }.isTyped)
        assertTrue(typedRow[0].isTyped)
        assertFalse(typedRow[2].isTyped) // the preset depth
    }

    /** MINOR 14: the card diagonal follows the unit preference. */
    @Test
    fun diagonalShortFollowsTheUnitPreference() {
        val tv = item(ObjectKind.TV, Dimension(0.952, 0.02, false), Dimension(0.536, 0.015, false), Dimension(0.08, 0.004, false))
        val metric = GalleryFormatter.diagonalShort(tv, UnitPreference.METRIC)!!
        assertTrue(metric, metric.matches(Regex("""\d+(\.\d)? cm \(\d+(\.\d)? in\)""")))
        val auto = GalleryFormatter.diagonalShort(tv, UnitPreference.AUTO)!!
        assertTrue(auto, auto.endsWith(" in") && "cm" !in auto)
    }

    /** MINOR 15: TalkBack reads "about", not "tilde". */
    @Test
    fun spokenDimsHaveNoTilde() {
        assertEquals("18 by 24 by about 1 in", GalleryFormatter.spokenDims("18 × 24 × ~1 in", "about", "by"))
    }

    /** MINOR 15: digits typed on a Hindi keyboard parse like ASCII digits. */
    @Test
    fun parseAcceptsDevanagariDigits() {
        assertEquals(0.455, GalleryFormatter.parseLength("४५.५", GalleryFormatter.EditUnit.CM)!!, 1e-12)
        assertEquals(23.625 * 0.0254, GalleryFormatter.parseLength("२३ ५/८", GalleryFormatter.EditUnit.IN)!!, 1e-12)
    }

    /** MINOR 17: only fields whose text really changed count as edits, also across a unit switch. */
    @Test
    fun editedValueIgnoresUntouchedAndRetypedFields() {
        val cm = GalleryFormatter.EditUnit.CM
        val inch = GalleryFormatter.EditUnit.IN
        assertNull(GalleryFormatter.editedValue(null, 0.457, cm))
        assertNull(GalleryFormatter.editedValue(" 45.7 ", 0.457, cm)) // retyped the pre-filled value
        assertEquals(0.5, GalleryFormatter.editedValue("50", 0.457, cm)!!, 1e-12)
        assertNull(GalleryFormatter.editedValue("abc", 0.457, cm))
        // Typed the measured value in cm, then switched to inches: still not an edit.
        val switched = GalleryFormatter.convertTyped(listOf("45.7", null, "xyz"), cm, inch)
        assertEquals(listOf(GalleryFormatter.editText(0.457, inch), null, "xyz"), switched)
        assertNull(GalleryFormatter.editedValue(switched[0], 0.457, inch))
        // A real edit survives the switch.
        val edited = GalleryFormatter.convertTyped(listOf("50", null, null), cm, inch)
        assertEquals(0.5, GalleryFormatter.editedValue(edited[0], 0.457, inch)!!, 0.0003)
    }

    @Test
    fun shareTextHasPrimaryPlusMinusAndSecondary() {
        assertEquals("18 × 24 × ~1 in (± 1/4 in) · 45.7 × 61.0 × ~2.5 cm", GalleryFormatter.shareDims(frame, UnitPreference.AUTO))
    }

    @Test
    fun parseAcceptsDecimalsCommasFractionsAndSuffixes() {
        val cm = GalleryFormatter.EditUnit.CM
        val inch = GalleryFormatter.EditUnit.IN
        assertEquals(0.455, GalleryFormatter.parseLength("45.5", cm)!!, 1e-12)
        assertEquals(0.455, GalleryFormatter.parseLength(" 45,5 cm ", cm)!!, 1e-12)
        assertEquals(23.625 * 0.0254, GalleryFormatter.parseLength("23 5/8", inch)!!, 1e-12)
        assertEquals(0.625 * 0.0254, GalleryFormatter.parseLength("5/8", inch)!!, 1e-12)
        assertEquals(24 * 0.0254, GalleryFormatter.parseLength("24\"", inch)!!, 1e-12)
        assertEquals(24 * 0.0254, GalleryFormatter.parseLength("24 in", inch)!!, 1e-12)
        for (bad in listOf("", "abc", "0", "-3", "1/0", "2.5 1/2", "1 2 3", "6000", "0.01")) {
            assertNull(bad, GalleryFormatter.parseLength(bad, cm))
        }
    }

    @Test
    fun editTextRoundTrips() {
        assertEquals("45.7", GalleryFormatter.editText(0.457, GalleryFormatter.EditUnit.CM))
        assertEquals("60", GalleryFormatter.editText(0.6, GalleryFormatter.EditUnit.CM))
        assertEquals("18", GalleryFormatter.editText(18 * 0.0254, GalleryFormatter.EditUnit.IN))
        assertEquals(GalleryFormatter.EditUnit.IN, GalleryFormatter.editUnit(ObjectKind.FRAME, UnitPreference.AUTO))
        assertEquals(GalleryFormatter.EditUnit.CM, GalleryFormatter.editUnit(ObjectKind.BOX, UnitPreference.AUTO))
        assertEquals(GalleryFormatter.EditUnit.IN, GalleryFormatter.editUnit(ObjectKind.BOX, UnitPreference.IMPERIAL))
    }

    @Test
    fun cardAndDetailState() {
        val card = GalleryViewModel.toCard(frame, UnitPreference.AUTO)
        assertEquals("18 × 24 × ~1 in", card.dimsText)
        assertNull(card.diagonalText)
        val state = GalleryItemViewModel.toState(frame, UnitPreference.METRIC)
        assertEquals(3, state.rows.size)
        assertTrue(state.rows[2].isEstimate)
        assertEquals("45.7 cm ± 0.5", state.rows[0].formatted.primary.text)
        assertNull(state.rows[2].formatted.primary.plusMinus)
        val gone = GalleryItemViewModel.toState(null, UnitPreference.AUTO)
        assertTrue(gone.loaded)
        assertNull(gone.item)
    }

    @Test
    fun shareFileNameIsSafe() {
        assertEquals("SpaceView-Caf-frame.jpg", GalleryShare.fileName("Café frame"))
        assertEquals("SpaceView.jpg", GalleryShare.fileName("फ़्रेम"))
        assertEquals("SpaceView-a-b.jpg", GalleryShare.fileName("a/../b"))
    }
}
