package com.musigatto.fosscore.ui.viewer

import android.graphics.Bitmap

// a blank band is at least this part of the page high: the space between the lines of a staff
// (crossed by the barlines) is not one; lyrics under a system can leave little more than this
private const val MIN_BAND = 0.01f
// a pixel this light is paper
private const val PAPER = 0xE0

/**
 * The blank bands of a drawn page, where a turned half page can cut without cutting a system
 * (HalfSplit.bandsOf). Read from the picture itself, so it works the same for PDF and MusicXML.
 */
fun Bitmap.blankBands(): List<Band> {
    val row = IntArray(width)
    val blank = BooleanArray(height) { y ->
        getPixels(row, 0, width, 0, y, width, 1)
        row.all { p ->
            (p ushr 24) == 0 || (((p shr 16) and 0xFF) >= PAPER && ((p shr 8) and 0xFF) >= PAPER && (p and 0xFF) >= PAPER)
        }
    }
    return HalfSplit.bandsOf(blank, (height * MIN_BAND).toInt().coerceAtLeast(2))
}
