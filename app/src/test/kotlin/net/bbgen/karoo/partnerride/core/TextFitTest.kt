package net.bbgen.karoo.partnerride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitTest {

    /** A font whose glyphs are [pxPerSpPerChar] wide per sp — width is linear in size, as real ones are. */
    private fun font(text: String, pxPerSpPerChar: Float = 0.6f): (Float) -> Float =
        { sp -> sp * pxPerSpPerChar * text.length }

    @Test
    fun `text that already fits keeps the desired size`() {
        val fitted = TextFit.fittedSp(40f, 480f, font("42 m"))
        assertEquals(40f, fitted, 0.001f)
    }

    @Test
    fun `text wider than the slot is shrunk to fit`() {
        // "150 m ▼" at 40 sp: 7 * 0.6 * 40 = 168 px into a 120 px slot.
        val measure = font("150 m ▼")
        val fitted = TextFit.fittedSp(40f, 120f, measure)
        assertTrue("expected shrinking, got $fitted", fitted < 40f)
        assertTrue("still overflows: ${measure(fitted)} px", measure(fitted) <= 120f)
    }

    @Test
    fun `the fitted size uses the slot rather than shrinking further than needed`() {
        val measure = font("150 m ▼")
        val fitted = TextFit.fittedSp(40f, 120f, measure)
        // Within a couple of percent of filling the width (the safety margin).
        assertTrue("wastes width: ${measure(fitted)} of 120 px", measure(fitted) >= 120f * 0.95f)
    }

    @Test
    fun `long strings shrink more than short ones`() {
        val short = TextFit.fittedSp(40f, 200f, font("42 m ▲"))
        val long = TextFit.fittedSp(40f, 200f, font("~150 m · 60 s"))
        assertTrue("$long should be smaller than $short", long < short)
    }

    @Test
    fun `shrinking stops at the readability floor`() {
        // A quarter-width slot with a 13-glyph string: the exact fit would be ~5 sp.
        val fitted = TextFit.fittedSp(40f, 40f, font("~150 m · 60 s"))
        assertEquals(TextFit.MIN_SP, fitted, 0.001f)
    }

    @Test
    fun `a desired size below the floor is never enlarged`() {
        val fitted = TextFit.fittedSp(8f, 10f, font("~150 m · 60 s"))
        assertEquals(8f, fitted, 0.001f)
    }

    @Test
    fun `an unknown slot width leaves the size alone`() {
        assertEquals(40f, TextFit.fittedSp(40f, 0f, font("~150 m · 60 s")), 0.001f)
        assertEquals(40f, TextFit.fittedSp(40f, -1f, font("~150 m · 60 s")), 0.001f)
    }

    @Test
    fun `empty text keeps the desired size`() {
        assertEquals(40f, TextFit.fittedSp(40f, 100f, font("")), 0.001f)
    }

    @Test
    fun `a font that rounds up per step still ends up fitting`() {
        // Hinting makes real widths step rather than scale exactly; the extra passes absorb it.
        val measure: (Float) -> Float = { sp -> kotlin.math.ceil(sp * 0.6f * 7) + 6f }
        val fitted = TextFit.fittedSp(40f, 120f, measure)
        assertTrue("still overflows: ${measure(fitted)} px", measure(fitted) <= 120f)
    }
}
