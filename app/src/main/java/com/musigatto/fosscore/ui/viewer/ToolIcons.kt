package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Glyphs of the annotation bar, drawn in code. The bar used to show emoji: 🩹 needs Unicode 11 and
 * came out as an empty box on Android 7 (issue #21), and emoji also change shape from one brand to
 * the next. These take the button's content color (so they grey out when the button is disabled)
 * and look the same from API 23 up.
 *
 * TODO: placeholder for a future .PNG
 */
private const val ICON_UNITS = 24f
private const val ICON_DP = 20

// Material's own 24 dp paths (edit / undo / redo / delete), so the shapes are the familiar ones.
internal const val ICON_PENCIL =
    "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39,0.39,-1.02,0,-1.41" +
        "l-2.34,-2.34c-0.39,-0.39,-1.02,-0.39,-1.41,0l-1.83,1.83,3.75,3.75,1.83,-1.83z"

internal const val ICON_UNDO =
    "M12.5,8c-2.65,0,-5.05,0.99,-6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16,3.16,-1.88,5.12,-1.88" +
        ",3.54,0,6.55,2.31,7.6,5.5l2.37,-0.78C21.08,11.03,17.15,8,12.5,8z"

internal const val ICON_REDO =
    "M18.4,10.6C16.55,8.99,14.15,8,11.5,8c-4.65,0,-8.58,3.03,-9.96,7.22L3.9,16c1.05,-3.19" +
        ",4.05,-5.5,7.6,-5.5,1.95,0,3.73,0.72,5.12,1.88L13,16h9V7l-3.6,3.6z"

internal const val ICON_TRASH =
    "M6,19c0,1.1,0.9,2,2,2h8c1.1,0,2,-0.9,2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"

/** One of the 24 dp paths above, scaled to the button's text size. */
@Composable
fun ToolIcon(path: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    val shape = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(ICON_DP.dp)) {
        val u = size.minDimension / ICON_UNITS
        withTransform({ scale(u, u, pivot = Offset.Zero) }) { drawPath(shape, color) }
    }
}

/**
 * Eraser: a slanted block with its sleeve line, over the line it is rubbing out. Material has no
 * eraser glyph, so it is two primitives instead of a path.
 */
@Composable
fun EraserIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(ICON_DP.dp)) {
        val u = size.minDimension / ICON_UNITS
        withTransform({ rotate(-45f, pivot = center) }) {
            drawRoundRect(
                color = color,
                topLeft = Offset(5f * u, 8f * u),
                size = Size(14f * u, 8f * u),
                cornerRadius = CornerRadius(2f * u),
                style = Stroke(width = 1.8f * u),
            )
            // sleeve: the mark that separates rubber from holder
            drawLine(
                color = color,
                start = Offset(10f * u, 8f * u),
                end = Offset(10f * u, 16f * u),
                strokeWidth = 1.5f * u,
            )
        }
        // the stroke being erased, under the block
        drawLine(
            color = color,
            start = Offset(4f * u, 21f * u),
            end = Offset(20f * u, 21f * u),
            strokeWidth = 1.5f * u,
        )
    }
}
