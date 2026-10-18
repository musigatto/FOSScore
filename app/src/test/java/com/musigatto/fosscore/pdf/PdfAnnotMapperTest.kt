package com.musigatto.fosscore.pdf

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapeo encaje-normalizado (0..1, y-hacia-abajo, el que usa el overlay) ->
 * espacio de página PDF (MediaBox, y-hacia-arriba, con /Rotate).
 * Solo el sentido de escritura (las inversas se re-añaden con el import desde PDF).
 */
class PdfAnnotMapperTest {

    private val box = PdfPageSpace(0f, 0f, 612f, 792f, 0)

    @Test
    fun cornersNoRotation() {
        // arriba-izq del encaje = arriba-izq de la página (y-hacia-arriba)
        val tl = PdfAnnotMapper.normalizedToPdf(Offset(0f, 0f), box)
        assertEquals(0f, tl.x, 1e-4f)
        assertEquals(792f, tl.y, 1e-4f)
        val br = PdfAnnotMapper.normalizedToPdf(Offset(1f, 1f), box)
        assertEquals(612f, br.x, 1e-4f)
        assertEquals(0f, br.y, 1e-4f)
    }
}
