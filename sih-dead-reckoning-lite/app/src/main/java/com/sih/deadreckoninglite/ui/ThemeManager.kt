package com.sih.deadreckoninglite.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * Manages dark/light theme toggle using SharedPreferences.
 *
 * Uses [AppCompatDelegate.MODE_NIGHT_YES] / [AppCompatDelegate.MODE_NIGHT_NO]
 * to switch themes at runtime. The splash screen always uses the dark theme
 * for brand consistency.
 *
 * ## Usage
 * Call [applyTheme] in every Activity's `onCreate` BEFORE `setContentView`.
 * Call [toggleTheme] from the theme toggle button to switch and recreate.
 */
object ThemeManager {

    private const val PREF_NAME = "dr_lite_theme_prefs"
    private const val KEY_IS_DARK = "is_dark_mode"

    // Default to dark mode (matches the "Horizon" design language)
    private const val DEFAULT_IS_DARK = true

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Returns true if the app is currently in dark mode.
     */
    fun isDarkMode(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_IS_DARK, DEFAULT_IS_DARK)

    /**
     * Apply the stored theme preference. Call this in `onCreate`
     * BEFORE `super.onCreate()` and `setContentView()`.
     */
    fun applyTheme(context: Context) {
        val mode = if (isDarkMode(context)) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Toggle between dark and light mode.
     * Saves the preference and applies immediately.
     * The calling Activity will be recreated by [AppCompatDelegate].
     */
    fun toggleTheme(context: Context) {
        val currentlyDark = isDarkMode(context)
        val newIsDark = !currentlyDark

        getPrefs(context).edit()
            .putBoolean(KEY_IS_DARK, newIsDark)
            .apply()

        val mode = if (newIsDark) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
