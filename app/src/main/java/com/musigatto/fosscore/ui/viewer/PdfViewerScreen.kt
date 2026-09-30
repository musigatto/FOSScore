package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.pdf.MuPdfDoc
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// El techo de render (memoria) vive ahora en MuPdfDoc.MAX_RENDER_PIXELS: MuPDF rasteriza a la
// resolución que le pidamos, así que el zoom se ve nítido en vez de "slightly soft".
private const val LOG_TAG = "FOSScore-PDF"

// Ruido del táctil con los dedos quietos: ±1 px sobre ~400 px entre dedos ≈ 0,25 %. Con 0,4 % de
// zona muerta el zoom no reacciona al ruido (si no, el contenido "respira" y parece que tiembla).
private const val ZOOM_NOISE_RATIO = 0.004f

// Zoom al que salta el doble toque (2.5x: legible sin perder el contexto de la página).
private const val DOUBLE_TAP_ZOOM = 2.5f

// Tope de zoom. Los visores de referencia (AndroidPdfViewer, vfr/Viewer, GrapheneOS) NO escalan
// la página con un transform: la re-renderizan al zoom actual, así que la GPU nunca reescala la
// textura. Nuestro capa sí la escala, y por eso hay que acotar: con MAX_RENDER_PIXELS = 4M y una
// página que encaja en ~1,6M px, el máximo que MuPDF puede entregar sin reescalar es ~2.5x.
// Más allá la GPU ampliada con vecino más cercano = "cocer". Súbelo solo si además subes el
// presupuesto de pixels de MuPdfDoc.
private const val MAX_ZOOM = 2.5f

// Re-render durante el pellizco: mínimo entre renders (150 ms) y espera de asentamiento para el
// render final (250 ms sin cambios de zoom). Los visores de referencia re-renderizan durante el
// gesto (AndroidPdfViewer doRenderDuringScale, GrapheneOS onRenderPage(2)); acotados, el bitmap
// sigue al zoom y la GPU nunca reescala la textura.
private const val RENDER_DURING_ZOOM_MS = 150L
private const val RENDER_SETTLE_MS = 250L

// estado del gesto "estirar un tirador": qué objeto se escala desde qué esquina
private sealed interface Resizing {
    val startPos: Offset

    data class OfStamp(
        val stamp: Stamp,
        override val startPos: Offset
    ) : Resizing

    data class OfStroke(
        val stroke: Stroke,
        val ptsPx: List<Offset>,
        val center: Offset,   // centro de la caja: la escala crece hacia fuera desde aquí
        override val startPos: Offset
    ) : Resizing
}

// inverts page colors (white bg -> black, ink -> white) for OLED dark mode; applied as a
// draw-time colorFilter so the render cache is untouched.
private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)
private val INVERT_FILTER = ColorFilter.colorMatrix(ColorMatrix(INVERT_MATRIX))

private fun centroid(changes: List<PointerInputChange>): Offset =
    changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()

