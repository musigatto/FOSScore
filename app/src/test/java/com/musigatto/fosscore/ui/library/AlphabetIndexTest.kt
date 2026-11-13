package com.musigatto.fosscore.ui.library

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlphabetIndexTest {
    private val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.SECONDARY }

    @Test
    fun letterWithoutAccentAndAfterPunctuation() {
        assertEquals("E", indexLetter("Élise"))
        assertEquals("R", indexLetter("'Round Midnight"))
        assertEquals("A", indexLetter("au clair de la lune"))
        assertEquals("#", indexLetter("1789"))
        assertEquals("#", indexLetter("..."))
    }

    @Test
    fun groupedByLetterOthersFirstSortedInside() {
        val index = alphabetIndex(
            listOf("Halleluya", "Aux Champs-Élysées", "1789", "Chanson sur ma drôle de vie", "Au clair de la lune", "écho"),
            collator,
        ) { it }

        assertEquals(listOf("#", "A", "C", "E", "H"), index.letters)
        assertEquals(
            listOf(
                IndexedRow.Letter("#"), IndexedRow.Item("1789"),
                IndexedRow.Letter("A"), IndexedRow.Item("Au clair de la lune"), IndexedRow.Item("Aux Champs-Élysées"),
                IndexedRow.Letter("C"), IndexedRow.Item("Chanson sur ma drôle de vie"),
                IndexedRow.Letter("E"), IndexedRow.Item("écho"),
                IndexedRow.Letter("H"), IndexedRow.Item("Halleluya"),
            ),
            index.rows,
        )
        // after "#", 1789, "A" and its two scores
        assertEquals(5, index.rowOf("C"))
        assertNull(index.rowOf("B"))
    }

    @Test
    fun theSameTitleTwiceStaysTwice() {
        val index = alphabetIndex(listOf("Lucie", "Lucie"), collator) { it }
        assertEquals(3, index.rows.size)
    }

    @Test
    fun aTagsScoresPlayInTheOrderTheyAreShown() {
        // by letter, "#" first, and "écho" under E (a plain sort by text would put it after "Z")
        assertEquals(
            listOf("1789", "Au clair de la lune", "écho", "Zoé"),
            alphabetical(listOf("Zoé", "écho", "1789", "Au clair de la lune"), collator) { it },
        )
    }
}
