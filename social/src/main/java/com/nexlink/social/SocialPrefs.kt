package com.nexlink.social

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * §14.16.1 — the two preferences that change what the app DOES.
 *
 * Deliberately two. Element's settings list runs to a dozen sections, most of
 * which describe Element rather than this service — a "Labs" page for a product
 * with one developer is a page of promises. These two earn their place because
 * each one changes something a user can point at: what the app looks like, and
 * what a notification gives away to someone glancing at a locked screen.
 *
 * Plain SharedPreferences, not Encrypted: neither value is a secret, and
 * reading the theme must work before the session does.
 */
object SocialPrefs {

    private const val FILE = "social_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_NOTIF_CONTENT = "notification_content"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun theme(c: Context): String = prefs(c).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setTheme(c: Context, value: String) {
        prefs(c).edit().putString(KEY_THEME, value).apply()
        apply(value)
    }

    /** Apply without touching storage — for [SocialApplication.onCreate]. */
    fun apply(value: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun themeLabel(c: Context): String = when (theme(c)) {
        THEME_LIGHT -> "Light"
        THEME_DARK -> "Dark"
        else -> "Follow system"
    }

    /**
     * Whether a notification may show who sent what.
     *
     * Default ON. Off is the right choice for a shared or frequently-visible
     * screen, and it is the only setting here that changes what someone ELSE
     * can learn — which is why it says so on the row rather than being a bare
     * toggle labelled "Show content".
     */
    fun showNotificationContent(c: Context): Boolean =
        prefs(c).getBoolean(KEY_NOTIF_CONTENT, true)

    fun setShowNotificationContent(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean(KEY_NOTIF_CONTENT, value).apply()
    }
}
