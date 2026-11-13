package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenreTest {
    @Test
    fun fifteenDefaultsFromClassicalToRap() {
        assertEquals(15, Genre.entries.size)
        assertEquals(Genre.CLASSICAL, Genre.entries.first())
        assertEquals(15, Genre.entries.map { normalize(it.english) }.toSet().size)
    }

    @Test
    fun anOldGenreTextIsRecognisedInEveryLanguage() {
        assertEquals(Genre.FOLK_COUNTRY, Genre.matching("Folk"))
        assertEquals(Genre.CLASSICAL, Genre.matching(" classique "))
        assertEquals(Genre.CLASSICAL, Genre.matching("Clásica"))
        assertEquals(Genre.FILM_STAGE, Genre.matching("Musique de film"))
        assertEquals(Genre.SOUL_FUNK, Genre.matching("Rhythm & Blues"))
        assertEquals(Genre.HIP_HOP, Genre.matching("Hip-Hop"))
        assertEquals(Genre.JAZZ_BLUES, Genre.matching("jazz & blues"))
        // as the test tablet had it
        assertEquals(Genre.ROCK_METAL, Genre.matching("Rock'n Roll"))
        assertNull(Genre.matching("Bal musette"))
        assertNull(Genre.matching(""))
    }

    @Test
    fun aGenreTextSplitsOnCommasNotOnAmpersands() {
        assertEquals(listOf("Rock", "Pop"), splitGenreText("Rock, Pop"))
        assertEquals(listOf("R&B"), splitGenreText(" R&B "))
        assertEquals(listOf("Jazz", "swing"), splitGenreText("Jazz / swing; jazz"))
    }

    @Test
    fun theDefaultsComeFirstThenOneGenrePerOtherText() {
        val plan = GenreBackfill.of(listOf(1L to "Folk", 2L to "Bal musette, Rock", 3L to "bal Musette", 4L to ""))
        // the 15 defaults, then "Bal musette" once ("Rock" is a default)
        assertEquals(16, plan.rows.size)
        assertEquals(GenreBackfill.Row("Classical", "CLASSICAL"), plan.rows[0])
        assertEquals(GenreBackfill.Row("Bal musette", null), plan.rows[15])
        assertEquals(
            listOf(1L to Genre.FOLK_COUNTRY.ordinal, 2L to 15, 2L to Genre.ROCK_METAL.ordinal, 3L to 15),
            plan.links,
        )
    }
}
