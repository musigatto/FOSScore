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
    fun halfPage_backFromTheFirstTurn() {
        val turned = half(0).next()
        assertTrue(turned.canPrev)
        assertEquals(half(0), turned.prev())                        // B-A back to A-A
        assertFalse(half(0).canPrev)
    }

    @Test
    fun halfPage_follow_turnsTheTopThenTheBottom() {
        var f = half(0)
        // the music and the music ahead on page 1: nothing turns, even in the lower half
        assertEquals(f, f.follow(target = 0, aheadInLowerPart = true, onScreen = true, ahead = 0))
        // the music ahead gets to page 2: its top shows above the end of page 1
        f = f.follow(target = 0, aheadInLowerPart = false, onScreen = true, ahead = 1)
        assertEquals(0, f.page); assertTrue(f.halfTurned)
        // the music ends page 1, then starts page 2 with the music ahead still at its top: stays
        assertEquals(f, f.follow(target = 0, aheadInLowerPart = false, onScreen = true, ahead = 1))
        assertEquals(f, f.follow(target = 1, aheadInLowerPart = false, onScreen = true, ahead = 1))
        // the music ahead gets to the lower part of page 2: the whole page 2
        f = f.follow(target = 1, aheadInLowerPart = true, onScreen = true, ahead = 1)
        assertEquals(1, f.page); assertFalse(f.halfTurned)
        // the last page has no next half
        val last = half(5)
        assertEquals(last, last.follow(target = 5, aheadInLowerPart = true, onScreen = true, ahead = 5))
    }

    @Test
    fun halfPage_withoutLookingAhead_turnsWhenTheMusicGetsThere() {
        // the music itself gets to page 2: its top shows, where the music is
        val f = half(0).follow(target = 1, aheadInLowerPart = false, onScreen = false, ahead = 1)
        assertEquals(0, f.page); assertTrue(f.halfTurned)
        // then to its lower part: the whole page 2
        assertEquals(half(1), f.follow(target = 1, aheadInLowerPart = true, onScreen = false, ahead = 1))
    }

    @Test
    fun follow_jumpsToAPageOffScreen() {
        assertEquals(3, single(0).follow(target = 3, aheadInLowerPart = false, onScreen = false).page)
        val f = half(0, halfTurned = true).follow(target = 4, aheadInLowerPart = false, onScreen = false)
        assertEquals(4, f.page); assertFalse(f.halfTurned)
        val twoUp = PageFlow(0, false, false, twoUp = true, isLandscape = true, pageCount = 5)
        assertEquals(0, twoUp.follow(target = 1, aheadInLowerPart = false, onScreen = true).page)
    }

    @Test
    fun follow_turnsAheadOfTheMusic() {
        // whole pages: the music ahead is on page 2, the music itself still on page 1
        var f = single(0).follow(target = 0, aheadInLowerPart = false, onScreen = true, ahead = 1)
        assertEquals(1, f.page)
        // the music ends page 1, off screen now: page 2 stays (it went back and forth on the tablet)
        assertEquals(f, f.follow(target = 0, aheadInLowerPart = false, onScreen = false, ahead = 1))
        // the music gets to page 2
        assertEquals(f, f.follow(target = 1, aheadInLowerPart = false, onScreen = true, ahead = 1))
        // two-up: only once the music ahead is past the second page
        val twoUp = PageFlow(0, false, false, twoUp = true, isLandscape = true, pageCount = 5)
        assertEquals(0, twoUp.follow(target = 1, aheadInLowerPart = false, onScreen = true, ahead = 1).page)
        val turned2 = twoUp.follow(target = 1, aheadInLowerPart = false, onScreen = true, ahead = 2)
        assertEquals(2, turned2.page)
        assertEquals(turned2, turned2.follow(target = 1, aheadInLowerPart = false, onScreen = false, ahead = 2))
    }

    @Test
    fun halfSplit_cutsBetweenSystems() {
        // a page without blank bands: the middle
        assertEquals(HalfSplit(0.5f, 0.5f), HalfSplit.of(emptyList(), emptyList()))
        // this page's band nearest the middle; the next page's band that shows the most above it
        val here = listOf(Band(0.22f, 0.26f), Band(0.46f, 0.52f), Band(0.72f, 0.76f))
        // its second system ends a little lower than this page's: still shown (it was cut off)
        assertEquals(HalfSplit(0.52f, 0.52f), HalfSplit.of(here, listOf(Band(0.24f, 0.28f), Band(0.5f, 0.55f))))
        // its bands are higher: blank paper between the two parts
        assertEquals(HalfSplit(0.46f, 0.42f), HalfSplit.of(here, listOf(Band(0.2f, 0.24f), Band(0.38f, 0.42f))))
        // its first system reaches below this page's middle: this page keeps only its last system
        assertEquals(HalfSplit(0.72f, 0.65f), HalfSplit.of(here, listOf(Band(0.6f, 0.65f))))
        // three systems above four: two systems of the next page show, above the last of this one
        val three = listOf(Band(0.415f, 0.44f), Band(0.641f, 0.665f), Band(0.867f, 0.903f))
        val four = listOf(Band(0.244f, 0.267f), Band(0.47f, 0.489f), Band(0.695f, 0.718f), Band(0.92f, 0.947f))
        assertEquals(HalfSplit(0.641f, 0.489f), HalfSplit.of(three, four))
        // no band of this page low enough in its upper three quarters: cut at its middle band
        assertEquals(HalfSplit(0.46f, 0.42f), HalfSplit.of(listOf(Band(0.46f, 0.52f)), listOf(Band(0.38f, 0.42f), Band(0.53f, 0.56f))))
    }

    @Test
    fun blankBands_leaveOutTheTopMarginAndTheLinesOfAStaff() {
        // rows 0-1 margin, 2-3 music, 4 one blank row (between staff lines), 5 music, 6-8 blank, 9 music
        val blank = booleanArrayOf(true, true, false, false, true, false, true, true, true, false)
        assertEquals(listOf(Band(0.6f, 0.9f)), HalfSplit.bandsOf(blank, minRows = 2))
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

    @Test
    fun visiblePageRange_paginaSuelta() {
        assertEquals(0..0, visiblePageRange(currentPage = 0, pageCount = 9, showSpread = false))
    }

    @Test
    fun visiblePageRange_spread() {
        assertEquals(2..3, visiblePageRange(currentPage = 2, pageCount = 9, showSpread = true))
    }

    @Test
    fun visiblePageRange_ultimaImparColapsa() {
        // 9 páginas: en la 9ª (índice 8) no hay compañera de spread
        assertEquals(8..8, visiblePageRange(currentPage = 8, pageCount = 9, showSpread = true))
    }

    @Test
    fun visiblePageRange_pdfSinPaginas() {
        assertEquals(IntRange.EMPTY, visiblePageRange(currentPage = 0, pageCount = 0, showSpread = false))
    }

    @Test
    fun visiblePageRange_spreadEnLaPrimera() {
        assertEquals(0..1, visiblePageRange(currentPage = 0, pageCount = 2, showSpread = true))
    }
}