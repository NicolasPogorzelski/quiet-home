package io.github.nicolaspogorzelski.quiethome

import android.content.Context

/** All launcher settings, stored locally in one SharedPreferences file. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Package names on Home, in display order. Empty until the first start fills in defaults. */
    var homeApps: List<String>
        get() = sp.getString(KEY_HOME, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_HOME, value.joinToString("\n")).apply()

    val homeAppsInitialised: Boolean get() = sp.contains(KEY_HOME)

    /** File name of the wallpaper, [Wallpapers.GRADIENT] for the built-in one, null before the first choice. */
    var wallpaper: String?
        get() = sp.getString(KEY_WALLPAPER, null)
        set(value) = sp.edit().putString(KEY_WALLPAPER, value).apply()

    var rotateWallpaper: Boolean
        get() = sp.getBoolean(KEY_ROTATE, false)
        set(value) = sp.edit().putBoolean(KEY_ROTATE, value).apply()

    /** Package started by the Netflix key, or null to leave the key alone. */
    var netflixTarget: String?
        get() = sp.getString(KEY_NETFLIX, DEFAULT_NETFLIX_TARGET)?.takeIf { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_NETFLIX, value.orEmpty()).apply()

    private companion object {
        const val KEY_HOME = "home_apps"
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_ROTATE = "rotate_wallpaper"
        const val KEY_NETFLIX = "netflix_target"
        const val DEFAULT_NETFLIX_TARGET = "org.moonfin.androidtv"
    }
}
