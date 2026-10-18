package com.musigatto.fosscore.ui.viewer

import android.graphics.Typeface
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musigatto.fosscore.R

// label = the text drawn on the page (only the dynamics use it, and it is notation, so it does
// not translate); descRes = the name a screen reader speaks, translated in strings.xml
enum class StampSymbol(val label: String, @param:StringRes val descRes: Int) {
    PP("pp", R.string.stamp_pp),
    P("p", R.string.stamp_p),
    MP("mp", R.string.stamp_mp),
    MF("mf", R.string.stamp_mf),
    F("f", R.string.stamp_f),
    FF("ff", R.string.stamp_ff),
    CRESC("cresc.", R.string.stamp_cresc),
    DECRESC("decresc.", R.string.stamp_decresc),
    UPBOW("upbow", R.string.stamp_upbow),
    DOWNBOW("downbow", R.string.stamp_downbow),
    ACCENT("acento", R.string.stamp_accent),
    STACCATO("staccato", R.string.stamp_staccato),
    TENUTO("tenuto", R.string.stamp_tenuto),
    FERMATA("fermata", R.string.stamp_fermata),
    // Accidentals (sharp, flat, natural, double sharp, double flat): Noto Music carries them at
    // normal Unicode code points (we checked its character table).
    SHARP("sostenido", R.string.stamp_sharp),
    FLAT("bemol", R.string.stamp_flat),
    NATURAL("becuadro", R.string.stamp_natural),
    DOUBLE_SHARP("doble sostenido", R.string.stamp_double_sharp),
    DOUBLE_FLAT("doble bemol", R.string.stamp_double_flat)
}

// Noto Music (OFL, third_party/NotoMusic-OFL.txt) carries the accidentals at normal Unicode
// code points: checked in its character table and eyeballed by drawing them (♭ 266D, ♮ 266E,
// ♯ 266F, 𝄪 1D12A, 𝄫 1D12B).
// ponytail: a pure notation font (say Bravura) keeps its glyphs in a private area (U+E000…)
// and you would have to know the exact code point of each one; Noto Music skips that mapping.
private val GLYPH_CODEPOINT = mapOf(
    StampSymbol.FLAT to 0x266D,
    StampSymbol.NATURAL to 0x266E,
    StampSymbol.SHARP to 0x266F,
    StampSymbol.DOUBLE_SHARP to 0x1D12A,
    StampSymbol.DOUBLE_FLAT to 0x1D12B
)

// Dynamics: in notation fonts, p / f / mp / mf / ff really are italic serif letters, which is
// exactly what we draw as text. Spectral Italic (OFL, third_party/Spectral-OFL.txt) looks
// closer to the weight of real sheet music than the system's Noto Serif Italic, which comes
// out too heavy.
private val TEXT_SYMBOLS = setOf(
    StampSymbol.PP, StampSymbol.P, StampSymbol.MP,
    StampSymbol.MF, StampSymbol.F, StampSymbol.FF,
    StampSymbol.CRESC, StampSymbol.DECRESC
)

private val MUSIC_FONT = FontFamily(Font(R.font.spectral_italic))

// share of the box each label takes up (long words get shrunk)
private val TEXT_FILL = mapOf(
    StampSymbol.DECRESC to 0.34f,
    StampSymbol.CRESC to 0.42f,
    StampSymbol.MF to 0.5f,
    StampSymbol.FF to 0.55f,
    StampSymbol.MP to 0.58f,
    StampSymbol.PP to 0.58f
)

// tint colors that read well on light and dark backgrounds
val SWATCH_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF757575)
)

// null = theme color; otherwise the ARGB that was saved
fun stampColor(color: Int?, fallback: Color): Color = color?.let(::Color) ?: fallback

