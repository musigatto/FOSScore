package com.musigatto.fosscore.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

// the synth works out what changes slowly (envelopes, LFOs, pitch, filter) once per this many frames
private const val CONTROL_FRAMES = 32

// EMU's sound cards, which SoundFonts are made for, play the attenuation at 0.4 of its value
private const val ATTENUATION_SCALE = 0.4f

// a voice's envelope below this attenuation (centibels, -96 dB) is silent: it stops
private const val SILENT_CB = 960f

// a released voice below this (-72 dB) cannot be heard in the music any more: it stops, and
// leaves its place (a bank's long piano releases held the voices of a dense piece)
private const val RELEASED_SILENT_CB = 720f

// the voices given up that may still be fading out, past the voice limit
private const val FADING_VOICES = 16

// the envelopes run down 100 dB (1440 cB) in their decay and release times
private const val FULL_RANGE_CB = 1440f

// a voice cut short (a stolen voice, a note of the same exclusive class) fades in this time
private const val QUICK_RELEASE_S = 0.005f

// the filter is left out above this (cents above 8.176 Hz, about 20 kHz) with no resonance
private const val FILTER_OPEN_CENTS = 13500f

// the level of the whole mix: a piece for piano plays around -20 dB, its loudest chords below 0
private const val MASTER_GAIN = 0.8f

// above this level the mix is bent down gently instead of clipping (a hard clip crackles)
private const val SOFT_LIMIT = 0.8f

// what the reverb sends get when a MIDI file says nothing (General MIDI's CC 91 default)
private const val DEFAULT_REVERB_CC = 40

/**
 * A SoundFont synthesizer: the notes of the 16 MIDI channels played from [font], mixed into
 * stereo frames by [render]. It follows the SoundFont 2 model (sample loops, volume and
 * modulation envelopes, LFOs, a resonant low-pass filter, modulators from velocity and the
 * channel controllers) with a small reverb, in plain Kotlin.
 *
 * Not thread-safe: one thread renders and sends the MIDI events (the player's audio thread).
 */
class Synth(private val font: SoundFont, val sampleRate: Int, private val maxVoices: Int = 48) {

    /**
     * The voices held now, up to [maxVoices]: fewer on a tablet that cannot render them all in
     * time (see VoiceBudget). Past it, a new note gives up a voice, which fades out in a few ms.
     */
    var voiceLimit = maxVoices
        set(value) {
            field = value.coerceIn(1, maxVoices)
        }


    private class Channel {
        var program = 0
        var bank = 0
        val cc = IntArray(128).apply {
            this[7] = 100
            this[10] = 64
            this[11] = 127
            this[91] = DEFAULT_REVERB_CC
        }
        var sustain = false
    }

    private val channels = Array(16) { Channel().apply { if (it == DRUM_CHANNEL) bank = SoundFont.DRUM_BANK } }
    private val voices = ArrayList<Voice>(maxVoices)
    private val reverb = Reverb(sampleRate)
    private val reverbIn = FloatArray(CONTROL_FRAMES)
    private var noteCount = 0L

    /** The voices sounding now: for a load gauge, and the tests. */
    val activeVoices: Int get() = voices.size

    fun noteOn(channel: Int, key: Int, velocity: Int) {
        if (velocity == 0) return noteOff(channel, key)
        val ch = channels[channel]
        noteCount++
        for (region in font.regions(ch.bank, ch.program, key, velocity)) {
            val exclusive = region.gens[Gen.EXCLUSIVE_CLASS]
            if (exclusive != 0) voices.forEach { if (it.channel == channel && it.exclusive == exclusive) it.cut() }
            if (sounding() >= voiceLimit) steal()
            voices += Voice(region, channel, key, velocity, noteCount, ch)
        }
    }

    fun noteOff(channel: Int, key: Int) {
        val sustained = channels[channel].sustain
        voices.forEach { if (it.channel == channel && it.key == key && !it.released) if (sustained) it.sustained = true else it.release() }
    }

    fun programChange(channel: Int, program: Int) {
        channels[channel].program = program
    }

