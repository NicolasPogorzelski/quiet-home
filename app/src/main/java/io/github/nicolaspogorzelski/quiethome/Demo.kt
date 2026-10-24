package io.github.nicolaspogorzelski.quiethome

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Placeholder apps for the demo build (-PquietHome.demo=true): screenshots without real logos. */
object Demo {
    private val tiles = listOf(
        "Filme" to 0xFF3B5B8C,
        "Serien" to 0xFF8C3B45,
        "Musik" to 0xFF2F7A5C,
        "Sport" to 0xFFB8732A,
        "Nachrichten" to 0xFF464C66,
        "Kinder" to 0xFF7A4A8C,
        "Spiele" to 0xFF2A6A7A,
        "Fotos" to 0xFF6B6A3A,
        "Podcasts" to 0xFF5A3A6B,
        "Radio" to 0xFF3A6B4F,
    )

    val apps: List<AppEntry> = tiles.map { (name, _) ->
        val info = ActivityInfo().apply { packageName = "demo.${name.lowercase()}" }
        AppEntry(info.packageName, name, info, leanback = true)
    }

    /** Simple geometric marks for the Quiet style; deliberately no letters or logos. */
    fun mark(context: Context, app: AppEntry): Drawable {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val path = android.graphics.Path()
        when (apps.indexOf(app) % 5) {
            0 -> canvas.drawCircle(64f, 64f, 56f, paint)
            1 -> canvas.drawRoundRect(10f, 10f, 118f, 118f, 26f, 26f, paint)
            2 -> { path.moveTo(64f, 6f); path.lineTo(122f, 116f); path.lineTo(6f, 116f); path.close(); canvas.drawPath(path, paint) }
            3 -> { path.moveTo(64f, 4f); path.lineTo(124f, 64f); path.lineTo(64f, 124f); path.lineTo(4f, 64f); path.close(); canvas.drawPath(path, paint) }
            else -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = 20f; canvas.drawCircle(64f, 64f, 50f, paint) }
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    fun banner(context: Context, app: AppEntry): Drawable {
        val color = tiles.first { it.first == app.label }.second.toInt()
        val bitmap = Bitmap.createBitmap(272, 152, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xF2FFFFFF.toInt()
            textSize = 30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        canvas.drawText(app.label, 136f, 76f - (paint.descent() + paint.ascent()) / 2, paint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
