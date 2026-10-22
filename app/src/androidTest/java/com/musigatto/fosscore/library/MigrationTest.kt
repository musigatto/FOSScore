package com.musigatto.fosscore.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
                put("genre", "Folk")
                put("musicalKey", "F")
                put("tags", "duo, practice")
            }
        )
        if (version >= 2) {
            db.insert(
                "stamps", SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("sheetHash", HASH)
                    put("page", 1)
                    put("symbol", "FERMATA")
                    put("x", 0.25f)
                    put("y", 0.5f)
                    put("size", 0.04f)
                    if (version >= 3) put("color", RED)
                }
            )
        }
        if (version >= 4) {
            db.insert(
                "strokes", SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("sheetHash", HASH)
                    put("page", 2)
                    put("points", "0.1;0.1|0.2;0.2")
                    put("width", 0.003f)
                }
            )
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
                assertEquals("v$version", "duo, practice", sheet.tags)
                val stamps = db.stampDao().observeBySheet(HASH).first()
                assertEquals("v$version: stamps", if (version >= 2) 1 else 0, stamps.size)
                if (version >= 2) {
                    assertEquals("FERMATA", stamps[0].symbol)
                    // Version 2 had no colour: the theme colour (null) after the migration.
                    if (version >= 3) assertEquals(RED, stamps[0].color) else assertNull(stamps[0].color)
                }
            }
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
            assertEquals(1, db.strokeDao().observeBySheet(HASH).first().size)
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
