package com.tongway.translator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tongway.translator.ui.TechBackgroundDrawable

/** Пошаговая инструкция, как бесплатно получить свой ключ OpenRouter (для себя и друзей). */
class KeyHelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_key_help)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnOpenSite).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OpenRouterClient.KEYS_PAGE))) }
                .onFailure { AppLog.e("Не удалось открыть сайт OpenRouter", it) }
        }
    }
}
