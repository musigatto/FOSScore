package com.musigatto.fosscore.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val LOG_TAG = "FOSScore-Backup"

/**
 * Export and import of the whole library: the database file itself, its schema, and the score files.
 *
 * **Export** ([export]) writes a ZIP archive:
 * - `manifest.json`: [BackupManifest] (schema version, app version, date, device, counts);
 * - `database/fosscore.db`: a consistent copy of the database, taken while writes are blocked;
 * - `database/schema.sql`: the schema of that database, for people;
 * - `sheets/<hash>.<extension>`: the file of each sheet, named by its MD5 hash. It keeps its
 *   extension (`pdf`, or another score format), which is how the app tells the formats apart.
 *
 * **Import** happens in three steps, as in AndroidAPS's settings import:
 * 1. [inspect] unpacks the archive aside and checks it ([BackupChecks]): an ERROR (newer schema,
 *    unreadable database) blocks it, a WARN (older schema, old export, other device) asks the user;
 * 2. [stage] marks it to be applied, then the app restarts;
 * 3. at the next start, before Room opens the database, [applyPending] saves the current library
 *    and puts the imported one in place. An older schema is then migrated by Room with the same
 *    migrations as an app update, and [relinkIfNeeded] points the sheets to their new files.
 *
 * All functions do file work: call them off the main thread.
 */
class LibraryBackup(private val context: Context, private val dbName: String = AppDatabase.NAME) {

    /** An unpacked archive waiting for the user's decision. */
    class Inspection internal constructor(val manifest: BackupManifest?, val checks: List<BackupCheck>) {
        val canImport: Boolean get() = manifest != null && BackupChecks.canImport(checks)
        val needsConfirmation: Boolean get() = BackupChecks.needsConfirmation(checks)
    }

