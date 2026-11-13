package com.musigatto.fosscore.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The tags screen's queries, on the app's database (emptied before and after). */
@RunWith(AndroidJUnit4::class)
class TagRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.get(context)
    private val tags = TagRepository(context)

    @Before
    fun empty() = db.clearAllTables()

    @After
    fun clean() = db.clearAllTables()

    private suspend fun score(title: String): Long = db.sheetDao().insert(
        Sheet(fileName = "$title.pdf", path = "/$title.pdf", hash = title, title = title,
            composer = "", genre = "", musicalKey = "", tags = "")
    )

    @Test
    fun aTagListsItsScoresOnceAndCountsThem() = runBlocking {
        val a = score("Alpha")
        val b = score("Bravo")
        val duo = tags.create("  duo  ")
        tags.create("solo")
        tags.add(duo, b)
        tags.add(duo, a)
        // a second time changes nothing: a tag is on a score or not
        tags.add(duo, a)

        assertEquals(listOf(GroupSummary(duo, "duo", 2), GroupSummary(duo + 1, "solo", 0)), tags.groups().first())
        assertEquals(listOf(a, b), tags.entries(duo).first().map { it.entryKey })
        assertEquals(listOf(a, b), tags.entries(duo).first().map { it.sheet.id })

        tags.remove(duo, a)
        assertEquals(listOf(b), tags.entries(duo).first().map { it.sheet.id })
    }

    @Test
    fun aDeletedTagLeavesItsScoresInTheLibrary() = runBlocking {
        val a = score("Alpha")
        val duo = tags.create("duo")
        tags.add(duo, a)

        tags.delete(duo)

        assertEquals(emptyList<GroupSummary>(), tags.groups().first())
        assertEquals(emptyList<Tag>(), db.tagDao().tagsOf(a))
        assertEquals(1, db.sheetDao().observeAll().first().size)
    }

    @Test
    fun aNameIsTakenWhateverItsSpellingButNotByTheTagItself() = runBlocking {
        val rock = tags.create("Rock")

        assertTrue(tags.nameTaken("rôck", null))
        assertFalse(tags.nameTaken("rock", rock))
        assertFalse(tags.nameTaken("jazz", null))

        tags.rename(rock, " Rock  and roll ")
        assertEquals("Rock and roll", tags.name(rock).first())
    }
}
