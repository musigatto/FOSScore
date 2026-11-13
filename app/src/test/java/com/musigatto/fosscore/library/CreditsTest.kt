package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CreditsTest {
    @Test
    fun composersThenPerformersSeparatedByADot() {
        assertEquals("Léonard Cohen · Jeff Buckley", Credits(listOf("Léonard Cohen"), listOf("Jeff Buckley")).line)
        assertEquals("Lennon, McCartney · The Beatles", Credits(listOf("Lennon", "McCartney"), listOf("The Beatles")).line)
    }

    @Test
    fun aPerformerWhoIsAlsoTheComposerIsNamedOnce() {
        assertEquals("Patrick Bruel", Credits(listOf("Patrick Bruel"), listOf("patrick bruel")).line)
        assertEquals("Serge Lama · Alice Dona", Credits(listOf("Serge Lama"), listOf("Serge Lama", "Alice Dona")).line)
    }

    @Test
    fun onlyTheSideThereIs() {
        assertEquals("Joe Dassin", Credits(performers = listOf("Joe Dassin")).line)
        assertEquals("Traditionnel", Credits(composers = listOf("Traditionnel")).line)
        assertEquals("", Credits().line)
    }

    @Test
    fun theRowsOfAScoreGoToItsRoles() {
        val rows = listOf(
            SheetPersonName(1L, 10L, "Brel", PersonRole.COMPOSER.name),
            SheetPersonName(1L, 11L, "Barbara", PersonRole.PERFORMER.name),
            SheetPersonName(2L, 10L, "Brel", PersonRole.PERFORMER.name),
        )
        assertEquals(
            mapOf(1L to Credits(listOf("Brel"), listOf("Barbara")), 2L to Credits(performers = listOf("Brel"))),
            Credits.bySheet(rows),
        )
    }
}
