package com.tongway.translator

/**
 * Live: субтитры часто рвут предложение посередине («Why do you have to keep sticking your» |
 * «nose into everything?»). Куски склеиваются, пока предложение не кончится знаком препинания;
 * готовое предложение уходит на перевод целиком, а незаконченный хвост ждёт продолжения.
 * Без зависимостей от Android, чтобы проверять на тестах.
 */
class SentenceJoiner(
    private val maxChars: Int = 260,
    private val maxParts: Int = 4,
) {
    /** complete — готовые предложения (по порядку); open — незаконченный хвост или null. */
    data class Update(val complete: List<String>, val open: String?)

    private var open = ""
    private var parts = 0

    /** Очередной кусок субтитров. */
    fun add(chunk: String): Update {
        val piece = chunk.trim()
        if (piece.isEmpty()) return Update(emptyList(), open.ifEmpty { null })
        open = join(open, piece)
        parts++
        val cut = lastSentenceEnd(open)
        val complete = mutableListOf<String>()
        if (cut > 0) {
            complete.add(open.substring(0, cut).trim())
            open = open.substring(cut).trim()
            parts = if (open.isEmpty()) 0 else 1
        }
        // Автосубтитры без знаков препинания: не копим бесконечно.
        if (open.isNotEmpty() && (open.length >= maxChars || parts >= maxParts)) {
            complete.add(open)
            open = ""
            parts = 0
        }
        return Update(complete, open.ifEmpty { null })
    }

    /** Предложение так и не кончилось (пауза в речи, выключили Live) — отдаём как есть. */
    fun flush(): String? {
        val rest = open.ifEmpty { null }
        open = ""
        parts = 0
        return rest
    }

    val hasOpen: Boolean get() = open.isNotEmpty()

    /** «I was going to...» + «...tell you» → без многоточий посередине. */
    private fun join(a: String, b: String): String {
        if (a.isEmpty()) return b
        val left = a.trimEnd().removeSuffix("...").removeSuffix("…").trimEnd()
        val right = b.trimStart().removePrefix("...").removePrefix("…").trimStart()
        val joined = if (left.endsWith("-") && right.firstOrNull()?.isLowerCase() == true) {
            left.dropLast(1) + right
        } else {
            "$left $right"
        }
        return joined.trim()
    }

    /**
     * Индекс сразу после последнего конца предложения (. ! ? и закрывающие кавычки/скобки), 0 — нет.
     * Многоточие в конце куска — это «продолжение следует», а не конец. Сокращения (Mr., Dr.) — не конец.
     */
    private fun lastSentenceEnd(text: String): Int {
        var i = text.length - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
                var end = i + 1
                while (end < text.length && text[end] in CLOSERS) end++
                val atEnd = end >= text.length
                val followedBySpace = atEnd || text[end].isWhitespace()
                if (followedBySpace && !isEllipsisAtEnd(text, i, atEnd) && !isAbbreviation(text, i)) return end
            }
            i--
        }
        return 0
    }

    private fun isEllipsisAtEnd(text: String, i: Int, atEnd: Boolean): Boolean {
        if (!atEnd && text.substring(i + 1).isNotBlank()) return false
        if (text[i] == '…') return true
        return i >= 2 && text[i - 1] == '.' && text[i - 2] == '.'
    }

    private fun isAbbreviation(text: String, i: Int): Boolean {
        if (text[i] != '.') return false
        var start = i
        while (start > 0 && text[start - 1].isLetter()) start--
        val word = text.substring(start, i).lowercase()
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter() && word[0] != 'i' && word[0] != 'a')
    }

    private companion object {
        val CLOSERS = setOf('"', '\'', '”', '’', '»', ')', ']', '♪')
        val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "etc", "prof", "mt", "no")
    }
}
