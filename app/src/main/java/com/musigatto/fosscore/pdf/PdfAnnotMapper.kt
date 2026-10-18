package com.musigatto.fosscore.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/** One PDF page: where it starts, where it ends, and how far it is turned (0, 90, 180 or 270). */
data class PdfPageSpace(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rotation: Int) {
    val w: Float get() = x1 - x0
    val h: Float get() = y1 - y0
}

/**
 * Changes a point you see on screen into a spot on the PDF page, and back.
 * Ink and stamps are saved as 0..1 counting from the top left. The PDF keeps
 * its points counting from the bottom left, with the page straight. This file
 * does that swap.
 */
object PdfAnnotMapper {

    /** Point on screen -> spot on the PDF page. */
    fun normalizedToPdf(p: Offset, s: PdfPageSpace): Offset {
        val (ux, uy) = displayedToUnrotated(p.x, p.y, s.rotation)
        return Offset(s.x0 + ux * s.w, s.y0 + uy * s.h)
    }

    /** Takes the page turn out, so the point lands where the PDF expects it. */
    private fun displayedToUnrotated(dx: Float, dyDown: Float, rot: Int): Pair<Float, Float> {
        val dyUp = 1f - dyDown
        return when (rot) {
            90 -> Pair(1f - dyUp, dx)
            180 -> Pair(1f - dx, 1f - dyUp)
            270 -> Pair(dyUp, 1f - dx)
            else -> Pair(dx, dyUp)
        }
    }

    /** Brings any angle down to 0, 90, 180 or 270. */
    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Android color -> the three numbers the PDF uses for red, green and blue. */
    fun argbToPdfRgb(argb: Int): FloatArray {
        val c = Color(argb)
        return floatArrayOf(c.red, c.green, c.blue)
    }

    /** Pen thickness: from a share of the page height to real points on the page. */
    fun widthFracToPt(frac: Float, s: PdfPageSpace): Float =
        frac * if (s.rotation == 90 || s.rotation == 270) s.w else s.h
}
