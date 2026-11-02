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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tongway.translator.App
import com.tongway.translator.AppLog
import com.tongway.translator.HistoryStore
import com.tongway.translator.ImageTextFinder
import com.tongway.translator.LiveText
import com.tongway.translator.PageLayout
import com.tongway.translator.R
import com.tongway.translator.ScreenTextFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val packageName: String,
) {
    private class Phrase(val id: Int, val original: String) {
        var draft: String? = null
        var final: String? = null
        var by: String? = null
        var row: View? = null
    }

    private val context = host.context
    private val phrases = mutableListOf<Phrase>()
    private val pending = ArrayDeque<Phrase>()
    private val reader = LiveText()
    private var nextId = 1
    private var loop: Job? = null
    private var cloudBusy = false
    private var paused = false
    private var waitingOtherApp = false
    private var showOriginal = false
    private var lastProvider: String? = null
    private var ended = false
    private val started = System.currentTimeMillis()

    private val frameView = LiveFrameView(context)
    private val framePad = host.dp(8)
    private val frameParams = host.overlayParams(0, 0, touchable = false)

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
        host.removeWindow(lane)
        saveSession()
        host.onLiveEnded()
    }

    // ---------- Слежение ----------

    private suspend fun watch() {
        while (host.scope.isActive && !ended) {
            val began = SystemClock.uptimeMillis()
            if (!paused) {
                if (host.topPackage() != packageName) {
                    // Ушли из приложения с видео — не читаем чужой экран и не тратим запросы.
                    if (!waitingOtherApp) {
                        waitingOtherApp = true
                        frameView.visibility = View.INVISIBLE
                        status.setText(R.string.live_other_app)
                        AppLog.i("Live: другое приложение на экране — жду")
                    }
                } else {
                    if (waitingOtherApp) {
                        waitingOtherApp = false
                        frameView.visibility = View.VISIBLE
                        status.text = statusText()
                        AppLog.i("Live: снова в видео — продолжаю")
                    }
                    readFrame()
                }
            }
            val spent = SystemClock.uptimeMillis() - began
            delay((PERIOD_MS - spent).coerceAtLeast(MIN_PAUSE_MS))
        }
    }

    private suspend fun readFrame() {
        val text = try {
            when (val capture = host.captureFor(frame)) {
                is ImageTextFinder.Capture.Failed -> {
                    AppLog.i("Live: ${capture.reason}")
                    return
                }
                is ImageTextFinder.Capture.Ok -> try {
                    val blocks = withContext(Dispatchers.Default) {
                        ImageTextFinder.recognize(capture.bitmap, frame, quiet = true)
                    }
                    PageLayout.joinRows(blocks.sortedBy { it.bounds.top }.map { it.text })
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
        // Русские субтитры переводить не нужно.
        val english = if (App.engine.looksCyrillic(text)) "" else text
        reader.feed(english)?.let { addPhrase(it) }
    }

    // ---------- Перевод ----------

    private fun addPhrase(text: String) {
        val phrase = Phrase(nextId++, text)
        phrases.add(phrase)
        AppLog.i("Live: фраза №${phrase.id}: ${text.length} симв.")
        refreshRows()
        // Дорожка 1: телефон — мгновенный черновик.
        host.scope.launch {
            val draft = runCatching { App.engine.translateEnglish(text) }
                .onFailure { if (it !is CancellationException) AppLog.e("Live: телефон не перевёл", it) }
                .getOrNull()
            if (draft != null && phrase.final == null) {
                phrase.draft = draft
                refreshRows()
            }
        }
        // Дорожка 2: нейросеть — тот же английский текст; если запрос уже идёт, фраза ждёт следующего.
        pending.addLast(phrase)
        if (!cloudBusy) sendToCloud()
    }

    private fun sendToCloud() {
        if (pending.isEmpty() || ended) return
        val batch = buildList { while (pending.isNotEmpty() && size < BATCH) add(pending.removeFirst()) }
        val firstIndex = phrases.indexOf(batch.first())
        val previous = phrases.subList((firstIndex - CONTEXT_LINES).coerceAtLeast(0), firstIndex.coerceAtLeast(0))
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
                AppLog.i("Live: $label перевёл ${batch.size} фраз(ы) за ${SystemClock.uptimeMillis() - started} мс")
            }
            batch.filter { it.final == null }.forEach { it.by = context.getString(R.string.live_phone) }
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
                    // Черновик телефона — бледный и наклонный, пока не придёт нейросеть.
                    text = phrase.draft
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
    }

    // ---------- История ----------

    private fun saveSession() {
        reader.flush()?.let { phrases.add(Phrase(nextId++, it)) }
        val done = phrases.filter { it.original.isNotBlank() }
        if (done.isEmpty()) {
            AppLog.i("Live выключен: фраз не было")
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
        Toast.makeText(context, context.getString(R.string.live_saved, done.size), Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Как часто читать рамку. */
        private const val PERIOD_MS = 700L
        private const val MIN_PAUSE_MS = 150L
        /** Сколько фраз максимум в одном запросе, если они идут быстрее, чем отвечает нейросеть. */
        private const val BATCH = 4
        /** Сколько прошлых фраз отдаём нейросети для связности. */
        private const val CONTEXT_LINES = 3
        private const val VISIBLE_ROWS = 4
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

/** Закреплённая рамка Live: оранжевые уголки и метка «LIVE». Касания проходят насквозь. */
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

    override fun onDraw(canvas: Canvas) {
        val inset = corner.strokeWidth
        val w = width - inset
        val h = height - inset
        val arm = minOf(22f * density, minOf(w, h) / 3f)
        canvas.drawLine(inset, inset, inset + arm, inset, corner)
        canvas.drawLine(inset, inset, inset, inset + arm, corner)
        canvas.drawLine(w, inset, w - arm, inset, corner)
        canvas.drawLine(w, inset, w, inset + arm, corner)
        canvas.drawLine(inset, h, inset + arm, h, corner)
        canvas.drawLine(inset, h, inset, h - arm, corner)
        canvas.drawLine(w, h, w - arm, h, corner)
        canvas.drawLine(w, h, w, h - arm, corner)
        canvas.drawText("LIVE", inset + arm + 6f * density, inset + label.textSize * 0.4f, label)
    }
}
