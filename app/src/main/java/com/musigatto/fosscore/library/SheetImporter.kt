package com.musigatto.fosscore.library

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

object SheetImporter {
    fun import(input: InputStream, destination: File): String {
        val md = MessageDigest.getInstance("MD5")
        DigestInputStream(input, md).use { dis ->
            destination.outputStream().use { dis.copyTo(it) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun titleFrom(fileName: String): String = fileName.substringBeforeLast('.').trim()
}