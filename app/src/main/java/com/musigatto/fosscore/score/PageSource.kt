package com.musigatto.fosscore.score

import android.graphics.Bitmap
import com.musigatto.fosscore.pdf.PageBox

/**
 * What the viewer draws: numbered pages of a known size. A PDF ([com.musigatto.fosscore.pdf.MuPdfDoc])
 * and a MusicXML score ([MusicXmlPages]) both provide it, so the viewer turns pages, zooms and
 * splits half pages the same way for both.
 *
 * Same threading rule for every implementation: the calls are blocking and not thread-safe, the
 * viewer serializes them with its render mutex and makes them off the main thread, except
 * [pageSize], which only reads memory.
 */
interface PageSource {
    val pageCount: Int

    /** Page size (any unit, only the shape matters), if already known. Safe from the main thread. */
    fun pageSize(index: Int): PageBox?

    /** Same as [pageSize], but works the size out now if needed. */
    fun ensurePageSize(index: Int): PageBox?

    /** Draws page [index] (0-based) as a bitmap that fits a [slotW]x[slotH] px hole. */
    fun render(index: Int, slotW: Int, slotH: Int): Bitmap?

    /** Frees everything. Blocking: call it off the main thread. */
    fun close()
}
