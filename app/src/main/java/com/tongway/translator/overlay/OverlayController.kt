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
import com.tongway.translator.BubbleTileService
import com.tongway.translator.HistoryActivity
import com.tongway.translator.HistoryStore
import com.tongway.translator.SettingsActivity
import com.tongway.translator.ModelDownloadWatcher
import com.tongway.translator.ui.MechanicalDnaView
import com.tongway.translator.ui.TechProgressView
import com.tongway.translator.AppLog
import com.tongway.translator.Prefs
import com.tongway.translator.R
import com.tongway.translator.ScreenTextFinder
import com.tongway.translator.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
    private val crosshairParams = overlayParams(aimSize, aimSize, touchable = false)

    private var closeZone: CloseZoneView? = null
    private val closeParams = overlayParams(closeSize, closeSize, touchable = false)

    private var highlight: HighlightView? = null
    private var popup: Popup? = null
    private var translateJob: Job? = null
    private var lastOutsideDismiss = 0L

    private var screenOverlay: ScreenTranslationView? = null
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
        clampBubble(screenSize())
        windowManager.updateViewLayout(view, bubbleParams)
    }

    fun destroy() {
        translateJob?.cancel()
        hideBubble()
        closeScreenTranslation()
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

    private fun onBubbleTap() {
        // Касание кружка уже закрыло карточку (как касание вне её) — второй раз не открываем.
        if (SystemClock.uptimeMillis() - lastOutsideDismiss < 600) return
        if (popup != null) {
            dismissPopup()
            return
        }
        showPopup(bubbleAnchor()).showMenu()
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
    }

    private fun updateDragViews() {
        crosshair?.let {
            positionCrosshair()
            windowManager.updateViewLayout(it, crosshairParams)
        }
        closeZone?.active = isOverCloseZone()
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
        updateWindowOffset()
        removeDragViews()
        when {
            cancelled -> snapToEdge()
            overClose -> {
                hideBubble()
                Prefs.setBubbleEnabled(service, false)
                Toast.makeText(service, R.string.bubble_closed, Toast.LENGTH_LONG).show()
            }
            else -> {
                snapToEdge()
                translateAt(aim.x, aim.y)
            }
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
            val found = try {
                withContext(Dispatchers.Default) { ScreenTextFinder.find(service, x, y) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Ошибка при поиске текста", e)
                null
            }
            if (found == null) {
                AppLog.i("Текст не найден в точке ($x, $y)")
                showPopup(Rect(x - 1, y - 1, x + 1, y + 1)).showMessage(context.getString(R.string.no_text_found))
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
                )
                card.showResult(result)
                if (!result.alreadyTarget) {
                    HistoryStore.add(service, result.original, result.translated, appLabel(found.packageName))
                }
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
            val english = blocks.filterNot { App.engine.looksCyrillic(it.text) }
            if (english.isEmpty()) {
                AppLog.i("Перевод экрана: текста не нашлось")
                showPopup(bubbleAnchor()).showMessage(context.getString(R.string.screen_no_text))
                return@launch
            }
            AppLog.i("Перевод экрана в $packageName: ${english.size} кусков текста")
            val overlay = ScreenTranslationView(context, english.map { it.bounds })
            val fullScreen = overlayParams(screen.x, screen.y, touchable = false).apply {
                x = -windowOffset.x
                y = -windowOffset.y
            }
            if (!addWindow(overlay, fullScreen)) return@launch
            screenOverlay = overlay
            val bar = showScreenBar(overlay)
            val status = bar.findViewById<TextView>(R.id.barStatus)
            val dna = bar.findViewById<MechanicalDnaView>(R.id.barDna)
            bubble?.busy = true
            try {
                english.forEachIndexed { index, block ->
                    status.text = context.getString(R.string.screen_progress, index, english.size)
                    overlay.setTranslation(index, App.engine.translateEnglish(block.text))
                }
                status.text = context.getString(R.string.screen_done, english.size)
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
            Toast.makeText(service, R.string.popup_copied, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(service, R.string.popup_copied, Toast.LENGTH_SHORT).show()
            }
            root.findViewById<View>(R.id.menuScreen).setOnClickListener { translateScreen() }
            root.findViewById<View>(R.id.menuHistory).setOnClickListener { openActivity(HistoryActivity::class.java) }
            root.findViewById<View>(R.id.menuSettings).setOnClickListener { openActivity(SettingsActivity::class.java) }
            setActionsVisible(false)
            attached = addWindow(root, params.also { it.y = placeY(measureHeight()) })
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
            language.text = "${result.sourceLanguage.uppercase()} → RU"
            language.visibility = View.VISIBLE
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
        private const val AIM_OFFSET_DP = 72
        private const val CLOSE_DP = 72
    }
}
