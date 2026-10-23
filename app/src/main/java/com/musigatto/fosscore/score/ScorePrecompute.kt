package com.musigatto.fosscore.score

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.savedScoreLayout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val LOG_TAG = "FOSScore-Precompute"

/**
 * Lays out and draws MusicXML scores ahead, whenever the user leaves the tablet alone: reading a
 * score, looking for one in the library, on a score's details... (but not while the viewer
 * lays out or draws a score, which needs the tablet too). A score then opens at once,
 * even the first time, even on a slow tablet.
 *
 * The scores go most recently opened first, then the ones never opened, newest import first;
 * each gets what its opening needs for every screen seen ([MusicXmlPages.prepare]). The work
 * pauses at every touch and while the music plays, and goes on where it stopped. It wakes up when
 * there is something new: an import, a new screen, new settings for a score.
 */
object ScorePrecompute {
    // the user left the tablet alone this long: the work may go on
    private const val IDLE_MS = 3_000L
    private const val IDLE_POLL_MS = 500L
    // after the start of the app, leave it the tablet a moment
    private const val START_DELAY_MS = 5_000L

    @Volatile private var lastTouch = 0L
    private val viewerWorks = AtomicInteger()
    // a player sounds: the work could be heard as hiccups in the music
    @Volatile var playing = false
    private val wake = MutableStateFlow(0)

    /** The user touched the screen or a key. */
    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
    }

    /** Runs [block], a layout or a drawing for the viewer: the work ahead waits until it is over. */
    suspend fun <T> whileViewerWorks(block: suspend () -> T): T {
        viewerWorks.incrementAndGet()
        try {
            return block()
        } finally {
            viewerWorks.decrementAndGet()
            touched()
        }
    }

    /** Something to lay out ahead may have appeared: a score, a screen, settings. */
    fun changed() = wake.update { it + 1 }

    /** When the score at [uri] (the viewer's score uri) was opened: the most recent go first. */
    fun opened(context: Context, uri: String) {
        Settings.setOpenedAt(context, uri, System.currentTimeMillis())
        changed()
    }

    /** Starts the work for the scores of [sheets], in [scope], for as long as it lives. */
    fun start(context: Context, scope: CoroutineScope, sheets: Flow<List<Sheet>>) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            delay(START_DELAY_MS)
            // scores that could not be prepared are not tried again until the app starts again
            val failed = mutableSetOf<String>()
            while (true) {
                val seen = wake.value
                val next = MusicXmlPages.unprepared(app, ordered(app, sheets.first()).filter { it.path !in failed }, ::layoutOf)
                if (next == null) {
                    wake.first { it != seen }
                    continue
                }
                awaitIdle()
                val uri = uriOf(next)
                runCatching {
                    MusicXmlPages.prepare(app, next, layoutOf(app, next), Settings.lastPage(app, uri), pause = ::awaitIdle)
                }.onFailure {
                    Log.e(LOG_TAG, "${next.name} could not be prepared: ${it.message}")
                    failed += next.path
                }
            }
        }
    }

    private fun idle() = !playing && viewerWorks.get() == 0 && SystemClock.uptimeMillis() - lastTouch >= IDLE_MS

    private suspend fun awaitIdle() {
        while (!idle()) delay(IDLE_POLL_MS)
    }

    private fun ordered(context: Context, sheets: List<Sheet>): List<File> =
        sheets.filter { SheetFormat.of(it.path).isMusicXml }
            .sortedWith(compareByDescending<Sheet> { Settings.openedAt(context, uriOf(File(it.path))) }.thenByDescending { it.id })
            .map { File(it.path) }

    private fun layoutOf(context: Context, file: File) = savedScoreLayout(context, uriOf(file))

    // the key of the score's settings: the uri the viewer opens it with
    private fun uriOf(file: File) = Uri.fromFile(file).toString()
}
