package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.geometry.Offset
import com.musigatto.fosscore.library.Stamp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the eraser takes with it. A stamp counts when the trail passes within the eraser's radius of
 * its box: grazing it has to erase it, like the wide red band the user sees while dragging.
 */
class EraserStampsTest {

    private val fit = RectPx(0f, 0f, 400f, 800f)
    private fun stamp(id: Long, x: Float, y: Float, size: Float = 0.05f) =
        Stamp(id = id, sheetHash = "h", page = 0, symbol = "F", x = x, y = y, size = size)

    // two stamps near each other: one at (0.25, 0.5), one at (0.75, 0.5)
    private val page = listOf(stamp(1, 0.25f, 0.5f), stamp(2, 0.75f, 0.5f))

    @Test
    fun `a trail across the page takes every stamp it crosses`() {
        val path = listOf(Offset(0f, 400f), Offset(200f, 400f), Offset(400f, 400f))
        assertEquals(listOf(1L, 2L), stampsTouched(page, fit, path, 8f).map { it.id })
    }

    @Test
    fun `grazing within the radius counts, further away does not`() {
        // stamp 1 is a 40 px box (0.05 * 800) around x = 100: right edge at 120
        val near = listOf(Offset(126f, 400f))   // 6 px away: inside an 8 px radius
        val far = listOf(Offset(140f, 400f))    // 20 px away
        assertEquals(listOf(1L), stampsTouched(page, fit, near, 8f).map { it.id })
        assertEquals(emptyList<Long>(), stampsTouched(page, fit, far, 8f).map { it.id })
    }

    @Test
    fun `an empty trail erases nothing`() {
        assertEquals(emptyList<Long>(), stampsTouched(page, fit, emptyList(), 8f).map { it.id })
    }
}
