package com.tongway.translator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import com.tongway.translator.overlay.OverlayController

/** Служба спец. возможностей: держит кружок поверх приложений и читает текст на экране. */
class TranslatorService : AccessibilityService() {

    private var overlay: OverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this)
        AppLog.i("Служба спец. возможностей запущена")
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
        overlay?.destroy()
        overlay = null
    }

    companion object {
        @Volatile
        var instance: TranslatorService? = null
            private set
    }
}
