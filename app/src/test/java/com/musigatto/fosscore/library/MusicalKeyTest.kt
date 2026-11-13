package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicalKeyTest {
    @Test
    fun fortyTwoKeysEachWithItsOwnCode() {
        assertEquals(42, MusicalKey.entries.size)
        assertEquals(42, MusicalKey.entries.map { it.code }.toSet().size)
        assertEquals("C", MusicalKey.C.code)
        assertEquals("Bbm", MusicalKey.B_FLAT_MINOR.code)
        assertEquals("F#", MusicalKey.F_SHARP.code)
        MusicalKey.entries.forEach { assertEquals(it, MusicalKey.ofCode(it.code)) }
    }

    @Test
    fun lettersAsPeopleWriteThem() {
        assertEquals(MusicalKey.F, MusicalKey.parse("F"))
        assertEquals(MusicalKey.F_MINOR, MusicalKey.parse("Fm"))
        assertEquals(MusicalKey.B_FLAT, MusicalKey.parse("Bb"))
        assertEquals(MusicalKey.F_SHARP_MINOR, MusicalKey.parse("F#m"))
        assertEquals(MusicalKey.E_FLAT, MusicalKey.parse("E♭"))
        assertEquals(MusicalKey.C, MusicalKey.parse("C major"))
        assertEquals(MusicalKey.A_MINOR, MusicalKey.parse("A minor"))
        assertEquals(MusicalKey.D_MINOR, MusicalKey.parse("Dm"))
    }

    @Test
    fun noteNamesInFrenchAndSpanish() {
        assertEquals(MusicalKey.C, MusicalKey.parse("Do M"))
        assertEquals(MusicalKey.A_MINOR, MusicalKey.parse("la mineur"))
        assertEquals(MusicalKey.B_FLAT, MusicalKey.parse("Si bémol majeur"))
        assertEquals(MusicalKey.F_SHARP_MINOR, MusicalKey.parse("Fa♯ m"))
        assertEquals(MusicalKey.D_MINOR, MusicalKey.parse("Ré menor"))
        assertEquals(MusicalKey.G, MusicalKey.parse("Sol"))
        assertEquals(MusicalKey.C_SHARP, MusicalKey.parse("do dièse"))
    }

    @Test
    fun aTextNamingNoKeyIsNone() {
        assertNull(MusicalKey.parse("coucou"))
        assertNull(MusicalKey.parse("H"))
        assertNull(MusicalKey.parse("C major minor"))
        assertNull(MusicalKey.parse(""))
    }

    @Test
    fun anOldKeyTextKeepsWhatNamesNoKey() {
        assertEquals(listOf(MusicalKey.F) to "", splitKeyText("F"))
        assertEquals(listOf(MusicalKey.F, MusicalKey.G_MINOR) to "", splitKeyText("F, Gm"))
        assertEquals(listOf(MusicalKey.D) to "capo 2", splitKeyText("D / capo 2"))
        assertEquals(emptyList<MusicalKey>() to "coucou", splitKeyText("coucou"))
        assertEquals(emptyList<MusicalKey>() to "", splitKeyText("  "))
    }
}
