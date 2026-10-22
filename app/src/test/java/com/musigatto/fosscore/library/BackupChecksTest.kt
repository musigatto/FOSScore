package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupChecksTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun manifest(dbVersion: Int = 4, ageDays: Int = 1, device: String = "Samsung GT-P5210", missing: Int = 0) =
        BackupManifest(
            createdAt = now - ageDays * day,
            appVersion = "1.0",
            dbVersion = dbVersion,
            dbIdentityHash = "abc",
            device = device,
            sheets = 3,
            stamps = 2,
            strokes = 1,
            missingFiles = missing
        )

    private fun status(checks: List<BackupCheck>, item: CheckItem) = checks.single { it.item == item }.status

    private fun check(m: BackupManifest, fileVersion: Int? = m.dbVersion) =
        BackupChecks.check(m, appDbVersion = 4, fileDbVersion = fileVersion, now = now, device = "Samsung GT-P5210")

    @Test
    fun sameVersionRecentSameDeviceImportsWithoutQuestion() {
        val checks = check(manifest())
        assertTrue(checks.all { it.status == CheckStatus.OK })
        assertTrue(BackupChecks.canImport(checks))
        assertFalse(BackupChecks.needsConfirmation(checks))
    }

    @Test
    fun olderSchemaAsksForConfirmation() {
        val checks = check(manifest(dbVersion = 2))
        assertEquals(CheckStatus.WARN, status(checks, CheckItem.DATABASE))
        assertTrue(BackupChecks.canImport(checks))
        assertTrue(BackupChecks.needsConfirmation(checks))
    }

    @Test
    fun newerSchemaIsBlocked() {
        val checks = check(manifest(dbVersion = 5))
        assertEquals(CheckStatus.ERROR, status(checks, CheckItem.DATABASE))
        assertFalse(BackupChecks.canImport(checks))
    }

    @Test
    fun unreadableOrMismatchedDatabaseIsBlocked() {
        assertEquals(CheckStatus.ERROR, status(check(manifest(), fileVersion = null), CheckItem.CONTENT))
        assertEquals(CheckStatus.ERROR, status(check(manifest(), fileVersion = 3), CheckItem.CONTENT))
    }

    @Test
    fun oldExportOtherDeviceAndMissingFilesAreWarnings() {
        val checks = check(manifest(ageDays = 45, device = "Google Pixel", missing = 2))
        assertEquals(CheckStatus.WARN, status(checks, CheckItem.AGE))
        assertEquals(CheckStatus.WARN, status(checks, CheckItem.DEVICE))
        assertEquals(CheckStatus.WARN, status(checks, CheckItem.FILES))
        assertTrue(BackupChecks.canImport(checks))
    }

    @Test
    fun laterArchiveLayoutIsBlocked() {
        val checks = check(manifest().copy(format = BackupManifest.FORMAT + 1))
        assertEquals(CheckStatus.ERROR, status(checks, CheckItem.FORMAT))
    }

    @Test
    fun manifestRoundTripIgnoresUnknownFields() {
        val m = manifest(missing = 1)
        assertEquals(m, BackupManifest.fromJson(m.toJson()))
        val later = m.toJson().replaceFirst("{", "{\"setlists\": 4,")
        assertEquals(m, BackupManifest.fromJson(later))
    }

    @Test
    fun onlyExpectedArchiveEntriesAreUnpacked() {
        assertTrue(LibraryBackup.isExpected("manifest.json"))
        assertTrue(LibraryBackup.isExpected("database/fosscore.db"))
        assertTrue(LibraryBackup.isExpected("sheets/0123456789abcdef0123456789abcdef.pdf"))
        assertFalse(LibraryBackup.isExpected("../../databases/fosscore.db"))
        assertFalse(LibraryBackup.isExpected("sheets/../../evil.pdf"))
        assertFalse(LibraryBackup.isExpected("sheets/notahash.pdf"))
    }
}
