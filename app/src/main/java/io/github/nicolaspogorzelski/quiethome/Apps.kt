package io.github.nicolaspogorzelski.quiethome

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

data class AppEntry(
    val packageName: String,
    val label: String,
    val activity: ActivityInfo,
    val leanback: Boolean,
)

/** Installed apps, their launch intents and banners. */
object Apps {
    /** Order used when Home is filled for the first time; missing packages are skipped. */
    private val preferredDefaults = listOf(
        "org.moonfin.androidtv",
        "org.jellyfin.androidtv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.google.android.youtube.tv",
        "com.crunchyroll.crunchyroid",
        "de.swr.avp.ard.tv",
        "com.zdf.android.mediathek",
    )

    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val banners = LruCache<String, Drawable>(16)

    fun installed(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val own = context.packageName
        val byPackage = LinkedHashMap<String, AppEntry>()
        for ((category, leanback) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val ai = info.activityInfo
                if (ai.packageName == own || ai.packageName in byPackage) continue
                byPackage[ai.packageName] = AppEntry(ai.packageName, info.loadLabel(pm).toString(), ai, leanback)
            }
        }
        return byPackage.values.sortedBy { it.label.lowercase() }
    }

    fun defaultHome(installed: List<AppEntry>): List<String> {
        val present = installed.map { it.packageName }.toSet()
        val picked = preferredDefaults.filter { it in present }
        return picked.ifEmpty { installed.filter { it.leanback }.take(6).map { it.packageName } }
    }

    fun launchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = launchIntent(context, packageName) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Loads the TV banner (or the icon as fallback) off the main thread. */
    fun loadBanner(context: Context, app: AppEntry, onLoaded: (Drawable) -> Unit) {
        banners.get(app.packageName)?.let { onLoaded(it); return }
        val pm = context.applicationContext.packageManager
        val resources = context.applicationContext.resources
        loader.execute {
            val drawable = bannerOf(pm, resources, app)
            banners.put(app.packageName, drawable)
            main.post { onLoaded(drawable) }
        }
    }

    /**
     * Banners ship at up to 1280x720; they are rendered once at tile size so that
     * only small bitmaps stay in memory.
     */
    private fun bannerOf(pm: PackageManager, resources: Resources, app: AppEntry): Drawable {
        val banner = app.activity.loadBanner(pm) ?: app.activity.applicationInfo.loadBanner(pm)
        val source = banner ?: app.activity.loadIcon(pm)
        val bitmap = Bitmap.createBitmap(BANNER_WIDTH, BANNER_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (banner != null) {
            source.setBounds(0, 0, BANNER_WIDTH, BANNER_HEIGHT)
        } else {
            // Phone-style icon: centred square on the tile.
            val size = BANNER_HEIGHT * 2 / 3
            val left = (BANNER_WIDTH - size) / 2
            val top = (BANNER_HEIGHT - size) / 2
            source.setBounds(left, top, left + size, top + size)
        }
        source.draw(canvas)
        return BitmapDrawable(resources, bitmap)
    }

    // 136x76 dp tiles at 320 dpi, the largest tile size used.
    private const val BANNER_WIDTH = 272
    private const val BANNER_HEIGHT = 152
}
