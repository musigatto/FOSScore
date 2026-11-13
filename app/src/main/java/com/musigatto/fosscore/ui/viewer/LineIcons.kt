package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's own icons, drawn as lines on a 24 unit grid: one stroke width, round ends; what comes
 * after [FILLED] in a path is filled as well (the black keys of the piano). They replace the
 * characters the chrome used as icons (☰ ✎ ✕ ⊞ ♫ ⚙...), whose shape depends on the fonts of each
 * device and some of which Android 6 does not have. Like ToolIcon, they take the content color, so
 * they grey out with their button.
 */
private const val LINE_UNITS = 24f
private const val LINE_WIDTH = 1.75f
private val LINE_ICON_SIZE = 22.dp
// in a path, between the part only stroked and the part also filled
private const val FILLED = "|"

// a circle of radius r around (x, y), as two arcs
private fun circle(x: Float, y: Float, r: Float) = "M${x - r},${y}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

// the viewer's top bar
internal const val LINE_MENU = "M4,7h16M4,12h16M4,17h16"
internal const val LINE_CLOSE = "M6,6L18,18M18,6L6,18"
internal const val LINE_EDIT = "M4,20l1,-4.5L15.5,5a2.1,2.1 0 0,1 3,3L8.5,18.5zM13.5,7l3,3"
internal const val LINE_DONE = "M5,12.5l4.5,4.5L19,7.5"
internal const val LINE_ADD = "M12,5v14M5,12h14"
// the full menu, behind the icon bar
internal val LINE_MORE = circle(5f, 12f, 1f) + circle(12f, 12f, 1f) + circle(19f, 12f, 1f)

// the viewer menu, one per entry
// a score on top of another, a note on it
internal val LINE_LIBRARY = "M8,3h11v15h-11zM5,7v14h11M15,7v6.5" + circle(13.5f, 13.5f, 1.5f)
// a numbered list: dots and lines
internal const val LINE_SETLIST = "M9,6h11M9,12h11M9,18h11M4.5,6h0.01M4.5,12h0.01M4.5,18h0.01"
// the side menu: a tag with its hole, a stopwatch for practice, "i" for about
internal val LINE_TAG = "M3,3h8l10,10l-8,8l-10,-10z" + circle(7.5f, 7.5f, 1.5f)
// a head and shoulders: the people (authors, composers, performers)
internal val LINE_PEOPLE = circle(12f, 8f, 3.5f) + "M5,20a7,7 0 0,1 14,0"
// two notes beamed: the genres of music
internal val LINE_GENRE = "M9,17V5l10,-2v12" + circle(6.5f, 17f, 2.5f) + circle(16.5f, 15f, 2.5f)
internal val LINE_PRACTICE = circle(12f, 13.5f, 7.5f) + "M12,13.5v-4M10,2.5h4M12,2.5v3.5M18,6l1.5,-1.5"
internal val LINE_ABOUT = circle(12f, 12f, 9f) + "M12,11v6M12,7.5v0.01"
internal const val LINE_BOOKMARK = "M7,3h10v18l-5,-4l-5,4z"
// two pages side by side, the page modes
internal const val LINE_DISPLAY = "M3,5h8v14h-8zM13,5h8v14h-8z"
// a staff and a note: how the music is laid out
internal val LINE_LAYOUT = "M3,6h18M3,12h18M3,18h18M15,18v-15" + circle(13f, 18f, 2f)
// a metronome: the case, its foot and the pendulum
internal const val LINE_METRONOME = "M9,3h6l4,18h-14zM12,16l5,-10M6.5,15h11"
// a tuning fork: two long prongs, a short stem ending in a ball
internal val LINE_TUNER = "M9,2v11a3,3 0 0,0 6,0v-11M12,16v3" + circle(12f, 20.5f, 1.5f)
// three white keys and the two black keys between them, filled. As on a keyboard, at the top the
// five keys (C, C#, D, D#, E) are as wide each, 3.6 units from line to line; the stroke widens the
// black ones to that
internal const val LINE_PIANO = "M3,3h18v18h-18zM9,12v9M15,12v9" + FILLED + "M7.475,3h1.85v9h-1.85zM14.675,3h1.85v9h-1.85z"
internal val LINE_PLAY = circle(12f, 12f, 9f) + "M10,8.5v7l5.5,-3.5z"
// a jump back: the route of a repeat
internal const val LINE_LINKS = "M5,19v-6a5,5 0 0,1 5,-5h9M15,4l4,4l-4,4"
internal const val LINE_PAGES = "M4,4h7v7h-7zM13,4h7v7h-7zM4,13h7v7h-7zM13,13h7v7h-7z"
internal const val LINE_EXPORT = "M12,3v12M7,10l5,5l5,-5M4,20h16"
internal val LINE_SHARE = "M8.2,10.8l7.6,-4.1M8.2,13.2l7.6,4.1" +
    circle(6f, 12f, 2.5f) + circle(18f, 5.5f, 2.5f) + circle(18f, 18.5f, 2.5f)
// a wheel with its teeth
internal val LINE_SETTINGS = circle(12f, 12f, 3f) + circle(12f, 12f, 7f) +
    "M12,2v3M12,19v3M2,12h3M19,12h3M4.9,4.9l2.1,2.1M17,17l2.1,2.1M4.9,19.1l2.1,-2.1M17,7l2.1,-2.1"

// the metronome band and its settings
internal const val LINE_VOLUME = "M4,9h4l5,-4v14l-5,-4h-4zM16,9.5a3.5,3.5 0 0,1 0,5M18.5,7a7,7 0 0,1 0,10"
internal const val LINE_PREVIOUS = "M15,5l-7,7l7,7"
internal const val LINE_NEXT = "M9,5l7,7l-7,7"
// the same bars on every page after: two arrows down
internal const val LINE_COPY_DOWN = "M8,4v12M4,12l4,4l4,-4M16,4v12M12,12l4,4l4,-4"

/** One of the paths above, stroked in the content color, and filled after [FILLED]. */
@Composable
fun LineIcon(path: String, modifier: Modifier = Modifier, size: Dp = LINE_ICON_SIZE) {
    val color = LocalContentColor.current
    val shapes = remember(path) { path.split(FILLED).map { PathParser().parsePathString(it).toPath() } }
    // drawn opaque, then made as transparent as the color as a whole: where a filled part and a
    // stroke overlap, a greyed out icon does not get darker
    val opaque = color.copy(alpha = 1f)
    Canvas(modifier.size(size).graphicsLayer { alpha = color.alpha; compositingStrategy = CompositingStrategy.Offscreen }) {
        val u = this.size.minDimension / LINE_UNITS
        withTransform({ scale(u, u, pivot = Offset.Zero) }) {
            shapes.forEach { drawPath(it, opaque, style = Stroke(width = LINE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            shapes.drop(1).forEach { drawPath(it, opaque, style = Fill) }
        }
    }
}
