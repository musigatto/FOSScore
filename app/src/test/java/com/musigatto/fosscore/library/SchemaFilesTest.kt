package com.musigatto.fosscore.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the files that let every database migration be tested. */
class SchemaFilesTest {

    private val schemas = File("schemas/com.musigatto.fosscore.library.AppDatabase")

    @Test
    fun everyVersionHasItsCommittedSchema() {
        val versions = schemas.list().orEmpty().mapNotNull { it.removeSuffix(".json").toIntOrNull() }.sorted()
        // Room writes the new file at build time: commit it with the version change.
        assertEquals((1..AppDatabase.VERSION).toList(), versions)
    }
}
