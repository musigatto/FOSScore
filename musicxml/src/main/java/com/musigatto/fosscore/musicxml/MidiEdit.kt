package com.musigatto.fosscore.musicxml

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Reads and changes a standard MIDI file without Verovio: a few milliseconds instead of seconds
 * on a slow tablet, so the tempo, the transposition and the parts heard change while the music
 * plays. It works on any MIDI file, also one attached to a PDF score.
 *
 * Every change keeps the length of the bytes: tempos are rewritten in their 3 bytes, keys and
 * programs in theirs, and the notes of a track left out start with velocity 0, which MIDI reads
 * as "note off".
 */
internal object MidiEdit {

    /**
     * [midi] with every tempo multiplied by [factor] (2.0 = twice as fast), the notes moved by
     * [semitones] (drums excepted), the notes of the tracks in [silent] muted, and the program
     * changes of the tracks in [programs] set to their General MIDI program (1-128). Tracks are
     * counted from 0, in file order.
     *
     * ponytail: a track that has no program change of its own keeps its instrument.
     */
    fun edit(
        midi: ByteArray,
        factor: Double,
        silent: Set<Int> = emptySet(),
        semitones: Int = 0,
        programs: Map<Int, Int> = emptyMap(),
    ): ByteArray {
        val b = midi.copyOf()
        forEachEvent(b) { track, _, status, at, meta ->
            val kind = status and 0xF0
            val drums = status and 0x0F == DRUM_CHANNEL
            when {
                meta == TEMPO -> {
                    val scaled = (micros(b, at) / factor).roundToInt().coerceIn(1, 0xFFFFFF)
                    b[at] = (scaled shr 16).toByte()
                    b[at + 1] = (scaled shr 8).toByte()
                    b[at + 2] = scaled.toByte()
                }
                meta >= 0 -> Unit
                kind == NOTE_ON || kind == NOTE_OFF -> {
                    if (semitones != 0 && !drums) b[at] = ((b[at].toInt() and 0x7F) + semitones).coerceIn(0, 127).toByte()
                    if (kind == NOTE_ON && track in silent) b[at + 1] = 0
                }
                kind == PROGRAM -> programs[track]?.let { b[at] = (it - 1).coerceIn(0, 127).toByte() }
            }
        }
        return b
    }

    /**
     * Where the music is in time: the audio time (ms) at the start, at each tempo change and at
     * the end, with the score position there in quarter notes. Time runs straight between them.
     */
    fun timing(midi: ByteArray): Pair<LongArray, DoubleArray> {
        if (midi.size < HEADER) return LongArray(0) to DoubleArray(0)
        val perQuarter = (midi[12].toInt() and 0xFF shl 8) or (midi[13].toInt() and 0xFF)
        // a time code division (top bit set) has no quarter notes
        if (perQuarter <= 0 || perQuarter and 0x8000 != 0) return LongArray(0) to DoubleArray(0)
        val tempos = sortedMapOf<Long, Int>()
        var end = 0L
        forEachEvent(midi) { _, tick, _, at, meta ->
            if (meta == TEMPO) tempos[tick] = micros(midi, at)
            end = maxOf(end, tick)
        }
        val ticks = (listOf(0L) + tempos.keys.filter { it > 0 } + end).distinct().sorted()
        val ms = LongArray(ticks.size)
        var micros = 0.0
        var tempo = tempos[0L] ?: DEFAULT_TEMPO
        for (i in 1 until ticks.size) {
            micros += (ticks[i] - ticks[i - 1]).toDouble() * tempo / perQuarter
            ms[i] = (micros / 1000).roundToLong()
            tempo = tempos[ticks[i]] ?: tempo
        }
        return ms to DoubleArray(ticks.size) { ticks[it].toDouble() / perQuarter }
    }

    // Calls [onEvent] for every event of every track: the track (0-based), its absolute tick, the
    // status byte, where its data starts, and the meta type (-1 for a channel or system event).
    private inline fun forEachEvent(b: ByteArray, onEvent: (track: Int, tick: Long, status: Int, at: Int, meta: Int) -> Unit) {
        if (b.size < HEADER || String(b, 0, 4, Charsets.ISO_8859_1) != "MThd") return
        var chunk = 8 + int32(b, 4)
        var track = 0
        while (chunk + 8 <= b.size) {
            val end = minOf(chunk + 8 + int32(b, chunk + 4), b.size)
            if (String(b, chunk, 4, Charsets.ISO_8859_1) == "MTrk") {
                var i = chunk + 8
                var tick = 0L
                var running = 0
                while (i < end) {
                    var delta = 0L
                    while (i < end) {
                        val v = b[i++].toInt() and 0xFF
                        delta = (delta shl 7) or (v and 0x7F).toLong()
                        if (v and 0x80 == 0) break
                    }
                    tick += delta
                    if (i >= end) break
                    var status = b[i].toInt() and 0xFF
                    if (status >= 0x80) i++ else status = running
                    if (status == 0xFF || status == 0xF0 || status == 0xF7) {
                        val meta = if (status == 0xFF) b[i++].toInt() and 0xFF else -1
                        var length = 0
                        while (i < end) {
                            val v = b[i++].toInt() and 0xFF
                            length = (length shl 7) or (v and 0x7F)
                            if (v and 0x80 == 0) break
                        }
                        if (meta == TEMPO && length == 3 && i + 3 <= end) onEvent(track, tick, status, i, meta)
                        else if (meta == END_OF_TRACK) onEvent(track, tick, status, i, meta)
                        i += length
                    } else {
                        running = status
                        val size = if (status and 0xF0 == PROGRAM || status and 0xF0 == PRESSURE) 1 else 2
                        if (i + size <= end) onEvent(track, tick, status, i, -1)
                        i += size
                    }
                }
                track++
            }
            chunk = end
        }
    }

    private fun micros(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 16) or (b[at + 1].toInt() and 0xFF shl 8) or (b[at + 2].toInt() and 0xFF)

    private fun int32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private const val HEADER = 14
    private const val TEMPO = 0x51
    private const val END_OF_TRACK = 0x2F
    private const val NOTE_OFF = 0x80
    private const val NOTE_ON = 0x90
    private const val PROGRAM = 0xC0
    private const val PRESSURE = 0xD0
    // channel 10, counted from 0
    private const val DRUM_CHANNEL = 9
    // MIDI's own default: ♩ = 120
    private const val DEFAULT_TEMPO = 500_000
}
