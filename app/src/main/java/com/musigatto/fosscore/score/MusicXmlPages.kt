package com.musigatto.fosscore.score

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.PageGeometry
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.musicxml.ScoreEngine
import com.musigatto.fosscore.musicxml.ScoreInfo
import com.musigatto.fosscore.musicxml.ScoreSummary
import com.musigatto.fosscore.musicxml.SoundSpec
import com.musigatto.fosscore.pdf.MAX_RENDER_PIXELS
import com.musigatto.fosscore.pdf.PageBox
import com.musigatto.fosscore.ui.viewer.ScoreLayout
import java.io.File
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val LOG_TAG = "FOSScore-MusicXML"

// bitmaps already drawn, keyed by (page, width in px): the same budget as MuPdfDoc
private const val MAX_CACHED_BITMAPS = 4

// bumped when what is kept in the cache is drawn or read differently, so old files are not used
private const val CACHE_VERSION = 1

// the layouts whose pages are kept: portrait, landscape, two-up
private const val KEPT_LAYOUTS = 3

/**
 * A MusicXML score as pages for the viewer. Unlike a PDF, the pages do not exist before the score
 * is laid out for a screen: [layout] breaks the music into pages of the viewer's size, and again
 * whenever that size or the music size changes, so [pageCount] changes with it.
 *
 * The music is laid out and drawn by the musicxml module (Verovio, then MuPDF for the bitmap),
 * which takes seconds on a slow tablet. So each score keeps, in the app's cache, what it showed
 * last: its summary, and the number and pictures of its pages for its last layouts (portrait,
 * landscape...). Opened again the same way, it shows those pages at once while the music is laid
 * out again behind them.
 */
