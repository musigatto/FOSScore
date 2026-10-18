package com.musigatto.fosscore.pdf

import android.content.Context as AndroidContext
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.musigatto.fosscore.R
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Pixmap
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap

private const val LOG_TAG = "FOSScore-MuPDF"

// ponytail: memory cap per bitmap (4M px = 16 MB) is the limit on zoom quality: past it the
// process eats the RAM and the system kills it. Upgrade path: draw only the visible part
// (tiles) instead of the whole page.
const val MAX_RENDER_PIXELS = 4_000_000L

// display lists in memory: they let us draw the page again at another resolution without
// parsing it a second time
private const val MAX_CACHED_LISTS = 3

// bitmaps already drawn, keyed by (page, width in px)
private const val MAX_CACHED_BITMAPS = 4

// pages whose size is loaded up front on open, so the UI can lay out without waiting for the
// first render
private const val PRELOAD_SIZES = 4

// below this size (in points) a display list counts as having no usable bounds
private const val MIN_PAGE_DIM = 0.05f

/** The page box, in PDF points. */
data class PageBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

/**
 * A PDF document opened with MuPDF.
 *
 * The whole API is **synchronous and not thread-safe** (MuPDF's `Context` is shared): callers
 * must take a mutex, the same way the old `PdfRenderer` code did.
 */
