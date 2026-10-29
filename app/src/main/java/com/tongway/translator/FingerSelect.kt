package com.tongway.translator

import com.tongway.translator.PageLayout.Pt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * «Пальцевый перевод»: какие слова задела линия пальца и в каком порядке их читать.
 * Без зависимостей от Android, чтобы проверять на тестах.
 */
object FingerSelect {

    /** Распознанное слово: углы по часовой стрелке от левого верхнего; line — номер строки. */
    data class Word(val text: String, val corners: List<Pt>, val line: Int)

    data class Selection(val text: String, val words: List<Word>)

    /**
     * Слово выбрано, если линия толщиной 2 × radius прошла по нему: хотя бы одна точка линии
     * ближе radius к прямоугольнику слова. Слова идут по строкам сверху вниз, в строке — слева направо.
     */
    fun select(words: List<Word>, stroke: List<Pt>, radius: Float): Selection {
        if (words.isEmpty() || stroke.isEmpty()) return Selection("", emptyList())
        // Наклон текста — по всем словам. Дальше всё считаем в «выпрямленной» странице:
        // на наклонном фото рамки слов иначе раздуваются и цепляют соседнюю строку.
        val angle = words.map { angleOf(it) }.sorted()[words.size / 2]
        val c = cos(-angle).toFloat()
        val s = sin(-angle).toFloat()
        fun rot(p: Pt) = Pt(p.x * c - p.y * s, p.x * s + p.y * c)
        fun rotY(p: Pt) = rot(p).y
        fun rotX(p: Pt) = rot(p).x

        val samples = densify(stroke.map { rot(it) }, max(2f, radius / 2))
        val hit = words.filter { word ->
            val box = boxOf(word.corners.map { rot(it) })
            samples.any { p -> distanceToBox(p, box) <= radius }
        }
        if (hit.isEmpty()) return Selection("", emptyList())

        val byLine = hit.groupBy { it.line }
        val ordered = byLine.values
            .sortedBy { line -> line.map { w -> rotY(center(w)) }.average() }
            .map { line -> line.sortedBy { w -> rotX(center(w)) } }
        val text = PageLayout.joinRows(ordered.map { line -> line.joinToString(" ") { it.text } })
        return Selection(text, ordered.flatten())
    }

    /** Точки линии не реже, чем через step: палец может двигаться быстро и «перепрыгнуть» слово. */
    private fun densify(stroke: List<Pt>, step: Float): List<Pt> {
        val out = mutableListOf(stroke.first())
        for (i in 1 until stroke.size) {
            val a = stroke[i - 1]
            val b = stroke[i]
            val length = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            val parts = (length / step).toInt().coerceAtLeast(1)
            for (k in 1..parts) {
                val t = k.toFloat() / parts
                out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        return out
    }

    private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun boxOf(corners: List<Pt>) = Box(
        corners.minOf { it.x },
        corners.minOf { it.y },
        corners.maxOf { it.x },
        corners.maxOf { it.y },
    )

    private fun distanceToBox(p: Pt, b: Box): Float {
        val dx = max(max(b.left - p.x, 0f), p.x - b.right)
        val dy = max(max(b.top - p.y, 0f), p.y - b.bottom)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun center(word: Word) =
        Pt(word.corners.map { it.x }.average().toFloat(), word.corners.map { it.y }.average().toFloat())

    private fun angleOf(word: Word): Double {
        val a = word.corners[0]
        val b = word.corners[1]
        return atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
    }

    /** Рамка вокруг выбранных слов — чтобы поставить карточку рядом. */
    fun bounds(words: List<Word>): PageLayout.Box {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (w in words) for (p in w.corners) {
            left = min(left, p.x)
            top = min(top, p.y)
            right = max(right, p.x)
            bottom = max(bottom, p.y)
        }
        return PageLayout.Box(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
