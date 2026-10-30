package com.tongway.translator.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import com.tongway.translator.App
import com.tongway.translator.FingerSelect
import com.tongway.translator.PageLayout
import com.tongway.translator.BubbleTileService
import com.tongway.translator.HistoryActivity
import com.tongway.translator.HistoryStore
import com.tongway.translator.ImageTextFinder
import com.tongway.translator.SettingsActivity
import com.tongway.translator.ModelDownloadWatcher
import com.tongway.translator.ui.MechanicalDnaView
import com.tongway.translator.ui.TechProgressView
import com.tongway.translator.ui.flashCopied
import com.tongway.translator.AppLog
import com.tongway.translator.Prefs
import com.tongway.translator.R
import com.tongway.translator.ScreenTextFinder
import com.tongway.translator.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Все окна поверх приложений: кружок, прицел, крестик, рамка, карточка с переводом
 * и перевод всего экрана.
 * Окна типа TYPE_ACCESSIBILITY_OVERLAY принадлежат службе спец. возможностей,
 * поэтому отдельное разрешение «поверх других окон» не нужно.
 */
class OverlayController(private val service: AccessibilityService) {

    private val context = ContextThemeWrapper(service, R.style.Theme_Tongway)
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val scope = MainScope()
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var bubbleSize = dp(Prefs.bubbleSizeDp(service))
    private var idleAlpha = Prefs.bubbleAlpha(service)
    private val aimSize = dp(AIM_DP)
    private val closeSize = dp(CLOSE_DP)

    private var bubble: BubbleView? = null
    private val bubbleParams = overlayParams(bubbleSize, bubbleSize, touchable = true)
    private var snapAnimator: ValueAnimator? = null

    private var crosshair: CrosshairView? = null
    /** Захват цели: прицел надо задержать на месте LOCK_MS, тогда после отпускания будет перевод. */
    private var lockAnimator: ValueAnimator? = null
    private val lockAnchor = Point()
    private var targetLocked = false
    private val crosshairParams = overlayParams(aimSize, aimSize, touchable = false)

    private var closeZone: CloseZoneView? = null
    private val closeParams = overlayParams(closeSize, closeSize, touchable = false)

    private var highlight: HighlightView? = null
    private var popup: Popup? = null
    private var translateJob: Job? = null
    private var lastOutsideDismiss = 0L

    private var screenOverlay: ScreenTranslationView? = null

    // «Пальцевый перевод»: слой на весь экран для пальца и плашка вверху.
    private var fingerCanvas: FingerCanvasView? = null
    private var fingerBar: View? = null
    private var fingerCapture: Deferred<ImageTextFinder.Capture>? = null
    private var lastFingerExit = 0L
    /** Меню по касанию кружка ждёт, не будет ли второго касания (двойное — пальцевый режим). */
    private var pendingMenu: Runnable? = null
    private var scanView: ScanOverlayView? = null
    private var screenBar: View? = null

    /** Сдвиг между координатами окон и экрана (на некоторых телефонах из-за выреза камеры). */
    private val windowOffset = Point()

    val isBubbleShown: Boolean get() = bubble != null

    // ---------- Кружок ----------

    fun showBubble() {
        if (bubble != null) return
        bubbleSize = dp(Prefs.bubbleSizeDp(service))
        idleAlpha = Prefs.bubbleAlpha(service)
        bubbleParams.width = bubbleSize
        bubbleParams.height = bubbleSize
        val view = BubbleView(context).apply {
            alpha = idleAlpha
            contentDescription = context.getString(R.string.service_label)
            setOnTouchListener(BubbleTouchListener())
        }
        val screen = screenSize()
        bubbleParams.x = Prefs.bubbleX(service, screen.x - bubbleSize)
        bubbleParams.y = Prefs.bubbleY(service, screen.y / 3)
        clampBubble(screen)
        if (addWindow(view, bubbleParams)) {
            bubble = view
            AppLog.i("Кружок показан")
        }
        BubbleTileService.refresh(service)
    }

    /** Применить настройки кружка (размер, прозрачность) без перезапуска. */
    fun applySettings() {
        idleAlpha = Prefs.bubbleAlpha(service)
        val newSize = dp(Prefs.bubbleSizeDp(service))
        val view = bubble
        if (view == null) {
            bubbleSize = newSize
            return
        }
        view.alpha = idleAlpha
        if (newSize != bubbleSize) {
            val centerX = bubbleParams.x + bubbleSize / 2
            val centerY = bubbleParams.y + bubbleSize / 2
            bubbleSize = newSize
            bubbleParams.width = newSize
            bubbleParams.height = newSize
            bubbleParams.x = centerX - newSize / 2
            bubbleParams.y = centerY - newSize / 2
            clampBubble(screenSize())
            runCatching { windowManager.updateViewLayout(view, bubbleParams) }
        }
    }

    fun hideBubble() {
        snapAnimator?.cancel()
        exitFingerMode()
        removeDragViews()
        dismissPopup()
        closeScreenTranslation()
        bubble?.let { removeWindow(it) }
        if (bubble != null) AppLog.i("Кружок убран")
        bubble = null
        BubbleTileService.refresh(service)
    }

    fun onScreenChanged() {
        val view = bubble ?: return
        dismissPopup()
        closeScreenTranslation()
        exitFingerMode()
        clampBubble(screenSize())
        windowManager.updateViewLayout(view, bubbleParams)
    }