    /** Writes the export archive of [db] (the open database named [dbName]) to [out]. */
    fun export(db: RoomDatabase, out: OutputStream): BackupManifest {
        val work = File(context.cacheDir, "export").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val copy = File(work, "fosscore.db")
            snapshot(db, copy)
            val version = DatabaseSafety.checkpointAndReadVersion(copy)
            for (suffix in listOf("-wal", "-shm")) File(copy.path + suffix).delete()

            val content = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { readContent(it) }
            var missing = 0
            ZipOutputStream(out.buffered()).use { zip ->
                for ((hash, path) in content.files) {
                    val file = File(path)
                    if (!file.isFile) {
                        missing++
                        continue
                    }
                    zip.putNextEntry(ZipEntry("${BackupManifest.SHEETS_DIR}$hash.${extensionOf(path)}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(BackupManifest.DATABASE))
                copy.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BackupManifest.SCHEMA))
                zip.write(content.schema.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                val manifest = BackupManifest(
                    createdAt = System.currentTimeMillis(),
                    appVersion = appVersion(),
                    dbVersion = version,
                    dbIdentityHash = content.identityHash,
                    device = device(),
                    sheets = content.sheets,
                    stamps = content.stamps,
                    strokes = content.strokes,
                    missingFiles = missing
                )
                // Last, so it can report the missing files.
                zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST))
                zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                return manifest
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Copies the database file and its write-ahead log inside a write transaction: other writers
     * wait, so the two files form one consistent state.
     */
    private fun snapshot(db: RoomDatabase, copy: File) {
        val source = context.getDatabasePath(dbName)
        db.runInTransaction {
            source.copyTo(copy, overwrite = true)
            val wal = File(source.path + "-wal")
            if (wal.isFile) wal.copyTo(File(copy.path + "-wal"), overwrite = true)
        }
    }

    private class Content(
        val identityHash: String?,
        val schema: String,
        val sheets: Int,
        val stamps: Int,
        val strokes: Int,
        /** Hash → file path, one per distinct hash. */
        val files: Map<String, String>
    )

    private fun readContent(db: SQLiteDatabase): Content {
        fun count(table: String) = try {
            db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (_: SQLiteException) {
            0
        }
        val identity = try {
            db.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: SQLiteException) {
            null
        }
        val schema = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY CASE type WHEN 'table' THEN 0 ELSE 1 END, name",
            null
        ).use { c -> buildString { while (c.moveToNext()) append(c.getString(0)).append(";\n") } }
        val files = linkedMapOf<String, String>()
        db.rawQuery("SELECT hash, path FROM sheets", null).use { c ->
            while (c.moveToNext()) {
                val hash = c.getString(0)
                if (HASH.matches(hash) && hash !in files) files[hash] = c.getString(1)
            }
        }
        return Content(identity, schema, count("sheets"), count("stamps"), count("strokes"), files)
    }

    /**
     * Unpacks the archive [input] aside and checks it against this app. Nothing in the library
     * changes. Follow with [stage] or [discard].
     */
    fun inspect(input: InputStream): Inspection {
        val dir = File(context.filesDir, PENDING_TMP).apply {
            deleteRecursively()
            mkdirs()
        }
        val unreadable = Inspection(null, listOf(BackupCheck(CheckItem.FORMAT, CheckStatus.ERROR)))
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Only the expected names: nothing can be written outside the folder.
                    if (entry.isDirectory || !isExpected(entry.name)) continue
                    val target = File(dir, entry.name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        } catch (e: IOException) {
            // Not a ZIP, truncated, or refused by Android (entries leaving the folder).
            Log.w(LOG_TAG, "unreadable archive", e)
            return unreadable
        }
        val manifest = File(dir, BackupManifest.MANIFEST).takeIf { it.isFile }?.let {
            try {
                BackupManifest.fromJson(it.readText())
            } catch (e: Exception) {
                Log.w(LOG_TAG, "unreadable manifest", e)
                null
            }
        } ?: return unreadable
        val checks = BackupChecks.check(
            manifest,
            appDbVersion = AppDatabase.VERSION,
            fileDbVersion = readableVersion(File(dir, BackupManifest.DATABASE)),
            now = System.currentTimeMillis(),
            device = device()
        )
        return Inspection(manifest, checks)
    }

    /** Marks the inspected archive to be applied at the next start. The app must restart then. */
    fun stage(inspection: Inspection) {
        check(inspection.canImport) { "this archive cannot be imported" }
        val pending = File(context.filesDir, PENDING)
        pending.deleteRecursively()
        check(File(context.filesDir, PENDING_TMP).renameTo(pending)) { "could not stage the import" }
    }

    /** Forgets an inspected archive that will not be imported. */
    fun discard() {
        File(context.filesDir, PENDING_TMP).deleteRecursively()
    }

    // The version stored in the database file, if it opens and passes SQLite's quick check.
    private fun readableVersion(db: File): Int? {
        if (!db.isFile) return null
        return try {
            SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                val ok = sqlite.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                if (ok) sqlite.version else null
            }
        } catch (e: SQLiteException) {
            Log.w(LOG_TAG, "unreadable database in the archive", e)
            null
        }
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    companion object {
        /** Folder of the library's PDFs, inside `filesDir`. */
        const val SHEETS_DIR = "sheets"

        /** The PDFs of the library replaced by the last import, kept until the next one. */
        const val SHEETS_BEFORE_IMPORT = "sheets-before-import"

        private const val PENDING_TMP = "import-inspected"
        private const val PENDING = "import-pending"
        private const val RELINK = "import-relink"

        private val HASH = Regex("[0-9a-f]{32}")
        private val EXTENSION = Regex("[a-z0-9]{1,8}")
        private val SHEET_ENTRY = Regex("""${BackupManifest.SHEETS_DIR}[0-9a-f]{32}\.[a-z0-9]{1,8}""")

        /** The extension a sheet's file keeps in the archive and after an import (`pdf` if it has none). */
        internal fun extensionOf(path: String): String =
            File(path).extension.lowercase().takeIf { EXTENSION.matches(it) } ?: "pdf"

        internal fun isExpected(name: String): Boolean =
            name == BackupManifest.MANIFEST || name == BackupManifest.DATABASE || name == BackupManifest.SCHEMA ||
                SHEET_ENTRY.matches(name)

        fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        /** True when an import is staged and will be applied at the next start. */
        fun hasPending(context: Context): Boolean = File(context.filesDir, PENDING).exists()

        /** Cancels a staged import before the restart. */
        fun cancelPending(context: Context) {
            File(context.filesDir, PENDING).deleteRecursively()
        }

        /**
         * Puts a staged import in place. Called once at startup, before Room opens [dbName].
         * Returns true when an import was applied.
         */
        fun applyPending(context: Context, dbName: String = AppDatabase.NAME, appDbVersion: Int = AppDatabase.VERSION): Boolean {
            val files = context.filesDir
            val pending = File(files, PENDING)
            if (!pending.exists()) return false
            try {
                val manifest = BackupManifest.fromJson(File(pending, BackupManifest.MANIFEST).readText())
                val database = File(pending, BackupManifest.DATABASE)
                // Checked at inspection already; checked again in case the app was downgraded since.
                if (manifest.dbVersion > appDbVersion || !database.isFile) {
                    Log.w(LOG_TAG, "staged import v${manifest.dbVersion} cannot be applied by v$appDbVersion: dropped")
                    return false
                }
                DatabaseSafety.saveCopy(context, dbName, "before-import")

                // The new database next to the current one first: the final swap is a rename.
                val target = context.getDatabasePath(dbName)
                target.parentFile?.mkdirs()
                val incoming = File(target.path + ".import")
                if (!database.renameTo(incoming)) database.copyTo(incoming, overwrite = true)

                // PDFs: the current folder is kept aside; put back if the new one cannot take its place.
                val sheets = File(files, SHEETS_DIR)
                val previous = File(files, SHEETS_BEFORE_IMPORT)
                previous.deleteRecursively()
                if (sheets.exists() && !sheets.renameTo(previous)) error("could not keep the current PDFs")
                val imported = File(pending, SHEETS_DIR)
                if (imported.isDirectory && !imported.renameTo(sheets)) {
                    previous.renameTo(sheets)
                    incoming.delete()
                    error("could not put the imported PDFs in place")
                }
                sheets.mkdirs()

                for (suffix in listOf("", "-wal", "-shm", "-journal")) File(target.path + suffix).delete()
                if (!incoming.renameTo(target)) error("could not put the imported database in place")
                File(files, RELINK).createNewFile()
                Log.i(LOG_TAG, "import applied: ${manifest.sheets} sheets, database v${manifest.dbVersion}")
                return true
            } catch (e: Exception) {
                Log.e(LOG_TAG, "could not apply the import", e)
                return false
            } finally {
                pending.deleteRecursively()
            }
        }

        /**
         * After an import, points every sheet to its file in this app's folder (the paths in the
         * archive belong to the device that exported it), keeping the extension of its old path,
         * as the export did. Called when Room opens the database, after the migrations, so it
         * uses the current schema.
         */
        fun relinkIfNeeded(context: Context, db: SupportSQLiteDatabase) {
            val marker = File(context.filesDir, RELINK)
            if (!marker.exists()) return
            val dir = File(context.filesDir, SHEETS_DIR)
            val links = mutableListOf<Pair<Long, String>>()
            db.query("SELECT id, hash, path FROM sheets").use { c ->
                while (c.moveToNext()) {
                    val file = File(dir, "${c.getString(1)}.${extensionOf(c.getString(2))}")
                    links += c.getLong(0) to file.absolutePath
                }
            }
            for ((id, path) in links) db.execSQL("UPDATE sheets SET path = ? WHERE id = ?", arrayOf<Any>(path, id))
            marker.delete()
        }
    }
}
