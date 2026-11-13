package com.musigatto.fosscore.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamesFieldTest {

    @Test
    fun thePickerListsTheLibrarysTagsAndTheScoresOnce() {
        assertEquals(listOf("duo", "Rock", "film music"), pickerTags(listOf("duo", "Rock"), listOf("rock", "film music")))
    }

    @Test
    fun typingFindsTheTagsHoldingItIgnoringCaseAndAccents() {
        val all = listOf("duo", "Practice", "rock", "Prélude")
        assertEquals(all, filterTags(all, "  "))
        assertEquals(listOf("Practice"), filterTags(all, "prá"))
        assertEquals(listOf("Practice", "Prélude"), filterTags(all, "PR"))
        assertEquals(emptyList<String>(), filterTags(all, "jazz"))
    }

    @Test
    fun creatingATagThatExistsChecksItInsteadOfMakingASecondOne() {
        val all = listOf("Rock", "duo")
        assertEquals("Rock", tagToCreate(all, " rôck "))
        assertEquals("film music", tagToCreate(all, " film   music "))
        assertNull(tagToCreate(all, "   "))
    }

    @Test
    fun aTapChecksOrUnchecks() {
        assertEquals(listOf("duo", "rock"), toggleTag(listOf("duo"), "rock"))
        assertEquals(listOf("duo"), toggleTag(listOf("duo", "rock"), "rock"))
    }
}
