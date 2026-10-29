package com.musigatto.fosscore.ui.viewer

import android.app.Application
import android.content.Context
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
import com.musigatto.fosscore.sound.LivePlayer
import com.musigatto.fosscore.sound.MidiSong
import com.musigatto.fosscore.sound.SoundBank
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
import kotlin.time.Duration.Companion.milliseconds

private const val LOG_TAG = "FOSScore-Playback"

// how often the cursor and the loop look at the player: under a sixteenth note at ♩ = 200
private const val FOLLOW_MS = 40L

// the musicxml module accepts tempo factors in this range (VerovioOptions)
const val MIN_TEMPO_FACTOR = 0.2f
const val MAX_TEMPO_FACTOR = 4.0f

// MIDI's own default, when the score writes no tempo
const val DEFAULT_BPM = 120.0

// new parts heard are taken once the taps on their boxes settle
private const val SETTLE_MS = 200L

// playback of a newly laid out score is made ready once its first pages are drawn
private const val WARM_DELAY_MS = 1000L

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
 * The parts heard: the ones picked by the user, or the ones shown until the user picks. Empty
 * when the user muted them all (the bar goes on without sound); when none of the parts picked is
 * left in the file, every part plays.
 */
fun heardParts(picked: Set<String>?, shown: Set<String>, parts: List<Part>): Set<String> {
    val ids = parts.map { it.id }.toSet()
    if (picked?.isEmpty() == true) return emptySet()
    return (picked?.filter { it in ids }?.toSet() ?: shown).ifEmpty { ids }
}

