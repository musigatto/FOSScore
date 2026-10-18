package com.musigatto.fosscore.ui.viewer

import androidx.compose.ui.input.key.Key

enum class PageTurn { NEXT, PREV }

// keys that turn pages from a Bluetooth keyboard or a page-turner pedal (most pedals emulate one
// of these pairs). Shift+Space goes back, like in a browser.
fun pageTurnFor(key: Key, shift: Boolean = false): PageTurn? = when (key) {
    Key.PageDown, Key.DirectionRight, Key.DirectionDown, Key.Enter, Key.NumPadEnter -> PageTurn.NEXT
    Key.Spacebar -> if (shift) PageTurn.PREV else PageTurn.NEXT
    Key.PageUp, Key.DirectionLeft, Key.DirectionUp -> PageTurn.PREV
    else -> null
}
