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

    /** Text colours for the areas of Home that sit directly on the wallpaper. */
    class Inks(val clock: Ink, val controls: Ink, val name: Ink)

    /** [softPanel] backs the Quiet dock: CleanFrame's lighter "Soft" material at quarter resolution. */
    class Loaded(val id: String, val background: Drawable, val panel: Bitmap, val softPanel: Bitmap, val inks: Inks)

    private val extensions = setOf("jpg", "jpeg", "png", "webp")
    private const val SCREEN_WIDTH = 1920
    private const val SCREEN_HEIGHT = 1080
    private const val PANEL_WIDTH = 160
    private const val PANEL_HEIGHT = 90
    private const val PANEL_BLUR_RADIUS = 6
    private const val SOFT_WIDTH = 480
    private const val SOFT_HEIGHT = 270
    // Quiet dock transparency, tuned on the device; CleanFrame's Soft uses 1, .35 and .28-.6.
    private const val SOFT_BLUR = 1
    private const val SOFT_SCRIM = .5f
    private const val SOFT_MEAN_MIX = .25f
    private const val SOFT_TINT_MIN = .14f
    private const val SOFT_TINT_MAX = .35f
    private const val MIN_CONTRAST = 4.5

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
            val panel = blurredPanel(screen)
            val loaded = Loaded(id, BitmapDrawable(app.resources, screen), panel, softPanel(screen), inks(panel))
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

    /**
     * Picks light or dark ink per area by WCAG contrast against the blurred panel. A mean colour
     * hides mixed areas (bright rock beside blue sky), so each ink is judged by the contrast it
     * keeps on the worst fifth of the area. Areas are in panel pixels (1 px = 6 dp on a
     * 960x540 dp screen) and cover the clock, the controls top right and the app name.
     * Below WCAG's 4.5:1 the area is busy or mid-toned, and the ink gets a halo.
     */
    private fun inks(panel: Bitmap) = Inks(
        clock = inkFor(panel, Rect(17, 7, 50, 22)),
        controls = inkFor(panel, Rect(117, 9, 144, 15)),
        name = inkFor(panel, Rect(21, 49, 67, 55)),
    )

    private fun inkFor(panel: Bitmap, area: Rect): Ink {
        val backgrounds = DoubleArray(area.width() * area.height())
        var n = 0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            backgrounds[n++] = luminance(panel.getPixel(x, y))
        }
        val light = luminance(Style.INK)
        val dark = luminance(Style.SOLID_INK)
        fun worstFifth(ink: Double) = backgrounds.map { contrast(ink, it) }.sorted()[backgrounds.size / 5]
        val lightContrast = worstFifth(light)
        val darkContrast = worstFifth(dark)
        return if (lightContrast >= darkContrast) Ink(Style.INK, halo = lightContrast < MIN_CONTRAST)
        else Ink(Style.SOLID_INK, halo = darkContrast < MIN_CONTRAST)
    }

    private fun luminance(color: Int) = luminance(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)

    /** WCAG 2 relative luminance. */
    private fun luminance(r: Int, g: Int, b: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    /**
     * CleanFrame's "Soft" dock material (scripts/build-materials.mjs, MIT), at a quarter of
     * 1920x1080: a scrim darkening the lower half, a 14 px blur, saturation 1.12, colours pulled
     * 35 % towards the dock band's mean, then a tint of rgb(4,10,12) between 28 and 60 % chosen
     * from the band's brightest 5 %. Unlike the heavily blurred panel, shapes stay recognisable.
     */
    private fun softPanel(screen: Bitmap): Bitmap {
        val w = SOFT_WIDTH
        val h = SOFT_HEIGHT
        val small = Bitmap.createScaledBitmap(screen, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== screen) small.recycle()
        // Scrim from y = 560 px: transparent, 42 % at 70 % of its height, 52 % at the bottom.
        val scrimTop = 140
        for (y in scrimTop until h) {
            val t = (y - scrimTop).toFloat() / (h - scrimTop)
            val alpha = (if (t < .7f) .42f * t / .7f else .42f + .1f * (t - .7f) / .3f) * SOFT_SCRIM
            for (x in 0 until w) px[y * w + x] = blend(px[y * w + x], 4, 10, 12, alpha)
        }
        // Three box passes of radius SOFT_BLUR approximate a Gaussian; 3 is CleanFrame's 14 px at full size.
        repeat(3) {
            boxBlur(px, w, h, horizontal = true, radius = SOFT_BLUR)
            boxBlur(px, w, h, horizontal = false, radius = SOFT_BLUR)
        }
        for (i in px.indices) px[i] = saturate(px[i], 1.12f)
        // Dock band: y 676..908, x 96..1824 at full size.
        var mr = 0L; var mg = 0L; var mb = 0L; var count = 0
        for (y in 169 until 227) for (x in 24 until 456) {
            val c = px[y * w + x]; mr += c shr 16 and 0xFF; mg += c shr 8 and 0xFF; mb += c and 0xFF; count++
        }
        val meanR = mr / count.toFloat(); val meanG = mg / count.toFloat(); val meanB = mb / count.toFloat()
        for (i in px.indices) {
            val c = px[i]
            val keep = 1 - SOFT_MEAN_MIX
            px[i] = rgb((c shr 16 and 0xFF) * keep + meanR * SOFT_MEAN_MIX, (c shr 8 and 0xFF) * keep + meanG * SOFT_MEAN_MIX, (c and 0xFF) * keep + meanB * SOFT_MEAN_MIX)
        }
        val lumas = ArrayList<Float>()
        for (y in 180 until 227 step 1) for (x in 28 until 452 step 1) {
            val c = px[y * w + x]
            lumas += .2126f * (c shr 16 and 0xFF) + .7152f * (c shr 8 and 0xFF) + .0722f * (c and 0xFF)
        }
        lumas.sort()
        val high = lumas[(lumas.size * .95f).toInt()]
        val tint = ((high - 94f) / (high - 8.87f)).coerceIn(SOFT_TINT_MIN, SOFT_TINT_MAX)
        for (i in px.indices) px[i] = blend(px[i], 4, 10, 12, tint)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blend(c: Int, r: Int, g: Int, b: Int, alpha: Float) = rgb(
        (c shr 16 and 0xFF) * (1 - alpha) + r * alpha,
        (c shr 8 and 0xFF) * (1 - alpha) + g * alpha,
        (c and 0xFF) * (1 - alpha) + b * alpha,
    )

    private fun saturate(c: Int, amount: Float): Int {
        val r = (c shr 16 and 0xFF).toFloat(); val g = (c shr 8 and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
        val l = .2126f * r + .7152f * g + .0722f * b
        return rgb(l + (r - l) * amount, l + (g - l) * amount, l + (b - l) * amount)
    }

    private fun rgb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, horizontal: Boolean, radius: Int = PANEL_BLUR_RADIUS) {
        val r = radius
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
