package com.musigatto.fosscore.ui.viewer

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

    val canPrev get() = page > 0
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