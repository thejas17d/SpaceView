package com.spaceview.app.common

import com.spaceview.app.common.SpokenText.Form
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** M5 accessibility: measurement text is read in full ("plus or minus", unit names, fractions), never as symbols. */
class SpokenTextTest {
    private val en = SpokenText.Words(
        plusOrMinus = "plus or minus", about = "about", by = "by",
        mixedNumber = "%1\$s and %2\$s", fractionOver = "%1\$s over %2\$s",
        mm = Form("millimetre", "millimetres"), cm = Form("centimetre", "centimetres"), m = Form("metre", "metres"),
        inch = Form("inch", "inches"), foot = Form("foot", "feet"),
        squareMetre = Form("square metre", "square metres"), squareFoot = Form("square foot", "square feet"),
        half = Form("half", "halves"), quarter = Form("quarter", "quarters"),
        eighth = Form("eighth", "eighths"), sixteenth = Form("sixteenth", "sixteenths"),
    )
    private val hi = SpokenText.Words(
        plusOrMinus = "कम या ज़्यादा", about = "लगभग", by = "गुणा",
        mixedNumber = "%1\$s सही %2\$s", fractionOver = "%1\$s बटा %2\$s",
        mm = Form("मिलीमीटर", "मिलीमीटर"), cm = Form("सेंटीमीटर", "सेंटीमीटर"), m = Form("मीटर", "मीटर"),
        inch = Form("इंच", "इंच"), foot = Form("फ़ुट", "फ़ुट"),
        squareMetre = Form("वर्ग मीटर", "वर्ग मीटर"), squareFoot = Form("वर्ग फ़ुट", "वर्ग फ़ुट"),
        half = Form("बटा 2", "बटा 2"), quarter = Form("बटा 4", "बटा 4"),
        eighth = Form("बटा 8", "बटा 8"), sixteenth = Form("बटा 16", "बटा 16"),
    )

    private fun speak(s: String) = SpokenText.speak(s, en)

    @Test fun `value with a bare tolerance speaks the unit twice`() =
        assertEquals("45.5 centimetres plus or minus 0.5 centimetres", speak("45.5 cm ± 0.5"))

    @Test fun `tolerance with its own unit`() =
        assertEquals("45.5 centimetres plus or minus 0.5 centimetres", speak("45.5 cm ± 0.5 cm"))

    @Test fun `feet and inches glyphs, singular after 1`() =
        assertEquals("5 feet 8 inches plus or minus 1 inch", speak("5′ 8″ ± 1″"))

    @Test fun `exactly one takes the singular`() {
        assertEquals("1 centimetre", speak("1 cm"))
        assertEquals("1 foot 1 inch", speak("1′ 1″"))
        assertEquals("1 metre plus or minus 1 centimetre", speak("1 m ± 1 cm"))
        assertEquals("1.0 metres", speak("1.0 m"))
        assertEquals("plus or minus 1 centimetre", speak("± 1 cm"))
    }

    @Test fun `fractions are spoken in words, never as a slash`() {
        assertEquals("23 and 5 eighths inches", speak("23 5/8 in"))
        assertEquals("23 and 5 eighths inches", speak("23 5/8″"))
        assertEquals("5 eighths inch", speak("5/8 in"))
        assertEquals("1 half inch", speak("1/2 in"))
        assertEquals("3 sixteenths inch", speak("3/16″"))
        assertEquals("2 and 3 quarters inches", speak("2 3/4 in"))
        assertEquals("7 over 32 inch", speak("7/32 in"))
        assertFalse(speak("23 5/8 in ± 1/8").contains("/"))
    }

    @Test fun `a fractional tolerance after a fractional value`() =
        assertEquals("23 and 5 eighths inches plus or minus 1 eighth inch", speak("23 5/8 in ± 1/8"))

    @Test fun `box dimensions with an estimate and a bracketed tolerance`() =
        assertEquals("18 by 24 by about 1 inch, plus or minus 1 quarter inch", speak("18 × 24 × ~1 in (± 1/4 in)"))

    @Test fun `area units`() {
        assertEquals("0.24 square metres plus or minus 0.01 square metres", speak("0.24 m² ± 0.01"))
        assertEquals("120 square feet", speak("120 sq ft"))
        assertEquals("1 square metre", speak("1 m²"))
    }

    @Test fun `a stray word in is not a unit`() =
        assertEquals("43 inches outer diagonal, fits in the corner", speak("43 in outer diagonal, fits in the corner"))

    @Test fun `metres and millimetres`() {
        assertEquals("1.20 metres plus or minus 12 millimetres", speak("1.20 m ± 12 mm"))
        assertEquals("61.0 centimetres, 24 inches", speak("61.0 cm (24 in)"))
    }

    @Test fun `glued units`() = assertEquals("45.5 centimetres", speak("45.5cm"))

    @Test fun `badge tolerance alone`() = assertEquals("plus or minus 2 centimetres", speak("± 2 cm"))

    @Test fun `plain text is unchanged`() = assertEquals("Good accuracy", speak("Good accuracy"))

    @Test fun `already spoken text stays the same`() {
        val once = speak("45.5 cm ± 0.5")
        assertEquals(once, speak(once))
    }

    @Test fun `unit chip names`() {
        assertEquals("centimetres", SpokenText.unitWord("cm", en))
        assertEquals("inches", SpokenText.unitWord("in", en))
        assertEquals("px", SpokenText.unitWord("px", en))
    }

    @Test fun `Hindi words, never a symbol`() {
        val s = SpokenText.speak("45.5 cm ± 0.5", hi)
        assertEquals("45.5 सेंटीमीटर कम या ज़्यादा 0.5 सेंटीमीटर", s)
        assertFalse(s.contains("±"))
        assertEquals("23 सही 5 बटा 8 इंच", SpokenText.speak("23 5/8 in", hi))
    }
}
