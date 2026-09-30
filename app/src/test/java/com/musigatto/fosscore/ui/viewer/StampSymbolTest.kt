package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampSymbolTest {
    @Test
    fun completeSetWithUniqueLabels() {
        // 14 originales + 5 alteraciones SMuFL
        assertEquals(19, StampSymbol.entries.size)
        assertEquals(StampSymbol.entries.size, StampSymbol.entries.map { it.label }.toSet().size)
        StampSymbol.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }

    @Test
    fun stampColorFallsBackToThemeColorWhenNull() {
        val fallback = Color.Red
        assertEquals(fallback, stampColor(null, fallback))
        assertEquals(Color(0xFF336699.toInt()), stampColor(0xFF336699.toInt(), fallback))
    }
}