    fun controlChange(channel: Int, controller: Int, value: Int) {
        val ch = channels[channel]
        when (controller) {
            // bank select; channel 10 stays on the drums
            0 -> if (channel != DRUM_CHANNEL) ch.bank = value
            64 -> {
                ch.sustain = value >= 64
                if (!ch.sustain) voices.forEach { if (it.channel == channel && it.sustained) it.release() }
            }
            120 -> voices.forEach { if (it.channel == channel) it.cut() }
            121 -> resetControllers(channel)
            123 -> voices.forEach { if (it.channel == channel && !it.released) it.release() }
            else -> ch.cc[controller] = value
        }
        voices.forEach { if (it.channel == channel) it.controllersChanged() }
    }

    /**
     * Reads ahead every sample [song] plays, so the first notes do not wait for them on the audio
     * thread. Safe on any thread.
     */
    fun warm(song: MidiSong) {
        val program = IntArray(16)
        val bank = IntArray(16) { if (it == DRUM_CHANNEL) SoundFont.DRUM_BANK else 0 }
        val seen = HashSet<Long>()
        for (e in song.events) {
            when (e.type) {
                MidiSong.PROGRAM -> program[e.channel] = e.data1
                MidiSong.CONTROL -> if (e.data1 == 0 && e.channel != DRUM_CHANNEL) bank[e.channel] = e.data2
                MidiSong.NOTE_ON -> if (e.data2 > 0) {
                    val key = (bank[e.channel].toLong() shl 24) or (program[e.channel].toLong() shl 16) or (e.data1.toLong() shl 8) or e.data2.toLong()
                    if (seen.add(key)) font.regions(bank[e.channel], program[e.channel], e.data1, e.data2).forEach { samplesOf(it.sample) }
                }
            }
        }
    }

    /** Every note released at once (a pause, a jump): they fade out in their release time. */
    fun allNotesOff() {
        channels.forEach { it.sustain = false }
        voices.forEach { if (!it.released || it.sustained) it.release() }
    }

    /** Every note stopped within a few milliseconds, and the reverb tail with them. */
    fun silence() {
        channels.forEach { it.sustain = false }
        voices.forEach { it.cut() }
        reverb.clear()
    }

    /** Back to the start: programs, banks and controllers of every channel as a new file finds them. */
    fun reset() {
        for (c in 0 until 16) {
            channels[c].program = 0
            channels[c].bank = if (c == DRUM_CHANNEL) SoundFont.DRUM_BANK else 0
            resetControllers(c)
            channels[c].cc[7] = 100
            channels[c].cc[10] = 64
            channels[c].cc[91] = DEFAULT_REVERB_CC
        }
    }

