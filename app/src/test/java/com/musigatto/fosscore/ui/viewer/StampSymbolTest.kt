package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampSymbolTest {
    @Test
    fun completeSetWithUniqueLabels() {
        // 27 con nombre traducido + 10 digitaciones que se anuncian con su propio número
        assertEquals(37, StampSymbol.entries.size)
        val named = StampSymbol.entries.filter { it.descRes != null }
        assertEquals(27, named.size)
        assertEquals(named.size, named.map { it.descRes }.toSet().size)

        // one glyph per stamp, all in SMuFL's private use area (a duplicated code point, or a
        // decimal that forgot its 0x, draws the wrong symbol in the palette)
        assertEquals(
            StampSymbol.entries.size,
            StampSymbol.entries.map { it.codepoint }.toSet().size
        )
        StampSymbol.entries.forEach {
            assertTrue("U+%04X".format(it.codepoint), it.codepoint in 0xE000..0xF8FF)
        }
    }

    @Test
    fun stampColorFallsBackToThemeColorWhenNull() {
        val fallback = Color.Red
        assertEquals(fallback, stampColor(null, fallback))
        assertEquals(Color(0xFF336699.toInt()), stampColor(0xFF336699.toInt(), fallback))
    }

    @Test
    fun invertedMatchesNegativeModeAndKeepsAlpha() {
        assertEquals(Color.White, Color.Black.inverted())
        assertEquals(Color.Black, Color.White.inverted())
        val half = Color(0x80FF0000.toInt()).inverted()
        assertEquals(Color(0x8000FFFF.toInt()).toArgb(), half.toArgb())
    }
}