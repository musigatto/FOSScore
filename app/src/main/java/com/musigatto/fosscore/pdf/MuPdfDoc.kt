package com.musigatto.fosscore.pdf

import android.content.Context as AndroidContext
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
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

private const val LOG_TAG = "FOSScore-MuPDF"

// Techo de memoria por bitmap (4M px = 16 MB). Es el límite de calidad del zoom: por encima el
// proceso se come la RAM y el sistema lo mata. Upgrade path: renderizar solo el recuadro visible
// (tiles) en lugar de la página entera, como hacen los visores serio.
const val MAX_RENDER_PIXELS = 4_000_000L

// display lists en memoria: permiten re-renderizar a otra resolución sin re-parsear la página
private const val MAX_CACHED_LISTS = 3

// bitmaps ya renderizados por (página, ancho en px)
private const val MAX_CACHED_BITMAPS = 4

// páginas cuyo tamaño se precarga al abrir, para poder maquetar sin esperar al primer render
private const val PRELOAD_SIZES = 4

// por debajo de esto (en puntos) el display list se considera "sin bounds utilizables"
private const val MIN_PAGE_DIM = 0.05f

/** Caja de la página en puntos PDF. */
data class PageBox(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

/**
 * Documento PDF abierto con MuPDF.
 *
 * Toda la API es **síncrona y no thread-safe** (el `Context` de MuPDF es global): el llamador
 * debe serializar con un mutex, igual que se hacía con `PdfRenderer`.
 */
class MuPdfDoc private constructor(
    private val fd: ParcelFileDescriptor,
    private val stream: FdSeekable,
    private val doc: Document
) {
    val pageCount: Int = doc.countPages()

    // se lee desde el main (pageSize) y se escribe desde el dispatcher de render
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
     * Tamaño de la página en puntos. **Lectura pura de memoria** (segura desde el main): solo
     * devuelve algo si ya la pre-cargamos al abrir o si se renderizó.
     */
    fun pageSize(index: Int): PageBox? = sizes[index]

    /**
     * Rasteriza la página al tamaño pedido (px), respetando [MAX_RENDER_PIXELS].
     * Devuelve el bitmap cacheado si esa resolución ya se renderizó.
     */
    /**
     * Rasteriza la página para que ocupe [slotW]x[slotH] px (el hueco donde se encaja), con
     * [quality] = 1 a 3 (multiplicador para el zoom). Hace el ajuste a la caja internamente, así
     * que quien llama no necesita conocer el tamaño de la página.
     */
    fun render(index: Int, slotW: Int, slotH: Int, quality: Int): Bitmap? {
        val list = lists[index] ?: displayList(index) ?: return null
        // OJO: el tamaño sale del DISPLAY LIST, no de page.getBounds(): toDisplayList() aplica la
        // transformación de la página (rotación /Rotate y flip del eje Y), así que sus bounds son
        // los de la página ya enderezada y en espacio y-hacia-abajo.
        val dl = list.getBounds()
        val fromList = PageBox(dl.x0, dl.y0, dl.x1, dl.y1)
        // una página en blanco da un display list con bounds INFINITOS: si nos cuela, la escala sale
        // w/inf = 0 y el pixmap nace vacío. Caemos al tamaño del diccionario de la página.
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

        // encaje en el hueco (mismo criterio que fitRect en la UI) por el multiplicador de calidad
        val fitW = min(slotW.toFloat(), slotH * (pw / ph))
        var w = (fitW * quality).toInt().coerceAtLeast(1)
        // presupuesto de memoria: el pixmap real será w x (w*ph/pw)
        val realH = w * ph / pw
        if (w.toLong() * realH.toLong() > MAX_RENDER_PIXELS) {
            w = sqrt(MAX_RENDER_PIXELS.toDouble() * pw.toDouble() / ph.toDouble())
                .toInt().coerceIn(1, w)
        }
        val key = index.toLong() * 1_000_000L + w
        bitmaps[key]?.let { return it }

        // y hacia abajo en ambos lados: escala positiva, sin el flip que usa el visor
        val scale = w / pw
        val m = Matrix(scale, 0f, 0f, scale, -box.x0 * scale, -box.y0 * scale)
        val t0 = System.nanoTime()
        var pix: Pixmap? = null
        try {
            // alpha=true: sale RGBA y el buffer se vuelca tal cual en un ARGB_8888
            pix = list.toPixmap(m, ColorSpace.DeviceRGB, true)
            val bmp = Bitmap.createBitmap(pix.getWidth(), pix.getHeight(), Bitmap.Config.ARGB_8888)
            val samples = pix.getSamples()
            val stride = pix.getStride()
            val rowBytes = pix.getWidth() * 4
            if (stride == rowBytes) {
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples))
            } else {
                for (y in 0 until pix.getHeight()) {
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(samples, y * stride, rowBytes))
                }
            }
            bitmaps[key] = bmp
            Log.d(
                LOG_TAG,
                "render p$index ${pix.getWidth()}x${pix.getHeight()} " +
                    "en ${(System.nanoTime() - t0) / 1_000_000}ms (hueco ${slotW}x$slotH q$quality)"
            )
            return bmp
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render falló en la página $index: ${e.message}", e)
            return null
        } finally {
            pix?.destroy()
        }
    }

    // Tamaño barato (parsea solo el diccionario de la página, no ejecuta el contenido): sirve para
    // maquetar antes del primer render. En páginas con /Rotate 90/270 el aspecto sale girado hasta
    // que el primer render lo corrige en sizes (un frame de overlay descentrado, nada más).
    private fun loadSize(index: Int): PageBox? {
        if (index !in 0 until pageCount) return null
        val page = loadPage(index) ?: return null
        val b = try {
            page.getBounds()
        } finally {
            page.destroy()
        }
        return PageBox(b.x0, b.y0, b.x1, b.y1).also { sizes[index] = it }
    }

    // El display list incluye el contenido y las anotaciones de la página; permite re-rasterizar
    // a otra resolución sin volver a parsear (es lo que da el zoom nítido).
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

    /** Libera todo. **Bloqueante**: llamarlo fuera del main (ver FOSScoreApp.applicationScope). */
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

        /** Abre un PDF desde un Uri de SAF. Bloqueante: llamar desde un dispatcher de fondo. */
        fun open(context: AndroidContext, uri: Uri): MuPdfDoc {
            if (!initialized) {
                Context.init()
                initialized = true
            }
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("No se pudo abrir el archivo")
            val stream = FdSeekable(fd)
            val doc = try {
                Document.openDocument(stream, "application/pdf")
            } catch (e: Exception) {
                runCatching { stream.close() }
                runCatching { fd.close() }
                throw e
            }
            return MuPdfDoc(fd, stream, doc).also { mupdf ->
                // tamaños por adelantado: la UI maqueta sin tener que esperar al primer render
                for (i in 0 until PRELOAD_SIZES) {
                    if (i >= mupdf.pageCount) break
                    runCatching { mupdf.loadSize(i) }
                }
            }
        }
    }
}

/**
 * MuPDF pide un stream con seek; el PFD de SAF lo da. Se mantiene abierto toda la vida del
 * documento porque MuPDF lee de forma perezosa.
 * ponytail: `RandomAccessFile(FileDescriptor, ...)` no existe en android.jar (es de escritorio),
 * así que leemos con FileInputStream y posicionamos con su FileChannel: ambos comparten el
 * offset del mismo descriptor, así que leen y posicionan de forma coherente.
 */
private class FdSeekable(private val fd: ParcelFileDescriptor) : SeekableInputStream {
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
