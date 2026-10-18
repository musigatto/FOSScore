package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTurnKeysTest {
    @Test
    fun nextKeys() {
        listOf(Key.PageDown, Key.DirectionRight, Key.DirectionDown, Key.Spacebar, Key.Enter, Key.NumPadEnter)
            .forEach { assertEquals(it.toString(), PageTurn.NEXT, pageTurnFor(it)) }
    }

    @Test
    fun prevKeys() {
        listOf(Key.PageUp, Key.DirectionLeft, Key.DirectionUp)
            .forEach { assertEquals(it.toString(), PageTurn.PREV, pageTurnFor(it)) }
        assertEquals(PageTurn.PREV, pageTurnFor(Key.Spacebar, shift = true))
    }

    @Test
    fun otherKeysAreIgnored() {
        assertNull(pageTurnFor(Key.A))
        assertNull(pageTurnFor(Key.Escape))
    }
}
