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
    private var sections: List<ProviderSection> = emptyList()

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
        sections = CloudProvider.CHAIN.mapIndexed { index, provider ->
            val box = findViewById<View>(if (provider == CloudProvider.GROQ) R.id.groqBox else R.id.openrouterBox)
            ProviderSection(provider, box, index + 1)
        }
        findViewById<View>(R.id.providerPhone).setOnClickListener {
            Prefs.setProvider(this, Prefs.PROVIDER_PHONE)
            AppLog.i("Переводчик: на телефоне")
            showTranslatorState()
        }
        findViewById<View>(R.id.providerCloud).setOnClickListener {
            Prefs.setProvider(this, Prefs.PROVIDER_CLOUD)
            AppLog.i("Переводчик: нейросеть, ключи: " + CloudProvider.CHAIN.joinToString { p ->
                "${p.title} ${SecretStore.masked(SecretStore.apiKey(this, p))}"
            })
            showTranslatorState()
        }
    }

    private fun showTranslatorState() {
        val cloud = Prefs.provider(this) == Prefs.PROVIDER_CLOUD
        findViewById<View>(R.id.providerPhone).setBackgroundResource(if (cloud) R.drawable.bg_pill_outline else R.drawable.bg_pill)
        findViewById<View>(R.id.providerCloud).setBackgroundResource(if (cloud) R.drawable.bg_pill else R.drawable.bg_pill_outline)
        findViewById<TextView>(R.id.providerHint).setText(if (cloud) R.string.provider_cloud_hint else R.string.provider_phone_hint)
        findViewById<View>(R.id.cloudBox).visibility = if (cloud) View.VISIBLE else View.GONE
        sections.forEach { it.refresh() }
    }

    /** Блок одного сервиса: ключ, инструкция, модель, проверка, счётчик запросов. */
    private inner class ProviderSection(private val provider: CloudProvider, private val box: View, order: Int) {
        private val keyInput = box.findViewById<EditText>(R.id.keyInput)
        private val checkResult = box.findViewById<TextView>(R.id.checkResult)
        private val modelText = box.findViewById<TextView>(R.id.modelText)
        private val usageText = box.findViewById<TextView>(R.id.usageText)
        private var keyVisible = false

        init {
            box.findViewById<TextView>(R.id.cpTitle).text = provider.title
            box.findViewById<TextView>(R.id.cpOrder).text = getString(R.string.chain_order, order)
            box.findViewById<TextView>(R.id.cpHint).setText(
                if (provider == CloudProvider.GROQ) R.string.groq_hint else R.string.openrouter_hint
            )
            keyInput.hint = provider.keyPrefix + "…"
            keyInput.setText(SecretStore.apiKey(this@SettingsActivity, provider).orEmpty())
            keyInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    SecretStore.saveApiKey(this@SettingsActivity, provider, s?.toString().orEmpty())
                    checkResult.visibility = View.GONE
                }
            })
            box.findViewById<TextView>(R.id.btnShowKey).setOnClickListener { view ->
                keyVisible = !keyVisible
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or
                    if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
                keyInput.setSelection(keyInput.text.length)
                (view as TextView).setText(if (keyVisible) R.string.key_hide else R.string.key_show)
            }
            box.findViewById<View>(R.id.btnPasteKey).setOnClickListener { pasteKey() }
            box.findViewById<View>(R.id.btnKeyHelp).setOnClickListener {
                startActivity(
                    Intent(this@SettingsActivity, KeyHelpActivity::class.java)
                        .putExtra(KeyHelpActivity.EXTRA_PROVIDER, provider.id)
                )
            }
            box.findViewById<View>(R.id.btnModel).setOnClickListener { chooseModel() }
            box.findViewById<View>(R.id.btnCheckKey).setOnClickListener { checkKey() }
        }

        fun refresh() {
            val manual = Prefs.model(this@SettingsActivity, provider)
            val auto = Prefs.autoModel(this@SettingsActivity, provider).first
            modelText.text = if (manual.isEmpty()) {
                getString(R.string.model_auto, if (auto.isEmpty()) "" else "\n($auto)")
            } else {
                getString(R.string.model_manual, manual)
            }
            usageText.text = getString(
                if (provider == CloudProvider.GROQ) R.string.usage_today_groq else R.string.usage_today_openrouter,
                Prefs.usageToday(this@SettingsActivity, provider),
            )
        }

        private fun pasteKey() {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(this@SettingsActivity)?.toString()?.trim()
            if (text.isNullOrEmpty() || text.contains(' ') || text.length < 20) {
                Toast.makeText(this@SettingsActivity, getString(R.string.key_clipboard_empty, provider.title), Toast.LENGTH_LONG).show()
                return
            }
            keyInput.setText(text)
            Toast.makeText(this@SettingsActivity, R.string.key_pasted, Toast.LENGTH_SHORT).show()
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
                    val (translation, model) = App.engine.checkCloud(provider, key)
                    checkResult.text = getString(R.string.key_ok, translation, model)
                    AppLog.i("Проверка ключа ${provider.title} ${SecretStore.masked(key)}: работает, модель $model")
                } catch (e: CloudClient.CloudException) {
                    checkResult.text = getString(R.string.key_fail, e.reason)
                    AppLog.i("Проверка ключа ${provider.title} ${SecretStore.masked(key)}: ${e.reason}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    checkResult.text = getString(R.string.key_fail, e.message ?: e.javaClass.simpleName)
                    AppLog.e("Проверка ключа ${provider.title}", e)
                }
                refresh()
            }
        }

        /** Список подходящих моделей с сайта (он меняется) — можно выбрать вручную. */
        private fun chooseModel() {
            Toast.makeText(this@SettingsActivity, R.string.model_loading, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val models = try {
                    CloudClient.models(provider, keyInput.text.toString().trim().ifEmpty { null })
                        .sortedBy { it.name.lowercase() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason = (e as? CloudClient.CloudException)?.reason ?: (e.message ?: e.javaClass.simpleName)
                    Toast.makeText(this@SettingsActivity, getString(R.string.model_list_error, reason), Toast.LENGTH_LONG).show()
                    return@launch
                }
                val labels = listOf(getString(R.string.model_auto_item)) + models.map { it.name }
                val current = Prefs.model(this@SettingsActivity, provider)
                val checked = if (current.isEmpty()) 0 else models.indexOfFirst { it.id == current } + 1
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.model_list_title, provider.title))
                    .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                        val id = if (which == 0) "" else models[which - 1].id
                        Prefs.setModel(this@SettingsActivity, provider, id)
                        AppLog.i("Модель ${provider.title}: ${id.ifEmpty { "автоматически" }}")
                        dialog.dismiss()
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
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
