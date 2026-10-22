package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the loupe sits for a finger at a given point (screen px). It hangs above the finger so the
 * hand does not cover the spot, and it must never leave the viewport: annotating the top of a page
 * is the common case.
 */
class LoupeCenterTest {

    private val radius = 150f
    private val margin = 20f
    private val viewport = Size(1000f, 2000f)

    @Test
    fun `it hangs above the finger`() {
        val c = loupeCenter(Offset(500f, 1000f), radius, margin, viewport)
        assertEquals(500f, c.x, 0.01f)
        assertEquals(1000f - radius * LOUPE_LIFT, c.y, 0.01f)
    }

    @Test
    fun `it stays inside the top edge`() {
        val c = loupeCenter(Offset(500f, 10f), radius, margin, viewport)
        assertEquals(radius + margin, c.y, 0.01f)
    }

    @Test
    fun `it stays inside the left and right edges`() {
        assertEquals(radius + margin, loupeCenter(Offset(0f, 1000f), radius, margin, viewport).x, 0.01f)
        assertEquals(
            viewport.width - radius - margin,
            loupeCenter(Offset(1000f, 1000f), radius, margin, viewport).x,
            0.01f
        )
    }
}
