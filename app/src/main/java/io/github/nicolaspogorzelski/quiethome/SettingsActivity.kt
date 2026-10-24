package io.github.nicolaspogorzelski.quiethome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One row per setting. Left/Right or OK changes the value; it is saved immediately. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        applyDimmedWallpaper(this, prefs)

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(heading(this, "Einstellungen"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            bottomMargin = dp(24)
        })

        val targets = listOf<String?>(null) + Apps.installed(this).map { it.packageName }
        val names = Apps.installed(this).associate { it.packageName to it.label }

        addChoice(rows, "Hintergrund", { Wallpapers.nameOf(currentWallpaper(this, prefs)) }) { step ->
            prefs.wallpaper = Wallpapers.next(this, currentWallpaper(this, prefs), step)
            applyDimmedWallpaper(this, prefs)
        }
        addChoice(rows, "App-Icons", { if (prefs.iconStyle == IconStyle.QUIET) "Quiet" else "Classic" }) {
            prefs.iconStyle = if (prefs.iconStyle == IconStyle.QUIET) IconStyle.CLASSIC else IconStyle.QUIET
        }
        addChoice(rows, "Hintergrund wechseln", { if (prefs.rotateWallpaper) "Bei jeder Rückkehr" else "Nie" }) {
            prefs.rotateWallpaper = !prefs.rotateWallpaper
        }
        addChoice(rows, "Netflix-Taste startet", { prefs.netflixTarget?.let { names[it] ?: it } ?: "Netflix (unverändert)" }) { step ->
            val index = targets.indexOf(prefs.netflixTarget).coerceAtLeast(0)
            prefs.netflixTarget = targets[(index + step).mod(targets.size)]
        }
        addAction(rows, "Tastenbelegung aktiv", { if (KeyRemapService.isEnabled(this)) "Ja" else "Nein – OK öffnet Bedienungshilfen" }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addAction(rows, "Android-Einstellungen", { "" }) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        addAction(rows, "Version", { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }) {}

        val root = FrameLayout(this)
        root.addView(rows, FrameLayout.LayoutParams(dp(560), WRAP_CONTENT).apply {
            leftMargin = dp(104); topMargin = dp(48)
        })
        setContentView(root)
        rows.getChildAt(1).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    private fun addChoice(rows: LinearLayout, title: String, value: () -> String, change: (Int) -> Unit) {
        val row = addAction(rows, title, value) { change(1) }
        row.setOnKeyListener { _, keyCode, event ->
            val step = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                change(step)
                refreshers.forEach { it() }
            }
            true
        }
    }

    private fun addAction(rows: LinearLayout, title: String, value: () -> String, action: () -> Unit): TextView {
        lateinit var row: TextView
        row = pill(this, "") {
            action()
            refreshers.forEach { it() }
        }.apply {
            textSize = 17f
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        val refresh = {
            val v = value()
            row.text = if (v.isEmpty()) title else "$title   ·   $v"
        }
        refreshers += refresh
        refresh()
        rows.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
        return row
    }
}
