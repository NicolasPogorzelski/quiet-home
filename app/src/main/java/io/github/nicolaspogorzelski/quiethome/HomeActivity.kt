package io.github.nicolaspogorzelski.quiethome

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * Home screen: clock top left, controls top right, a glass dock with the Home apps.
 * Geometry follows CleanFrame's Dock layout on a 960x540 dp screen.
 */
class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var clock: TextView
    private lateinit var focusName: TextView
    private lateinit var hint: TextView
    private lateinit var dock: GlassFrame
    private lateinit var row: LinearLayout
    private lateinit var controls: List<TextView>

    private var installed: List<AppEntry> = emptyList()
    private var appsDirty = true
    private var lastTile: TileView? = null
    private var lastControl: TextView? = null
    private var moving: TileView? = null
    private var leftHome = false

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appsDirty = true
            if (hasWindowFocus()) reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildViews()
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        applyWallpaper()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        if (leftHome && prefs.rotateWallpaper) {
            prefs.wallpaper = Wallpapers.next(this, currentWallpaper(this, prefs))
            applyWallpaper()
        }
        leftHome = false
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(tick, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        if (appsDirty) reloadApps()
        // Settings may have changed the wallpaper while Home was paused.
        applyWallpaper()
    }

    override fun onPause() {
        unregisterReceiver(tick)
        endMove(save = true)
        super.onPause()
    }

    override fun onStop() {
        leftHome = true
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on Home: return to the start of the dock.
        endMove(save = true)
        (row.getChildAt(0) as? TileView)?.let {
            it.requestFocus()
            (dock.getChildAt(0) as HorizontalScrollView).smoothScrollTo(0, 0)
        }
    }

    @Deprecated("Back on Home has nowhere to go")
    override fun onBackPressed() {
        if (moving != null) endMove(save = true)
    }

    private fun buildViews() {
        root = FrameLayout(this)

        clock = TextView(this).apply {
            textSize = 64f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Style.INK)
            alpha = .9f
            fontFeatureSettings = "tnum"
            letterSpacing = -.01f
            setShadowLayer(dp(14f), 0f, dp(2f), 0x3D000000)
        }
        root.addView(clock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(104); topMargin = dp(40)
        })

        controls = listOf(
            pill(this, "Alle Apps") { startActivity(Intent(this, AllAppsActivity::class.java)) },
            pill(this, "Einstellungen") { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.forEach { control ->
            control.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    lastControl = control
                    (lastTile ?: row.getChildAt(0))?.requestFocus()
                    true
                } else false
            }
            controlRow.addView(control, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        }
        root.addView(controlRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END).apply {
            rightMargin = dp(96); topMargin = dp(52)
        })

        focusName = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Style.INK)
            letterSpacing = -.015f
            setShadowLayer(dp(7f), 0f, dp(1.5f), 0x3D0E0A06)
        }
        root.addView(focusName, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(128); topMargin = dp(296)
        })

        dock = GlassFrame(this)
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            clipChildren = false
            clipToPadding = false
            isSmoothScrollingEnabled = true
        }
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), 0, dp(24), 0)
        }
        scroller.addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        dock.addView(scroller, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dock.clipChildren = false
        root.addView(dock, FrameLayout.LayoutParams(dp(752), dp(116)).apply {
            leftMargin = dp(104); topMargin = dp(338)
        })

        hint = TextView(this).apply {
            textSize = 14f
            setTextColor(Style.MUTE)
            text = "Verschieben: ◀ ▶   Fertig: OK"
            visibility = View.INVISIBLE
            setShadowLayer(dp(6f), 0f, dp(1f), 0x52000000)
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = dp(128); topMargin = dp(470)
        })

        setContentView(root)
    }

    private fun applyWallpaper() {
        Wallpapers.load(this, currentWallpaper(this, prefs)) { loaded ->
            window.setBackgroundDrawable(loaded.background)
            dock.setPanel(loaded.panel)
        }
    }

    private fun updateClock() {
        clock.text = DateFormat.getTimeFormat(this).format(Date())
    }

    private fun reloadApps() {
        appsDirty = false
        installed = Apps.installed(this)
        if (!prefs.homeAppsInitialised) prefs.homeApps = Apps.defaultHome(installed)
        val byPackage = installed.associateBy { it.packageName }
        val focusedPackage = (currentFocus as? TileView)?.app?.packageName ?: lastTile?.app?.packageName
        row.removeAllViews()
        lastTile = null
        for (pkg in prefs.homeApps) {
            val app = byPackage[pkg] ?: continue
            row.addView(makeTile(app), LinearLayout.LayoutParams(dp(128), dp(72)).apply {
                leftMargin = dp(8); rightMargin = dp(8)
            })
        }
        val target = (0 until row.childCount).map { row.getChildAt(it) as TileView }
            .firstOrNull { it.app.packageName == focusedPackage } ?: row.getChildAt(0)
        target?.requestFocus() ?: controls.first().requestFocus()
    }

    private fun makeTile(app: AppEntry) = TileView(this, app).apply {
        setOnFocusChangeListener { v, focused ->
            if (focused) {
                lastTile = v as TileView
                focusName.text = app.label
                focusName.animate().alpha(1f).setDuration(120).start()
            } else if (!row.hasFocus()) {
                focusName.animate().alpha(0f).setDuration(120).start()
            }
        }
        setOnClickListener {
            if (moving == this) endMove(save = true)
            else if (!Apps.launch(this@HomeActivity, app.packageName)) focusName.text = "${app.label} lässt sich nicht starten"
        }
        setOnKeyListener { v, keyCode, event -> onTileKey(v as TileView, keyCode, event) }
    }

    private fun onTileKey(tile: TileView, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val index = row.indexOfChild(tile)
        if (moving == tile) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (index > 0) swap(index, index - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (index < row.childCount - 1) swap(index, index + 1)
                KeyEvent.KEYCODE_MENU -> endMove(save = true)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
                else -> return false
            }
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                moving = tile
                tile.lifted = true
                hint.visibility = View.VISIBLE
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                (lastControl ?: controls.first()).requestFocus()
                true
            }
            // No wrapping at either end of the dock.
            KeyEvent.KEYCODE_DPAD_LEFT -> index == 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> index == row.childCount - 1
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
    }

    private fun swap(from: Int, to: Int) {
        val tile = row.getChildAt(from)
        row.removeViewAt(from)
        row.addView(tile, to)
        tile.requestFocus()
    }

    private fun endMove(save: Boolean) {
        val tile = moving ?: return
        moving = null
        tile.lifted = false
        hint.visibility = View.INVISIBLE
        if (save) {
            val shown = (0 until row.childCount).map { (row.getChildAt(it) as TileView).app.packageName }
            // Keep Home entries of apps that are currently uninstalled or disabled, after the visible ones.
            prefs.homeApps = shown + prefs.homeApps.filter { it !in shown }
        }
    }
}
