package io.github.nicolaspogorzelski.quiethome

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.Executors

/**
 * White marks for the Quiet style, without bundling any logo. In order of preference:
 * 1. a mark the user installed (tools/install-icons.sh): <package>.svg or <package>.png in
 *    /sdcard/Android/data/<this app>/files/icons,
 * 2. the app's own monochrome icon layer (meant for Android 13 themed icons, read here directly),
 * 3. a mark derived from the app icon: background removed, the rest turned white,
 * 4. the app icon itself, in colour.
 */
object Marks {
    private const val SIZE = 192
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Drawable>(48)
    private var stamp = 0L

    fun folder(context: Context): File? = context.getExternalFilesDir("icons")

    /** Drops cached marks when icons were added, replaced or removed; returns true if so. */
    fun refresh(context: Context): Boolean {
        val files = folder(context)?.listFiles().orEmpty()
        val now = files.fold(files.size.toLong()) { acc, f -> acc * 31 + f.name.hashCode() + f.lastModified() + f.length() }
        if (now == stamp) return false
        stamp = now
        cache.evictAll()
        return true
    }

    fun load(context: Context, app: AppEntry, onLoaded: (Drawable) -> Unit) {
        if (BuildConfig.DEMO) { onLoaded(Demo.mark(context, app)); return }
        cache.get(app.packageName)?.let { onLoaded(it); return }
        val appContext = context.applicationContext
        loader.execute {
            val drawable = resolve(appContext, app)
            cache.put(app.packageName, drawable)
            main.post { onLoaded(drawable) }
        }
    }

    private fun resolve(context: Context, app: AppEntry): Drawable {
        val pm = context.packageManager
        val mark = try {
            userMark(context, app) ?: monochromeLayer(pm, app) ?: derived(pm, app)
        } catch (e: Exception) {
            null
        }
        return mark?.let { BitmapDrawable(context.resources, it) } ?: app.activity.loadIcon(pm)
    }

    private fun userMark(context: Context, app: AppEntry): Bitmap? {
        val dir = folder(context) ?: return null
        val svg = File(dir, "${app.packageName}.svg")
        if (svg.isFile) return SvgPath.render(svg.readText(), SIZE)?.let(::trim)
        val png = File(dir, "${app.packageName}.png")
        if (png.isFile) return BitmapFactory.decodeFile(png.path)?.let { whiten(it, keepAll = true) }
        return null
    }

    /** Reads <monochrome android:drawable> from the app's adaptive icon XML; absent on most TV apps. */
    private fun monochromeLayer(pm: PackageManager, app: AppEntry): Bitmap? {
        val info = app.activity
        val iconId = info.iconResource.takeIf { it != 0 } ?: info.applicationInfo.icon
        if (iconId == 0) return null
        val res = pm.getResourcesForApplication(info.applicationInfo)
        val parser = try {
            res.getXml(iconId)
        } catch (e: Exception) {
            return null // a bitmap icon, not an adaptive-icon XML
        }
        var monoId = 0
        parser.use {
            while (it.next() != XmlPullParser.END_DOCUMENT) {
                if (it.eventType == XmlPullParser.START_TAG && it.name == "monochrome") {
                    monoId = it.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                    break
                }
            }
        }
        if (monoId == 0) return null
        val layer = res.getDrawable(monoId, null)
        return trim(draw(layer))
    }

    /**
     * Turns the icon into a white mark. Adaptive icons use their foreground layer; other icons lose
     * the background colour found along their border. Where light parts sit inside a coloured
     * shape (YouTube's white triangle in the red field), the light parts become holes.
     */
    private fun derived(pm: PackageManager, app: AppEntry): Bitmap? {
        val icon = app.activity.loadIcon(pm)
        val adaptive = icon is AdaptiveIconDrawable
        val source = draw(if (icon is AdaptiveIconDrawable) icon.foreground ?: return null else icon)
        val pixels = IntArray(SIZE * SIZE)
        source.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (!adaptive) borderColour(pixels)?.let { background ->
            for (i in pixels.indices) if (distance(pixels[i], background) < 60) pixels[i] = 0
        }
        var light = 0
        var other = 0
        for (c in pixels) if (Color.alpha(c) > 128) { if (isLight(c)) light++ else other++ }
        if (light + other < SIZE * SIZE / 100) return null
        val twoTone = light > (light + other) / 12 && other > (light + other) / 12
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = if (twoTone && isLight(c)) 0 else Color.argb(Color.alpha(c), 255, 255, 255)
        }
        source.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val mark = trim(source) ?: return null
        // A solid block means the background was not found; the colour icon is the better fallback.
        val markPixels = IntArray(mark.width * mark.height).also { mark.getPixels(it, 0, mark.width, 0, 0, mark.width, mark.height) }
        val coverage = markPixels.count { Color.alpha(it) > 128 }.toFloat() / markPixels.size
        return mark.takeIf { coverage < .9f }
    }

    private fun draw(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** Most common opaque colour along the border, or null if the border is mostly transparent. */
    private fun borderColour(pixels: IntArray): Int? {
        val counts = HashMap<Int, Int>()
        var transparent = 0
        for (i in 0 until SIZE) for (j in listOf(i, SIZE * SIZE - SIZE + i, i * SIZE, i * SIZE + SIZE - 1)) {
            val c = pixels[j]
            if (Color.alpha(c) < 200) transparent++ else {
                // Quantised so that anti-aliasing and gradients still count as one colour.
                val key = (c and 0xF0F0F0) or 0xFF000000.toInt()
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        if (transparent > SIZE * 2) return null
        return counts.maxByOrNull { it.value }?.key
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun isLight(c: Int): Boolean {
        val max = maxOf(Color.red(c), Color.green(c), Color.blue(c))
        val min = minOf(Color.red(c), Color.green(c), Color.blue(c))
        return min > 200 && max - min < 40
    }

    /** White with the source's alpha; PNG marks from the user keep all their shapes. */
    private fun whiten(bitmap: Bitmap, keepAll: Boolean): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = Color.argb(Color.alpha(pixels[i]), 255, 255, 255)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return if (keepAll) trim(out) else out
    }

    /** Crops to the visible part so that every mark fills its slot the same way. */
    private fun trim(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) if (Color.alpha(pixels[y * w + x]) > 24) {
            if (x < left) left = x; if (x > right) right = x
            if (y < top) top = y; if (y > bottom) bottom = y
        }
        if (right < left || right - left < 6 || bottom - top < 6) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
