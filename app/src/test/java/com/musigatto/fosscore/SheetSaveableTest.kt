package com.musigatto.fosscore

import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.score.SheetFormat
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
            tags = "ensayo,clase",
            format = SheetFormat.MUSICXML,
            tempoBpm = 72.0,
            beatUnit = 4,
            timeSignature = "3/4",
            durationS = 312.5,
            openedAt = 1_700_000_000_000L,
            openCount = 9,
            addedAt = 1_600_000_000_000L
        )

        assertEquals(sheet, sheetFromSaveable(sheet.toSaveable()))
    }
}
