package com.musigatto.fosscore.musicxml

import kotlin.math.abs
import org.json.JSONObject

/**
 * How to lay out the score: pages of [width] × [height] pixels (the viewport), music at
 * [scale] percent of Verovio's default size, transposed by [semitones], showing [parts] (empty
 * shows them all) with lyrics placed as [lyrics], keeping the lyrics of the parts left out when
 * [hiddenLyrics]. [marginTop] and [marginBottom] pixels of every page stay free of music, for
 * bars the viewer draws over the page.
 */
data class LayoutSpec(
    val width: Int,
    val height: Int,
    val scale: Int = 100,
    val semitones: Int = 0,
    val parts: Set<String> = emptySet(),
    val lyrics: LyricsPlacement = LyricsPlacement.FILE,
    val hiddenLyrics: Boolean = false,
    val marginTop: Int = 0,
    val marginBottom: Int = 0
) {
    init {
        require(width > 0 && height > 0) { "empty viewport" }
        require(marginTop >= 0 && marginBottom >= 0 && marginTop + marginBottom < height) { "margins larger than the page" }
        require(scale in MIN_SCALE..MAX_SCALE) { "scale out of range: $scale" }
        require(semitones in -12..12) { "transposition out of range: $semitones" }
    }

    companion object {
        const val MIN_SCALE = 10
        const val MAX_SCALE = 400

        // Verovio draws a five-line staff 72 px high at scale 100.
        private const val STAFF_PX_AT_100 = 72f

        // A comfortable staff on a tablet held at arm's length on a music stand.
        private const val STAFF_MM = 7f

        /**
         * A starting music size for a screen of [widthPx] × [heightPx] at [densityDpi]: staves
         * about 7 mm high, but never more than 1/14 of the shorter side (on a phone, 7 mm would
         * leave one measure per line).
         *
         * Use `DisplayMetrics.densityDpi`, not `xdpi`: some ROMs report a wrong `xdpi` (the
         * Galaxy Tab 3 gave a 2.5 times too large size).
         */
        fun defaultScale(widthPx: Int, heightPx: Int, densityDpi: Int): Int {
            val staffPx = minOf(STAFF_MM / 25.4f * densityDpi, minOf(widthPx, heightPx) / 14f)
            return Math.round(staffPx / STAFF_PX_AT_100 * 100f).coerceIn(20, 200)
        }
    }
}

/**
 * What to play: the parts heard ([audible], null hears the parts shown), instruments replacing
 * the file's (General MIDI program per part id), and a tempo factor (1.0 = as written).
 */
data class SoundSpec(
    val audible: Set<String>? = null,
    val programs: Map<String, Int> = emptyMap(),
    val tempo: Double = 1.0
)

/** Verovio options (JSON) for a [LayoutSpec]. */
internal object VerovioOptions {
    // Page margins in Verovio units (1/10 mm at scale 100).
    private const val MARGIN = 40

    // Verovio's default (1.2) let words touch in narrow measures of real songs.
    private const val LYRIC_WORD_SPACE = 2.5

    // Tempo factors outside this range make no musical sense and stress the MIDI player.
    const val MIN_TEMPO = 0.2
    const val MAX_TEMPO = 4.0

    fun layout(spec: LayoutSpec): JSONObject = JSONObject().apply {
        // Verovio page units are 1/10 mm at scale 100: this maps one page to the viewport.
        put("pageWidth", (spec.width * 100 / spec.scale).coerceIn(100, 100_000))
        put("pageHeight", (spec.height * 100 / spec.scale).coerceIn(100, 60_000))
        put("scale", spec.scale)
        put("pageMarginLeft", MARGIN)
        put("pageMarginRight", MARGIN)
        put("pageMarginTop", MARGIN + spec.marginTop * 100 / spec.scale)
        put("pageMarginBottom", MARGIN + spec.marginBottom * 100 / spec.scale)
        put("breaks", "auto")
        put("footer", "none")
        put("header", "auto")
        put("adjustPageHeight", false)
        put("svgViewBox", true)
        // No indentation: about half the size, so MuPDF parses it faster.
        put("svgFormatRaw", true)
        put("transpose", interval(spec.semitones))
        put("lyricWordSpace", LYRIC_WORD_SPACE)
    }

    /** The cheapest layout (one endless system), for a toolkit that is never drawn. */
    fun unpaged(semitones: Int): JSONObject =
        JSONObject().put("transpose", interval(semitones)).put("breaks", "none")

    private val INTERVALS = listOf("P1", "m2", "M2", "m3", "M3", "P4", "A4", "P5", "m6", "M6", "m7", "M7", "P8")

    /** Verovio's transposition interval for [semitones] (-12..12), "" for none. */
    fun interval(semitones: Int): String {
        if (semitones == 0) return ""
        val name = INTERVALS[abs(semitones).coerceAtMost(12)]
        return if (semitones < 0) "-$name" else name
    }
}
