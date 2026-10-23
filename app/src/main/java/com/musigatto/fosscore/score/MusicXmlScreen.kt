package com.musigatto.fosscore.score

import com.musigatto.fosscore.musicxml.LayoutSpec
import com.musigatto.fosscore.musicxml.Part
import com.musigatto.fosscore.ui.viewer.ScoreLayout
import com.musigatto.fosscore.ui.viewer.ZoomMath
import com.musigatto.fosscore.ui.viewer.fitRect
import org.json.JSONObject

/**
 * The screen a MusicXML score is laid out for, apart from the score's own settings: the viewport,
 * the width of a page (the viewport less the side bars, halved in two-up), the music size of a
 * note size of 100 %, and the room of the bars above and below. The viewer builds its layout from
 * it, and the scores are laid out and drawn ahead for the screens seen last
 * ([MusicXmlPages.prepare]), so both must give the very same layout and page pictures.
 */
data class MusicXmlScreen(
    val viewportWidth: Int,
    val viewportHeight: Int,
    val pageWidth: Int,
    val baseScale: Int,
    val marginTop: Int,
    val marginBottom: Int,
    val twoUp: Boolean,
) {
    /** The layout of a score with [layout] and [parts] on this screen. */
    fun spec(layout: ScoreLayout, parts: List<Part>) = LayoutSpec(
        width = pageWidth,
        height = viewportHeight,
        // the music size follows the whole screen, so two-up does not shrink the notes
        scale = (baseScale * layout.musicSize / 100).coerceIn(LayoutSpec.MIN_SCALE, LayoutSpec.MAX_SCALE),
        marginTop = marginTop,
        marginBottom = marginBottom,
        semitones = layout.semitones,
        parts = layout.layoutParts(parts),
        lyrics = layout.lyrics,
        hiddenLyrics = layout.keepHiddenLyrics,
    )

    /** The size, in px, the viewer draws a page in at rest (no zoom), as its render loop does. */
    fun slot(): Pair<Int, Int> =
        if (twoUp) Pair(viewportWidth / 2, viewportHeight)
        else ZoomMath.renderSlot(fitRect(viewportWidth.toFloat(), viewportHeight.toFloat(), pageWidth.toFloat() / viewportHeight), 1f)

    fun toJson(): JSONObject = JSONObject()
        .put("viewportWidth", viewportWidth).put("viewportHeight", viewportHeight).put("pageWidth", pageWidth)
        .put("baseScale", baseScale).put("marginTop", marginTop).put("marginBottom", marginBottom).put("twoUp", twoUp)

    companion object {
        fun fromJson(o: JSONObject) = MusicXmlScreen(
            o.getInt("viewportWidth"), o.getInt("viewportHeight"), o.getInt("pageWidth"),
            o.getInt("baseScale"), o.getInt("marginTop"), o.getInt("marginBottom"), o.getBoolean("twoUp"),
        )
    }
}
