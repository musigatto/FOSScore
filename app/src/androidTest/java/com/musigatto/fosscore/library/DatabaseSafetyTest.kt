package com.musigatto.fosscore.library

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSafetyTest {
    private val name = "safety-test.db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backups = File(context.filesDir, DatabaseSafety.BACKUP_DIR)

    private fun ours() = backups.listFiles().orEmpty().filter { it.name.startsWith("safety-test-") }

    /** A database file at [version] holding one row, written through the write-ahead log. */
    private fun createAt(version: Int) {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE t (v TEXT)")
            db.execSQL("INSERT INTO t VALUES ('kept')")
            db.version = version
        }
    }

    private fun rowsIn(file: File): List<String> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT v FROM t", null).use { c -> List(c.count) { c.moveToNext(); c.getString(0) } }
        }

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(name)
        ours().forEach { it.delete() }
    }

    @Test
    fun upgradeKeepsTheDatabaseAndSavesACopy() {
        createAt(2)
        DatabaseSafety.beforeOpen(context, name, 4)
        assertTrue(context.getDatabasePath(name).isFile)
        val copy = ours().single()
        assertTrue(copy.name, copy.name.endsWith("-v2-before-v4.db"))
        // The copy is complete on its own, write-ahead log included.
        assertEquals(listOf("kept"), rowsIn(copy))
    }

    @Test
    fun downgradeMovesTheNewerDatabaseAside() {
        createAt(9)
        DatabaseSafety.beforeOpen(context, name, 4)
        assertFalse(context.getDatabasePath(name).exists())
        assertFalse(File(context.getDatabasePath(name).path + "-wal").exists())
        val moved = ours().single()
        assertTrue(moved.name, moved.name.endsWith("-v9-newer-than-v4.db"))
        assertEquals(listOf("kept"), rowsIn(moved))
    }

    @Test
    fun sameVersionOrNoDatabaseChangesNothing() {
        DatabaseSafety.beforeOpen(context, name, 4)
        createAt(4)
        DatabaseSafety.beforeOpen(context, name, 4)
        assertTrue(ours().isEmpty())
        assertTrue(context.getDatabasePath(name).isFile)
    }
}
