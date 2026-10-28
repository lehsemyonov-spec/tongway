package com.tongway.translator

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.tongway.translator.overlay.BubbleView
import com.tongway.translator.ui.TechBackgroundDrawable
import kotlin.math.roundToInt

/** Настройки кружка, истории и кнопки в шторке. Всё применяется сразу. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var preview: BubbleView
    private lateinit var sizeButtons: List<TextView>
    private lateinit var alphaValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

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
