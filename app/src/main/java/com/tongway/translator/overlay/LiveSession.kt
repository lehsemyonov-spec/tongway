package com.tongway.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tongway.translator.App
import com.tongway.translator.FrameResize
import com.tongway.translator.Prefs
import com.tongway.translator.StaticFilter
import com.tongway.translator.AppLog
import com.tongway.translator.HistoryStore
import com.tongway.translator.ImageTextFinder
import com.tongway.translator.LiveText
import com.tongway.translator.PageLayout
import com.tongway.translator.R
import com.tongway.translator.SentenceJoiner
import com.tongway.translator.ScreenTextFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** То, что режиму Live нужно от OverlayController: окна, снимок экрана, размеры. */
interface LiveHost {
    val context: Context
    val scope: CoroutineScope
    fun dp(value: Int): Int
    fun overlayParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams
    fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean
    fun removeWindow(view: View)
    fun updateWindow(view: View, params: WindowManager.LayoutParams)
    fun screenSize(): Point
    fun windowOffset(): Point
    fun panelBackground(): Drawable
    /** Снимок экрана; кружок прячется, если он заходит на area. */
    suspend fun captureFor(area: Rect): ImageTextFinder.Capture
    fun appLabel(packageName: String): String
    fun topPackage(): String
    /** Пальцевый перевод ведёт линию или жест двумя пальцами — снимок Live пропускаем. */
    fun captureBusy(): Boolean
    fun onLiveEnded()
}

/**
 * Режим Live: рамка закреплена на месте субтитров, раз в ~0,7 с её текст распознаётся на телефоне,
 * новая фраза сразу переводится дважды — телефоном (бледный черновик, мгновенно) и нейросетью
 * (заменяет черновик). У каждой фразы своё место в ленте, поэтому опоздавший ответ не путается.
 * Пока Live включён — это один сеанс; при выключении он сохраняется в историю одной записью.
 */
