package com.tongway.translator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Субтитры обычно в две строки, а приложения и распознавание часто отдают каждую строку отдельным
 * куском. Под прицелом оказывается одна строка — дополняем её соседними строками того же субтитра.
 * Без зависимостей от Android, чтобы проверять на тестах.
 */
object SubtitleJoin {

    /** Кусок текста; lineHeight — высота одной строки (0 — считать всю высоту одной строкой). */
    data class Piece(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val lineHeight: Int = 0,
    ) {
        val height get() = bottom - top
        val width get() = right - left
        val centerX get() = (left + right) / 2f
        val lh get() = if (lineHeight > 0) lineHeight else height
        val lines get() = max(1, (height.toFloat() / lh.coerceAtLeast(1)).roundToInt())
    }

    /** Больше строк субтитр не бывает; длиннее — уже обычный текст. */
    private const val MAX_LINES = 3

    /**
     * Добавляет к target строки сверху и снизу, если они похожи на продолжение субтитра:
     * вплотную по высоте, того же размера шрифта и стоят друг под другом по центру
     * (или по левому краю, если leftAligned — для текста со снимка; в тексте приложения так стоят
     * пункты списков и меню, их склеивать нельзя).
     */
    fun extend(target: Piece, others: List<Piece>, leftAligned: Boolean = true): Piece {
        if (target.lines >= MAX_LINES) return target
        val pool = others.filter { it != target && it.height > 0 && it.width > 0 && it.text.isNotBlank() }
            .filterNot { overlapsVertically(it, target) }
            .toMutableList()
        var group = listOf(target)
        while (true) {
            val top = group.first()
            val bottom = group.last()
            val total = group.sumOf { it.lines }
            val next = pool
                .filter { total + it.lines <= MAX_LINES && it.lines < MAX_LINES }
                .mapNotNull { p ->
                    when {
                        fits(p, top, above = true, leftAligned) -> Triple(p, true, top.top - p.bottom)
                        fits(p, bottom, above = false, leftAligned) -> Triple(p, false, p.top - bottom.bottom)
                        else -> null
                    }
                }
                .minByOrNull { it.third }
                ?: break
            pool.remove(next.first)
            group = if (next.second) listOf(next.first) + group else group + next.first
        }
        if (group.size == 1) return target
        return Piece(
            PageLayout.joinRows(group.map { it.text }),
            group.minOf { it.left },
            group.minOf { it.top },
            group.maxOf { it.right },
            group.maxOf { it.bottom },
            group.map { it.lh }.sorted()[group.size / 2],
        )
    }

    /** p стоит прямо над (above) или под base и похож на строку того же субтитра. */
    private fun fits(p: Piece, base: Piece, above: Boolean, allowLeft: Boolean): Boolean {
        val h = min(p.lh, base.lh).toFloat()
        val ratio = p.lh.toFloat() / base.lh.coerceAtLeast(1)
        if (ratio < 0.65f || ratio > 1.55f) return false
        val gap = if (above) base.top - p.bottom else p.top - base.bottom
        if (gap < -0.35f * h || gap > 1.25f * h) return false
        val overlap = min(p.right, base.right) - max(p.left, base.left)
        if (overlap <= 0) return false
        val centered = abs(p.centerX - base.centerX) < 0.12f * max(p.width, base.width) + h
        val leftAligned = allowLeft && abs(p.left - base.left) < 0.8f * h
        return centered || leftAligned
    }

    private fun overlapsVertically(a: Piece, b: Piece): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.height, b.height)
    }
}
