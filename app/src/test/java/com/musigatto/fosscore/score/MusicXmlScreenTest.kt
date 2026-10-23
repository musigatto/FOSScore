package com.musigatto.fosscore.score

import com.musigatto.fosscore.musicxml.LyricsPlacement
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.ui.viewer.ScoreLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicXmlScreenTest {
    private val tablet = MusicXmlScreen(800, 1280, 800, 44, 88, 112, twoUp = false)

    @Test
    fun aScreenKeptReadsBackTheSame() {
        assertEquals(tablet, MusicXmlScreen.fromJson(tablet.toJson()))
    }

    @Test
    fun theLayoutFollowsTheScoreSettings() {
        val parts = listOf(Part("P1", "Voix"), Part("P2", "Piano"))
        val spec = tablet.spec(ScoreLayout(musicSize = 90, semitones = 2, shownParts = setOf("P2"), lyrics = LyricsPlacement.BELOW), parts)
        assertEquals(800, spec.width)
        assertEquals(1280, spec.height)
        assertEquals(44 * 90 / 100, spec.scale)
        assertEquals(setOf("P2"), spec.parts)
        assertEquals(2, spec.semitones)
        // two-up: a page is half the screen, drawn in half the viewport
        assertEquals(Pair(400, 1280), tablet.copy(pageWidth = 400, twoUp = true).slot())
    }
}