class LiveSession(
    private val host: LiveHost,
    private var frame: Rect,
    private var packageName: String,
) {
    private class Phrase(val id: Int, var original: String) {
        /** Предложение ещё не кончилось — ждём продолжения в следующих субтитрах. */
        var open = false
        var draft: String? = null
        var final: String? = null
        var by: String? = null
        var row: View? = null
        /** Из скольких кусков субтитров склеено предложение. */
        var chunks = 0
    }

    /** Для журнала: что происходило за сеанс. */
    private class Stats {
        var chunks = 0
        var joined = 0
        var timeouts = 0
        var cloudAnswers = 0
        var cloudMs = 0L
        var phoneOnly = 0
        var captureErrors = 0
        var unreadable = 0
        var russian = 0
        var staticSkipped = 0
        var moved = 0
        var resized = 0
    }
    private val stats = Stats()

    // Пустая рамка и нечитаемый текст — пишем в журнал один раз, а не каждые 0,7 с.
    private var emptySince = 0L
    private var emptyLogged = false
    private var unreadableLogged = false
    private var captureProblem: String? = null

    private val context = host.context
    private val phrases = mutableListOf<Phrase>()
    private val pending = ArrayDeque<Phrase>()
    private val reader = LiveText()
    /** Склеивает куски субтитров в целые предложения по знакам препинания. */
    private val joiner = SentenceJoiner()
    /** Строка ленты с незаконченным предложением. */
    private var openPhrase: Phrase? = null
    private var lastChunkAt = 0L
    private var nextId = 1
    private var loop: Job? = null
    private var cloudBusy = false
    private var paused = false
    /** Ожидание: ушли в другое приложение или повернули экран (тогда ждём нового наведения кружка). */
    private enum class Wait { NONE, OTHER_APP, AIM }
    private var wait = Wait.NONE
    private var waitingSince = 0L
    private val waitingOtherApp get() = wait != Wait.NONE
    /** Надписи на видео (обложка, логотип) стоят на месте — их не переводим. */
    private val staticFilter = StaticFilter()
    /** Рамку прямо сейчас тянут за эти уголки. */
    private val heldCorners = HashSet<Int>()
    private var knownScreen = host.screenSize()
    /** Разбудить слежение раньше времени (отпустили уголок, переехала рамка). */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var showOriginal = false
    private var lastProvider: String? = null
    private var ended = false
    private val started = System.currentTimeMillis()

    private val frameView = LiveFrameView(context)
    private val framePad = host.dp(8)
    private val frameParams = host.overlayParams(0, 0, touchable = false)

    /** Уголки рамки ловят палец (зона с подушечку пальца); внутри рамки касания идут видео. */
    private val handleSize = host.dp(48)
    private val handles = Array(4) { corner -> View(context).also { it.setOnTouchListener(CornerTouch(corner)) } }
    private val handleParams = Array(4) {
        host.overlayParams(handleSize, handleSize, touchable = true).apply {
            // Каждый уголок — своё окно: два пальца на двух уголках тянут их одновременно.
            flags = flags or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        }
    }

    private val lane = LayoutInflater.from(context).inflate(R.layout.live_lane, null)
    private val rows = lane.findViewById<LinearLayout>(R.id.liveRows)
    private val status = lane.findViewById<TextView>(R.id.liveStatus)
    private val btnPause = lane.findViewById<TextView>(R.id.liveBtnPause)
    private val btnOriginal = lane.findViewById<TextView>(R.id.liveBtnOriginal)
    private val laneParams: WindowManager.LayoutParams

    init {
        lane.background = host.panelBackground()
        val screen = host.screenSize()
        val width = minOf(screen.x - host.dp(24), host.dp(520))
        laneParams = host.overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            x = (screen.x - width) / 2 - host.windowOffset().x
        }
        btnPause.setOnClickListener { setPaused(!paused) }
        btnOriginal.setOnClickListener {
            showOriginal = !showOriginal
            btnOriginal.setTextColor(ContextCompat.getColor(context, if (showOriginal) R.color.accent_light else R.color.text))
            refreshRows()
        }
        lane.findViewById<View>(R.id.liveBtnClose).setOnClickListener { stop() }
    }

    fun start() {
        placeFrame()
        host.addWindow(frameView, frameParams)
        handles.forEachIndexed { i, h -> host.addWindow(h, handleParams[i]) }
        host.addWindow(lane, laneParams)
        status.setText(R.string.live_waiting)
        reposition()
        AppLog.i("Live включён: рамка $frame в ${host.appLabel(packageName)}")
        loop = host.scope.launch { watch() }
    }

    /** Выключить Live: досылаем последнюю фразу в историю, убираем окна. */
    fun stop() {
        if (ended) return
        ended = true
        loop?.cancel()
        host.removeWindow(frameView)
        handles.forEach { host.removeWindow(it) }
        host.removeWindow(lane)
        saveSession()
        host.onLiveEnded()
    }

    /** Кружок навели на субтитры при включённом Live — рамка переезжает туда, сеанс продолжается. */
    fun relocate(found: Rect, newPackage: String) {
        if (ended) return
        val screen = host.screenSize()
        frame = frameFor(found, newPackage, screen)
        val changedApp = newPackage != packageName
        packageName = newPackage
        stats.moved++
        knownScreen = screen
        staticFilter.reset()
        AppLog.i(
            "Live: рамка перенесена" + (if (changedApp) " в ${host.appLabel(newPackage)}" else "") + ": $frame" +
                if (wait != Wait.NONE) " — ожидание закончено" else ""
        )
        stopWaiting()
        refitLane(screen)
        moveFrameWindows()
        wake.trySend(Unit)
    }

    /** Повернули экран: старая рамка не на месте — ждём, пока кружок наведут на субтитры заново. */
    fun onScreenChanged() {
        if (ended) return
        // Настройки экрана меняются не только при повороте (тема, шрифт) — ждём, только если размер стал другим.
        val screen = host.screenSize()
        if (screen == knownScreen) return
        knownScreen = screen
        refitLane(screen)
        if (wait != Wait.AIM) {
            startWaiting(Wait.AIM)
            AppLog.i("Live: экран повернулся — жду, пока кружок наведут на субтитры")
        }
        status.setText(R.string.live_aim_again)
        reposition()
    }

    /** Пальцевый слой лёг поверх всего — поднимаем уголки и ленту обратно наверх, чтобы их можно было коснуться. */
    fun raiseAbove() {
        if (ended) return
        handles.forEachIndexed { i, h ->
            host.removeWindow(h)
            host.addWindow(h, handleParams[i])
        }
        host.removeWindow(lane)
        host.addWindow(lane, laneParams)
        reposition()
    }

    /** Где сейчас лента (координаты экрана) — чтобы окно пальцевого перевода её не закрывало. */
    fun laneBounds(): Rect? {
        if (ended || !lane.isAttachedToWindow) return null
        val offset = host.windowOffset()
        val x = laneParams.x + offset.x
        val y = laneParams.y + offset.y
        return Rect(x, y, x + laneParams.width, y + lane.height.coerceAtLeast(lane.measuredHeight))
    }

    private fun startWaiting(kind: Wait) {
        if (wait == Wait.NONE) waitingSince = SystemClock.uptimeMillis()
        wait = kind
        setFrameVisible(false)
    }

    private fun stopWaiting() {
        if (wait == Wait.NONE) return
        wait = Wait.NONE
        setFrameVisible(true)
        status.text = if (paused) context.getString(R.string.live_paused) else statusText()
    }

    private fun setFrameVisible(visible: Boolean) {
        val state = if (visible) View.VISIBLE else View.INVISIBLE
        frameView.visibility = state
        handles.forEach { it.visibility = state }
    }

    /** Рамка для найденных субтитров: размер, который растянули в этом приложении, или обычный. */
    private fun frameFor(found: Rect, pkg: String, screen: Point): Rect {
        val saved = Prefs.liveFrame(context, pkg, screen.x > screen.y)
            ?: return frameAround(found, screen) { host.dp(it) }
        val box = FrameResize.placeSaved(found.centerX(), found.bottom, saved.first, saved.second, screen.x, screen.y, host.dp(16))
        return Rect(box.left, box.top, box.right, box.bottom)
    }

    private fun refitLane(screen: Point) {
        val width = minOf(screen.x - host.dp(24), host.dp(520))
        laneParams.width = width
        laneParams.x = (screen.x - width) / 2 - host.windowOffset().x
    }

    private fun moveFrameWindows() {
        placeFrame()
        host.updateWindow(frameView, frameParams)
        frameView.invalidate()
        handles.forEachIndexed { i, h -> host.updateWindow(h, handleParams[i]) }
        reposition()
    }

    /** Один уголок рамки: тянем — меняется размер, противоположный уголок стоит на месте. */
    private inner class CornerTouch(private val corner: Int) : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    heldCorners.add(corner)
                    frameView.active = heldCorners.toSet()
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_MOVE -> {
                    val screen = host.screenSize()
                    val box = FrameResize.drag(
                        FrameResize.Box(frame.left, frame.top, frame.right, frame.bottom),
                        corner, event.rawX.toInt(), event.rawY.toInt(), screen.x, screen.y,
                        host.dp(MIN_FRAME_W_DP), host.dp(MIN_FRAME_H_DP),
                    )
                    frame = Rect(box.left, box.top, box.right, box.bottom)
                    moveFrameWindows()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    heldCorners.remove(corner)
                    frameView.active = heldCorners.toSet()
                    if (heldCorners.isEmpty()) onResized()
                }
            }
            return true
        }
    }

    private fun onResized() {
        val screen = host.screenSize()
        Prefs.setLiveFrame(
            context, packageName, screen.x > screen.y,
            frame.width().toFloat() / screen.x, frame.height().toFloat() / screen.y,
        )
        stats.resized++
        // Рамка теперь другая: прошлые положения надписей больше не совпадают.
        staticFilter.reset()
        AppLog.i("Live: рамку растянули: $frame (запомнил для ${host.appLabel(packageName)})")
        wake.trySend(Unit)
    }

    // ---------- Слежение ----------

    private suspend fun watch() {
        while (host.scope.isActive && !ended) {
            val began = SystemClock.uptimeMillis()
            if (!paused && wait != Wait.AIM) {
                if (host.topPackage() != packageName) {
                    // Ушли из приложения с видео — не читаем чужой экран и не тратим запросы.
                    if (wait == Wait.NONE) {
                        startWaiting(Wait.OTHER_APP)
                        status.setText(R.string.live_other_app)
                        AppLog.i("Live: другое приложение на экране — жду (вернитесь в ${host.appLabel(packageName)} или наведите кружок на субтитры)")
                    }
                } else {
                    if (wait == Wait.OTHER_APP) {
                        stopWaiting()
                        AppLog.i("Live: снова в видео — продолжаю")
                    }
                    // Пока тянут уголок или пальцем ведут линию — рамку не читаем.
                    if (heldCorners.isEmpty() && !host.captureBusy()) readFrame()
                }
            }
            // Долгое ожидание — выключаемся сами, сеанс сохраняется.
            if (wait != Wait.NONE && SystemClock.uptimeMillis() - waitingSince > MAX_WAIT_MS) {
                AppLog.i("Live: ждал ${MAX_WAIT_MS / 60000} мин — выключаюсь")
                stop()
                return
            }
            // Предложение так и не кончилось, а новых субтитров давно нет (пауза в речи) — переводим как есть.
            if (joiner.hasOpen && SystemClock.uptimeMillis() - lastChunkAt > OPEN_TIMEOUT_MS) {
                joiner.flush()?.let {
                    stats.timeouts++
                    AppLog.i("Live: продолжения нет ${OPEN_TIMEOUT_MS / 1000.0} с — перевожу как есть")
                    complete(it)
                }
            }
            val spent = SystemClock.uptimeMillis() - began
            withTimeoutOrNull((PERIOD_MS - spent).coerceAtLeast(MIN_PAUSE_MS)) { wake.receive() }
        }
    }

    private suspend fun readFrame() {
        val seen = ImageTextFinder.Stats()
        val text = try {
            when (val capture = host.captureFor(frame)) {
                is ImageTextFinder.Capture.Failed -> {
                    stats.captureErrors++
                    // Одну и ту же причину пишем один раз, пока снимки снова не пойдут.
                    if (captureProblem != capture.reason) AppLog.i("Live: снимок не получился — ${capture.reason}")
                    captureProblem = capture.reason
                    return
                }
                is ImageTextFinder.Capture.Ok -> try {
                    if (captureProblem != null) AppLog.i("Live: снимки снова получаются")
                    captureProblem = null
                    val blocks = withContext(Dispatchers.Default) {
                        ImageTextFinder.recognize(capture.bitmap, frame, quiet = true, stats = seen)
                    }
                    subtitlesOnly(blocks.sortedBy { it.bounds.top })
                } finally {
                    capture.bitmap.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Live: ошибка распознавания", e)
            return
        }
        logFrameState(text, seen)
        // Русские субтитры переводить не нужно.
        val russian = App.engine.looksCyrillic(text)
        if (russian) stats.russian++
        val english = if (russian) "" else text
        reader.feed(english)?.let { onChunk(it) }
    }

    /**
     * Журнал: субтитры пропали и вернулись; в рамке что-то есть, но прочитать нельзя.
     * Так видно, где виновато видео (субтитры сами погасли), а где — распознавание.
     */
    private fun logFrameState(text: String, seen: ImageTextFinder.Stats) {
        val now = SystemClock.uptimeMillis()
        if (text.isBlank()) {
            if (emptySince == 0L) emptySince = now
            if (!emptyLogged && now - emptySince >= EMPTY_LOG_MS) {
                emptyLogged = true
                AppLog.i("Live: в рамке нет текста ${(now - emptySince) / 1000} с — субтитров на экране нет")
            }
            // Строки нашлись, но все отброшены (мелкие, кривые, неуверенные) — это «плохо читается».
            if (seen.lines > 0 && !unreadableLogged) {
                unreadableLogged = true
                stats.unreadable++
                AppLog.i("Live: в рамке что-то есть (строк ${seen.lines}), но прочитать не удалось — все отброшены")
            }
        } else {
            if (emptyLogged) AppLog.i("Live: текст снова появился (не было ${(now - emptySince) / 1000} с)")
            emptySince = 0L
            emptyLogged = false
            unreadableLogged = false
        }
    }

    /** Убираем неподвижные надписи (обложка, логотип) — остаются субтитры. */
    private fun subtitlesOnly(blocks: List<ImageTextFinder.Block>): String {
        val result = staticFilter.feed(
            blocks.map { StaticFilter.Item(it.text, it.bounds.centerX(), it.bounds.centerY()) },
            SystemClock.uptimeMillis(),
        )
        for ((text, age) in result.newlyStatic) {
            stats.staticSkipped++
            AppLog.i("Live: надпись ${quote(text)} стоит на месте ${age / 1000} с — не субтитр, пропускаю")
        }
        return PageLayout.joinRows(result.kept.map { blocks[it].text })
    }

    private fun quote(text: String) = "«" + (if (text.length > 90) text.take(90) + "…" else text) + "»"

    // ---------- Перевод ----------

    /**
     * Новый кусок субтитров. Готовые предложения уходят на перевод целиком; незаконченное
     * показывается черновиком телефона и дополняется, пока не встретится конец предложения.
     */
    private fun onChunk(text: String) {
        lastChunkAt = SystemClock.uptimeMillis()
        stats.chunks++
        val update = joiner.add(text)
        AppLog.i(
            "Live: кусок ${quote(text)}" + when {
                update.open != null && update.complete.isEmpty() -> " → предложение не кончилось, жду продолжения"
                update.open != null -> " → готово ${update.complete.size}, хвост ждёт продолжения"
                else -> ""
            }
        )
        update.complete.forEach { complete(it) }
        update.open?.let { showOpen(it) }
        refreshRows()
    }

    /** Незаконченное предложение: в нейросеть пока не отправляем, только черновик телефона. */
    private fun showOpen(text: String) {
        val phrase = openPhrase ?: Phrase(nextId++, text).also {
            it.open = true
            phrases.add(it)
            openPhrase = it
        }
        phrase.original = text
        phrase.chunks++
        draftOnPhone(phrase)
    }

    /** Предложение кончилось: дорожка 1 — телефон, дорожка 2 — нейросеть (тот же английский текст). */
    private fun complete(text: String) {
        val phrase = openPhrase?.also { openPhrase = null } ?: Phrase(nextId++, text).also { phrases.add(it) }
        phrase.original = text
        phrase.open = false
        val parts = phrase.chunks + 1
        if (parts > 1) stats.joined++
        AppLog.i("Live: предложение №${phrase.id}" + (if (parts > 1) " (склеено из $parts кусков)" else "") + ": ${quote(text)}")
        draftOnPhone(phrase)
        // Если запрос уже идёт, предложение ждёт следующего.
        pending.addLast(phrase)
        if (!cloudBusy) sendToCloud()
        refreshRows()
    }

    private fun draftOnPhone(phrase: Phrase) {
        val text = phrase.original
        host.scope.launch {
            val draft = runCatching { App.engine.translateEnglish(text) }
                .onFailure { if (it !is CancellationException) AppLog.e("Live: телефон не перевёл", it) }
                .getOrNull()
            // Пока переводили, к предложению могли добавиться слова — тогда этот черновик устарел.
            if (draft != null && phrase.final == null && phrase.original == text) {
                phrase.draft = draft
                refreshRows()
            }
        }
    }

    private fun sendToCloud() {
        if (pending.isEmpty() || ended) return
        val batch = buildList { while (pending.isNotEmpty() && size < BATCH) add(pending.removeFirst()) }
        val firstIndex = phrases.indexOf(batch.first())
        val previous = phrases.subList((firstIndex - CONTEXT_LINES).coerceAtLeast(0), firstIndex.coerceAtLeast(0))
            .filterNot { it.open }
            .map { it.original }
        cloudBusy = true
        host.scope.launch {
            val started = SystemClock.uptimeMillis()
            val result = try {
                App.engine.translateLive(batch.map { it.original }, previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Live: ошибка нейросети", e)
                null
            }
            if (result != null) {
                val (texts, label) = result
                lastProvider = label
                batch.forEachIndexed { i, phrase ->
                    texts.getOrNull(i)?.let {
                        phrase.final = it
                        phrase.by = label
                    }
                }
                val ms = SystemClock.uptimeMillis() - started
                stats.cloudAnswers++
                stats.cloudMs += ms
                AppLog.i("Live: $label перевёл №${batch.joinToString(", ") { it.id.toString() }} за $ms мс")
            }
            batch.filter { it.final == null }.forEach {
                it.by = context.getString(R.string.live_phone)
                stats.phoneOnly++
                AppLog.i("Live: №${it.id} остался переводом телефона")
            }
            if (!paused && !waitingOtherApp) status.text = statusText()
            refreshRows()
            cloudBusy = false
            sendToCloud()
        }
    }

    // ---------- Лента ----------

    private fun refreshRows() {
        if (ended) return
        val visible = phrases.takeLast(VISIBLE_ROWS)
        rows.removeAllViews()
        visible.forEachIndexed { index, phrase ->
            val age = visible.size - 1 - index
            rows.addView(rowFor(phrase).apply { alpha = ROW_ALPHA[age.coerceAtMost(ROW_ALPHA.size - 1)] })
        }
        reposition()
    }

    private fun rowFor(phrase: Phrase): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, host.dp(4), 0, host.dp(4))
        }
        val translation = TextView(context).apply {
            typeface = context.resources.getFont(R.font.inter)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setLineSpacing(0f, 1.1f)
            when {
                phrase.final != null -> {
                    text = phrase.final
                    setTextColor(ContextCompat.getColor(context, R.color.text))
                }
                phrase.draft != null -> {
                    // Черновик телефона — бледный и наклонный, пока не придёт нейросеть;
                    // «…» в конце — предложение ещё продолжается.
                    text = if (phrase.open) phrase.draft + " …" else phrase.draft
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                    setTypeface(typeface, Typeface.ITALIC)
                }
                else -> {
                    text = "…"
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                }
            }
        }
        box.addView(translation)
        if (showOriginal) {
            box.addView(TextView(context).apply {
                typeface = context.resources.getFont(R.font.inter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                text = phrase.original
            })
        }
        return box
    }

    private fun statusText(): String = lastProvider ?: context.getString(R.string.live_waiting)

    private fun setPaused(value: Boolean) {
        paused = value
        btnPause.setText(if (paused) R.string.live_resume else R.string.live_pause)
        frameView.dimmed = paused
        status.text = if (paused) context.getString(R.string.live_paused) else statusText()
        AppLog.i(if (paused) "Live: пауза" else "Live: продолжаю")
    }

    /** Лента под рамкой, если там хватает места, иначе над ней — чтобы не закрывать субтитры. */
    private fun reposition() {
        if (ended) return
        lane.measure(
            View.MeasureSpec.makeMeasureSpec(laneParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = lane.measuredHeight
        val screenHeight = host.screenSize().y
        val margin = host.dp(10)
        val below = frame.bottom + framePad + margin
        val y = when {
            below + height <= screenHeight - host.dp(16) -> below
            frame.top - framePad - margin - height >= host.dp(24) -> frame.top - framePad - margin - height
            else -> screenHeight - host.dp(16) - height
        }
        laneParams.y = y.coerceAtLeast(0) - host.windowOffset().y
        host.updateWindow(lane, laneParams)
    }

    private fun placeFrame() {
        val offset = host.windowOffset()
        frameParams.width = frame.width() + framePad * 2
        frameParams.height = frame.height() + framePad * 2
        frameParams.x = frame.left - framePad - offset.x
        frameParams.y = frame.top - framePad - offset.y
        val corners = listOf(
            frame.left to frame.top, frame.right to frame.top,
            frame.right to frame.bottom, frame.left to frame.bottom,
        )
        corners.forEachIndexed { i, (cx, cy) ->
            handleParams[i].x = cx - handleSize / 2 - offset.x
            handleParams[i].y = cy - handleSize / 2 - offset.y
        }
    }

    // ---------- История ----------

    private fun saveSession() {
        reader.flush()?.let { joiner.add(it) }
        joiner.flush()?.let { rest ->
            val phrase = openPhrase ?: Phrase(nextId++, rest).also { phrases.add(it) }
            phrase.original = rest
        }
        val done = phrases.filter { it.original.isNotBlank() }
        if (done.isEmpty()) {
            AppLog.i("Live выключен: фраз не было")
            logSummary(0, ((System.currentTimeMillis() - started) / 60000).coerceAtLeast(1))
            return
        }
        val minutes = ((System.currentTimeMillis() - started) / 60000).coerceAtLeast(1)
        // Одна фраза — одна строка: так история раскладывает сеанс обратно по фразам.
        fun line(t: String) = t.replace('\n', ' ').trim()
        val original = done.joinToString("\n") { line(it.original) }
        val translated = done.joinToString("\n") { line(it.final ?: it.draft ?: "…") }
        val by = done.mapNotNull { it.by }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
        HistoryStore.add(
            context,
            original,
            translated,
            host.appLabel(packageName),
            "Live · ${done.size} фраз · $minutes мин" + if (by.isNotEmpty()) " · $by" else "",
        )
        AppLog.i("Live выключен: сохранено ${done.size} фраз")
        logSummary(done.size, minutes)
        Toast.makeText(context, context.getString(R.string.live_saved, done.size), Toast.LENGTH_LONG).show()
    }

    /** Итог сеанса в журнал: по нему видно, как Live справился в целом. */
    private fun logSummary(sentences: Int, minutes: Long) {
        val average = if (stats.cloudAnswers > 0) stats.cloudMs / stats.cloudAnswers else 0
        AppLog.i(
            "Live, итог за $minutes мин: предложений $sentences (склеено из нескольких кусков: ${stats.joined}), " +
                "кусков субтитров ${stats.chunks}, без продолжения по паузе ${stats.timeouts}; " +
                "ответов нейросети ${stats.cloudAnswers} (в среднем $average мс), только телефон ${stats.phoneOnly}; " +
                "русских кадров ${stats.russian}, нечитаемых ${stats.unreadable}, неудачных снимков ${stats.captureErrors}; " +
                "пропущено надписей ${stats.staticSkipped}, рамку переносили ${stats.moved}, растягивали ${stats.resized}"
        )
    }

    companion object {
        /** Через сколько пустой рамки писать в журнал «субтитров нет». */
        private const val EMPTY_LOG_MS = 5000L
        /** Сколько ждать (другое приложение, поворот экрана), прежде чем выключиться самим. */
        private const val MAX_WAIT_MS = 10 * 60 * 1000L
        /** Меньше рамку не сжать: примерно одна короткая строка субтитров. */
        private const val MIN_FRAME_W_DP = 120
        private const val MIN_FRAME_H_DP = 36
        /** Как часто читать рамку. */
        private const val PERIOD_MS = 700L
        private const val MIN_PAUSE_MS = 150L
        /** Сколько фраз максимум в одном запросе, если они идут быстрее, чем отвечает нейросеть. */
        private const val BATCH = 4
        /** Сколько прошлых фраз отдаём нейросети для связности. */
        private const val CONTEXT_LINES = 3
        private const val VISIBLE_ROWS = 4
        /** Сколько ждать продолжения незаконченного предложения, если новых субтитров нет. */
        private const val OPEN_TIMEOUT_MS = 3500L
        /** Новая фраза яркая, старые бледнеют. */
        private val ROW_ALPHA = floatArrayOf(1f, 0.72f, 0.5f, 0.35f)

        /**
         * Рамка Live из места найденного текста: шире (субтитры бывают разной длины)
         * и выше (вторая строка появляется сверху), но не за краем экрана.
         */
        fun frameAround(found: Rect, screen: Point, dp: (Int) -> Int): Rect {
            val width = maxOf(found.width() + dp(48), (screen.x * 0.84f).toInt()).coerceAtMost(screen.x - dp(8))
            val left = (found.centerX() - width / 2).coerceIn(dp(4), screen.x - dp(4) - width)
            val top = (found.top - maxOf(dp(40), found.height() / 2)).coerceAtLeast(0)
            val bottom = (found.bottom + dp(16)).coerceAtMost(screen.y)
            return Rect(left, top, left + width, bottom)
        }
    }
}

/** Закреплённая рамка Live: оранжевые уголки и метка «LIVE». Касания проходят насквозь (уголки ловят свои окна). */
class LiveFrameView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.accent)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_light)
        textSize = 10f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    var dimmed = false
        set(value) {
            field = value
            alpha = if (value) 0.4f else 1f
        }

    /** Уголки, за которые сейчас тянут: ярче и толще. */
    var active: Set<Int> = emptySet()
        set(value) {
            field = value
            invalidate()
        }
    private val bright = Paint(corner).apply {
        color = ContextCompat.getColor(context, R.color.accent_light)
        strokeWidth = 5f * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent_light) }

    override fun onDraw(canvas: Canvas) {
        val inset = corner.strokeWidth
        val w = width - inset
        val h = height - inset
        val arm = minOf(22f * density, minOf(w, h) / 3f)
        // 0 — левый верхний, 1 — правый верхний, 2 — правый нижний, 3 — левый нижний.
        val points = listOf(inset to inset, w to inset, w to h, inset to h)
        points.forEachIndexed { i, (x, y) ->
            val paint = if (i in active) bright else corner
            val dx = if (i == 0 || i == 3) arm else -arm
            val dy = if (i < 2) arm else -arm
            canvas.drawLine(x, y, x + dx, y, paint)
            canvas.drawLine(x, y, x, y + dy, paint)
            // Точка в уголке — подсказка, что за него можно потянуть.
            canvas.drawCircle(x, y, (if (i in active) 4.5f else 3f) * density, dot)
        }
        canvas.drawText("LIVE", inset + arm + 6f * density, inset + label.textSize * 0.4f, label)
    }
}
