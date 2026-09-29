package com.musigatto.fosscore.ui.viewer

import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musigatto.fosscore.R
import java.util.Locale

enum class StampSymbol(val label: String) {
    PP("pp"), P("p"), MP("mp"), MF("mf"), F("f"), FF("ff"),
    CRESC("cresc."), DECRESC("decresc."),
    UPBOW("upbow"), DOWNBOW("downbow"),
    ACCENT("acento"), STACCATO("staccato"), TENUTO("tenuto"), FERMATA("fermata"),
    // Alteraciones: glifos SMuFL accidentalSharp/Flat/Natural/DoubleSharp/DoubleFlat, servidos
    // por Noto Music en codepoints Unicode estándar (verificados contra su cmap).
    SHARP("sostenido"), FLAT("bemol"), NATURAL("becuadro"),
    DOUBLE_SHARP("doble sostenido"), DOUBLE_FLAT("doble bemol")
}

// Noto Music (OFL, third_party/NotoMusic-OFL.txt) trae las alteraciones en codepoints Unicode
// estándar: verificados en su cmap y "vistos" renderizándolos (♭ 266D, ♮ 266E, ♯ 266F, 𝄪 1D12A, 𝄫 1D12B).
// ponytail: si algún día quieres Bravura (SMuFL puro) sus glifos están en el área privada
// (U+E000…) y hay que conocer el codepoint exacto de cada uno; Noto Music evita ese mapeo.
private val GLYPH_CODEPOINT = mapOf(
    StampSymbol.FLAT to 0x266D,
    StampSymbol.NATURAL to 0x266E,
    StampSymbol.SHARP to 0x266F,
    StampSymbol.DOUBLE_SHARP to 0x1D12A,
    StampSymbol.DOUBLE_FLAT to 0x1D12B
)

// Dinámicas: en SMuFL dynamicPiano/Forte/MezzoPiano/MezzoForte/ForteFortissimo son literalmente
// letras serif cursivas (p, f, mp, mf, ff), que es lo que pintamos con Text. Spectral Italic
// (OFL, third_party/Spectral-OFL.txt) se parece más a la gravedad de una partitura que la Noto
// Serif Italic del sistema, que sale gruesa.
private val TEXT_SYMBOLS = setOf(
    StampSymbol.PP, StampSymbol.P, StampSymbol.MP,
    StampSymbol.MF, StampSymbol.F, StampSymbol.FF,
    StampSymbol.CRESC, StampSymbol.DECRESC
)

private val MUSIC_FONT = FontFamily(Font(R.font.spectral_italic))

// fracción de la caja que ocupa la etiqueta (las palabras largas se encogen)
private val TEXT_FILL = mapOf(
    StampSymbol.DECRESC to 0.34f,
    StampSymbol.CRESC to 0.42f,
    StampSymbol.MF to 0.5f,
    StampSymbol.FF to 0.55f,
    StampSymbol.MP to 0.58f,
    StampSymbol.PP to 0.58f
)

// colores de tinte legibles en claro y oscuro (al estilo forScore)
val SWATCH_COLORS = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF757575)
)

// null = color del tema; si no, el ARGB guardado
fun stampColor(color: Int?, fallback: Color): Color = color?.let(::Color) ?: fallback

// ponytail: resolución por getIdentifier para que el usuario pueda soltar `stamp_<nombre>.png`
// en res/drawable y los iconos aparezcan sin tocar código. Upgrade si llega a >20 iconos: mapeo estático.
@Composable
private fun stampDrawableId(symbol: StampSymbol): Int? {
    val context = LocalContext.current
    return remember(symbol) {
        val id = context.resources.getIdentifier(
            "stamp_" + symbol.name.lowercase(Locale.ROOT), "drawable", context.packageName
        )
        id.takeIf { it != 0 }
    }
}

@Composable
private fun musicTypeface(): Typeface? {
    val context = LocalContext.current
    // Resources.getFont (API 26+): devuelve tipo "platform", así que el local va tipado
    return remember {
        val tf: Typeface? = try {
            context.resources.getFont(R.font.noto_music)
        } catch (e: Exception) {
            null
        }
        tf
    }
}

/**
 * Glifos musicales: Noto Music para las alteraciones (glifos reales, medidos y centrados por la
 * caja de tinta) y Canvas para el resto. Un PNG en res/drawable tiene prioridad sobre todo.
 */
@Composable
fun StampView(symbol: StampSymbol, sizePx: Float, color: Color, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val side = with(density) { sizePx.toDp() }
    val typeface = musicTypeface()
    Box(modifier, contentAlignment = Alignment.Center) {
        val png = stampDrawableId(symbol)
        if (png != null) {
            Image(
                painter = painterResource(png),
                contentDescription = symbol.label,
                colorFilter = ColorFilter.tint(color),
                modifier = Modifier.fillMaxSize()
            )
        } else if (symbol in TEXT_SYMBOLS) {
            // ponytail: la etiqueta se encoge con un factor fijo por etiqueta (medido a ojo sobre
            // la caja) en vez de medir el texto en runtime. Upgrade: Canvas.drawText con medición real.
            val maxSp = with(density) { (sizePx * TEXT_FILL.getOrDefault(symbol, 0.7f) / density.density).sp }
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
                    // medimos la TINTA (no la caja de la em) para escalarlo a la medida de la
                    // página y centrarlo de verdad; la caja de texto de Compose descentra los
                    // glifos de símbolo y además los limita al interlineado
                    val text = String(Character.toChars(cp))
                    val paint = android.graphics.Paint().apply {
                        // OJO: sin "this." el lado izquierdo resuelve al val de fuera (mismo
                        // nombre) y Kotlin dice "'val' cannot be reassigned"
                        this.typeface = typeface
                        isAntiAlias = true
                        this.color = color.toArgb()
                    }
                    val b = android.graphics.Rect()
                    val REF = 200f
                    paint.textSize = REF
                    paint.getTextBounds(text, 0, text.length, b)
                    val k = if (b.width() > 0 && b.height() > 0) {
                        minOf(s * 0.92f / b.width(), s * 0.92f / b.height())
                    } else 1f
                    paint.textSize = REF * k
                    paint.getTextBounds(text, 0, text.length, b)
                    // centrado sobre la caja de tinta; la línea base va por debajo del centro
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
                        // cúpula (semicírculo superior) + punto DEBAJO: en Android 0° son las 3 en
                        // punto y los ángulos van en sentido horario, así que 180→360 es la mitad
                        // de arriba. Con 0→180 la cúpula salía abajo.
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
                // los símbolos con Path son los que no se pintan con glifo ni con primitivas
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
