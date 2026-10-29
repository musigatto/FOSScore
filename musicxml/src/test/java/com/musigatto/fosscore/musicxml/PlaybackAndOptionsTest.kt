package com.musigatto.fosscore.musicxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackAndOptionsTest {

    // Verovio's timemap: onsets with their time in ms and in quarter notes, notes starting and ending.
    private val timemap = """[
        {"tstamp": 0, "qstamp": 0, "on": ["n1", "n2"], "tempo": 120},
        {"tstamp": 500, "qstamp": 1, "on": ["n3"], "off": ["n1"]},
        {"tstamp": 1000, "qstamp": 2, "off": ["n2", "n3"]},
        {"tstamp": 2000, "qstamp": 3, "on": ["n4"]}
    ]"""

    @Test
    fun onsetsKeepOnlyEntriesWhereNotesStart() {
        val p = Playback.from(ByteArray(0), timemap, timemap)
        assertEquals(listOf(0.0, 1.0, 3.0), p.onsets.map { it.quarters })
        assertEquals(listOf("n1", "n2"), p.onsets[0].notes)
    }

    @Test
    fun audioTimeAndScorePositionMapBothWays() {
        val p = Playback.from(ByteArray(0), timemap, timemap)
        assertEquals(0.0, p.quartersAt(0), 1e-9)
        assertEquals(0.5, p.quartersAt(250), 1e-9)
        // The tempo halves after the second quarter: 1000 ms per quarter.
        assertEquals(2.5, p.quartersAt(1500), 1e-9)
        assertEquals(3.0, p.quartersAt(9000), 1e-9)
        assertEquals(500L, p.msAt(1.0))
        assertEquals(1000L, p.msAt(2.4))
    }

    @Test
    fun onsetIndexIsTheLastOneStarted() {
        val p = Playback.from(ByteArray(0), timemap, timemap)
        assertEquals(-1, p.onsetIndexAt(-0.5))
        assertEquals(0, p.onsetIndexAt(0.0))
        assertEquals(1, p.onsetIndexAt(2.9))
        assertEquals(2, p.onsetIndexAt(3.0))
    }

    @Test
    fun theCursorFollowsTheShownScoreWhileSoundComesFromOtherParts() {
        val shown = """[{"tstamp": 0, "qstamp": 0, "on": ["v1"]}, {"tstamp": 900, "qstamp": 2, "on": ["v2"]}]"""
        val p = Playback.from(ByteArray(0), shown, timemap)
        assertEquals(listOf("v1", "v2"), p.onsets.flatMap { it.notes })
        assertEquals(0.5, p.quartersAt(250), 1e-9)
    }

    @Test
    fun emptyTimemapIsHarmless() {
        val p = Playback.from(ByteArray(0), "[]", "[]")
        assertEquals(0.0, p.quartersAt(100), 0.0)
        assertEquals(0L, p.msAt(1.0))
        assertEquals(-1, p.onsetIndexAt(1.0))
    }

    @Test
    fun transpositionIntervals() {
        assertEquals("", VerovioOptions.interval(0))
        assertEquals("M2", VerovioOptions.interval(2))
        assertEquals("-m3", VerovioOptions.interval(-3))
        assertEquals("P8", VerovioOptions.interval(12))
    }

    @Test
    fun layoutOptionsMapOnePageToTheViewport() {
        val o = VerovioOptions.layout(LayoutSpec(width = 1200, height = 1800, scale = 50, semitones = -2))
        assertEquals(2400, o.getInt("pageWidth"))
        assertEquals(3600, o.getInt("pageHeight"))
        assertEquals(50, o.getInt("scale"))
        assertEquals("-M2", o.getString("transpose"))
        assertEquals(true, o.getBoolean("svgViewBox"))
        assertFalse(o.getBoolean("adjustPageHeight"))
    }

    // a header and three tracks: the tempo (500000 µs per quarter), then a note on each staff,
    // the second one in running status
    private fun midi(): ByteArray {
        fun track(vararg events: Int) = byteArrayOf(0x4D, 0x54, 0x72, 0x6B, 0, 0, 0, events.size.toByte()) +
            ByteArray(events.size) { events[it].toByte() }
        val header = byteArrayOf(0x4D, 0x54, 0x68, 0x64, 0, 0, 0, 6, 0, 1, 0, 3, 0x01, 0xE0.toByte())
        return header +
            track(0, 0xFF, 0x51, 3, 0x07, 0xA1, 0x20, 0, 0xFF, 0x2F, 0) +
            track(0, 0x90, 60, 100, 0x81, 0x70, 62, 90, 0, 0xFF, 0x2F, 0) +
            track(0, 0x91, 48, 80, 0, 0xFF, 0x2F, 0)
    }

    @Test
    fun theTempoAndThePartsHeardAreRewrittenInTheMidi() {
        val original = midi()
        val out = MidiEdit.edit(original, 2.0, setOf(1))
        assertEquals(original.size, out.size)
        // 500000 / 2 = 250000 = 0x03D090
        assertEquals(listOf(0x03, 0xD0, 0x90), (26..28).map { out[it].toInt() and 0xFF })
        // track 1 muted, in running status too: velocity 0 is a note off
        assertEquals(0, out[44].toInt())
        assertEquals(0, out[48].toInt())
        // track 2 untouched
        assertEquals(80, out[out.size - 5].toInt())
    }

    @Test
    fun theTimingComesFromTheMidiAndFollowsItsTempo() {
        // 240 ticks at 480 per quarter, ♩ = 120: half a quarter, 250 ms; twice as fast, 125 ms
        val (ms, quarters) = MidiEdit.timing(midi())
        assertEquals(listOf(0L, 250L), ms.toList())
        assertEquals(listOf(0.0, 0.5), quarters.toList())
        val faster = Playback.of(MidiEdit.edit(midi(), 2.0), emptyList())
        assertEquals(0.5, faster.quartersAt(125), 1e-9)
    }

    @Test
    fun theMeasuresStartWhereTheTimemapSays() {
        val timemap = """[{"qstamp":0,"tstamp":0,"measureOn":"m1","on":["n1"]},{"qstamp":1,"tstamp":500,"on":["n2"]},""" +
            """{"qstamp":4,"tstamp":2000,"measureOn":"m2"}]"""
        assertEquals(listOf(0.0, 4.0), Playback.barsOf(timemap))
        assertEquals(2, Playback.onsetsOf(timemap).size)
    }

    @Test
    fun aRepeatPlaysTheNotesOnThePageAgain() {
        // Verovio names the notes of a repeat after the notes written once on the page
        val timemap = """[{"qstamp":0,"tstamp":0,"on":["n1"]},{"qstamp":2,"tstamp":1000,"on":["n1-rend2","n2-rend2"]}]"""
        assertEquals(listOf(listOf("n1"), listOf("n1", "n2")), Playback.onsetsOf(timemap).map { it.notes })
    }

    @Test
    fun aTranspositionMovesTheNotes() {
        val out = MidiEdit.edit(midi(), 1.0, semitones = 2)
        assertEquals(62, out[43].toInt())
        assertEquals(64, out[47].toInt())
        assertEquals(50, out[out.size - 6].toInt())
    }

    @Test
    fun defaultScaleGivesSevenMillimetreStavesOnTabletsAndLessOnPhones() {
        // Galaxy Tab 3 10.1: 1280 × 800, 160 dpi → 7 mm = 44 px.
        assertEquals(61, LayoutSpec.defaultScale(1280, 800, 160))
        // Phone 1080 × 2340 at 420 dpi: 7 mm would be 116 px, capped at 1080 / 14 = 77 px.
        assertEquals(107, LayoutSpec.defaultScale(1080, 2340, 420))
    }

    @Test
    fun barsOverThePageBecomeVerovioMarginsAtTheScale() {
        // at scale 50 one pixel is 2 Verovio units: 120 px at the top, 140 at the bottom
        val o = VerovioOptions.layout(LayoutSpec(width = 1200, height = 1800, scale = 50, marginTop = 120, marginBottom = 140))
        assertEquals(3600, o.getInt("pageHeight"))
        assertEquals(VerovioOptions.layout(LayoutSpec(1200, 1800, 50)).getInt("pageMarginTop") + 240, o.getInt("pageMarginTop"))
        assertEquals(VerovioOptions.layout(LayoutSpec(1200, 1800, 50)).getInt("pageMarginBottom") + 280, o.getInt("pageMarginBottom"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun marginsLargerThanThePageAreRefused() {
        LayoutSpec(width = 800, height = 600, marginTop = 300, marginBottom = 300)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyViewportIsRefused() {
        LayoutSpec(width = 0, height = 100)
    }
}
