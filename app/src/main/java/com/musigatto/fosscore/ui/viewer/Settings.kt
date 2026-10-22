package com.musigatto.fosscore.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.musigatto.fosscore.R
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]

    fun darkTheme(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    @Composable
    fun label(): String = when (this) {
        SYSTEM -> stringResource(R.string.theme_auto)
        LIGHT -> stringResource(R.string.theme_light)
        DARK -> stringResource(R.string.theme_dark)
    }
}

object Settings {
    private const val PREFS = "fosscore_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_DIM = "dim"
    private const val KEY_INVERT = "invert"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LAST_PAGE = "last_page_"
    private const val KEY_STAMP_PX = "stamp_px"

    fun themeMode(context: Context): ThemeMode =
        ThemeMode.entries[context.prefs().getInt(KEY_THEME, ThemeMode.SYSTEM.ordinal)]

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.prefs().edit { putInt(KEY_THEME, mode.ordinal) }
    }

    fun dimPct(context: Context): Float = context.prefs().getFloat(KEY_DIM, 0f)

    fun setDimPct(context: Context, pct: Float) {
        context.prefs().edit { putFloat(KEY_DIM, pct.coerceIn(0f, 80f)) }
    }

    fun invert(context: Context): Boolean = context.prefs().getBoolean(KEY_INVERT, false)

    fun setInvert(context: Context, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_INVERT, value) }
    }

    // the screen stays awake while a score is on screen, so it does not sleep mid-performance
    fun keepScreenOn(context: Context): Boolean =
        context.prefs().getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        context.prefs().edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }
    }

    fun lastPage(context: Context, uri: String): Int =
        context.prefs().getInt(KEY_LAST_PAGE + uri, 0)

    fun setLastPage(context: Context, uri: String, page: Int) {
        context.prefs().edit { putInt(KEY_LAST_PAGE + uri, page) }
    }

    // size the stamp tool starts with, saved from the stamps panel
    fun stampPx(context: Context): Float =
        context.prefs().getFloat(KEY_STAMP_PX, DEFAULT_STAMP_PX)

    fun setStampPx(context: Context, px: Float) {
        context.prefs().edit { putFloat(KEY_STAMP_PX, px.coerceIn(MIN_STAMP_PX, MAX_STAMP_PX)) }
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}