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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
 * then CleanFrame's white plate gradient and a light top edge.
 */
class GlassFrame(context: Context) : FrameLayout(context) {
    var radius = context.dp(24f)
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

    init {
        setWillNotDraw(false)
    }

    fun setPanel(bitmap: Bitmap) {
        panel = bitmap
        backdrop.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val h0 = h.toFloat()
        tint.shader = LinearGradient(
            0f, 0f, w * .18f, h0,
            intArrayOf(Color.argb(77, 255, 255, 255), Color.argb(41, 255, 255, 255), Color.argb(56, 255, 255, 255)),
            floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP,
        )
        edge.shader = LinearGradient(
            0f, 0f, 0f, h0 * .35f,
            Color.argb(87, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        panel?.let { bitmap ->
            getLocationInWindow(location)
            val root = rootView
            shaderMatrix.setScale(root.width / bitmap.width.toFloat(), root.height / bitmap.height.toFloat())
            shaderMatrix.postTranslate(-location[0].toFloat(), -location[1].toFloat())
            backdrop.shader.setLocalMatrix(shaderMatrix)
            canvas.drawRoundRect(rect, radius, radius, backdrop)
        }
        canvas.drawRoundRect(rect, radius, radius, tint)
        rect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
        canvas.drawRoundRect(rect, radius, radius, edge)
    }
}

/** An app banner with rounded corners; focus lifts it to full opacity. */
@SuppressLint("ViewConstructor")
class TileView(context: Context, val app: AppEntry) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(0x33000000)
        outlineProvider = roundedOutline(context.dp(12f))
        clipToOutline = true
    }
    var lifted = false
        set(value) {
            field = value
            refresh()
        }

    init {
        isFocusable = true
        alpha = Style.TILE_REST_ALPHA
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Apps.loadBanner(context, app) { image.setImageDrawable(it) }
        contentDescription = app.label
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        refresh()
    }

    private fun refresh() = when {
        lifted -> animateTo(1.12f, 1f, -context.dp(8f))
        isFocused -> animateTo(Style.TILE_FOCUS_SCALE, 1f)
        else -> animateTo(1f, Style.TILE_REST_ALPHA)
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
    setOnFocusChangeListener { v, focused ->
        (v.background as GradientDrawable).setColor(if (focused) Style.SOLID else Color.TRANSPARENT)
        (v as TextView).setTextColor(if (focused) Style.SOLID_INK else Style.INK)
        v.setShadowLayer(if (focused) 0f else context.dp(6f), 0f, context.dp(1f), 0x52000000)
    }
}

fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
