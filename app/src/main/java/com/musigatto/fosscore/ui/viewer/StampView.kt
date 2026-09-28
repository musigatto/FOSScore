package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class StampSymbol(val label: String) {
    PP("pp"), P("p"), MP("mp"), MF("mf"), F("f"), FF("ff"),
    CRESC("cresc."), DECRESC("decresc."),
    UPBOW("upbow"), DOWNBOW("downbow"),
    ACCENT("acento"), STACCATO("staccato"), TENUTO("tenuto"), FERMATA("fermata")
}

private val TEXT_SYMBOLS = setOf(
    StampSymbol.PP, StampSymbol.P, StampSymbol.MP,
    StampSymbol.MF, StampSymbol.F, StampSymbol.FF
)

// ponytail: géstos musicales dibujados a mano en Canvas — los glyphs Unicode del bloque
// SMuFL/Noto Music no están garantizados en la fuente del dispositivo. Upgrade: fuentes musicales reales.
@Composable
fun StampView(symbol: StampSymbol, sizePx: Float, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val side = with(density) { sizePx.toDp() }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (symbol in TEXT_SYMBOLS) {
            val fontSize = with(density) { (sizePx * 0.7f / density.density).sp }
            androidx.compose.material3.Text(
                text = symbol.label,
                fontSize = fontSize,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                color = color
            )
        } else {
            Canvas(Modifier.size(side)) {
                val s = size.minDimension
                val stroke = s * 0.08f
                val p = androidx.compose.ui.graphics.Path()
                when (symbol) {
                    StampSymbol.CRESC -> p.apply {
                        moveTo(s * 0.05f, s * 0.9f); lineTo(s * 0.7f, s * 0.5f); lineTo(s * 0.05f, s * 0.1f)
                    }
                    StampSymbol.DECRESC -> p.apply {
                        moveTo(s * 0.95f, s * 0.9f); lineTo(s * 0.3f, s * 0.5f); lineTo(s * 0.95f, s * 0.1f)
                    }
                    StampSymbol.UPBOW -> p.apply {
                        moveTo(s * 0.08f, s * 0.28f); lineTo(s * 0.5f, s * 0.78f); lineTo(s * 0.92f, s * 0.28f)
                    }
                    StampSymbol.DOWNBOW -> p.apply {
                        moveTo(s * 0.08f, s * 0.78f); lineTo(s * 0.5f, s * 0.18f); lineTo(s * 0.92f, s * 0.78f)
                    }
                    StampSymbol.ACCENT -> p.apply {
                        moveTo(s * 0.05f, s * 0.12f); lineTo(s * 0.88f, s * 0.5f); lineTo(s * 0.05f, s * 0.88f)
                    }
                    StampSymbol.STACCATO -> drawCircle(color, radius = s * 0.14f, center = androidx.compose.ui.geometry.Offset(s / 2f, s / 2f))
                    StampSymbol.TENUTO -> drawLine(
                        color, androidx.compose.ui.geometry.Offset(s * 0.05f, s / 2f),
                        androidx.compose.ui.geometry.Offset(s * 0.95f, s / 2f), strokeWidth = s * 0.15f, cap = StrokeCap.Round
                    )
                    StampSymbol.FERMATA -> {
                        drawArc(
                            color = color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(s * 0.2f, s * 0.25f),
                            size = androidx.compose.ui.geometry.Size(s * 0.6f, s * 0.6f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                        )
                        drawCircle(
                            color, radius = s * 0.1f, center = androidx.compose.ui.geometry.Offset(s / 2f, s * 0.8f)
                        )
                    }
                    else -> {}
                }
                if (symbol != StampSymbol.STACCATO && symbol != StampSymbol.TENUTO && symbol != StampSymbol.FERMATA) {
                    drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
fun StampPaletteButton(symbol: StampSymbol, selected: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) color else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(border, borderColor),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            val palettePx = with(LocalDensity.current) { 24.dp.toPx() }
            StampView(symbol, sizePx = palettePx, color = color)
        }
    }
}