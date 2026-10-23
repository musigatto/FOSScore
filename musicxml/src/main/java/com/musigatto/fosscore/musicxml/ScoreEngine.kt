package com.musigatto.fosscore.musicxml

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A score could not be read or laid out. [message] includes Verovio's log when it has one. */
class MusicXmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What an opened score offers: its parts and the details read from the file. */
data class ScoreSummary(val parts: List<Part>, val info: ScoreInfo) {

    /** As JSON, to keep with the score: it opens again without waiting for the file to be read. */
    fun toJson(): String = JSONObject()
        .put("parts", JSONArray(parts.map {
            JSONObject().put("id", it.id).put("name", it.name).put("visible", it.visibleInFile)
                .put("program", it.program ?: JSONObject.NULL).put("drums", it.drums)
        }))
        .put("title", info.title ?: JSONObject.NULL)
        .put("subtitle", info.subtitle ?: JSONObject.NULL)
        .put("composer", info.composer ?: JSONObject.NULL)
        .put("lyricist", info.lyricist ?: JSONObject.NULL)
        .put("arranger", info.arranger ?: JSONObject.NULL)
        .put("fifths", info.key?.fifths ?: JSONObject.NULL)
        .put("mode", info.key?.mode ?: JSONObject.NULL)
        .put("time", info.time ?: JSONObject.NULL)
        .put("tempo", info.tempo ?: JSONObject.NULL)
        .put("measures", info.measures)
        .toString()

    companion object {
        /** The summary kept by [toJson], or null when it cannot be read. */
        fun fromJson(json: String): ScoreSummary? = runCatching {
            val o = JSONObject(json)
            fun text(name: String): String? = if (o.isNull(name)) null else o.getString(name)
            val parts = o.getJSONArray("parts").let { a ->
                List(a.length()) { i ->
                    val p = a.getJSONObject(i)
                    Part(
                        p.getString("id"), p.getString("name"), p.getBoolean("visible"),
                        if (p.isNull("program")) null else p.getInt("program"), p.getBoolean("drums")
                    )
                }
            }
            val key = if (o.isNull("fifths")) null else KeySignature(o.getInt("fifths"), text("mode"))
            val tempo = if (o.isNull("tempo")) null else o.getDouble("tempo")
            ScoreSummary(
                parts,
                ScoreInfo(
                    text("title"), text("subtitle"), text("composer"), text("lyricist"), text("arranger"),
                    key, text("time"), tempo, o.getInt("measures")
                )
            )
        }.getOrNull()
    }
}

/** One rendered page: the bitmap and where its notes are, for a playback cursor. */
class RenderedPage(val bitmap: Bitmap, val geometry: PageGeometry)

/**
 * Lays out, draws and plays one MusicXML score.
 *
 * A toolkit is not thread-safe, but Verovio keeps its state per toolkit and per thread: the pages
 * are laid out and drawn by one toolkit on one thread, while a second thread reads the file and
 * makes the music, once, as soon as the score is open. All functions are safe to call from any
 * coroutine. Call [close] when the score is closed: it frees the native memory.
 *
 * Given a cache directory for the score, what is slow to make is kept there (the music, and the
 * text Verovio lays out for each transposition, parts and lyrics), so the next openings skip it.
 *
 * Pages are drawn by MuPDF. If the app also uses MuPDF from other threads and needs its calls
 * serialized, pass the same lock as [rasterLock].
 */
