package com.musigatto.fosscore.musicxml

/**
 * Puts back, in the MEI that Verovio exports, what its MusicXML import drops: the placement of
 * lyrics above the staff and the font size of the title, composer and other page-header lines.
 * Verovio itself is used as released, without any patch.
 */
internal object MeiFixes {
    private val PG_HEAD = Regex("""<pgHead\b[^>]*>(.*?)</pgHead>""", RegexOption.DOT_MATCHES_ALL)
    private val REND = Regex("""<rend\b""")
    private val UNPLACED_VERSE = Regex("""<verse\b(?![^>]*\bplace=)""")

    // On a screen page a header line as large as on paper (a 24 pt title over 10 pt lyrics) takes
    // the whole width: the largest line is brought down to this size, the others in proportion.
    const val MAX_HEADER_PERCENT = 150

    /** [creditSizes] for a screen: scaled down together so the largest is at most [MAX_HEADER_PERCENT]. */
    fun forScreen(creditSizes: List<Int?>): List<Int?> {
        val largest = creditSizes.filterNotNull().maxOrNull() ?: return creditSizes
        if (largest <= MAX_HEADER_PERCENT) return creditSizes
        return creditSizes.map { size -> size?.let { Math.round(it * MAX_HEADER_PERCENT.toFloat() / largest) } }
    }

    /** Whether [apply] would change anything, so the MEI round trip can be skipped otherwise. */
    fun needed(lyricsAbove: Boolean, creditSizes: List<Int?>): Boolean =
        lyricsAbove || creditSizes.any { it != null }

    /**
     * [mei] with every verse drawn above the staff when [lyricsAbove], and the n-th `<rend>` of
     * the first page header sized to `creditSizes[n]` percent.
     */
    fun apply(mei: String, lyricsAbove: Boolean, creditSizes: List<Int?>): String {
        var out = if (lyricsAbove) mei.replace(UNPLACED_VERSE, "<verse place=\"above\"") else mei
        val head = PG_HEAD.find(out) ?: return out
        var index = 0
        val sized = REND.replace(head.groupValues[1]) { m ->
            val size = creditSizes.getOrNull(index++)
            if (size == null) m.value else "${m.value} fontsize=\"$size%\""
        }
        out = out.replaceRange(head.groups[1]!!.range, sized)
        return out
    }
}
