package com.musigatto.fosscore.ui.viewer

import android.content.Context

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]

    fun darkTheme(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    val label: String
        get() = when (this) {
            SYSTEM -> "Auto"
            LIGHT -> "Claro"
            DARK -> "Oscuro"
        }
}

object Settings {
    private const val PREFS = "fosscore_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_DIM = "dim"
    private const val KEY_INVERT = "invert"
    private const val KEY_LAST_PAGE = "last_page_"

    fun themeMode(context: Context): ThemeMode =
        ThemeMode.entries[context.prefs().getInt(KEY_THEME, ThemeMode.SYSTEM.ordinal)]

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.prefs().edit().putInt(KEY_THEME, mode.ordinal).apply()
    }

    fun dimPct(context: Context): Float = context.prefs().getFloat(KEY_DIM, 0f)

    fun setDimPct(context: Context, pct: Float) {
        context.prefs().edit().putFloat(KEY_DIM, pct.coerceIn(0f, 80f)).apply()
    }

    fun invert(context: Context): Boolean = context.prefs().getBoolean(KEY_INVERT, false)

    fun setInvert(context: Context, value: Boolean) {
        context.prefs().edit().putBoolean(KEY_INVERT, value).apply()
    }

    fun lastPage(context: Context, uri: String): Int =
        context.prefs().getInt(KEY_LAST_PAGE + uri, 0)

    fun setLastPage(context: Context, uri: String, page: Int) {
        context.prefs().edit().putInt(KEY_LAST_PAGE + uri, page).apply()
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}