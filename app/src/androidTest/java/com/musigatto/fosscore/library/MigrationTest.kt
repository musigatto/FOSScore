package com.musigatto.fosscore.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musigatto.fosscore.score.SheetFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every database a user may have, from version 1, must reach the current version with its
 * sheets and annotations. When the schema changes, add the new version's data to [fill] and
 * check it in [migratesFromEveryVersionKeepingTheLibrary].
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val testDb = "migration-test.db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    /** One sheet, and the annotations the schema of [version] can hold. */
    private fun fill(db: SupportSQLiteDatabase, version: Int) {
        db.insert(
            "sheets", SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("fileName", "song.pdf")
                put("path", "/data/sheets/song.pdf")
                put("hash", HASH)
                put("title", "Song")
                put("composer", "Composer")
                // v5 kept its format already computed by the 4→5 migration; a hand-filled v5
                // database with no format is not a database a user can have
                if (version >= 5) put("format", "PDF")
                put("genre", "Folk")
                put("musicalKey", "F")
                put("tags", "duo, practice")
            }
        )
        if (version >= 5) {
            // a database born at v5 already went through the 4→5 migration: its tags live
            // in their table, split from the sheets' free text
            db.execSQL(
                "INSERT INTO `tags` (`name`, `kind`) VALUES ('Folk','GENRE'),('duo','FREE'),('practice','FREE')"
            )
            db.execSQL("INSERT INTO `sheet_tags` (`sheetId`, `tagId`) SELECT 1, `id` FROM `tags`")
        }
        if (version >= 2) {
            db.insert(
                "stamps", SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    // from version 5 the annotations hang on the sheet's id, not on its hash
                    if (version >= 5) put("sheetId", 1L) else put("sheetHash", HASH)
                    put("page", 1)
                    put("symbol", "FERMATA")
                    put("x", 0.25f)
                    put("y", 0.5f)
                    put("size", 0.04f)
                    if (version >= 3) put("color", RED)
                    if (version >= 5) {
                        put("bold", 0)
                        put("italic", 0)
                    }
                    // v7: a text stamp keeps the width it wraps at
                    if (version >= 7) put("textWidth", 0.5f)
                    // v9: the layer the mark belongs to
                    if (version >= 9) put("layer", 1L)
                }
            )
        }
        if (version >= 4) {
            db.insert(
                "strokes", SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    if (version >= 5) put("sheetId", 1L) else put("sheetHash", HASH)
                    put("page", 2)
                    put("points", "0.1;0.1|0.2;0.2")
                    put("width", 0.003f)
                    if (version >= 5) put("groupId", 7L)
                    // v6: strokes carry their kind; a shape row is what the new schema adds
                    if (version >= 6) put("kind", 1)
                    // v9: the layer the mark belongs to
                    if (version >= 9) put("layer", 1L)
                }
            )
        }
        if (version >= 9) {
            // a database born at v9 already has its layer, written by the app (id 1: the first row)
            db.insert("layers", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("sheetId", 1L)
                put("name", "")
                put("visible", 1)
            })
        }
        if (version >= 8) {
            db.insert("setlists", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("name", "Concert")
                put("createdAt", 1L)
            })
            // the same score twice: a setlist may play a piece again
            for (position in 0..1) {
                db.insert("setlist_items", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("setlistId", 1L)
                    put("position", position)
                    put("sheetId", 1L)
                })
            }
        }
    }

    @Test
    fun migratesFromEveryVersionKeepingTheLibrary() {
        for (version in 1 until AppDatabase.VERSION) {
            context.deleteDatabase(testDb)
            helper.createDatabase(testDb, version).apply {
                fill(this, version)
                close()
            }
            // Also checks that the result matches the schema Room expects for the current version.
            helper.runMigrationsAndValidate(testDb, AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).close()

            withRoom { db ->
                val sheet = db.sheetDao().byHash(HASH)
                assertNotNull("v$version: sheet lost", sheet)
                assertEquals("v$version", "Song", sheet!!.title)
                // v10: the tags left the text for their table (checked below)
                assertEquals("v$version", "", sheet.tags)
                // v5: the format stops being the extension
                assertEquals("v$version", SheetFormat.PDF, sheet.format)

                val stamps = db.stampDao().observePage(sheet.id, 1).first()
                assertEquals("v$version: stamps", if (version >= 2) 1 else 0, stamps.size)
                if (version >= 2) {
                    assertEquals("FERMATA", stamps[0].symbol)
                    // the annotation follows the sheet now, not the file's hash
                    assertEquals("v$version: stamp sheetId", sheet.id, stamps[0].sheetId)
                    // Version 2 had no colour: the theme colour (null) after the migration.
                    if (version >= 3) assertEquals(RED, stamps[0].color) else assertNull(stamps[0].color)
                    // v7: a width written on v7 stays; old texts keep null (full-page wrap)
                    if (version >= 7) assertEquals(0.5f, stamps[0].textWidth) else assertNull(stamps[0].textWidth)
                }

                val strokes = db.strokeDao().observePage(sheet.id, 2).first()
                assertEquals("v$version: strokes", if (version >= 4) 1 else 0, strokes.size)
                if (version >= 4) {
                    assertEquals("v$version: stroke sheetId", sheet.id, strokes[0].sheetId)
                    // ink from before v5 gets no group (it falls back to the geometric word
                    // box); a database born at v5 already had its own group ids written
                    assertEquals("v$version: groupId", if (version >= 5) 7L else 0L, strokes[0].groupId)
                    // v6: old ink is ink (kind 0); a shape written on v6 stays a shape
                    assertEquals("v$version: stroke kind", if (version >= 6) 1 else 0, strokes[0].kind)
                }

                // v9: the old annotations end up in their sheet's layer (a layer the migration created)
                if (version >= 2) assertEquals("v$version: layer", 1L, stamps[0].layer)
                if (version >= 4) assertEquals("v$version: layer", 1L, strokes[0].layer)
                assertEquals(
                    "v$version: layers",
                    if (version >= 2) listOf(Layer(1L, sheet.id, "", true)) else emptyList(),
                    db.layerDao().observe(sheet.id).first(),
                )

                // v8: a database created at v8 already has its setlists populated by fill()
                assertEquals(
                    "v$version: setlists",
                    if (version >= 8) listOf(GroupSummary(1L, "Concert", 2)) else emptyList(),
                    db.setlistDao().observeSummaries().first()
                )

                // v10: the score's text "duo, practice" is its tags, whatever the table held
                val tags = db.openHelper.readableDatabase
                    .query("SELECT `name` FROM `tags` WHERE `kind` = 'FREE' ORDER BY `name`").use { c ->
                        buildList { while (c.moveToNext()) add(c.getString(0)) }
                    }
                assertEquals("v$version: tags", listOf("duo", "practice"), tags)
                assertEquals("v$version: the score's tags", listOf("duo", "practice"), db.tagDao().tagsOf(sheet.id).map { it.name })

                // v10: the 15 default genres; the text "Folk" is the default "Folk & country"
                assertEquals("v$version: genres", Genre.entries.map { it.name }, db.tagDao().genres().mapNotNull { it.code }.sortedBy { Genre.valueOf(it).ordinal })
                assertEquals("v$version: the score's genre", listOf(Genre.FOLK_COUNTRY.name), db.tagDao().genresOf(sheet.id).map { it.code })
                assertEquals("v$version: genre text", "", sheet.genre)
                // v10: the key text "F" is the key F
                assertEquals("v$version: keys", listOf("F"), db.keyDao().keysOf(sheet.id).map { it.key })
                assertEquals("v$version: key text", "", sheet.musicalKey)

                // v10: the composer text is a person, as author/composer; the text is emptied
                assertEquals("v$version: people", listOf("Composer"), db.personDao().all().map { it.name })
                assertEquals(
                    "v$version: the score's composers",
                    listOf("Composer"),
                    db.personDao().peopleOf(sheet.id, PersonRole.COMPOSER.name).map { it.name },
                )
                assertEquals("v$version: composer text", "", sheet.composer)
                // v10: a comment, empty for every score that came before it
                assertEquals("v$version: comment", "", sheet.comment)
            }
        }
    }

    @Test
    fun aGenreOrAKeyTextNotRecognisedIsKept() {
        context.deleteDatabase(testDb)
        helper.createDatabase(testDb, 8).apply {
            for ((id, genreAndKey) in listOf(1L to ("Bal musette, Rock" to "Do M, coucou"), 2L to ("bal musette" to "F#m / Bb"))) {
                insert("sheets", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("id", id)
                    put("fileName", "song$id.pdf")
                    put("path", "/data/sheets/song$id.pdf")
                    put("hash", "hash$id")
                    put("title", "Song $id")
                    put("composer", "")
                    put("format", "PDF")
                    put("genre", genreAndKey.first)
                    put("musicalKey", genreAndKey.second)
                    put("tags", "")
                })
            }
            close()
        }
        helper.runMigrationsAndValidate(testDb, 10, true, *AppDatabase.MIGRATIONS).close()
        withRoom { db ->
            // "Bal musette" is a genre of its own, once; "Rock" the default
            val own = db.tagDao().genres().filter { it.code == null }.map { it.name }
            assertEquals(listOf("Bal musette"), own)
            assertEquals(listOf(null, Genre.ROCK_METAL.name), db.tagDao().genresOf(1L).map { it.code }.sortedBy { it ?: "" })
            assertEquals(listOf("Bal musette"), db.tagDao().genresOf(2L).map { it.name })
            // the keys in their order; what names no key goes to the comment
            assertEquals(listOf("C"), db.keyDao().keysOf(1L).map { it.key })
            assertEquals(listOf("F#m", "Bb"), db.keyDao().keysOf(2L).map { it.key })
            assertEquals("Key: coucou", db.sheetDao().byHash("hash1")?.comment)
            assertEquals("", db.sheetDao().byHash("hash2")?.comment)
        }
    }

    @Test
    fun aComposerTextBecomesPeopleOncePerName() {
        context.deleteDatabase(testDb)
        helper.createDatabase(testDb, 8).apply {
            for ((id, composer) in listOf(1L to "Léonard Cohen / Kyle Landry", 2L to "léonard cohen", 3L to "")) {
                insert("sheets", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("id", id)
                    put("fileName", "song$id.pdf")
                    put("path", "/data/sheets/song$id.pdf")
                    put("hash", "hash$id")
                    put("title", "Song $id")
                    put("composer", composer)
                    put("format", "PDF")
                    put("genre", "")
                    put("musicalKey", "")
                    put("tags", "")
                })
            }
            close()
        }
        helper.runMigrationsAndValidate(testDb, 10, true, *AppDatabase.MIGRATIONS).close()
        withRoom { db ->
            val composers = { id: Long -> runBlocking { db.personDao().peopleOf(id, PersonRole.COMPOSER.name).map { it.name } } }
            // the first spelling met names the person
            assertEquals(listOf("Kyle Landry", "Léonard Cohen"), db.personDao().all().map { it.name }.sorted())
            assertEquals(listOf("Kyle Landry", "Léonard Cohen"), composers(1L))
            assertEquals(listOf("Léonard Cohen"), composers(2L))
            assertEquals(emptyList<String>(), composers(3L))
            assertEquals(emptyList<String>(), db.personDao().peopleOf(1L, PersonRole.PERFORMER.name).map { it.name })
        }
    }

    @Test
    fun aTagWrittenOnSeveralScoresOrSeveralWaysBecomesOneTag() {
        context.deleteDatabase(testDb)
        helper.createDatabase(testDb, 8).apply {
            for ((id, tags) in listOf(1L to "Rock duo", 2L to "rock", 3L to "", 4L to "film music, Rôck")) {
                insert("sheets", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("id", id)
                    put("fileName", "song$id.pdf")
                    put("path", "/data/sheets/song$id.pdf")
                    put("hash", "hash$id")
                    put("title", "Song $id")
                    put("composer", "")
                    put("format", "PDF")
                    put("genre", "")
                    put("musicalKey", "")
                    put("tags", tags)
                })
            }
            close()
        }
        helper.runMigrationsAndValidate(testDb, 10, true, *AppDatabase.MIGRATIONS).close()
        withRoom { db ->
            // the first spelling met names the tag: "Rock" (score 1), not "rock" or "Rôck"
            assertEquals(listOf("Rock", "duo", "film music"), db.tagDao().free().map { it.name }.sorted())
            assertEquals(listOf("duo", "Rock"), db.tagDao().tagsOf(1L).map { it.name })
            assertEquals(listOf("Rock"), db.tagDao().tagsOf(2L).map { it.name })
            assertEquals(emptyList<String>(), db.tagDao().tagsOf(3L).map { it.name })
            assertEquals(listOf("film music", "Rock"), db.tagDao().tagsOf(4L).map { it.name })
        }
    }

    @Test
    fun currentVersionOpensWithRoom() {
        context.deleteDatabase(testDb)
        helper.createDatabase(testDb, AppDatabase.VERSION).apply {
            fill(this, AppDatabase.VERSION)
            close()
        }
        withRoom { db ->
            // the kind column comes out of the schema the way it was written: a shape row
            // created on v6 stays a shape (and old rows default to ink through the migration)
            val stroke = db.strokeDao().observePage(1L, 2).first().single()
            assertEquals(1, db.strokeDao().observePage(1L, 2).first().size)
            assertEquals("v6 keeps a shape's kind", Stroke.SHAPE, stroke.kind)
            // v9: the layer written on v9 comes out of the schema as it was written
            assertEquals(1L, stroke.layer)
            assertEquals(
                "v9 layers",
                listOf(Layer(1L, 1L, "", true)),
                db.layerDao().observe(1L).first(),
            )
            assertEquals(listOf(GroupSummary(1L, "Concert", 2)), db.setlistDao().observeSummaries().first())
            assertEquals(listOf(1L, 1L), db.setlistDao().observeEntries(1L).first().map { it.sheet.id })
        }
    }

    private fun withRoom(block: suspend (AppDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, testDb)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking { block(db) }
        } finally {
            db.close()
            context.deleteDatabase(testDb)
        }
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef"
        const val RED = 0xFFCC0000.toInt()
    }
}
