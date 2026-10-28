package com.tongway.translator

import android.app.StatusBarManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.tongway.translator.overlay.BubbleView
import com.tongway.translator.ui.TechBackgroundDrawable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Настройки кружка, истории и кнопки в шторке. Всё применяется сразу. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var preview: BubbleView
    private lateinit var sizeButtons: List<TextView>
    private lateinit var alphaValue: TextView
    private lateinit var keyInput: EditText
    private lateinit var checkResult: TextView
    private var keyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        setupTranslator()

        preview = BubbleView(this)
        findViewById<FrameLayout>(R.id.previewBox).addView(preview)

        sizeButtons = listOf(R.id.sizeSmall, R.id.sizeMedium, R.id.sizeLarge).map { findViewById(it) }
        sizeButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                Prefs.setBubbleSizeDp(this, Prefs.BUBBLE_SIZES[index])
                showBubbleSettings()
            }
        }

        alphaValue = findViewById(R.id.alphaValue)
        findViewById<Slider>(R.id.alphaSlider).apply {
            value = (Prefs.bubbleAlpha(this@SettingsActivity) * 100).roundToInt().coerceIn(30, 100).let { it - it % 5 }.toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    Prefs.setBubbleAlpha(this@SettingsActivity, value / 100f)
                    showBubbleSettings()
                }
            }
        }

        findViewById<MaterialSwitch>(R.id.snapSwitch).apply {
            isChecked = Prefs.snapToEdge(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setSnapToEdge(this@SettingsActivity, checked) }
        }
        findViewById<View>(R.id.btnResetPosition).setOnClickListener { resetPosition() }

        findViewById<MaterialSwitch>(R.id.historySwitch).apply {
            isChecked = Prefs.historyEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setHistoryEnabled(this@SettingsActivity, checked) }
        }
        findViewById<View>(R.id.btnOpenHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        val addTile = findViewById<View>(R.id.btnAddTile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.setOnClickListener { requestTile() }
        } else {
            addTile.visibility = View.GONE
        }
        showBubbleSettings()
    }

    override fun onResume() {
        super.onResume()
        showTranslatorState()
    }

    // ---------- Переводчик ----------

    private fun setupTranslator() {
        keyInput = findViewById(R.id.keyInput)
        checkResult = findViewById(R.id.checkResult)
        keyInput.setText(SecretStore.apiKey(this).orEmpty())
        keyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                SecretStore.saveApiKey(this@SettingsActivity, s?.toString().orEmpty())
                checkResult.visibility = View.GONE
            }
        })
        findViewById<View>(R.id.providerPhone).setOnClickListener {
            Prefs.setProvider(this, Prefs.PROVIDER_PHONE)
            AppLog.i("Переводчик: на телефоне")
            showTranslatorState()
        }
        findViewById<View>(R.id.providerCloud).setOnClickListener {
            Prefs.setProvider(this, Prefs.PROVIDER_OPENROUTER)
            AppLog.i("Переводчик: OpenRouter, ключ ${SecretStore.masked(SecretStore.apiKey(this))}")
            showTranslatorState()
        }
        findViewById<TextView>(R.id.btnShowKey).setOnClickListener { view ->
            keyVisible = !keyVisible
            keyInput.inputType = InputType.TYPE_CLASS_TEXT or
                if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            keyInput.setSelection(keyInput.text.length)
            (view as TextView).setText(if (keyVisible) R.string.key_hide else R.string.key_show)
        }
        findViewById<View>(R.id.btnPasteKey).setOnClickListener { pasteKey() }
        findViewById<View>(R.id.btnKeyHelp).setOnClickListener {
            startActivity(Intent(this, KeyHelpActivity::class.java))
        }
        findViewById<View>(R.id.btnModel).setOnClickListener { chooseModel() }
        findViewById<View>(R.id.btnCheckKey).setOnClickListener { checkKey() }
    }

    private fun showTranslatorState() {
        val cloud = Prefs.provider(this) == Prefs.PROVIDER_OPENROUTER
        findViewById<View>(R.id.providerPhone).setBackgroundResource(if (cloud) R.drawable.bg_pill_outline else R.drawable.bg_pill)
        findViewById<View>(R.id.providerCloud).setBackgroundResource(if (cloud) R.drawable.bg_pill else R.drawable.bg_pill_outline)
        findViewById<TextView>(R.id.providerHint).setText(if (cloud) R.string.provider_cloud_hint else R.string.provider_phone_hint)
        findViewById<View>(R.id.cloudBox).visibility = if (cloud) View.VISIBLE else View.GONE
        val manual = Prefs.model(this)
        val auto = Prefs.autoModel(this).first
        findViewById<TextView>(R.id.modelText).text = if (manual.isEmpty()) {
            getString(R.string.model_auto, if (auto.isEmpty()) "" else "\n($auto)")
        } else {
            getString(R.string.model_manual, manual)
        }
        findViewById<TextView>(R.id.usageText).text = getString(R.string.usage_today, Prefs.usageToday(this))
    }

    private fun pasteKey() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty() || text.contains(' ') || text.length < 20) {
            Toast.makeText(this, R.string.key_clipboard_empty, Toast.LENGTH_LONG).show()
            return
        }
        keyInput.setText(text)
        Toast.makeText(this, R.string.key_pasted, Toast.LENGTH_SHORT).show()
    }

    private fun checkKey() {
        val key = keyInput.text.toString().trim()
        checkResult.visibility = View.VISIBLE
        if (key.isEmpty()) {
            checkResult.setText(R.string.key_missing)
            return
        }
        checkResult.setText(R.string.key_checking)
        lifecycleScope.launch {
            try {
                val (translation, model) = App.engine.checkCloud(key)
                checkResult.text = getString(R.string.key_ok, translation, model)
                AppLog.i("Проверка ключа ${SecretStore.masked(key)}: работает, модель $model")
            } catch (e: OpenRouterClient.CloudException) {
                checkResult.text = getString(R.string.key_fail, e.reason)
                AppLog.i("Проверка ключа ${SecretStore.masked(key)}: ${e.reason}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                checkResult.text = getString(R.string.key_fail, e.message ?: e.javaClass.simpleName)
                AppLog.e("Проверка ключа", e)
            }
            showTranslatorState()
        }
    }

    /** Список бесплатных моделей с сайта (он меняется) — можно выбрать вручную. */
    private fun chooseModel() {
        val loading = Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT)
        loading.show()
        lifecycleScope.launch {
            val models = try {
                OpenRouterClient.freeModels().sortedBy { it.name.lowercase() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e as? OpenRouterClient.CloudException)?.reason ?: (e.message ?: e.javaClass.simpleName)
                Toast.makeText(this@SettingsActivity, getString(R.string.model_list_error, reason), Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = listOf(getString(R.string.model_auto_item)) + models.map { it.name }
            val current = Prefs.model(this@SettingsActivity)
            val checked = if (current.isEmpty()) 0 else models.indexOfFirst { it.id == current } + 1
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.model_list_title)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                    val id = if (which == 0) "" else models[which - 1].id
                    Prefs.setModel(this@SettingsActivity, id)
                    AppLog.i("Модель OpenRouter: ${id.ifEmpty { "автоматически" }}")
                    dialog.dismiss()
                    showTranslatorState()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------- Кружок ----------

    private fun showBubbleSettings() {
        val size = Prefs.bubbleSizeDp(this)
        val alpha = Prefs.bubbleAlpha(this)
        val px = (size * resources.displayMetrics.density).roundToInt()
        preview.layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
        preview.alpha = alpha
        alphaValue.text = "${(alpha * 100).roundToInt()}%"
        sizeButtons.forEachIndexed { index, button ->
            val selected = Prefs.BUBBLE_SIZES[index] == size
            button.setBackgroundResource(if (selected) R.drawable.bg_pill else R.drawable.bg_pill_outline)
        }
    }

    private fun resetPosition() {
        Prefs.resetBubblePosition(this)
        TranslatorService.instance?.let { service ->
            if (service.isBubbleShown) {
                service.setBubbleVisible(false)
                service.setBubbleVisible(true)
            }
        }
        Toast.makeText(this, R.string.settings_position_reset, Toast.LENGTH_SHORT).show()
    }

    /** Android 13+: система сама предлагает добавить кнопку в шторку. */
    private fun requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = getSystemService(StatusBarManager::class.java)
        manager.requestAddTileService(
            ComponentName(this, BubbleTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_translate),
            mainExecutor,
        ) { result ->
            AppLog.i("Добавление кнопки в шторку: результат $result")
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            ) {
                Toast.makeText(this, R.string.tile_added, Toast.LENGTH_SHORT).show()
            } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                Toast.makeText(this, R.string.tile_manual, Toast.LENGTH_LONG).show()
            }
        }
    }
}
