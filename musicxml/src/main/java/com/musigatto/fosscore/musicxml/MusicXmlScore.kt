package com.musigatto.fosscore.musicxml

import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * A MusicXML score kept as a DOM, so it can be changed before Verovio lays it out: hide parts,
 * move lyrics, change instruments. Files exported from MuseScore often hold the whole
 * arrangement (voice, piano, strings, drums...) while the musician only wants to read one part.
 *
 * At parsing, notes the author hid are cleaned up (see [hideInvisibleNotes]).
 *
 * Not thread-safe: [prepare] changes the DOM and puts it back.
 */
class MusicXmlScore private constructor(private val doc: Document) {

    private val root: Element = doc.documentElement

    /** False for `score-timewise` files: they are laid out as they are, without [prepare]'s changes. */
    val partwise: Boolean = root.tagName == "score-partwise"

    val parts: List<Part> = run {
        val hidden = root.children("part").filter { it.allStavesHidden() }.map { it.getAttribute("id") }.toSet()
        root.child("part-list")?.children("score-part").orEmpty().map {
            val id = it.getAttribute("id")
            val instrument = it.child("midi-instrument")
            Part(
                id = id,
                name = it.child("part-name")?.text().orEmpty(),
                visibleInFile = id !in hidden,
                program = instrument?.child("midi-program")?.text()?.toIntOrNull(),
                drums = instrument?.child("midi-channel")?.text() == "10"
            )
        }
    }

    val info: ScoreInfo = readInfo()

    /** True when most lyrics of the file ask to be drawn above the staff. */
    private val lyricsAboveInFile: Boolean = doc.getElementsByTagName("lyric").let { lyrics ->
        val places = (0 until lyrics.length).map { (lyrics.item(it) as Element).getAttribute("placement") }
        places.count { it == "above" } > places.size / 2
    }

    /** Whether lyrics end up above the staff with [placement]. */
    fun lyricsAbove(placement: LyricsPlacement): Boolean = when (placement) {
        LyricsPlacement.FILE -> lyricsAboveInFile
        LyricsPlacement.ABOVE -> true
        LyricsPlacement.BELOW, LyricsPlacement.BOTTOM -> false
    }

    /**
     * Size of each first-page credit (title, composer...), in percent of the file's default word
     * font, in the order Verovio turns them into page-header lines. Null where the file gives none.
     */
    val creditSizes: List<Int?> = run {
        val wordSize = root.child("defaults")?.child("word-font")?.getAttribute("font-size")?.toFloatOrNull()
            ?.takeIf { it > 0 } ?: 10f
        root.children("credit").filter { it.getAttribute("page").ifEmpty { "1" } == "1" }
            .flatMap { it.children("credit-words") }
            .map { words -> words.getAttribute("font-size").toFloatOrNull()?.let { Math.round(it / wordSize * 100) } }
    }

    /** The score changed as [filter] asks, as MusicXML text for Verovio. */
    fun prepare(filter: ScoreFilter = ScoreFilter()): String {
        // Android's DOM cannot clone a Document, and parsing it again takes more than a second
        // on slow tablets: change the DOM in place, serialize, then undo every change in reverse.
        val undo = mutableListOf<() -> Unit>()
        try {
            if (partwise) {
                keepParts(filter.parts, undo)
                if (filter.lyrics == LyricsPlacement.BOTTOM) lyricsUnderSystem(undo)
                setPrograms(filter.programs, undo)
            }
            return serialize()
        } finally {
            undo.asReversed().forEach { it() }
        }
    }

