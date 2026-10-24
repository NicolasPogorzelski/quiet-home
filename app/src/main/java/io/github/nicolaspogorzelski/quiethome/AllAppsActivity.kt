package io.github.nicolaspogorzelski.quiethome

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Every launchable app in a five-column grid. OK opens; a long press on OK adds to or removes
 * from Home. The SHIELD keeps the remote's Menu key for itself, so it never reaches apps.
 */
class AllAppsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val labels = HashMap<String, TextView>()
    private var firstTile: TileView? = null
    private lateinit var style: IconStyle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        style = prefs.iconStyle
        applyDimmedWallpaper(this, prefs)

        val root = FrameLayout(this)
        root.addView(heading(this, "Alle Apps"), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(104); topMargin = dp(48)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Style.MUTE)
            text = "OK: öffnen   OK lange drücken: auf Home / von Home"
        }
        root.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(104); topMargin = dp(92)
        })

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(104), dp(16), dp(104), dp(48))
        }
        val home = prefs.homeApps.toMutableSet()
        Apps.installed(this).chunked(COLUMNS).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            chunk.forEach { app -> line.addView(cell(app, app.packageName in home)) }
            grid.addView(line)
        }
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(32))
            clipChildren = false
            addView(grid)
        }
        root.addView(scroller, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(120) })
        setContentView(root)
        // Without this, focus starts nowhere and the first key press only enters the grid.
        firstTile?.requestFocus()
    }

    private fun cell(app: AppEntry, onHome: Boolean): LinearLayout {
        val label = TextView(this).apply {
            textSize = 13f
            setTextColor(Style.INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
        }
        labels[app.packageName] = label
        showLabel(app, onHome)
        val tile = TileView(this, app, style, showName = false).also { if (firstTile == null) firstTile = it }.apply {
            isLongClickable = true
            setOnClickListener {
                if (!Apps.launch(this@AllAppsActivity, app.packageName)) status.text = "${app.label} lässt sich nicht starten"
            }
            setOnLongClickListener { toggleHome(app); true }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(tile, LinearLayout.LayoutParams(dp(136), dp(if (style == IconStyle.QUIET) 84 else 76)))
            addView(label, LinearLayout.LayoutParams(dp(136), WRAP_CONTENT).apply { topMargin = dp(6) })
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = dp(14); bottomMargin = dp(18)
            }
        }
    }

    private fun toggleHome(app: AppEntry) {
        val home = prefs.homeApps.toMutableList()
        val nowOnHome = if (app.packageName in home) {
            home.remove(app.packageName); false
        } else {
            home.add(app.packageName); true
        }
        prefs.homeApps = home
        showLabel(app, nowOnHome)
        status.text = if (nowOnHome) "${app.label} ist jetzt auf Home" else "${app.label} ist nicht mehr auf Home"
    }

    private fun showLabel(app: AppEntry, onHome: Boolean) {
        labels[app.packageName]?.text = if (onHome) "● ${app.label}" else app.label
    }

    private companion object {
        const val COLUMNS = 5
    }
}

fun heading(activity: Activity, text: String) = TextView(activity).apply {
    this.text = text
    textSize = 28f
    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    setTextColor(Style.INK)
}

/** Shared background for secondary screens: the current wallpaper under a dark veil. */
fun applyDimmedWallpaper(activity: Activity, prefs: Prefs) {
    activity.window.setBackgroundDrawable(ColorDrawable(0xFF101214.toInt()))
    Wallpapers.load(activity, currentWallpaper(activity, prefs)) { loaded ->
        activity.window.setBackgroundDrawable(LayerDrawable(arrayOf(loaded.background, ColorDrawable(0xA6101214.toInt()))))
    }
}

/** The chosen wallpaper if its file still exists, else the first installed one or the gradient. */
fun currentWallpaper(context: android.content.Context, prefs: Prefs): String {
    val chosen = prefs.wallpaper
    return if (chosen != null && chosen in Wallpapers.ids(context)) chosen else Wallpapers.initial(context)
}
