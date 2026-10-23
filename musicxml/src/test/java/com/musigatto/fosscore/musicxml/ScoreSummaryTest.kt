package com.musigatto.fosscore.musicxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreSummaryTest {

    @Test
    fun aKeptSummaryReadsBackTheSame() {
        val full = ScoreSummary(
            listOf(Part("P1", "Voix", true, 53, false), Part("P2", "Batterie", false, null, true)),
            ScoreInfo("Titre", "Sous-titre", "Compositeur", null, "Arrangeur", KeySignature(-2, "minor"), "6/8", 96.5, 42)
        )
        assertEquals(full, ScoreSummary.fromJson(full.toJson()))
        val empty = ScoreSummary(emptyList(), ScoreInfo())
        assertEquals(empty, ScoreSummary.fromJson(empty.toJson()))
        assertNull(ScoreSummary.fromJson("not json"))
    }
}
