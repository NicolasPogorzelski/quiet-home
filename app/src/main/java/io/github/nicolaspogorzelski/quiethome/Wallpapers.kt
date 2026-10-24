package io.github.nicolaspogorzelski.quiethome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Wallpapers are not bundled. They are read from the app's external files folder
 * (/sdcard/Android/data/<package>/files/wallpapers), where tools/install-wallpapers.sh puts them.
 * Without any, a generated gradient is used. The blurred "panel" behind the glass surfaces
 * is computed here at 160x90, so no live blur runs while drawing.
 */
object Wallpapers {
    /** Id of the built-in gradient; photo ids are file names. */
    const val GRADIENT = ""

    class Loaded(val id: String, val background: Drawable, val panel: Bitmap)

    private val extensions = setOf("jpg", "jpeg", "png", "webp")
    private const val SCREEN_WIDTH = 1920
    private const val SCREEN_HEIGHT = 1080
    private const val PANEL_WIDTH = 160
    private const val PANEL_HEIGHT = 90
    private const val PANEL_BLUR_RADIUS = 6

    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var current: Loaded? = null
    private var pendingId: String? = null
    private val waiting = mutableListOf<(Loaded) -> Unit>()

    fun folder(context: Context): File? = context.getExternalFilesDir("wallpapers")

    /** The gradient first, then the installed photos by file name. */
    fun ids(context: Context): List<String> =
        listOf(GRADIENT) + folder(context)?.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in extensions }
            .map { it.name }
            .sortedBy { it.lowercase() }

    fun next(context: Context, id: String, step: Int = 1): String {
        val all = ids(context)
        return all[(all.indexOf(id).coerceAtLeast(0) + step).mod(all.size)]
    }

    /** First installed photo, or the gradient when none is installed. */
    fun initial(context: Context): String = ids(context).getOrElse(1) { GRADIENT }

    fun nameOf(id: String): String =
        if (id == GRADIENT) "Verlauf (eingebaut)" else id.substringBeforeLast('.').replace('_', ' ')

    /**
     * Main thread only. Calls back on the main thread; the result is kept so other screens
     * reuse it, and concurrent requests for the same wallpaper share one decode.
     */
    fun load(context: Context, id: String, onLoaded: (Loaded) -> Unit) {
        current?.takeIf { it.id == id }?.let { onLoaded(it); return }
        if (pendingId != id) waiting.clear()
        waiting += onLoaded
        if (pendingId == id) return
        pendingId = id
        val app = context.applicationContext
        loader.execute {
            val photo = if (id == GRADIENT) null else folder(app)?.let { decode(File(it, id)) }
            val screen = photo ?: gradient()
            val loaded = Loaded(id, BitmapDrawable(app.resources, screen), blurredPanel(screen))
            main.post {
                // A newer request replaced this one while it was decoding.
                if (pendingId != id) return@post
                pendingId = null
                current = loaded
                val callbacks = waiting.toList()
                waiting.clear()
                callbacks.forEach { it(loaded) }
            }
        }
    }

    /** Centre-crops to 16:9 and scales to 1920x1080; null if the file is missing or unreadable. */
    private fun decode(file: File): Bitmap? = try {
        file.inputStream().use { stream ->
            // The single-argument overload only exists from API 31; the SHIELD runs API 30.
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(stream, false)!!
            try {
                val crop = centreCrop16x9(decoder.width, decoder.height)
                var sample = 1
                while (crop.width() / (sample * 2) >= SCREEN_WIDTH) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val region = decoder.decodeRegion(crop, options)
                if (region.width == SCREEN_WIDTH && region.height == SCREEN_HEIGHT) region
                else Bitmap.createScaledBitmap(region, SCREEN_WIDTH, SCREEN_HEIGHT, true).also { region.recycle() }
            } finally {
                decoder.recycle()
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun centreCrop16x9(width: Int, height: Int): Rect {
        val targetHeight = width * 9 / 16
        return if (targetHeight <= height) {
            val top = (height - targetHeight) / 2
            Rect(0, top, width, top + targetHeight)
        } else {
            val targetWidth = height * 16 / 9
            val left = (width - targetWidth) / 2
            Rect(left, 0, left + targetWidth, height)
        }
    }

    /** Dark slate gradient with a soft warm light top left, drawn at panel scale and enlarged. */
    private fun gradient(): Bitmap {
        val width = 480
        val height = 270
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, width * .3f, height.toFloat(), 0xFF2A3036.toInt(), 0xFF0E1013.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(width * .2f, height * .1f, width * .7f, 0x40C8A27A, 0x00C8A27A, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun blurredPanel(screen: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(screen, PANEL_WIDTH, PANEL_HEIGHT, true)
        val pixels = IntArray(PANEL_WIDTH * PANEL_HEIGHT)
        small.getPixels(pixels, 0, PANEL_WIDTH, 0, 0, PANEL_WIDTH, PANEL_HEIGHT)
        // Three box-blur passes approximate a Gaussian blur.
        repeat(3) {
            boxBlur(pixels, PANEL_WIDTH, PANEL_HEIGHT, horizontal = true)
            boxBlur(pixels, PANEL_WIDTH, PANEL_HEIGHT, horizontal = false)
        }
        val panel = small.copy(Bitmap.Config.ARGB_8888, true)
        if (small !== screen) small.recycle()
        panel.setPixels(pixels, 0, PANEL_WIDTH, 0, 0, PANEL_WIDTH, PANEL_HEIGHT)
        return panel
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val r = PANEL_BLUR_RADIUS
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val out = IntArray(length)
        for (line in 0 until lines) {
            fun index(i: Int) = if (horizontal) line * width + i.coerceIn(0, length - 1) else i.coerceIn(0, length - 1) * width + line
            var red = 0
            var green = 0
            var blue = 0
            for (i in -r..r) {
                val c = pixels[index(i)]
                red += c shr 16 and 0xFF; green += c shr 8 and 0xFF; blue += c and 0xFF
            }
            val size = 2 * r + 1
            for (i in 0 until length) {
                out[i] = (0xFF shl 24) or (red / size shl 16) or (green / size shl 8) or (blue / size)
                val add = pixels[index(i + r + 1)]
                val remove = pixels[index(i - r)]
                red += (add shr 16 and 0xFF) - (remove shr 16 and 0xFF)
                green += (add shr 8 and 0xFF) - (remove shr 8 and 0xFF)
                blue += (add and 0xFF) - (remove and 0xFF)
            }
            for (i in 0 until length) pixels[index(i)] = out[i]
        }
    }
}
