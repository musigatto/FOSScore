package com.musigatto.fosscore.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatLabelTest {

    @Test
    fun theLabelComesFromTheExtensionOfTheCopy() {
        assertEquals("PDF", formatLabel("/data/sheets/0b1c.pdf"))
        assertEquals("MusicXML", formatLabel("/data/sheets/0b1c.musicxml"))
        assertEquals("MusicXML", formatLabel("/data/sheets/0b1c.MXL"))
        // what the importer cannot name stays a PDF, as the viewer opens it
        assertEquals("PDF", formatLabel("/data/sheets/0b1c"))
    }
}
