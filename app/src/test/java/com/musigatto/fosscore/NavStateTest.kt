package com.musigatto.fosscore

import com.musigatto.fosscore.library.GroupKind
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.ui.library.MenuView
import com.musigatto.fosscore.ui.viewer.StartPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The views of the side menu replace one another: back never lands on a hidden one. */
class NavStateTest {
    private val score = Sheet(id = 1L, fileName = "a.pdf", path = "/a.pdf", hash = "h", title = "A",
        composer = "", genre = "", musicalKey = "", tags = "",
    )

    @Test
    fun setlistsFromTheLibraryOverAScoreReplaceIt() {
        // the library opened over a score, then Setlists in its menu: back must go to the score,
        // not close a library hidden under the setlists
        val nav = NavState(sheet = score, libraryOpen = true).toGroups(GroupKind.SETLIST)

        assertEquals(GroupKind.SETLIST, nav.groups)
        assertFalse(nav.libraryOpen)
        assertEquals(score, nav.sheet)
    }

    @Test
    fun libraryFromTheSetlistsReplacesThem() {
        val nav = NavState(sheet = score, groups = GroupKind.SETLIST, groupShown = 3).toLibrary()

        assertNull(nav.groups)
        assertTrue(nav.libraryOpen)
        assertEquals(score, nav.sheet)
    }

    @Test
    fun tagsFromTheSetlistsReplaceThem() {
        val nav = NavState(groups = GroupKind.SETLIST, groupShown = 3).toView(MenuView.TAGS)

        assertEquals(GroupKind.TAG, nav.groups)
        // the list of the tags, not the setlist 3's id taken for a tag's
        assertEquals(0L, nav.groupShown)
    }

    @Test
    fun turningToTheNextScoreOfASetlistKeepsTheTopBarAsItWas() {
        val next = score.copy(id = 2L, hash = "h2")
        val playing = NavState(sheet = score, playGroup = 3, playItem = 9)

        assertTrue(playing.toPlace(next, itemId = 10, start = StartPage.FIRST, chromeShown = true).chromeShown)
        assertFalse(playing.toPlace(next, itemId = 10, start = StartPage.FIRST, chromeShown = false).chromeShown)
    }

    @Test
    fun backFromAScoreOfASetlistGoesBackToTheSetlist() {
        val nav = NavState(sheet = score, playGroup = 3, playItem = 9).leaveScore()

        assertEquals(null, nav.sheet)
        assertEquals(GroupKind.SETLIST, nav.groups)
        assertEquals(3L, nav.groupShown)
        // the place just left stays marked in the setlist
        assertEquals(9L, nav.playItem)
    }

    @Test
    fun backFromAScoreOfATagGoesBackToTheTag() {
        val nav = NavState(sheet = score, playKind = GroupKind.TAG, playGroup = 4, playItem = 1).leaveScore()

        assertEquals(null, nav.sheet)
        assertEquals(GroupKind.TAG, nav.groups)
        assertEquals(4L, nav.groupShown)
    }

    @Test
    fun backFromAScoreOfTheLibraryGoesBackToTheLibrary() {
        val nav = NavState(sheet = score, libraryOpen = true).leaveScore()

        assertEquals(null, nav.sheet)
        assertNull(nav.groups)
        assertFalse(nav.libraryOpen)
    }

    @Test
    fun eachViewOfTheMenuOpensItsScreen() {
        val nav = NavState(sheet = score, groups = GroupKind.SETLIST, groupShown = 3)

        // not built yet: nothing changes
        assertEquals(nav, nav.toView(MenuView.PRACTICE))
        assertEquals(nav.toLibrary(), nav.toView(MenuView.LIBRARY))
        assertEquals(nav.toGroups(GroupKind.SETLIST), nav.toView(MenuView.SETLISTS))
        assertEquals(nav.toGroups(GroupKind.TAG), nav.toView(MenuView.TAGS))
        assertEquals(nav.toGroups(GroupKind.PERSON), nav.toView(MenuView.PEOPLE))
        assertEquals(nav.toGroups(GroupKind.GENRE), nav.toView(MenuView.GENRES))
    }

    @Test
    fun backFromAScoreOfAPersonGoesBackToTheListItWasOpenedFrom() {
        // opened from a person's scores as performer (facet 1): back shows that list again
        val nav = NavState(sheet = score, playKind = GroupKind.PERSON, playGroup = 5, playFacet = 1, playItem = 1).leaveScore()

        assertEquals(GroupKind.PERSON, nav.groups)
        assertEquals(5L, nav.groupShown)
        assertEquals(1, nav.groupFacet)
    }

    @Test
    fun anotherViewShowsItsFirstFacet() {
        val nav = NavState(groups = GroupKind.PERSON, groupShown = 5, groupFacet = 1).toView(MenuView.TAGS)

        assertEquals(0, nav.groupFacet)
    }

    @Test
    fun setlistsFromAScoreShowItsSetlist() {
        val nav = NavState(sheet = score, playGroup = 3, playItem = 9).toGroups(GroupKind.SETLIST, 3)

        assertEquals(GroupKind.SETLIST, nav.groups)
        assertEquals(3L, nav.groupShown)
        assertEquals(9L, nav.playItem)
    }
}