class ScoreEngine(
    context: Context,
    private val rasterLock: Any = Any(),
    /** Work done ahead: its threads give way to everything else (the screen, the music). */
    background: Boolean = false,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor(threads("fosscore-verovio", background))
    private val dispatcher = executor.asCoroutineDispatcher()
    private val soundExecutor = Executors.newSingleThreadExecutor(threads("fosscore-verovio-sound", background))
    private val soundDispatcher = soundExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val appContext = context.applicationContext
    // calls queued or running on the engine thread: the cursor's onsets wait until it is free
    private val busy = AtomicInteger()
    // a page was drawn since the score was opened: the music, made meanwhile, may start
    @Volatile private var drawn = false

    // Set on the engine thread only.
    private var glyphs: SmuflGlyphs? = null
    private var display: Verovio? = null
    private var loadedFor: Any? = null
    private var laidOut: LayoutSpec? = null
    // when notes start in the score shown, and which: the cursor and the page turns
    private var onsets: Pair<List<Onset>, List<Double>>? = null
    private var onsetsFor: Any? = null

    // Set by [openLater].
    @Volatile private var cacheDir: File? = null
    // the score read from its file, on the sound thread
    @Volatile private var parsed: Deferred<MusicXmlScore>? = null
    // the MIDI of every part at the written tempo, not transposed: the tempo, the transposition
    // and the parts heard are then rewritten in it (MidiEdit), without Verovio
    @Volatile private var music: Deferred<ByteArray>? = null

    private var resourcePath: String? = null

    /** Verovio's version, for an "about" screen. */
    suspend fun version(): String = run { toolkit().version }

    /**
     * Reads plain MusicXML or .mxl and returns what it offers. Replaces the score opened before,
     * if any. [cacheDir], when given, is this score's own: see the class documentation.
     */
    suspend fun open(bytes: ByteArray, cacheDir: File? = null, soundFirst: Boolean = false): ScoreSummary {
        openLater(bytes, cacheDir, soundFirst)
        val doc = score()
        return ScoreSummary(doc.parts, doc.info)
    }

    /**
     * Like [open], but returns at once, for a score whose [ScoreSummary] was kept: the file is read
     * on the sound thread while the pages are laid out from the cache. Calls made after this one
     * see the new score; a file that cannot be read makes them fail.
     *
     * Making the music takes both cores of a slow tablet for seconds: unless [soundFirst], it
     * waits until the first page is drawn.
     */
    fun openLater(bytes: ByteArray, cacheDir: File? = null, soundFirst: Boolean = false) {
        parsed?.cancel()
        music?.cancel()
        this.cacheDir = cacheDir
        drawn = soundFirst
        busy.incrementAndGet()
        executor.execute {
            loadedFor = null
            laidOut = null
            onsets = null
            busy.decrementAndGet()
        }
        val doc = scope.async(soundDispatcher) {
            try {
                MusicXmlScore.parse(bytes)
            } catch (e: Exception) {
                throw MusicXmlException("not a readable MusicXML file", e)
            }
        }
        parsed = doc
        music = scope.async(soundDispatcher) {
            val cache = cacheDir?.let { File(it, "music.$CACHE_VERSION.mid") }
            if (cache?.isFile != true) {
                var waited = 0L
                while ((!drawn || busy.get() > 0) && waited < MAX_SOUND_WAIT_MS) {
                    delay(IDLE_POLL_MS)
                    waited += IDLE_POLL_MS
                }
            }
            musicOf(doc.await(), cache)
        }
    }

    /**
     * Lays out the score for [spec] and returns the number of pages.
     *
     * Transposition, parts and lyrics are applied while loading: changing them reloads the score
     * (about a second on a large score on a slow tablet). Changing only the viewport or the scale
     * lays the loaded score out again, measuring the measures anew: widths kept from another
     * layout break the lines differently from a score opened at this size (going 90 % → 80 % →
     * 90 %, or turning the screen and back, put one more measure on a line).
     */
    suspend fun layout(spec: LayoutSpec): Int = run {
        val t = toolkit()
        val wanted = listOf(spec.semitones, spec.parts, spec.lyrics, spec.hiddenLyrics)
        val options = VerovioOptions.layout(spec)
        if (loadedFor != wanted) {
            loadedFor = null
            val (text, transposed) = textToLayOut(t, spec)
            // the MEI is already transposed: it must not be transposed again
            if (transposed) options.put("transpose", "")
            t.setOptions(options.toString())
            load(t, text)
            loadedFor = wanted
        } else if (laidOut != spec) {
            t.setOptions(options.toString())
            t.redoLayout("""{"resetCache": true}""")
        }
        laidOut = spec
        t.pageCount
    }

    /**
     * Makes and keeps in the cache what Verovio lays out for this transposition, these [parts]
     * and lyrics, without laying it out: the first opening then skips it. Needs a cache directory.
     */
    suspend fun prepareLayout(semitones: Int, parts: Set<String>, lyrics: LyricsPlacement, hiddenLyrics: Boolean) = run {
        val spec = LayoutSpec(1, 1, semitones = semitones, parts = parts, lyrics = lyrics, hiddenLyrics = hiddenLyrics)
        textToLayOut(toolkit(), spec)
        // the toolkit may hold another score now
        loadedFor = null
    }

    /** Page [page] (1-based) of the current layout as Verovio's SVG, unchanged. */
    suspend fun pageSvg(page: Int): String = run { svgOf(page) }

    /**
     * Draws page [page] (1-based) of the current layout to fit [maxWidth] × [maxHeight] pixels,
     * on a white background.
     */
    suspend fun renderPage(page: Int, maxWidth: Int, maxHeight: Int): RenderedPage = run {
        val svg = svgOf(page)
        val ready = glyphs().inline(SvgForMuPdf.fix(svg))
        val bitmap = synchronized(rasterLock) { SvgRasterizer.render(ready, maxWidth, maxHeight) }
        drawn = true
        RenderedPage(bitmap, PageGeometry.parse(svg))
    }

    /** The page (1-based) that shows the element [id] (a note id from [Playback.onsets]), or 0. */
    suspend fun pageOf(id: String): Int = run { toolkit().pageWithElement(id) }

    /**
     * MIDI and timing maps for the current layout and [sound]. Once the music is made (see
     * [open]) and the onsets known (see [preparePlayback]), a new tempo or new parts heard take
     * milliseconds.
     */
    suspend fun playback(sound: SoundSpec): Playback {
        val made = music ?: throw MusicXmlException("no score open")
        val (notes, spec) = run { onsets() to laidOut!! }
        val doc = score()
        val midi = made.await()
        val heard = (sound.audible ?: spec.parts).ifEmpty { doc.parts.map { it.id }.toSet() }
        // staff n plays on MIDI track n
        val silent = mutableSetOf<Int>()
        val programs = mutableMapOf<Int, Int>()
        var staff = 0
        for ((id, staves) in doc.staffCounts) {
            repeat(staves) {
                staff++
                if (id !in heard) silent += staff
                sound.programs[id]?.let { programs[staff] = it }
            }
        }
        val tempo = sound.tempo.coerceIn(VerovioOptions.MIN_TEMPO, VerovioOptions.MAX_TEMPO)
        return withContext(Dispatchers.Default) {
            Playback.of(MidiEdit.edit(midi, tempo, silent, spec.semitones, programs), notes.first, notes.second)
        }
    }

    /**
     * Gets playback ready ahead of the first ▶: waits for the music, then finds the onsets of the
     * score shown once the pages are drawn (they take the engine thread for a second or so on a
     * slow tablet). A failure is left for [playback] to report.
     */
    suspend fun preparePlayback() {
        runCatching { music?.await() }
        while (busy.get() > 0) delay(IDLE_POLL_MS)
        run { runCatching { onsets() } }
    }

    /**
     * Frees the toolkits and stops the engine threads. Pending calls finish first; calls made
     * after this one fail.
     */
    override fun close() {
        scope.cancel()
        executor.execute {
            display?.close()
            display = null
        }
        executor.shutdown()
        soundExecutor.shutdown()
    }

    private suspend fun score(): MusicXmlScore = (parsed ?: throw MusicXmlException("no score open")).await()

    // On the engine thread: what Verovio lays out for [spec], and whether it is already
    // transposed. Kept in the cache: making it reads the file, prepares the score, and, when the
    // MEI needs fixing, loads it once more (seconds on a slow tablet).
    private fun textToLayOut(t: Verovio, spec: LayoutSpec): Pair<String, Boolean> {
        val name = "layout-${spec.semitones}-${spec.parts.sorted().joinToString(",").hashCode()}-" +
            "${spec.lyrics}-${spec.hiddenLyrics}.$CACHE_VERSION"
        val mei = cacheDir?.let { File(it, "$name.mei") }
        val xml = cacheDir?.let { File(it, "$name.xml") }
        mei?.takeIf { it.isFile }?.let { file -> runCatching { return file.readText() to true } }
        xml?.takeIf { it.isFile }?.let { file -> runCatching { return file.readText() to false } }
        val doc = runBlocking { score() }
        val prepared = synchronized(doc) {
            doc.prepare(ScoreFilter(spec.parts, spec.lyrics, hiddenLyrics = spec.hiddenLyrics))
        }
        val above = doc.lyricsAbove(spec.lyrics)
        val credits = MeiFixes.forScreen(doc.creditSizes)
        val made = if (MeiFixes.needed(above, credits)) {
            // Load once only to get the MEI (one endless system is the cheapest layout), and fix
            // what the MusicXML import dropped.
            t.setOptions(VerovioOptions.unpaged(spec.semitones).toString())
            load(t, prepared)
            MeiFixes.apply(t.mei(), above, credits) to true
        } else {
            prepared to false
        }
        (if (made.second) mei else xml)?.let { keep(it, made.first.toByteArray()) }
        return made
    }

    // On the sound thread: the MIDI of every part at the written tempo, from [cache] when it was
    // made before, otherwise from a clean copy of the score, laid out as cheaply as possible.
    private fun musicOf(doc: MusicXmlScore, cache: File?): ByteArray {
        cache?.takeIf { it.isFile }?.let { file -> runCatching { return file.readBytes() } }
        val t = Verovio(resources())
        try {
            t.setOptions(VerovioOptions.unpaged(0).toString())
            load(t, synchronized(doc) { doc.prepare() })
            val midi = Base64.decode(t.renderMidi(), Base64.DEFAULT)
            cache?.let { keep(it, midi) }
            return midi
        } finally {
            t.close()
        }
    }

    // On the engine thread: the onsets and the measures of the score shown.
    private fun onsets(): Pair<List<Onset>, List<Double>> {
        onsets?.takeIf { onsetsFor == loadedFor }?.let { return it }
        if (laidOut == null) throw MusicXmlException("not laid out")
        val timemap = toolkit().renderTimemap()
        return (Playback.onsetsOf(timemap) to Playback.barsOf(timemap)).also {
            onsets = it
            onsetsFor = loadedFor
        }
    }

    // kept in the cache, written aside first then renamed: a reader never gets half a file, even
    // when two engines make the same file (an import being prepared while the score is opened)
    private fun keep(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".${Thread.currentThread().id}.part")
            part.writeBytes(bytes)
            part.renameTo(file)
        }
    }

    private fun load(t: Verovio, data: String) {
        if (!t.load(data)) throw MusicXmlException("Verovio could not load the score: ${t.log.trim()}")
    }

    private fun svgOf(page: Int): String {
        val t = toolkit()
        if (laidOut == null) throw MusicXmlException("not laid out")
        if (page !in 1..t.pageCount) throw IndexOutOfBoundsException("page $page of ${t.pageCount}")
        return t.renderSvg(page)
    }

    // both threads need it
    @Synchronized
    private fun resources(): String = resourcePath ?: Verovio.prepareResources(appContext).also { resourcePath = it }

    private fun glyphs(): SmuflGlyphs = glyphs ?: SmuflGlyphs(File(resources())).also { glyphs = it }

    private fun toolkit(): Verovio = display ?: Verovio(resources()).also { display = it }

    private suspend fun <T> run(block: () -> T): T {
        busy.incrementAndGet()
        try {
            return withContext(dispatcher) { block() }
        } finally {
            busy.decrementAndGet()
        }
    }

    private companion object {
        fun threads(name: String, background: Boolean) = ThreadFactory { r ->
            Thread({
                if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, name)
        }

        // how often [preparePlayback] looks whether the pages are drawn
        const val IDLE_POLL_MS = 300L

        // the music waits for the first page no longer than this
        const val MAX_SOUND_WAIT_MS = 15_000L

        // bumped when what is kept in the cache is made differently, so the old files are not used
        const val CACHE_VERSION = 1
    }
}
