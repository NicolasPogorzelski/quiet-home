package io.github.nicolaspogorzelski.quiethome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Design tokens taken from CleanFrame's style.css (px values halved for 320 dpi). */
object Style {
    const val INK = 0xFFF4F4F1.toInt()
    const val SOLID = 0xFFF3F3EF.toInt()
    const val SOLID_INK = 0xFF141618.toInt()
    const val MUTE = 0xB3F4F4F1.toInt()
    const val DURATION = 180L
    val EASE = PathInterpolator(.2f, .7f, .2f, 1f)
    const val TILE_REST_ALPHA = .62f
    const val TILE_FOCUS_SCALE = 1.06f
}

fun Context.dp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
fun Context.dp(value: Int): Int = dp(value.toFloat()).toInt()

fun View.animateTo(scale: Float, alpha: Float, translationY: Float = 0f) {
    animate().scaleX(scale).scaleY(scale).alpha(alpha).translationY(translationY)
        .setDuration(Style.DURATION).setInterpolator(Style.EASE).start()
}

/**
 * Frosted-glass plate: draws the matching region of the pre-blurred panel image,
 * then CleanFrame's white plate gradient (or a dark tint for the Quiet style) and a light top edge.
 */
class GlassFrame(context: Context) : FrameLayout(context) {
    var radius = context.dp(24f)
    /** LIGHT tints the heavily blurred panel; SOFT shows CleanFrame's Soft material as is. */
    enum class Material { LIGHT, SOFT }

