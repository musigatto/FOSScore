package com.musigatto.fosscore.sound

// the load (time to render a block / the block's own time) above which voices are given up,
// and below which they come back: on a Galaxy Tab 3 a dense piece averaged 70 % with the limit
// at 85 %, some blocks going past their time; 75 % keeps them in it
private const val HIGH_LOAD = 0.75f
private const val LOW_LOAD = 0.5f

// the blocks in a row that must agree before the limit moves: one slow block is no trend
private const val BLOCKS_TO_MOVE = 8

/**
 * How many voices the synth holds, from how long the last blocks took to render: a slow tablet
 * cannot render every voice of a dense piece (a bank of long stereo samples, the sustain pedal)
 * in time, and the music would crackle and lag. Over [HIGH_LOAD] the limit goes down two by two
 * to [min]; under [LOW_LOAD] it goes back up one by one to [max].
 */
class VoiceBudget(private val max: Int, private val min: Int) {
    /** The voices the synth may hold now. */
    var limit = max
        private set

    private var high = 0
    private var low = 0

    /** The load of the block just rendered: its render time over its own time. */
    fun update(load: Float) {
        when {
            load > HIGH_LOAD -> {
                low = 0
                if (++high >= BLOCKS_TO_MOVE) {
                    high = 0
                    limit = (limit - 2).coerceAtLeast(min)
                }
            }
            load < LOW_LOAD -> {
                high = 0
                if (++low >= BLOCKS_TO_MOVE) {
                    low = 0
                    limit = (limit + 1).coerceAtMost(max)
                }
            }
            else -> {
                high = 0
                low = 0
            }
        }
    }
}
