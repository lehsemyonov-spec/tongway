package com.tongway.translator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Live, часть 2: рамку тянут за уголки (одним пальцем или двумя сразу — каждый палец свой уголок).
 * Противоположный уголок стоит на месте, рамка не меньше минимума и не выходит за экран.
 * Без зависимостей от Android, чтобы проверять на тестах.
 */
object FrameResize {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Уголки: 0 — левый верхний, 1 — правый верхний, 2 — правый нижний, 3 — левый нижний. */
    fun drag(frame: Box, corner: Int, x: Int, y: Int, screenW: Int, screenH: Int, minW: Int, minH: Int): Box {
        val px = x.coerceIn(0, screenW)
        val py = y.coerceIn(0, screenH)
        var left = frame.left
        var top = frame.top
        var right = frame.right
        var bottom = frame.bottom
        when (corner) {
            0 -> { left = min(px, right - minW); top = min(py, bottom - minH) }
            1 -> { right = max(px, left + minW); top = min(py, bottom - minH) }
            2 -> { right = max(px, left + minW); bottom = max(py, top + minH) }
            3 -> { left = min(px, right - minW); bottom = max(py, top + minH) }
            else -> return frame
        }
        // Минимум мог вытолкнуть край за экран — сдвигаем обратно.
        if (left < 0) { right -= left; left = 0 }
        if (top < 0) { bottom -= top; top = 0 }
        if (right > screenW) { left -= right - screenW; right = screenW }
        if (bottom > screenH) { top -= bottom - screenH; bottom = screenH }
        return Box(max(0, left), max(0, top), right, bottom)
    }

    /**
     * Запомненный для приложения размер (доли экрана) ставим вокруг найденных субтитров:
     * по центру найденного текста, низ — чуть ниже его низа.
     */
    fun placeSaved(foundCenterX: Int, foundBottom: Int, widthShare: Float, heightShare: Float, screenW: Int, screenH: Int, below: Int): Box {
        val w = (widthShare * screenW).toInt().coerceIn(1, screenW)
        val h = (heightShare * screenH).toInt().coerceIn(1, screenH)
        val left = (foundCenterX - w / 2).coerceIn(0, screenW - w)
        val bottom = (foundBottom + below).coerceIn(h, screenH)
        return Box(left, bottom - h, left + w, bottom)
    }
}

/**
 * Live, часть 2: надписи на видео (обложка, логотип, название канала) стоят на месте, а субтитры
 * меняются каждые несколько секунд. Кусок текста, который не меняется и не двигается, пока рядом
 * сменились субтитры (или просто очень долго), — надпись; её не переводим.
 * Без зависимостей от Android, чтобы проверять на тестах.
 */
class StaticFilter(
    private val staticMs: Long = 4500,
    private val veryLongMs: Long = 8000,
    private val forgetMs: Long = 2000,
    /** Насколько может «дрожать» положение одного и того же куска (в пикселях). */
    private val slop: Int = 24,
) {
    data class Item(val text: String, val centerX: Int, val centerY: Int)

    /** kept — индексы кусков, которые похожи на субтитры; newlyStatic — тексты, только что признанные надписями. */
    data class Result(val kept: List<Int>, val newlyStatic: List<Pair<String, Long>>)

    private class Track(val norm: String, var x: Int, var y: Int, val since: Long, var lastSeen: Long) {
        var changesNearby = 0
        var isStatic = false
        /** Что было рядом с куском в прошлый раз — чтобы заметить смену субтитров. */
        var others: String? = null
    }

    private val tracks = mutableListOf<Track>()

    /** Забыть всё (рамку растянули или перенесли — положения кусков стали другими). */
    fun reset() = tracks.clear()

    fun feed(items: List<Item>, now: Long): Result {
        // Пропавшие надолго — забываем: если появятся снова, проверятся заново.
        tracks.removeAll { now - it.lastSeen > forgetMs }
        val kept = mutableListOf<Int>()
        val newly = mutableListOf<Pair<String, Long>>()
        val matched = HashSet<Track>()
        val matches = items.map { item ->
            val norm = normalize(item.text)
            val track = tracks.firstOrNull { t ->
                t !in matched && t.norm == norm && abs(t.x - item.centerX) <= slop && abs(t.y - item.centerY) <= slop
            } ?: Track(norm, item.centerX, item.centerY, now, now).also { tracks.add(it) }
            matched.add(track)
            track.lastSeen = now
            track
        }
        // Изменилось ли что-то, кроме самого куска: тогда это «рядом сменились субтитры».
        matches.forEachIndexed { i, track ->
            val others = matches.filterIndexed { j, _ -> j != i }.joinToString("|") { it.norm }
            if (others != track.others) {
                if (track.others != null) track.changesNearby++
                track.others = others
            }
        }
        matches.forEachIndexed { i, track ->
            val age = now - track.since
            if (!track.isStatic && track.norm.length >= 2 &&
                ((age >= staticMs && track.changesNearby >= 1) || age >= veryLongMs)
            ) {
                track.isStatic = true
                newly.add(items[i].text to age)
            }
            if (!track.isStatic) kept.add(i)
        }
        return Result(kept, newly)
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
