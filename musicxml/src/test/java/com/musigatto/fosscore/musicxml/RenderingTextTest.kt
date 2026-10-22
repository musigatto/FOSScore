package com.musigatto.fosscore.musicxml

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text transformations around Verovio: MEI fixes, SVG fixes for MuPDF, page geometry. */
class RenderingTextTest {

    @Test
    fun headerLinesAreBroughtDownTogetherForTheScreen() {
        assertEquals(listOf(150, null, 68), MeiFixes.forScreen(listOf(220, null, 100)))
        assertEquals(listOf(120, null), MeiFixes.forScreen(listOf(120, null)))
        assertEquals(listOf(null), MeiFixes.forScreen(listOf(null)))
    }

    @Test
    fun meiFixesPlaceVersesAboveAndSizeHeaderLines() {
        val mei = """<mei><pgHead><rend xml:id="r1">Title</rend><rend xml:id="r2">Composer</rend></pgHead>""" +
            """<verse xml:id="v1" n="1"/><verse place="below" n="2"/></mei>"""
        val out = MeiFixes.apply(mei, lyricsAbove = true, creditSizes = listOf(220, null))
        assertTrue("""<rend fontsize="220%" xml:id="r1">""" in out)
        assertTrue("""<rend xml:id="r2">""" in out)
        assertTrue("""<verse place="above" xml:id="v1" n="1"/>""" in out)
        // A verse that already has a place keeps it (no duplicate attribute).
        assertTrue("""<verse place="below" n="2"/>""" in out)
    }

    @Test
    fun meiFixesOnlyWhenNeeded() {
        assertFalse(MeiFixes.needed(false, listOf(null, null)))
        assertTrue(MeiFixes.needed(true, emptyList()))
        assertTrue(MeiFixes.needed(false, listOf(null, 150)))
        val mei = "<mei><verse n=\"1\"/></mei>"
        assertEquals(mei, MeiFixes.apply(mei, false, listOf(200)))
    }

    @Test
    fun svgIsAdaptedToMuPdf() {
        val svg = """<svg><style>path {stroke: currentColor}</style>""" +
            """<svg class="definition-scale" viewBox="0 0 10 10"><path d="M0 0"/><rect stroke="red"/></svg></svg>"""
        val out = SvgForMuPdf.fix(svg)
        assertFalse("<style" in out)
        assertTrue("""<svg class="definition-scale" width="100%" height="100%" viewBox""" in out)
        assertTrue("""<path stroke="currentColor" d="M0 0"/>""" in out)
        assertTrue("""<rect stroke="red"/>""" in out)
    }

    @Test
    fun smuflTextGlyphsBecomePaths() {
        val dir = Files.createTempDirectory("verovio-data").toFile()
        try {
            File(dir, "Leipzig").mkdirs()
            File(dir, "Leipzig/E1D5.xml").writeText("""<symbol id="E1D5" d="M0 0 L10 10"/>""")
            File(dir, "Leipzig.xml").writeText("""<font><g c="E1D5" n="metNoteQuarterUp" h-a-x="400"/></font>""")
            val svg = """<text x="100" y="200" font-size="0px"><tspan font-size="40px">""" +
                """<tspan font-family="Leipzig" font-size="1000px">""" + "" + """</tspan> = 96</tspan></text>"""
            val out = SmuflGlyphs(dir).inline(svg)
            assertTrue("""<g transform="translate(100.0, 200) scale(1.0, 1.0)"><path transform="scale(1,-1)" d="M0 0 L10 10"/></g>""" in out)
            // The rest of the text moves after the glyph's advance.
            assertTrue("""<text x="500.0" y="200" font-size="0px"><tspan font-size="40px"> = 96</tspan>""" in out)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownGlyphsAreLeftAsText() {
        val svg = """<text x="1" y="2"><tspan font-family="Leipzig" font-size="10px">X</tspan></text>"""
        assertEquals(svg, SmuflGlyphs(File("does-not-exist")).inline(svg))
    }

    @Test
    fun pageGeometryFindsNoteheadsAndTheirSystem() {
        val svg = """<svg viewBox="0 0 2100 2970"><svg class="definition-scale" color="black" viewBox="0 0 21000 29700">""" +
            """<g class="page-margin" transform="translate(400, 400)">""" +
            """<g class="system"><g class="staff"><path d="M0 100 L900 100" stroke-width="13"/>""" +
            """<path d="M0 280 L900 280" stroke-width="13"/><path d="M0 820 L900 820" stroke-width="13"/></g>""" +
            """<g id="n1" class="note"><g class="notehead"><use xlink:href="#E0A4" transform="translate(300, 460)"/></g></g>""" +
            """<g id="n2" class="note" visibility="hidden"><g class="stem"/></g>""" +
            """</g></g></svg></svg>"""
        val page = PageGeometry.parse(svg)
        assertEquals(21000f, page.width)
        assertEquals(setOf("n1", "n2"), page.noteIds)
        val head = page.head("n1")
        assertNotNull(head)
        head!!
        assertEquals(300f + 400f + 180f * 0.6f, head.x, 0.01f)
        assertEquals(860f, head.y, 0.01f)
        assertEquals(500f, head.top, 0.01f)
        assertEquals(1220f, head.bottom, 0.01f)
        assertEquals(180f, head.space, 0.01f)
        assertNull(page.head("n2"))
    }
}