/**
 * MusicXML playback: the MIDI the musicxml module writes for the score, played live by our own
 * SoundFont synth ([LivePlayer]), and where the music is for the viewer's playback bar and page
 * turns. A new tempo, new parts heard or the loop apply at once. Tempo and parts heard are saved
 * per score.
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

    // the notes [turnAhead] bars after the cursor: where the reader looks, for the page turns
    private val _ahead = MutableStateFlow<List<String>>(emptyList())
    val ahead: StateFlow<List<String>> = _ahead.asStateFlow()

    /** Bars ahead of the music the pages turn (the metronome setting, shared by playback). */
    var turnAhead = 0

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
    private var player: LivePlayer? = null
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
                    delay(WARM_DELAY_MS.milliseconds)
                    it.preparePlayback()
                    // the sound bank and the samples of the piece too (decoded once, then kept):
                    // ▶ then starts at once
                    runCatching {
                        val made = livePlayer()
                        it.playback(liveSound())?.let { pb -> withContext(Dispatchers.Default) { made.warm(MidiSong.read(pb.midi)) } }
                    }
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
        val pb = playback ?: lastPlayback
        val p = player
        if (playback != null && p != null) p.seek(at) else resumeAt = at
        if (pb != null) showNotesAt(pb, at) else _toFirstPage.value++
    }

    fun toggleLoop() = setLoop(_loop.value.toggle())

    fun tapA() = setLoop(_loop.value.tapA(position))

    fun tapB() = setLoop(_loop.value.tapB(position))

    /**
     * The music heard now, in quarter notes, for the playback bar: where the player is, or where
     * ⏮ put the music before ▶; null when nothing is to show.
     */
    fun positionNow(): Double? = when {
        playback != null -> player?.position
        _cursor.value.isNotEmpty() -> position
        else -> null
    }

    /** The playback being heard, or the last one: its notes and bars for the playback bar. */
    val currentPlayback: Playback? get() = playback ?: lastPlayback

    fun setTempo(factor: Float) {
        val f = factor.coerceIn(MIN_TEMPO_FACTOR, MAX_TEMPO_FACTOR)
        if (f == _tempo.value) return
        _tempo.value = f
        Settings.setTempo(context, score, f)
        // the live player takes it at once
        player?.tempoFactor = f.toDouble()
    }

    fun setHeard(id: String, heard: Boolean, parts: List<Part>) {
        val now = heardParts(_heard.value, shownParts, parts)
        val next = if (heard) now + id else now - id
        _heard.value = next
        Settings.setHeardParts(context, score, next)
        // silent at once; heard again once the new parts' music is ready (at once when stopped)
        if (next.isEmpty() || playback == null) player?.muted = next.isEmpty()
        // all muted: the music goes on as it is, unheard
        if (next.isNotEmpty()) soundChanged()
    }

    /** A page turned by hand during playback: let the reader look ahead or back. */
    fun pageTurnedByHand() {
        if (_state.value == PlayState.PLAYING) followPaused = true
    }

    /** The music reached the page on screen again: the pages follow it again. */
    fun musicOnScreen() {
        followPaused = false
    }

    /** The music of the score as laid out now: its bars and notes, for the metronome. */
    suspend fun music(): Playback? = pages?.playback(sound())

    fun stop() {
        starting?.cancel()
        starting = null
        settling?.cancel()
        settling = null
        following?.cancel()
        following = null
        player?.pause()
        playback = null
        followPaused = false
        _ahead.value = emptyList()
        _cursor.value = emptyList()
        _state.value = PlayState.STOPPED
    }

    init {
        // working ahead could be heard as hiccups in the music
        viewModelScope.launch { _state.collect { ScorePrecompute.playing = it == PlayState.PLAYING } }
    }

    override fun onCleared() {
        stop()
        player?.release()
        player = null
        ScorePrecompute.playing = false
    }

    private fun pause() {
        player?.pause()
        _state.value = PlayState.PAUSED
    }

    private fun resume() {
        val p = player ?: return
        p.play(p.position)
        _state.value = PlayState.PLAYING
        follow()
    }

    private fun setLoop(next: Loop) {
        _loop.value = next
        player?.setLoop(next.on, next.start, next.b)
    }

    // nothing heard: the music of the parts shown, which the player keeps silent
    private fun sound() = SoundSpec(audible = _heard.value?.ifEmpty { null }, tempo = _tempo.value.toDouble())

    // the music for the live player: at the written tempo, which the player scales as it goes
    private fun liveSound() = sound().copy(tempo = 1.0)

    // the player, made once (its sound bank is read the first time)
    private suspend fun livePlayer(): LivePlayer = player ?: withContext(Dispatchers.Default) {
        LivePlayer(SoundBank.load(context))
    }.also { made ->
        made.onEnd = { viewModelScope.launch { onEnd() } }
        made.muted = _heard.value?.isEmpty() == true
        player = made
    }

    // the highlight and the page go to the notes at [quarters], without playing
    private fun showNotesAt(pb: Playback, quarters: Double) {
        val index = pb.onsetIndexAt(quarters).coerceAtLeast(0)
        pb.onsets.getOrNull(index)?.let {
            _ahead.value = emptyList()
            _cursor.value = it.notes
        }
    }

    private fun start(onScreen: Set<String>) {
        val pages = pages ?: return
        // only one start at a time: two quick taps must not end up with two players
        stop()
        _state.value = PlayState.PREPARING
        _failed.value = false
        starting = viewModelScope.launch {
            try {
                val pb = pages.playback(liveSound()) ?: run {
                    stop()
                    _failed.value = true
                    return@launch
                }
                val loop = _loop.value
                val from = resumeAt
                    ?: loop.a?.takeIf { loop.on }
                    ?: pb.onsets.firstOrNull { o -> o.notes.any { it in onScreen } }?.quarters
                    ?: 0.0
                val song = withContext(Dispatchers.Default) { MidiSong.read(pb.midi) }
                val next = livePlayer()
                // the samples are read now, not by the first notes on the audio thread
                withContext(Dispatchers.Default) { next.warm(song) }
                if (!isActive) return@launch
                next.tempoFactor = _tempo.value.toDouble()
                next.setLoop(loop.on, loop.start, loop.b)
                next.setSong(song)
                next.play(from)
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

    // New parts heard: the MIDI is written again (a few milliseconds) once the taps settle, and
    // the live player takes it where the music is, without a gap.
    private fun soundChanged() {
        settling?.cancel()
        if (playback == null) return
        settling = viewModelScope.launch {
            delay(SETTLE_MS.milliseconds)
            val pages = pages ?: return@launch
            try {
                val pb = pages.playback(liveSound()) ?: return@launch
                val song = withContext(Dispatchers.Default) { MidiSong.read(pb.midi).also { player?.warm(it) } }
                if (playback == null) return@launch
                player?.setSong(song)
                player?.muted = _heard.value?.isEmpty() == true
                playback = pb
                lastPlayback = pb
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the old parts go on: better than silence
                Log.e(LOG_TAG, "new parts could not be prepared: ${e::class.simpleName}: ${e.message}", e)
            } finally {
                if (settling === coroutineContext[Job]) settling = null
            }
        }
    }

    // the end of the music (the loop wraps in the player itself)
    private fun onEnd() {
        if (playback != null) stop()
    }

    private fun follow() {
        following?.cancel()
        following = viewModelScope.launch {
            var last = -2
            while (isActive) {
                val p = player ?: break
                val pb = playback ?: break
                if (!p.playing) break
                position = p.position
                val index = pb.onsetIndexAt(position)
                if (index != last && index >= 0) {
                    last = index
                    _ahead.value = pb.notesAhead(position, turnAhead) ?: pb.onsets[index].notes
                    _cursor.value = pb.onsets[index].notes
                }
                delay(FOLLOW_MS.milliseconds)
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

/** The bar (0-based) at [quarters], from the bar lines of the score; 0 before the first. */
fun Playback.barAt(quarters: Double): Int = bars.indexOfLast { it <= quarters + 1e-6 }.coerceAtLeast(0)

/** The first notes of bar [bar], or null past the last bar. */
fun Playback.notesOfBar(bar: Int): List<String>? =
    bars.getOrNull(bar)?.let { start -> onsets.firstOrNull { it.quarters >= start - 1e-6 }?.notes }

/** The notes [bars] bars after [quarters], where the reader looks ahead; null when not looking ahead. */
fun Playback.notesAhead(quarters: Double, bars: Int): List<String>? =
    if (bars <= 0) null else notesOfBar(barAt(quarters) + bars) ?: onsets.lastOrNull()?.notes

