package com.musigatto.fosscore.ui.viewer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ponytail: rendering ceiling on the longest side (px). Cuts memory ~10x for big scan pages;
// zoom past ~2.5x gets slightly soft. Upgrade path: multi-resolution cache.
private const val MAX_RENDER_DIM = 3200
private const val MAX_CACHED_PAGES = 4

private fun renderPage(r: PdfRenderer, idx: Int): Bitmap {
    val page = r.openPage(idx)
    try {
        val scale = min(1f, MAX_RENDER_DIM.toFloat() / max(page.width, page.height))
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply { setScale(scale, scale) }
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    } finally {
        page.close()
    }
}

private fun centroid(changes: List<PointerInputChange>): Offset =
    changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()

@Composable
fun PdfViewerScreen(pdfUri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var twoUp by rememberSaveable { mutableStateOf(isLandscape) }
    var halfEnabled by rememberSaveable { mutableStateOf(false) }
    var halfTurned by rememberSaveable { mutableStateOf(false) }
    var navVisible by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var bitmaps by remember { mutableStateOf(emptyMap<Int, Bitmap>()) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var renderGeneration by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val swipeThresholdPx = with(LocalDensity.current) { 80.dp.toPx() }

    // access-order LRU, fresh per document
    val cache = remember(pdfUri) {
        object : LinkedHashMap<Int, Bitmap>(MAX_CACHED_PAGES + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean =
                size > MAX_CACHED_PAGES
        }
    }

    val branch = PageFlow(currentPage, halfTurned, halfEnabled, twoUp, isLandscape, pageCount)

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
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    BackHandler(onBack = onBack)

    // ponytail: Android PdfRenderer (zero deps). Swap to MuPDF for annotations/reflow.
    LaunchedEffect(pdfUri) {
        try {
            val fd = context.contentResolver.openFileDescriptor(pdfUri, "r", null)
            if (fd == null) {
                error = "No se pudo abrir el archivo"
            } else {
                val r = PdfRenderer(fd)
                pageCount = r.pageCount
                renderer = r
            }
        } catch (e: Exception) {
            error = "PDF inválido o corrupto"
        }
    }

    LaunchedEffect(renderer, currentPage, branch.showTwoUp, branch.showHalf, halfTurned) {
        val r = renderer ?: return@LaunchedEffect
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        val gen = ++renderGeneration
        val needed = when {
            branch.showTwoUp -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            branch.showHalf && halfTurned -> listOf(currentPage, currentPage + 1).filter { it < pageCount }
            else -> listOf(currentPage)
        }
        try {
            val session = withContext(Dispatchers.Default) {
                val out = mutableMapOf<Int, Bitmap>()
                for (idx in needed) {
                    val cached = synchronized(cache) { cache[idx] }
                    out[idx] = cached ?: renderPage(r, idx).also { bmp ->
                        synchronized(cache) { cache[idx] = bmp }
                    }
                }
                out
            }
            // guard against an older, non-cancellable render landing after a newer one
            if (gen == renderGeneration) bitmaps = session
        } catch (e: Exception) {
            if (gen == renderGeneration) error = "No se pudo renderizar la página"
        }
    }

    DisposableEffect(renderer, cache) {
        val current = renderer
        onDispose {
            current?.close()
            synchronized(cache) {
                cache.values.forEach { it.recycle() }
                cache.clear()
            }
            bitmaps.values.forEach { it.recycle() }
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
            val zoomMod = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offsetX, translationY = offsetY
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.isConsumed) return@awaitEachGesture
                        var ids = setOf(down.id)
                        var prevCentroid = down.position
                        var baseScale = scale
                        var anchorDist = 0f
                        var totalPan = Offset.Zero
                        var isTransform = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (!isTransform) {
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
                                break
                            }

                            val nids = pressed.map { it.id }.toSet()
                            if (nids != ids) {
                                ids = nids
                                baseScale = scale
                                anchorDist = 0f
                                prevCentroid = centroid(pressed)
                            }

                            val c = centroid(pressed)
                            val delta = c - prevCentroid
                            totalPan += delta

                            if (pressed.size > 1) {
                                isTransform = true
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (anchorDist == 0f) anchorDist = d
                                else if (d > 0f) scale = (baseScale * d / anchorDist).coerceIn(1f, 5f)
                            }

                            if (scale > 1f && delta.getDistance() > 0f) {
                                isTransform = true
                                offsetX += delta.x
                                offsetY += delta.y
                            }
                            clampOffsets()
                            prevCentroid = c
                            event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                        }
                    }
                }

            when {
                branch.showTwoUp -> {
                    Row(modifier = zoomMod) {
                        bitmaps[currentPage]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                        bitmaps[currentPage + 1]?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
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
                    val combined = remember(bitmaps, currentPage, halfTurned) {
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
                        modifier = zoomMod
                    )
                }
                else -> {
                    bitmaps[currentPage]?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Page ${currentPage + 1} of $pageCount",
                            contentScale = ContentScale.Fit,
                            modifier = zoomMod
                        )
                    } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
                    colors = if (halfEnabled) ButtonDefaults.buttonColors()
                    else ButtonDefaults.outlinedButtonColors()
                ) { Text("½") }
                if (isLandscape) {
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = false },
                        colors = if (!twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("1") }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { twoUp = true },
                        colors = if (twoUp) ButtonDefaults.buttonColors()
                        else ButtonDefaults.outlinedButtonColors()
                    ) { Text("2") }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(surface)
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
                    onClick = applyNext,
                    enabled = branch.canNext
                ) { Text("▶") }
            }
        }
    }
}