    private fun serialize(): String {
        val out = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(doc), StreamResult(out))
        return out.toString()
    }

    private fun keepParts(keep: Set<String>, undo: MutableList<() -> Unit>) {
        val all = root.children("part")
        if (keep.isEmpty() || all.all { it.getAttribute("id") in keep }) return
        val kept = all.filter { it.getAttribute("id") in keep }
        val removed = all.filter { it.getAttribute("id") !in keep }
        // MuseScore writes tempo marks in the first part only, often the hidden voice.
        kept.firstOrNull()?.let { if (kept.none(::hasTempo)) copyTempo(removed, it, undo) }
        root.child("part-list")?.let { list ->
            detach(list, undo) {
                // Brackets and braces would point at removed parts.
                it.tagName == "part-group" || (it.tagName == "score-part" && it.getAttribute("id") !in keep)
            }
        }
        detach(root, undo) { it.tagName == "part" && it.getAttribute("id") !in keep }
    }

    // Detach in document order so that, restored in reverse, each node's next sibling is back.
    private fun detach(parent: Element, undo: MutableList<() -> Unit>, drop: (Element) -> Boolean) {
        parent.children().filter(drop).forEach { child ->
            val next = child.nextSibling
            parent.removeChild(child)
            undo += { parent.insertBefore(child, next) }
        }
    }

    private fun setText(element: Element, text: String, undo: MutableList<() -> Unit>) {
        val old = element.textContent
        element.textContent = text
        undo += { element.textContent = old }
    }

    /**
     * Moves the hidden voices that carry lyrics to the lowest staff of their part. Their notes
     * are invisible, so only the lyrics move; the visible notes stay where they are.
     *
     * Above or below the staff is set later, in the MEI ([MeiFixes]): Verovio's MusicXML import
     * ignores `<lyric placement>`.
     */
    private fun lyricsUnderSystem(undo: MutableList<() -> Unit>) {
        for (part in root.children("part")) {
            val staves = part.child("measure")?.children("attributes").orEmpty()
                .firstNotNullOfOrNull { it.child("staves")?.text()?.toIntOrNull() } ?: 1
            if (staves < 2) continue
            val hiddenNotes = part.children("measure").flatMap { it.children("note") }
                .filter { it.getAttribute("print-object") == "no" }
            val voices = hiddenNotes.filter { it.child("lyric") != null }.mapNotNull { it.child("voice")?.text() }.toSet()
            hiddenNotes.filter { it.child("voice")?.text() in voices }.forEach { note ->
                note.child("staff")?.let { setText(it, staves.toString(), undo) }
            }
        }
    }

    private fun setPrograms(programs: Map<String, Int>, undo: MutableList<() -> Unit>) {
        if (programs.isEmpty()) return
        root.child("part-list")?.children("score-part").orEmpty().forEach { scorePart ->
            val program = programs[scorePart.getAttribute("id")]?.coerceIn(1, 128) ?: return@forEach
            scorePart.children("midi-instrument").forEach { instrument ->
                instrument.child("midi-program")?.let { setText(it, program.toString(), undo) }
            }
        }
    }

    private fun isTempo(e: Element): Boolean = when (e.tagName) {
        "sound" -> e.hasAttribute("tempo")
        "direction" -> e.child("sound")?.hasAttribute("tempo") == true || e.getElementsByTagName("metronome").length > 0
        else -> false
    }

    private fun hasTempo(part: Element) = part.children("measure").any { m -> m.children().any(::isTempo) }

    /**
     * Copies the tempo marks of [from] into [to], at the same measure and beat, so the tempo and
     * its mark survive hiding the part that holds them.
     */
    private fun copyTempo(from: List<Element>, to: Element, undo: MutableList<() -> Unit>) {
        val marks = from.flatMap(::tempoMarks).distinctBy { it.measure to it.quarters }
        if (marks.isEmpty()) return
        var divisions = 1
        to.children("measure").forEachIndexed { index, measure ->
            measure.children("attributes").forEach { a ->
                a.child("divisions")?.text()?.toIntOrNull()?.let { divisions = it }
            }
            for (mark in marks.filter { it.measure == index }) {
                val copy = mark.element.cloneNode(true) as Element
                if (copy.tagName == "direction") {
                    copy.children().filter { it.tagName in setOf("staff", "voice", "offset") }.forEach { copy.removeChild(it) }
                    val offset = Math.round(mark.quarters * divisions).toInt()
                    if (offset > 0) {
                        val element = doc.createElement("offset").apply { textContent = offset.toString() }
                        // Schema order: direction-type+, offset?, ..., voice?, staff?, sound?
                        copy.insertBefore(element, copy.children().firstOrNull { it.tagName != "direction-type" })
                    }
                }
                // At the start of the measure, after its attributes and print settings.
                measure.insertBefore(copy, measure.children().firstOrNull { it.tagName !in setOf("attributes", "print") })
                undo += { measure.removeChild(copy) }
            }
        }
    }

    private class TempoMark(val measure: Int, val quarters: Double, val element: Element)

    private fun tempoMarks(part: Element): List<TempoMark> {
        val out = mutableListOf<TempoMark>()
        var divisions = 1
        part.children("measure").forEachIndexed { index, measure ->
            var time = 0
            for (e in measure.children()) {
                val duration = e.child("duration")?.text()?.toIntOrNull() ?: 0
                when (e.tagName) {
                    "attributes" -> e.child("divisions")?.text()?.toIntOrNull()?.let { divisions = it }
                    "note" -> if (e.child("chord") == null) time += duration
                    "backup" -> time -= duration
                    "forward" -> time += duration
                    else -> if (isTempo(e)) out += TempoMark(index, time.toDouble() / divisions, e)
                }
            }
        }
        return out
    }

    private fun readInfo(): ScoreInfo {
        val credits = root.children("credit").groupBy(
            { it.child("credit-type")?.text() },
            { c -> c.children("credit-words").joinToString(" ") { it.text() }.trim() }
        )
        fun credit(type: String) = credits[type]?.firstOrNull { it.isNotEmpty() }
        fun creator(vararg types: String) = root.child("identification")?.children("creator").orEmpty()
            .firstOrNull { it.getAttribute("type") in types }?.text()?.takeIf { it.isNotEmpty() }

        val title = credit("title")
            ?: root.child("work")?.child("work-title")?.text()?.takeIf { it.isNotEmpty() }
            ?: root.child("movement-title")?.text()?.takeIf { it.isNotEmpty() }
        val firstPart = root.child("part")
        val attributes = firstPart?.children("measure")?.firstOrNull()?.children("attributes").orEmpty()
        val key = attributes.firstNotNullOfOrNull { it.child("key") }?.let { k ->
            k.child("fifths")?.text()?.toIntOrNull()?.let { KeySignature(it, k.child("mode")?.text()) }
        }
        val time = attributes.firstNotNullOfOrNull { it.child("time") }?.let { t ->
            val beats = t.child("beats")?.text()
            val type = t.child("beat-type")?.text()
            if (beats != null && type != null) "$beats/$type" else null
        }
        return ScoreInfo(
            title = title,
            subtitle = credit("subtitle"),
            composer = credit("composer") ?: creator("composer"),
            lyricist = credit("lyricist") ?: creator("lyricist", "poet"),
            arranger = credit("arranger") ?: creator("arranger"),
            key = key,
            time = time,
            tempo = firstTempo(),
            measures = firstPart?.children("measure")?.size ?: 0
        )
    }

    // A <sound tempo> is the played tempo; a metronome mark in quarter notes is the next best.
    private fun firstTempo(): Double? {
        val sounds = doc.getElementsByTagName("sound")
        (0 until sounds.length).map { sounds.item(it) as Element }
            .firstNotNullOfOrNull { it.getAttribute("tempo").toDoubleOrNull()?.takeIf { t -> t > 0 } }
            ?.let { return it }
        val metronomes = doc.getElementsByTagName("metronome")
        return (0 until metronomes.length).map { metronomes.item(it) as Element }.firstNotNullOfOrNull { m ->
            val perMinute = m.child("per-minute")?.text()?.toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            val unit = m.child("beat-unit")?.text() ?: return@firstNotNullOfOrNull null
            val dotted = m.child("beat-unit-dot") != null
            BEAT_UNITS[unit]?.let { perMinute * it * (if (dotted) 1.5 else 1.0) }
        }
    }

    companion object {
        // Length of each beat unit in quarter notes.
        private val BEAT_UNITS = mapOf(
            "whole" to 4.0, "half" to 2.0, "quarter" to 1.0, "eighth" to 0.5, "16th" to 0.25
        )

        /**
         * Parses plain MusicXML or compressed .mxl. Never touches the network: the DOCTYPE that
         * MusicXML files carry points to a DTD online, which is not loaded.
         */
        fun parse(bytes: ByteArray): MusicXmlScore {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isValidating = false
                isNamespaceAware = false
                isExpandEntityReferences = false
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            val doc = builder.parse(ByteArrayInputStream(MusicXmlFiles.scoreBytes(bytes)))
            require(doc.documentElement.tagName.startsWith("score-")) { "not a MusicXML score" }
            hideInvisibleNotes(doc)
            return MusicXmlScore(doc)
        }

        /**
         * MuseScore hides a note either with `print-object="no"` or, for a vocal line kept only for
         * its lyrics, with no notehead and no stem. Verovio still draws the beams, ledger lines,
         * ties, slurs and accidentals of such notes: mark them invisible and drop what hangs on
         * them. The lyrics stay.
         */
        private fun hideInvisibleNotes(doc: Document) {
            val notes = doc.getElementsByTagName("note")
            for (i in 0 until notes.length) {
                val note = notes.item(i) as Element
                val headless = note.child("notehead")?.text() == "none" && note.child("stem")?.text() == "none"
                if (!headless && note.getAttribute("print-object") != "no") continue
                note.setAttribute("print-object", "no")
                note.children().filter { it.tagName in HIDDEN_NOTE_MARKS }.forEach { note.removeChild(it) }
                note.children("notations").forEach { notations ->
                    notations.children().filter { it.tagName in HIDDEN_NOTE_MARKS }.forEach { notations.removeChild(it) }
                }
            }
        }

        private val HIDDEN_NOTE_MARKS = setOf("beam", "tie", "tied", "slur", "accidental")

        /** True when the first measure marks every staff of this part invisible. */
        private fun Element.allStavesHidden(): Boolean {
            val attributes = child("measure")?.children("attributes").orEmpty()
            val staves = attributes.firstNotNullOfOrNull { it.child("staves")?.text()?.toIntOrNull() } ?: 1
            val hidden = attributes.flatMap { it.children("staff-details") }
                .filter { it.getAttribute("print-object") == "no" }
                .map { it.getAttribute("number").toIntOrNull() }
            return null in hidden || (1..staves).all { it in hidden }
        }

        private fun Element.children(tag: String? = null): List<Element> {
            val out = mutableListOf<Element>()
            var n = firstChild
            while (n != null) {
                if (n is Element && (tag == null || n.tagName == tag)) out += n
                n = n.nextSibling
            }
            return out
        }

        private fun Element.child(tag: String): Element? {
            var n = firstChild
            while (n != null) {
                if (n is Element && n.tagName == tag) return n
                n = n.nextSibling
            }
            return null
        }

        private fun Element.text(): String = textContent.trim()
    }
}
