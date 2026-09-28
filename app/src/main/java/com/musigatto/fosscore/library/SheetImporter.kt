package com.musigatto.fosscore.library

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object SheetImporter {
    private const val BUFFER_SIZE = 64 * 1024

    fun import(input: InputStream, destination: File): String {
        val md = MessageDigest.getInstance("MD5")
        try {
            destination.outputStream().use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        md.update(buf, 0, read)
                        out.write(buf, 0, read)
                    }
                }
            }
        } finally {
            input.close()
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun titleFrom(fileName: String): String = fileName.substringBeforeLast('.').trim()
}