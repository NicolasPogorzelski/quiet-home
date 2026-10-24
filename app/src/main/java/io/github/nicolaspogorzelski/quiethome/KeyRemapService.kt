package io.github.nicolaspogorzelski.quiethome

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sends the remote's Netflix key to another app. On the SHIELD remote
 * (Vendor_0955_Product_7217.kl) that key is mapped to BUTTON_12.
 */
class KeyRemapService : AccessibilityService() {
    private lateinit var prefs: Prefs

    override fun onServiceConnected() {
        prefs = Prefs(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_12) return false
        val target = prefs.netflixTarget ?: return false
        if (Apps.launchIntent(this, target) == null) return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) Apps.launch(this, target)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val own = ComponentName(context, KeyRemapService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}
