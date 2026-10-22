package com.musigatto.fosscore.musicxml

import org.json.JSONArray

/** One onset of the score shown: [quarters] from the start, and the ids of the notes that start there. */
class Onset(val quarters: Double, val notes: List<String>)

/**
 * What playback needs: a standard MIDI file, a map from audio time to score position, and the
 * onsets of the score shown. Score positions in quarter notes link the two, so the sound can
 * come from other parts than the ones on screen (a second layout), and the cursor still follows.
 */
class Playback internal constructor(
    /** Standard MIDI file, ready for `MediaPlayer` or any MIDI player. */
    val midi: ByteArray,
    private val audioMs: LongArray,
    private val audioQuarters: DoubleArray,
    val onsets: List<Onset>
) {
    /** Score position, in quarter notes, at [ms] of audio. */
    fun quartersAt(ms: Long): Double {
        if (audioMs.isEmpty()) return 0.0
        val i = audioMs.binarySearch(ms).let { if (it >= 0) it else -it - 2 }.coerceIn(0, audioMs.size - 1)
        if (i == audioMs.size - 1) return audioQuarters[i]
        val span = audioMs[i + 1] - audioMs[i]
        val f = if (span > 0) (ms - audioMs[i]).toDouble() / span else 0.0
        return audioQuarters[i] + f.coerceIn(0.0, 1.0) * (audioQuarters[i + 1] - audioQuarters[i])
    }

    /** Audio time of the last audio onset at or before the score position [quarters]. */
    fun msAt(quarters: Double): Long {
        if (audioMs.isEmpty()) return 0
        val i = audioQuarters.binarySearch(quarters).let { if (it >= 0) it else -it - 2 }
            .coerceIn(0, audioMs.size - 1)
        return audioMs[i]
    }

    /** Index in [onsets] of the last onset at or before [quarters], or -1 before the first. */
    fun onsetIndexAt(quarters: Double): Int {
        var lo = 0
        var hi = onsets.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (onsets[mid].quarters <= quarters + EPSILON) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    internal companion object {
        // Quarter-note positions come from floating-point sums of durations.
        private const val EPSILON = 1e-6

        /**
         * Builds the playback from Verovio timemaps: [displayTimemap] for the score shown (the
         * cursor) and [audioTimemap] for the parts heard (the same text when they are the same).
         */
        fun from(midi: ByteArray, displayTimemap: String, audioTimemap: String): Playback {
            val display = JSONArray(displayTimemap)
            val onsets = mutableListOf<Onset>()
            for (i in 0 until display.length()) {
                val entry = display.getJSONObject(i)
                val on = entry.optJSONArray("on") ?: continue
                if (on.length() == 0) continue
                onsets += Onset(entry.getDouble("qstamp"), List(on.length()) { on.getString(it) })
            }
            val audio = if (audioTimemap === displayTimemap) display else JSONArray(audioTimemap)
            val ms = LongArray(audio.length()) { audio.getJSONObject(it).getDouble("tstamp").toLong() }
            val quarters = DoubleArray(audio.length()) { audio.getJSONObject(it).getDouble("qstamp") }
            return Playback(midi, ms, quarters, onsets)
        }
    }
}