    var material = Material.LIGHT
        set(value) {
            if (field == value) return
            field = value
            updateShaders(width, height)
            invalidate()
        }
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
    }
    private val rect = RectF()
    private val location = IntArray(2)
    private val shaderMatrix = Matrix()
    private var panel: Bitmap? = null
    private var softPanel: Bitmap? = null
    private val softBackdrop = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        setWillNotDraw(false)
    }

    fun setPanel(bitmap: Bitmap, soft: Bitmap? = null) {
        panel = bitmap
        backdrop.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        softPanel = soft
        softBackdrop.shader = soft?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShaders(w, h)
    }

    private fun updateShaders(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val h0 = h.toFloat()
        val stops = when (material) {
            Material.SOFT -> intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT)
            Material.LIGHT -> intArrayOf(Color.argb(77, 255, 255, 255), Color.argb(41, 255, 255, 255), Color.argb(56, 255, 255, 255))
        }
        tint.shader = LinearGradient(0f, 0f, w * .18f, h0, stops, floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
        if (material == Material.SOFT) {
            // No edge: CleanFrame's example shows none, the dock is set off by its darkening alone.
            edge.shader = null
            edge.color = Color.TRANSPARENT
        } else {
            edge.shader = LinearGradient(
                0f, 0f, 0f, h0 * .35f,
                Color.argb(87, 255, 255, 255),
                Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val (bitmap, paint) = if (material == Material.SOFT && softPanel != null) softPanel to softBackdrop else panel to backdrop
        bitmap?.let {
            getLocationInWindow(location)
            val root = rootView
            shaderMatrix.setScale(root.width / it.width.toFloat(), root.height / it.height.toFloat())
            shaderMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
            paint.shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        canvas.drawRoundRect(rect, radius, radius, tint)
        rect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
        canvas.drawRoundRect(rect, radius, radius, edge)
    }
}

/**
 * One app. CLASSIC shows its banner with rounded corners; QUIET follows CleanFrame: a white mark,
 * optionally the app name below it, and a frosted plate behind the focused app. Focus lifts the
 * tile to full opacity.
 */
@SuppressLint("ViewConstructor")
class TileView(
    context: Context,
    val app: AppEntry,
    private val style: IconStyle = IconStyle.CLASSIC,
    showName: Boolean = true,
) : FrameLayout(context) {
    private val plate = GradientDrawable().apply {
        cornerRadius = context.dp(16f)
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
    }
    private val restAlpha = if (style == IconStyle.QUIET) QUIET_REST_ALPHA else Style.TILE_REST_ALPHA
    private var restPlate = Color.TRANSPARENT
    var lifted = false
        set(value) {
            field = value
            refresh()
        }

    init {
        isFocusable = true
        alpha = restAlpha
        contentDescription = app.label
        if (style == IconStyle.CLASSIC) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(0x33000000)
                outlineProvider = roundedOutline(context.dp(12f))
                clipToOutline = true
            }
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            Apps.loadBanner(context, app) { image.setImageDrawable(it) }
        } else {
            background = plate
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val mark = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            // Wider than tall: wordmarks keep their height instead of shrinking to fit a square.
            val markHeight = context.dp(if (showName) 34 else 40)
            column.addView(mark, LinearLayout.LayoutParams(markHeight * 2, markHeight))
            if (showName) {
                column.addView(TextView(context).apply {
                    text = app.label
                    textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(Style.INK)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(8)
                    leftMargin = context.dp(6); rightMargin = context.dp(6)
                })
            }
            addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            // Without names (All apps) a faint plate gives the grid its structure.
            restPlate = if (showName) Color.TRANSPARENT else 0x14FFFFFF
            plate.setColor(restPlate)
            Marks.load(context, app) { drawable -> mark.setImageDrawable(drawable) }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refresh()
    }

    private fun refresh() {
        if (style == IconStyle.QUIET) {
            if (isFocused || lifted) plate.colors = FOCUS_PLATE else plate.setColor(restPlate)
        }
        when {
            lifted -> animateTo(1.12f, 1f, -context.dp(8f))
            isFocused -> animateTo(if (style == IconStyle.QUIET) QUIET_FOCUS_SCALE else Style.TILE_FOCUS_SCALE, 1f)
            else -> animateTo(1f, restAlpha)
        }
    }

    private companion object {
        const val QUIET_REST_ALPHA = .8f
        const val QUIET_FOCUS_SCALE = 1.04f
        // CleanFrame's Frost focus plate: white fading from 26 % to 8 % and back up to 10 %.
        val FOCUS_PLATE = intArrayOf(0x42FFFFFF, 0x14FFFFFF, 0x1AFFFFFF)
    }
}

/**
 * Colour of text that sits directly on the wallpaper. [halo] is set where neither light nor dark
 * text reaches 4.5:1 against the photo; the text then gets a wide soft shadow in the opposite tone.
 */
data class Ink(val color: Int, val halo: Boolean = false)

fun TextView.setInk(ink: Ink, shadowRadius: Float) {
    setTextColor(ink.color)
    val light = ink.color == Style.INK
    when {
        // A tight, dense glow: a wide blur spreads the thin strokes' shadow until it disappears.
        ink.halo -> setShadowLayer(context.dp(4f), 0f, 0f, if (light) 0xB3000000.toInt() else 0xB3FFFFFF.toInt())
        light -> setShadowLayer(shadowRadius, 0f, context.dp(1f), 0x52000000)
        else -> setShadowLayer(0f, 0f, 0f, 0)
    }
}

/** Text control in the CleanFrame style: plain ink at rest, solid plate when focused. */
fun pill(context: Context, label: String, onClick: () -> Unit): TextView = TextView(context).apply {
    text = label
    textSize = 15f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(Style.INK)
    gravity = Gravity.CENTER
    val padH = context.dp(18)
    val padV = context.dp(8)
    setPadding(padH, padV, padH, padV)
    isFocusable = true
    setShadowLayer(context.dp(6f), 0f, context.dp(1f), 0x52000000)
    background = GradientDrawable().apply {
        cornerRadius = context.dp(20f)
        setColor(Color.TRANSPARENT)
    }
    setOnClickListener { onClick() }
    tag = Ink(Style.INK)
    setOnFocusChangeListener { v, focused ->
        (v.background as GradientDrawable).setColor(if (focused) Style.SOLID else Color.TRANSPARENT)
        if (focused) (v as TextView).setInk(Ink(Style.SOLID_INK), 0f) else (v as TextView).setInk(v.tag as Ink, context.dp(6f))
    }
}

/** Changes a pill's resting ink, e.g. to dark on a bright wallpaper. */
fun TextView.setRestInk(ink: Ink) {
    tag = ink
    if (!isFocused) setInk(ink, context.dp(6f))
}

fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
