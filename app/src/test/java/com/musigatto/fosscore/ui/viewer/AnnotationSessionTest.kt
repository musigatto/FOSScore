package com.musigatto.fosscore.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session snapshot behind ✕ (issue #50): Cancel must reach the start of the session, not the
 * last ten undo steps. */
class AnnotationSessionTest {

    private fun edit() = PageEdit(stamps = emptyList(), strokes = emptyList())

    @Test
    fun nothingToDiscardBeforeTheFirstEdit() {
        assertFalse(AnnotationSession().hasChanges)
    }

    @Test
    fun keepsTheFirstStateOfEachPage() {
        val session = AnnotationSession()
        val start = edit()
        session.note(3, start)
        // a second edit of the same page must not overwrite the state the session began with
        session.note(3, edit())
        assertEquals(listOf(3), session.pages().map { it.first })
        assertSame(start, session.pages().single().second)
    }

    @Test
    fun everyPageTouchedIsKeptOnce() {
        val session = AnnotationSession()
        session.note(2, edit())
        session.note(5, edit())
        assertEquals(listOf(2, 5), session.pages().map { it.first })
    }

    @Test
    fun clearEndsTheSession() {
        val session = AnnotationSession()
        session.note(0, edit())
        session.clear()
        assertFalse(session.hasChanges)
        assertTrue(session.pages().isEmpty())
    }
}
