package com.tongway.translator

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Сборка страницы из распознанных строк, как в книге: выравнивание наклона, отсев мусора,
 * колонки, порядок чтения и абзацы по отступам. Без зависимостей от Android, чтобы
 * проверять на тестовых страницах.
 */
object PageLayout {

    data class Pt(val x: Float, val y: Float)

    /** Распознанная строка: углы по часовой стрелке от левого верхнего; confidence 0 — неизвестно. */
    data class Line(val text: String, val corners: List<Pt>, val confidence: Float = 0f)

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    }

    data class Paragraph(val text: String, val bounds: Box, val lineHeight: Int)

    data class Result(val paragraphs: List<Paragraph>, val dropped: Int, val angle: Float)

    /** Насколько строка может отличаться по наклону от страницы (текст боком на полях — мусор). */
    private const val MAX_ANGLE_DIFF = 12.0
    private const val MIN_CONFIDENCE = 0.5f

    /** Строка после выравнивания наклона: координаты в «повёрнутой обратно» странице. */
    private class Placed(val line: Line, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height get() = bottom - top
        val centerY get() = (top + bottom) / 2
    }

    private class Row(val parts: MutableList<Placed>) {
        val left get() = parts.minOf { it.left }
        val right get() = parts.maxOf { it.right }
        val top get() = parts.minOf { it.top }
        val bottom get() = parts.maxOf { it.bottom }
        val text get() = parts.sortedBy { it.left }.joinToString(" ") { it.line.text.trim() }
    }

    fun build(lines: List<Line>, width: Int, exclude: List<Box> = emptyList()): Result {
        var dropped = 0
        val anyConfidence = lines.any { it.confidence > 0f }
        val kept = lines.filter { line ->
            val center = center(line)
            val bad = line.corners.size < 4 ||
                line.text.count { it.isLetter() } < 2 ||
                (anyConfidence && line.confidence in 0.0001f..MIN_CONFIDENCE) ||
                exclude.any { it.contains(center.x, center.y) }
            if (bad) dropped++
            !bad
        }
        if (kept.isEmpty()) return Result(emptyList(), dropped, 0f)

        // Наклон страницы — взвешенная по длине медиана наклонов строк.
        val angles = kept.map { angleOf(it) to lengthOf(it) }.sortedBy { it.first }
        val total = angles.sumOf { it.second }
        var acc = 0.0
        var dominant = angles.first().first
        for ((angle, length) in angles) {
            acc += length
            if (acc >= total / 2) {
                dominant = angle
                break
            }
        }
        val upright = kept.filter { line ->
            val ok = abs(angleDiff(angleOf(line), dominant)) <= MAX_ANGLE_DIFF
            if (!ok) dropped++
            ok
        }

        // «Поворачиваем» страницу обратно, чтобы строки стали горизонтальными.
        val rad = Math.toRadians(-dominant)
        val c = cos(rad)
        val s = sin(rad)
        val placed = upright.map { line ->
            val xs = line.corners.map { (it.x * c - it.y * s).toFloat() }
            val ys = line.corners.map { (it.x * s + it.y * c).toFloat() }
            Placed(line, xs.min(), ys.min(), xs.max(), ys.max())
        }.filter { p ->
            // Короткие обрывки у левого или правого края кадра — соседняя страница книги.
            val box = boundsOf(listOf(p.line))
            val edge = width * 0.025f
            val atEdge = box.left <= edge || box.right >= width - edge
            val scrap = atEdge && p.line.text.trim().length < 15
            if (scrap) dropped++
            !scrap
        }
        if (placed.isEmpty()) return Result(emptyList(), dropped, dominant.toFloat())

        val columns = splitColumns(placed)
        val paragraphs = orderColumns(columns).flatMap { paragraphsOf(it) }
        return Result(paragraphs, dropped, dominant.toFloat())
    }

    /**
     * Колонки: строка идёт в колонку, если по горизонтали перекрывается с её последней строкой.
     * Сравниваем с последней строкой, а не со всей колонкой, — так широкий заголовок над
     * двумя колонками не склеивает их в одну. Строки на одной высоте идут слева направо.
     */
    private fun splitColumns(lines: List<Placed>): List<MutableList<Placed>> {
        val heights = lines.map { it.height }.sorted()
        val step = (heights[heights.size / 2] / 2).coerceAtLeast(1f)
        val columns = mutableListOf<MutableList<Placed>>()
        for (line in lines.sortedWith(compareBy({ (it.top / step).toInt() }, { it.left }))) {
            val target = columns.firstOrNull { column ->
                column.takeLast(1).any { other ->
                    val overlap = min(line.right, other.right) - max(line.left, other.left)
                    overlap > 0.5f * min(line.right - line.left, other.right - other.left)
                } && line.top >= column.last().top - column.last().height
            }
            if (target != null) target.add(line) else columns.add(mutableListOf(line))
        }
        return columns
    }

    /** Порядок чтения колонок: сверху вниз, а стоящие рядом — слева направо. */
    private fun orderColumns(columns: List<MutableList<Placed>>): List<List<Placed>> {
        val ordered = columns.sortedBy { col -> col.minOf { it.top } }.toMutableList()
        repeat(ordered.size) {
            for (i in 0 until ordered.size - 1) {
                val a = ordered[i]
                val b = ordered[i + 1]
                val aTop = a.minOf { it.top }
                val aBottom = a.maxOf { it.bottom }
                val bTop = b.minOf { it.top }
                val bBottom = b.maxOf { it.bottom }
                val overlap = min(aBottom, bBottom) - max(aTop, bTop)
                val side = overlap > 0.3f * min(aBottom - aTop, bBottom - bTop)
                if (side && b.minOf { it.left } < a.minOf { it.left }) {
                    ordered[i] = b
                    ordered[i + 1] = a
                }
            }
        }
        return ordered
    }

    private fun paragraphsOf(column: List<Placed>): List<Paragraph> {
        // Строки на одной высоте (распознавание иногда делит строку на две) — один ряд.
        val rows = mutableListOf<Row>()
        for (line in column.sortedBy { it.centerY }) {
            val last = rows.lastOrNull()
            val sameRow = last != null && run {
                val overlap = min(last.bottom, line.bottom) - max(last.top, line.top)
                overlap > 0.5f * min(last.bottom - last.top, line.height) &&
                    last.parts.none { min(it.right, line.right) - max(it.left, line.left) > 0 }
            }
            if (sameRow) last!!.parts.add(line) else rows.add(Row(mutableListOf(line)))
        }
        val heights = rows.map { it.bottom - it.top }.sorted()
        val h = heights[heights.size / 2].coerceAtLeast(1f)
        val lefts = rows.map { it.left }.sorted()
        val rights = rows.map { it.right }.sorted()
        val leftMargin = lefts[(lefts.size * 0.2f).toInt().coerceAtMost(lefts.size - 1)]
        val rightMargin = rights[(rights.size * 0.8f).toInt().coerceAtMost(rights.size - 1)]
        val longColumn = rows.size >= 3

        val out = mutableListOf<Paragraph>()
        var current = mutableListOf<Row>()
        fun flush() {
            if (current.isEmpty()) return
            out.add(
                Paragraph(
                    joinRows(current.map { it.text }),
                    boundsOf(current.flatMap { row -> row.parts.map { it.line } }),
                    h.toInt(),
                )
            )
            current = mutableListOf()
        }
        for ((i, row) in rows.withIndex()) {
            if (i > 0) {
                val prev = rows[i - 1]
                val gap = row.top - prev.bottom > 0.75f * h
                val indented = row.left - leftMargin > 0.8f * h
                val prevIndented = prev.left - leftMargin > 0.8f * h
                // Красная строка: отступ у этой строки, а у предыдущей его нет.
                val indent = longColumn && indented && !prevIndented
                // Предыдущая строка кончилась точкой и оборвалась заметно раньше края.
                val ended = prev.text.trimEnd().lastOrNull()?.let { it in ".!?:;\"”»)" } == true
                val short = longColumn && ended && rightMargin - prev.right > 2.5f * h
                if (gap || indent || short) flush()
            }
            current.add(row)
        }
        flush()
        return out
    }

    /** Строки абзаца в одну фразу; перенос «-» перед строчной буквой склеивается без пробела. */
    fun joinRows(rows: List<String>): String {
        val out = StringBuilder()
        for (raw in rows) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (out.isNotEmpty()) {
                if (out.endsWith("-") && t.first().isLowerCase()) out.setLength(out.length - 1) else out.append(' ')
            }
            out.append(t)
        }
        return out.toString()
    }

    private fun boundsOf(lines: List<Line>): Box {
        val xs = lines.flatMap { l -> l.corners.map { it.x } }
        val ys = lines.flatMap { l -> l.corners.map { it.y } }
        return Box(xs.min().toInt(), ys.min().toInt(), xs.max().toInt(), ys.max().toInt())
    }

    private fun center(line: Line): Pt =
        Pt(line.corners.map { it.x }.average().toFloat(), line.corners.map { it.y }.average().toFloat())

    private fun angleOf(line: Line): Double {
        val a = line.corners[0]
        val b = line.corners[1]
        return Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()))
    }

    private fun lengthOf(line: Line): Double {
        val a = line.corners[0]
        val b = line.corners[1]
        return hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }
}
