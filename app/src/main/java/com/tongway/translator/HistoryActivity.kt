package com.tongway.translator

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tongway.translator.ui.TechBackgroundDrawable
import com.tongway.translator.ui.goHome

/** История переводов кружком: поиск, копирование, удаление. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var search: EditText
    private lateinit var empty: TextView
    private val adapter = Adapter()

    /** Вкладки: всё, обычные переводы, сеансы Live. */
    private enum class Tab { ALL, PLAIN, LIVE }
    private var tab = Tab.ALL
    private lateinit var tabs: Map<Tab, TextView>

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
        findViewById<View>(R.id.btnBack).setOnClickListener { goHome() }
        tabs = mapOf(
            Tab.ALL to findViewById(R.id.tabAll),
            Tab.PLAIN to findViewById(R.id.tabPlain),
            Tab.LIVE to findViewById(R.id.tabLive),
        )
        tabs.forEach { (t, view) ->
            view.setOnClickListener {
                tab = t
                refresh()
            }
        }
        if (intent.getBooleanExtra(EXTRA_LIVE, false)) tab = Tab.LIVE
        // Очищается только то, что видно на выбранной вкладке.
        findViewById<View>(R.id.btnClear).setOnClickListener {
            val message = when (tab) {
                Tab.ALL -> R.string.history_clear_all_confirm
                Tab.PLAIN -> R.string.history_clear_plain_confirm
                Tab.LIVE -> R.string.history_clear_live_confirm
            }
            AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    when (tab) {
                        Tab.ALL -> HistoryStore.clear(this)
                        Tab.PLAIN -> HistoryStore.removeWhere(this) { !isLive(it) }
                        Tab.LIVE -> HistoryStore.removeWhere(this) { isLive(it) }
                    }
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
        val live = all.count { isLive(it) }
        tabs.getValue(Tab.ALL).text = getString(R.string.history_tab_all, all.size)
        tabs.getValue(Tab.PLAIN).text = getString(R.string.history_tab_plain, all.size - live)
        tabs.getValue(Tab.LIVE).text = getString(R.string.history_tab_live, live)
        tabs.forEach { (t, view) ->
            val selected = t == tab
            view.setBackgroundResource(if (selected) R.drawable.bg_pill else R.drawable.bg_pill_outline)
            view.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.text else if (t == Tab.LIVE) R.color.accent_light else R.color.text_secondary)
            )
        }
        val inTab = when (tab) {
            Tab.ALL -> all
            Tab.PLAIN -> all.filterNot { isLive(it) }
            Tab.LIVE -> all.filter { isLive(it) }
        }
        val shown = if (query.isEmpty()) {
            inTab
        } else {
            inTab.filter { it.original.contains(query, ignoreCase = true) || it.translated.contains(query, ignoreCase = true) }
        }
        adapter.submit(shown)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(
            when {
                !Prefs.historyEnabled(this) && all.isEmpty() -> R.string.history_off
                inTab.isEmpty() && tab == Tab.LIVE -> R.string.history_live_empty
                inTab.isEmpty() && tab == Tab.PLAIN -> R.string.history_plain_empty
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

    /** Сеанс Live: все фразы по порядку — перевод и под ним оригинал. */
    private fun showSession(entry: HistoryStore.Entry) {
        val originals = entry.original.split('\n')
        val translations = entry.translated.split('\n')
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        val text = SpannableStringBuilder()
        val plain = StringBuilder()
        originals.forEachIndexed { i, original ->
            val translated = translations.getOrElse(i) { "…" }
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(translated)
            val start = text.length
            text.append("\n").append(original)
            text.setSpan(ForegroundColorSpan(muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            plain.append(translated).append("\n").append(original).append("\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle(listOf(entry.appName, entry.provider).filter { it.isNotBlank() }.joinToString(" · "))
            .setMessage(text)
            .setPositiveButton(R.string.live_copy_all) { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Live", plain.toString().trim()))
                Toast.makeText(this, R.string.popup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.live_close, null)
            .show()
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

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<HistoryStore.Entry> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<HistoryStore.Entry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (isLive(items[position])) TYPE_LIVE else TYPE_PLAIN

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_LIVE) {
                LiveHolder(inflater.inflate(R.layout.item_history_live, parent, false))
            } else {
                Holder(inflater.inflate(R.layout.item_history, parent, false))
            }
        }

        override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
            val entry = items[position]
            val time = DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            viewHolder.itemView.setOnLongClickListener {
                askDelete(entry)
                true
            }
            if (viewHolder is LiveHolder) {
                // «Live · 37 фраз · 12 мин · Groq · модель» → итоги без слова Live.
                val clock = DateUtils.formatDateTime(this@HistoryActivity, entry.time, DateUtils.FORMAT_SHOW_TIME)
                viewHolder.title.text = listOf(entry.appName, "$time, $clock").filter { it.isNotBlank() }.joinToString(" · ")
                viewHolder.stats.text = entry.provider.split(" · ").drop(1).joinToString(" · ")
                viewHolder.preview.text = entry.translated.lines().filter { it.isNotBlank() }.take(3).joinToString("\n")
                viewHolder.itemView.setOnClickListener { showSession(entry) }
                return
            }
            val holder = viewHolder as Holder
            holder.meta.text = listOf(entry.appName, time.toString(), entry.provider)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            holder.translation.text = entry.translated
            holder.original.text = entry.original
            holder.itemView.setOnClickListener { copy(entry) }
        }
    }

    private fun isLive(entry: HistoryStore.Entry) = entry.provider.startsWith(LIVE_PREFIX)

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val meta: TextView = view.findViewById(R.id.itemMeta)
        val translation: TextView = view.findViewById(R.id.itemTranslation)
        val original: TextView = view.findViewById(R.id.itemOriginal)
    }

    private class LiveHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.liveItemTitle)
        val stats: TextView = view.findViewById(R.id.liveItemStats)
        val preview: TextView = view.findViewById(R.id.liveItemPreview)
    }

    companion object {
        /** Записи сеансов Live: provider начинается с «Live ·». */
        const val LIVE_PREFIX = "Live"
        /** Открыть историю сразу на вкладке «● Live». */
        const val EXTRA_LIVE = "live"
        private const val TYPE_PLAIN = 0
        private const val TYPE_LIVE = 1
    }
}
