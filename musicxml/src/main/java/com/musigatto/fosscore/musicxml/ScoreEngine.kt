package com.musigatto.fosscore.musicxml

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** A score could not be read or laid out. [message] includes Verovio's log when it has one. */
class MusicXmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What an opened score offers: its parts and the details read from the file. */
data class ScoreSummary(val parts: List<Part>, val info: ScoreInfo)

/** One rendered page: the bitmap and where its notes are, for a playback cursor. */
class RenderedPage(val bitmap: Bitmap, val geometry: PageGeometry)

/**
 * Lays out, draws and plays one MusicXML score.
 *
 * Every Verovio call runs on one dedicated thread (a toolkit is not thread-safe); all functions
 * are safe to call from any coroutine. A second toolkit holds the parts heard when they differ
 * from the parts shown. Call [close] when the score is closed: it frees the native memory.
 *
 * Pages are drawn by MuPDF. If the app also uses MuPDF from other threads and needs its calls
 * serialized, pass the same lock as [rasterLock].
 */
class ScoreEngine(context: Context, private val rasterLock: Any = Any()) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fosscore-verovio") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val appContext = context.applicationContext

    // Set on the engine thread only.
    private var resourcePath: String? = null
    private var glyphs: SmuflGlyphs? = null
    private var display: Verovio? = null
    private var audio: Verovio? = null
    private var score: MusicXmlScore? = null
    private var loadedFor: Any? = null
    private var audioLoadedFor: Any? = null
    private var laidOut: LayoutSpec? = null

    /** Verovio's version, for an "about" screen. */
    suspend fun version(): String = run { toolkit().version }

    /** Reads plain MusicXML or .mxl. Replaces the score opened before, if any. */
    suspend fun open(bytes: ByteArray): ScoreSummary = run {
        val parsed = try {
            MusicXmlScore.parse(bytes)
        } catch (e: Exception) {
            throw MusicXmlException("not a readable MusicXML file", e)
        }
        score = parsed
        loadedFor = null
        audioLoadedFor = null
        laidOut = null
        ScoreSummary(parsed.parts, parsed.info)
    }

    /**
     * Lays out the score for [spec] and returns the number of pages.
     *
     * Transposition, parts and lyrics are applied while loading: changing them reloads the score
     * (about a second on a large score on a slow tablet). Changing only the viewport or the scale
     * keeps the measure widths Verovio cached and only breaks lines and pages again.
     */
    suspend fun layout(spec: LayoutSpec): Int = run {
        val doc = score ?: throw MusicXmlException("no score open")
        val t = toolkit()
        val wanted = Triple(spec.semitones, spec.parts, spec.lyrics)
        val options = VerovioOptions.layout(spec)
        if (loadedFor != wanted) {
            loadedFor = null
            val xml = doc.prepare(ScoreFilter(spec.parts, spec.lyrics))
            val above = doc.lyricsAbove(spec.lyrics)
            val credits = MeiFixes.forScreen(doc.creditSizes)
            if (MeiFixes.needed(above, credits)) {
                // Load once only to get the MEI (one endless system is the cheapest layout), fix
                // what the MusicXML import dropped, then load the MEI for real. The MEI is
                // already transposed, so it must not be transposed again.
                t.setOptions(VerovioOptions.unpaged(spec.semitones).toString())
                load(t, xml)
                val mei = MeiFixes.apply(t.mei(), above, credits)
                t.setOptions(options.put("transpose", "").toString())
                load(t, mei)
            } else {
                t.setOptions(options.toString())
                load(t, xml)
            }
            loadedFor = wanted
        } else if (laidOut != spec) {
            t.setOptions(options.toString())
            t.redoLayout("""{"resetCache": false}""")
        }
        laidOut = spec
        t.pageCount
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
        RenderedPage(bitmap, PageGeometry.parse(svg))
    }

    /** The page (1-based) that shows the element [id] (a note id from [Playback.onsets]), or 0. */
    suspend fun pageOf(id: String): Int = run { toolkit().pageWithElement(id) }

    /** MIDI and timing maps for the current layout and [sound]. */
    suspend fun playback(sound: SoundSpec): Playback = run {
        val shown = toolkit()
        val spec = laidOut ?: throw MusicXmlException("not laid out")
        val tempo = VerovioOptions.tempo(sound.tempo).toString()
        shown.setOptions(tempo)
        val displayMap = shown.renderTimemap()
        val heard = sound.audible ?: spec.parts
        val separate = heard != spec.parts || sound.programs.isNotEmpty()
        val player = if (!separate) shown else {
            val a = audio ?: Verovio(resources()).also { audio = it }
            val wanted = Triple(spec.semitones, heard, sound.programs)
            if (audioLoadedFor != wanted) {
                audioLoadedFor = null
                // This copy is never drawn: make its layout as cheap as possible.
                a.setOptions(VerovioOptions.unpaged(spec.semitones).toString())
                load(a, score!!.prepare(ScoreFilter(heard, programs = sound.programs)))
                audioLoadedFor = wanted
            }
            a.setOptions(tempo)
            a
        }
        val audioMap = if (player === shown) displayMap else player.renderTimemap()
        val midi = Base64.decode(player.renderMidi(), Base64.DEFAULT)
        Playback.from(midi, displayMap, audioMap)
    }

    /**
     * Frees the toolkits and stops the engine thread. Pending calls finish first; calls made
     * after this one fail.
     */
    override fun close() {
        executor.execute {
            display?.close()
            audio?.close()
            display = null
            audio = null
            score = null
        }
        executor.shutdown()
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

    private fun resources(): String = resourcePath ?: Verovio.prepareResources(appContext).also { resourcePath = it }

    private fun glyphs(): SmuflGlyphs = glyphs ?: SmuflGlyphs(File(resources())).also { glyphs = it }

    private fun toolkit(): Verovio = display ?: Verovio(resources()).also { display = it }

    private suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }
}
