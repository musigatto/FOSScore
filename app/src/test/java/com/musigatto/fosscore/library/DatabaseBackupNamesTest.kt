package com.musigatto.fosscore.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseBackupNamesTest {

    @Test
    fun backupNamesSortByDate() {
        val older = DatabaseSafety.backupName("fosscore.db", 3, "before-v4", "20261001-120000")
        val newer = DatabaseSafety.backupName("fosscore.db", 4, "newer-than-v3", "20261017-090000")
        assertEquals("fosscore-20261001-120000-v3-before-v4.db", older)
        assertTrue(newer > older)
    }

    @Test
    fun onlyTheNewestBackupsAreKept() {
        val names = (1..8).map { DatabaseSafety.backupName("fosscore.db", 4, "before-v5", "2026100$it-000000") }
        val deleted = DatabaseSafety.toDelete(names.shuffled())
        assertEquals(names.take(8 - DatabaseSafety.KEEP).toSet(), deleted.toSet())
    }
}