    fun destroy() {
        translateJob?.cancel()
        pendingMenu?.let { bubble?.removeCallbacks(it) }
        exitFingerMode()
        hideBubble()
        closeScreenTranslation()
        removeScan()
        scope.cancel()
    }

    private fun clampBubble(screen: Point) {
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxOf(0, screen.x - bubbleSize))
        bubbleParams.y = bubbleParams.y.coerceIn(dp(24), maxOf(dp(24), screen.y - bubbleSize - dp(24)))
    }

    /** После отпускания кружок отъезжает к ближайшему краю, чтобы не закрывать текст. */
    private fun snapToEdge() {
        val view = bubble ?: return
        val screen = screenSize()
        clampBubble(screen)
        if (!Prefs.snapToEdge(service)) {
            runCatching { windowManager.updateViewLayout(view, bubbleParams) }
            Prefs.saveBubblePosition(service, bubbleParams.x, bubbleParams.y)
            return
        }
        val centerX = bubbleParams.x + bubbleSize / 2
        val targetX = if (centerX < screen.x / 2) 0 else screen.x - bubbleSize
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(bubbleParams.x, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (bubble == null) return@addUpdateListener
                bubbleParams.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, bubbleParams) }
            }
            start()
        }
        Prefs.saveBubblePosition(service, targetX, bubbleParams.y)
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var pressedView: View? = null

        /** Долгое нажатие без перетаскивания — перевод всего экрана. */
        private val longPress = Runnable {
            if (dragging) return@Runnable
            longPressed = true
            pressedView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            translateScreen()
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    downX = event.rawX
                    downY = event.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    longPressed = false
                    pressedView = view
                    view.alpha = 1f
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && !longPressed && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        view.removeCallbacks(longPress)
                        startDrag()
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.roundToInt()
                        bubbleParams.y = startY + dy.roundToInt()
                        windowManager.updateViewLayout(view, bubbleParams)
                        updateDragViews()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    view.alpha = idleAlpha
                    when {
                        dragging -> endDrag(cancelled = false)
                        !longPressed -> onBubbleTap()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    view.alpha = idleAlpha
                    if (dragging) endDrag(cancelled = true)
                }
            }
            return true
        }
    }

    /**
     * Касание кружка: меню. Двойное касание — включить «пальцевый перевод».
     * В пальцевом режиме любое касание кружка его выключает.
     */
    private fun onBubbleTap() {
        val now = SystemClock.uptimeMillis()
        if (isFingerMode) {
            exitFingerMode()
            return
        }
        // Второе касание двойного, которым режим только что выключили, — не открываем меню.
        if (now - lastFingerExit < DOUBLE_TAP_MS * 2) return
        pendingMenu?.let { waiting ->
            bubble?.removeCallbacks(waiting)
            pendingMenu = null
            enterFingerMode()
            return
        }
        // Касание кружка уже закрыло карточку (как касание вне её) — второй раз не открываем.
        if (now - lastOutsideDismiss < 600) return
        if (popup != null) {
            dismissPopup()
            return
        }
        val waiting = Runnable {
            pendingMenu = null
            showPopup(bubbleAnchor()).showMenu()
        }
        pendingMenu = waiting
        bubble?.postDelayed(waiting, DOUBLE_TAP_MS)
    }

    // ---------- Пальцевый перевод ----------

    val isFingerMode: Boolean get() = fingerCanvas != null

    private fun enterFingerMode() {
        if (fingerCanvas != null || bubble == null) return
        dismissPopup()
        closeScreenTranslation()
        updateWindowOffset()
        val screen = screenSize()
        val canvas = FingerCanvasView(context, fingerListener).apply {
            strokeWidthPx = dp(Prefs.fingerWidthDp(Prefs.fingerThickness(service)).roundToInt()).toFloat()
        }
        val params = overlayParams(screen.x, screen.y, touchable = true).apply {
            x = -windowOffset.x
            y = -windowOffset.y
        }
        if (!addWindow(canvas, params)) return
        fingerCanvas = canvas

        val bar = LayoutInflater.from(context).inflate(R.layout.finger_bar, null)
        bar.findViewById<View>(R.id.fingerBarClose).setOnClickListener { exitFingerMode() }
        val barParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
        }
        if (addWindow(bar, barParams)) fingerBar = bar

        // Кружок должен остаться поверх слоя для пальца — иначе его не коснуться.
        // Переставляем его чуть позже: включить режим могли прямо из касания кружка.
        bubble?.let { view ->
            view.fingerMode = true
            canvas.post {
                if (bubble !== view || fingerCanvas !== canvas) return@post
                removeWindow(view)
                addWindow(view, bubbleParams)
            }
        }
        AppLog.i("Пальцевый перевод включён, толщина ${Prefs.fingerThickness(service)}")
    }

    private fun exitFingerMode() {
        val canvas = fingerCanvas ?: return
        fingerCapture?.cancel()
        fingerCapture = null
        dismissPopup()
        removeWindow(canvas)
        fingerCanvas = null
        fingerBar?.let { removeWindow(it) }
        fingerBar = null
        bubble?.fingerMode = false
        lastFingerExit = SystemClock.uptimeMillis()
        AppLog.i("Пальцевый перевод выключен")
    }

    private val fingerListener = object : FingerCanvasView.Listener {
        /** Снимок экрана — сразу в момент касания, чтобы субтитры не успели смениться. */
        override fun onStrokeStart() {
            dismissPopup()
            val canvas = fingerCanvas ?: return
            canvas.hideStroke = true
            fingerCapture?.cancel()
            fingerCapture = scope.async {
                val view = bubble
                view?.alpha = 0f
                fingerBar?.visibility = View.INVISIBLE
                try {
                    delay(FINGER_CAPTURE_DELAY_MS)
                    ImageTextFinder.capture(service)
                } finally {
                    view?.alpha = idleAlpha
                    fingerBar?.visibility = View.VISIBLE
                    fingerCanvas?.hideStroke = false
                }
            }
        }

        override fun onStrokeEnd(points: List<PageLayout.Pt>) {
            val capture = fingerCapture ?: return
            translateJob?.cancel()
            translateJob = scope.launch { translateStroke(capture, points) }
        }
    }

    private suspend fun translateStroke(captureJob: Deferred<ImageTextFinder.Capture>, points: List<PageLayout.Pt>) {
        val canvas = fingerCanvas ?: return
        val width = canvas.strokeWidthPx
        val radius = width / 2
        val strokeBox = Rect(
            points.minOf { it.x }.toInt(), points.minOf { it.y }.toInt(),
            points.maxOf { it.x }.toInt(), points.maxOf { it.y }.toInt(),
        )
        val anchor = Rect(strokeBox).apply { inset(-radius.toInt(), -radius.toInt()) }
        val capture = captureJob.await()
        if (capture is ImageTextFinder.Capture.Failed) {
            canvas.clear()
            showPopup(anchor).showMessage(context.getString(R.string.ocr_problem, capture.reason))
            return
        }
        val bitmap = (capture as ImageTextFinder.Capture.Ok).bitmap
        val card = showPopup(anchor)
        card.showStatus(context.getString(R.string.finger_reading))
        bubble?.busy = true
        try {
            // Читаем только область вокруг линии — так быстрее; с запасом, чтобы слова у краёв не обрезались.
            val area = Rect(strokeBox).apply { inset(-(radius + dp(60)).toInt(), -(radius + dp(30)).toInt()) }
            val words = withContext(Dispatchers.Default) { ImageTextFinder.recognizeWords(bitmap, area) }
            val selection = FingerSelect.select(words, points, radius)
            AppLog.i("Пальцевый перевод: слов рядом ${words.size}, выделено ${selection.words.size}")
            if (selection.text.isBlank()) {
                canvas.clear()
                card.showMessage(context.getString(R.string.finger_nothing))
                return
            }
            canvas.showMarks(selection.words.map { w ->
                Rect(
                    w.corners.minOf { it.x }.toInt(), w.corners.minOf { it.y }.toInt(),
                    w.corners.maxOf { it.x }.toInt(), w.corners.maxOf { it.y }.toInt(),
                )
            })
            card.showStatus(context.getString(R.string.translating))
            val result = App.engine.translate(
                selection.text,
                onStatus = { card.showStatus(it) },
                onDownload = { card.showDownload(it) },
                fromImage = true,
            )
            card.showResult(result)
            if (!result.alreadyTarget) {
                HistoryStore.add(service, result.original, result.translated, appLabel(ScreenTextFinder.topPackage(service)), result.provider)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Ошибка пальцевого перевода", e)
            card.showMessage(context.getString(R.string.translate_error, e.message ?: e.javaClass.simpleName))
        } finally {
            bitmap.recycle()
            bubble?.busy = false
        }
    }

    // ---------- Перетаскивание: прицел и крестик ----------

    private fun startDrag() {
        dismissPopup()
        closeScreenTranslation()
        val aim = CrosshairView(context)
        val close = CloseZoneView(context)
        val screen = screenSize()
        closeParams.x = (screen.x - closeSize) / 2
        closeParams.y = screen.y - closeSize - dp(56)
        positionCrosshair()
        if (addWindow(close, closeParams)) closeZone = close
        if (addWindow(aim, crosshairParams)) crosshair = aim
        restartLock()
    }

    /** Прицел сдвинулся — захват начинается заново с текущей точки. */
    private fun restartLock() {
        lockAnimator?.cancel()
        targetLocked = false
        crosshair?.locked = false
        crosshair?.progress = 0f
        lockAnchor.set(crosshairParams.x, crosshairParams.y)
        if (isOverCloseZone()) return
        lockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOCK_MS
            addUpdateListener { crosshair?.progress = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    targetLocked = true
                    crosshair?.let {
                        it.locked = true
                        it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                }
            })
            start()
        }
    }

    private fun stopLock() {
        lockAnimator?.cancel()
        lockAnimator = null
    }

    private fun updateDragViews() {
        crosshair?.let {
            positionCrosshair()
            windowManager.updateViewLayout(it, crosshairParams)
        }
        closeZone?.active = isOverCloseZone()
        // Небольшое дрожание пальца захват не сбрасывает.
        val moved = hypot(
            (crosshairParams.x - lockAnchor.x).toFloat(),
            (crosshairParams.y - lockAnchor.y).toFloat(),
        )
        if (moved > dp(LOCK_SLOP_DP)) restartLock()
    }

    /** Прицел стоит над кружком, чтобы палец не закрывал текст. */
    private fun positionCrosshair() {
        val centerX = bubbleParams.x + bubbleSize / 2
        val aimY = bubbleParams.y + bubbleSize / 2 - dp(AIM_OFFSET_DP)
        crosshairParams.x = centerX - aimSize / 2
        crosshairParams.y = aimY - aimSize / 2
    }

    private fun isOverCloseZone(): Boolean {
        if (closeZone == null) return false
        val bx = bubbleParams.x + bubbleSize / 2f
        val by = bubbleParams.y + bubbleSize / 2f
        val cx = closeParams.x + closeSize / 2f
        val cy = closeParams.y + closeSize / 2f
        return hypot(bx - cx, by - cy) < dp(80)
    }

    private fun endDrag(cancelled: Boolean) {
        val overClose = isOverCloseZone()
        val aim = aimPointOnScreen()
        val locked = targetLocked
        stopLock()
        targetLocked = false
        updateWindowOffset()
        removeDragViews()
        when {
            cancelled -> snapToEdge()
            overClose -> {
                hideBubble()
                Prefs.setBubbleEnabled(service, false)
                Toast.makeText(service, R.string.bubble_closed, Toast.LENGTH_LONG).show()
            }
            // Цель захвачена — переводим. Иначе кружок просто переехал.
            locked -> {
                snapToEdge()
                translateAt(aim.x, aim.y)
            }
            else -> snapToEdge()
        }
    }

    private fun aimPointOnScreen(): Point {
        val view = crosshair
        if (view != null && view.isAttachedToWindow) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return Point(location[0] + aimSize / 2, location[1] + aimSize / 2)
        }
        return Point(crosshairParams.x + aimSize / 2, crosshairParams.y + aimSize / 2)
    }

    private fun updateWindowOffset() {
        val view = bubble ?: return
        if (!view.isAttachedToWindow) return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        windowOffset.set(location[0] - bubbleParams.x, location[1] - bubbleParams.y)
    }

    private fun removeDragViews() {
        stopLock()
        crosshair?.let { removeWindow(it) }
        crosshair = null
        closeZone?.let { removeWindow(it) }
        closeZone = null
    }

    // ---------- Перевод ----------

    private fun translateAt(x: Int, y: Int) {
        translateJob?.cancel()
        dismissPopup()
        translateJob = scope.launch {
            var found = try {
                withContext(Dispatchers.Default) { ScreenTextFinder.find(service, x, y) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Ошибка при поиске текста", e)
                null
            }
            // Обычного текста нет (или это подпись к картинке) — ищем текст на самой картинке.
            // Сканируем только полосу вокруг прицела во всю ширину: так быстро и точно
            // ловятся субтитры, а лишние надписи с экрана не попадают.
            var imageProblem: String? = null
            var fromImage = false
            if (found == null || found.weak) {
                val screen = screenSize()
                val half = dp(BAND_HALF_DP)
                val band = Rect(0, (y - half).coerceAtLeast(0), screen.x, (y + half).coerceAtMost(screen.y))
                val (blocks, problem) = recognizeScreen(band, aim = Point(x, y), exclude = excludedZones(withAppText = false))
                removeScan()
                val block = ImageTextFinder.blockAt(blocks, x, y, dp(28))
                if (block != null) {
                    AppLog.i("Текст найден на картинке: ${block.text.length} симв.")
                    found = ScreenTextFinder.Found(block.text, block.bounds, ScreenTextFinder.topPackage(service))
                    fromImage = true
                } else {
                    imageProblem = problem
                }
            }
            if (found == null) {
                AppLog.i("Текст не найден в точке ($x, $y)")
                val message = context.getString(R.string.no_text_found) +
                    (imageProblem?.let { "\n\n" + context.getString(R.string.ocr_problem, it) } ?: "")
                showPopup(Rect(x - 1, y - 1, x + 1, y + 1)).showMessage(message)
                return@launch
            }
            AppLog.i("Найден текст в ${found.packageName}: ${found.text.length} симв.")
            val card = showPopup(found.bounds)
            showHighlight(found.bounds)
            card.showStatus(context.getString(R.string.translating))
            bubble?.busy = true
            try {
                val result = App.engine.translate(
                    found.text,
                    onStatus = { status -> card.showStatus(status) },
                    onDownload = { progress -> card.showDownload(progress) },
                    fromImage = fromImage,
                )
                card.showResult(result)
                if (!result.alreadyTarget) {
                    HistoryStore.add(service, result.original, result.translated, appLabel(found.packageName), result.provider)
                }
                // Текст с картинки — бонусом можно отсканировать весь экран.
                if (fromImage) card.offerPage(found.bounds, found.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Ошибка перевода", e)
                card.showMessage(context.getString(R.string.translate_error, e.message ?: e.javaClass.simpleName))
            } finally {
                bubble?.busy = false
            }
        }
    }

    // ---------- Перевод всего экрана ----------

    private fun translateScreen() {
        translateJob?.cancel()
        dismissPopup()
        closeScreenTranslation()
        updateWindowOffset()
        translateJob = scope.launch {
            if (!App.engine.isModelDownloaded(TranslationEngine.TARGET) ||
                !App.engine.isModelDownloaded(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            ) {
                showPopup(bubbleAnchor()).showMessage(context.getString(R.string.screen_need_model))
                return@launch
            }
            val screen = screenSize()
            val (blocks, packageName) = try {
                withContext(Dispatchers.Default) {
                    ScreenTextFinder.collectAll(service, Rect(0, 0, screen.x, screen.y))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Ошибка при сборе текста с экрана", e)
                emptyList<ScreenTextFinder.Block>() to "?"
            }
            // Добавляем текст с картинок, если его нет среди обычного текста.
            val (imageBlocks, imageProblem) = recognizeScreen(null, exclude = excludedZones(withAppText = true))
            removeScan()
            val fromImages = imageBlocks
                .filter { image -> blocks.none { overlapShare(image.bounds, it.bounds) > 0.5f } }
                .map { ScreenTextFinder.Block(it.text, it.bounds) }
            if (fromImages.isNotEmpty()) AppLog.i("Перевод экрана: с картинок ${fromImages.size} кусков")
            val english = (blocks + fromImages)
                .filterNot { App.engine.looksCyrillic(it.text) }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            if (english.isEmpty()) {
                AppLog.i("Перевод экрана: текста не нашлось")
                val message = context.getString(R.string.screen_no_text) +
                    (imageProblem?.let { "\n\n" + context.getString(R.string.ocr_problem, it) } ?: "")
                showPopup(bubbleAnchor()).showMessage(message)
                return@launch
            }
            AppLog.i("Перевод экрана в $packageName: ${english.size} кусков текста")
            showPlates(english.map { it.text to it.bounds }, fromImage = fromImages.isNotEmpty())
        }
    }

    /** Плашки с переводом поверх каждого куска текста и панель внизу. */
    private suspend fun showPlates(pieces: List<Pair<String, Rect>>, fromImage: Boolean) {
        closeScreenTranslation()
        val screen = screenSize()
        val overlay = ScreenTranslationView(context, pieces.map { it.second })
        val fullScreen = overlayParams(screen.x, screen.y, touchable = false).apply {
            x = -windowOffset.x
            y = -windowOffset.y
        }
        if (!addWindow(overlay, fullScreen)) return
        screenOverlay = overlay
        val bar = showScreenBar(overlay)
        val status = bar.findViewById<TextView>(R.id.barStatus)
        val dna = bar.findViewById<MechanicalDnaView>(R.id.barDna)
        bubble?.busy = true
        try {
            val outcome = App.engine.translateMany(
                pieces.map { it.first },
                onStatus = { status.text = it },
                onEach = { index, text -> overlay.setTranslation(index, text) },
                fromImage = fromImage,
            )
            status.text = context.getString(R.string.screen_done, pieces.size) +
                "\n" + outcome.provider + (outcome.note?.let { " · $it" } ?: "")
            bar.findViewById<View>(R.id.barCopy).visibility = View.VISIBLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Ошибка перевода экрана", e)
            status.text = context.getString(R.string.screen_error, e.message ?: e.javaClass.simpleName)
        } finally {
            dna.animating = false
            bubble?.busy = false
        }
    }

    /**
     * Бонус «Весь текст» / «Поверх фото»: заново сканируем весь экран (с большой полосой)
     * и показываем перевод всего текста в карточке или плашками на картинке.
     */
    private fun scanWholeScreen(anchor: Rect, packageName: String, plates: Boolean) {
        translateJob?.cancel()
        closePopupWindows()
        removeScan()
        translateJob = scope.launch {
            val (blocks, problem) = recognizeScreen(null, exclude = excludedZones(withAppText = true))
            val english = blocks.filterNot { App.engine.looksCyrillic(it.text) }
            if (english.isEmpty()) {
                removeScan()
                val message = context.getString(R.string.screen_no_text) +
                    (problem?.let { "\n\n" + context.getString(R.string.ocr_problem, it) } ?: "")
                showPopup(anchor).showMessage(message)
                return@launch
            }
            if (plates) {
                removeScan()
                showPlates(english.map { it.text to it.bounds }, fromImage = true)
            } else {
                translatePage(showPopup(anchor), english, packageName)
            }
        }
    }

    /** «Весь текст»: все абзацы по порядку прямо в карточке, одной записью в историю. */
    private suspend fun translatePage(card: Popup, english: List<ImageTextFinder.Block>, packageName: String) {
        scanView?.highlightAll(context.getString(R.string.page_label, english.size))
        card.prepareForPage()
        bubble?.busy = true
        try {
            card.showStatus(context.getString(R.string.page_progress, 0, english.size))
            val (translated, outcome) = App.engine.translateDocument(
                english.map { it.text },
                onStatus = { card.showStatus(it) },
                fromImage = true,
            )
            val result = TranslationEngine.Result(
                english.joinToString("\n\n") { it.text },
                translated,
                "en",
                alreadyTarget = false,
                provider = outcome.provider,
                note = outcome.note,
            )
            card.showResult(result)
            HistoryStore.add(service, result.original, result.translated, appLabel(packageName), result.provider)
            AppLog.i("Весь текст с экрана переведён: ${english.size} абз.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Ошибка перевода всего текста", e)
            card.showMessage(context.getString(R.string.translate_error, e.message ?: e.javaClass.simpleName))
        } finally {
            bubble?.busy = false
        }
    }

    /**
     * Снимок экрана и распознавание текста на нём. Кружок на время снимка становится
     * прозрачным, чтобы не закрыть текст. Пока идёт распознавание, по экрану едет
     * полоса сканирования; потом найденные абзацы обводятся рамками (окно scanView
     * остаётся, его убирает вызывающий). Возвращает абзацы и, если не вышло, причину.
     */
    private suspend fun recognizeScreen(
        area: Rect?,
        aim: Point? = null,
        exclude: List<Rect> = emptyList(),
    ): Pair<List<ImageTextFinder.Block>, String?> {
        val view = bubble
        view?.alpha = 0f
        val capture = try {
            delay(SCREENSHOT_DELAY_MS)
            ImageTextFinder.capture(service)
        } finally {
            view?.alpha = idleAlpha
        }
        if (capture is ImageTextFinder.Capture.Failed) {
            AppLog.i("Распознавание: ${capture.reason}")
            return emptyList<ImageTextFinder.Block>() to capture.reason
        }
        val bitmap = (capture as ImageTextFinder.Capture.Ok).bitmap
        val scan = showScan(area)
        bubble?.busy = true
        return try {
            val started = SystemClock.uptimeMillis()
            // Полоса должна успеть хотя бы раз проехать — так видно, что именно сканируется.
            var blocks = withContext(Dispatchers.Default) { ImageTextFinder.recognize(bitmap, area, exclude) }
            // Абзац под прицелом выходит за полосу — это не субтитр, а печатный текст:
            // дочитываем тот же снимок целиком, чтобы взять абзац полностью.
            if (area != null && aim != null) {
                val hit = ImageTextFinder.blockAt(blocks, aim.x, aim.y, dp(28))
                if (hit != null && (hit.bounds.top <= area.top + dp(4) || hit.bounds.bottom >= area.bottom - dp(4))) {
                    AppLog.i("Абзац под прицелом выходит за полосу — читаю весь снимок")
                    blocks = withContext(Dispatchers.Default) { ImageTextFinder.recognize(bitmap, null, exclude) }
                }
            }
            val elapsed = SystemClock.uptimeMillis() - started
            val minimum = if (area != null) MIN_AREA_SCAN_MS else MIN_SCAN_MS
            if (elapsed < minimum) delay(minimum - elapsed)
            AppLog.i("Распознавание: ${blocks.size} абзацев за $elapsed мс")
            scan?.showBlocks(blocks.map { it.bounds }, context.getString(R.string.scan_found, blocks.size))
            blocks to if (blocks.isEmpty()) context.getString(R.string.ocr_nothing) else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Ошибка распознавания текста", e)
            emptyList<ImageTextFinder.Block>() to (e.message ?: e.javaClass.simpleName)
        } finally {
            bitmap.recycle()
            bubble?.busy = false
        }
    }

    private fun showScan(area: Rect?): ScanOverlayView? {
        removeScan()
        val screen = screenSize()
        val view = ScanOverlayView(context, area)
        view.showScanning(context.getString(R.string.scan_working))
        val params = overlayParams(screen.x, screen.y, touchable = false).apply {
            x = -windowOffset.x
            y = -windowOffset.y
        }
        if (!addWindow(view, params)) return null
        scanView = view
        return view
    }

    private fun removeScan() {
        scanView?.let { removeWindow(it) }
        scanView = null
    }

    /**
     * Где на снимке текст точно не с картинки: системные панели и клавиатура, а для всего экрана —
     * ещё и надписи самих приложений (заголовки, кнопки, время), их переводят отдельно.
     */
    private suspend fun excludedZones(withAppText: Boolean): List<Rect> = withContext(Dispatchers.Default) {
        val screen = screenSize()
        val screenArea = screen.x.toLong() * screen.y
        val zones = mutableListOf<Rect>()
        runCatching {
            for (window in service.windows) {
                val type = window.type
                if (type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                ) continue
                val r = Rect()
                window.getBoundsInScreen(r)
                // Системное окно во весь экран (шторка, панели) не исключаем — иначе исключим всё.
                if (r.width().toLong() * r.height() < screenArea * 0.4) zones.add(r)
            }
            if (withAppText) {
                zones += ScreenTextFinder.collectAll(service, Rect(0, 0, screen.x, screen.y)).first.map { it.bounds }
            }
        }.onFailure { AppLog.e("Не удалось определить зоны без картинки", it) }
        zones
    }

    /** Какая доля прямоугольника a перекрыта прямоугольником b. */
    private fun overlapShare(a: Rect, b: Rect): Float {
        val i = Rect()
        if (!i.setIntersect(a, b)) return 0f
        val area = a.width().toFloat() * a.height()
        return if (area <= 0f) 0f else i.width() * i.height() / area
    }

    /** Панель внизу: ход перевода, «Копировать» и «✕». Касание в любом другом месте закрывает перевод. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showScreenBar(overlay: ScreenTranslationView): View {
        val bar = LayoutInflater.from(context).inflate(R.layout.screen_bar, null)
        bar.background = popupBackground()
        val screen = screenSize()
        val width = minOf(screen.x - dp(24), dp(520))
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = (screen.x - width) / 2 - windowOffset.x
            y = screen.y - dp(140) - windowOffset.y
        }
        bar.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                lastOutsideDismiss = SystemClock.uptimeMillis()
                translateJob?.cancel()
                closeScreenTranslation()
                true
            } else {
                false
            }
        }
        bar.findViewById<View>(R.id.barClose).setOnClickListener {
            translateJob?.cancel()
            closeScreenTranslation()
        }
        bar.findViewById<View>(R.id.barCopy).setOnClickListener {
            val clipboard = service.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Перевод экрана", overlay.allTranslations()))
            (it as TextView).flashCopied()
        }
        if (addWindow(bar, params)) screenBar = bar
        return bar
    }

    private fun closeScreenTranslation() {
        screenOverlay?.let { removeWindow(it) }
        screenOverlay = null
        screenBar?.let {
            it.findViewById<MechanicalDnaView>(R.id.barDna)?.animating = false
            removeWindow(it)
        }
        screenBar = null
    }

    private fun bubbleAnchor(): Rect {
        val anchor = Rect(bubbleParams.x, bubbleParams.y, bubbleParams.x + bubbleSize, bubbleParams.y + bubbleSize)
        anchor.offset(windowOffset.x, windowOffset.y)
        return anchor
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun openActivity(activity: Class<*>) {
        dismissPopup()
        runCatching {
            service.startActivity(Intent(service, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLog.e("Не удалось открыть экран приложения", it) }
    }

    private fun showHighlight(bounds: Rect) {
        removeHighlight()
        val pad = dp(6)
        val params = overlayParams(bounds.width() + pad * 2, bounds.height() + pad * 2, touchable = false).apply {
            x = bounds.left - pad - windowOffset.x
            y = bounds.top - pad - windowOffset.y
        }
        val view = HighlightView(context)
        if (addWindow(view, params)) highlight = view
    }

    private fun removeHighlight() {
        highlight?.let { removeWindow(it) }
        highlight = null
    }

    // ---------- Карточка с переводом ----------

    private fun showPopup(anchor: Rect): Popup {
        closePopupWindows()
        val card = Popup(anchor)
        popup = card
        return card
    }

    /** Закрыть карточку и остановить перевод, если он ещё идёт. */
    private fun dismissPopup() {
        translateJob?.cancel()
        closePopupWindows()
        removeScan()
    }

    private fun closePopupWindows() {
        popup?.remove()
        popup = null
        removeHighlight()
    }

    /** Тёмная карточка с оранжевым свечением в углу, как карточки на слайдах. */
    private fun popupBackground(): Drawable {
        val radius = dp(22).toFloat()
        val base = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0xF50B0B0B.toInt())
            setStroke(dp(1), 0xFF3A3A3A.toInt())
        }
        val glow = GradientDrawable().apply {
            cornerRadius = radius
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(260).toFloat()
            setGradientCenter(1f, 0f)
            colors = intArrayOf(0x995A2203.toInt(), 0x005A2203)
        }
        return LayerDrawable(arrayOf(base, glow))
    }

    /** Карточка рядом с текстом. Закрывается касанием в любом другом месте экрана. */
    @SuppressLint("ClickableViewAccessibility")
    private inner class Popup(private val anchor: Rect) {
        private val root = LayoutInflater.from(context).inflate(R.layout.popup_translation, null)
        private val scroll = root.findViewById<MaxHeightScrollView>(R.id.popupScroll)
        private val loader = root.findViewById<View>(R.id.popupLoader)
        private val dna = root.findViewById<MechanicalDnaView>(R.id.popupDna)
        private val status = root.findViewById<TextView>(R.id.popupStatus)
        private val downloadBar = root.findViewById<TechProgressView>(R.id.popupProgress)
        private val translation = root.findViewById<TextView>(R.id.popupTranslation)
        private val original = root.findViewById<TextView>(R.id.popupOriginal)
        private val language = root.findViewById<TextView>(R.id.popupLang)
        private val btnOriginal = root.findViewById<TextView>(R.id.popupBtnOriginal)
        private val btnCopy = root.findViewById<TextView>(R.id.popupBtnCopy)
        private val btnClose = root.findViewById<TextView>(R.id.popupBtnClose)
        private val menu = root.findViewById<View>(R.id.popupMenu)
        private val pageRow = root.findViewById<View>(R.id.popupPageRow)
        private val note = root.findViewById<TextView>(R.id.popupNote)

        private val params: WindowManager.LayoutParams
        private var attached = false

        init {
            root.background = popupBackground()
            val screen = screenSize()
            val width = minOf(screen.x - dp(24), dp(520))
            params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                x = (screen.x - width) / 2 - windowOffset.x
            }
            scroll.maxHeightPx = (screen.y * 0.45f).toInt()

            root.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (popup === this) {
                        lastOutsideDismiss = SystemClock.uptimeMillis()
                        dismissPopup()
                    }
                    true
                } else {
                    false
                }
            }
            btnClose.setOnClickListener { if (popup === this) dismissPopup() }
            btnOriginal.setOnClickListener {
                val show = original.visibility != View.VISIBLE
                original.visibility = if (show) View.VISIBLE else View.GONE
                btnOriginal.setText(if (show) R.string.popup_hide_original else R.string.popup_original)
                reposition()
            }
            btnCopy.setOnClickListener {
                val clipboard = service.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", translation.text))
                btnCopy.flashCopied()
            }
            root.findViewById<View>(R.id.menuScreen).setOnClickListener { translateScreen() }
            root.findViewById<View>(R.id.menuHistory).setOnClickListener { openActivity(HistoryActivity::class.java) }
            root.findViewById<View>(R.id.menuSettings).setOnClickListener { openActivity(SettingsActivity::class.java) }
            root.findViewById<View>(R.id.menuFinger).setOnClickListener { enterFingerMode() }
            setActionsVisible(false)
            attached = addWindow(root, params.also { it.y = placeY(measureHeight()) })
        }

        /** Кнопки «Весь текст» и «Поверх фото» для текста с картинки. */
        fun offerPage(anchor: Rect, packageName: String) {
            pageRow.visibility = View.VISIBLE
            root.findViewById<View>(R.id.popupBtnPage).setOnClickListener { scanWholeScreen(anchor, packageName, plates = false) }
            root.findViewById<View>(R.id.popupBtnPlates).setOnClickListener { scanWholeScreen(anchor, packageName, plates = true) }
            reposition()
        }

        /** Для всей страницы карточка выше и без кнопок выбора. */
        fun prepareForPage() {
            pageRow.visibility = View.GONE
            original.visibility = View.GONE
            btnOriginal.setText(R.string.popup_original)
            scroll.maxHeightPx = (screenSize().y * 0.6f).toInt()
        }

        /** Меню по касанию кружка: подсказка и кнопки. */
        fun showMenu() {
            showMessage(context.getString(R.string.bubble_tap_hint))
            menu.visibility = View.VISIBLE
            reposition()
        }

        /** Идёт работа: крутится ДНК и рядом подпись. */
        fun showStatus(text: String) {
            loader.visibility = View.VISIBLE
            dna.animating = true
            status.text = text
            translation.visibility = View.GONE
            reposition()
        }

        /** Пришлось скачивать словарь — показываем полосу загрузки. */
        fun showDownload(progress: ModelDownloadWatcher.Progress) {
            val fraction = progress.fraction
            downloadBar.visibility = View.VISIBLE
            downloadBar.progress = fraction
            status.text = when {
                progress.waitingReason != null -> "Скачивание словаря: ${progress.waitingReason}"
                fraction == null -> "Подключаюсь, чтобы скачать словарь…"
                else -> "Скачиваю словарь: ${ModelDownloadWatcher.formatMb(progress.downloadedBytes)} " +
                    "из ${if (progress.exact) "" else "~"}${ModelDownloadWatcher.formatMb(progress.totalBytes)}"
            }
            reposition()
        }

        fun showMessage(text: String) {
            stopLoader()
            translation.visibility = View.VISIBLE
            translation.text = text
            setActionsVisible(false)
            language.visibility = View.GONE
            reposition()
        }

        fun showResult(result: TranslationEngine.Result) {
            stopLoader()
            translation.visibility = View.VISIBLE
            original.text = result.original
            if (result.alreadyTarget) {
                translation.text = context.getString(R.string.already_russian)
                original.visibility = View.VISIBLE
                reposition()
                return
            }
            translation.text = result.translated
            language.text = "${result.sourceLanguage.uppercase()} → RU · ${result.provider}"
            language.visibility = View.VISIBLE
            // Облако не ответило — честно пишем, почему перевод сделан на телефоне.
            note.text = result.note?.let { context.getString(R.string.cloud_fallback, it) }
            note.visibility = if (result.note != null) View.VISIBLE else View.GONE
            setActionsVisible(true)
            reposition()
        }

        fun remove() {
            dna.animating = false
            if (attached) removeWindow(root)
            attached = false
        }

        private fun stopLoader() {
            dna.animating = false
            loader.visibility = View.GONE
            downloadBar.visibility = View.GONE
        }

        private fun setActionsVisible(visible: Boolean) {
            val state = if (visible) View.VISIBLE else View.GONE
            btnOriginal.visibility = state
            btnCopy.visibility = state
        }

        private fun measureHeight(): Int {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return root.measuredHeight
        }

        private fun reposition() {
            if (!attached) return
            params.y = placeY(measureHeight())
            runCatching { windowManager.updateViewLayout(root, params) }
        }

        /** Под текстом, если там хватает места, иначе над ним, иначе у края экрана. */
        private fun placeY(height: Int): Int {
            val screenHeight = screenSize().y
            val margin = dp(12)
            val top = margin + dp(24)
            val y = when {
                anchor.bottom + margin + height <= screenHeight - margin -> anchor.bottom + margin
                anchor.top - margin - height >= top -> anchor.top - margin - height
                anchor.centerY() < screenHeight / 2 -> screenHeight - margin - height
                else -> top
            }
            return y.coerceIn(0, maxOf(0, screenHeight - height)) - windowOffset.y
        }
    }

    // ---------- Окна ----------

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, params)
        true
    } catch (e: Exception) {
        AppLog.e("Не удалось показать окно поверх приложений", e)
        false
    }

    private fun removeWindow(view: View) {
        runCatching { windowManager.removeView(view) }
            .onFailure { AppLog.e("Не удалось убрать окно", it) }
    }

    private fun screenSize(): Point {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    companion object {
        private const val AIM_DP = 60
        /** Пауза, чтобы прозрачный кружок и убранный прицел не попали на снимок. */
        private const val SCREENSHOT_DELAY_MS = 90L
        /** Не меньше одного прохода полосы сканирования. */
        private const val MIN_SCAN_MS = 1100L
        private const val MIN_AREA_SCAN_MS = 500L
        /** Полоса вокруг прицела: по 80dp вверх и вниз — хватает на 2–3 строки субтитров. */
        private const val BAND_HALF_DP = 80
        /** Сколько держать прицел на месте, чтобы захватить цель. */
        private const val LOCK_MS = 500L
        /** Сколько ждать второго касания, прежде чем открыть меню. */
        private const val DOUBLE_TAP_MS = 280L
        /** Пауза перед снимком в пальцевом режиме: кружок и плашка успевают стать прозрачными. */
        private const val FINGER_CAPTURE_DELAY_MS = 50L
        /** Насколько можно сдвинуть палец, не сбрасывая захват. */
        private const val LOCK_SLOP_DP = 14
        private const val AIM_OFFSET_DP = 72
        private const val CLOSE_DP = 72
    }
}
