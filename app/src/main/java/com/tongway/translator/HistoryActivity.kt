package com.tongway.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tongway.translator.ui.TechBackgroundDrawable

/** История переводов кружком: поиск, копирование, удаление. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var search: EditText
    private lateinit var empty: TextView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(TechBackgroundDrawable(this))
        setContentView(R.layout.activity_history)
        search = findViewById(R.id.historySearch)
        empty = findViewById(R.id.historyEmpty)

        findViewById<RecyclerView>(R.id.historyList).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    HistoryStore.clear(this)
                    refresh()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val query = search.text.toString().trim()
        val all = HistoryStore.all(this)
        val shown = if (query.isEmpty()) {
            all
        } else {
            all.filter { it.original.contains(query, ignoreCase = true) || it.translated.contains(query, ignoreCase = true) }
        }
        adapter.submit(shown)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(
            when {
                !Prefs.historyEnabled(this) && all.isEmpty() -> R.string.history_off
                all.isEmpty() -> R.string.history_empty
                else -> R.string.history_nothing_found
            }
        )
    }

    private fun copy(entry: HistoryStore.Entry) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", entry.translated))
        Toast.makeText(this, R.string.popup_copied, Toast.LENGTH_SHORT).show()
    }

    private fun askDelete(entry: HistoryStore.Entry) {
        AlertDialog.Builder(this)
            .setMessage(R.string.history_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                HistoryStore.remove(this, entry)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var items: List<HistoryStore.Entry> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<HistoryStore.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val time = DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            holder.meta.text = listOf(entry.appName, time.toString(), entry.provider)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            holder.translation.text = entry.translated
            holder.original.text = entry.original
            holder.itemView.setOnClickListener { copy(entry) }
            holder.itemView.setOnLongClickListener {
                askDelete(entry)
                true
            }
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val meta: TextView = view.findViewById(R.id.itemMeta)
        val translation: TextView = view.findViewById(R.id.itemTranslation)
        val original: TextView = view.findViewById(R.id.itemOriginal)
    }
}
