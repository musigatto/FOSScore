package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Credits
import com.musigatto.fosscore.library.Sheet
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetFilterTest {
    private fun sheet(id: Long, title: String = "", musicalKey: String = "", comment: String = "") =
        Sheet(id = id, fileName = "", path = "", hash = "", title = title, composer = "", genre = "",
            musicalKey = musicalKey, tags = "", comment = comment)

    private val sheets = listOf(
        sheet(1, title = "Fantasía en re menor", comment = "Slower in the coda"),
        sheet(2, title = "Clave de sol")
    )

    // the tags and the people come from their tables, by score id
    private val tags = mapOf(2L to listOf("Tángo", "duo"))
    private val credits = mapOf(
        1L to Credits(composers = listOf("Ludwig van Beethoven")),
        2L to Credits(composers = listOf("Astor Piazzolla"), performers = listOf("Quinteto Nuevo Tango")),
    )

    @Test
    fun matchesIgnoringAccentsAndCase() {
        assertEquals(1, filterSheets(sheets, "beethoven", tags, credits).size)
        assertEquals(1, filterSheets(sheets, "RE MENOR", tags, credits).size)
    }

    @Test
    fun matchesTokensAcrossFields() {
        assertEquals(1, filterSheets(sheets, "beethoven menor", tags, credits).size)
        assertEquals(1, filterSheets(sheets, "tango piazzolla", tags, credits).size)
    }

    @Test
    fun matchesAScoresTags() {
        assertEquals(listOf(sheets[1]), filterSheets(sheets, "duo", tags))
        assertEquals(emptyList<Sheet>(), filterSheets(sheets, "duo"))
    }

    @Test
    fun matchesAScoresPeopleInEitherRole() {
        assertEquals(listOf(sheets[1]), filterSheets(sheets, "quinteto", tags, credits))
        assertEquals(emptyList<Sheet>(), filterSheets(sheets, "quinteto", tags))
    }

    @Test
    fun matchesAScoresComment() {
        assertEquals(listOf(sheets[0]), filterSheets(sheets, "coda", tags, credits))
    }

    @Test
    fun emptyQueryReturnsAll() {
        assertEquals(sheets, filterSheets(sheets, "", tags, credits))
        assertEquals(sheets, filterSheets(sheets, "   ", tags, credits))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<Sheet>(), filterSheets(sheets, "chopin", tags, credits))
    }
}
