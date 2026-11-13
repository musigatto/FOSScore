package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNamesTest {
    @Test
    fun spellingsOfOneTagAreTheSameTag() {
        assertTrue(sameTag("Tángo", " tango "))
        assertEquals(listOf("Rock", "jazz"), distinctTagNames(listOf(" Rock", "", "rôck", "jazz", "ROCK")))
    }

    @Test
    fun theTagFieldsTextSplitsOnSpaces() {
        assertEquals(listOf("duo", "practice"), splitTagText(" duo  practice "))
        assertEquals(emptyList<String>(), splitTagText(""))
    }

    @Test
    fun theOldTextKeepsATagWithASpaceInside() {
        assertEquals(listOf("film music", "duo"), splitTagText("film music, duo;"))
    }

    @Test
    fun aTagWrittenOnSeveralScoresBecomesOneTag() {
        val plan = NameBackfill.of(listOf(1L to "Rock duo", 2L to "rock", 3L to "", 4L to "Duo, rôck"), ::splitTagText)
        assertEquals(listOf("Rock", "duo"), plan.names)
        assertEquals(listOf(1L to 0, 1L to 1, 2L to 0, 4L to 1, 4L to 0), plan.links)
    }

    @Test
    fun theComposerTextSplitsIntoPeople() {
        assertEquals(listOf("Léonard Cohen", "Kyle Landry"), splitPeopleText(" Léonard Cohen  / Kyle Landry"))
        assertEquals(listOf("Simon", "Garfunkel", "Bach", "Johann Sebastian"), splitPeopleText("Simon & Garfunkel; Bach, Johann Sebastian"))
        assertEquals(emptyList<String>(), splitPeopleText(" / "))
    }

    @Test
    fun aPersonNamedOnSeveralScoresBecomesOnePerson() {
        val plan = NameBackfill.of(listOf(1L to "Patrick Bruel", 2L to "", 3L to "patrick bruel / Il était une fois"), ::splitPeopleText)
        assertEquals(listOf("Patrick Bruel", "Il était une fois"), plan.names)
        assertEquals(listOf(1L to 0, 3L to 0, 3L to 1), plan.links)
    }

    @Test
    fun aScoreGetsATagOnceEvenIfItsTextRepeatsIt() {
        val plan = NameBackfill.of(listOf(1L to "duo Duo dúo"), ::splitTagText)
        assertEquals(listOf("duo"), plan.names)
        assertEquals(listOf(1L to 0), plan.links)
    }
}