    /** [frames] stereo frames (left, right) mixed into [out] from [offset], overwriting it. */
    fun render(out: FloatArray, offset: Int, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = min(CONTROL_FRAMES, frames - done)
            val at = offset + done * 2
            out.fill(0f, at, at + n * 2)
            reverbIn.fill(0f)
            // by index, from the end: no iterator made 1500 times a second on the audio thread
            for (i in voices.size - 1 downTo 0) {
                if (!voices[i].render(out, at, n, reverbIn)) voices.removeAt(i)
            }
            reverb.process(reverbIn, out, at, n)
            for (i in at until at + n * 2) out[i] = limit(out[i] * MASTER_GAIN)
            done += n
        }
    }

    // the soft limit: unchanged below it, then bent towards 1 without ever reaching it
    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= SOFT_LIMIT) return x
        val over = (a - SOFT_LIMIT) / (1 - SOFT_LIMIT)
        val bent = SOFT_LIMIT + (1 - SOFT_LIMIT) * over / (1 + over)
        return if (x < 0) -bent else bent
    }

    private fun resetControllers(channel: Int) {
        val ch = channels[channel]
        ch.cc[1] = 0
        ch.cc[11] = 127
        ch.cc[64] = 0
        ch.sustain = false
    }

    // the voices not fading out (given up already)
    private fun sounding(): Int {
        var n = 0
        for (i in voices.indices) if (!voices[i].cutting) n++
        return n
    }

    // The voice to give up for a new note, the quietest released one, else the oldest: it fades
    // out in a few ms (cut dead, it clicks). Too many fading at once: the oldest of them goes now.
    // By index, without a list made on the audio thread.
    private fun steal() {
        var victim: Voice? = null
        for (i in voices.indices) {
            val v = voices[i]
            if (v.cutting) continue
            val better = when {
                victim == null -> true
                v.released != victim.released -> v.released
                v.released -> v.level() < victim.level()
                else -> v.order < victim.order
            }
            if (better) victim = v
        }
        victim?.cut()
        if (voices.size >= voiceLimit + FADING_VOICES) {
            var oldest = -1
            for (i in voices.indices) if (voices[i].cutting && (oldest < 0 || voices[i].order < voices[oldest].order)) oldest = i
            if (oldest >= 0) voices.removeAt(oldest)
        }
    }

    // read once by the bank, ahead by [warm] off the audio thread
    private fun samplesOf(sample: SoundFont.Sample): ShortArray = font.valuesOf(sample)

    private inner class Voice(
        private val region: SoundFont.Region,
        val channel: Int,
        val key: Int,
        private val velocity: Int,
        val order: Long,
        private val ch: Channel,
    ) {
        private val gens = region.gens
        private val data = samplesOf(region.sample)
        val exclusive = gens[Gen.EXCLUSIVE_CLASS]
        var released = false
            private set
        var sustained = false

        // where the voice plays in [data] (the sample from its start, then the guard frames)
        private val start = gens[Gen.START_OFFSET] + 32768 * gens[Gen.START_COARSE_OFFSET]
        private val end = (data.size - SoundFont.GUARD_FRAMES + gens[Gen.END_OFFSET] + 32768 * gens[Gen.END_COARSE_OFFSET])
            .coerceIn(start + 1, max(start + 1, data.size - 1))
        private val loopStart = (region.sample.loopFrom + gens[Gen.LOOP_START_OFFSET] + 32768 * gens[Gen.LOOP_START_COARSE_OFFSET])
            .coerceIn(0, end)
        private val loopEnd = (region.sample.loopTo + gens[Gen.LOOP_END_OFFSET] + 32768 * gens[Gen.LOOP_END_COARSE_OFFSET])
            .coerceIn(loopStart, end)
        private val loopMode = gens[Gen.SAMPLE_MODES] and 3
        private var position = start.coerceIn(0, end).toDouble()

        // the values the modulators give, summed into the generators they drive
        private val mod = FloatArray(Gen.COUNT)

        private val noteKey = if (gens[Gen.KEYNUM] >= 0) gens[Gen.KEYNUM] else key
        private val rootKey = if (gens[Gen.OVERRIDING_ROOT_KEY] >= 0) gens[Gen.OVERRIDING_ROOT_KEY] else region.sample.rootKey

        private val volEnv = Envelope(volume = true)
        private val modEnv = Envelope(volume = false)
        private val modLfo = Lfo()
        private val vibLfo = Lfo()
        private val filter = LowPass()
        private var gain = 0f
        private var leftGain = 0f
        private var rightGain = 0f
        private var reverbSend = 0f
        var cutting = false
            private set

        init {
            controllersChanged()
            volEnv.start(
                g(Gen.DELAY_VOL_ENV), g(Gen.ATTACK_VOL_ENV),
                g(Gen.HOLD_VOL_ENV) + (60 - noteKey) * g(Gen.KEY_TO_VOL_ENV_HOLD),
                g(Gen.DECAY_VOL_ENV) + (60 - noteKey) * g(Gen.KEY_TO_VOL_ENV_DECAY),
                g(Gen.SUSTAIN_VOL_ENV), g(Gen.RELEASE_VOL_ENV),
            )
            modEnv.start(
                g(Gen.DELAY_MOD_ENV), g(Gen.ATTACK_MOD_ENV),
                g(Gen.HOLD_MOD_ENV) + (60 - noteKey) * g(Gen.KEY_TO_MOD_ENV_HOLD),
                g(Gen.DECAY_MOD_ENV) + (60 - noteKey) * g(Gen.KEY_TO_MOD_ENV_DECAY),
                g(Gen.SUSTAIN_MOD_ENV), g(Gen.RELEASE_MOD_ENV),
            )
            modLfo.start(g(Gen.DELAY_MOD_LFO), g(Gen.FREQ_MOD_LFO))
            vibLfo.start(g(Gen.DELAY_VIB_LFO), g(Gen.FREQ_VIB_LFO))
            gain = targetGain()
        }

        // a generator with what its modulators add
        private fun g(gen: Int): Float = gens[gen] + mod[gen]

        /** The modulators read the channel's controllers again. */
        fun controllersChanged() {
            mod.fill(0f)
            for (m in region.modulators) {
                if (m.destination >= Gen.COUNT) continue
                mod[m.destination] += m.amount * source(m.source) * source(m.amountSource, unset = 1f)
            }
            val pan = (g(Gen.PAN) / 1000f + 0.5f).coerceIn(0f, 1f)
            leftGain = cos(pan * PI.toFloat() / 2)
            rightGain = sin(pan * PI.toFloat() / 2)
            reverbSend = (g(Gen.REVERB_SEND) / 1000f).coerceIn(0f, 1f)
        }

        // a modulator source (specification 8.2), 0..1 or -1..1
        private fun source(code: Int, unset: Float = 0f): Float {
            val index = code and 0x7F
            val raw = if (code and 0x80 != 0) {
                ch.cc[index] / 127f
            } else {
                when (index) {
                    0 -> return unset
                    2 -> velocity / 127f
                    3 -> key / 127f
                    else -> return 0f
                }
            }
            var x = if (code and 0x100 != 0) 1f - raw else raw
            val bipolar = code and 0x200 != 0
            if (bipolar) x = 2 * x - 1
            val shape = { v: Float ->
                when (code shr 10 and 0x3F) {
                    1 -> concave(v)
                    2 -> 1f - concave(1f - v)
                    3 -> if (v >= 0.5f) 1f else 0f
                    else -> v
                }
            }
            return if (bipolar) (if (x >= 0) shape(x) else -shape(-x)) else shape(x)
        }

        private fun concave(x: Float): Float = if (x >= 1f) 1f else min(1f, (-(20f / 96f) * log10((1f - x) * (1f - x))))

        fun release() {
            if (released) return
            released = true
            sustained = false
            volEnv.release()
            modEnv.release()
        }

        /** Stops within a few milliseconds. */
        fun cut() {
            cutting = true
            release()
            volEnv.cut()
        }

        fun level(): Float = gain

        private fun targetGain(): Float {
            val attenuation = ATTENUATION_SCALE * g(Gen.INITIAL_ATTENUATION) + volEnv.attenuation + modLfo.value * g(Gen.MOD_LFO_TO_VOLUME)
            return volEnv.amplitude * 10f.pow(-attenuation / 200f)
        }

        // Adds [n] frames into [out] at [at], and its reverb send into [reverbIn]; false once silent.
        fun render(out: FloatArray, at: Int, n: Int, reverbIn: FloatArray): Boolean {
            val seconds = n.toFloat() / sampleRate
            volEnv.advance(seconds)
            modEnv.advance(seconds)
            modLfo.advance(seconds)
            vibLfo.advance(seconds)
            if (volEnv.done || (released && volEnv.attenuation >= RELEASED_SILENT_CB)) return false

            val cents = (noteKey - rootKey) * g(Gen.SCALE_TUNING) + g(Gen.COARSE_TUNE) * 100 + g(Gen.FINE_TUNE) +
                region.sample.correction + modEnv.value * g(Gen.MOD_ENV_TO_PITCH) +
                modLfo.value * g(Gen.MOD_LFO_TO_PITCH) + vibLfo.value * g(Gen.VIB_LFO_TO_PITCH)
            val step = 2.0.pow(cents / 1200.0) * region.sample.rate / sampleRate

            val fc = g(Gen.INITIAL_FILTER_FC) + modEnv.value * g(Gen.MOD_ENV_TO_FILTER_FC) + modLfo.value * g(Gen.MOD_LFO_TO_FILTER_FC)
            val q = g(Gen.INITIAL_FILTER_Q)
            val filtered = fc < FILTER_OPEN_CENTS || q > 0f
            if (filtered) filter.set(fc, q, sampleRate)

            val target = targetGain()
            val from = gain
            val ramp = (target - from) / n
            // The loop runs 48 000 times a second for every voice: what it reads is taken into
            // locals first (a field is read again at every turn on a slow tablet), the 16-bit
            // scale goes into the gains, and the loop's bounds are worked out once.
            val samples = data
            val last = samples.size - 1
            val stop = end
            val looping = (loopMode == 1 || (loopMode == 3 && !released)) && loopEnd > loopStart
            val loopTo = loopEnd.toDouble()
            val loopLength = (loopEnd - loopStart).toDouble()
            val left = leftGain / 32768f
            val right = rightGain / 32768f
            val send = reverbSend / 32768f
            var p = position
            for (i in 0 until n) {
                val ip = p.toInt()
                if (ip >= stop) {
                    position = p
                    gain = 0f
                    return false
                }
                // 4-point cubic (Hermite) interpolation: smoother highs than a straight line
                val t = (p - ip).toFloat()
                val y0 = samples[if (ip > 0) ip - 1 else 0].toFloat()
                val y1 = samples[ip].toFloat()
                val y2 = samples[if (ip < last) ip + 1 else last].toFloat()
                val y3 = samples[if (ip + 1 < last) ip + 2 else last].toFloat()
                val c1 = 0.5f * (y2 - y0)
                val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
                val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
                var s = ((c3 * t + c2) * t + c1) * t + y1
                if (filtered) s = filter.process(s)
                s *= from + ramp * i
                out[at + i * 2] += s * left
                out[at + i * 2 + 1] += s * right
                reverbIn[i] += s * send
                p += step
                if (looping && p >= loopTo) p -= loopLength
            }
            position = p
            gain = target
            return true
        }

        private inner class Envelope(private val volume: Boolean) {
            private var stage = 0
            private var time = 0f
            private var delay = 0f
            private var attack = 0f
            private var hold = 0f
            private var decay = 0f
            private var sustain = 0f
            private var releaseTime = 0f
            // the volume envelope: attenuation in cB (0 = full) and the attack's linear rise
            var attenuation = 0f
                private set
            var amplitude = 0f
                private set
            // the modulation envelope: 0..1
            var value = 0f
                private set
            var done = false
                private set

            fun start(delayTc: Float, attackTc: Float, holdTc: Float, decayTc: Float, sustainLevel: Float, releaseTc: Float) {
                delay = seconds(delayTc)
                attack = seconds(attackTc)
                hold = seconds(holdTc)
                decay = seconds(decayTc)
                releaseTime = seconds(releaseTc)
                sustain = if (volume) sustainLevel.coerceIn(0f, FULL_RANGE_CB) else 1f - (sustainLevel / 1000f).coerceIn(0f, 1f)
                stage = 0
                time = 0f
                amplitude = 0f
                attenuation = 0f
                value = 0f
            }

            fun release() {
                if (stage < RELEASE) {
                    stage = RELEASE
                    time = 0f
                    // the attack is linear in amplitude: carry on from where it is, in cB
                    if (volume && amplitude < 1f) {
                        attenuation = -200f * log10(max(amplitude, 1e-5f)) + attenuation
                        amplitude = 1f
                    }
                }
            }

            fun cut() {
                release()
                releaseTime = QUICK_RELEASE_S
            }

            fun advance(dt: Float) {
                time += dt
                when (stage) {
                    0 -> if (time >= delay) next()
                    1 -> {
                        val p = if (attack > 0f) time / attack else 1f
                        if (volume) amplitude = min(1f, p) else value = min(1f, p)
                        if (p >= 1f) next()
                    }
                    2 -> {
                        if (volume) amplitude = 1f else value = 1f
                        if (time >= hold) next()
                    }
                    3 -> {
                        if (volume) {
                            attenuation = min(sustain, FULL_RANGE_CB * time / max(decay, 1e-4f))
                            if (attenuation >= sustain) next()
                        } else {
                            value = max(sustain, 1f - time / max(decay, 1e-4f))
                            if (value <= sustain) next()
                        }
                    }
                    SUSTAIN -> Unit
                    RELEASE -> {
                        if (volume) {
                            attenuation += FULL_RANGE_CB * dt / max(releaseTime, 1e-4f)
                            if (attenuation >= SILENT_CB) done = true
                        } else {
                            value = max(0f, value - dt / max(releaseTime, 1e-4f))
                        }
                    }
                }
            }

            private fun next() {
                stage++
                time = 0f
            }
        }
    }

    // a triangle LFO, -1..1, starting after its delay
    private inner class Lfo {
        private var delay = 0f
        private var frequency = 0f
        private var time = 0f
        var value = 0f
            private set

        fun start(delayTc: Float, frequencyCents: Float) {
            delay = seconds(delayTc)
            frequency = 8.176f * 2f.pow(frequencyCents / 1200f)
            time = 0f
            value = 0f
        }

        fun advance(dt: Float) {
            time += dt
            if (time < delay) return
            val phase = ((time - delay) * frequency) % 1f
            value = if (phase < 0.25f) phase * 4 else if (phase < 0.75f) 2 - phase * 4 else phase * 4 - 4
        }
    }

    // a resonant low-pass filter (RBJ biquad), cut-off in absolute cents and resonance in cB
    private class LowPass {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f
        // what the coefficients were worked out for: most voices keep it from block to block
        private var setCents = Float.NaN
        private var setResonance = Float.NaN

        fun set(cents: Float, resonanceCb: Float, sampleRate: Int) {
            if (cents == setCents && resonanceCb == setResonance) return
            setCents = cents
            setResonance = resonanceCb
            val hz = (8.176f * 2f.pow(cents / 1200f)).coerceIn(20f, sampleRate * 0.45f)
            val q = max(0.7071f, 10f.pow(resonanceCb / 200f))
            val w = 2 * PI.toFloat() * hz / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            b0 = (1 - c) / 2 / a0
            b1 = (1 - c) / a0
            b2 = b0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = if (abs(y) < 1e-20f) 0f else y
            return y
        }
    }

    // A small Freeverb: eight combs and four all-passes, mono in, stereo out (the right side's
    // delays a little longer). It gives the bank's instruments the room they were made with.
    private class Reverb(sampleRate: Int) {
        private val scale = sampleRate / 44100f
        // arrays: a loop over a list makes an iterator, here 24 for each frame
        private val combsL = Array(COMBS.size) { Comb((COMBS[it] * scale).toInt()) }
        private val combsR = Array(COMBS.size) { Comb(((COMBS[it] + STEREO_SPREAD) * scale).toInt()) }
        private val passesL = Array(PASSES.size) { AllPass((PASSES[it] * scale).toInt()) }
        private val passesR = Array(PASSES.size) { AllPass(((PASSES[it] + STEREO_SPREAD) * scale).toInt()) }

        fun process(input: FloatArray, out: FloatArray, at: Int, n: Int) {
            for (i in 0 until n) {
                val x = input[i] * INPUT_GAIN
                var l = 0f
                var r = 0f
                for (c in combsL) l += c.process(x)
                for (c in combsR) r += c.process(x)
                for (p in passesL) l = p.process(l)
                for (p in passesR) r = p.process(r)
                out[at + i * 2] += l * WET
                out[at + i * 2 + 1] += r * WET
            }
        }

        fun clear() {
            (combsL + combsR).forEach { it.clear() }
            (passesL + passesR).forEach { it.clear() }
        }

        private class Comb(size: Int) {
            private val buffer = FloatArray(max(1, size))
            private var index = 0
            private var store = 0f

            fun process(x: Float): Float {
                val y = buffer[index]
                store = y * (1 - DAMP) + store * DAMP
                buffer[index] = x + store * FEEDBACK
                if (++index >= buffer.size) index = 0
                return y
            }

            fun clear() {
                buffer.fill(0f)
                store = 0f
            }
        }

        private class AllPass(size: Int) {
            private val buffer = FloatArray(max(1, size))
            private var index = 0

            fun process(x: Float): Float {
                val b = buffer[index]
                buffer[index] = x + b * 0.5f
                if (++index >= buffer.size) index = 0
                return b - x
            }

            fun clear() = buffer.fill(0f)
        }

        companion object {
            private val COMBS = listOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            private val PASSES = listOf(556, 441, 341, 225)
            private const val STEREO_SPREAD = 23
            private const val INPUT_GAIN = 0.015f
            private const val FEEDBACK = 0.84f
            private const val DAMP = 0.2f
            private const val WET = 1f
        }
    }

    companion object {
        /** General MIDI's drum channel (10), counted from 0. */
        const val DRUM_CHANNEL = 9

        private const val RELEASE = 5
        private const val SUSTAIN = 4

        // timecents to seconds; -12000 and below is no time at all
        private fun seconds(timecents: Float): Float = if (timecents <= -12000f) 0f else 2f.pow(timecents / 1200f)
    }
}
