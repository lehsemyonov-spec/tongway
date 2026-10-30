package com.tongway.translator.ui

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import android.widget.Toast
import com.tongway.translator.MainActivity
import com.tongway.translator.R

/**
 * Отклик на «Копировать»: кнопка на полторы секунды становится тёмной с надписью «Скопировано ✓»,
 * плюс короткое уведомление «Текст скопирован в буфер обмена».
 */
fun TextView.flashCopied() {
    Toast.makeText(context, R.string.popup_copied, Toast.LENGTH_SHORT).show()
    val oldText = text
    val oldBackground = background
    setText(R.string.copied)
    setBackgroundResource(R.drawable.bg_pill_dark)
    isEnabled = false
    postDelayed({
        text = oldText
        background = oldBackground
        isEnabled = true
    }, 1500)
}

/** Стрелочка «назад» на экранах приложения: вернуться на главный экран. */
fun Activity.goHome() {
    startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    finish()
}
