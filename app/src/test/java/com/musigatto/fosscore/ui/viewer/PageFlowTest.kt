package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFlowTest {

    private fun half(page: Int, halfTurned: Boolean = false, pageCount: Int = 6) =
        PageFlow(page, halfTurned, halfEnabled = true, twoUp = false, isLandscape = false, pageCount = pageCount)

    private fun single(page: Int, pageCount: Int = 4) =
        PageFlow(page, halfTurned = false, halfEnabled = false, twoUp = false, isLandscape = false, pageCount = pageCount)

    @Test
    fun halfPage_turns_follow_A_A_B_A_B_B_C_B() {
        var f = half(0)
        assertEquals(0, f.page); assertFalse(f.halfTurned)          // A-A

        f = f.next()
        assertEquals(0, f.page); assertTrue(f.halfTurned)           // B-A (top B, bottom A)

        f = f.next()
        assertEquals(1, f.page); assertFalse(f.halfTurned)          // B-B

        f = f.next()
        assertEquals(1, f.page); assertTrue(f.halfTurned)           // C-B

        f = f.next()
        assertEquals(2, f.page); assertFalse(f.halfTurned)          // C-C
    }

    @Test
    fun twoUp_stepsByTwo_andStopsAtLast() {
        var f = PageFlow(0, false, false, twoUp = true, isLandscape = true, pageCount = 5)
        assertTrue(f.showTwoUp)
        assertEquals(2, f.next().page)
        f = f.next().next()
        assertEquals(4, f.page)
        assertFalse(f.canNext)
        assertEquals(2, f.prev().page)
    }

    @Test
    fun singlePage_stepsByOne() {
        var f = single(0)
        assertEquals(1, f.next().page)
        assertEquals(0, f.prev().page)
    }

    @Test
    fun prev_unwindsHalfTurn_beforeChangingPage() {
        var f = half(1, halfTurned = true)  // top 2, bottom 1
        f = f.prev()
        assertFalse(f.halfTurned)
        assertEquals(1, f.page)
        f = f.prev()
        assertEquals(0, f.page)
    }

    @Test
    fun boundaries_neverLeaveRange() {
        var f = single(0, pageCount = 1)
        assertFalse(f.canNext)
        assertEquals(0, f.next().page)

        f = single(0)
        assertFalse(f.canPrev)
        assertEquals(0, f.prev().page)

        f = half(5, halfTurned = false)  // last page, no next half
        assertFalse(f.canNext)
        assertEquals(5, f.next().page)
    }

    @Test
    fun halfEnabled_disablesTwoUp() {
        val f = PageFlow(2, false, halfEnabled = true, twoUp = true, isLandscape = true, pageCount = 6)
        assertFalse(f.showTwoUp)
        assertTrue(f.showHalf)
    }
}