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

    @Test
    fun tempoFactorIsKeptInARange() {
        assertEquals(4.0, VerovioOptions.tempo(10.0).getDouble("midiTempoAdjustment"), 0.0)
        assertEquals(0.75, VerovioOptions.tempo(0.75).getDouble("midiTempoAdjustment"), 0.0)
    }

    @Test
    fun defaultScaleGivesSevenMillimetreStavesOnTabletsAndLessOnPhones() {
        // Galaxy Tab 3 10.1: 1280 × 800, 160 dpi → 7 mm = 44 px.
        assertEquals(61, LayoutSpec.defaultScale(1280, 800, 160))
        // Phone 1080 × 2340 at 420 dpi: 7 mm would be 116 px, capped at 1080 / 14 = 77 px.
        assertEquals(107, LayoutSpec.defaultScale(1080, 2340, 420))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyViewportIsRefused() {
        LayoutSpec(width = 0, height = 100)
    }
}
