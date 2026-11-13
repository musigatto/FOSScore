package com.musigatto.fosscore.library

import androidx.annotation.StringRes
import com.musigatto.fosscore.R

/**
 * The genres a library starts with, shown in the app's language ([label]): fifteen broad groups
 * from classical music to rap, few enough to translate into a new language. In the database a
 * default genre is a tag of kind GENRE with this [name][Enum.name] as its `code` (its `name` column
 * holds [english], for a reader of the file). The user removes, renames or adds genres freely: a
 * genre they add or rename is their text, in no language, and a language change leaves it as it is.
 *
 * [aliases] are the words an old free-text genre ("Folk", "Classique", "Música de cine") is
 * recognised by when the library moves to the genres table, in the app's languages; a word not
 * recognised becomes a genre of its own, so nothing is lost.
 */
enum class Genre(@StringRes val label: Int, val english: String, val aliases: List<String>) {
    CLASSICAL(R.string.genre_classical, "Classical", listOf("classique", "clasica", "baroque", "barroco", "romantique", "contemporain", "symphonique", "musique classique", "musica clasica")),
    OPERA(R.string.genre_opera, "Opera & vocal", listOf("opera", "lyrique", "art lyrique", "lirica", "lied", "melodie", "vocal", "chant")),
    SACRED(R.string.genre_sacred, "Sacred & gospel", listOf("sacre", "musique sacree", "religieux", "religious", "religiosa", "musica sacra", "sacred", "gospel", "spiritual", "negro spiritual", "liturgique", "cantique")),
    JAZZ_BLUES(R.string.genre_jazz_blues, "Jazz & blues", listOf("jazz", "blues", "swing", "bebop", "ragtime", "jazz manouche", "dixieland")),
    ROCK_METAL(R.string.genre_rock_metal, "Rock & metal", listOf("rock", "metal", "hard rock", "heavy metal", "punk", "grunge", "rock and roll", "rock'n'roll", "rock'n roll", "rock 'n' roll", "rock n roll", "rock & roll")),
    POP(R.string.genre_pop, "Pop", listOf("pop", "pop music", "musique pop")),
    CHANSON(R.string.genre_chanson, "Chanson & variety", listOf("chanson", "chanson francaise", "variete", "varietes", "variete francaise", "variety", "cancion", "variedades", "cantautor")),
    FOLK_COUNTRY(R.string.genre_folk_country, "Folk & country", listOf("folk", "country", "bluegrass", "folk song")),
    TRADITIONAL_WORLD(R.string.genre_traditional_world, "Traditional & world", listOf("traditionnel", "traditional", "tradicional", "trad", "world", "world music", "musique du monde", "musiques du monde", "musica del mundo", "celtique", "celtic", "klezmer", "folklore")),
    LATIN(R.string.genre_latin, "Latin", listOf("latin", "latino", "latina", "salsa", "tango", "bossa nova", "samba", "flamenco", "musique latine", "musica latina")),
    SOUL_FUNK(R.string.genre_soul_funk, "Soul, R&B & funk", listOf("soul", "funk", "r&b", "rnb", "rhythm and blues", "rhythm & blues", "motown")),
    HIP_HOP(R.string.genre_hip_hop, "Rap & hip-hop", listOf("rap", "hip-hop", "hip hop", "hiphop")),
    ELECTRONIC(R.string.genre_electronic, "Electronic & dance", listOf("electro", "electronique", "electronic", "electronica", "musique electronique", "dance", "techno", "house", "disco")),
    FILM_STAGE(R.string.genre_film_stage, "Film & musicals", listOf("film", "musique de film", "bande originale", "soundtrack", "movie", "banda sonora", "musica de cine", "comedie musicale", "musical", "musicals", "comedia musical")),
    CHILDREN(R.string.genre_children, "Children", listOf("enfants", "enfant", "pour enfants", "comptines", "children", "kids", "nursery rhymes", "infantil", "musica infantil"));

    companion object {
        /** The default genre an old genre text names (case and accents ignored), or none. */
        fun matching(text: String): Genre? {
            val wanted = normalize(text.trim())
            if (wanted.isEmpty()) return null
            return entries.firstOrNull { genre ->
                normalize(genre.english) == wanted || normalize(genre.name.replace('_', ' ')) == wanted ||
                    genre.aliases.any { normalize(it) == wanted }
            }
        }

        fun ofCode(code: String?): Genre? = entries.firstOrNull { it.name == code }
    }
}

/**
 * The genres table built from every score's genre text (v10): the [Genre]s first (rows 0..14, by
 * their code), then a genre of its own for each other text, one per name however it is spelled.
 * [links] are a score's id and the index of its genre in [rows].
 */
object GenreBackfill {
    data class Row(val name: String, val code: String?)
    data class Plan(val rows: List<Row>, val links: List<Pair<Long, Int>>)

    fun of(sheets: List<Pair<Long, String>>): Plan {
        val rows = Genre.entries.map { Row(it.english, it.name) }.toMutableList()
        val own = HashMap<String, Int>()
        val links = mutableListOf<Pair<Long, Int>>()
        for ((sheetId, text) in sheets) {
            for (name in splitGenreText(text)) {
                val at = Genre.matching(name)?.ordinal
                    ?: own.getOrPut(normalize(name)) { rows.add(Row(name, null)); rows.size - 1 }
                if ((sheetId to at) !in links) links += sheetId to at
            }
        }
        return Plan(rows, links)
    }
}

/**
 * The genres an old `sheets.genre` text holds: "Rock, Pop" is two. `,`, `;` and `/` split it; `&`
 * does not ("Rhythm & Blues" is one genre).
 */
fun splitGenreText(text: String): List<String> = distinctTagNames(text.split(',', ';', '/').map(::tagName))
