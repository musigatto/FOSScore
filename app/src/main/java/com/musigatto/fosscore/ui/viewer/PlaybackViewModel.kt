package com.musigatto.fosscore.ui.viewer

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.musicxml.Playback
import com.musigatto.fosscore.musicxml.SoundSpec
import com.musigatto.fosscore.score.MusicXmlPages
import com.musigatto.fosscore.score.ScorePrecompute
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "FOSScore-Playback"

// how often the cursor and the loop look at the player: under a sixteenth note at ♩ = 200
private const val FOLLOW_MS = 40L

// the musicxml module accepts tempo factors in this range (VerovioOptions)
const val MIN_TEMPO_FACTOR = 0.2f
const val MAX_TEMPO_FACTOR = 4.0f

// MIDI's own default, when the score writes no tempo
const val DEFAULT_BPM = 120.0

// a held − / + of the tempo repeats; the music takes the new tempo once the taps settle
private const val SETTLE_MS = 200L

// playback of a newly laid out score is made ready once its first pages are drawn
private const val WARM_DELAY_MS = 1000L

// a new tempo or new parts heard take over on a barline at least this far ahead, started this
// much early (a MIDI player takes a moment to sound), the old player being watched at this pace
// and then fading out in a few steps
private const val SWITCH_LEAD_MS = 150L
private const val START_LATENCY_MS = 40L
private const val SWITCH_POLL_MS = 5L
private const val FADE_MS = 40L
private const val FADE_STEPS = 4

enum class PlayState { STOPPED, PREPARING, PLAYING, PAUSED }

/**
 * The loop, as in common players (forScore, MuseScore, media players): [on] repeats a section,
 * from [a] to [b] in quarter notes. A marker left unset is the start or the end of the piece, so
 * with none the whole piece loops. The markers stay when the loop is turned off.
 */
data class Loop(val on: Boolean = false, val a: Double? = null, val b: Double? = null) {
    /** Where the section starts. */
    val start: Double get() = a ?: 0.0

    fun toggle() = copy(on = !on)

    /** A tap on A with the music at [quarters]: marks the start there, or clears it. */
    fun tapA(quarters: Double): Loop = if (a != null) copy(a = null) else ordered(copy(a = quarters))

    /** A tap on B with the music at [quarters]: marks the end there, or clears it. */
    fun tapB(quarters: Double): Loop = if (b != null) copy(b = null) else ordered(copy(b = quarters))

    /** Where ⏮ goes: the start of the section while looping, else the start of the piece. */
    fun restartAt(): Double = if (on) start else 0.0

    // a marker on the other side of the other one swaps them; on the same spot it is not set
    private fun ordered(next: Loop): Loop = when {
        next.a == null || next.b == null -> next
        next.a == next.b -> this
        next.a > next.b -> next.copy(a = next.b, b = next.a)
        else -> next
    }
}

/** Beats per minute shown for a tempo [factor] of the score's [writtenBpm]. */
fun bpmOf(factor: Float, writtenBpm: Double): Int = (writtenBpm * factor).roundToInt()

/** The tempo factor for [bpm], within what the module accepts. */
fun factorOf(bpm: Int, writtenBpm: Double): Float =
    (bpm / writtenBpm).toFloat().coerceIn(MIN_TEMPO_FACTOR, MAX_TEMPO_FACTOR)

/**
 * The parts heard: the ones picked by the user, or the ones shown until the user picks. Never
 * empty while the score has parts, so the music never goes silent by accident.
 */
fun heardParts(picked: Set<String>?, shown: Set<String>, parts: List<Part>): Set<String> {
    val ids = parts.map { it.id }.toSet()
    return (picked?.filter { it in ids }?.toSet() ?: shown).ifEmpty { ids }
}

/**
 * MusicXML playback: the MIDI the musicxml module writes for the score, played by Android's own
 * MIDI player, and the notes being played for the viewer's cursor and page turns. Tempo and
 * parts heard are saved per score.
 *
 * ponytail: tempo and parts heard live in the app settings by file, like the layout
 * (ScoreLayoutStore), until the per-score configurations table exists (roadmap 3.4).
 */
