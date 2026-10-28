package com.tongway.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** История переводов кружком: последние записи в файле filesDir/history.json. */
object HistoryStore {

    data class Entry(
        val time: Long,
        val original: String,
        val translated: String,
        val appName: String,
    )

    private const val MAX_ENTRIES = 300
    private var entries: MutableList<Entry>? = null

    @Synchronized
    fun all(context: Context): List<Entry> = load(context).toList()

    @Synchronized
    fun add(context: Context, original: String, translated: String, appName: String) {
        if (!Prefs.historyEnabled(context)) return
        val list = load(context)
        // Повторный перевод того же текста поднимаем наверх, а не дублируем.
        list.removeAll { it.original == original }
        list.add(0, Entry(System.currentTimeMillis(), original, translated, appName))
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, entry: Entry) {
        val list = load(context)
        list.remove(entry)
        save(context, list)
    }

    @Synchronized
    fun clear(context: Context) {
        val list = load(context)
        list.clear()
        save(context, list)
    }

    private fun file(context: Context) = File(context.filesDir, "history.json")

    private fun load(context: Context): MutableList<Entry> {
        entries?.let { return it }
        val list = mutableListOf<Entry>()
        runCatching {
            val f = file(context)
            if (f.exists()) {
                val array = JSONArray(f.readText())
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list.add(Entry(o.getLong("t"), o.getString("o"), o.getString("r"), o.optString("a")))
                }
            }
        }.onFailure { AppLog.e("Не удалось прочитать историю", it) }
        entries = list
        return list
    }

    private fun save(context: Context, list: List<Entry>) {
        val array = JSONArray()
        for (e in list) {
            array.put(JSONObject().put("t", e.time).put("o", e.original).put("r", e.translated).put("a", e.appName))
        }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { AppLog.e("Не удалось сохранить историю", it) }
    }
}
