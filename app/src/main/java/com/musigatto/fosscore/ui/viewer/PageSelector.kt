package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import kotlin.math.roundToInt

/** Touch height: the drag target, tall enough to hit with a thumb (a11y minimum). */
private val SELECTOR_HEIGHT = 48.dp
private val DOT_RADIUS = 1.5.dp
private val KNOB_RADIUS = 6.dp
private val KNOB_RING = 2.dp

/** Center x (px) of the dot for [index] on a [width] track. */
internal fun pageMarkX(index: Int, width: Int, pageCount: Int): Float =
    (index + 0.5f) * width / pageCount

/** Page under [x] (px) on a [width] track. The ends clamp instead of falling off the document. */
internal fun pageIndexAt(x: Float, width: Int, pageCount: Int): Int =
    if (width <= 0 || pageCount <= 0) 0
    else (x * pageCount / width).toInt().coerceIn(0, pageCount - 1)

/**
 * Page selector ("seek bar", spec §2): one dot per page with a knob you drag to travel. While
 * dragging, a tooltip above the knob names the target page; letting go jumps there.
 *
 * Colors are M3 pairs on purpose: the knob is `onSurface` (dark ink on light chrome, light ink on
 * dark chrome) inside a `surface` ring that lifts it off the dots, and the tooltip is the
 * `inverseSurface`/`inverseOnSurface` pair — neither can vanish in either theme.
 *
 * ponytail: no thumbnail in the tooltip and no fine control (vertical drag) yet — both documented
 * in forScore (§2) and pending. A tap on the track does nothing: forScore documents the drag only.
 */
@Composable
fun PageSelector(
    pageCount: Int,
    currentPage: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return

    val colors = MaterialTheme.colorScheme
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    var popupWidthPx by remember { mutableIntStateOf(0) }
    var dragPage by remember { mutableStateOf<Int?>(null) }
    val target = dragPage ?: currentPage.coerceIn(0, pageCount - 1)
    val label = stringResource(R.string.desc_page, target + 1, pageCount)
    // Every dot in one path, rebuilt only when the track or the theme changes: a drawCircle per
    // page per frame was one Skia call per page of the score on every drag frame.
    val dotColor = colors.onSurface.copy(alpha = 0.35f)
    val dotRadiusPx = with(LocalDensity.current) { DOT_RADIUS.toPx() }
    val dots = remember(pageCount, widthPx, heightPx, dotColor, dotRadiusPx) {
        val cy = heightPx * 0.78f
        Path().apply {
            for (i in 0 until pageCount) {
                addOval(
                    Rect(center = Offset(pageMarkX(i, widthPx, pageCount), cy), radius = dotRadiusPx)
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SELECTOR_HEIGHT)
            .onSizeChanged { widthPx = it.width; heightPx = it.height }
            .semantics { contentDescription = label }
            .pointerInput(pageCount) {
                detectHorizontalDragGestures(
                    onDragStart = { at -> dragPage = pageIndexAt(at.x, widthPx, pageCount) },
                    onDragEnd = {
                        val page = dragPage
                        dragPage = null
                        if (page != null) onJump(page)
                    },
                    onDragCancel = { dragPage = null },
                ) { change, _ ->
                    change.consume()
                    dragPage = pageIndexAt(change.position.x, widthPx, pageCount)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height * 0.78f
            drawPath(dots, dotColor)
            val kx = pageMarkX(target, size.width.toInt(), pageCount)
            drawCircle(colors.surface, KNOB_RADIUS.toPx() + KNOB_RING.toPx(), Offset(kx, cy))
            drawCircle(colors.onSurface, KNOB_RADIUS.toPx(), Offset(kx, cy))
        }
        if (dragPage != null) {
            Surface(
                color = colors.inverseSurface,
                contentColor = colors.inverseOnSurface,
                shape = RoundedCornerShape(6.dp),
                shadowElevation = 3.dp,
                modifier = Modifier
                    // follows the knob, kept inside the track: never half off the screen
                    .offset {
                        IntOffset(
                            (pageMarkX(target, widthPx, pageCount) - popupWidthPx / 2f)
                                .roundToInt()
                                .coerceIn(0, (widthPx - popupWidthPx).coerceAtLeast(0)),
                            0,
                        )
                    }
                    .onSizeChanged { popupWidthPx = it.width },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
