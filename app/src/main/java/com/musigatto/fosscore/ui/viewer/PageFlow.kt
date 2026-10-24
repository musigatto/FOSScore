package com.musigatto.fosscore.ui.viewer

import kotlin.math.abs

data class PageFlow(
    val page: Int,
    val halfTurned: Boolean,
    val halfEnabled: Boolean,
    val twoUp: Boolean,
    val isLandscape: Boolean,
    val pageCount: Int,
) {
    val showTwoUp get() = isLandscape && twoUp && !halfEnabled
    val showHalf get() = halfEnabled && !showTwoUp
    val step get() = if (showTwoUp) 2 else 1

    // a turned half page of the first page goes back to the whole first page
    val canPrev get() = page > 0 || (showHalf && halfTurned)
    val canNext get() = when {
        showHalf -> if (!halfTurned) page + 1 < pageCount else page < pageCount - 1
        else -> page < pageCount - 1
    }

    fun next(): PageFlow {
        if (!canNext) return this
        return if (showHalf) {
            if (!halfTurned) copy(halfTurned = true)
            else copy(page = (page + 1).coerceAtMost(pageCount - 1), halfTurned = false)
        } else {
            copy(page = (page + step).coerceAtMost(pageCount - 1))
        }
    }

    fun prev(): PageFlow {
        if (!canPrev) return this
        return if (showHalf && halfTurned) copy(halfTurned = false)
        else copy(page = (page - step).coerceAtLeast(0))
    }

    /**
     * Where the music played takes the reader: it is on page [target], already on screen when
     * [onScreen]. The reader looks a few bars ahead (the bars to turn ahead, 0 to 2): the music
     * there is on page [ahead], in its lower part (the part a turned half page does not show) when
     * [aheadInLowerPart]. Nothing ever goes back and forth.
     *
     * Half page: when the music ahead gets to the next page, its top shows above the end of this
     * one; when the music is on the next page and the music ahead gets to its lower part, the
     * whole next page shows. The music played always stays on screen.
     *
     * Whole pages: the page turns when the music ahead is on the next one, and stays turned while
     * the music ends the page before.
     */
    fun follow(target: Int, aheadInLowerPart: Boolean, onScreen: Boolean, ahead: Int = target): PageFlow = when {
        showHalf -> when {
            !halfTurned && ahead > page && target in page..page + 1 && page + 1 < pageCount -> copy(halfTurned = true)
            halfTurned && target == page + 1 && (ahead > page + 1 || aheadInLowerPart) -> copy(page = target, halfTurned = false)
            // turned, the music at the end of this page or at the top of the next: both on screen
            halfTurned && target in page..page + 1 -> this
            onScreen -> this
            else -> copy(page = target, halfTurned = false)
        }
        // the page shown is the one ahead, the music ends the page before
        ahead in page until page + step && target in page - step until page -> this
        onScreen && ahead < page + step -> this
        onScreen -> copy(page = ahead.coerceAtMost(pageCount - 1))
        else -> copy(page = target, halfTurned = false)
    }
}

/** A band of a page, from [start] to [end], as fractions of the page height. */
data class Band(val start: Float, val end: Float)

/**
 * Where a turned half page cuts, as fractions of the page height: this page shows below [lower],
 * the next one above [upper], blank paper between them.
 */
data class HalfSplit(val lower: Float = 0.5f, val upper: Float = lower) {
    companion object {
        /**
         * The cut goes through blank bands of the pages, the space between two systems, so no
         * system is cut in two. The top half of the next page shows: down to its band nearest the
         * middle, above the highest band of this page low enough for it, as long as this page
         * keeps at least its last quarter. Otherwise (no such band) this page cuts at its band
         * nearest the middle, and the next one shows the most it can above that. [bands] and
         * [nextBands] are the blank bands of this page and the next (the top margin left out). A
         * page with none cuts at its middle.
         */
        fun of(bands: List<Band>, nextBands: List<Band>): HalfSplit {
            val middle = { b: Band -> abs((b.start + b.end) / 2 - 0.5f) }
            val inMiddle = { b: Band -> b.end >= 0.25f && b.start <= 0.75f }
            // pages of different systems: a page of three systems above a page of four
            nextBands.filter(inMiddle).minByOrNull(middle)?.let { next ->
                bands.firstOrNull { it.end >= next.start && it.start <= 0.75f }?.let { here ->
                    val upper = minOf(next.end, here.end)
                    return HalfSplit(maxOf(here.start, upper), upper)
                }
            }
            val here = bands.filter(inMiddle).minByOrNull(middle) ?: return HalfSplit()
            val upper = nextBands.filter { it.start <= here.end }.maxOfOrNull { minOf(it.end, here.end) }
                ?: return ((here.start + here.end) / 2).let { HalfSplit(it, it) }
            return HalfSplit(maxOf(here.start, upper), upper)
        }

        /**
         * The blank bands of a page whose rows are [blank] or not, at least [minRows] high, from
         * the top; the top margin is left out: the next page shows nothing above it.
         */
        fun bandsOf(blank: BooleanArray, minRows: Int): List<Band> {
            val h = blank.size.toFloat()
            val out = mutableListOf<Band>()
            var from = -1
            for (y in 0..blank.size) {
                val isBlank = y < blank.size && blank[y]
                if (isBlank && from < 0) from = y
                if (!isBlank && from >= 0) {
                    if (from > 0 && y - from >= minRows) out += Band(from / h, y / h)
                    from = -1
                }
            }
            return out
        }
    }
}

/**
 * Pages visible at once, 0-based: [currentPage] alone, or it and the next one in a spread.
 * `IntRange.EMPTY` while the PDF still has no pages.
 */
fun visiblePageRange(currentPage: Int, pageCount: Int, showSpread: Boolean): IntRange = when {
    pageCount <= 0 -> IntRange.EMPTY
    showSpread && currentPage + 1 < pageCount -> currentPage..currentPage + 1
    else -> currentPage..currentPage
}