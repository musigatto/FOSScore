package com.musigatto.fosscore.musicxml

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Thin wrapper over the JNI bridge to one `vrv::Toolkit`.
 *
 * **Not thread-safe:** [ScoreEngine] calls it from its own single thread only.
 */
internal class Verovio(resourcePath: String) : AutoCloseable {
    private var handle: Long = nativeCreate(resourcePath.utf8())

    val version: String get() = nativeVersion(live()).text()
    val pageCount: Int get() = nativePageCount(live())

    /** Messages of the last call (warnings of the last load, for instance). */
    val log: String get() = nativeLog(live()).text()

    fun setOptions(json: String): Boolean = nativeSetOptions(live(), json.utf8())

    /** Loads MusicXML or MEI text; Verovio detects which. */
    fun load(xml: String): Boolean = nativeLoadData(live(), xml.utf8())

    fun redoLayout(json: String = "") = nativeRedoLayout(live(), json.utf8())

    /** Page [page] (1-based) as SVG, with its XML declaration. */
    fun renderSvg(page: Int): String = nativeRenderSvg(live(), page).text()

    /** Base64-encoded standard MIDI file of the loaded score. */
    fun renderMidi(): String = nativeRenderMidi(live()).text()

    /** JSON array: for each onset, its time in ms (`tstamp`), in quarter notes (`qstamp`), notes on/off. */
    fun renderTimemap(): String = nativeRenderTimemap(live()).text()

    /** The loaded score as MEI, Verovio's own format, with any transposition already applied. */
    fun mei(): String = nativeGetMei(live()).text()

    /** The page (1-based) that holds the element with this id, or 0. */
    fun pageWithElement(id: String): Int = nativePageWithElement(live(), id.utf8())

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private fun live(): Long = handle.also { check(it != 0L) { "Verovio toolkit already closed" } }

    companion object {
        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("verovio")
            System.loadLibrary("verovio_jni")
        }

        // Change it whenever the shipped data changes (Verovio update, other fonts).
        private const val DATA_VERSION = "6.3.0-leipzig-bravura"

        /**
         * Copies Verovio's font data out of the APK, once per data version, and returns its
         * folder: Verovio reads its resources from real files.
         */
        fun prepareResources(context: Context): String {
            val root = File(context.noBackupFilesDir, "verovio")
            val data = File(root, "data")
            val stamp = File(root, "version")
            if (!stamp.isFile || stamp.readText() != DATA_VERSION) {
                data.deleteRecursively()
                copyAssets(context.assets, "verovio/data", data)
                stamp.writeText(DATA_VERSION)
            }
            return data.absolutePath
        }

        private fun copyAssets(assets: AssetManager, from: String, to: File) {
            val children = assets.list(from).orEmpty()
            if (children.isEmpty()) {
                to.parentFile?.mkdirs()
                assets.open(from).use { input -> to.outputStream().use { input.copyTo(it) } }
                return
            }
            to.mkdirs()
            for (child in children) copyAssets(assets, "$from/$child", File(to, child))
        }

        private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
        private fun ByteArray.text(): String = toString(Charsets.UTF_8)

        @JvmStatic private external fun nativeCreate(resourcePath: ByteArray): Long
        @JvmStatic private external fun nativeDestroy(h: Long)
        @JvmStatic private external fun nativeVersion(h: Long): ByteArray
        @JvmStatic private external fun nativeSetOptions(h: Long, json: ByteArray): Boolean
        @JvmStatic private external fun nativeLoadData(h: Long, data: ByteArray): Boolean
        @JvmStatic private external fun nativeRedoLayout(h: Long, json: ByteArray)
        @JvmStatic private external fun nativePageCount(h: Long): Int
        @JvmStatic private external fun nativeRenderSvg(h: Long, page: Int): ByteArray
        @JvmStatic private external fun nativeRenderMidi(h: Long): ByteArray
        @JvmStatic private external fun nativeRenderTimemap(h: Long): ByteArray
        @JvmStatic private external fun nativeGetMei(h: Long): ByteArray
        @JvmStatic private external fun nativePageWithElement(h: Long, id: ByteArray): Int
        @JvmStatic private external fun nativeLog(h: Long): ByteArray
    }
}
