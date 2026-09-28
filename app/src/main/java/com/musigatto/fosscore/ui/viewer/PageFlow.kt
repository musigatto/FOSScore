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

    val label: String
        get() = if (showTwoUp && page + 1 < pageCount) "${page + 1}-${page + 2} / $pageCount"
        else "${page + 1} / $pageCount"

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
        else copy(page = (page - (if (showHalf) 1 else step)).coerceAtLeast(0))
    }
}