package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StampSymbolTest {
    @Test
    fun completeSetOf14WithUniqueLabels() {
        assertEquals(14, StampSymbol.entries.size)
        assertEquals(StampSymbol.entries.size, StampSymbol.entries.map { it.label }.toSet().size)
        StampSymbol.entries.forEach { assertTrue(it.label.isNotBlank()) }
    }
}