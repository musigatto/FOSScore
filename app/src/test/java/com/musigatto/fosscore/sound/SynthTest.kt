package com.musigatto.fosscore.sound

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthTest {

    // the app's own bank (SF3), read from the sources as the app maps it from the APK
    private val bank: SoundFont by lazy {
        val file = File("src/main/assets/soundfont/GeneralUser-GS.sf3")
        RandomAccessFile(file, "r").use { f -> SoundFont.read(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())) }
    }

    // A SoundFont 2 bank of one piano, built here: a looped 440 Hz tone with its root on A4.
    // Android's Vorbis decoder, which the app's SF3 bank needs, is not on the JVM.
    private val font: SoundFont by lazy {
        val frames = 4410
        val tone = ShortArray(frames + SoundFont.GUARD_FRAMES) { (sin(2 * PI * 440 * it / 44100) * 16000).toInt().toShort() }
        fun chunk(tag: String, body: ByteArray): ByteArray =
            ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).put(tag.toByteArray()).putInt(body.size).put(body).array()
        fun list(tag: String, vararg chunks: ByteArray) = chunk("LIST", tag.toByteArray() + chunks.fold(ByteArray(0)) { a, c -> a + c })
        fun record(size: Int, fill: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()
        fun name(text: String) = text.toByteArray().copyOf(20)
        val smpl = ByteBuffer.allocate(tone.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(tone) }.array()
        val pdta = list(
            "pdta",
            chunk("phdr", record(38) { put(name("Piano")); putShort(0); putShort(0); putShort(0) } + record(38) { put(name("EOP")); putShort(0); putShort(0); putShort(1) }),
            chunk("pbag", record(4) { putShort(0); putShort(0) } + record(4) { putShort(1); putShort(0) }),
            chunk("pmod", ByteArray(10)),
            chunk("pgen", record(4) { putShort(Gen.INSTRUMENT.toShort()); putShort(0) } + ByteArray(4)),
            chunk("inst", record(22) { put(name("Tone")); putShort(0) } + record(22) { put(name("EOI")); putShort(1) }),
            chunk("ibag", record(4) { putShort(0); putShort(0) } + record(4) { putShort(2); putShort(0) }),
            chunk("imod", ByteArray(10)),
            chunk("igen", record(4) { putShort(Gen.SAMPLE_MODES.toShort()); putShort(1) } +
                record(4) { putShort(Gen.SAMPLE_ID.toShort()); putShort(0) } + ByteArray(4)),
            chunk("shdr", record(46) {
                put(name("Tone")); putInt(0); putInt(frames); putInt(100); putInt(frames - 100); putInt(44100)
                put(69.toByte()); put(0); putShort(0); putShort(1)
            } + ByteArray(46)),
        )
        val body = "sfbk".toByteArray() + list("INFO", chunk("ifil", record(4) { putShort(2); putShort(1) })) + list("sdta", chunk("smpl", smpl)) + pdta
        SoundFont.read(ByteBuffer.wrap(chunk("RIFF", body)))
    }

    private fun peak(out: FloatArray) = out.maxOf { abs(it) }

    @Test
    fun theBankHasThePianoAndTheDrumsInOggVorbis() {
        assertTrue(bank.has(0, 0))
        assertTrue(bank.has(SoundFont.DRUM_BANK, 0))
        val piano = bank.regions(0, 0, 60, 100)
        assertTrue(piano.isNotEmpty())
        assertTrue(piano.all { it.sample.compressed })
        // a snare on channel 10
        assertTrue(bank.regions(SoundFont.DRUM_BANK, 0, 38, 100).isNotEmpty())
        // without a decoder (Android's) a compressed sample is silent, not a crash
        assertEquals(SoundFont.GUARD_FRAMES, bank.read(piano.first().sample).size)
    }

    @Test
    fun aNoteSoundsThenFadesAfterItsRelease() {
        val synth = Synth(font, 44100)
        val out = FloatArray(44100 * 2)
        synth.noteOn(0, 60, 100)
        synth.render(out, 0, 22050)
        assertTrue("a C4 is heard", peak(out) > 0.02f)
        assertTrue(peak(out) <= 1f)
        synth.noteOff(0, 60)
        repeat(10) { synth.render(out, 0, 44100) }
        assertEquals("after its release the voice is gone", 0, synth.activeVoices)
    }

    @Test
    fun aVoiceGivenUpForANewNoteFadesInsteadOfStoppingDead() {
        // past the voices it can hold, the synth gives up the oldest one: cut dead it clicks (the
        // crackle heard with a bank of many voices, 2026-10-28); it fades out in a few ms instead
        val synth = Synth(font, 44100, maxVoices = 2)
        val out = FloatArray(4410 * 2)
        for (key in listOf(60, 64, 67)) synth.noteOn(0, key, 100)
        assertEquals("the voice given up still sounds, fading", 3, synth.activeVoices)
        synth.render(out, 0, 4410)
        assertEquals("then it is gone", 2, synth.activeVoices)
    }

    @Test
    fun theVoicesHeldFollowTheLoadOfTheTablet() {
        // a slow tablet cannot render every voice of a dense piece in time: past 75 % of the
        // time, the synth holds fewer voices, and more again once there is room
        val budget = VoiceBudget(max = 48, min = 16)
        assertEquals(48, budget.limit)
        repeat(20) { budget.update(load = 1.1f) }
        assertTrue(budget.limit < 48)
        assertTrue(budget.limit >= 16)
        repeat(200) { budget.update(load = 0.3f) }
        assertEquals(48, budget.limit)
    }

    @Test
    fun aSofterNoteIsQuieter() {
        fun level(velocity: Int): Float {
            val synth = Synth(font, 44100)
            val out = FloatArray(8820 * 2)
            synth.noteOn(0, 60, velocity)
            synth.render(out, 0, 8820)
            return peak(out)
        }
        assertTrue(level(40) < level(110))
    }

    @Test
    fun aFullChordDoesNotClip() {
        val synth = Synth(font, 44100)
        val out = FloatArray(22050 * 2)
        for (key in listOf(36, 48, 55, 60, 64, 67, 72, 76)) synth.noteOn(0, key, 127)
        synth.render(out, 0, 22050)
        val clipped = out.count { abs(it) >= 0.999f }
        assertTrue("$clipped samples clipped, peak ${peak(out)}", clipped < 20)
    }

    @Test
    fun aMidiFileIsReadInQuarterNotes() {
        // format 0, 480 ticks per quarter: ♩ = 60, a C4 for one quarter, then an E4 one quarter later
        val track = byteArrayOf(
            0x00, 0xFF.toByte(), 0x51, 0x03, 0x0F, 0x42, 0x40,
            0x00, 0xC0.toByte(), 0x00,
            0x00, 0x90.toByte(), 60, 100,
            0x83.toByte(), 0x60, 0x80.toByte(), 60, 0,
            0x83.toByte(), 0x60, 0x90.toByte(), 64, 90,
            0x83.toByte(), 0x60, 0x90.toByte(), 64, 0,
            0x00, 0xFF.toByte(), 0x2F, 0x00,
        )
        val midi = byteArrayOf(
            'M'.code.toByte(), 'T'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(), 0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte(),
            'M'.code.toByte(), 'T'.code.toByte(), 'r'.code.toByte(), 'k'.code.toByte(), 0, 0, 0, track.size.toByte(),
        ) + track
        val song = MidiSong.read(midi)
        assertEquals(1_000_000, song.microsPerQuarter(0.0))
        assertEquals(listOf(0.0, 0.0, 1.0, 2.0, 3.0), song.events.map { it.quarters })
        assertEquals(3.0, song.length, 1e-9)
        assertEquals(3, song.firstEventAt(1.5))
    }
}
