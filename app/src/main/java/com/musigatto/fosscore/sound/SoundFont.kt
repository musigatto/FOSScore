package com.musigatto.fosscore.sound

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * A SoundFont bank, read from its bytes: the presets (one per General MIDI program and bank),
 * their instruments, and the samples, read in place (a mapped file is never copied). SoundFont 2
 * keeps its samples as 16-bit values; SF3 (MuseScore's) keeps each one as an Ogg Vorbis stream, a
 * few times smaller, which [decoder] turns back into values the first time a note plays it.
 *
 * [regions] gives what one note plays: each sample zone of the preset that covers the key and
 * the velocity, with its generators already summed (instrument values plus preset offsets, as
 * the SoundFont 2.04 specification says) and its modulators.
 */
class SoundFont private constructor(
    // the smpl chunk: 16-bit values, or Ogg Vorbis streams one after the other (SF3)
    private val data: ByteBuffer,
    private val presets: Map<Int, Preset>,
) {
    /**
     * Turns sample [index]'s Ogg Vorbis stream into 16-bit values (SF3 banks); without it, their
     * notes are silent. Called from any thread.
     */
    @Volatile var decoder: ((index: Int, ogg: ByteBuffer) -> ShortArray)? = null

    /**
     * One sample as the bank describes it. [start] and [end] are frames into the data, or byte
     * offsets of its Ogg stream when [compressed]; [loopFrom] and [loopTo] are frames from the
     * sample's own start in both cases.
     */
    class Sample(
        val index: Int,
        val start: Int,
        val end: Int,
        val loopFrom: Int,
        val loopTo: Int,
        val rate: Int,
        val rootKey: Int,
        val correction: Int,
        val compressed: Boolean,
    )

    // the samples read so far, for every synth playing the bank
    private val values = ConcurrentHashMap<Sample, ShortArray>()

    /** [sample]'s values ([read]), read once and kept. Safe on any thread. */
    fun valuesOf(sample: Sample): ShortArray = values.getOrPut(sample) { read(sample) }

    /**
     * [sample]'s values, followed by [GUARD_FRAMES] frames for the interpolation past its end (the
     * specification keeps them in the file; a decoded sample gets them as silence).
     */
    fun read(sample: Sample): ShortArray {
        if (sample.compressed) {
            val from = sample.start.coerceIn(0, data.limit())
            val to = sample.end.coerceIn(from, data.limit())
            val ogg = data.duplicate().apply {
                position(from)
                limit(to)
            }.slice()
            val pcm = decoder?.invoke(sample.index, ogg) ?: return ShortArray(GUARD_FRAMES)
            return pcm.copyOf(pcm.size + GUARD_FRAMES)
        }
        val values = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val from = sample.start.coerceIn(0, values.limit())
        val to = (sample.end + GUARD_FRAMES).coerceIn(from, values.limit())
        val out = ShortArray(to - from)
        values.position(from)
        values.get(out)
        return out
    }

    /** What a note plays: a [sample] and its generators ([Gen]), and its [modulators]. */
    class Region(val sample: Sample, val gens: IntArray, val modulators: List<Modulator>)

    /** A modulator: a source (and an amount source) driving a generator by [amount]. */
    data class Modulator(val source: Int, val destination: Int, val amount: Int, val amountSource: Int, val transform: Int) {
        // two modulators are the same one when they differ only by their amount (spec 9.5.1)
        fun sameAs(other: Modulator) =
            source == other.source && destination == other.destination && amountSource == other.amountSource && transform == other.transform
    }

    private class Zone(
        val keyLo: Int,
        val keyHi: Int,
        val velLo: Int,
        val velHi: Int,
        // generator values, and which ones the zone sets
        val gens: IntArray,
        val set: BooleanArray,
        val modulators: List<Modulator>,
        val sample: Sample?,
        val instrument: Instrument?,
    ) {
        fun covers(key: Int, velocity: Int) = key in keyLo..keyHi && velocity in velLo..velHi
    }

    private class Instrument(val global: Zone?, val zones: List<Zone>)

    private class Preset(val global: Zone?, val zones: List<Zone>)

    /** Whether the bank has [program] (0-127) in [bank]. */
    fun has(bank: Int, program: Int) = presets.containsKey(bank shl 7 or program)

    /**
     * What [key] at [velocity] plays on [program] of [bank]; the bank's first preset of that
     * program, then piano, when the bank does not have it.
     */
    fun regions(bank: Int, program: Int, key: Int, velocity: Int): List<Region> {
        val preset = presets[bank shl 7 or program]
            ?: presets[(if (bank == DRUM_BANK) DRUM_BANK else 0) shl 7 or program]
            ?: presets[0] ?: return emptyList()
        val out = ArrayList<Region>()
        for (pz in preset.zones) {
            if (!pz.covers(key, velocity)) continue
            val instrument = pz.instrument ?: continue
            for (iz in instrument.zones) {
                if (!iz.covers(key, velocity)) continue
                val sample = iz.sample ?: continue
                val gens = IntArray(Gen.COUNT) { g ->
                    val own = when {
                        iz.set[g] -> iz.gens[g]
                        instrument.global?.set?.get(g) == true -> instrument.global.gens[g]
                        else -> Gen.DEFAULTS[g]
                    }
                    // a preset adds to what the instrument says, except for what is not a sound value
                    val added = if (g in Gen.NOT_ADDED) 0 else when {
                        pz.set[g] -> pz.gens[g]
                        preset.global?.set?.get(g) == true -> preset.global.gens[g]
                        else -> 0
                    }
                    own + added
                }
                val mods = ArrayList(DEFAULT_MODULATORS)
                for (m in (instrument.global?.modulators.orEmpty()) + iz.modulators) {
                    val same = mods.indexOfFirst { it.sameAs(m) }
                    if (same >= 0) mods[same] = m else mods += m
                }
                // a preset's modulators add to the instrument's
                mods += preset.global?.modulators.orEmpty()
                mods += pz.modulators
                out += Region(sample, gens, mods)
            }
        }
        return out
    }

    companion object {
        /** The drums: bank 128, as General MIDI channel 10 plays. */
        const val DRUM_BANK = 128

        /** The frames kept after every sample's end. */
        const val GUARD_FRAMES = 46

        // in a sample's type, the SF3 flag: its data is an Ogg Vorbis stream
        private const val OGG_VORBIS = 0x10

        // the specification's default modulators (8.4) that a MIDI file can drive
        private val DEFAULT_MODULATORS = listOf(
            // velocity: softer notes are quieter...
            Modulator(0x0502, Gen.INITIAL_ATTENUATION, 960, 0, 0),
            // ...and darker
            Modulator(0x0102, Gen.INITIAL_FILTER_FC, -2400, 0, 0),
            // CC 7 volume and CC 11 expression
            Modulator(0x0587, Gen.INITIAL_ATTENUATION, 960, 0, 0),
            Modulator(0x058B, Gen.INITIAL_ATTENUATION, 960, 0, 0),
            // CC 10 pan
            Modulator(0x028A, Gen.PAN, 1000, 0, 0),
            // CC 91 reverb and CC 93 chorus sends
            Modulator(0x00DB, Gen.REVERB_SEND, 200, 0, 0),
            Modulator(0x00DD, Gen.CHORUS_SEND, 200, 0, 0),
        )

        /** Reads the bank in [bytes] (the whole .sf2 file). */
        fun read(bytes: ByteBuffer): SoundFont {
            val b = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            require(b.limit() >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "sfbk") { "not a SoundFont 2 bank" }
            val chunks = HashMap<String, Pair<Int, Int>>()
            // the LIST chunks hold the ones we need: smpl in sdta, the tables in pdta
            var at = 12
            while (at + 8 <= b.limit()) {
                val size = b.getInt(at + 4)
                if (tag(b, at) == "LIST") {
                    var inner = at + 12
                    val end = at + 8 + size
                    while (inner + 8 <= end) {
                        val innerSize = b.getInt(inner + 4)
                        chunks[tag(b, inner)] = (inner + 8) to innerSize
                        inner += 8 + innerSize + (innerSize and 1)
                    }
                }
                at += 8 + size + (size and 1)
            }
            fun chunk(name: String) = chunks[name] ?: throw IllegalArgumentException("SoundFont without $name")
            val (smplAt, smplSize) = chunk("smpl")
            val data = b.duplicate().apply {
                position(smplAt)
                limit(smplAt + smplSize)
            }.slice()

            val shdr = chunk("shdr")
            val sampleList = List(shdr.second / 46 - 1) { i ->
                val r = shdr.first + i * 46
                val start = b.getInt(r + 20)
                val compressed = b.getShort(r + 44).toInt() and OGG_VORBIS != 0
                // SF3 writes the loop from the sample's start already; SoundFont 2 from the data's
                val base = if (compressed) 0 else start
                Sample(
                    index = i, start = start, end = b.getInt(r + 24),
                    loopFrom = b.getInt(r + 28) - base, loopTo = b.getInt(r + 32) - base,
                    rate = b.getInt(r + 36), rootKey = b.get(r + 40).toInt() and 0xFF, correction = b.get(r + 41).toInt(),
                    compressed = compressed,
                )
            }

            fun gens(name: String) = chunk(name).let { (start, size) ->
                List(size / 4) { i -> (b.getShort(start + i * 4).toInt() and 0xFFFF) to b.getShort(start + i * 4 + 2) }
            }
            fun mods(name: String) = chunk(name).let { (start, size) ->
                List(size / 10) { i ->
                    val r = start + i * 10
                    Modulator(
                        b.getShort(r).toInt() and 0xFFFF, b.getShort(r + 2).toInt() and 0xFFFF, b.getShort(r + 4).toInt(),
                        b.getShort(r + 6).toInt() and 0xFFFF, b.getShort(r + 8).toInt() and 0xFFFF,
                    )
                }
            }
            fun bags(name: String) = chunk(name).let { (start, size) ->
                List(size / 4) { i -> (b.getShort(start + i * 4).toInt() and 0xFFFF) to (b.getShort(start + i * 4 + 2).toInt() and 0xFFFF) }
            }

            // a zone: its generators, from bag i to bag i + 1; the last generator says what it plays
            fun zones(
                bagList: List<Pair<Int, Int>>, genList: List<Pair<Int, Short>>, modList: List<Modulator>,
                firstBag: Int, endBag: Int, target: Int, link: (Int) -> Any?,
            ): Pair<Zone?, List<Zone>> {
                var global: Zone? = null
                val zones = ArrayList<Zone>()
                for (bag in firstBag until endBag) {
                    if (bag + 1 >= bagList.size) break
                    val values = IntArray(Gen.COUNT)
                    val set = BooleanArray(Gen.COUNT)
                    var keyLo = 0
                    var keyHi = 127
                    var velLo = 0
                    var velHi = 127
                    var linked: Any? = null
                    for (g in bagList[bag].first until bagList[bag + 1].first) {
                        val (oper, amount) = genList.getOrNull(g) ?: break
                        when (oper) {
                            Gen.KEY_RANGE -> { keyLo = amount.toInt() and 0xFF; keyHi = amount.toInt() shr 8 and 0xFF }
                            Gen.VEL_RANGE -> { velLo = amount.toInt() and 0xFF; velHi = amount.toInt() shr 8 and 0xFF }
                            target -> linked = link(amount.toInt() and 0xFFFF)
                            else -> if (oper < Gen.COUNT) {
                                values[oper] = amount.toInt()
                                set[oper] = true
                            }
                        }
                    }
                    val mods = (bagList[bag].second until bagList[bag + 1].second).mapNotNull { modList.getOrNull(it) }
                    val zone = Zone(keyLo, keyHi, velLo, velHi, values, set, mods, linked as? Sample, linked as? Instrument)
                    // a first zone that plays nothing is the global one: the others start from it
                    if (linked == null) {
                        if (bag == firstBag) global = zone
                    } else {
                        zones += zone
                    }
                }
                return global to zones
            }

            val ibag = bags("ibag")
            val igen = gens("igen")
            val imod = mods("imod")
            val inst = chunk("inst")
            val instruments = List(inst.second / 22 - 1) { i ->
                val r = inst.first + i * 22
                val from = b.getShort(r + 20).toInt() and 0xFFFF
                val to = b.getShort(r + 42).toInt() and 0xFFFF
                val (global, zones) = zones(ibag, igen, imod, from, to, Gen.SAMPLE_ID) { sampleList.getOrNull(it) }
                Instrument(global, zones)
            }

            val pbag = bags("pbag")
            val pgen = gens("pgen")
            val pmod = mods("pmod")
            val phdr = chunk("phdr")
            val presets = HashMap<Int, Preset>()
            for (i in 0 until phdr.second / 38 - 1) {
                val r = phdr.first + i * 38
                val program = b.getShort(r + 20).toInt() and 0xFFFF
                val bank = b.getShort(r + 22).toInt() and 0xFFFF
                val from = b.getShort(r + 24).toInt() and 0xFFFF
                val to = b.getShort(r + 38 + 24).toInt() and 0xFFFF
                val (global, zones) = zones(pbag, pgen, pmod, from, to, Gen.INSTRUMENT) { instruments.getOrNull(it) }
                // the first preset of a bank and program wins (HashMap.putIfAbsent needs API 24)
                presets.getOrPut(bank shl 7 or program) { Preset(global, zones) }
            }
            return SoundFont(data, presets)
        }

        private fun tag(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.ISO_8859_1)
    }
}

/** The SoundFont 2 generators the synth reads (specification 8.1.2), and their defaults. */
object Gen {
    const val START_OFFSET = 0
    const val END_OFFSET = 1
    const val LOOP_START_OFFSET = 2
    const val LOOP_END_OFFSET = 3
    const val START_COARSE_OFFSET = 4
    const val MOD_LFO_TO_PITCH = 5
    const val VIB_LFO_TO_PITCH = 6
    const val MOD_ENV_TO_PITCH = 7
    const val INITIAL_FILTER_FC = 8
    const val INITIAL_FILTER_Q = 9
    const val MOD_LFO_TO_FILTER_FC = 10
    const val MOD_ENV_TO_FILTER_FC = 11
    const val END_COARSE_OFFSET = 12
    const val MOD_LFO_TO_VOLUME = 13
    const val CHORUS_SEND = 15
    const val REVERB_SEND = 16
    const val PAN = 17
    const val DELAY_MOD_LFO = 21
    const val FREQ_MOD_LFO = 22
    const val DELAY_VIB_LFO = 23
    const val FREQ_VIB_LFO = 24
    const val DELAY_MOD_ENV = 25
    const val ATTACK_MOD_ENV = 26
    const val HOLD_MOD_ENV = 27
    const val DECAY_MOD_ENV = 28
    const val SUSTAIN_MOD_ENV = 29
    const val RELEASE_MOD_ENV = 30
    const val KEY_TO_MOD_ENV_HOLD = 31
    const val KEY_TO_MOD_ENV_DECAY = 32
    const val DELAY_VOL_ENV = 33
    const val ATTACK_VOL_ENV = 34
    const val HOLD_VOL_ENV = 35
    const val DECAY_VOL_ENV = 36
    const val SUSTAIN_VOL_ENV = 37
    const val RELEASE_VOL_ENV = 38
    const val KEY_TO_VOL_ENV_HOLD = 39
    const val KEY_TO_VOL_ENV_DECAY = 40
    const val INSTRUMENT = 41
    const val KEY_RANGE = 43
    const val VEL_RANGE = 44
    const val LOOP_START_COARSE_OFFSET = 45
    const val KEYNUM = 46
    const val VELOCITY = 47
    const val INITIAL_ATTENUATION = 48
    const val LOOP_END_COARSE_OFFSET = 50
    const val COARSE_TUNE = 51
    const val FINE_TUNE = 52
    const val SAMPLE_ID = 53
    const val SAMPLE_MODES = 54
    const val SCALE_TUNING = 56
    const val EXCLUSIVE_CLASS = 57
    const val OVERRIDING_ROOT_KEY = 58
    const val COUNT = 61

    // timecents of "no time at all", the default of every envelope and LFO delay and stage
    private const val INSTANT = -12000

    val DEFAULTS = IntArray(COUNT).apply {
        this[INITIAL_FILTER_FC] = 13500
        for (g in listOf(DELAY_MOD_LFO, DELAY_VIB_LFO, DELAY_MOD_ENV, ATTACK_MOD_ENV, HOLD_MOD_ENV, DECAY_MOD_ENV,
            RELEASE_MOD_ENV, DELAY_VOL_ENV, ATTACK_VOL_ENV, HOLD_VOL_ENV, DECAY_VOL_ENV, RELEASE_VOL_ENV)) this[g] = INSTANT
        this[KEYNUM] = -1
        this[VELOCITY] = -1
        this[SCALE_TUNING] = 100
        this[OVERRIDING_ROOT_KEY] = -1
    }

    // what a preset does not add to its instrument: what says which note, sample or loop
    val NOT_ADDED = setOf(
        START_OFFSET, END_OFFSET, LOOP_START_OFFSET, LOOP_END_OFFSET, START_COARSE_OFFSET, END_COARSE_OFFSET,
        LOOP_START_COARSE_OFFSET, LOOP_END_COARSE_OFFSET, KEYNUM, VELOCITY, SAMPLE_ID, SAMPLE_MODES,
        EXCLUSIVE_CLASS, OVERRIDING_ROOT_KEY, INSTRUMENT,
    )
}