class PlaybackViewModel(app: Application, private val score: String) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    private val _state = MutableStateFlow(PlayState.STOPPED)
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val _loop = MutableStateFlow(Loop())
    val loop: StateFlow<Loop> = _loop.asStateFlow()

    // ids of the notes that started last: the cursor, and what the page turns follow
    private val _cursor = MutableStateFlow<List<String>>(emptyList())
    val cursor: StateFlow<List<String>> = _cursor.asStateFlow()

    private val _tempo = MutableStateFlow(Settings.tempo(context, score))
    val tempo: StateFlow<Float> = _tempo.asStateFlow()

    // null: the parts shown are the parts heard
    private val _heard = MutableStateFlow(Settings.heardParts(context, score))
    val heard: StateFlow<Set<String>?> = _heard.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    // bumped by ⏮ before anything was played: the viewer shows the first page
    private val _toFirstPage = MutableStateFlow(0)
    val toFirstPage: StateFlow<Int> = _toFirstPage.asStateFlow()

    /** Set by a page turned by hand while playing: the pages stop following until the music is on screen again. */
    var followPaused = false
        private set

    private var pages: MusicXmlPages? = null
    private var shownParts: Set<String> = emptySet()
    private var attachedFor: Any? = null
    private var player: MediaPlayer? = null
    private var playback: Playback? = null
    // the last playback of this layout, kept after a stop: ⏮ can still show where it goes
    private var lastPlayback: Playback? = null
    private var starting: Job? = null
    private var settling: Job? = null
    private var following: Job? = null
    private var warming: Job? = null
    private var position = 0.0
    // where ▶ starts next, when it is not "the page on screen": after ⏮, or a stop in a loop
    private var resumeAt: Double? = null
    // the MIDI files alternate: the one playing stays untouched while the next is written
    private var midiFiles = 0

    /**
     * The score to play, with the [shown] parts. [loaded] stands for what the score was loaded
     * with (transposition, parts, lyrics): a new one has new note ids, so the music stops. A new
     * size or a turned screen keeps the ids, and the music goes on.
     */
    fun attach(pages: MusicXmlPages?, shown: Set<String>, loaded: Any) {
        if (pages !== this.pages || loaded != attachedFor) {
            stop()
            lastPlayback = null
            resumeAt = null
            warming?.cancel()
            warming = pages?.let {
                viewModelScope.launch {
                    delay(WARM_DELAY_MS)
                    it.preparePlayback()
                }
            }
        }
        this.pages = pages
        shownParts = shown
        attachedFor = loaded
    }

    /**
     * ▶ / ⏸. From a pause it goes on; otherwise it starts where ⏮ put it, at the start of the
     * loop, or at [onScreen], the first notes of the page shown, so the music starts where the
     * eyes are.
     */
    fun playPause(onScreen: Set<String>) {
        when (_state.value) {
            PlayState.PLAYING -> pause()
            PlayState.PAUSED -> resume()
            PlayState.PREPARING -> Unit
            PlayState.STOPPED -> start(onScreen)
        }
    }

    /**
     * ⏮: back to the start of the loop, or of the piece. Playing, the music goes on from there;
     * paused or stopped, the page and the highlight go there at once, and ▶ starts there.
     */
    fun restart() {
        val at = _loop.value.restartAt()
        position = at
        followPaused = false
        val pb = player?.let { playback } ?: lastPlayback
        player?.seekTo(pb?.msExact(at)?.toInt() ?: 0)
        if (player == null) resumeAt = at
        if (pb != null) showNotesAt(pb, at) else _toFirstPage.value++
    }

    fun toggleLoop() {
        _loop.value = _loop.value.toggle()
    }

    fun tapA() {
        _loop.value = _loop.value.tapA(position)
    }

    fun tapB() {
        _loop.value = _loop.value.tapB(position)
    }

    fun setTempo(factor: Float) {
        val f = factor.coerceIn(MIN_TEMPO_FACTOR, MAX_TEMPO_FACTOR)
        if (f == _tempo.value) return
        _tempo.value = f
        Settings.setTempo(context, score, f)
        soundChanged()
    }

    fun setHeard(id: String, heard: Boolean, parts: List<Part>) {
        val now = heardParts(_heard.value, shownParts, parts)
        val next = if (heard) now + id else now - id
        if (next.isEmpty()) return
        _heard.value = next
        Settings.setHeardParts(context, score, next)
        soundChanged()
    }

    /** A page turned by hand during playback: let the reader look ahead or back. */
    fun pageTurnedByHand() {
        if (_state.value == PlayState.PLAYING) followPaused = true
    }

    /** The music reached the page on screen again: the pages follow it again. */
    fun musicOnScreen() {
        followPaused = false
    }

    fun stop() {
        starting?.cancel()
        starting = null
        settling?.cancel()
        settling = null
        following?.cancel()
        following = null
        player?.release()
        player = null
        playback = null
        followPaused = false
        _cursor.value = emptyList()
        _state.value = PlayState.STOPPED
    }

    init {
        // working ahead could be heard as hiccups in the music
        viewModelScope.launch { _state.collect { ScorePrecompute.playing = it == PlayState.PLAYING } }
    }

    override fun onCleared() {
        stop()
        ScorePrecompute.playing = false
    }

    private fun pause() {
        player?.pause()
        _state.value = PlayState.PAUSED
    }

    private fun resume() {
        player?.start()
        _state.value = PlayState.PLAYING
        follow()
    }

    private fun sound() = SoundSpec(audible = _heard.value, tempo = _tempo.value.toDouble())

    // the highlight and the page go to the notes at [quarters], without playing
    private fun showNotesAt(pb: Playback, quarters: Double) {
        val index = pb.onsetIndexAt(quarters).coerceAtLeast(0)
        pb.onsets.getOrNull(index)?.let { _cursor.value = it.notes }
    }

    private suspend fun preparePlayer(pb: Playback): MediaPlayer {
        val midi = withContext(Dispatchers.IO) {
            File(context.cacheDir, "playback-${midiFiles++ % 2}.mid").apply { writeBytes(pb.midi) }
        }
        val next = MediaPlayer()
        try {
            withContext(Dispatchers.IO) {
                next.setDataSource(midi.absolutePath)
                next.prepare()
            }
        } catch (e: Exception) {
            next.release()
            throw e
        }
        next.setOnCompletionListener { onEnd() }
        return next
    }

    private fun start(onScreen: Set<String>) {
        val pages = pages ?: return
        // only one start at a time: two quick taps must not end up with two players
        stop()
        _state.value = PlayState.PREPARING
        _failed.value = false
        starting = viewModelScope.launch {
            try {
                val pb = pages.playback(sound())
                val loop = _loop.value
                val from = resumeAt
                    ?: loop.a?.takeIf { loop.on }
                    ?: pb.onsets.firstOrNull { o -> o.notes.any { it in onScreen } }?.quarters
                    ?: 0.0
                val next = preparePlayer(pb)
                if (!isActive) {
                    next.release()
                    return@launch
                }
                if (from > 0) next.seekTo(pb.msExact(from).toInt())
                next.start()
                player = next
                playback = pb
                lastPlayback = pb
                position = from
                resumeAt = null
                followPaused = false
                _state.value = PlayState.PLAYING
                follow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "playback could not start: ${e::class.simpleName}: ${e.message}", e)
                stop()
                _failed.value = true
            }
        }
    }

    // A new tempo or new parts heard need a new MIDI, rewritten in milliseconds. Once the taps
    // settle, a new player is prepared while the music goes on, then takes over at the next
    // barline (the next notes when no barline is left), as a musician would: nothing is played
    // twice and nothing is cut in the middle of a bar. Stopped or paused, the new player simply
    // waits where the old one was.
    private fun soundChanged() {
        settling?.cancel()
        if (player == null) return
        settling = viewModelScope.launch {
            delay(SETTLE_MS)
            val pages = pages ?: return@launch
            var next: MediaPlayer? = null
            try {
                val pb = pages.playback(sound())
                val ready = preparePlayer(pb).also { next = it }
                val old = player
                val oldPb = playback
                if (old == null || oldPb == null) return@launch
                var at = oldPb.quartersAt(old.currentPosition.toLong())
                if (old.isPlaying) {
                    // the next barline far enough ahead to seek and start in time
                    val ahead = oldPb.quartersAt(old.currentPosition.toLong() + SWITCH_LEAD_MS)
                    at = oldPb.bars.firstOrNull { it >= ahead }
                        ?: oldPb.onsets.getOrNull(oldPb.onsetIndexAt(ahead) + 1)?.quarters
                        ?: ahead
                    ready.seekTo(pb.msExact(at).toInt())
                    val due = oldPb.msExact(at) - START_LATENCY_MS
                    while (old.isPlaying && old.currentPosition < due) delay(SWITCH_POLL_MS)
                }
                if (old.isPlaying) {
                    ready.start()
                    // the old player fades out in a few steps: cut at once, a held note clicks
                    for (step in FADE_STEPS - 1 downTo 0) {
                        val v = step.toFloat() / FADE_STEPS
                        old.setVolume(v, v)
                        delay(FADE_MS / FADE_STEPS)
                    }
                } else {
                    at = oldPb.quartersAt(old.currentPosition.toLong())
                    ready.seekTo(pb.msExact(at).toInt())
                }
                old.release()
                next = null
                player = ready
                playback = pb
                lastPlayback = pb
                position = at
                if (ready.isPlaying) follow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the old player goes on: better the old tempo than silence
                Log.e(LOG_TAG, "new sound could not be prepared: ${e::class.simpleName}: ${e.message}", e)
            } finally {
                // cut short (stopped, or another change): the player made for nothing goes
                next?.release()
                if (settling === coroutineContext[Job]) settling = null
            }
        }
    }

    // the end of the music: around the loop again, or stop
    private fun onEnd() {
        val loop = _loop.value
        val p = player
        val pb = playback
        if (loop.on && p != null && pb != null) {
            p.seekTo(pb.msExact(loop.start).toInt())
            p.start()
            follow()
        } else {
            stop()
        }
    }

    private fun follow() {
        following?.cancel()
        following = viewModelScope.launch {
            var last = -2
            while (isActive) {
                val p = player ?: break
                val pb = playback ?: break
                if (!p.isPlaying) break
                position = pb.quartersAt(p.currentPosition.toLong())
                val loop = _loop.value
                val end = loop.b
                if (loop.on && end != null && position >= end) {
                    p.seekTo(pb.msExact(loop.start).toInt())
                    position = loop.start
                }
                val index = pb.onsetIndexAt(position)
                if (index != last && index >= 0) {
                    last = index
                    _cursor.value = pb.onsets[index].notes
                }
                delay(FOLLOW_MS)
            }
        }
    }

    companion object {
        /** One ViewModel per score: [score] keys both the ViewModel and its saved settings. */
        fun factory(context: Context, score: String) = viewModelFactory {
            initializer { PlaybackViewModel(context.applicationContext as Application, score) }
        }
    }
}

/**
 * Audio time of the score position [quarters], between two notes too: `msAt` goes back to the
 * last note started, which would replay it when the music takes over at a new tempo.
 */
private fun Playback.msExact(quarters: Double): Long {
    val ms0 = msAt(quarters)
    val q0 = quartersAt(ms0)
    val q1 = quartersAt(ms0 + 1000)
    return if (q1 > q0) ms0 + ((quarters - q0) / (q1 - q0) * 1000).toLong() else ms0
}
