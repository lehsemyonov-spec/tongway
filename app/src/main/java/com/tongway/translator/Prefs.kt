package com.tongway.translator

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val NAME = "tongway"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    const val KEY_BUBBLE_SIZE = "bubble_size"
    const val KEY_BUBBLE_ALPHA = "bubble_alpha"
    const val KEY_SNAP_TO_EDGE = "snap_to_edge"
    const val KEY_HISTORY_ENABLED = "history_enabled"

    /** Размеры кружка на выбор, в dp: маленький, средний, большой. */
    val BUBBLE_SIZES = intArrayOf(56, 68, 82)
    const val DEFAULT_BUBBLE_SIZE = 68
    const val DEFAULT_BUBBLE_ALPHA = 0.92f

    const val PROVIDER_PHONE = "phone"
    const val PROVIDER_OPENROUTER = "openrouter"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_MODEL = "openrouter_model"
    private const val KEY_AUTO_MODEL = "openrouter_auto_model"
    private const val KEY_AUTO_MODEL_TIME = "openrouter_auto_model_time"
    private const val KEY_USAGE_DAY = "usage_day"
    private const val KEY_USAGE_COUNT = "usage_count"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isBubbleEnabled(context: Context) = prefs(context).getBoolean(KEY_BUBBLE_ENABLED, true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()

    fun bubbleX(context: Context, default: Int) = prefs(context).getInt(KEY_BUBBLE_X, default)

    fun bubbleY(context: Context, default: Int) = prefs(context).getInt(KEY_BUBBLE_Y, default)

    fun saveBubblePosition(context: Context, x: Int, y: Int) =
        prefs(context).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()

    fun resetBubblePosition(context: Context) =
        prefs(context).edit().remove(KEY_BUBBLE_X).remove(KEY_BUBBLE_Y).apply()

    fun bubbleSizeDp(context: Context) = prefs(context).getInt(KEY_BUBBLE_SIZE, DEFAULT_BUBBLE_SIZE)

    fun setBubbleSizeDp(context: Context, size: Int) = prefs(context).edit().putInt(KEY_BUBBLE_SIZE, size).apply()

    fun bubbleAlpha(context: Context) = prefs(context).getFloat(KEY_BUBBLE_ALPHA, DEFAULT_BUBBLE_ALPHA)

    fun setBubbleAlpha(context: Context, alpha: Float) = prefs(context).edit().putFloat(KEY_BUBBLE_ALPHA, alpha).apply()

    fun snapToEdge(context: Context) = prefs(context).getBoolean(KEY_SNAP_TO_EDGE, true)

    fun setSnapToEdge(context: Context, snap: Boolean) = prefs(context).edit().putBoolean(KEY_SNAP_TO_EDGE, snap).apply()

    /** Чем переводить: на телефоне или через OpenRouter. */
    fun provider(context: Context) = prefs(context).getString(KEY_PROVIDER, PROVIDER_PHONE) ?: PROVIDER_PHONE

    fun setProvider(context: Context, provider: String) = prefs(context).edit().putString(KEY_PROVIDER, provider).apply()

    /** Модель, выбранная вручную; пустая строка — выбирать автоматически. */
    fun model(context: Context) = prefs(context).getString(KEY_MODEL, "") ?: ""

    fun setModel(context: Context, model: String) = prefs(context).edit().putString(KEY_MODEL, model).apply()

    fun autoModel(context: Context): Pair<String, Long> =
        (prefs(context).getString(KEY_AUTO_MODEL, "") ?: "") to prefs(context).getLong(KEY_AUTO_MODEL_TIME, 0L)

    fun setAutoModel(context: Context, model: String) = prefs(context).edit()
        .putString(KEY_AUTO_MODEL, model)
        .putLong(KEY_AUTO_MODEL_TIME, if (model.isEmpty()) 0L else System.currentTimeMillis())
        .apply()

    /** Сколько запросов к OpenRouter сделано сегодня (бесплатно около 50 в день). */
    fun usageToday(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_USAGE_DAY, "") == today()) p.getInt(KEY_USAGE_COUNT, 0) else 0
    }

    fun addUsage(context: Context) {
        val count = usageToday(context) + 1
        prefs(context).edit().putString(KEY_USAGE_DAY, today()).putInt(KEY_USAGE_COUNT, count).apply()
    }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun historyEnabled(context: Context) = prefs(context).getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply()
}
