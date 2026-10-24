package io.github.nicolaspogorzelski.quiethome

import android.content.Context

/** How apps appear: colourful banners, or CleanFrame's white marks with the app name. */
enum class IconStyle { CLASSIC, QUIET }

/** The dock's glass: CLASSIC as before, or QUIET, CleanFrame's lighter "Soft" material. */
enum class DockStyle { CLASSIC, QUIET }

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

    var iconStyle: IconStyle
        get() = if (sp.getString(KEY_ICON_STYLE, null) == "quiet") IconStyle.QUIET else IconStyle.CLASSIC
        set(value) = sp.edit().putString(KEY_ICON_STYLE, if (value == IconStyle.QUIET) "quiet" else "classic").apply()

    var dockStyle: DockStyle
        get() = if (sp.getString(KEY_DOCK_STYLE, null) == "quiet") DockStyle.QUIET else DockStyle.CLASSIC
        set(value) = sp.edit().putString(KEY_DOCK_STYLE, if (value == DockStyle.QUIET) "quiet" else "classic").apply()

    private companion object {
        const val KEY_HOME = "home_apps"
        const val KEY_WALLPAPER = "wallpaper"
        const val KEY_ROTATE = "rotate_wallpaper"
        const val KEY_NETFLIX = "netflix_target"
        const val KEY_ICON_STYLE = "icon_style"
        const val KEY_DOCK_STYLE = "dock_style"
        const val DEFAULT_NETFLIX_TARGET = "org.moonfin.androidtv"
    }
}
