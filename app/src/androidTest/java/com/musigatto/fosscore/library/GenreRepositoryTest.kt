package com.musigatto.fosscore.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The genres: the defaults shown in the app's language, the user's own as written, and their return. */
@RunWith(AndroidJUnit4::class)
class GenreRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.get(context)
    // the default genres as a French app shows them (two are enough here)
    private val labels = mapOf(Genre.CLASSICAL.name to "Classique", Genre.POP.name to "Pop")
    private val genres = GenreRepository(context, labels)
    private val library = LibraryRepository(context)

    @Before
    fun defaults() = runBlocking {
        db.clearAllTables()
        library.restoreDefaultGenres(labels)
        Unit
    }

    @After
    fun clean() = db.clearAllTables()

    private suspend fun score(title: String): Long = db.sheetDao().insert(
        Sheet(fileName = "$title.pdf", path = "/$title.pdf", hash = title, title = title,
            composer = "", genre = "", musicalKey = "", tags = "")
    )

    @Test
    fun aDefaultGenreShowsInTheAppsLanguageAndIsPickedByThatName() = runBlocking {
        val a = score("Alpha")
        library.setGenres(a, listOf("classique", "Bal musette"), labels)

        val classical = db.tagDao().genres().single { it.code == Genre.CLASSICAL.name }
        assertEquals("Classique", genres.name(classical.id).first())
        assertEquals(listOf(Genre.CLASSICAL.name, null), db.tagDao().genresOf(a).map { it.code }.sortedBy { it == null })
        assertEquals(16, db.tagDao().genres().size)
    }

    @Test
    fun aRenamedDefaultIsTheUsersTextFromThenOn() = runBlocking {
        val pop = db.tagDao().genres().single { it.code == Genre.POP.name }.id
        genres.rename(pop, "Pop française")

        val renamed = db.tagDao().genres().single { it.id == pop }
        assertNull(renamed.code)
        assertEquals("Pop française", genres.name(pop).first())
    }

    @Test
    fun aNameIsTakenAsShownOrAsStored() = runBlocking {
        assertTrue(genres.nameTaken("classique", null))
        // the stored English name of a default, unique among the genres
        assertTrue(genres.nameTaken("Classical", null))
        assertFalse(genres.nameTaken("Bal musette", null))
        // a tag may share a genre's name: another kind
        assertFalse(TagRepository(context).nameTaken("Pop", null))
    }

    @Test
    fun deletedDefaultsComeBackAndTheUsersGenresStay() = runBlocking {
        val classical = db.tagDao().genres().single { it.code == Genre.CLASSICAL.name }.id
        genres.delete(classical)
        val pop = db.tagDao().genres().single { it.code == Genre.POP.name }.id
        genres.delete(pop)
        // the user wrote "Pop" again: on restore it is the default again, not a second "Pop"
        genres.create("Pop")
        genres.create("Bal musette")

        assertEquals(2, library.restoreDefaultGenres(labels))

        val all = db.tagDao().genres()
        assertEquals(16, all.size)
        assertEquals(Genre.entries.map { it.name }.toSet(), all.mapNotNull { it.code }.toSet())
        assertEquals(listOf("Bal musette"), all.filter { it.code == null }.map { it.name })
        assertEquals(0, library.restoreDefaultGenres(labels))
    }

    @Test
    fun aScoresKeysKeepTheirOrder() = runBlocking {
        val a = score("Alpha")
        library.setKeys(a, listOf(MusicalKey.G, MusicalKey.E_MINOR))
        assertEquals(listOf(MusicalKey.G, MusicalKey.E_MINOR), library.keysOf(a))

        library.addKeys(listOf(a), listOf(MusicalKey.E_MINOR, MusicalKey.D))
        assertEquals(listOf(MusicalKey.G, MusicalKey.E_MINOR, MusicalKey.D), library.keysOf(a))
        assertEquals(mapOf(a to listOf(MusicalKey.G, MusicalKey.E_MINOR, MusicalKey.D)), library.keysBySheet().first())
    }
}
