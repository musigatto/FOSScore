package com.musigatto.fosscore.musicxml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import java.nio.ByteBuffer

/** SVG → bitmap through MuPDF's SVG document handler, the engine FOSScore already ships for PDF. */
// Plain Bitmap.createBitmap: the project does not use androidx core-ktx.
@Suppress("UseKtx")
internal object SvgRasterizer {

    /** Draws [svg] (already adapted by [SvgForMuPdf]) to fit [maxWidth] × [maxHeight], on white. */
    fun render(svg: String, maxWidth: Int, maxHeight: Int): Bitmap {
        require(maxWidth > 0 && maxHeight > 0) { "empty target size" }
        val doc = Document.openDocument(svg.toByteArray(Charsets.UTF_8), "image/svg+xml")
        try {
            val page = doc.loadPage(0)
            try {
                val b = page.getBounds()
                val scale = minOf(maxWidth / (b.x1 - b.x0), maxHeight / (b.y1 - b.y0))
                val m = Matrix(scale, 0f, 0f, scale, -b.x0 * scale, -b.y0 * scale)
                // Transparent paper, then drawn onto white: the bitmap is opaque, so an inverting
                // colour filter ("Neg") gives white music on black, as for PDF pages.
                val pix = page.toPixmap(m, ColorSpace.DeviceRGB, true, false)
                try {
                    val transparent = Bitmap.createBitmap(pix.getWidth(), pix.getHeight(), Bitmap.Config.ARGB_8888)
                    val samples = pix.getSamples()
                    val stride = pix.getStride()
                    val rowBytes = pix.getWidth() * 4
                    if (stride == rowBytes) {
                        transparent.copyPixelsFromBuffer(ByteBuffer.wrap(samples))
                    } else {
                        val packed = ByteArray(rowBytes * pix.getHeight())
                        for (y in 0 until pix.getHeight()) System.arraycopy(samples, y * stride, packed, y * rowBytes, rowBytes)
                        transparent.copyPixelsFromBuffer(ByteBuffer.wrap(packed))
                    }
                    return onWhite(transparent)
                } finally {
                    pix.destroy()
                }
            } finally {
                page.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    private fun onWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        src.recycle()
        return out
    }
}
