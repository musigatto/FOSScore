package com.musigatto.fosscore.musicxml

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The whole chain on a device: Verovio (native), the MEI round trip and MuPDF. */
@RunWith(AndroidJUnit4::class)
class ScoreEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: ScoreEngine

    private fun demo(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("au-clair-de-la-lune.musicxml").use { it.readBytes() }

    @Before
    fun setUp() {
        engine = ScoreEngine(context)
    }

    @After
    fun tearDown() {
        engine.close()
    }

    @Test
    fun opensLaysOutAndDrawsAPage() = runBlocking {
        val summary = engine.open(demo())
        assertEquals("Au clair de la lune", summary.info.title)
        assertEquals(listOf("P1", "P2"), summary.parts.map { it.id })

        val pages = engine.layout(LayoutSpec(width = 800, height = 1200))
        assertTrue(pages >= 1)
        val page = engine.renderPage(1, 800, 1200)
        assertTrue(page.bitmap.width in 1..800 && page.bitmap.height in 1..1200)
        // Mostly white paper with black music on it.
        assertTrue(darkShare(page.bitmap) in 0.005..0.5)
        assertTrue(page.geometry.noteIds.isNotEmpty())
        save(page.bitmap, "page1.png")
    }

    @Test
    fun theMeiRoundTripKeepsTheBigTitle() = runBlocking {
        engine.open(demo())
        engine.layout(LayoutSpec(width = 800, height = 1200))
        // The 22 pt title is put back in the MEI, brought down to the screen's cap: 150 % of
        // the composer's 10 pt (MeiFixes.forScreen), the font sizes being rounded to the pixel.
        val svg = engine.pageSvg(1)
        val title = sizeBefore(svg, "Au clair de la lune")
        val composer = sizeBefore(svg, "Traditionnel")
        assertEquals("title $title, composer $composer", MeiFixes.MAX_HEADER_PERCENT / 100.0, title.toDouble() / composer, 0.02)
    }

    @Test
    fun relayoutTranspositionAndPartsChangeTheResult() = runBlocking {
        engine.open(demo())
        val base = LayoutSpec(width = 800, height = 1200)
        engine.layout(base)
        val original = engine.pageSvg(1)

        // Bigger music on a smaller page: more pages, same score loaded.
        val big = engine.layout(base.copy(width = 400, height = 600, scale = 150))
        assertTrue(big > 1)

        engine.layout(base.copy(semitones = 2))
        assertNotEquals(withoutIds(original), withoutIds(engine.pageSvg(1)))

        assertTrue(original.contains("class=\"verse\""))
        engine.layout(base.copy(parts = setOf("P2")))
        assertTrue(!engine.pageSvg(1).contains("class=\"verse\""))

        engine.layout(base)
        assertEquals(withoutIds(original), withoutIds(engine.pageSvg(1)))
    }

    @Test
    fun playbackGivesMidiAndACursor() = runBlocking {
        engine.open(demo())
        engine.layout(LayoutSpec(width = 800, height = 1200, parts = setOf("P1")))
        val playback = engine.playback(SoundSpec())
        assertEquals("MThd", String(playback.midi, 0, 4, Charsets.US_ASCII))
        assertTrue(playback.onsets.isNotEmpty())
        // the demo has 16 measures, the first starting at the start
        assertEquals(16, playback.bars.size)
        assertEquals(0.0, playback.bars.first(), 0.0)
        assertEquals(1, engine.pageOf(playback.onsets.first().notes.first()))

        // Hearing both parts while only the voice is shown: the same MIDI, the piano no longer
        // muted, and the same score positions.
        val both = engine.playback(SoundSpec(audible = setOf("P1", "P2"), tempo = 0.5))
        assertEquals(playback.onsets.size, both.onsets.size)
        assertEquals(playback.midi.size, both.midi.size)
        assertFalse(playback.midi.contentEquals(both.midi))
        // Half tempo: after the same time, the music is half as far.
        assertEquals(playback.quartersAt(2000) / 2, both.quartersAt(2000), 0.01)
    }

    @Test
    fun aSizeGivesTheSameLinesWhateverSizeCameBefore() = runBlocking {
        engine.open(demo())
        // the sizes of a tablet in portrait (defaultScale × the note size, 80 to 120 %)
        val sizes = listOf(30, 34, 38, 42, 46)
        val fresh = sizes.associateWith { scale ->
            ScoreEngine(context).use { e ->
                e.open(demo())
                measuresPerPage(e, e.layout(LayoutSpec(width = 800, height = 1150, scale = scale)))
            }
        }
        // every size after every other one: the same page breaks as a score opened at that size
        for (before in sizes) for (scale in sizes) {
            engine.layout(LayoutSpec(width = 800, height = 1150, scale = before))
            val pages = engine.layout(LayoutSpec(width = 800, height = 1150, scale = scale))
            assertEquals("$scale % after $before %", fresh.getValue(scale), measuresPerPage(engine, pages))
        }
    }

    @Test
    fun turningTheScreenAndBackGivesTheSameLines() = runBlocking {
        val portrait = LayoutSpec(width = 800, height = 1150, scale = 38)
        val fresh = ScoreEngine(context).use { e ->
            e.open(demo())
            measuresPerPage(e, e.layout(portrait))
        }
        engine.open(demo())
        engine.layout(portrait)
        // landscape, then two pages side by side, then back: only the viewport changed
        engine.layout(portrait.copy(width = 1150, height = 800))
        engine.layout(portrait.copy(width = 575, height = 800))
        assertEquals(fresh, measuresPerPage(engine, engine.layout(portrait)))
    }

    @Test(expected = MusicXmlException::class)
    fun aFileThatIsNotMusicXmlIsRefused(): Unit = runBlocking {
        engine.open("%PDF-1.7".toByteArray())
    }

    // How many measures each line of each page holds: the page and line breaks, without the drawing.
    private suspend fun measuresPerPage(e: ScoreEngine, pages: Int): List<List<Int>> =
        (1..pages).map { page ->
            e.pageSvg(page).split("""class="system"""").drop(1)
                .map { Regex("""class="measure""").findAll(it).count() }
        }

    // The last font size written before [text]: the size it is drawn with.
    private fun sizeBefore(svg: String, text: String): Int {
        val at = svg.indexOf(text)
        assertTrue("$text not found", at >= 0)
        return Regex("""font-size="(\d+)px"""").findAll(svg.substring(0, at)).last().groupValues[1].toInt()
    }

    // Element ids are new at every load; the drawing itself must be the same.
    private fun withoutIds(svg: String) = svg.replace(Regex(""" id="[^"]*""""), "").replace(Regex("""#[A-Za-z0-9-]+"""), "")

    private fun darkShare(b: Bitmap): Double {
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        return pixels.count { Color.red(it) < 128 }.toDouble() / pixels.size
    }

    // For a look by eye: adb pull /sdcard/Android/data/<test package>/files/
    private fun save(b: Bitmap, name: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
