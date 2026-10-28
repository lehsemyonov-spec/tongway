package com.tongway.translator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import com.tongway.translator.overlay.OverlayController

/** Служба спец. возможностей: держит кружок поверх приложений и читает текст на экране. */
class TranslatorService : AccessibilityService() {

    private var overlay: OverlayController? = null

    /** Настройки кружка меняются на экране «Настройки» — применяем сразу. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_BUBBLE_SIZE || key == Prefs.KEY_BUBBLE_ALPHA) overlay?.applySettings()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        AppLog.i("Служба спец. возможностей запущена")
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        if (Prefs.isBubbleEnabled(this)) overlay?.showBubble()
    }

    val isBubbleShown: Boolean get() = overlay?.isBubbleShown == true

    fun setBubbleVisible(visible: Boolean) {
        Prefs.setBubbleEnabled(this, visible)
        if (visible) overlay?.showBubble() else overlay?.hideBubble()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onScreenChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        overlay?.destroy()
        overlay = null
        BubbleTileService.refresh(this)
    }

    companion object {
        @Volatile
        var instance: TranslatorService? = null
            private set
    }
}
