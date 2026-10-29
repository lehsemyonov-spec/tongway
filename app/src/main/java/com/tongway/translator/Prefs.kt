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
    const val KEY_FINGER_THICKNESS = "finger_thickness"
    const val DEFAULT_FINGER_THICKNESS = 40

    /** Размеры кружка на выбор, в dp: маленький, средний, большой. */
    val BUBBLE_SIZES = intArrayOf(56, 68, 82)
    const val DEFAULT_BUBBLE_SIZE = 68
    const val DEFAULT_BUBBLE_ALPHA = 0.92f

    const val PROVIDER_PHONE = "phone"
    /** Режим «нейросеть» (значение осталось от времени, когда был только OpenRouter). */
    const val PROVIDER_CLOUD = "openrouter"
    private const val KEY_PROVIDER = "provider"

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

    /** Толщина линии «пальцевого перевода»: 10 (тонкая) … 100 (около двух строк). */
    fun fingerThickness(context: Context) = prefs(context).getInt(KEY_FINGER_THICKNESS, DEFAULT_FINGER_THICKNESS)

    fun setFingerThickness(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_FINGER_THICKNESS, value.coerceIn(10, 100)).apply()

    /** Толщина в dp: 10 → 5dp, 40 → 20dp (одна строка с запасом), 100 → 50dp (около двух строк). */
    fun fingerWidthDp(thickness: Int) = thickness * 0.5f

    /** Чем переводить: на телефоне или через OpenRouter. */
    fun provider(context: Context) = prefs(context).getString(KEY_PROVIDER, PROVIDER_PHONE) ?: PROVIDER_PHONE

    fun setProvider(context: Context, provider: String) = prefs(context).edit().putString(KEY_PROVIDER, provider).apply()

    /** Модель сервиса, выбранная вручную; пустая строка — выбирать автоматически. */
    fun model(context: Context, provider: CloudProvider) = prefs(context).getString("${provider.id}_model", "") ?: ""

    fun setModel(context: Context, provider: CloudProvider, model: String) =
        prefs(context).edit().putString("${provider.id}_model", model).apply()

    fun autoModel(context: Context, provider: CloudProvider): Pair<String, Long> =
        (prefs(context).getString("${provider.id}_auto_model", "") ?: "") to
            prefs(context).getLong("${provider.id}_auto_model_time", 0L)

    fun setAutoModel(context: Context, provider: CloudProvider, model: String) = prefs(context).edit()
        .putString("${provider.id}_auto_model", model)
        .putLong("${provider.id}_auto_model_time", if (model.isEmpty()) 0L else System.currentTimeMillis())
        .apply()

    /** Сколько запросов к сервису сделано сегодня. */
    fun usageToday(context: Context, provider: CloudProvider): Int {
        val p = prefs(context)
        return if (p.getString("${provider.id}_usage_day", "") == today()) p.getInt("${provider.id}_usage_count", 0) else 0
    }

    fun addUsage(context: Context, provider: CloudProvider) {
        val count = usageToday(context, provider) + 1
        prefs(context).edit()
            .putString("${provider.id}_usage_day", today())
            .putInt("${provider.id}_usage_count", count)
            .apply()
    }

    private fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun historyEnabled(context: Context) = prefs(context).getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply()
}
