package com.musigatto.fosscore.library

import org.json.JSONObject

/**
 * What a library export holds, written as `manifest.json` at the root of the archive and
 * checked before an import.
 */
data class BackupManifest(
    /** Version of the archive layout ([FORMAT]); not the database version. */
    val format: Int = FORMAT,
    /** Export time, in milliseconds since 1970. */
    val createdAt: Long,
    val appVersion: String,
    /** Room schema version of the database in the archive. */
    val dbVersion: Int,
    /** Room's identity hash of that schema, to tell two schemas with the same number apart. */
    val dbIdentityHash: String?,
    val device: String,
    val sheets: Int,
    val stamps: Int,
    val strokes: Int,
    /** Sheets whose PDF file could not be found at export time. */
    val missingFiles: Int = 0
) {
    fun toJson(): String = JSONObject().apply {
        put("format", format)
        put("createdAt", createdAt)
        put("appVersion", appVersion)
        put("dbVersion", dbVersion)
        put("dbIdentityHash", dbIdentityHash)
        put("device", device)
        put("sheets", sheets)
        put("stamps", stamps)
        put("strokes", strokes)
        put("missingFiles", missingFiles)
    }.toString(2)

    companion object {
        /** Archive layout written by this app. Change it only when the layout changes. */
        const val FORMAT = 1

        const val MANIFEST = "manifest.json"
        const val DATABASE = "database/fosscore.db"
        const val SCHEMA = "database/schema.sql"
        const val SHEETS_DIR = "sheets/"

        /** Reads a manifest; fields added by later formats are ignored, missing ones get defaults. */
        fun fromJson(json: String): BackupManifest {
            val o = JSONObject(json)
            return BackupManifest(
                format = o.getInt("format"),
                createdAt = o.optLong("createdAt"),
                appVersion = o.optString("appVersion"),
                dbVersion = o.getInt("dbVersion"),
                dbIdentityHash = o.optString("dbIdentityHash").ifEmpty { null },
                device = o.optString("device"),
                sheets = o.optInt("sheets"),
                stamps = o.optInt("stamps"),
                strokes = o.optInt("strokes"),
                missingFiles = o.optInt("missingFiles")
            )
        }
    }
}

/** Result of one check, as in AndroidAPS's settings import: an ERROR blocks the import. */
enum class CheckStatus { OK, WARN, ERROR }

enum class CheckItem {
    /** The archive layout. */
    FORMAT,

    /** The database schema version against the app's. */
    DATABASE,

    /** The database file itself (readable, version as announced). */
    CONTENT,

    /** How old the export is. */
    AGE,

    /** Whether it comes from another device. */
    DEVICE,

    /** PDFs that were missing at export time. */
    FILES
}

data class BackupCheck(val item: CheckItem, val status: CheckStatus)

object BackupChecks {
    /** Like AndroidAPS: an export older than this gets a warning. */
    const val OLD_EXPORT_DAYS = 30

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Checks [manifest] against this app. [fileDbVersion] is the version read from the database
     * file of the archive (null when it could not be read).
     */
    fun check(
        manifest: BackupManifest,
        appDbVersion: Int,
        fileDbVersion: Int?,
        now: Long,
        device: String
    ): List<BackupCheck> = listOf(
        BackupCheck(CheckItem.FORMAT, if (manifest.format <= BackupManifest.FORMAT) CheckStatus.OK else CheckStatus.ERROR),
        BackupCheck(
            CheckItem.DATABASE,
            when {
                manifest.dbVersion == appDbVersion -> CheckStatus.OK
                // Older: Room migrates it at restart, with the same migrations as an app update.
                manifest.dbVersion < appDbVersion -> CheckStatus.WARN
                // Newer: this app does not know that schema. Update the app first.
                else -> CheckStatus.ERROR
            }
        ),
        BackupCheck(
            CheckItem.CONTENT,
            if (fileDbVersion != null && fileDbVersion == manifest.dbVersion) CheckStatus.OK else CheckStatus.ERROR
        ),
        BackupCheck(
            CheckItem.AGE,
            if (now - manifest.createdAt > OLD_EXPORT_DAYS * DAY_MS) CheckStatus.WARN else CheckStatus.OK
        ),
        BackupCheck(CheckItem.DEVICE, if (manifest.device == device) CheckStatus.OK else CheckStatus.WARN),
        BackupCheck(CheckItem.FILES, if (manifest.missingFiles == 0) CheckStatus.OK else CheckStatus.WARN)
    )

    fun canImport(checks: List<BackupCheck>): Boolean = checks.none { it.status == CheckStatus.ERROR }

    fun needsConfirmation(checks: List<BackupCheck>): Boolean = checks.any { it.status == CheckStatus.WARN }
}
