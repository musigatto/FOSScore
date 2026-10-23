package com.musigatto.fosscore.musicxml

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts Verovio's SVG to MuPDF's SVG reader, which ignores CSS, needs explicit sizes and has
 * no SMuFL music font.
 */
internal object SvgForMuPdf {
    private val STYLE = Regex("<style.*?</style>", RegexOption.DOT_MATCHES_ALL)
    private val UNSTROKED = Regex("<(path|rect|polygon|polyline|ellipse)(?![^>]*\\bstroke=)")
    private val HIDDEN = Regex("""<([a-zA-Z]+)\b[^>]*\bvisibility="hidden"[^>]*>""")
    private val VERSE = Regex("""<g\b[^>]*\bclass="verse"[^>]*>""")

    /**
     * - the nested `definition-scale` <svg> gets width/height 100% (otherwise nothing is drawn);
     * - the CSS rule `path, rect... {stroke: currentColor}` becomes an attribute (otherwise staff
     *   lines, stems and bar lines are missing);
     * - the <style> blocks (including the embedded web font) are dropped;
     * - what Verovio marks `visibility="hidden"` (invisible notes) is dropped, but for its lyrics:
     *   MuPDF would draw it.
     */
    fun fix(svg: String): String = dropHidden(svg)
        .replace(STYLE, "")
        .replace("<svg class=\"definition-scale\"", "<svg class=\"definition-scale\" width=\"100%\" height=\"100%\"")
        .replace(UNSTROKED) { "<${it.groupValues[1]} stroke=\"currentColor\"" }

    /**
     * [svg] without the elements marked `visibility="hidden"`, except their lyrics: Verovio
     * writes a note's verses inside the note, and an invisible note carrying lyrics (a hidden
     * vocal line) must still show its words.
     */
    fun dropHidden(svg: String): String {
        val out = StringBuilder(svg.length)
        var from = 0
        while (true) {
            val hidden = HIDDEN.find(svg, from) ?: break
            out.append(svg, from, hidden.range.first)
            if (hidden.value.endsWith("/>")) {
                from = hidden.range.last + 1
                continue
            }
            from = endOf(svg, hidden.groupValues[1], hidden.range.last + 1)
            // the verses it holds stay, the rest of it goes
            var at = hidden.range.last + 1
            while (true) {
                val verse = VERSE.find(svg, at)?.takeIf { it.range.first < from } ?: break
                val end = endOf(svg, "g", verse.range.last + 1)
                out.append(svg, verse.range.first, end)
                at = end
            }
        }
        return out.append(svg, from, svg.length).toString()
    }

    // the opening tag of each element name, compiled once: a page can hide hundreds of notes
    private val OPENS = ConcurrentHashMap<String, Regex>()

    // where the element opened just before [start] closes, counting the same tags nested in it
    private fun endOf(svg: String, tag: String, start: Int): Int {
        val open = OPENS.getOrPut(tag) { Regex("<$tag\\b[^>]*>") }
        val close = "</$tag>"
        var depth = 1
        var at = start
        while (depth > 0) {
            val c = svg.indexOf(close, at)
            if (c < 0) return svg.length
            val o = open.find(svg, at)
            if (o != null && o.range.first < c) {
                if (!o.value.endsWith("/>")) depth++
                at = o.range.last + 1
            } else {
                depth--
                at = c + close.length
            }
        }
        return at
    }
}

/**
 * Verovio writes some music symbols as text in the SMuFL font (the note of a tempo mark,
 * "♩ = 96"). MuPDF has no such font, so the symbol would be missing. This draws those glyphs as
 * paths instead, taken from Verovio's own font data, and moves the rest of the text after them.
 *
 * Only glyphs at the start of a text element are handled, which covers tempo marks.
 */
internal class SmuflGlyphs(private val dataDir: File) {
    private class Glyph(val path: String, val advance: Float)

    private val glyphs = mutableMapOf<String, Glyph?>()
    private val advances = mutableMapOf<String, Map<String, Float>>()

    fun inline(svg: String): String {
        if (!svg.contains("font-family=\"")) return svg
        return LEADING_GLYPHS.replace(svg) { m ->
            val (x, y, rest, wrappers, font, size, text) = m.destructured
            val parts = codePoints(text).map { code -> glyph(font, "%04X".format(code)) }
            if (parts.any { it == null }) return@replace m.value
            val scale = size.toFloat() / 1000f
            var cursor = x.toFloat()
            val paths = StringBuilder()
            for (g in parts.filterNotNull()) {
                paths.append("<g transform=\"translate($cursor, $y) scale($scale, $scale)\">")
                    .append("<path transform=\"scale(1,-1)\" d=\"").append(g.path).append("\"/></g>")
                cursor += g.advance * scale
            }
            "$paths<text x=\"$cursor\" y=\"$y\"$rest>$wrappers"
        }
    }

    // String.codePoints() needs Android 7.
    private fun codePoints(text: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            out += code
            i += Character.charCount(code)
        }
        return out
    }

    private fun glyph(font: String, code: String): Glyph? = glyphs.getOrPut("$font/$code") {
        val file = File(dataDir, "$font/$code.xml")
        if (!file.isFile) return@getOrPut null
        val path = PATH_D.find(file.readText())?.groupValues?.get(1) ?: return@getOrPut null
        Glyph(path, advances(font)[code] ?: DEFAULT_ADVANCE)
    }

    private fun advances(font: String): Map<String, Float> = advances.getOrPut(font) {
        val file = File(dataDir, "$font.xml")
        if (!file.isFile) return@getOrPut emptyMap()
        ADVANCE.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].toFloat() }
    }

    companion object {
        // In font units (1000 per em): about the width of a notehead.
        private const val DEFAULT_ADVANCE = 300f

        // <text x y ...> then wrapper tspans, then the glyph tspan as the first content.
        private val LEADING_GLYPHS = Regex(
            """<text x="(-?[\d.]+)" y="(-?[\d.]+)"([^>]*)>((?:<tspan[^>]*>)*)<tspan font-family="([A-Za-z]+)" font-size="([\d.]+)px">([^<]+)</tspan>"""
        )
        private val PATH_D = Regex(""" d="([^"]+)"""")
        private val ADVANCE = Regex("""<g c="([0-9A-F]{4})"[^>]*h-a-x="([\d.]+)"""")
    }
}
