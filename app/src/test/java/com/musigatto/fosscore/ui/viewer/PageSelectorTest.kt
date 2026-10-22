package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSelectorTest {

    @Test
    fun pageIndexFollowsTheTrackAndClampsAtBothEnds() {
        assertEquals(0, pageIndexAt(0f, 1000, 10))
        assertEquals(5, pageIndexAt(500f, 1000, 10))
        assertEquals(9, pageIndexAt(999f, 1000, 10))
        // the right edge belongs to the last page: it must not index one past the end
        assertEquals(9, pageIndexAt(1000f, 1000, 10))
        assertEquals(9, pageIndexAt(5000f, 1000, 10))
        assertEquals(0, pageIndexAt(-40f, 1000, 10))
    }

    @Test
    fun everyDotCenterMapsBackToItsOwnPage() {
        for (page in 0 until 10) {
            assertEquals(page, pageIndexAt(pageMarkX(page, 1000, 10), 1000, 10))
        }
    }

    @Test
    fun unmeasuredTrackAndEmptyDocumentDoNotCrash() {
        assertEquals(0, pageIndexAt(500f, 0, 10))
        assertEquals(0, pageIndexAt(500f, 1000, 0))
    }
}
