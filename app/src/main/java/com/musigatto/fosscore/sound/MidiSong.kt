package com.musigatto.fosscore.sound

/**
 * A standard MIDI file read for the live player: its channel events in time order, placed in
 * quarter notes (the score's own time, whatever the tempo), and its tempo changes. The player
 * turns quarter notes into audio time as it goes, so a new tempo applies at once.
 */
class MidiSong private constructor(
    /** The channel events, in time order (ties keep the file's order). */
    val events: List<Event>,
    // where the tempo changes, in quarter notes, with its microseconds per quarter note
    private val tempoAt: DoubleArray,
    private val tempoMicros: IntArray,
    /** Where the last event is, in quarter notes. */
    val length: Double,
) {
    /**
     * One channel event: a note on (velocity 0 is a note off), a note off, a program change or a
     * controller. [data1] is the key, program or controller, [data2] the velocity or value.
     */
    class Event(val quarters: Double, val type: Int, val channel: Int, val data1: Int, val data2: Int)

    /** Microseconds per quarter note at [quarters]: the file's tempo there. */
    fun microsPerQuarter(quarters: Double): Int {
        var i = tempoAt.size - 1
        while (i > 0 && tempoAt[i] > quarters) i--
        return if (tempoAt.isEmpty()) DEFAULT_TEMPO else tempoMicros[i]
    }

    /** Where the next tempo change after [quarters] is, or null. */
    fun nextTempoChange(quarters: Double): Double? = tempoAt.firstOrNull { it > quarters + 1e-9 }

    /** The index of the first event at or after [quarters]. */
    fun firstEventAt(quarters: Double): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].quarters < quarters - 1e-9) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val NOTE_OFF = 0x80
        const val NOTE_ON = 0x90
        const val CONTROL = 0xB0
        const val PROGRAM = 0xC0

        // MIDI's own default: ♩ = 120
        private const val DEFAULT_TEMPO = 500_000
        private const val TEMPO = 0x51

        /** Reads [midi]; an empty song when it is not a MIDI file timed in quarter notes. */
        fun read(midi: ByteArray): MidiSong {
            val empty = MidiSong(emptyList(), doubleArrayOf(0.0), intArrayOf(DEFAULT_TEMPO), 0.0)
            if (midi.size < 14 || String(midi, 0, 4, Charsets.ISO_8859_1) != "MThd") return empty
            val perQuarter = u16(midi, 12)
            // a time code division (top bit set) has no quarter notes
            if (perQuarter <= 0 || perQuarter and 0x8000 != 0) return empty
            class Raw(val tick: Long, val order: Int, val type: Int, val channel: Int, val d1: Int, val d2: Int)
            val raws = ArrayList<Raw>()
            val tempos = sortedMapOf<Long, Int>()
            var chunk = 8 + i32(midi, 4)
            var order = 0
            var lastTick = 0L
            while (chunk + 8 <= midi.size) {
                val end = minOf(chunk + 8 + i32(midi, chunk + 4), midi.size)
                if (String(midi, chunk, 4, Charsets.ISO_8859_1) == "MTrk") {
                    var i = chunk + 8
                    var tick = 0L
                    var running = 0
                    while (i < end) {
                        var delta = 0L
                        while (i < end) {
                            val v = midi[i++].toInt() and 0xFF
                            delta = (delta shl 7) or (v and 0x7F).toLong()
                            if (v and 0x80 == 0) break
                        }
                        tick += delta
                        if (i >= end) break
                        var status = midi[i].toInt() and 0xFF
                        if (status >= 0x80) i++ else status = running
                        if (status == 0xFF || status == 0xF0 || status == 0xF7) {
                            val meta = if (status == 0xFF) midi[i++].toInt() and 0xFF else -1
                            var length = 0
                            while (i < end) {
                                val v = midi[i++].toInt() and 0xFF
                                length = (length shl 7) or (v and 0x7F)
                                if (v and 0x80 == 0) break
                            }
                            if (meta == TEMPO && length == 3 && i + 3 <= end) {
                                tempos[tick] = (midi[i].toInt() and 0xFF shl 16) or (midi[i + 1].toInt() and 0xFF shl 8) or (midi[i + 2].toInt() and 0xFF)
                            }
                            i += length
                        } else {
                            running = status
                            val type = status and 0xF0
                            val size = if (type == PROGRAM || type == 0xD0) 1 else 2
                            if (i + size > end) break
                            val d1 = midi[i].toInt() and 0x7F
                            val d2 = if (size == 2) midi[i + 1].toInt() and 0x7F else 0
                            if (type == NOTE_ON || type == NOTE_OFF || type == PROGRAM || type == CONTROL) {
                                raws += Raw(tick, order++, type, status and 0x0F, d1, d2)
                            }
                            i += size
                        }
                        lastTick = maxOf(lastTick, tick)
                    }
                }
                chunk = end
            }
            // at one moment: notes off first, then programs and controllers, then notes on, so a
            // repeated note is not cut by its own end
            fun rank(type: Int, d2: Int) = when {
                type == NOTE_OFF || (type == NOTE_ON && d2 == 0) -> 0
                type == NOTE_ON -> 2
                else -> 1
            }
            val events = raws.sortedWith(compareBy<Raw>({ it.tick }, { rank(it.type, it.d2) }, { it.order }))
                .map { Event(it.tick.toDouble() / perQuarter, it.type, it.channel, it.d1, it.d2) }
            val at = (listOf(0L) + tempos.keys.filter { it > 0 }).distinct()
            val micros = at.map { tick -> tempos[tick] ?: tempos.headMap(tick + 1).values.lastOrNull() ?: DEFAULT_TEMPO }
            return MidiSong(
                events,
                DoubleArray(at.size) { at[it].toDouble() / perQuarter },
                micros.toIntArray(),
                lastTick.toDouble() / perQuarter,
            )
        }

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)

        private fun i32(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
                (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)
    }
}
