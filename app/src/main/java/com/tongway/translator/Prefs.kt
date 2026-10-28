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

    fun historyEnabled(context: Context) = prefs(context).getBoolean(KEY_HISTORY_ENABLED, true)

    fun setHistoryEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_HISTORY_ENABLED, enabled).apply()
}
