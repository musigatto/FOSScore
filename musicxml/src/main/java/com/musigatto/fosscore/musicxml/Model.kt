package com.musigatto.fosscore.musicxml

/**
 * One part of the score (voice, piano, drums...).
 *
 * [visibleInFile] is false when the author hid every staff of the part (MuseScore "hide staff"):
 * a good default for the parts shown. [program] is the General MIDI program (1-128) written in
 * the file, [drums] is true on MIDI channel 10.
 */
data class Part(
    val id: String,
    val name: String,
    val visibleInFile: Boolean = true,
    val program: Int? = null,
    val drums: Boolean = false
)

/** Where lyrics are drawn. */
enum class LyricsPlacement {
    /** As the file asks (most of its lyrics above or below the staff). */
    FILE,
    ABOVE,
    BELOW,

    /** Below the lowest staff of the part: for a piano part, under the left hand. */
    BOTTOM
}

/** Key signature: [fifths] sharps (positive) or flats (negative), and the mode as written ("major", "minor"...). */
data class KeySignature(val fifths: Int, val mode: String? = null)

/**
 * The details of a score that a library can show and sort by, read from the file. Every field
 * is null when the file does not give it.
 */
data class ScoreInfo(
    val title: String? = null,
    val subtitle: String? = null,
    val composer: String? = null,
    val lyricist: String? = null,
    val arranger: String? = null,
    val key: KeySignature? = null,
    /** First time signature, such as "4/4" or "6/8". */
    val time: String? = null,
    /** First tempo in quarter notes per minute. */
    val tempo: Double? = null,
    val measures: Int = 0
)

/** What to change in the score before it is laid out. */
data class ScoreFilter(
    /** Ids of the parts to keep; empty keeps them all. */
    val parts: Set<String> = emptySet(),
    val lyrics: LyricsPlacement = LyricsPlacement.FILE,
    /** General MIDI program (1-128) per part id, replacing the file's. */
    val programs: Map<String, Int> = emptyMap()
)
