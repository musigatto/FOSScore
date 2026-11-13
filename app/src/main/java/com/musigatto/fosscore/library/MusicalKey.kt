package com.musigatto.fosscore.library

import java.text.Normalizer

/**
 * A key a score is in: a closed list of 42 (the 7 notes, natural, flat or sharp, major or minor).
 * Stored as [code], language-neutral (`G`, `Gm`, `Bb`, `F#m`): the names of the notes ("Sol",
 * "G") only matter when showing it. [note] is 0 for C to 6 for B; [accidental] is -1 (flat), 0 or
 * +1 (sharp).
 */
enum class MusicalKey(val note: Int, val accidental: Int, val minor: Boolean) {
    C(0, 0, false), C_MINOR(0, 0, true), C_FLAT(0, -1, false), C_FLAT_MINOR(0, -1, true), C_SHARP(0, 1, false), C_SHARP_MINOR(0, 1, true),
    D(1, 0, false), D_MINOR(1, 0, true), D_FLAT(1, -1, false), D_FLAT_MINOR(1, -1, true), D_SHARP(1, 1, false), D_SHARP_MINOR(1, 1, true),
    E(2, 0, false), E_MINOR(2, 0, true), E_FLAT(2, -1, false), E_FLAT_MINOR(2, -1, true), E_SHARP(2, 1, false), E_SHARP_MINOR(2, 1, true),
    F(3, 0, false), F_MINOR(3, 0, true), F_FLAT(3, -1, false), F_FLAT_MINOR(3, -1, true), F_SHARP(3, 1, false), F_SHARP_MINOR(3, 1, true),
    G(4, 0, false), G_MINOR(4, 0, true), G_FLAT(4, -1, false), G_FLAT_MINOR(4, -1, true), G_SHARP(4, 1, false), G_SHARP_MINOR(4, 1, true),
    A(5, 0, false), A_MINOR(5, 0, true), A_FLAT(5, -1, false), A_FLAT_MINOR(5, -1, true), A_SHARP(5, 1, false), A_SHARP_MINOR(5, 1, true),
    B(6, 0, false), B_MINOR(6, 0, true), B_FLAT(6, -1, false), B_FLAT_MINOR(6, -1, true), B_SHARP(6, 1, false), B_SHARP_MINOR(6, 1, true);

    /** `C`, `Cm`, `Cb`, `C#m`: what `sheet_keys.key` holds. */
    val code: String
        get() = LETTERS[note] + when (accidental) {
            -1 -> "b"
            1 -> "#"
            else -> ""
        } + if (minor) "m" else ""

    /** The sign after the note's name when shown: ♭, ♯ or nothing. */
    val sign: String
        get() = when (accidental) {
            -1 -> "♭"
            1 -> "♯"
            else -> ""
        }

    companion object {
        private const val LETTERS = "CDEFGAB"
        private val SOLFEGE = listOf("do", "re", "mi", "fa", "sol", "la", "si")
        private val MARKS = Regex("\\p{M}")
        private val WORDS = Regex("[\\s-]+")

        fun ofCode(code: String): MusicalKey? = entries.firstOrNull { it.code == code }

        /**
         * The key an old free text names, as people write it: "F", "Fm", "Bb", "F#m", "C major",
         * "A minor", "Do M", "la mineur", "Si bémol majeur", "Fa♯ m", "Re menor". Null when it is
         * not one ("coucou").
         */
        fun parse(text: String): MusicalKey? {
            // accents off (Ré, dièse), case kept: "m" is minor and "M" major
            var t = Normalizer.normalize(text.trim(), Normalizer.Form.NFD).replace(MARKS, "")
                .replace('♭', 'b').replace('♯', '#')
            for ((word, sign) in ACCIDENTAL_WORDS) t = t.replace(Regex("\\s*\\b$word\\b", RegexOption.IGNORE_CASE), sign)
            val tokens = t.split(WORDS).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            // the note, its sign glued to it or apart, then the mode
            var head = tokens[0]
            var rest = tokens.drop(1)
            val (note, afterNote) = noteOf(head) ?: return null
            var accidental = 0
            var tail = afterNote
            if (tail.startsWith("#") || tail.startsWith("b")) {
                accidental = if (tail[0] == '#') 1 else -1
                tail = tail.substring(1)
            } else if (tail.isEmpty() && rest.isNotEmpty() && (rest[0] == "#" || rest[0] == "b")) {
                accidental = if (rest[0] == "#") 1 else -1
                rest = rest.drop(1)
            }
            val mode = (listOf(tail) + rest).filter { it.isNotEmpty() }
            val minor = when {
                mode.isEmpty() -> false
                mode.size > 1 -> return null
                else -> minorOf(mode[0]) ?: return null
            }
            return entries.first { it.note == note && it.accidental == accidental && it.minor == minor }
        }

        // the note at the start of [token] (a letter A-G, or do/ré/mi/fa/sol/la/si/ut) and what follows it
        private fun noteOf(token: String): Pair<Int, String>? {
            val lower = token.lowercase()
            val solfege = (SOLFEGE + "ut").sortedByDescending { it.length }.firstOrNull { lower.startsWith(it) }
            if (solfege != null) {
                val note = if (solfege == "ut") 0 else SOLFEGE.indexOf(solfege)
                return note to token.substring(solfege.length)
            }
            val at = LETTERS.indexOf(token[0].uppercaseChar())
            if (at < 0) return null
            return at to token.substring(1)
        }

        // "m" and "M" by their case; the words in any case
        private fun minorOf(word: String): Boolean? = when {
            word == "m" -> true
            word == "M" -> false
            word.lowercase() in MINOR_WORDS -> true
            word.lowercase() in MAJOR_WORDS -> false
            else -> null
        }

        private val MINOR_WORDS = setOf("min", "minor", "mineur", "menor", "moll")
        private val MAJOR_WORDS = setOf("maj", "major", "majeur", "mayor", "dur")
        private val ACCIDENTAL_WORDS = listOf("diese" to "#", "sostenido" to "#", "sharp" to "#", "bemol" to "b", "flat" to "b")
    }
}

/**
 * The keys an old `sheets.musicalKey` text names, in its order, and what is left of it that names no
 * key ("coucou"), so that it can be kept elsewhere. "F" is one key; "F, Gm" or "F / Gm" two.
 */
fun splitKeyText(text: String): Pair<List<MusicalKey>, String> {
    val whole = text.trim()
    if (whole.isEmpty()) return emptyList<MusicalKey>() to ""
    MusicalKey.parse(whole)?.let { return listOf(it) to "" }
    val keys = mutableListOf<MusicalKey>()
    val left = mutableListOf<String>()
    for (part in whole.split(',', ';', '/').map { it.trim() }.filter { it.isNotEmpty() }) {
        val key = MusicalKey.parse(part)
        if (key == null) left += part else if (key !in keys) keys += key
    }
    return keys to left.joinToString(", ")
}
