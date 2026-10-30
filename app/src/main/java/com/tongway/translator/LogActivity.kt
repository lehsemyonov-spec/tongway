package com.tongway.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tongway.translator.ui.TechBackgroundDrawable
import com.tongway.translator.ui.flashCopied
import com.tongway.translator.ui.goHome

class LogActivity : AppCompatActivity() {

    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_log)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        findViewById<View>(R.id.btnBack).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Журнал Tongway", AppLog.report()))
            (it as TextView).flashCopied()
        }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            AppLog.clear()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val text = AppLog.text()
        logText.text = text.ifEmpty { getString(R.string.log_empty) }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
