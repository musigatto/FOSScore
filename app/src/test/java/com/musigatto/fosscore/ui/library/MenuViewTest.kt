package com.musigatto.fosscore.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuViewTest {
    @Test
    fun everyViewButPracticeIsBuilt() {
        assertEquals(
            listOf(MenuView.LIBRARY, MenuView.SETLISTS, MenuView.TAGS, MenuView.PEOPLE, MenuView.GENRES),
            MenuView.entries.filter { it.ready },
        )
    }

    @Test
    fun everyViewHasItsOwnIcon() {
        assertEquals(MenuView.entries.size, MenuView.entries.map { it.icon }.toSet().size)
    }
}
