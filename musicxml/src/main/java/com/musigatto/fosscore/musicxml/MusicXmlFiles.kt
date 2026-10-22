package com.musigatto.fosscore.musicxml

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Reading MusicXML files as they come from the file picker: plain (.musicxml, .xml) or compressed (.mxl). */
object MusicXmlFiles {

    // Real scores are well below 10 MB; the limits only stop a broken or hostile archive.
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 1_000

    /** True for a ZIP archive, which is what a compressed MusicXML (.mxl) file is. */
    fun isCompressed(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /**
     * The score itself: [bytes] unchanged for plain MusicXML, the root file of the archive for
     * .mxl (named by `META-INF/container.xml`, or else its first XML file outside `META-INF`).
     *
     * @throws IOException if the archive holds no score or is too large.
     */
    fun scoreBytes(bytes: ByteArray): ByteArray = if (isCompressed(bytes)) unzipRootFile(bytes) else bytes

    /**
     * True when [bytes] look like a MusicXML score (`score-partwise` or `score-timewise` root),
     * without parsing the whole file. Lets an importer refuse other XML files early.
     */
    fun looksLikeMusicXml(bytes: ByteArray): Boolean {
        val head = runCatching { scoreBytes(bytes) }.getOrNull() ?: return false
        val text = decodeHead(head)
        return ROOT.containsMatchIn(text)
    }

    private val ROOT = Regex("""<score-(partwise|timewise)[\s>]""")

    // The root element comes after the declaration, the DOCTYPE and maybe a comment: 4 KB is plenty.
    private fun decodeHead(b: ByteArray): String {
        val n = minOf(b.size, 4096)
        return when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 0, n, Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 0, n, Charsets.UTF_16BE)
            else -> String(b, 0, n, Charsets.UTF_8)
        }
    }

    private fun unzipRootFile(bytes: ByteArray): ByteArray {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entries.size >= MAX_ENTRIES) throw IOException("too many files in the archive")
                if (entry.isDirectory) continue
                // Only XML files can hold the score; images and other attachments are skipped.
                if (!entry.name.endsWith(".xml", ignoreCase = true) &&
                    !entry.name.endsWith(".musicxml", ignoreCase = true)
                ) continue
                entries[entry.name] = zip.readLimited()
            }
        }
        val container = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val rootPath = container?.let { FULL_PATH.find(it)?.groupValues?.get(1) }
        return rootPath?.let { entries[it] }
            ?: entries.entries.firstOrNull { !it.key.startsWith("META-INF/") }?.value
            ?: throw IOException("no score in the archive")
    }

    private val FULL_PATH = Regex("""full-path="([^"]+)"""")

    private fun ZipInputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw IOException("file too large in the archive")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
