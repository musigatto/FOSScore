package com.musigatto.fosscore.ui.viewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musigatto.fosscore.musicxml.LyricsPlacement
import com.musigatto.fosscore.musicxml.Part
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// MusicXML note size, in percent of the size chosen for the screen: 10 % steps, half to double
const val MIN_MUSIC_SIZE = 50
const val MAX_MUSIC_SIZE = 200
const val MUSIC_SIZE_STEP = 5

// the musicxml module transposes up to an octave either way
const val MAX_TRANSPOSE = 12

/** What the viewer shows over the page: nothing, the menu, or one of the menu's panels. */
enum class ViewerSurface { NONE, MENU, DISPLAY, LAYOUT, PLAYBACK }

/**
 * Every entry of the viewer menu, in its order. The groups are separated by a line: going
 * somewhere or changing the view · practising · the play route · changing the score · sending it
 * out · the app.
 */
enum class ViewerMenuItem(val group: Int) {
    LIBRARY(0), SETLIST(0), BOOKMARKS(0), DISPLAY(0), LAYOUT(0),
    METRONOME(1), TUNER(1), PIANO(1), PLAYBACK(1),
    LINKS(2),
    ANNOTATE(3), PAGES(3),
    EXPORT(4), SHARE(4),
    SETTINGS(5),
}

/** Why an entry can or cannot be used now. Only [READY] entries react to a tap. */
enum class MenuItemState { READY, SOON, MUSICXML_ONLY, NOT_ON_MUSICXML }

/** Which entries work for this score. */
fun menuItemState(item: ViewerMenuItem, isMusicXml: Boolean): MenuItemState =
    when (item) {
        ViewerMenuItem.LIBRARY, ViewerMenuItem.DISPLAY -> MenuItemState.READY
        ViewerMenuItem.LAYOUT, ViewerMenuItem.PLAYBACK ->
            if (isMusicXml) MenuItemState.READY else MenuItemState.MUSICXML_ONLY
        // annotations are stored per page, and a MusicXML page changes with the screen
        ViewerMenuItem.ANNOTATE -> if (isMusicXml) MenuItemState.NOT_ON_MUSICXML else MenuItemState.READY
        else -> MenuItemState.SOON
    }

/**
 * How a MusicXML score is laid out. [shownParts] is null until the user picks parts: the score
 * then shows the parts its author left visible.
 */
data class ScoreLayout(
    val musicSize: Int = 100,
    val semitones: Int = 0,
    val shownParts: Set<String>? = null,
    val lyrics: LyricsPlacement = LyricsPlacement.FILE,
    /** The lyrics of the parts hidden stay, as a line of lyrics: the accompanist reads the words. */
    val keepHiddenLyrics: Boolean = true,
) {
    /** The ids of the parts drawn, out of [parts]. Never empty while the score has parts. */
    fun partsShown(parts: List<Part>): Set<String> {
        val ids = parts.map { it.id }
        val picked = shownParts?.filter { it in ids }
            ?: parts.filter { it.visibleInFile }.map { it.id }
        return (picked.ifEmpty { ids }).toSet()
    }

    /** [partsShown] as the musicxml module takes it: empty when every part is drawn. */
    fun layoutParts(parts: List<Part>): Set<String> =
        partsShown(parts).takeIf { it.size < parts.size }.orEmpty()
}

/**
 * Where a score's layout is kept.
 *
 * ponytail: in the app settings, by file, until the per-score configurations table exists
 * (roadmap 3.4); then the store reads and writes that table instead, and nothing else changes.
 */
interface ScoreLayoutStore {
    fun load(): ScoreLayout
    fun save(layout: ScoreLayout)
}

/** The layout saved for [score] (the viewer's score uri), or the default one. */
fun savedScoreLayout(context: Context, score: String): ScoreLayout =
    SettingsLayoutStore(context.applicationContext, score).load()

private class SettingsLayoutStore(private val context: Context, private val score: String) : ScoreLayoutStore {
    override fun load() = ScoreLayout(
        musicSize = Settings.musicSize(context, score),
        semitones = Settings.transpose(context, score),
        shownParts = Settings.shownParts(context, score),
        keepHiddenLyrics = Settings.keepHiddenLyrics(context, score),
        lyrics = Settings.lyrics(context, score)
            ?.let { name -> LyricsPlacement.entries.firstOrNull { it.name == name } }
            ?: LyricsPlacement.FILE,
    )

    override fun save(layout: ScoreLayout) {
        Settings.setMusicSize(context, score, layout.musicSize)
        Settings.setTranspose(context, score, layout.semitones)
        Settings.setShownParts(context, score, layout.shownParts)
        Settings.setLyrics(context, score, layout.lyrics.name)
        Settings.setKeepHiddenLyrics(context, score, layout.keepHiddenLyrics)
    }
}

/**
 * The viewer menu and what it changes in the score's layout. The viewer draws what this holds
 * and sends the user's choices back here; the page turning, zoom and annotations stay in the
 * viewer for now (architecture roadmap, phase 5, moves them to a ViewModel too).
 */
class ViewerMenuViewModel(private val store: ScoreLayoutStore) : ViewModel() {

    private val _surface = MutableStateFlow(ViewerSurface.NONE)
    val surface: StateFlow<ViewerSurface> = _surface.asStateFlow()

    private val _layout = MutableStateFlow(store.load())
    val layout: StateFlow<ScoreLayout> = _layout.asStateFlow()

    // the score's parts, once it is read: a PDF has none
    private val _parts = MutableStateFlow<List<Part>>(emptyList())
    val parts: StateFlow<List<Part>> = _parts.asStateFlow()

    fun show(surface: ViewerSurface) {
        _surface.value = surface
    }

    fun dismiss() = show(ViewerSurface.NONE)

    fun onScoreRead(parts: List<Part>) {
        _parts.value = parts
    }

    fun setMusicSize(percent: Int) = change { it.copy(musicSize = percent.coerceIn(MIN_MUSIC_SIZE, MAX_MUSIC_SIZE)) }

    fun transpose(semitones: Int) = change { it.copy(semitones = semitones.coerceIn(-MAX_TRANSPOSE, MAX_TRANSPOSE)) }

    fun setLyrics(placement: LyricsPlacement) = change { it.copy(lyrics = placement) }

    fun setKeepHiddenLyrics(keep: Boolean) = change { it.copy(keepHiddenLyrics = keep) }

    /** Shows or hides one part. The last part shown stays: a score with no staff is a blank page. */
    fun setPartShown(id: String, shown: Boolean) = change { layout ->
        val now = layout.partsShown(_parts.value)
        val next = if (shown) now + id else now - id
        if (next.isEmpty()) layout else layout.copy(shownParts = next)
    }

    private fun change(edit: (ScoreLayout) -> ScoreLayout) {
        _layout.update(edit)
        store.save(_layout.value)
    }

    companion object {
        /** One ViewModel per score: [score] keys both the ViewModel and the saved layout. */
        fun factory(context: Context, score: String) = viewModelFactory {
            initializer { ViewerMenuViewModel(SettingsLayoutStore(context.applicationContext, score)) }
        }
    }
}
