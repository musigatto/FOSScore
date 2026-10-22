package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tap cycle of the annotation bar tools (spec §5): one tap selects the tool, the next opens its
 * options, the third puts it away. It is the whole state machine behind pen, eraser and stamps.
 */
class ToolTapTest {

    @Test
    fun `a tool that is off turns on without opening its panel`() {
        assertEquals(true to false, nextToolTap(active = false, panelOpen = false))
    }

    @Test
    fun `tapping the selected tool opens its options`() {
        assertEquals(true to true, nextToolTap(active = true, panelOpen = false))
    }

    @Test
    fun `tapping the selected tool with its options open puts it away`() {
        assertEquals(false to false, nextToolTap(active = true, panelOpen = true))
    }
}
