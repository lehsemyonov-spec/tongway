package com.tongway.translator

import kotlin.math.max
import kotlin.math.min

/**
 * Режим Live: по тексту в рамке (раз в ~0,7 с) решает, когда появилась новая фраза субтитров.
 * Обычные субтитры появляются целиком; автосубтитры YouTube «бегут» — слова дописываются,
 * строки уезжают вверх. Фразу отдаём, когда она устоялась (прочитана одинаково дважды),
 * сменилась другой или исчезла. Уже отданные слова повторно не отдаём.
 * Без зависимостей от Android, чтобы проверять на тестах.
 */
class LiveText(private val stableReads: Int = 2) {

    private class Token(val raw: String, val norm: String)

    private var candidate: List<Token>? = null
    private var seen = 0
    /** Последние отданные слова (нормализованные) — чтобы не переводить одно и то же дважды. */
    private val history = ArrayDeque<String>()

    /** Очередное чтение рамки. Возвращает новую фразу для перевода или null. */
    fun feed(text: String): String? {
        val tokens = tokenize(text)
        val current = candidate
        if (tokens.isEmpty()) {
            // Субтитр исчез — отдаём его, если ещё не отдали.
            candidate = null
            seen = 0
            return current?.let { emit(it) }
        }
        if (current == null) {
            candidate = tokens
            seen = 1
            return if (seen >= stableReads) emit(tokens) else null
        }
        if (similar(current, tokens)) {
            candidate = tokens
            seen++
            return if (seen == stableReads) emit(tokens) else null
        }
        if (grows(current, tokens)) {
            // Бегущие субтитры дописывают слова — ждём, пока фраза устоится.
            candidate = tokens
            seen = 1
            return null
        }
        // Другая фраза: прошлую (если не успели) отдаём, новую начинаем ждать.
        candidate = tokens
        seen = 1
        return emit(current)
    }

    /** Отдать то, что ещё не отдано (например, при выключении). */
    fun flush(): String? {
        val current = candidate ?: return null
        candidate = null
        seen = 0
        return emit(current)
    }

    private fun emit(tokens: List<Token>): String? {
        val overlap = overlapWithHistory(tokens)
        val fresh = tokens.drop(overlap)
        if (fresh.isEmpty()) return null
        for (t in fresh) history.addLast(t.norm)
        while (history.size > HISTORY_WORDS) history.removeFirst()
        return fresh.joinToString(" ") { it.raw }
    }

    /**
     * Сколько первых слов фразы совпадает с концом уже отданного: у бегущих субтитров верхняя
     * строка — это прошлая нижняя. Короткие совпадения (1–2 слова) не считаем — это могут быть
     * обычные «I know…» в начале новой фразы; кроме полного повтора фразы.
     */
    private fun overlapWithHistory(tokens: List<Token>): Int {
        val limit = min(tokens.size, history.size)
        for (k in limit downTo 1) {
            if (k < MIN_OVERLAP && k < tokens.size) break
            var match = true
            for (i in 0 until k) {
                if (!sameWord(history[history.size - k + i], tokens[i].norm)) {
                    match = false
                    break
                }
            }
            if (match) return k
        }
        return 0
    }

    private fun similar(a: List<Token>, b: List<Token>): Boolean = closeEnough(joined(a), joined(b))

    /** b — это a, к которому дописали слова. */
    private fun grows(a: List<Token>, b: List<Token>): Boolean =
        b.size > a.size && closeEnough(joined(a), joined(b.take(a.size)))

    private fun joined(t: List<Token>) = t.joinToString(" ") { it.norm }

    /** Распознавание иногда путает буквы — допускаем ~20% отличий. */
    private fun closeEnough(a: String, b: String): Boolean {
        if (a == b) return true
        val longest = max(a.length, b.length)
        if (longest == 0) return true
        return distance(a, b) <= longest * 0.2
    }

    private fun sameWord(a: String, b: String): Boolean =
        a == b || (min(a.length, b.length) >= 4 && distance(a, b) <= 1)

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private fun tokenize(text: String): List<Token> =
        text.split(Regex("\\s+"))
            .map { raw -> Token(raw, raw.lowercase().filter { it.isLetterOrDigit() || it == '\'' }) }
            .filter { it.norm.isNotEmpty() }

    private companion object {
        const val HISTORY_WORDS = 60
        const val MIN_OVERLAP = 3
    }
}
