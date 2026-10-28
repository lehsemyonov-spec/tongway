package com.tongway.translator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var modelStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var serviceStatus: TextView
    private lateinit var serviceHint: TextView
    private lateinit var btnBubble: Button
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView

    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.versionText).text = "Версия ${BuildConfig.VERSION_NAME}"
        modelStatus = findViewById(R.id.modelStatus)
        btnDownload = findViewById(R.id.btnDownload)
        serviceStatus = findViewById(R.id.serviceStatus)
        serviceHint = findViewById(R.id.serviceHint)
        btnBubble = findViewById(R.id.btnBubble)
        testInput = findViewById(R.id.testInput)
        testResult = findViewById(R.id.testResult)

        btnDownload.setOnClickListener { downloadModel() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        btnBubble.setOnClickListener { toggleBubble() }
        findViewById<Button>(R.id.btnTestTranslate).setOnClickListener { testTranslate() }
        findViewById<Button>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshService()
        refreshModel()
    }

    private fun refreshService() {
        val service = TranslatorService.instance
        val running = service != null
        serviceStatus.setText(if (running) R.string.service_on else R.string.service_off)
        serviceHint.alpha = if (running) 0.5f else 0.8f
        btnBubble.setText(if (service?.isBubbleShown == true) R.string.bubble_hide else R.string.bubble_show)
    }

    private fun refreshModel() {
        if (downloading) return
        lifecycleScope.launch {
            val ready = runCatching { App.engine.baseModelsReady() }
                .onFailure { AppLog.e("Не удалось проверить словарь", it) }
                .getOrDefault(false)
            if (downloading) return@launch
            modelStatus.setText(if (ready) R.string.model_ready else R.string.model_missing)
            btnDownload.isEnabled = !ready
        }
    }

    private fun downloadModel() {
        downloading = true
        btnDownload.isEnabled = false
        modelStatus.setText(R.string.model_downloading)
        lifecycleScope.launch {
            try {
                App.engine.downloadBaseModels()
                modelStatus.setText(R.string.model_ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Не удалось скачать словарь", e)
                modelStatus.text = "❌ Не удалось скачать: ${e.message}\nПроверьте интернет и попробуйте ещё раз."
                btnDownload.isEnabled = true
            } finally {
                downloading = false
            }
        }
    }

    private fun toggleBubble() {
        val service = TranslatorService.instance
        if (service == null) {
            Toast.makeText(this, R.string.bubble_need_service, Toast.LENGTH_LONG).show()
            return
        }
        service.setBubbleVisible(!service.isBubbleShown)
        refreshService()
    }

    private fun testTranslate() {
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) return
        testResult.text = getString(R.string.translating)
        lifecycleScope.launch {
            try {
                val result = App.engine.translate(text) { status -> testResult.text = status }
                testResult.text = if (result.alreadyTarget) getString(R.string.already_russian) else result.translated
                refreshModel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Ошибка перевода на главном экране", e)
                testResult.text = getString(R.string.translate_error, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            AppLog.e("Не удалось открыть настройки", e)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