@Composable
private fun musicTypeface(): Typeface? {
    val resources = LocalResources.current
    // Resources.getFont is API 26+: on 23-25 we skip it (a NoSuchMethodError is not caught by
    // catch(Exception)) and the glyphs fall through to the Canvas drawing, which is already
    // the fallback.
    return remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            null
        } else {
            try {
                resources.getFont(R.font.noto_music)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Musical glyphs: Noto Music for the accidentals (real glyphs, measured and centred by their
 * ink box), Spectral Italic for the dynamics, and Canvas for the rest.
 */
@Composable
fun StampView(symbol: StampSymbol, sizePx: Float, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val side = with(density) { sizePx.toDp() }
    val typeface = musicTypeface()
    Box(modifier, contentAlignment = Alignment.Center) {
        if (symbol in TEXT_SYMBOLS) {
            // ponytail: each label is shrunk by a fixed factor (eyeballed against the box)
            // instead of measuring the text at runtime. Upgrade: Canvas.drawText with a real measure.
            val maxSp = (sizePx * (TEXT_FILL[symbol] ?: 0.7f) / density.density).sp
            androidx.compose.material3.Text(
                text = symbol.label,
                maxLines = 1,
                softWrap = false,
                fontSize = maxSp,
                fontFamily = MUSIC_FONT,
                fontStyle = FontStyle.Italic,
                color = color,
                textAlign = TextAlign.Center
            )
        } else {
            Canvas(Modifier.size(side)) {
                val s = size.minDimension
                val stroke = s * 0.08f
                val p = Path()
                val cp = GLYPH_CODEPOINT[symbol]

                if (cp != null && typeface != null) {
                    // we measure the INK (not the em box) so it scales to the size of the page
                    // and truly lands centred; Compose's text box shifts symbol glyphs off
                    // center and also clips them to the line height
                    val text = String(Character.toChars(cp))
                    val paint = android.graphics.Paint().apply {
                        // NOTE: without "this." the left side resolves to the outer val (same
                        // name) and Kotlin says "'val' cannot be reassigned"
                        this.typeface = typeface
                        isAntiAlias = true
                        this.color = color.toArgb()
                    }
                    val b = android.graphics.Rect()
                    val refPx = 200f
                    paint.textSize = refPx
                    paint.getTextBounds(text, 0, text.length, b)
                    val k = if (b.width() > 0 && b.height() > 0) {
                        minOf(s * 0.92f / b.width(), s * 0.92f / b.height())
                    } else 1f
                    paint.textSize = refPx * k
                    paint.getTextBounds(text, 0, text.length, b)
                    // centred on the ink box; the baseline sits below the center
                    val x = s / 2f - (b.left + b.right) / 2f
                    val y = s / 2f - (b.top + b.bottom) / 2f + b.bottom
                    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
                } else when (symbol) {
                    StampSymbol.UPBOW -> p.apply {
                        moveTo(s * 0.08f, s * 0.28f); lineTo(s * 0.5f, s * 0.78f); lineTo(s * 0.92f, s * 0.28f)
                    }
                    StampSymbol.DOWNBOW -> p.apply {
                        moveTo(s * 0.08f, s * 0.78f); lineTo(s * 0.5f, s * 0.18f); lineTo(s * 0.92f, s * 0.78f)
                    }
                    StampSymbol.ACCENT -> p.apply {
                        moveTo(s * 0.05f, s * 0.12f); lineTo(s * 0.88f, s * 0.5f); lineTo(s * 0.05f, s * 0.88f)
                    }
                    StampSymbol.STACCATO ->
                        drawCircle(color, radius = s * 0.14f, center = Offset(s / 2f, s / 2f))
                    StampSymbol.TENUTO -> drawLine(
                        color, Offset(s * 0.05f, s / 2f), Offset(s * 0.95f, s / 2f),
                        strokeWidth = s * 0.15f, cap = StrokeCap.Round
                    )
                    StampSymbol.FERMATA -> {
                        // dome (top half circle) + a dot BELOW: in Android 0° is 3 o'clock and
                        // angles run clockwise, so 180→360 is the top half. With 0→180 the dome
                        // came out at the bottom.
                        drawArc(
                            color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                            topLeft = Offset(s * 0.18f, s * 0.16f),
                            size = androidx.compose.ui.geometry.Size(s * 0.64f, s * 0.64f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = stroke, cap = StrokeCap.Round
                            )
                        )
                        drawCircle(color, radius = s * 0.11f, center = Offset(s / 2f, s * 0.72f))
                    }
                    else -> {}
                }
                // the Path symbols are the ones drawn with neither a glyph nor simple shapes
                val usesPath = symbol == StampSymbol.UPBOW ||
                    symbol == StampSymbol.DOWNBOW || symbol == StampSymbol.ACCENT
                if (usesPath) {
                    drawPath(
                        p, color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke, cap = StrokeCap.Round
                        )
                    )
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
    val desc = stringResource(symbol.descRes)
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(border, borderColor),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = desc }
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            val palettePx = with(LocalDensity.current) { 24.dp.toPx() }
            StampView(symbol, sizePx = palettePx, color = color)
        }
    }
}

@Composable
fun ColorSwatchButton(color: Color, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) 2.dp else 1.dp
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(border, borderColor),
        color = color,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(Modifier.size(28.dp))
    }
}
