package com.musigatto.fosscore.pdf

import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Rect
import com.musigatto.fosscore.R
import java.io.File

private const val LOG_TAG = "FOSScore-Annots"

/** Typed ink-export error: each case carries its own string (see values/strings.xml). */
sealed class ExportError(val res: Int) : Exception() {
    class NoLocalFile : ExportError(R.string.err_no_local_file)
    class NotEditable : ExportError(R.string.err_pdf_not_editable)
    class ReplaceFailed : ExportError(R.string.err_replace_failed)
    class NoInk : ExportError(R.string.err_no_ink)
}

/**
 * Ink as fractions of the page you see (0..1, y running down): what the DB stores.
 * No Room IDs or metadata, so this package stays clear of the data layer.
 */
data class InkStroke(val points: List<Offset>, val widthFrac: Float, val colorArgb: Int?)

/**
 * Annotations inside the PDF itself (/Annots): ink stops living only in the
 * SQLite sidecar and becomes part of the document (works in any viewer).
 *
 * v1: ink only. Everything is synchronous and reaches into native code: call it under the
 * viewer's render mutex and from a background dispatcher. It leaves the DB alone: the
 * caller deletes the sidecar that was exported.
 */
object MuPdfAnnots {

    /**
     * Writes [strokes] per page as ink annotations and saves the file.
     * Returns the pages that were written (so the caller can delete that sidecar).
     * Edits nothing if the document does not open as an editable PDF.
     */
    fun exportInk(
        filePath: String,
        strokes: Map<Int, List<InkStroke>>,
        fallbackBox: (Int) -> PageBox?
    ): Set<Int> {
        if (strokes.isEmpty()) throw ExportError.NoInk()
        val file = File(filePath)
        if (!file.isFile) throw ExportError.NoLocalFile()
        val doc = Document.openDocument(filePath) as? PDFDocument
            ?: throw ExportError.NotEditable()
        try {
            val done = mutableSetOf<Int>()
            for ((pageIdx, list) in strokes) {
                val usable = list.filter { it.points.size >= 2 }
                if (usable.isEmpty()) continue
                if (writePageInk(doc, pageIdx, usable, fallbackBox(pageIdx))) done += pageIdx
            }
            if (done.isEmpty()) return emptySet()
            // save the whole file under a tmp name, then swap it in: never rewrite a file
            // while reading it. Saving just the changes would avoid that too, but it needs
            // the document opened as that very file and breaks on repaired PDFs.
            val tmp = File("$filePath.fosscore-tmp")
            try {
                doc.save(tmp.absolutePath, "")
                if (!tmp.renameTo(file)) throw ExportError.ReplaceFailed()
            } finally {
                tmp.delete()
            }
            return done
        } finally {
            runCatching { doc.destroy() }
        }
    }

    private fun writePageInk(
        doc: PDFDocument,
        pageIdx: Int,
        strokes: List<InkStroke>,
        fallback: PageBox?
    ): Boolean {
        if (pageIdx !in 0 until doc.countPages()) return false
        val page = try {
            doc.loadPage(pageIdx) as? PDFPage ?: return false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "página $pageIdx no editable: ${e.message}", e)
            return false
        }
        return try {
            val space = readSpace(page, fallback) ?: return false
            var n = 0
            for ((points, widthFrac, colorArgb) in strokes) {
                val pts = points.map {
                    val p = PdfAnnotMapper.normalizedToPdf(it, space)
                    Point(p.x, p.y)
                }
                val annot = page.createAnnotation(PDFAnnotation.TYPE_INK)
                annot.inkList = arrayOf(pts.toTypedArray())
                annot.rect = inkRect(pts, PdfAnnotMapper.widthFracToPt(widthFrac, space))
                annot.color = PdfAnnotMapper.argbToPdfRgb(colorArgb ?: 0xFF000000.toInt())
                annot.borderWidth = PdfAnnotMapper.widthFracToPt(widthFrac, space)
                annot.update()
                n++
            }
            n > 0
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo anotar la página $pageIdx: ${e.message}", e)
            false
        } finally {
            runCatching { page.destroy() }
        }
    }

    private fun readSpace(page: PDFPage, fallback: PageBox?): PdfPageSpace? {
        val parsed = runCatching {
            val obj = page.getObject()
            val mb = obj.get("MediaBox")
            val rot = runCatching { obj.getInheritable("Rotate")?.asInteger() ?: 0 }.getOrDefault(0)
            PdfPageSpace(
                mb.get(0).asFloat(), mb.get(1).asFloat(),
                mb.get(2).asFloat(), mb.get(3).asFloat(),
                PdfAnnotMapper.normalizeRotation(rot).let { if (it % 90 == 0) it else 0 }
            )
        }.getOrNull()
        if (parsed != null) return parsed
        // last resort: use the drawing box (already turned) as if the page were not turned
        Log.w(LOG_TAG, "MediaBox ilegible, uso caja de dibujo")
        return fallback?.let { PdfPageSpace(0f, 0f, it.width, it.height, 0) }
    }

    private fun inkRect(pts: List<Point>, widthPt: Float): Rect {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in pts) {
            if (p.x < x0) x0 = p.x
            if (p.y < y0) y0 = p.y
            if (p.x > x1) x1 = p.x
            if (p.y > y1) y1 = p.y
        }
        val pad = widthPt / 2f + 1f
        return Rect(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    }
}
