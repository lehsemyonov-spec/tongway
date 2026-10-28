package com.tongway.translator

import android.content.Context

object Prefs {
    private const val NAME = "tongway"
    private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"

    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isBubbleEnabled(context: Context) = prefs(context).getBoolean(KEY_BUBBLE_ENABLED, true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()

    fun bubbleX(context: Context, default: Int) = prefs(context).getInt(KEY_BUBBLE_X, default)

    fun bubbleY(context: Context, default: Int) = prefs(context).getInt(KEY_BUBBLE_Y, default)

    fun saveBubblePosition(context: Context, x: Int, y: Int) =
        prefs(context).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
}
