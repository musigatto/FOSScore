package com.musigatto.fosscore.musicxml

/**
 * Where the notes and systems of one page are, read from Verovio's SVG. Lets the viewer draw a
 * playback cursor over the page bitmap without rendering the page again.
 *
 * Positions are in SVG units: multiply by `bitmapWidth / width` to get bitmap pixels.
 */
class PageGeometry private constructor(
    /** Every note id on the page, hidden ones included (to know whether a note is on this page). */
    val noteIds: Set<String>,
    private val heads: Map<String, Head>,
    /** Page width in SVG units. */
    val width: Float
) {
    /** Notehead centre ([x], [y]), the vertical extent of its system and the staff [space]. */
    class Head(val x: Float, val y: Float, val top: Float, val bottom: Float, val space: Float)

    /** The visible notehead with this id, or null (hidden note, or not on this page). */
    fun head(id: String): Head? = heads[id]

    companion object {
        private val INNER = Regex("""class="definition-scale"[^>]*viewBox="0 0 ([\d.]+) ([\d.]+)"""")
        private val MARGIN = Regex("""class="page-margin" transform="translate\((-?[\d.]+), (-?[\d.]+)\)"""")
        private val SYSTEM = Regex("""class="system"""")
        private val STAFF_LINES = Regex("""class="staff">((?:<path d="M-?\d+ -?\d+ L-?\d+ -?\d+" stroke-width="\d+"/>)+)""")
        private val LINE_Y = Regex("""<path d="M-?\d+ (-?\d+) L""")
        private val NOTE = Regex("""<g id="([^"]+)" class="note"""")
        private val HEAD = Regex(
            """<g id="([^"]+)" class="note"[^>]*><g class="notehead"><use[^>]*transform="translate\((-?\d+), (-?\d+)\)"""
        )

        // Verovio's default staff space at scale 100 (1/10 mm units), used when a system has no lines.
        private const val DEFAULT_SPACE = 180f

        /**
         * Parses the SVG as Verovio wrote it, with `svgFormatRaw` (one line, before the MuPDF fixes).
         */
        fun parse(svg: String): PageGeometry {
            val innerWidth = INNER.find(svg)?.groupValues?.get(1)?.toFloat() ?: 1f
            val margin = MARGIN.find(svg)
            val mx = margin?.groupValues?.get(1)?.toFloat() ?: 0f
            val my = margin?.groupValues?.get(2)?.toFloat() ?: 0f

            // Vertical extent and staff space of each system, by its start offset in the text.
            class System(val start: Int, val top: Float, val bottom: Float, val space: Float)
            val starts = SYSTEM.findAll(svg).map { it.range.first }.toList()
            val systems = starts.mapIndexed { i, start ->
                val end = starts.getOrElse(i + 1) { svg.length }
                val ys = STAFF_LINES.findAll(svg.substring(start, end))
                    .flatMap { m -> LINE_Y.findAll(m.groupValues[1]).map { it.groupValues[1].toFloat() } }.toList()
                val space = if (ys.size >= 2) ys[1] - ys[0] else DEFAULT_SPACE
                System(start, (ys.minOrNull() ?: 0f) + my, (ys.maxOrNull() ?: 0f) + my, space)
            }

            val heads = HEAD.findAll(svg).associate { m ->
                val system = systems.lastOrNull { it.start < m.range.first }
                val space = system?.space ?: DEFAULT_SPACE
                // The notehead glyph starts at x and is about 1.2 staff spaces wide.
                m.groupValues[1] to Head(
                    m.groupValues[2].toFloat() + mx + space * 0.6f,
                    m.groupValues[3].toFloat() + my,
                    system?.top ?: 0f,
                    system?.bottom ?: 0f,
                    space
                )
            }
            return PageGeometry(NOTE.findAll(svg).map { it.groupValues[1] }.toSet(), heads, innerWidth)
        }
    }
}
