package com.musigatto.fosscore.musicxml

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicXmlScoreTest {

    private fun demo(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("au-clair-de-la-lune.musicxml")!!.readBytes()

    /**
     * Two parts as MuseScore writes a song: a vocal line hidden by its author (staff marked
     * invisible, notes without head or stem, kept only for the lyrics and the tempo mark) and
     * a two-staff piano.
     */
    private val song = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
        <score-partwise version="4.0">
          <work><work-title>Work title</work-title></work>
          <identification><creator type="composer">Someone</creator><creator type="poet">Poet</creator></identification>
          <part-list>
            <part-group type="start" number="1"><group-symbol>bracket</group-symbol></part-group>
            <score-part id="P1"><part-name>Voice</part-name>
              <midi-instrument id="P1-I1"><midi-channel>1</midi-channel><midi-program>54</midi-program></midi-instrument></score-part>
            <score-part id="P2"><part-name>Piano</part-name>
              <midi-instrument id="P2-I1"><midi-channel>2</midi-channel><midi-program>1</midi-program></midi-instrument></score-part>
            <part-group type="stop" number="1"/>
          </part-list>
          <part id="P1">
            <measure number="1">
              <attributes><divisions>2</divisions><key><fifths>-1</fifths><mode>major</mode></key>
                <time><beats>3</beats><beat-type>4</beat-type></time>
                <staff-details print-object="no"/></attributes>
              <note><pitch><step>F</step><octave>4</octave></pitch><duration>2</duration><voice>1</voice>
                <notehead>none</notehead><stem>none</stem><beam number="1">begin</beam>
                <notations><slur type="start"/><articulations><accent/></articulations></notations>
                <lyric placement="above"><text>la</text></lyric></note>
              <direction placement="above"><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>96</per-minute></metronome></direction-type>
                <voice>1</voice><staff>1</staff><sound tempo="96"/></direction>
              <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice>
                <notehead>none</notehead><stem>none</stem><lyric placement="above"><text>lu</text></lyric></note>
            </measure>
          </part>
          <part id="P2">
            <measure number="1">
              <attributes><divisions>2</divisions><staves>2</staves></attributes>
              <note><pitch><step>A</step><octave>4</octave></pitch><duration>6</duration><voice>1</voice><staff>1</staff></note>
              <backup><duration>6</duration></backup>
              <note print-object="no"><pitch><step>A</step><octave>4</octave></pitch><duration>6</duration><voice>5</voice><staff>1</staff>
                <lyric><text>ah</text></lyric></note>
              <backup><duration>6</duration></backup>
              <note><pitch><step>F</step><octave>3</octave></pitch><duration>6</duration><voice>2</voice><staff>2</staff></note>
            </measure>
          </part>
        </score-partwise>
    """.trimIndent().toByteArray()

    @Test
    fun readsPartsAndWhetherTheAuthorHidThem() {
        val parts = MusicXmlScore.parse(song).parts
        assertEquals(listOf("P1", "P2"), parts.map { it.id })
        assertEquals(listOf("Voice", "Piano"), parts.map { it.name })
        assertEquals(listOf(false, true), parts.map { it.visibleInFile })
        assertEquals(listOf(54, 1), parts.map { it.program })
    }

    @Test
    fun readsScoreDetails() {
        val info = MusicXmlScore.parse(song).info
        assertEquals("Work title", info.title)
        assertEquals("Someone", info.composer)
        assertEquals("Poet", info.lyricist)
        assertEquals(KeySignature(-1, "major"), info.key)
        assertEquals("3/4", info.time)
        assertEquals(96.0, info.tempo!!, 0.0)
        assertEquals(1, info.measures)
    }

    @Test
    fun creditsWinOverWorkTitleAndGiveTheirSize() {
        val score = MusicXmlScore.parse(demo())
        assertEquals("Au clair de la lune", score.info.title)
        assertEquals("Traditionnel", score.info.composer)
        assertEquals("4/4", score.info.time)
        assertEquals(100.0, score.info.tempo!!, 0.0)
        // 22 pt title over a 10 pt word font; the composer line has no size of its own.
        assertEquals(listOf(220, null), score.creditSizes)
        assertEquals(listOf("Voix", "Piano"), score.parts.map { it.name })
    }

    @Test
    fun metronomeGivesTheTempoWhenNoSoundDoes() {
        val xml = String(song).replace("""<sound tempo="96"/>""", "")
            .replace("<beat-unit>quarter</beat-unit>", "<beat-unit>half</beat-unit>")
        assertEquals(192.0, MusicXmlScore.parse(xml.toByteArray()).info.tempo!!, 0.0)
    }

    @Test
    fun headlessNotesBecomeInvisibleWithoutBeamsOrSlurs() {
        val xml = MusicXmlScore.parse(song).prepare()
        val first = xml.substringAfter("<note").substringBefore("</note>")
        assertTrue(first.startsWith(" print-object=\"no\""))
        assertFalse("beam" in first)
        assertFalse("slur" in first)
        // Lyrics and other notations stay.
        assertTrue("<lyric" in first)
        assertTrue("<accent" in first)
    }

    @Test
    fun keepingPartsCopiesTheTempoOfTheHiddenOne() {
        val score = MusicXmlScore.parse(song)
        val xml = score.prepare(ScoreFilter(parts = setOf("P2")))
        assertFalse("<part id=\"P1\"" in xml)
        assertFalse("score-part id=\"P1\"" in xml)
        assertFalse("part-group" in xml)
        val piano = xml.substringAfter("<part id=\"P2\"")
        assertTrue("<sound tempo=\"96\"/>" in piano)
        // The tempo mark was after a 2-division note: offset 2, without the voice and staff of the voice part.
        val direction = piano.substringAfter("<direction").substringBefore("</direction>")
        assertTrue("<offset>2</offset>" in direction)
        assertFalse("<staff>" in direction)
        assertFalse("<voice>" in direction)
    }

    @Test
    fun prepareLeavesTheScoreAsItWas() {
        val score = MusicXmlScore.parse(song)
        val before = score.prepare()
        score.prepare(ScoreFilter(parts = setOf("P2"), lyrics = LyricsPlacement.BOTTOM, programs = mapOf("P2" to 41)))
        assertEquals(before, score.prepare())
    }

    @Test
    fun lyricsUnderTheSystemMoveHiddenVoicesToTheLowestStaff() {
        val xml = MusicXmlScore.parse(song).prepare(ScoreFilter(lyrics = LyricsPlacement.BOTTOM))
        val piano = xml.substringAfter("<part id=\"P2\"")
        val hidden = piano.substringAfter("<note print-object=\"no\">").substringBefore("</note>")
        assertTrue("<staff>2</staff>" in hidden)
        // The visible notes of the right hand stay on the upper staff.
        assertTrue(piano.substringAfter("<note>").substringBefore("</note>").contains("<staff>1</staff>"))
    }

    @Test
    fun lyricsAboveFollowTheFileOrTheChoice() {
        val score = MusicXmlScore.parse(song)
        // Two lyrics above out of three.
        assertTrue(score.lyricsAbove(LyricsPlacement.FILE))
        assertTrue(score.lyricsAbove(LyricsPlacement.ABOVE))
        assertFalse(score.lyricsAbove(LyricsPlacement.BELOW))
        assertFalse(MusicXmlScore.parse(demo()).lyricsAbove(LyricsPlacement.FILE))
    }

    @Test
    fun programsReplaceTheFileInstrument() {
        val xml = MusicXmlScore.parse(song).prepare(ScoreFilter(programs = mapOf("P2" to 41, "P1" to 999)))
        assertTrue("<midi-program>41</midi-program>" in xml)
        assertTrue("<midi-program>128</midi-program>" in xml)
    }

    @Test
    fun readsCompressedMusicXml() {
        val container = """<?xml version="1.0"?><container><rootfiles>
            <rootfile full-path="score/song.musicxml"/></rootfiles></container>"""
        val mxl = zip(
            "mimetype" to "application/vnd.recordare.musicxml".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "score/other.xml" to "<other/>".toByteArray(),
            "score/song.musicxml" to song
        )
        assertTrue(MusicXmlFiles.isCompressed(mxl))
        assertTrue(MusicXmlFiles.looksLikeMusicXml(mxl))
        assertEquals("Work title", MusicXmlScore.parse(mxl).info.title)
    }

    @Test
    fun recognizesMusicXmlAmongOtherFiles() {
        assertTrue(MusicXmlFiles.looksLikeMusicXml(song))
        assertTrue(MusicXmlFiles.looksLikeMusicXml(demo()))
        assertFalse(MusicXmlFiles.looksLikeMusicXml("<svg/>".toByteArray()))
        assertFalse(MusicXmlFiles.looksLikeMusicXml("%PDF-1.7".toByteArray()))
        assertFalse(MusicXmlFiles.looksLikeMusicXml(zip("a.txt" to "x".toByteArray())))
    }

    @Test
    fun utf16FilesAreRead() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            String(song).replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"").toByteArray(Charsets.UTF_16LE)
        assertTrue(MusicXmlFiles.looksLikeMusicXml(utf16))
        assertEquals("Work title", MusicXmlScore.parse(utf16).info.title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherXmlIsRefused() {
        MusicXmlScore.parse("<svg/>".toByteArray())
    }

    @Test
    fun noDetailsGiveNulls() {
        val bare = """<score-partwise><part-list/></score-partwise>""".toByteArray()
        val info = MusicXmlScore.parse(bare).info
        assertNull(info.title)
        assertNull(info.key)
        assertNull(info.tempo)
        assertEquals(0, info.measures)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
