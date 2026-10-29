package com.tongway.translator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tongway.translator.ui.TechBackgroundDrawable

/** Пошаговая инструкция, как бесплатно получить свой ключ Groq или OpenRouter (для себя и друзей). */
class KeyHelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_key_help)
        val provider = CloudProvider.entries.firstOrNull { it.id == intent.getStringExtra(EXTRA_PROVIDER) }
            ?: CloudProvider.OPENROUTER
        val groq = provider == CloudProvider.GROQ
        findViewById<TextView>(R.id.helpProvider).text = provider.title
        findViewById<TextView>(R.id.helpSubtitle).setText(if (groq) R.string.help_subtitle_groq else R.string.help_subtitle)
        findViewById<TextView>(R.id.helpSteps).setText(if (groq) R.string.help_steps_groq else R.string.help_steps)
        findViewById<TextView>(R.id.btnOpenSite).text = getString(R.string.help_open, provider.title)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnOpenSite).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keysPage))) }
                .onFailure { AppLog.e("Не удалось открыть сайт ${provider.title}", it) }
        }
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
    }
}