class MusicXmlPages private constructor(
    private val engine: ScoreEngine,
    /** The score's parts, in the file's order. */
    val parts: List<Part>,
    /** Title, tempo, key... as the file gives them. */
    val info: ScoreInfo,
    private val cacheDir: File,
    // the pages made ahead by [prepare], which no viewer shows
    private val ahead: Boolean = false,
) : PageSource {

    @Volatile
    override var pageCount: Int = 0
        private set

    // every page of a layout has the viewport's shape
    @Volatile
    private var box: PageBox? = null
    @Volatile
    private var laidOut: LayoutSpec? = null
    // the name of the current layout in the cache
    @Volatile
    private var kept: String? = null
    // where the notes are on each page drawn since the last layout, for the playback cursor
    private val geometries = ConcurrentHashMap<Int, PageGeometry>()
    private val bitmaps = object : LinkedHashMap<Long, Bitmap>(MAX_CACHED_BITMAPS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) =
            size > MAX_CACHED_BITMAPS
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pagesDir = File(cacheDir, "pages")

    /**
     * Breaks the music into pages for [spec]. Returns false when nothing changed. Call it under
     * the viewer's render mutex, like [render]. When the cache has this layout, it returns at once
     * and the music is laid out behind the kept pages.
     */
    suspend fun layout(spec: LayoutSpec): Boolean {
        if (spec == laidOut) return false
        val name = nameOf(spec)
        // the old bitmaps show the old page breaks; the viewer may still be showing one, so they
        // are dropped, not recycled
        bitmaps.clear()
        geometries.clear()
        box = PageBox(0f, 0f, spec.width.toFloat(), spec.height.toFloat())
        laidOut = spec
        kept = name
        val count = File(pagesDir, "$name.count").takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }
        if (count != null && count > 0) {
            File(pagesDir, "$name.count").setLastModified(System.currentTimeMillis())
            pageCount = count
            // started here, so it is queued before any later call to the engine
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { viewerWork { engine.layout(spec) } }
                    .onSuccess { if (laidOut == spec) pageCount = it }
                    .onFailure { Log.e(LOG_TAG, "the score could not be laid out: ${it.message}", it) }
            }
            return true
        }
        pageCount = viewerWork { engine.layout(spec) }
        scope.launch {
            runCatching {
                pagesDir.mkdirs()
                File(pagesDir, "$name.count").writeText(pageCount.toString())
                // only the last layouts are kept
                val old = pagesDir.listFiles { f -> f.name.endsWith(".count") }.orEmpty()
                    .sortedByDescending { it.lastModified() }.drop(KEPT_LAYOUTS).map { it.name.removeSuffix(".count") }
                pagesDir.listFiles()?.filter { f -> old.any { f.name.startsWith(it) } }?.forEach { it.delete() }
            }
        }
        return true
    }

    override fun pageSize(index: Int): PageBox? = box?.takeIf { index in 0 until pageCount }

    override fun ensurePageSize(index: Int): PageBox? = pageSize(index)

    override fun render(index: Int, slotW: Int, slotH: Int): Bitmap? {
        if (index !in 0 until pageCount || slotW <= 0 || slotH <= 0) return null
        // same memory cap as a PDF page: past it a zoomed page would eat the RAM
        val k = sqrt(MAX_RENDER_PIXELS.toDouble() / (slotW.toLong() * slotH)).coerceAtMost(1.0)
        val w = (slotW * k).toInt().coerceAtLeast(1)
        val h = (slotH * k).toInt().coerceAtLeast(1)
        val key = index.toLong() * 1_000_000L + w
        bitmaps[key]?.let { return it }
        val name = kept
        val file = name?.let { File(pagesDir, "$it-$index-$w.png") }
        file?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }?.let { bitmap ->
            bitmaps[key] = bitmap
            // the notes' positions, for the cursor, once the music is laid out again
            scope.launch {
                runCatching { PageGeometry.parse(engine.pageSvg(index + 1)) }
                    .onSuccess { if (kept == name) geometries[index] = it }
            }
            return bitmap
        }
        return try {
            // the engine runs Verovio on its own thread; the viewer already calls us off the main one
            val page = runBlocking { viewerWork { engine.renderPage(index + 1, w, h) } }
            geometries[index] = page.geometry
            bitmaps[key] = page.bitmap
            file?.let { keep(it, page.bitmap) }
            page.bitmap
        } catch (e: Exception) {
            Log.e(LOG_TAG, "page $index could not be drawn: ${e.message}", e)
            null
        }
    }

    /** Where the notes of page [index] are, once the page has been drawn. Positions in SVG units. */
    fun geometry(index: Int): PageGeometry? = geometries[index]

    /** Page (0-based) of the note [id] in the current layout, or -1. */
    suspend fun pageOf(id: String): Int = engine.pageOf(id) - 1

    /** The MIDI and the timing maps for the current layout and [sound]. */
    suspend fun playback(sound: SoundSpec): Playback = engine.playback(sound)

    /** Makes the music ahead of the first ▶ (seconds on a slow tablet), so playing starts at once. */
    suspend fun preparePlayback() = engine.preparePlayback()

    override fun close() {
        scope.cancel()
        bitmaps.clear()
        geometries.clear()
        engine.close()
    }

    // the viewer works on a score: the work ahead leaves it the tablet meanwhile
    private suspend fun <T> viewerWork(block: suspend () -> T): T =
        if (ahead) block() else ScorePrecompute.whileViewerWorks(block)

    // waits until what is being kept in the cache is written, then stops
    private suspend fun settle() {
        scope.coroutineContext[Job]?.children?.forEach { it.join() }
        scope.cancel()
    }

    // a page drawn, kept for the next opening; written aside first, so a half-written file is never read
    private fun keep(file: File, bitmap: Bitmap) {
        scope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.path + ".part")
                part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                part.renameTo(file)
            }
        }
    }

    companion object {
        /**
         * Reads the score in [file]. Nothing is laid out yet: call [layout] once the viewport is
         * known. When the score was opened before, this returns at once: the file is read behind.
         */
        suspend fun open(context: Context, file: File): MusicXmlPages {
            val engine = ScoreEngine(context, RASTER)
            val dir = cacheOf(context, file)
            val summaryFile = File(dir, SUMMARY)
            val summary = try {
                val bytes = file.readBytes()
                summaryFile.takeIf { it.isFile }?.let { ScoreSummary.fromJson(it.readText()) }
                    ?.also { engine.openLater(bytes, dir) }
                    ?: engine.open(bytes, dir).also { keepSummary(dir, it) }
            } catch (e: Exception) {
                engine.close()
                throw e
            }
            return MusicXmlPages(engine, summary.parts, summary.info, dir)
        }

        /**
         * Makes and keeps what the first opening of the score in [file] needs (seconds each on a
         * slow tablet): its summary, the text to lay out for [layout], its pages laid out and the
         * first ones drawn for each screen it may be shown on ([firstPage] is where it opens),
         * and its music. It calls [pause] between its steps (a second or so each on a slow
         * tablet), which may wait, to leave the tablet to the user.
         */
        suspend fun prepare(
            context: Context,
            file: File,
            layout: ScoreLayout = ScoreLayout(),
            firstPage: Int = 0,
            pause: suspend () -> Unit = {},
        ) {
            val dir = cacheOf(context, file)
            ScoreEngine(context, RASTER, background = true).use { engine ->
                val summary = engine.open(file.readBytes(), dir)
                keepSummary(dir, summary)
                pause()
                engine.prepareLayout(layout.semitones, layout.layoutParts(summary.parts), layout.lyrics, layout.keepHiddenLyrics)
                val pages = MusicXmlPages(engine, summary.parts, summary.info, dir, ahead = true)
                try {
                    for (screen in screens(context)) {
                        val spec = screen.spec(layout, summary.parts)
                        if (File(dir, "pages/${nameOf(spec)}.count").isFile) continue
                        pause()
                        pages.layout(spec)
                        val (slotW, slotH) = screen.slot()
                        // two-up shows a page and the next one, and waits for both
                        val first = if (screen.twoUp) listOf(firstPage, firstPage + 1, 0, 1) else listOf(firstPage, 0)
                        for (page in first.distinct()) {
                            pause()
                            pages.render(page, slotW, slotH)
                        }
                    }
                } finally {
                    pages.settle()
                }
                pause()
                engine.preparePlayback()
            }
        }

        /**
         * The first of the MusicXML scores in [files] whose cache misses something for the
         * screens seen so far, with the layout [layoutOf] gives, or null when none does.
         */
        fun unprepared(context: Context, files: List<File>, layoutOf: (Context, File) -> ScoreLayout): File? =
            files.firstOrNull { SheetFormat.of(it.path).isMusicXml && !isPrepared(context, it, layoutOf(context, it)) }

        /** Keeps [screen] among the screens the scores are prepared for: the last ones seen. */
        fun rememberScreen(context: Context, screen: MusicXmlScreen) {
            val before = screens(context)
            val now = (listOf(screen) + before.filter { it != screen }).take(KEPT_LAYOUTS)
            if (now == before) return
            ScorePrecompute.changed()
            runCatching {
                val file = File(context.cacheDir, SCREENS)
                val part = File(file.path + ".part")
                part.writeText(JSONArray(now.map { it.toJson() }).toString())
                part.renameTo(file)
            }
        }

        private fun screens(context: Context): List<MusicXmlScreen> = runCatching {
            val a = JSONArray(File(context.cacheDir, SCREENS).readText())
            List(a.length()) { MusicXmlScreen.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        private fun isPrepared(context: Context, file: File, layout: ScoreLayout): Boolean {
            val dir = cacheOf(context, file)
            val summary = File(dir, SUMMARY).takeIf { it.isFile }?.let { ScoreSummary.fromJson(it.readText()) } ?: return false
            return screens(context).all { File(dir, "pages/${nameOf(it.spec(layout, summary.parts))}.count").isFile }
        }

        // the name of a layout's pages in a score's cache
        private fun nameOf(spec: LayoutSpec) = "${spec.toString().hashCode().toUInt().toString(16)}.$CACHE_VERSION"

        private const val SCREENS = "musicxml-screens.$CACHE_VERSION.json"

        // MuPDF draws the pages of every engine, the viewer's and the one working ahead, one at a time
        private val RASTER = Any()

        private const val SUMMARY = "summary.$CACHE_VERSION.json"

        private fun cacheOf(context: Context, file: File) = File(context.cacheDir, "scores/${file.name}")

        // written aside first, then renamed: an opening never reads half a file
        private fun keepSummary(dir: File, summary: ScoreSummary) {
            runCatching {
                dir.mkdirs()
                val part = File(dir, "$SUMMARY.part")
                part.writeText(summary.toJson())
                part.renameTo(File(dir, SUMMARY))
            }
        }
    }
}
