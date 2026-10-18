package com.musigatto.fosscore

import com.musigatto.fosscore.library.Sheet
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetSaveableTest {
    @Test
    fun roundTripKeepsEveryField() {
        val sheet = Sheet(
            id = 7L,
            fileName = "bolero.pdf",
            path = "/data/sheets/bolero.pdf",
            hash = "a1b2c3",
            title = "Bolero",
            composer = "Ravel",
            genre = "Orquestal",
            musicalKey = "Do M",
            tags = "ensayo,clase"
        )

        assertEquals(sheet, sheetFromSaveable(sheet.toSaveable()))
    }
}
