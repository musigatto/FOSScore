package com.musigatto.fosscore.library

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export, then import at a simulated restart, on a test database. Runs on the test emulator
 * only: it moves the `sheets` folder of the app under test.
 */
@RunWith(AndroidJUnit4::class)
class LibraryBackupTest {
    private val dbName = "backup-test.db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backup = LibraryBackup(context, dbName)
    private val sheetsDir = File(context.filesDir, LibraryBackup.SHEETS_DIR)
    private val pdfBytes = "%PDF-1.4 test score".toByteArray()
    private val scoreBytes = "<score-partwise version=\"4.0\"/>".toByteArray()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Before
    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
        sheetsDir.deleteRecursively()
        File(context.filesDir, LibraryBackup.SHEETS_BEFORE_IMPORT).deleteRecursively()
        File(context.filesDir, DatabaseSafety.BACKUP_DIR).listFiles().orEmpty()
            .filter { it.name.startsWith("backup-test-") }.forEach { it.delete() }
        backup.discard()
        LibraryBackup.cancelPending(context)
    }

    /** Opens the test database as the app does: migrations, then relinking after an import. */
    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
        .addMigrations(*AppDatabase.MIGRATIONS)
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = LibraryBackup.relinkIfNeeded(context, db)
        })
        .build()

    private fun pdf(name: String): File = File(sheetsDir, name).apply {
        parentFile?.mkdirs()
        writeBytes(pdfBytes)
    }

    @Test
    fun exportThenImportBringsBackTheLibrary() = runBlocking {
        val db = open()
        val file = pdf("some-uuid.pdf")
        db.sheetDao().insert(sheet(file.path))
        db.stampDao().insert(Stamp(sheetHash = HASH, page = 1, symbol = "FERMATA", x = 0.2f, y = 0.3f, size = 0.04f))
        db.strokeDao().insert(Stroke(sheetHash = HASH, page = 2, points = "0.1;0.1|0.2;0.2", width = 0.003f))

        val archive = ByteArrayOutputStream()
        val manifest = backup.export(db, archive)
        assertEquals(AppDatabase.VERSION, manifest.dbVersion)
        assertEquals(listOf(1, 1, 1, 0), listOf(manifest.sheets, manifest.stamps, manifest.strokes, manifest.missingFiles))
        assertTrue(entries(archive.toByteArray()).containsAll(listOf("manifest.json", "database/fosscore.db", "database/schema.sql", "sheets/$HASH.pdf")))

        // The library changes after the export, then the export is imported.
        db.sheetDao().delete(db.sheetDao().byHash(HASH)!!)
        db.close()
        val inspection = backup.inspect(ByteArrayInputStream(archive.toByteArray()))
        assertTrue(inspection.canImport)
        assertFalse(inspection.needsConfirmation)
        backup.stage(inspection)

        // Restart.
        assertTrue(LibraryBackup.applyPending(context, dbName))
        val restored = open()
        val sheet = restored.sheetDao().byHash(HASH)!!
        assertEquals(File(sheetsDir, "$HASH.pdf").path, sheet.path)
        assertArrayEquals(pdfBytes, File(sheet.path).readBytes())
        assertEquals(1, restored.stampDao().observeBySheet(HASH).first().size)
        assertEquals(1, restored.strokeDao().observeBySheet(HASH).first().size)
        restored.close()
        // The library replaced by the import is kept.
        assertTrue(File(context.filesDir, "${LibraryBackup.SHEETS_BEFORE_IMPORT}/some-uuid.pdf").isFile)
        assertTrue(DatabaseSafety.backups(context).any { it.name.startsWith("backup-test-") && it.name.endsWith("-before-import.db") })
    }

    @Test
    fun aSheetKeepsTheExtensionOfItsFile() = runBlocking {
        val db = open()
        val score = File(sheetsDir, "some-uuid.musicxml").apply {
            parentFile?.mkdirs()
            writeBytes(scoreBytes)
        }
        db.sheetDao().insert(sheet(score.path))
        val archive = ByteArrayOutputStream()
        backup.export(db, archive)
        assertTrue(entries(archive.toByteArray()).contains("sheets/$HASH.musicxml"))
        db.close()

        backup.stage(backup.inspect(ByteArrayInputStream(archive.toByteArray())))
        assertTrue(LibraryBackup.applyPending(context, dbName))
        val restored = open()
        val sheet = restored.sheetDao().byHash(HASH)!!
        assertEquals(File(sheetsDir, "$HASH.musicxml").path, sheet.path)
        assertArrayEquals(scoreBytes, File(sheet.path).readBytes())
        restored.close()
    }

    @Test
    fun anOlderExportIsMigratedAtRestart() = runBlocking {
        // A version 2 database, as exported by an older app.
        helper.createDatabase(dbName, 2).apply {
            insertSheet(this)
            insert(
                "stamps", SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("sheetHash", HASH); put("page", 1); put("symbol", "FERMATA")
                    put("x", 0.2f); put("y", 0.3f); put("size", 0.04f)
                }
            )
            close()
        }
        val archive = archive(dbVersion = 2, database = context.getDatabasePath(dbName).readBytes())
        context.deleteDatabase(dbName)

        val inspection = backup.inspect(ByteArrayInputStream(archive))
        assertEquals(CheckStatus.WARN, inspection.checks.single { it.item == CheckItem.DATABASE }.status)
        assertTrue(inspection.canImport)
        assertTrue(inspection.needsConfirmation)
        backup.stage(inspection)
        assertTrue(LibraryBackup.applyPending(context, dbName))

        val db = open()
        assertEquals("Song", db.sheetDao().byHash(HASH)!!.title)
        assertNull(db.stampDao().observeBySheet(HASH).first().single().color)
        assertTrue(File(db.sheetDao().byHash(HASH)!!.path).isFile)
        db.close()
    }

    @Test
    fun aNewerExportIsRefused() {
        val newer = SQLiteDatabase.openOrCreateDatabase(File(context.cacheDir, "newer.db"), null).use {
            it.execSQL("CREATE TABLE sheets (id INTEGER)")
            it.version = AppDatabase.VERSION + 1
            it.path
        }
        val inspection = backup.inspect(ByteArrayInputStream(archive(AppDatabase.VERSION + 1, File(newer).readBytes())))
        File(newer).delete()
        assertEquals(CheckStatus.ERROR, inspection.checks.single { it.item == CheckItem.DATABASE }.status)
        assertFalse(inspection.canImport)
        assertFalse(LibraryBackup.applyPending(context, dbName))
    }

    @Test
    fun aDamagedDatabaseIsRefused() {
        val inspection = backup.inspect(ByteArrayInputStream(archive(AppDatabase.VERSION, "not a database".toByteArray())))
        assertEquals(CheckStatus.ERROR, inspection.checks.single { it.item == CheckItem.CONTENT }.status)
        assertFalse(inspection.canImport)
    }

    @Test
    fun anArchiveWithoutManifestIsRefused() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("../../evil.txt")); it.write(1); it.closeEntry() }
        }
        val inspection = backup.inspect(ByteArrayInputStream(zip.toByteArray()))
        assertFalse(inspection.canImport)
        assertFalse(File(context.filesDir.parentFile, "evil.txt").exists())
    }

    private fun sheet(path: String) = Sheet(
        fileName = "song.pdf", path = path, hash = HASH, title = "Song",
        composer = "", genre = "", musicalKey = "", tags = ""
    )

    private fun insertSheet(db: SupportSQLiteDatabase) {
        db.insert(
            "sheets", SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("fileName", "song.pdf"); put("path", "/elsewhere/song.pdf"); put("hash", HASH)
                put("title", "Song"); put("composer", ""); put("genre", ""); put("musicalKey", ""); put("tags", "")
            }
        )
    }

    /** An archive as an export would write it, with [database] as its database file. */
    private fun archive(dbVersion: Int, database: ByteArray): ByteArray {
        val manifest = BackupManifest(
            createdAt = System.currentTimeMillis(), appVersion = "test", dbVersion = dbVersion, dbIdentityHash = null,
            device = LibraryBackup.device(), sheets = 1, stamps = 1, strokes = 0
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf(
                "sheets/$HASH.pdf" to pdfBytes,
                BackupManifest.DATABASE to database,
                BackupManifest.MANIFEST to manifest.toJson().toByteArray()
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun entries(zip: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        generateSequence { z.nextEntry?.name }.toList()
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef"
    }
}
