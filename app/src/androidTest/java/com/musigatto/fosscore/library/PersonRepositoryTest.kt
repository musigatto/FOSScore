package com.musigatto.fosscore.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The people screen's queries, on the app's database (emptied before and after). */
@RunWith(AndroidJUnit4::class)
class PersonRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.get(context)
    private val people = PersonRepository(context)
    private val composers = PersonRepository(context, PersonRole.COMPOSER)
    private val performers = PersonRepository(context, PersonRole.PERFORMER)
    private val library = LibraryRepository(context)

    @Before
    fun empty() = db.clearAllTables()

    @After
    fun clean() = db.clearAllTables()

    private suspend fun score(title: String): Long = db.sheetDao().insert(
        Sheet(fileName = "$title.pdf", path = "/$title.pdf", hash = title, title = title,
            composer = "", genre = "", musicalKey = "", tags = "")
    )

    @Test
    fun aSingerWhoWritesTheirSongsIsOnePersonInBothRoles() = runBlocking {
        val a = score("Alpha")
        val b = score("Bravo")
        library.setPeople(a, PersonRole.COMPOSER, listOf("Patrick Bruel"))
        library.setPeople(a, PersonRole.PERFORMER, listOf("patrick bruel"))
        library.setPeople(b, PersonRole.PERFORMER, listOf("Patrick Bruel"))

        val bruel = people.groups().first().single()
        // two scores, though he is on the first one twice
        assertEquals(GroupSummary(bruel.id, "Patrick Bruel", 2), bruel)
        assertEquals(listOf(a), composers.entries(bruel.id).first().map { it.sheet.id })
        assertEquals(listOf(a, b), performers.entries(bruel.id).first().map { it.sheet.id })
        assertEquals(listOf(a, b), people.entries(bruel.id).first().map { it.sheet.id })
        assertEquals(mapOf(a to Credits(listOf("Patrick Bruel"), listOf("Patrick Bruel")), b to Credits(performers = listOf("Patrick Bruel"))),
            library.creditsBySheet().first())
    }

    @Test
    fun takenOutInOneRoleTheOtherStays() = runBlocking {
        val a = score("Alpha")
        val brel = people.create("Brel")
        composers.add(brel, a)
        performers.add(brel, a)

        composers.remove(brel, a)

        assertEquals(emptyList<Long>(), composers.entries(brel).first().map { it.sheet.id })
        assertEquals(listOf(a), performers.entries(brel).first().map { it.sheet.id })
    }

    @Test
    fun aDeletedPersonLeavesTheirScoresInTheLibrary() = runBlocking {
        val a = score("Alpha")
        library.setPeople(a, PersonRole.COMPOSER, listOf("Brel"))
        val brel = people.groups().first().single().id

        people.delete(brel)

        assertEquals(emptyList<GroupSummary>(), people.groups().first())
        assertEquals(emptyMap<Long, Credits>(), library.creditsBySheet().first())
        assertEquals(1, db.sheetDao().observeAll().first().size)
    }

    @Test
    fun twoSpellingsOfOnePersonMergeKeepingTheirScoresAndRoles() = runBlocking {
        val a = score("Alpha")
        val b = score("Bravo")
        val c = score("Charlie")
        val cohen = people.create("Leonard Cohen")
        val cohen2 = people.create("Cohen, Léonard")
        composers.add(cohen, a)
        composers.add(cohen2, a) // the same score in the same role: once after the merge
        performers.add(cohen2, b)
        composers.add(cohen2, c)

        // the one shown stays, with the other's spelling
        people.merge(into = cohen, from = cohen2, name = "Léonard Cohen")

        assertEquals(listOf(GroupSummary(cohen, "Léonard Cohen", 3)), people.groups().first())
        assertEquals(listOf(a, c), composers.entries(cohen).first().map { it.sheet.id })
        assertEquals(listOf(b), performers.entries(cohen).first().map { it.sheet.id })
    }

    @Test
    fun aNameIsTakenWhateverItsSpelling() = runBlocking {
        people.create("Léo Ferré")
        assertTrue(people.nameTaken("leo ferre", null))
    }
}
