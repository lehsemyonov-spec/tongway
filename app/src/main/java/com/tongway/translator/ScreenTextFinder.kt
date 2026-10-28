package com.tongway.translator

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.min

/** Ищет текст в точке экрана через дерево элементов, которое видят спец. возможности. */
object ScreenTextFinder {

    data class Found(val text: String, val bounds: Rect, val packageName: String)

    private const val MAX_NODES = 5000
    /** Текст длиннее этого пробуем сузить до абзаца под прицелом. */
    private const val NARROW_FROM = 200
    private const val MAX_TEXT = 4000
    private const val MAX_CHAR_LOCATIONS = 20000

    fun find(service: AccessibilityService, x: Int, y: Int): Found? {
        val windows = service.windows.sortedByDescending { it.layer }
        val windowBounds = Rect()
        for (window in windows) {
            // Свои окна (кружок, прицел) и окна других служб пропускаем.
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            window.getBoundsInScreen(windowBounds)
            if (!windowBounds.contains(x, y)) continue
            val root = window.root ?: continue
            findInTree(root, x, y)?.let { return it }
        }
        if (windows.isEmpty()) {
            service.rootInActiveWindow?.let { root -> findInTree(root, x, y)?.let { return it } }
        }
        return null
    }

    private fun findInTree(root: AccessibilityNodeInfo, x: Int, y: Int): Found? {
        var best: AccessibilityNodeInfo? = null
        var bestText: String? = null
        var bestArea = Long.MAX_VALUE
        val bestBounds = Rect()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        val bounds = Rect()
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) continue
            val text = nodeText(node)
            if (text != null && node.isVisibleToUser) {
                val area = bounds.width().toLong() * bounds.height()
                // Самый маленький элемент под прицелом — это и есть «кусок под кружком».
                // При равной площади побеждает вложенный (он обходится позже).
                if (area <= bestArea) {
                    best = node
                    bestText = text
                    bestArea = area
                    bestBounds.set(bounds)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        val node = best ?: return null
        val text = bestText ?: return null
        val packageName = root.packageName?.toString() ?: "?"
        if (text.length > NARROW_FROM) {
            narrowToParagraph(node, text, x, y)?.let { (paragraph, rect) ->
                return Found(paragraph, rect, packageName)
            }
        }
        return Found(text.take(MAX_TEXT), Rect(bestBounds), packageName)
    }

    private fun nodeText(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * В длинном тексте находит символ под прицелом и берёт только его абзац.
     * Работает, если приложение сообщает координаты символов (обычные TextView это умеют).
     */
    private fun narrowToParagraph(
        node: AccessibilityNodeInfo,
        text: String,
        x: Int,
        y: Int,
    ): Pair<String, Rect>? {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (node.availableExtraData?.contains(key) != true) return null
        // Координаты относятся к полному тексту элемента, а не к обрезанному trim().
        val fullText = node.text?.toString() ?: return null
        val length = min(fullText.length, MAX_CHAR_LOCATIONS)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        if (!node.refreshWithExtraData(key, args)) return null
        @Suppress("DEPRECATION")
        val locations = node.extras.getParcelableArray(key) ?: return null

        var index = -1
        var bestDistance = Float.MAX_VALUE
        for (i in locations.indices) {
            val rect = locations[i] as? RectF ?: continue
            if (rect.contains(x.toFloat(), y.toFloat())) {
                index = i
                break
            }
            val distance = hypot(rect.centerX() - x, rect.centerY() - y)
            if (distance < bestDistance) {
                bestDistance = distance
                index = i
            }
        }
        if (index < 0) return null
        if (fullText[index] == '\n' && index > 0) index--

        val start = fullText.lastIndexOf('\n', index) + 1
        val end = fullText.indexOf('\n', index).let { if (it < 0) fullText.length else it }
        if (start >= end) return null
        var paragraphStart = start
        var paragraphEnd = end
        if (paragraphEnd - paragraphStart > MAX_TEXT) {
            paragraphStart = maxOf(start, index - MAX_TEXT / 2)
            paragraphEnd = minOf(end, paragraphStart + MAX_TEXT)
        }
        val paragraph = fullText.substring(paragraphStart, paragraphEnd).trim()
        if (paragraph.isEmpty()) return null

        val union = RectF()
        for (i in paragraphStart until minOf(paragraphEnd, locations.size)) {
            val rect = locations[i] as? RectF ?: continue
            if (rect.isEmpty) continue
            if (union.isEmpty) union.set(rect) else union.union(rect)
        }
        if (union.isEmpty) return null
        val bounds = Rect()
        union.roundOut(bounds)
        return paragraph to bounds
    }
}
