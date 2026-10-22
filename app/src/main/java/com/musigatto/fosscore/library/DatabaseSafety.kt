package com.musigatto.fosscore.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_TAG = "FOSScore-DB"

/**
 * Keeps the library safe when the database schema changes.
 *
 * Before Room opens the database:
 * - **upgrade** (the file is older than the app): the file is copied to `db-backups/` first, so a
 *   failed or wrong migration can be undone;
 * - **downgrade** (the file is newer than the app, after installing an older build): the file is
 *   moved to `db-backups/` and the app starts with an empty library, instead of Room refusing to
 *   open it or wiping it. Going back to the newer build later can restore it from there.
 *
 * Only the last [KEEP] backups are kept.
 */
object DatabaseSafety {
    const val BACKUP_DIR = "db-backups"
    const val KEEP = 5

    fun beforeOpen(context: Context, name: String, appVersion: Int) {
        val db = context.getDatabasePath(name)
        if (!db.isFile) return
        val fileVersion = try {
            checkpointAndReadVersion(db)
        } catch (e: SQLiteException) {
            // Unreadable file: let Room report it rather than touching it here.
            Log.w(LOG_TAG, "could not read the database version", e)
            return
        }
        val dir = File(context.filesDir, BACKUP_DIR)
        when {
            // 0: created but never initialised by Room.
            fileVersion == 0 || fileVersion == appVersion -> return
            fileVersion < appVersion -> {
                copy(db, File(dir, backupName(name, fileVersion, "before-v$appVersion")))
                Log.i(LOG_TAG, "database v$fileVersion saved before migrating to v$appVersion")
            }
            else -> {
                moveAside(db, File(dir, backupName(name, fileVersion, "newer-than-v$appVersion")))
                Log.w(LOG_TAG, "database v$fileVersion is newer than this app (v$appVersion): moved to $BACKUP_DIR")
            }
        }
        prune(dir)
    }

    /**
     * Saves a complete copy of the database [name] to the backup folder, tagged with [reason].
     * Only while Room has not opened it (at startup). Returns the copy, or null without a database.
     */
    fun saveCopy(context: Context, name: String, reason: String): File? {
        val db = context.getDatabasePath(name)
        if (!db.isFile) return null
        val version = checkpointAndReadVersion(db)
        val dir = File(context.filesDir, BACKUP_DIR)
        val target = File(dir, backupName(name, version, reason))
        copy(db, target)
        prune(dir)
        return target
    }

    /** The saved databases, newest first. */
    fun backups(context: Context): List<File> =
        File(context.filesDir, BACKUP_DIR).listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.name }

    /**
     * Writes the content of the write-ahead log into the main file, so that copying that file
     * alone gives a complete database, and returns the schema version stored in the file.
     */
    internal fun checkpointAndReadVersion(db: File): Int =
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { sqlite ->
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            sqlite.version
        }

    private fun copy(db: File, target: File) {
        target.parentFile?.mkdirs()
        db.copyTo(target, overwrite = true)
    }

    private fun moveAside(db: File, target: File) {
        target.parentFile?.mkdirs()
        if (!db.renameTo(target)) {
            db.copyTo(target, overwrite = true)
            if (!db.delete()) throw IOException("could not move ${db.name} aside")
        }
        // The log and shared-memory files belong to the moved database.
        for (suffix in listOf("-wal", "-shm", "-journal")) File(db.path + suffix).delete()
    }

    private fun prune(dir: File) {
        val names = dir.list().orEmpty().toList()
        for (name in toDelete(names)) File(dir, name).delete()
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

    /** `fosscore-20261017-093000-v4-before-v5.db`: sorting by name sorts by date. */
    internal fun backupName(name: String, version: Int, reason: String, time: String = timestamp()): String =
        "${name.removeSuffix(".db")}-$time-v$version-$reason.db"

    /** The backup files beyond the [KEEP] newest. */
    internal fun toDelete(names: List<String>): List<String> = names.sortedDescending().drop(KEEP)
}
