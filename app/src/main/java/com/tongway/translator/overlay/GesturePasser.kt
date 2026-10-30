package com.tongway.translator.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tongway.translator.AppLog

/**
 * Передаёт жест двумя и более пальцами приложению под слоем «пальцевого перевода»:
 * повторяет движение пальцев через службу спец. возможностей (dispatchGesture с продолжением
 * штрихов), чтобы фото можно было приближать и двигать, не выключая режим.
 */
class GesturePasser(private val service: AccessibilityService, private val onFinished: () -> Unit) {

    private val strokes = HashMap<Int, GestureDescription.StrokeDescription>()
    private val lastPoint = HashMap<Int, PointF>()
    private val pending = HashMap<Int, PointF>()
    private val ending = mutableSetOf<Int>()
    private var inFlight = false
    private var dirty = false
    private var lastDispatch = 0L
    private var lastRealMove = 0L
    private var active = false
    private var segments = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Если реальные касания вдруг перестали приходить — отпускаем пальцы, чтобы ничего не «залипло». */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.uptimeMillis() - lastRealMove > STALL_MS) {
                AppLog.i("Передача жеста: касания перестали приходить — отпускаю")
                end()
            } else {
                handler.postDelayed(this, STALL_MS / 2)
            }
        }
    }

    val isActive: Boolean get() = active

    /** Пальцы в координатах экрана, ключ — номер пальца. */
    fun start(points: Map<Int, PointF>) {
        reset()
        active = true
        lastRealMove = SystemClock.uptimeMillis()
        points.forEach { (id, p) -> pending[id] = PointF(p.x, p.y) }
        AppLog.i("Два пальца: передаю жест приложению (пальцев ${points.size})")
        handler.postDelayed(watchdog, STALL_MS)
        flush()
    }

    fun move(points: Map<Int, PointF>) {
        if (!active) return
        lastRealMove = SystemClock.uptimeMillis()
        points.forEach { (id, p) -> pending[id] = PointF(p.x, p.y) }
        if (inFlight) dirty = true else flush()
    }

    fun pointerUp(id: Int) {
        if (!active) return
        ending.add(id)
        if (inFlight) dirty = true else flush()
    }

    fun end() {
        if (!active) return
        ending.addAll(strokes.keys)
        ending.addAll(pending.keys)
        if (inFlight) dirty = true else flush()
    }

    private fun reset() {
        strokes.clear()
        lastPoint.clear()
        pending.clear()
        ending.clear()
        inFlight = false
        dirty = false
        segments = 0
        handler.removeCallbacks(watchdog)
    }

    private fun flush() {
        dirty = false
        val ids = (strokes.keys + pending.keys).toSet()
        if (ids.isEmpty()) {
            finish()
            return
        }
        val now = SystemClock.uptimeMillis()
        val duration = if (lastDispatch == 0L) FIRST_MS else (now - lastDispatch).coerceIn(MIN_MS, MAX_MS)
        val builder = GestureDescription.Builder()
        var added = 0
        for (id in ids.take(GestureDescription.getMaxStrokeCount())) {
            val from = lastPoint[id] ?: pending[id] ?: continue
            val to = pending[id] ?: from
            val path = Path().apply {
                moveTo(from.x, from.y)
                // Нулевой длины путь не принимается — сдвигаем на долю пикселя.
                lineTo(if (to.x == from.x && to.y == from.y) to.x + 0.1f else to.x, to.y)
            }
            val willContinue = id !in ending
            val previous = strokes[id]
            val stroke = previous?.continueStroke(path, 0, duration, willContinue)
                ?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)
            builder.addStroke(stroke)
            added++
            if (willContinue) {
                strokes[id] = stroke
                lastPoint[id] = PointF(to.x, to.y)
            } else {
                strokes.remove(id)
                lastPoint.remove(id)
                pending.remove(id)
            }
        }
        ending.clear()
        if (added == 0) {
            finish()
            return
        }
        inFlight = true
        lastDispatch = now
        segments++
        val sent = service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                inFlight = false
                if (strokes.isEmpty()) finish() else if (dirty) flush()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                inFlight = false
                AppLog.i("Передача жеста прервана системой")
                strokes.clear()
                lastPoint.clear()
                pending.clear()
                finish()
            }
        }, handler)
        if (!sent) {
            inFlight = false
            AppLog.i("Передать жест не получилось — выключите и включите Tongway в спец. возможностях")
            strokes.clear()
            finish()
        }
    }

    private fun finish() {
        if (!active) return
        active = false
        handler.removeCallbacks(watchdog)
        AppLog.i("Жест двумя пальцами передан (частей: $segments)")
        lastDispatch = 0L
        onFinished()
    }

    private companion object {
        const val FIRST_MS = 20L
        const val MIN_MS = 8L
        const val MAX_MS = 120L
        const val STALL_MS = 4000L
    }
}
