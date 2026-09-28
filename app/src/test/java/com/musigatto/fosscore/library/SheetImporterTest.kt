package com.musigatto.fosscore.library

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SheetImporterTest {
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun copiesBytesAndReturnsMd5() {
        val src = File.createTempFile("src", ".pdf")
        src.writeBytes(byteArrayOf(1, 2, 3, 5, 8, 13))
        val dst = File.createTempFile("dst", ".pdf")

        val hash = SheetImporter.import(src.inputStream(), dst)

        assertEquals(
            MessageDigest.getInstance("MD5").digest(src.readBytes()).toHex(),
            hash
        )
        assertArrayEquals(src.readBytes(), dst.readBytes())
    }

    @Test
    fun derivesTitleFromFileName() {
        assertEquals("Preludio 3", SheetImporter.titleFrom("Preludio 3.pdf"))
    }
}