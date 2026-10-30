package com.tongway.translator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tongway.translator.ModelDownloadWatcher.Companion.formatMb
import com.tongway.translator.ui.TechBackgroundDrawable
import com.tongway.translator.ui.TechProgressView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var modelChip: TextView
    private lateinit var modelProgress: TechProgressView
    private lateinit var modelProgressText: TextView
    private lateinit var btnDownload: TextView
    private lateinit var serviceChip: TextView
    private lateinit var serviceHint: TextView
    private lateinit var bubbleChip: TextView
    private lateinit var btnBubble: TextView
    private lateinit var statusRobot: ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView

    private var downloading = false
    private var modelReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.versionText).text = "v${BuildConfig.VERSION_NAME}"
        modelChip = findViewById(R.id.modelChip)
        modelProgress = findViewById(R.id.modelProgress)
        modelProgressText = findViewById(R.id.modelProgressText)
        btnDownload = findViewById(R.id.btnDownload)
        serviceChip = findViewById(R.id.serviceChip)
        serviceHint = findViewById(R.id.serviceHint)
        bubbleChip = findViewById(R.id.bubbleChip)
        btnBubble = findViewById(R.id.btnBubble)
        statusRobot = findViewById(R.id.statusRobot)
        statusTitle = findViewById(R.id.statusTitle)
        statusText = findViewById(R.id.statusText)
        testInput = findViewById(R.id.testInput)
        testResult = findViewById(R.id.testResult)

        btnDownload.setOnClickListener { downloadModel() }
        findViewById<View>(R.id.btnAccessibility).setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.btnAppInfo).setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        btnBubble.setOnClickListener { toggleBubble() }
        findViewById<View>(R.id.btnHowTo).setOnClickListener { showHowTo() }
        findViewById<View>(R.id.btnTestTranslate).setOnClickListener { testTranslate() }
        findViewById<View>(R.id.statusCard).setOnClickListener { showSteps() }
        findViewById<View>(R.id.btnExit).setOnClickListener { askExit() }
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.btnLog).setOnClickListener {
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
        setChip(serviceChip, running, if (running) R.string.service_on else R.string.service_off)
        serviceHint.alpha = if (running) 0.5f else 1f
        val shown = service?.isBubbleShown == true
        setChip(bubbleChip, shown, if (shown) R.string.bubble_shown else R.string.bubble_hidden)
        btnBubble.setText(if (shown) R.string.bubble_hide else R.string.bubble_show)
        refreshStatus()
    }

    private fun refreshModel() {
        if (downloading) return
        lifecycleScope.launch {
            val ready = runCatching { App.engine.baseModelsReady() }
                .onFailure { AppLog.e("Не удалось проверить словарь", it) }
                .getOrDefault(false)
            if (downloading) return@launch
            showModelState(ready)
        }
    }

    private fun showModelState(ready: Boolean) {
        modelReady = ready
        setChip(modelChip, ready, if (ready) R.string.model_ready else R.string.model_missing)
        btnDownload.visibility = if (ready) View.GONE else View.VISIBLE
        btnDownload.setText(R.string.model_download)
        modelProgress.visibility = View.GONE
        modelProgressText.visibility = View.GONE
        refreshStatus()
    }

    private fun downloadModel() {
        downloading = true
        btnDownload.visibility = View.GONE
        setChip(modelChip, true, R.string.model_downloading)
        modelProgress.progress = null
        modelProgress.visibility = View.VISIBLE
        modelProgressText.visibility = View.VISIBLE
        modelProgressText.setText(R.string.model_connecting)
        lifecycleScope.launch {
            try {
                App.engine.downloadBaseModels { showDownloadProgress(it) }
                downloading = false
                showModelState(ready = true)
                modelProgressText.visibility = View.VISIBLE
                modelProgressText.setText(R.string.model_done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Не удалось скачать словарь", e)
                downloading = false
                showModelState(ready = false)
                btnDownload.setText(R.string.model_download_retry)
                modelProgressText.visibility = View.VISIBLE
                modelProgressText.text = getString(R.string.model_error, e.message ?: e.javaClass.simpleName)
            } finally {
                downloading = false
            }
        }
    }

    private fun showDownloadProgress(progress: ModelDownloadWatcher.Progress) {
        val fraction = progress.fraction
        modelProgress.progress = fraction
        modelProgressText.text = when {
            progress.waitingReason != null -> "Пауза: ${progress.waitingReason}"
            fraction == null -> getString(R.string.model_connecting)
            !progress.exact && fraction >= 1f -> getString(R.string.model_finishing)
            progress.exact ->
                "${formatMb(progress.downloadedBytes)} из ${formatMb(progress.totalBytes)} · ${(fraction * 100).roundToInt()}%"
            else -> "Скачано ${formatMb(progress.downloadedBytes)} из ~${formatMb(progress.totalBytes)}"
        }
    }

    /** Робот с биноклем ищет, что не готово; робот с поднятым пальцем — всё готово. */
    private fun refreshStatus() {
        val service = TranslatorService.instance
        val left = listOf(modelReady, service != null, service?.isBubbleShown == true).count { !it }
        if (left == 0) {
            statusRobot.setImageResource(R.drawable.robot_thumb)
            statusTitle.setText(R.string.status_ready_title)
            statusText.setText(R.string.status_ready_text)
        } else {
            statusRobot.setImageResource(R.drawable.robot_scan)
            statusTitle.text = if (left == 1) getString(R.string.status_left_one) else getString(R.string.status_left_many, left)
            statusText.setText(R.string.status_left_text)
        }
    }

    /** Стрелочка на главном экране — спросить, выйти ли из приложения. */
    private fun askExit() {
        AlertDialog.Builder(this)
            .setMessage(R.string.exit_question)
            .setPositiveButton(R.string.exit_yes) { _, _ -> finishAffinity() }
            .setNegativeButton(R.string.exit_stay, null)
            .show()
    }

    /** Какие шаги готовы, а какие нет; кнопка сразу делает первый недостающий шаг. */
    private fun showSteps() {
        val service = TranslatorService.instance
        val serviceOn = service != null
        val bubbleOn = service?.isBubbleShown == true
        val lines = listOf(
            getString(if (modelReady) R.string.status_step_model_ok else R.string.status_step_model_no),
            getString(if (serviceOn) R.string.status_step_service_ok else R.string.status_step_service_no),
            getString(if (bubbleOn) R.string.status_step_bubble_ok else R.string.status_step_bubble_no),
        )
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.status_dialog_title)
            .setMessage(lines.joinToString("\n\n"))
            .setNegativeButton(R.string.status_close, null)
        when {
            !modelReady -> builder.setPositiveButton(R.string.model_download) { _, _ -> if (!downloading) downloadModel() }
            !serviceOn -> builder.setPositiveButton(R.string.open_accessibility) { _, _ ->
                openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !bubbleOn -> builder.setPositiveButton(R.string.bubble_show) { _, _ -> toggleBubble() }
            else -> builder.setMessage(lines.joinToString("\n\n") + "\n\n" + getString(R.string.status_all_ready))
        }
        builder.show()
    }

    private fun setChip(chip: TextView, ok: Boolean, text: Int) {
        chip.setText(text)
        chip.setBackgroundResource(if (ok) R.drawable.bg_chip_ok else R.drawable.bg_chip)
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(if (ok) R.drawable.dot_on else R.drawable.dot_off, 0, 0, 0)
        chip.setTextColor(ContextCompat.getColor(this, if (ok) R.color.text else R.color.text_muted))
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

    private fun showHowTo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.how_to_title)
            .setMessage(R.string.how_to_text)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun testTranslate() {
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) return
        testResult.setText(R.string.translating)
        lifecycleScope.launch {
            try {
                val result = App.engine.translate(
                    text,
                    onStatus = { status -> testResult.text = status },
                    onDownload = { progress ->
                        progress.fraction?.let { testResult.text = "Скачиваю словарь… ${(it * 100).roundToInt()}%" }
                    },
                )
                testResult.text = if (result.alreadyTarget) {
                    getString(R.string.already_russian)
                } else {
                    result.translated + "\n\n— " + result.provider +
                        (result.note?.let { "\n" + getString(R.string.cloud_fallback, it) } ?: "")
                }
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
