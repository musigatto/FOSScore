package com.musigatto.fosscore.ui.viewer

import com.musigatto.fosscore.musicxml.Part
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackLogicTest {

    @Test
    fun theLoopAloneRepeatsTheWholePiece() {
        val loop = Loop().toggle()
        assertEquals(Loop(on = true), loop)
        assertEquals(0.0, loop.start, 0.0)
        assertEquals(null, loop.b)
    }

    @Test
    fun aAndBSetTheSectionAndASecondTapClearsThem() {
        val section = Loop(on = true).tapA(8.0).tapB(24.0)
        assertEquals(Loop(on = true, a = 8.0, b = 24.0), section)
        assertEquals(Loop(on = true, b = 24.0), section.tapA(30.0))
        assertEquals(Loop(on = true, a = 8.0), section.tapB(30.0))
    }

    @Test
    fun theMarkersStayWhenTheLoopIsTurnedOff() {
        val off = Loop(on = true, a = 8.0, b = 24.0).toggle()
        assertEquals(Loop(on = false, a = 8.0, b = 24.0), off)
        assertEquals(Loop(on = true, a = 8.0, b = 24.0), off.toggle())
    }

    @Test
    fun aMarkerOnTheOtherSideSwapsThem() {
        // the music was taken back between the two taps
        assertEquals(Loop(a = 4.0, b = 16.0), Loop(a = 16.0).tapB(4.0))
    }

    @Test
    fun bOnTheSameSpotAsAIsNotSet() {
        assertEquals(Loop(a = 8.0), Loop(a = 8.0).tapB(8.0))
    }

    @Test
    fun backGoesToTheStartOfTheLoopWhileLooping() {
        assertEquals(0.0, Loop(a = 8.0).restartAt(), 0.0)
        assertEquals(8.0, Loop(on = true, a = 8.0).restartAt(), 0.0)
        assertEquals(0.0, Loop(on = true, b = 24.0).restartAt(), 0.0)
    }

    @Test
    fun tempoIsShownInBeatsPerMinute() {
        assertEquals(100, bpmOf(1f, 100.0))
        assertEquals(80, bpmOf(0.8f, 100.0))
        assertEquals(0.8f, factorOf(80, 100.0), 1e-6f)
    }

    @Test
    fun tempoStaysInTheRangeTheModuleAccepts() {
        assertEquals(MIN_TEMPO_FACTOR, factorOf(1, 100.0), 0f)
        assertEquals(MAX_TEMPO_FACTOR, factorOf(1000, 100.0), 0f)
    }

    @Test
    fun thePartsHeardAreThePartsShownUntilPicked() {
        val parts = listOf(Part("P1", "Voice"), Part("P2", "Piano"))
        assertEquals(setOf("P1"), heardParts(null, setOf("P1"), parts))
        assertEquals(setOf("P2"), heardParts(setOf("P2"), setOf("P1"), parts))
    }

    @Test
    fun theMusicNeverGoesSilentByAccident() {
        val parts = listOf(Part("P1", "Voice"), Part("P2", "Piano"))
        // a part that is no longer in the file is dropped; with nothing left, every part plays
        assertEquals(setOf("P1", "P2"), heardParts(setOf("gone"), setOf("P1"), parts))
    }

    @Test
    fun everyPartCanBeMutedOnPurpose() {
        // the bar then follows the music without sound
        val parts = listOf(Part("P1", "Piano"))
        assertEquals(emptySet<String>(), heardParts(emptySet(), setOf("P1"), parts))
    }
}