class MuPdfDoc private constructor(
    private val fd: ParcelFileDescriptor,
    private val stream: FdSeekable,
    private val doc: Document
) {
    val pageCount: Int = doc.countPages()

    // read from the main thread (pageSize), written from the render dispatcher
    private val sizes = ConcurrentHashMap<Int, PageBox>()
    private val lists = object : LinkedHashMap<Int, DisplayList>(MAX_CACHED_LISTS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DisplayList>?): Boolean {
            if (size <= MAX_CACHED_LISTS) return false
            eldest?.value?.destroy()
            return true
        }
    }
    private val bitmaps = object : LinkedHashMap<Long, Bitmap>(MAX_CACHED_BITMAPS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) =
            size > MAX_CACHED_BITMAPS
    }

    /**
     * Page size in points. **Reads memory only** (safe from the main thread): it answers only
     * if the size was loaded up front on open, or the page has been drawn already.
     */
    fun pageSize(index: Int): PageBox? = sizes[index]

    /**
     * Same as [pageSize], but loads the size now if it is not in yet (pages past the up-front
     * ones). It reaches into MuPDF ([loadSize]), so call it under the viewer's render mutex.
     */
    fun ensurePageSize(index: Int): PageBox? = sizes[index] ?: loadSize(index)

    /**
     * Draws the page as a bitmap that fits a [slotW]x[slotH] px hole.
     * It does the fitting itself, so callers do not need to know the page size.
     */
    fun render(index: Int, slotW: Int, slotH: Int): Bitmap? {
        val list = lists[index] ?: displayList(index) ?: return null
        // NOTE: the size comes from the DISPLAY LIST, not from page.getBounds(): toDisplayList()
        // applies the page transform (/Rotate and the Y flip), so its bounds are those of the
        // page already straightened out, with y running down.
        val dl = list.bounds
        val fromList = PageBox(dl.x0, dl.y0, dl.x1, dl.y1)
        // a blank page gives a display list with INFINITE bounds: if that slips through, the
        // scale comes out as w/inf = 0 and the bitmap is born empty. Fall back to the dictionary.
        val box = if (fromList.width.isFinite() && fromList.height.isFinite() &&
            fromList.width > MIN_PAGE_DIM && fromList.height > MIN_PAGE_DIM
        ) {
            sizes[index] = fromList
            fromList
        } else {
            Log.w(LOG_TAG, "página $index: display list sin bounds (¿en blanco?), uso MediaBox")
            sizes[index] ?: loadSize(index) ?: return null
        }
        if (slotW <= 0 || slotH <= 0) return null
        val pw = box.width
        val ph = box.height
        if (!pw.isFinite() || !ph.isFinite() || pw <= 0f || ph <= 0f) return null

        // fit into the hole (same rule as fitRect in the UI)
        val fitW = min(slotW.toFloat(), slotH * (pw / ph))
        var w = fitW.toInt().coerceAtLeast(1)
        // memory budget: the real pixmap will be w x (w*ph/pw)
        val realH = w * ph / pw
        if (w.toLong() * realH.toLong() > MAX_RENDER_PIXELS) {
            w = sqrt(MAX_RENDER_PIXELS.toDouble() * pw.toDouble() / ph.toDouble())
                .toInt().coerceIn(1, w)
        }
        val key = index.toLong() * 1_000_000L + w
        bitmaps[key]?.let { return it }

        // y down on both sides: positive scale, no sign flip like the viewer does
        val scale = w / pw
        val m = Matrix(scale, 0f, 0f, scale, -box.x0 * scale, -box.y0 * scale)
        val t0 = System.nanoTime()
        var pix: Pixmap? = null
        try {
            // alpha=true: comes out RGBA, and the buffer drops straight into an ARGB_8888
            pix = list.toPixmap(m, ColorSpace.DeviceRGB, true)
            val rgba = createBitmap(pix.width, pix.height)
            val samples = pix.samples
            val stride = pix.stride
            val rowBytes = pix.width * 4
            if (stride == rowBytes) {
                rgba.copyPixelsFromBuffer(ByteBuffer.wrap(samples))
            } else {
                for (y in 0 until pix.height) {
                    rgba.copyPixelsFromBuffer(ByteBuffer.wrap(samples, y * stride, rowBytes))
                }
            }
            // the pixmap comes out with TRANSPARENT paper: composite it onto white so the page is
            // opaque. Otherwise "Neg" inverts the ink but not the background (whatever is behind
            // shows through, white in light theme) and the score ends up white on white.
            val bmp = createBitmap(rgba.width, rgba.height)
            bmp.eraseColor(android.graphics.Color.WHITE)
            Canvas(bmp).drawBitmap(rgba, 0f, 0f, null)
            bmp.setHasAlpha(false)
            rgba.recycle()
            bitmaps[key] = bmp
            Log.d(
                LOG_TAG,
                "render p$index ${pix.width}x${pix.height} " +
                    "en ${(System.nanoTime() - t0) / 1_000_000}ms (hueco ${slotW}x$slotH)"
            )
            return bmp
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render falló en la página $index: ${e.message}", e)
            return null
        } finally {
            pix?.destroy()
        }
    }

    // Cheap size (reads just the page dictionary, does not run the content): enough to lay out
    // before the first draw. On pages with /Rotate 90/270 the shape comes out turned until the
    // first draw corrects it in sizes (one frame of an off-centre overlay, nothing more).
    private fun loadSize(index: Int): PageBox? {
        if (index !in 0 until pageCount) return null
        val page = loadPage(index) ?: return null
        val b = try {
            page.bounds
        } finally {
            page.destroy()
        }
        return PageBox(b.x0, b.y0, b.x1, b.y1).also { sizes[index] = it }
    }

    // The display list holds the page content and its annotations; it lets us draw again at
    // another resolution without parsing a second time (that is what keeps zoom sharp).
    private fun displayList(index: Int): DisplayList? {
        val page = loadPage(index) ?: return null
        return try {
            page.toDisplayList().also { lists[index] = it }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo preparar la página $index: ${e.message}", e)
            null
        } finally {
            page.destroy()
        }
    }

    private fun loadPage(index: Int): Page? =
        if (index in 0 until pageCount) {
            try {
                doc.loadPage(index)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "no se pudo cargar la página $index: ${e.message}", e)
                null
            }
        } else null

    /**
     * Drops what is cached for a page (display list + bitmaps) so it gets parsed and drawn again.
     * For after the PDF is edited behind our back (exporting annotations): otherwise the old
     * bitmap would hide the ink that was just saved. Call it under the viewer's mutex.
     */
    fun forgetPage(index: Int) {
        lists.remove(index)?.destroy()
        bitmaps.keys.filter { it / 1_000_000L == index.toLong() }.forEach { bitmaps.remove(it) }
    }

    /** Frees everything. **Blocking**: call it off the main thread (see FOSScoreApp.applicationScope). */
    fun close() {
        lists.values.forEach { runCatching { it.destroy() } }
        lists.clear()
        bitmaps.values.forEach { runCatching { it.recycle() } }
        bitmaps.clear()
        runCatching { doc.destroy() }
        runCatching { stream.close() }
        runCatching { fd.close() }
    }

    companion object {
        private var initialized = false

        /** Opens a PDF from a SAF Uri. Blocking: call it from a background dispatcher. */
        fun open(context: AndroidContext, uri: Uri): MuPdfDoc {
            if (!initialized) {
                Context.init()
                initialized = true
            }
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException(context.getString(R.string.err_open_file))
            val stream = FdSeekable(fd)
            val doc = try {
                Document.openDocument(stream, "application/pdf")
            } catch (e: Exception) {
                runCatching { stream.close() }
                runCatching { fd.close() }
                throw e
            }
            return MuPdfDoc(fd, stream, doc).also { mupdf ->
                // sizes up front: the UI can lay out without waiting for the first draw
                for (i in 0 until PRELOAD_SIZES) {
                    if (i >= mupdf.pageCount) break
                    runCatching { mupdf.loadSize(i) }
                }
            }
        }
    }
}

/**
 * MuPDF wants a stream it can seek, and the SAF file descriptor gives us one. It stays open for
 * the whole life of the document because MuPDF reads lazily.
 * ponytail: `RandomAccessFile(FileDescriptor, ...)` does not exist in android.jar (that is a
 * desktop thing), so we read with FileInputStream and seek with its FileChannel: both share the
 * offset of the same descriptor, so reading and seeking stay in step.
 */
private class FdSeekable(fd: ParcelFileDescriptor) : SeekableInputStream {
    private val fis = FileInputStream(fd.fileDescriptor)
    private val channel = fis.channel
    private var closed = false

    override fun read(buf: ByteArray): Int = fis.read(buf)

    override fun seek(pos: Long, whence: Int): Long {
        val target = when (whence) {
            SeekableStream.SEEK_CUR -> channel.position() + pos
            SeekableStream.SEEK_END -> channel.size() + pos
            else -> pos
        }
        channel.position(target)
        return channel.position()
    }

    override fun position(): Long = channel.position()

    fun close() {
        if (closed) return
        closed = true
        runCatching { fis.close() }
    }
}
