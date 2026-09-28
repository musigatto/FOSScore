package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun cycleWrapsSystemLightDark() {
        var current = ThemeMode.SYSTEM
        val seen = mutableListOf(current)
        repeat(3) {
            current = current.next()
            seen += current
        }
        assertEquals(
            listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM),
            seen
        )
    }

    @Test
    fun darkThemeMapsEachModeCorrectly() {
        assertTrue(ThemeMode.SYSTEM.darkTheme(true))
        assertFalse(ThemeMode.SYSTEM.darkTheme(false))
        assertFalse(ThemeMode.LIGHT.darkTheme(true))
        assertTrue(ThemeMode.DARK.darkTheme(false))
    }
}