@Composable
fun PdfViewerScreen(
    pdfUri: Uri,
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    sheetHash: String? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var mupdfDoc by remember { mutableStateOf<MuPdfDoc?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable(pdfUri) {
        mutableIntStateOf(Settings.lastPage(context, pdfUri.toString()))
    }
    var twoUp by rememberSaveable { mutableStateOf(isLandscape) }
    var halfEnabled by rememberSaveable { mutableStateOf(false) }
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    var navVisible by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // SnapshotStateMap: al cambiar un bitmap solo se redibuja quien lee ESA clave. Con un Map
    // normal, cambiar el bitmap invalidaba el Image + el Canvas de tinta + los sellos enteros
    // (eso se-notaba como un tirón al cambiar la nitidez en mitad del pellizco).
    // doble toque: encaje <-> zoom. Se guardan el instante y el punto del toque anterior.
    var lastTapMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    // velocidad que queda al soltar el dedo (inercia del paneo); se anula al tocar
    var flingVel by remember { mutableStateOf(Offset.Zero) }
    val doubleTapSlopPx = with(LocalDensity.current) { 40.dp.toPx() }
    val bitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    // escalón de render del bitmap: el bitmap se rasteriza a encaje × renderScale (1:1 en
    // pantalla, cero reescalado de GPU). Lo alimenta el gesto vía ZoomMath.renderBucket.
    var renderScale by remember { mutableFloatStateOf(1f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var renderGeneration by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var dimPct by remember { mutableStateOf(Settings.dimPct(context)) }
    var showDim by rememberSaveable { mutableStateOf(false) }
    var invert by remember { mutableStateOf(Settings.invert(context)) }

    val stampsFlow = remember(sheetHash) {
        sheetHash?.let { (context.applicationContext as FOSScoreApp).stampRepository.observe(it) }
            ?: flowOf(emptyList())
    }
    val stamps by stampsFlow.collectAsState(initial = emptyList())
    val stampRepo = (context.applicationContext as FOSScoreApp).stampRepository

    val strokesFlow = remember(sheetHash) {
        sheetHash?.let { (context.applicationContext as FOSScoreApp).strokeRepository.observe(it) }
            ?: flowOf(emptyList())
    }
    val strokes by strokesFlow.collectAsState(initial = emptyList())
    val strokeRepo = (context.applicationContext as FOSScoreApp).strokeRepository

    var editing by rememberSaveable { mutableStateOf(false) }
    var activeSymbol by rememberSaveable { mutableStateOf<StampSymbol?>(null) }
    // stamp seleccionado (para el recuadro con tiradores) o trazo seleccionado
    var selectedStrokeId by remember { mutableStateOf<Long?>(null) }
    // tirador en curso: (objeto base, esquina agarrada) mientras se escala
    var resizing by remember { mutableStateOf<Resizing?>(null) }
    var activeColor by rememberSaveable { mutableStateOf<Int?>(null) }  // ARGB del tinte; null = tema
    var drawingTool by rememberSaveable { mutableStateOf(false) }      // modo lápiz: tinta, no sellos
    var eraserTool by rememberSaveable { mutableStateOf(false) }      // modo goma: borra sellos y trazos
    var penWidth by remember { mutableFloatStateOf(DEFAULT_STROKE_WIDTH) }  // grosor de tinta (frac. alto página)
    var dragStamp by remember { mutableStateOf<Stamp?>(null) }
    var dragStroke by remember { mutableStateOf<Stroke?>(null) }   // preview de mover/estirar un trazo
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var undoStack by remember { mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList()) }
    var inkPreview by remember { mutableStateOf<List<Offset>>(emptyList()) }  // preview del trazo en curso (px)
    // para pegar el trazo siguiente al anterior si el stylus solo alza unos ms
    val inkTail = remember { InkTail() }
    LaunchedEffect(currentPage, editing) { inkTail.last = null }
    // tinta ya soltada que la BD todavía no ha devuelto: sin esto el trazo parpadea ~20 ms
    var pendingInk by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    LaunchedEffect(currentPage, editing) { pendingInk = emptyList() }
    // borrado optimista: la vista esconde lo borrado al instante y la BD lo confirma después.
    // ponytail: Set de ids + poda por LaunchedEffect; suficiente porque borrar nunca resucita ids.
    var hiddenStampIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var hiddenStrokeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val editScope = rememberCoroutineScope()

    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // MuPDF tampoco es thread-safe (comparte el Context global): serializa render/close para que
    // un render cancelado (no cancelable a mitad de página) no colisione con el siguiente ni con
    // close(). Ponytail: un solo mutex por documento alcanza; si algún día hay multi-documento en
    // paralelo, moverlo a FOSScoreApp. La caché de bitmaps la lleva MuPdfDoc.
    val rendererLock = remember(pdfUri) { Mutex() }

    val branch = PageFlow(currentPage, halfTurned, halfEnabled, twoUp, isLandscape, pageCount)

    val canEdit = sheetHash != null && !branch.showTwoUp && !(branch.showHalf && halfTurned)

    // geometría y sellos de la página actual, compartidos entre overlay y gestos.
    // El encaje sale del tamaño en puntos de MuPDF (no del bitmap), así que la UI se maqueta
    // desde el primer frame y el overlay no depende de que el render haya aterrizado.
    val currentBmp = bitmaps[currentPage]
    val pageBox = mupdfDoc?.pageSize(currentPage)
    val pageStamps = if (sheetHash != null) {
        stamps.filter { it.page == currentPage && it.id !in hiddenStampIds }
    } else emptyList()
    val pageStrokes = if (sheetHash != null) {
        strokes.filter { it.page == currentPage && it.id !in hiddenStrokeIds }
    } else emptyList()
    val pageFit = if (pageBox != null && pageBox.height > 0f) {
        fitRect(
            viewport.width.toFloat(),
            viewport.height.toFloat(),
            pageBox.width / pageBox.height
        )
    } else RectPx(0f, 0f, 0f, 0f)
    // rect de encaje PURO (origen 0,0): coordenadas de contenido (la página en sí, sin el
    // margen de centrado de pageFit). Todo el overlay y el gesto de edición viven aquí.
    val hitFit = RectPx(0f, 0f, pageFit.width, pageFit.height)
    val currentEditing by rememberUpdatedState(editing)
    val currentCanEdit by rememberUpdatedState(canEdit)
    val currentActiveSymbol by rememberUpdatedState(activeSymbol)
    val currentPageStamps by rememberUpdatedState(pageStamps)
    val currentPageStrokes by rememberUpdatedState(pageStrokes)
    val currentPageFit by rememberUpdatedState(pageFit)
    val currentDrawingTool by rememberUpdatedState(drawingTool)
    val currentEraserTool by rememberUpdatedState(eraserTool)
    // base de centrado de la página en el viewport y rect de encaje puro (origen 0,0):
    // operaciones de contenido y anclaje del zoom viven en estas coordenadas
    val currentBase by rememberUpdatedState(Offset(pageFit.left, pageFit.top))
    val currentHitFit by rememberUpdatedState(RectPx(0f, 0f, pageFit.width, pageFit.height))
    // los modos de lectura (two-up, media página) escalan el viewport entero; la página única
    // escala su contenido. El gesto lo captura vía rememberUpdatedState: siempre lee lo vivo.
    val currentSpread by rememberUpdatedState(branch.showTwoUp || (branch.showHalf && halfTurned))

    val selectedStamp = pageStamps.firstOrNull { it.id == selectedId }
    val selectedStroke = pageStrokes.firstOrNull { it.id == selectedStrokeId }

    // recuadro de selección en px: caja del sello o del trazo. Mientras se arrastra o estira se
    // usa la posición viva (dragStamp/dragStroke) para que el recuadro siga al objeto.
    val selectionRect: RectPx? = when {
        dragStamp != null -> stampRect(dragStamp!!, hitFit)
        dragStroke != null -> strokeSelectionRect(
            decodePoints(dragStroke!!.points).map { normalizedToPx(it, hitFit) },
            dragStroke!!.width * hitFit.height
        )
        selectedStamp != null -> stampRect(selectedStamp, hitFit)
        selectedStroke != null -> strokeSelectionRect(
            decodePoints(selectedStroke.points).map { normalizedToPx(it, hitFit) },
            selectedStroke.width * hitFit.height
        )
        else -> null
    }
    val currentSelectionRect by rememberUpdatedState(selectionRect)
    val currentSelectedStamp by rememberUpdatedState(selectedStamp)
    val currentSelectedStroke by rememberUpdatedState(selectedStroke)

    // durante un tirador o un arrastre se dibuja el objeto en su posición viva, no la caja
    val showSelection = selectionRect != null && editing && canEdit &&
        !drawingTool && !eraserTool

    // apila snapshots ANTES de cada mutación, con su página; como forScore, 10 niveles
    fun pushUndo(snapshot: PageEdit = PageEdit(pageStamps, pageStrokes), page: Int = currentPage) {
        undoStack = (undoStack + listOf(page to snapshot)).takeLast(10)
    }

    val applyNext: () -> Unit = {
        val f = branch.next()
        currentPage = f.page
        halfTurned = f.halfTurned
    }
    val applyPrev: () -> Unit = {
        val f = branch.prev()
        currentPage = f.page
        halfTurned = f.halfTurned
    }

    fun clampOffsets() {
        if (viewport == IntSize.Zero) return
        // Página única: el contenido es la página en su encaje; los modos de lectura (two-up,
        // media página) escalan el viewport entero. `currentSpread`/`currentPageFit` son states
        // actualizados en cada composición: el gesto y la inercia, que capturan esta función al
        // empezar, leen siempre los valores vivos.
        val vps = Size(viewport.width.toFloat(), viewport.height.toFloat())
        val (content, base) = if (currentSpread) {
            Size(viewport.width.toFloat(), viewport.height.toFloat()) to Offset.Zero
        } else {
            Size(currentPageFit.width, currentPageFit.height) to
                Offset(currentPageFit.left, currentPageFit.top)
        }
        val o = ZoomMath.clampOffset(Offset(offsetX, offsetY), content, vps, scale, base)
        offsetX = o.x
        offsetY = o.y
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(currentPage) {
        Settings.setLastPage(context, pdfUri.toString(), currentPage)
    }

    // la BD ya no tiene lo que escondimos: limpia el borrado optimista (y el preview de arrastre)
    LaunchedEffect(stamps, strokes) {
        val liveStamps = stamps.map { it.id }.toSet()
        val liveStrokes = strokes.map { it.id }.toSet()
        if (hiddenStampIds.any { it !in liveStamps }) hiddenStampIds = hiddenStampIds intersect liveStamps
        if (hiddenStrokeIds.any { it !in liveStrokes }) hiddenStrokeIds = hiddenStrokeIds intersect liveStrokes
        dragStamp?.let { d ->
            if (stamps.any { it.id == d.id && it.x == d.x && it.y == d.y && it.size == d.size }) dragStamp = null
        }
        dragStroke?.let { d ->
            if (strokes.any { it.id == d.id && it.points == d.points && it.width == d.width }) dragStroke = null
        }
        // la BD ya trae esta tinta: fuera de la capa optimista
        pendingInk = pendingInk.filter { p -> strokes.none { it.id == p.id && it.points == p.points } }
    }

    // MuPDF (AAR prebuilt, AGPL-3.0: ver LICENSE). Abre y calcula tamaños en segundo plano: abrir
    // parsea el índice del PDF y no puede tocar el main.
    LaunchedEffect(pdfUri) {
        try {
            // NonCancellable: si el usuario sale mientras abrimos, el documento se crea igual;
            // lo cerramos abajo. Cancelar aquí dejaría el doc nativo colgado (y su memoria).
            val doc = withContext(NonCancellable + Dispatchers.IO) { MuPdfDoc.open(context, pdfUri) }
            if (!isActive) {
                withContext(NonCancellable + Dispatchers.IO) { doc.close() }
                return@LaunchedEffect
            }
            pageCount = doc.pageCount
            if (pageCount > 0) currentPage = currentPage.coerceIn(0, pageCount - 1)
            mupdfDoc = doc
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(LOG_TAG, "no se pudo abrir el archivo: ${e.message}", e)
            error = "No se pudo abrir el archivo"
        } catch (e: Exception) {
            Log.e(LOG_TAG, "no se pudo abrir: ${e::class.simpleName}: ${e.message}", e)
            error = "PDF inválido o corrupto"
        }
    }

    // zoom a cero al cambiar de página o de modo (el escalón de render también: si no, se
    // paginaría renderizando cada página ampliada)
    LaunchedEffect(currentPage, branch.showTwoUp, branch.showHalf, halfTurned) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        renderScale = 1f
    }

    // Inercia del paneo: al soltar, el desplazamiento sigue con fricción hasta que la velocidad
    // muere o topa con el borde. Sin esto, recorrer una página ampliada a dedo es lentísimo.
    // ponytail: bucle propio con fricción en vez de Animatable+decay (menos código, mismo efecto).
    LaunchedEffect(flingVel) {
        var v = flingVel
        flingVel = Offset.Zero
        if (v.getDistance() < 600f) return@LaunchedEffect
        var px = offsetX
        var py = offsetY
        while (v.getDistance() > 20f) {
            delay(16)
            px += v.x * 0.016f
            py += v.y * 0.016f
            val bx = offsetX
            val by = offsetY
            offsetX = px
            offsetY = py
            clampOffsets()
            // el clamp nos ha frenado contra el borde: la inercia ahí no aporta nada
            if (offsetX == bx && offsetY == by) break
            v *= 0.965f   // fricción larga: el glide se siente, no se corta a los 200 ms
        }
    }

    // Re-render al zoom actual (escalones del 10% del encaje, ZoomMath.renderBucket): como los
    // visores de referencia (AndroidPdfViewer: doRenderDuringScale; GrapheneOS: onRenderPage(2)),
    // el bitmap sigue al zoom para que la GPU nunca lo reescala. Durante el pellizco solo se
    // SUBE de escalón, con un mínimo de 150 ms entre renders; al asentarse (sin cambios de scale
    // durante 250 ms) se afina el escalón final, que también puede BAJAR tras reducir el zoom.
    LaunchedEffect(mupdfDoc, viewport) {
        var lastUpgradeMs = 0L
        snapshotFlow { scale }.collectLatest { s ->
            val target = ZoomMath.renderBucket(s)
            val now = System.currentTimeMillis()
            if (target > renderScale && now - lastUpgradeMs >= RENDER_DURING_ZOOM_MS) {
                lastUpgradeMs = now
                renderScale = target
                return@collectLatest
            }
            delay(RENDER_SETTLE_MS)
            // si el scale siguió cambiando mientras esperábamos, la siguiente emisión lo coge
            if (target >= ZoomMath.renderBucket(scale)) renderScale = target
        }
    }

    // viewport es clave: en el primer frame todavía es IntSize.Zero y sin tamaño no hay render
    LaunchedEffect(
        mupdfDoc, currentPage, branch.showTwoUp, branch.showHalf, halfTurned, renderScale, viewport
    ) {
        val r = mupdfDoc ?: return@LaunchedEffect
        val gen = ++renderGeneration
        val needed = when {
            branch.showTwoUp -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            branch.showHalf && halfTurned -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            else -> listOf(currentPage)
        }
        val isSpread = branch.showTwoUp || (branch.showHalf && halfTurned)
        // Página única: el hueco de render es el encaje × escalón de zoom (el bitmap se dibuja
        // 1:1 en pantalla, la GPU no reescala nada). Modos de lectura (two-up, media página): el
        // hueco es el viewport y la capa sigue escalando (es para leer, no para ampliar).
        val spreadSlot =
            if (isSpread) Pair(if (branch.showTwoUp) viewport.width / 2 else viewport.width, viewport.height)
            else null
        // lecturas del estado de Compose ANTES de entrar al dispatcher de render
        val rs = renderScale
        val vpW = viewport.width.toFloat()
        val vpH = viewport.height.toFloat()
        val fallback = needed.associateWith { bitmaps[it] }
        try {
            val session = rendererLock.withLock {
                withContext(Dispatchers.Default) {
                    // el tamaño real de la página puede no estar cargado aún (páginas más allá del
                    // preload): se resuelve dentro del lock con loadSize (parseo barato del
                    // diccionario de la página), el mismo camino que sigue render para su encaje
                    val slot = if (isSpread) spreadSlot!! else {
                        val box = r.ensurePageSize(currentPage)
                        if (box == null || box.height <= 0f) return@withContext emptyMap<Int, Bitmap>()
                        val fit = fitRect(vpW, vpH, box.width / box.height)
                        Pair((fit.width * rs).toInt(), (fit.height * rs).toInt())
                    }
                    val out = mutableMapOf<Int, Bitmap>()
                    for (idx in needed) {
                        // si el render falla seguimos con lo que ya se ve: mejor tenue que hueco
                        out[idx] = r.render(idx, slot.first, slot.second, 1) ?: fallback[idx] ?: continue
                    }
                    out
                }
            }
            // guard against an older, non-cancellable render landing after a newer one
            if (gen == renderGeneration) {
                for ((idx, bmp) in session) bitmaps[idx] = bmp
                // no acumulamos bitmaps de páginas que ya no se ven (16 MB cada uno en alta nitidez)
                bitmaps.keys.filterNot { it in needed }.forEach { bitmaps.remove(it) }
                // sin bitmap para esta página no hay nada que enseñar: dilo en vez de dejar el
                // spinner girando eternamente (pasó con una página en blanco)
                if (bitmaps[currentPage] == null) {
                    Log.e(LOG_TAG, "la página $currentPage no se pudo renderizar")
                    error = "No se pudo renderizar la página"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "render failed: ${e::class.simpleName}: ${e.message}", e)
            if (gen == renderGeneration) error = "No se pudo renderizar la página"
        }
    }

    DisposableEffect(mupdfDoc) {
        val current = mupdfDoc
        onDispose {
            // nunca bloquear el main esperando a un render en vuelo (ANR): close() va a
            // applicationScope; la cola FIFO del mutex garantiza que ocurre tras el render.
            val scope = (context.applicationContext as FOSScoreApp).applicationScope
            scope.launch {
                rendererLock.withLock { current?.close() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
    ) {
        if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(error!!, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Volver") }
            }
        } else if (bitmaps.isNotEmpty()) {
            // Modos de lectura (two-up, media página): la capa escala el viewport entero como antes
            // (no se usan para ampliar detalles: el offscreen barre el "cocer" sin coste notorio de
            // nitidez). Página única (rama else): SIN capa de escala — el bitmap se renderiza al
            // zoom actual y se dibuja 1:1; el overlay se re-rasteriza como vector (ver plan
            // 2026-09-30-zoom-render-1to1).
            val spreadMod = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    compositingStrategy = CompositingStrategy.Offscreen
                }
            val gestureMod = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        flingVel = Offset.Zero   // tocar corta la inercia
                        // pantalla -> coordenadas de encaje (0..fitW x 0..fitH): el contenido ya no
                        // vive en una capa escalada, el gesto traduce aquí el punto de los dedos
                        fun fitPoint(p: Offset): Offset {
                            val b = currentBase
                            return Offset(
                                (p.x - b.x - offsetX) / scale,
                                (p.y - b.y - offsetY) / scale
                            )
                        }
                        val downF = fitPoint(down.position)
                        // base del anclaje: la página única escala su contenido (centrado en
                        // pageFit.left/top); los modos de lectura escalan el viewport entero (origen 0)
                        val anchorBase = if (currentSpread) Offset.Zero else currentBase
                        var ids = setOf(down.id)
                        var prevCentroid = down.position
                        var anchorDist = 0f
                        var totalPan = Offset.Zero
                        var isTransform = false
                        var movingStamp: Stamp? = null
                        var movingOrigin: Stamp? = null
                        var moveDownId = down.id
                        var moveTrackingPos = downF
                        var movingStroke: Stroke? = null
                        var movingStrokeBase: Stroke? = null
                        var moveStrokeLastPos = downF
                        var inkActive = false
                        var inkPts = mutableListOf<Offset>()
                        var eraserActive = false
                        var eraserPath = mutableListOf<Offset>()
                        var scaleF = 1f   // factor acumulado del tirador (siempre desde startPos)
                        var panVel = Offset.Zero
                        var lastPanMs = System.currentTimeMillis()
                        if (currentEditing && currentCanEdit && !currentDrawingTool && !currentEraserTool) {
                            // 1) un tirador del recuadro de selección manda sobre todo lo demás:
                            //    entrar por la esquina estira, entrar por dentro mueve.
                            val sel = currentSelectionRect
                            val corner = if (sel != null) hitHandle(downF, sel, currentHitFit) else null
                            if (corner != null) {
                                resizing = when (val s = currentSelectedStamp) {
                                    null -> currentSelectedStroke?.let { st ->
                                        val ptsPx = decodePoints(st.points)
                                            .map { normalizedToPx(it, currentHitFit) }
                                        Resizing.OfStroke(
                                            stroke = st,
                                            ptsPx = ptsPx,
                                            center = strokeBounds(ptsPx).center(),
                                            startPos = downF
                                        )
                                    }
                                    else -> Resizing.OfStamp(stamp = s, startPos = downF)
                                }
                            }
                            if (resizing == null) {
                                val hitS = hitStamp(downF, currentPageStamps, currentHitFit)
                                val hitT = if (hitS == null) {
                                    hitStroke(downF, currentPageStrokes, currentHitFit)
                                } else null
                                if (hitS != null) {
                                    movingStamp = hitS
                                    movingOrigin = hitS
                                    selectedId = hitS.id
                                    selectedStrokeId = null
                                    activeSymbol = runCatching { StampSymbol.valueOf(hitS.symbol) }.getOrNull()
                                    dragStamp = hitS
                                } else if (hitT != null) {
                                    movingStroke = hitT
                                    movingStrokeBase = hitT
                                    moveStrokeLastPos = downF
                                    selectedStrokeId = hitT.id
                                    selectedId = null
                                    dragStroke = hitT
                                }
                            }
                        }
                        if (currentEditing && currentCanEdit && currentEraserTool) {
                            eraserActive = true
                            eraserPath.add(downF)
                            inkPreview = listOf(downF)
                        }
                        if (currentEditing && currentCanEdit && currentDrawingTool) {
                            inkActive = true
                            inkPts.add(downF)
                            inkPreview = listOf(downF)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (resizing != null) {
                                    val rz = resizing
                                    resizing = null
                                    when (rz) {
                                        is Resizing.OfStamp -> {
                                            val base = rz.stamp
                                            val newSize = (base.size * scaleF)
                                                .coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)
                                            if (kotlin.math.abs(newSize - base.size) > 1e-4f) {
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    stampRepo.update(base.copy(size = newSize))
                                                }
                                            }
                                        }
                                        is Resizing.OfStroke -> {
                                            val base = rz.stroke
                                            val pts = scalePoints(rz.ptsPx, rz.center, scaleF)
                                            val newW = (base.width * scaleF)
                                                .coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                            if (kotlin.math.abs(newW - base.width) > 1e-5f ||
                                                pts != rz.ptsPx
                                            ) {
                                                pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                                editScope.launch {
                                                    strokeRepo.update(
                                                        base.copy(
                                                            points = encodePoints(
                                                                pts.map { pxToNormalized(it, currentHitFit) }
                                                            ),
                                                            width = newW
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        null -> {}  // resizing es una var capturada: puede ser null
                                    }
                                } else if (inkActive) {
                                    inkActive = false
                                    // la captura de tinta vive en coordenadas de encaje (F): el umbral
                                    // de muestreo es de pantalla, así que se escala con el zoom
                                    val pts = samplePoints(inkPts, MIN_SAMPLE_DIST_PX / scale)
                                    inkPts = mutableListOf()
                                    inkPreview = emptyList()
                                    if (pts.size >= 2 && sheetHash != null && currentPageFit.width > 0f) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        val norm = pts.map { pxToNormalized(it, currentHitFit) }
                                        val fit = currentHitFit
                                        val page = currentPage
                                        val hash = sheetHash
                                        val width = penWidth
                                        val color = activeColor
                                        val startPx = pts.first()   // comparación en px, no normalizado
                                        editScope.launch {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            val prev = inkTail.last
                                            val prevPts = prev?.points?.let { decodePoints(it) }
                                                ?.map { normalizedToPx(it, fit) } ?: emptyList()
                                            val canMerge = prev != null && prevPts.isNotEmpty() &&
                                                shouldMergeStroke(
                                                    prevPts.last(),
                                                    startPx,
                                                    fit,
                                                    now - inkTail.at,
                                                    prev.width == width && prev.color == color
                                                )
                                            Log.d(LOG_TAG, "ink: merge=$canMerge gap=${now - inkTail.at}ms prev=${prev != null} dist=${if (prevPts.isNotEmpty()) (startPx - prevPts.last()).getDistance() else -1f}px limit=${STROKE_MERGE_DIST_FRAC * fit.height}px")
                                            if (canMerge) {
                                                val merged = prev!!.copy(
                                                    points = encodePoints(decodePoints(prev.points) + norm)
                                                )
                                                strokeRepo.update(merged)
                                                inkTail.last = merged
                                                pendingInk = pendingInk.filter { it.id != merged.id } + merged
                                            } else {
                                                val s = Stroke(
                                                    sheetHash = hash,
                                                    page = page,
                                                    points = encodePoints(norm),
                                                    width = width,
                                                    color = color
                                                )
                                                val stored = s.copy(id = strokeRepo.insert(s))
                                                inkTail.last = stored
                                                pendingInk = pendingInk + stored
                                            }
                                            inkTail.at = now
                                        }
                                    }
                                } else if (eraserActive) {
                                    eraserActive = false
                                    val path = eraserPath
                                    eraserPath = mutableListOf()
                                    inkPreview = emptyList()
                                    if (path.isNotEmpty() && sheetHash != null && currentPageFit.width > 0f) {
                                        pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        val radiusPx = ERASE_RADIUS_FRAC * currentHitFit.height
                                        // la goma siempre borra lo que roza: para quitar una palabra
                                        // entera, tocarla (la selecciona) y luego 🗑
                                        val goneStamps = currentPageStamps.filter { st ->
                                            val r = stampRect(st, currentHitFit)
                                            path.any { r.contains(it) }
                                        }
                                        val goneStrokes = mutableListOf<Stroke>()
                                        val newRuns = mutableListOf<Stroke>()
                                        for (st in currentPageStrokes) {
                                            val ptsPx = decodePoints(st.points).map { normalizedToPx(it, currentHitFit) }
                                            val runs = eraseStrokePoints(ptsPx, path, radiusPx)
                                            if (runs.size == 1 && runs[0].size == ptsPx.size) {
                                                // intacto
                                            } else {
                                                goneStrokes.add(st)
                                                runs.forEach { r ->
                                                    newRuns.add(
                                                        st.copy(
                                                            id = 0,
                                                            points = encodePoints(r.map { pxToNormalized(it, currentHitFit) })
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        selectedId = null
                                        selectedStrokeId = null
                                        inkTail.last = null   // el trazo anterior ya no existe
                                        // que se vea borrado YA: la BD va por detrás (~20 ms)
                                        hiddenStampIds = hiddenStampIds + goneStamps.map { it.id }
                                        hiddenStrokeIds = hiddenStrokeIds + goneStrokes.map { it.id }
                                        editScope.launch {
                                            if (goneStamps.isNotEmpty()) stampRepo.deleteAll(goneStamps)
                                            if (goneStrokes.isNotEmpty()) {
                                                strokeRepo.deleteAll(goneStrokes)
                                                strokeRepo.insertAll(newRuns)
                                            }
                                        }
                                    }
                                } else if (movingStroke != null) {
                                    val done = movingStroke
                                    val base = movingStrokeBase
                                    movingStroke = null
                                    movingStrokeBase = null
                                    // mantener la posición final: si se limpia aquí, el overlay dibuja la
                                    // posición vieja de la BD unos ms hasta que el flow re-emite (glitch).
                                    dragStroke = done
                                    if (done != null && base != null) {
                                        if (done.points != base.points) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch { strokeRepo.update(done) }
                                        }
                                    }
                                } else if (movingStamp != null) {
                                    val done = movingStamp
                                    val origin = movingOrigin
                                    movingStamp = null
                                    movingOrigin = null
                                    // mantener la posición final: si se limpia aquí, el overlay dibuja la
                                    // posición vieja de la BD unos ms hasta que el flow re-emite (glitch).
                                    dragStamp = done
                                    if (done != null) {
                                        if (origin != null && (done.x != origin.x || done.y != origin.y)) {
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                        }
                                        editScope.launch { stampRepo.update(done) }
                                    }
                                } else if (!isTransform) {
                                    // doble toque: alterna encaje <-> 2.5x en el punto tocado. Es el
                                    // atajo para volver a ver la página entera (y para meterse en un
                                    // detalle) sin pelearse con los botones de la barra.
                                    val nowMs = System.currentTimeMillis()
                                    val isDoubleTap = nowMs - lastTapMs < 320L &&
                                        (down.position - lastTapPos).getDistance() < doubleTapSlopPx
                                    if (isDoubleTap && !currentEditing) {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            val k = DOUBLE_TAP_ZOOM / scale
                                            val no = ZoomMath.anchoredOffset(
                                                Offset(offsetX, offsetY), k, down.position, anchorBase
                                            )
                                            offsetX = no.x
                                            offsetY = no.y
                                            scale = DOUBLE_TAP_ZOOM
                                            clampOffsets()
                                        }
                                        lastTapMs = 0L
                                    } else {
                                        lastTapMs = nowMs
                                        lastTapPos = down.position
                                        flingVel = if (panVel.getDistance() > 600f) panVel else Offset.Zero
                                    }
                                    if (isDoubleTap) {
                                        // ya gestionado arriba: ni coloca sello ni cambia de página
                                    } else if (currentEditing && currentCanEdit) {
                                        // tap en modo editar: coloca el sello activo en el punto tocado
                                        val sym = currentActiveSymbol
                                        if (sym == null) {
                                            // sin herramienta: solo deseleccionar
                                            selectedId = null
                                            selectedStrokeId = null
                                            dragStamp = null
                                            dragStroke = null
                                        }
                                        if (sym != null && sheetHash != null && scale <= 1f) {
                                            val pos = pxToNormalized(downF, currentHitFit)
                                            pushUndo(PageEdit(currentPageStamps, currentPageStrokes))
                                            editScope.launch {
                                                val id = stampRepo.insert(
                                                    Stamp(
                                                        sheetHash = sheetHash,
                                                        page = currentPage,
                                                        symbol = sym.name,
                                                        x = pos.x.coerceIn(0f, 1f),
                                                        y = pos.y.coerceIn(0f, 1f),
                                                        size = DEFAULT_STAMP_SIZE,
                                                        color = activeColor
                                                    )
                                                )
                                                selectedId = id
                                            }
                                        }
                                    } else {
                                        val isSwipe = scale <= 1f &&
                                            totalPan.getDistance() > swipeThresholdPx &&
                                            abs(totalPan.x) > abs(totalPan.y)
                                        if (isSwipe) {
                                            if (totalPan.x < 0f) applyNext() else applyPrev()
                                        } else {
                                            // tap zones (forScore-style): left third prev, right third next, center toggles nav
                                            val width = viewport.width
                                            when {
                                                width > 0 && down.position.x > width * 2f / 3f -> applyNext()
                                                width > 0 && down.position.x < width / 3f -> applyPrev()
                                                else -> navVisible = !navVisible
                                            }
                                        }
                                    }
                                }
                                break
                            }

                            if (resizing != null) {
                                // estirar por un tirador: 1 puntero, escala sobre el centro.
                                // el factor se recalcula desde la posición inicial, nunca se acumula
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val rz = resizing!!
                                val center = when (rz) {
                                    is Resizing.OfStamp -> stampRect(rz.stamp, currentHitFit).center()
                                    is Resizing.OfStroke -> rz.center
                                }
                                val f = scaleFactor(center, rz.startPos, fitPoint(tracking.position))
                                scaleF = f
                                when (rz) {
                                    is Resizing.OfStamp -> {
                                        dragStamp = rz.stamp.copy(
                                            size = (rz.stamp.size * f).coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE)
                                        )
                                    }
                                    is Resizing.OfStroke -> {
                                        val pts = scalePoints(rz.ptsPx, center, f)
                                        dragStroke = rz.stroke.copy(
                                            points = encodePoints(pts.map { pxToNormalized(it, currentHitFit) }),
                                            width = (rz.stroke.width * f).coerceIn(MIN_STROKE_WIDTH, MAX_STROKE_WIDTH)
                                        )
                                    }
                                }
                                prevCentroid = tracking.position
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (movingStroke != null) {
                                val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                    pxToNormalized(moveStrokeLastPos, currentHitFit)
                                moveStrokeLastPos = fitPoint(tracking.position)
                                val ns = movingStroke!!.copy(
                                    points = encodePoints(
                                        decodePoints(movingStroke!!.points).map {
                                            Offset((it.x + m.x).coerceIn(0f, 1f), (it.y + m.y).coerceIn(0f, 1f))
                                        }
                                    )
                                )
                                movingStroke = ns
                                dragStroke = ns
                                prevCentroid = tracking.position
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (movingStamp != null) {
                                // mover con 1 dedo (el puntero inicial, sin saltos al añadir otro dedo);
                                // sin reescalar por pellizco — forScore ajusta el tamaño con un slider
                                val tracking = pressed.firstOrNull { it.id == moveDownId } ?: pressed.first()
                                val m = pxToNormalized(fitPoint(tracking.position), currentHitFit) -
                                    pxToNormalized(moveTrackingPos, currentHitFit)
                                moveTrackingPos = fitPoint(tracking.position)
                                val ns = movingStamp!!.copy(
                                    x = (movingStamp!!.x + m.x).coerceIn(0f, 1f),
                                    y = (movingStamp!!.y + m.y).coerceIn(0f, 1f)
                                )
                                movingStamp = ns
                                dragStamp = ns
                                prevCentroid = centroid(pressed)
                                event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                continue
                            }

                            if (inkActive) {
                                if (pressed.size > 1) {
                                    // llegó un segundo dedo: abandona la tinta, deja que el pellizco haga zoom
                                    inkActive = false
                                    inkPts = mutableListOf()
                                    inkPreview = emptyList()
                                } else {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val last = inkPts.last()
                                    // puntos capturados en coordenadas de encaje: el umbral de
                                    // pantalla se escala con el zoom
                                    if ((fitPoint(tracking.position) - last).getDistance() >=
                                        MIN_SAMPLE_DIST_PX / scale
                                    ) {
                                        inkPts.add(fitPoint(tracking.position))
                                        inkPreview = inkPts.toList()
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }
                            }

                            if (eraserActive) {
                                if (pressed.size > 1) {
                                    eraserActive = false
                                    eraserPath = mutableListOf()
                                    inkPreview = emptyList()
                                } else {
                                    val tracking = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                    val last = eraserPath.last()
                                    if ((fitPoint(tracking.position) - last).getDistance() >=
                                        MIN_SAMPLE_DIST_PX / scale
                                    ) {
                                        eraserPath.add(fitPoint(tracking.position))
                                        inkPreview = eraserPath.toList()
                                    }
                                    prevCentroid = tracking.position
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                    continue
                                }
                            }

                            val nids = pressed.map { it.id }.toSet()
                            if (nids != ids) {
                                ids = nids
                                anchorDist = 0f      // se reancla al cambiar el nº de dedos
                                prevCentroid = centroid(pressed)
                            }

                            val c = centroid(pressed)
                            val delta = c - prevCentroid
                            totalPan += delta

                            if (pressed.size > 1) {
                                isTransform = true
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (anchorDist == 0f) {
                                    anchorDist = d
                                } else if (d > 0f) {
                                    // INCREMENTAL: aplicamos cuánto ha cambiado la distancia entre
                                    // los dedos en ESTE frame (ratio), no un objetivo absoluto. Con un
                                    // objetivo absoluto suavizado la escala se queda siempre a
                                    // medias -> zoom lento y el anclaje se deriva hacia el centro.
                                    val ratio = d / anchorDist
                                    anchorDist = d   // el siguiente frame mide contra esta distancia
                                    // zona muerta contra el ruido del táctil (±1 px, dedos quietos)
                                    if (abs(ratio - 1f) > ZOOM_NOISE_RATIO) {
                                        val target = (scale * ratio).coerceIn(1f, MAX_ZOOM)
                                        if (target != scale) {
                                            // anclaje: el punto bajo los dedos se queda quieto
                                            val k = target / scale
                                            val no = ZoomMath.anchoredOffset(
                                                Offset(offsetX, offsetY), k, c, anchorBase
                                            )
                                            offsetX = no.x
                                            offsetY = no.y
                                            scale = target
                                        }
                                    }
                                }
                                // y seguimos a los dedos si los dos se mueven juntos
                                offsetX += delta.x
                                offsetY += delta.y
                            } else if (scale > 1f && delta.getDistance() > 0f) {
                                // un solo dedo: pan (el escalado ya lo lleva el caso de arriba)
                                isTransform = true
                                offsetX += delta.x
                                offsetY += delta.y
                                // velocidad suavizada, para la inercia al soltar
                                val nowMs = System.currentTimeMillis()
                                val dt = (nowMs - lastPanMs).coerceAtLeast(1L) / 1000f
                                if (dt < 0.2f) panVel = panVel * 0.7f + (delta / dt) * 0.3f
                                lastPanMs = nowMs
                            }
                            clampOffsets()
                            prevCentroid = c
                            event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                        }
                    }
                }

            when {
                branch.showTwoUp -> {
                    Row(modifier = spreadMod.then(gestureMod)) {
                        bitmaps[currentPage]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        bitmaps[currentPage + 1]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
                branch.showHalf && halfTurned && bitmaps[currentPage] != null && bitmaps[currentPage + 1] != null -> {
                    // ponytail: compose both halves into a single bitmap the same size as one page,
                    // so it scales identically to the full page and the bottom (reading) half stays put.
                    // Assumes consecutive pages share the same dimensions.
                    val cur = bitmaps[currentPage]!!
                    val next = bitmaps[currentPage + 1]!!
                    val w = cur.width
                    val h = cur.height
                    val sepColor = MaterialTheme.colorScheme.primary.toArgb()
                    // remember por los BITMAPS (no por el mapa): con SnapshotStateMap, leer las
                    // claves arriba ya registra la dependencia y solo se recompone si cambian
                    val combined = remember(cur, next, w, h) {
                        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(out)
                        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
                        canvas.drawBitmap(next, Rect(0, 0, next.width, next.height / 2), Rect(0, 0, w, h / 2), paint)
                        canvas.drawBitmap(cur, Rect(0, cur.height / 2, cur.width, cur.height), Rect(0, h / 2, w, h), paint)
                        paint.color = sepColor
                        paint.strokeWidth = (h / 200f).coerceAtLeast(2f)
                        canvas.drawLine(0f, (h / 2).toFloat(), w.toFloat(), (h / 2).toFloat(), paint)
                        out
                    }
                    Image(
                        bitmap = combined.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (invert) INVERT_FILTER else null,
                        modifier = spreadMod.then(gestureMod)
                    )
                }
                else -> {
                    bitmaps[currentPage]?.let { bmp ->
                        // Página única: el bitmap se renderiza al zoom actual (encaje × scale, el
                        // mismo tamaño que ocupa en pantalla) y se dibuja 1:1 — la GPU no reescala
                        // nada. El overlay (tinta/sellos/selección) se dibuja en coordenadas de
                        // encaje y el zoom se aplica en el propio dibujo con translate+scale:
                        // vectorial, se re-rasteriza cada frame y queda nítido a cualquier zoom.
                        val density = LocalDensity.current
                        val k = scale
                        val posX = pageFit.left + offsetX
                        val posY = pageFit.top + offsetY
                        Box(modifier = Modifier.fillMaxSize().then(gestureMod)) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Page ${currentPage + 1} of $pageCount",
                                contentScale = ContentScale.FillBounds,
                                colorFilter = if (invert) INVERT_FILTER else null,
                                modifier = Modifier
                                    .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                                    .size(
                                        with(density) { (pageFit.width * k).toDp() },
                                        with(density) { (pageFit.height * k).toDp() }
                                    )
                            )
                            if (canEdit) {
                                // por defecto tinta/sellos en negro (no el primary azulado del tema)
                                val themeColor = Color.Black
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    withTransform({
                                        translate(posX, posY)
                                        scale(k, k, pivot = Offset.Zero)
                                    }) {
                                        fun drawStrokePath(pts: List<Offset>, color: Color, widthPx: Float) {
                                            if (pts.size < 2) return
                                            val path = Path()
                                            path.moveTo(pts[0].x, pts[0].y)
                                            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                                            drawPath(
                                                path,
                                                color = color,
                                                style = DrawStroke(
                                                    width = max(1f, widthPx),
                                                    cap = StrokeCap.Round,
                                                    join = StrokeJoin.Round
                                                )
                                            )
                                        }
                                        // pendingInk va primero: si el flujo aún trae la versión
                                        // anterior (tras una fusión), gana la que se está escribiendo
                                        for (stroke in (pendingInk + pageStrokes).distinctBy { it.id }) {
                                            // el que se está moviendo/estirando se dibuja solo en su
                                            // posición viva (si no, quedan dos copias = fantasma)
                                            if (dragStroke?.id == stroke.id) continue
                                            drawStrokePath(
                                                decodePoints(stroke.points).map { normalizedToPx(it, hitFit) },
                                                stampColor(stroke.color, themeColor),
                                                stroke.width * hitFit.height
                                            )
                                        }
                                        // trazo seleccionado movido/estirado en vivo
                                        dragStroke?.let { ds ->
                                            drawStrokePath(
                                                decodePoints(ds.points).map { normalizedToPx(it, hitFit) },
                                                stampColor(ds.color, themeColor),
                                                ds.width * hitFit.height
                                            )
                                        }
                                        if (inkPreview.size >= 2) {
                                            drawStrokePath(
                                                inkPreview,
                                                if (eraserTool) Color.Red.copy(alpha = 0.4f)
                                                else stampColor(activeColor, themeColor),
                                                if (eraserTool) ERASE_RADIUS_FRAC * hitFit.height * 2f
                                                else penWidth * hitFit.height
                                            )
                                        }
                                    }
                                }
                                for (stamp in pageStamps) {
                                    val shown = dragStamp?.takeIf { it.id == stamp.id } ?: stamp
                                    val sym = runCatching { StampSymbol.valueOf(shown.symbol) }.getOrNull()
                                        ?: continue
                                    val r = stampRect(shown, hitFit)
                                    StampView(
                                        symbol = sym,
                                        sizePx = r.width * k,
                                        color = stampColor(shown.color, Color.Black),
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    (posX + r.left * k).roundToInt(),
                                                    (posY + r.top * k).roundToInt()
                                                )
                                            }
                                            .size(with(density) { (r.width * k).toDp() })
                                    )
                                }
                                // recuadro de selección POR ENCIMA de todo: si no, los tiradores
                                // quedan tapados por el propio sello que se está editando
                                if (showSelection && selectionRect != null) {
                                    val sel = selectionRect
                                    val hr = handleRadiusPx(hitFit)
                                    val frame = Color(0xFF00A0FF)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        withTransform({
                                            translate(posX, posY)
                                            scale(k, k, pivot = Offset.Zero)
                                        }) {
                                            drawRect(
                                                color = frame,
                                                topLeft = Offset(sel.left, sel.top),
                                                size = Size(sel.width, sel.height),
                                                style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                            )
                                            handleCenters(sel, hitFit).forEach { hc ->
                                                drawRect(
                                                    color = Color.White,
                                                    topLeft = Offset(hc.x - hr, hc.y - hr),
                                                    size = Size(hr * 2f, hr * 2f)
                                                )
                                                drawRect(
                                                    color = frame,
                                                    topLeft = Offset(hc.x - hr, hc.y - hr),
                                                    size = Size(hr * 2f, hr * 2f),
                                                    style = DrawStroke(width = max(1.5f, hr * 0.3f))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (dimPct > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimPct / 100f))
            )
        }

        if (navVisible) {
            val surface = MaterialTheme.colorScheme.surface

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(surface)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Modo:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        halfEnabled = !halfEnabled
                        halfTurned = false
                    },
                    enabled = !editing,
                    colors = if (halfEnabled) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text("½") }
                if (isLandscape) {
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = false },
                        enabled = !editing,
                        colors = if (!twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("1") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = true },
                        enabled = !editing,
                        colors = if (twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("2") }
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onToggleTheme) { Text(themeMode.label) }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        invert = !invert
                        Settings.setInvert(context, invert)
                    },
                    colors = if (invert) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text("Neg") }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        editing = !editing
                        if (editing) {
                            twoUp = false
                            halfEnabled = false
                            halfTurned = false
                        } else {
                            undoStack = emptyList()
                            selectedId = null
                            selectedStrokeId = null
                            dragStamp = null
                            dragStroke = null
                            drawingTool = false
                            eraserTool = false
                            inkPreview = emptyList()
                            hiddenStampIds = emptySet()
                            hiddenStrokeIds = emptySet()
                        }
                    },
                    enabled = canEdit,
                    colors = if (editing) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text("Editar") }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(surface)
            ) {
                if (showDim) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Atenuar", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = dimPct,
                            onValueChange = { dimPct = it },
                            onValueChangeFinished = { Settings.setDimPct(context, dimPct) },
                            valueRange = 0f..80f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )
                        Text("${dimPct.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StampSymbol.entries.forEach { sym ->
                            StampPaletteButton(
                                symbol = sym,
                                selected = sym == activeSymbol,
                                onClick = {
                                    activeSymbol = sym
                                    drawingTool = false
                                    eraserTool = false
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // swatch transparen = sin tinte (color del tema)
                        ColorSwatchButton(
                            color = Color.Transparent,
                            selected = activeColor == null,
                            onClick = {
                                val s = selectedStamp
                                activeColor = null
                                if (s != null && s.color != null) {
                                    pushUndo()
                                    editScope.launch { stampRepo.update(s.copy(color = null)) }
                                }
                            }
                        )
                        Spacer(Modifier.width(6.dp))
                        SWATCH_COLORS.forEach { c ->
                            ColorSwatchButton(
                                color = c,
                                selected = activeColor == c.toArgb(),
                                onClick = {
                                    activeColor = c.toArgb()
                                    val s = selectedStamp
                                    if (s != null && s.color != c.toArgb()) {
                                        pushUndo()
                                        editScope.launch { stampRepo.update(s.copy(color = c.toArgb())) }
                                    }
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                if (editing && canEdit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                drawingTool = !drawingTool
                                if (drawingTool) {
                                    activeSymbol = null
                                    eraserTool = false
                                    selectedId = null
                                }
                            },
                            colors = if (drawingTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors()
                        ) { Text("✏️") }
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = {
                                eraserTool = !eraserTool
                                if (eraserTool) {
                                    activeSymbol = null
                                    drawingTool = false
                                    selectedId = null
                                    selectedStrokeId = null
                                    inkPreview = emptyList()
                                }
                            },
                            colors = if (eraserTool) ButtonDefaults.buttonColors()
                            else ButtonDefaults.outlinedButtonColors()
                        ) { Text("🩹") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val (page, edit) = undoStack.lastOrNull() ?: return@Button
                                undoStack = undoStack.dropLast(1)
                                selectedId = null
                                selectedStrokeId = null
                                dragStamp = null
                                dragStroke = null
                                inkTail.last = null
                                if (sheetHash != null) {
                                    editScope.launch {
                                        stampRepo.restorePage(sheetHash, page, edit.stamps)
                                        strokeRepo.restorePage(sheetHash, page, edit.strokes)
                                    }
                                }
                            },
                            enabled = undoStack.isNotEmpty()
                        ) { Text("↶") }
                        Spacer(Modifier.width(8.dp))
                        if (drawingTool) {
                            Text("Grosor", style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = penWidth,
                                onValueChange = { penWidth = it },
                                valueRange = MIN_STROKE_WIDTH..MAX_STROKE_WIDTH,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text("${(penWidth * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (selectedStamp != null) {
                            val s = selectedStamp
                            var sliderUndoPushed by remember { mutableStateOf(false) }
                            Text("Tamaño", style = MaterialTheme.typography.bodyMedium)
                            Slider(
                                value = s.size.coerceIn(MIN_STAMP_SIZE, MAX_STAMP_SIZE),
                                onValueChange = { v ->
                                    val current = pageStamps.firstOrNull { it.id == selectedId }
                                    if (current != null && v != current.size) {
                                        if (!sliderUndoPushed) {
                                            pushUndo()
                                            sliderUndoPushed = true
                                        }
                                        editScope.launch { stampRepo.update(current.copy(size = v)) }
                                    }
                                },
                                onValueChangeFinished = { sliderUndoPushed = false },
                                valueRange = MIN_STAMP_SIZE..MAX_STAMP_SIZE,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            Text("${(s.size * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = {
                                val s = selectedStamp
                                val t = selectedStroke
                                if (s == null && t == null) return@Button
                                pushUndo()
                                selectedId = null
                                selectedStrokeId = null
                                inkTail.last = null
                                hiddenStampIds = hiddenStampIds + listOfNotNull(s?.id)
                                hiddenStrokeIds = hiddenStrokeIds + listOfNotNull(t?.id)
                                editScope.launch {
                                    s?.let { stampRepo.delete(it) }
                                    t?.let { strokeRepo.delete(it) }
                                }
                            },
                            enabled = selectedStamp != null || selectedStroke != null
                        ) { Text("🗑") }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = applyPrev,
                        enabled = branch.canPrev
                    ) { Text("◀") }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        branch.label,
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onBack) { Text("✕") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = { showDim = !showDim },
                        colors = if (showDim) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("◐") }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = applyNext,
                        enabled = branch.canNext
                    ) { Text("▶") }
                }
            }
        }
    }
}