package com.musigatto.fosscore.ui.library

import com.musigatto.fosscore.library.Sheet
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetFilterTest {
    private fun sheet(title: String = "", composer: String = "", musicalKey: String = "", tags: String = "") =
        Sheet(fileName = "", path = "", hash = "", title = title, composer = composer, genre = "", musicalKey = musicalKey, tags = tags)

    private val sheets = listOf(
        sheet(title = "Fantasía en re menor", composer = "Ludwig van Beethoven"),
        sheet(title = "Clave de sol", composer = "Astor Piazzolla", tags = "tango")
    )

    @Test
    fun matchesIgnoringAccentsAndCase() {
        assertEquals(1, filterSheets(sheets, "beethoven").size)
        assertEquals(1, filterSheets(sheets, "RE MENOR").size)
    }

    @Test
    fun matchesTokensAcrossFields() {
        assertEquals(1, filterSheets(sheets, "beethoven menor").size)
        assertEquals(1, filterSheets(sheets, "tango piazzolla").size)
    }

    @Test
    fun emptyQueryReturnsAll() {
        assertEquals(sheets, filterSheets(sheets, ""))
        assertEquals(sheets, filterSheets(sheets, "   "))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList<Sheet>(), filterSheets(sheets, "chopin"))
    }
}