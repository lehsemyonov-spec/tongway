package com.tongway.translator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Облачный сервис с нейросетями. Ключ у каждого пользователя свой. */
enum class CloudProvider(
    val id: String,
    val title: String,
    val base: String,
    val keysPage: String,
    val keyPrefix: String,
    /** Нужен ли ключ, чтобы получить список моделей. */
    val modelsNeedKey: Boolean,
    /** Лимиты считаются на каждую модель отдельно (Groq) — при лимите помогает другая модель. */
    val perModelLimits: Boolean,
    /** Семейства моделей в порядке предпочтения для перевода. */
    val preferred: List<String>,
) {
    GROQ(
        "groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "gsk_",
        modelsNeedKey = true, perModelLimits = true,
        preferred = listOf("llama-3.3-70b", "llama-4-maverick", "kimi-k2", "gpt-oss-120b", "qwen3", "llama-4-scout", "llama"),
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "sk-or-",
        modelsNeedKey = false, perModelLimits = false,
        preferred = listOf("gemini", "deepseek-chat", "deepseek-v3", "llama-4", "llama-3.3-70b", "qwen3", "qwen", "mistral", "gemma"),
    );

    companion object {
        /** Порядок цепочки: у Groq лимит больше, поэтому он первый; если не вышло — OpenRouter. */
        val CHAIN = listOf(GROQ, OPENROUTER)
    }
}

/**
 * Перевод нейросетью через сервисы, совместимые с OpenAI (Groq, OpenRouter).
 * На OpenRouter используются только бесплатные модели (id кончаются на «:free»).
 */
object CloudClient {

    data class Model(val id: String, val name: String, val contextLength: Int)

    /**
     * Понятная ошибка для карточки; после неё пробуем следующий сервис или телефон.
     * tryOtherModel — модель пропала, перегружена или упёрлась в свой лимит: стоит попробовать другую.
     */
    class CloudException(val reason: String, val tryOtherModel: Boolean = false) : Exception(reason)

    private const val SYSTEM_ONE =
        "You are a professional translator. Translate the user's text from English into Russian. " +
            "Keep the meaning, tone, terminology, numbers, names and line breaks. " +
            "If the text is already in Russian, return it unchanged. " +
            "Reply with the translation only: no comments, no quotes, no explanations."

    private const val SYSTEM_MANY =
        "You are a professional translator. The user sends a JSON object whose keys are fragment numbers " +
            "and whose values are English text fragments from one screen or page. Translate every fragment " +
            "into Russian, using the other fragments as context. Keep the meaning, tone, terminology, numbers " +
            "and names. Reply with a JSON object with exactly the same keys, each value being the Russian " +
            "translation of that fragment. Never merge, split or skip fragments. Reply with the JSON only."

    private const val SYSTEM_DOCUMENT =
        "You are a professional translator. Translate the user's English document into Russian as one " +
            "coherent text. Keep the meaning, tone, terminology, numbers and names. Paragraphs are separated " +
            "by blank lines: keep exactly the same paragraphs, separated by blank lines. " +
            "Reply with the translation only: no comments, no headings of your own."

    /** Добавка к заданию, если текст распознан с фото. */
    private const val OCR_NOTE =
        " The text was recognized from a photo (OCR), so it may contain recognition errors: letters " +
            "confused with digits or symbols (e.g. 'S00m' for '\$500m', '239%' for '23%', 'L' for '£', 'rn' for 'm'), " +
            "broken words or stray characters. Silently fix obvious recognition errors using the context, " +
            "but do not add or remove content."

    /** Модели, подходящие для перевода текста. */
    suspend fun models(provider: CloudProvider, key: String?): List<Model> = withContext(Dispatchers.IO) {
        val body = request(provider, "GET", "${provider.base}/models", if (provider.modelsNeedKey) key else null, null)
        val data = JSONObject(body).getJSONArray("data")
        val out = mutableListOf<Model>()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val id = m.optString("id")
            if (id.isEmpty() || SKIP.any { id.contains(it, ignoreCase = true) }) continue
            if (!m.optBoolean("active", true)) continue
            if (provider == CloudProvider.OPENROUTER) {
                val pricing = m.optJSONObject("pricing")
                val free = id.endsWith(":free") ||
                    (pricing != null && pricing.optString("prompt") == "0" && pricing.optString("completion") == "0")
                if (!free) continue
                val output = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                if (output != null && !output.toString().contains("text")) continue
            }
            val context = m.optInt("context_length", m.optInt("context_window", 0))
            out.add(Model(id, m.optString("name", id), context))
        }
        out
    }

    /** Автовыбор: сначала известные сильные семейства, потом модель с самым большим контекстом. */
    fun pickDefault(provider: CloudProvider, models: List<Model>): Model? {
        for (family in provider.preferred) {
            models.filter { it.id.contains(family, ignoreCase = true) }
                .maxByOrNull { it.contextLength }
                ?.let { return it }
        }
        return models.maxByOrNull { it.contextLength }
    }

    suspend fun translateOne(provider: CloudProvider, key: String, model: String, text: String, ocr: Boolean = false): String =
        withContext(Dispatchers.IO) {
            checkComplete(text, clean(chat(provider, key, model, SYSTEM_ONE + if (ocr) OCR_NOTE else "", text)))
        }

    /** Связный документ (абзацы через пустую строку) одним запросом. */
    suspend fun translateDocument(provider: CloudProvider, key: String, model: String, text: String, ocr: Boolean): String =
        withContext(Dispatchers.IO) {
            val reply = checkComplete(text, clean(chat(provider, key, model, SYSTEM_DOCUMENT + if (ocr) OCR_NOTE else "", text)))
            AppLog.i("${provider.title} ($model): весь текст ${text.length} → ${reply.length} символов")
            reply
        }

    /**
     * Перевод на русский обычно не короче оригинала. Если ответ намного короче — нейросеть
     * перевела только кусок (у пользователя так было: вместо страницы — «В ТЕКСТЕ 2. Сателлитные»).
     */
    private fun checkComplete(original: String, translated: String): String {
        if (original.length > 120 && translated.length < original.length * 0.45) {
            AppLog.i("Перевод подозрительно короткий: ${original.length} → ${translated.length} символов")
            throw CloudException("нейросеть перевела не весь текст", tryOtherModel = true)
        }
        return translated
    }

    /**
     * Несколько кусков одним запросом — так тратится один запрос из дневного лимита, а не десятки.
     * Куски пронумерованы; возвращаются те, что нейросеть вернула (пропущенные — null).
     */
    suspend fun translateMany(
        provider: CloudProvider,
        key: String,
        model: String,
        texts: List<String>,
        ocr: Boolean = false,
    ): List<String?> = withContext(Dispatchers.IO) {
        val input = JSONObject()
        texts.forEachIndexed { i, t -> input.put((i + 1).toString(), t) }
        val reply = clean(chat(provider, key, model, SYSTEM_MANY + if (ocr) OCR_NOTE else "", input.toString()))
        parseNumbered(reply, texts.size) ?: throw CloudException("нейросеть ответила не в том виде")
    }

    /** Разбирает ответ: объект {"1": "...", ...} или, на всякий случай, массив. */
    private fun parseNumbered(reply: String, count: Int): List<String?>? {
        val objStart = reply.indexOf('{')
        val objEnd = reply.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            runCatching { JSONObject(reply.substring(objStart, objEnd + 1)) }.getOrNull()?.let { obj ->
                return List(count) { i -> obj.optString((i + 1).toString()).takeIf { it.isNotBlank() } }
            }
        }
        val arrStart = reply.indexOf('[')
        val arrEnd = reply.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            runCatching { JSONArray(reply.substring(arrStart, arrEnd + 1)) }.getOrNull()?.let { arr ->
                if (arr.length() == count) return List(count) { i -> arr.optString(i).takeIf { it.isNotBlank() } }
            }
        }
        return null
    }

    private fun chat(provider: CloudProvider, key: String, model: String, system: String, user: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            // С запасом: русский текст занимает в 2–3 раза больше «токенов», чем английский.
            .put("max_tokens", (user.length * 2).coerceIn(1024, 8000))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val body = request(provider, "POST", "${provider.base}/chat/completions", key, payload.toString())
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw CloudException(it.optString("message", "ошибка сервиса")) }
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optJSONObject("message")?.optString("content")
        if (content.isNullOrBlank()) throw CloudException("пустой ответ нейросети", tryOtherModel = true)
        if (choice?.optString("finish_reason") == "length") {
            AppLog.i("${provider.title} ($model): ответ обрезан по длине")
            throw CloudException("ответ нейросети обрезан", tryOtherModel = true)
        }
        return content
    }

    private fun request(provider: CloudProvider, method: String, url: String, key: String?, payload: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = if (payload == null) 15_000 else 40_000
            setRequestProperty("Accept", "application/json")
            if (provider == CloudProvider.OPENROUTER) {
                setRequestProperty("HTTP-Referer", "https://github.com/lehsemyonov-spec/tongway")
                setRequestProperty("X-Title", "Tongway")
            }
            if (key != null) setRequestProperty("Authorization", "Bearer $key")
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (payload != null) connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw httpError(provider, code, body)
            return body
        } catch (e: CloudException) {
            throw e
        } catch (e: UnknownHostException) {
            throw CloudException("нет интернета")
        } catch (e: SocketTimeoutException) {
            throw CloudException("сервис долго не отвечает")
        } catch (e: IOException) {
            throw CloudException("ошибка сети: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun httpError(provider: CloudProvider, code: Int, body: String): CloudException {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        AppLog.i("${provider.title} ответил $code: ${message ?: body.take(200)}")
        return when (code) {
            401 -> CloudException("неверный ключ")
            402 -> CloudException("для этой модели нужен баланс на счёте", tryOtherModel = true)
            403 -> CloudException("доступ запрещён — возможно, нужен VPN (${message ?: "проверьте ключ"})")
            404 -> CloudException("модель недоступна", tryOtherModel = true)
            413 -> CloudException("слишком длинный текст для этой модели", tryOtherModel = true)
            429 -> rateLimit(provider, message.orEmpty())
            400 -> CloudException(message ?: "неверный запрос", tryOtherModel = message?.contains("model", true) == true)
            in 500..599 -> CloudException("модель сейчас перегружена", tryOtherModel = true)
            else -> CloudException("ошибка $code${message?.let { ": $it" } ?: ""}")
        }
    }

    /**
     * 429 бывает трёх видов: исчерпан дневной лимит, слишком частые запросы или модель
     * перегружена у поставщика. У Groq лимиты на каждую модель свои, поэтому там
     * в любом случае помогает другая модель.
     */
    private fun rateLimit(provider: CloudProvider, message: String): CloudException {
        val m = message.lowercase()
        val other = provider.perModelLimits
        return when {
            "per day" in m || "per-day" in m || "daily" in m || "(rpd)" in m || "(tpd)" in m ->
                CloudException("дневной лимит бесплатных запросов исчерпан, сбросится завтра", tryOtherModel = other)
            "per min" in m || "per-min" in m || "(rpm)" in m || "(tpm)" in m ->
                CloudException("слишком много запросов подряд, подождите минуту", tryOtherModel = other)
            else -> CloudException("модель перегружена, слишком много желающих", tryOtherModel = true)
        }
    }

    /** Убираем «размышления» рассуждающих моделей и обрамление кода. */
    private fun clean(text: String): String {
        var out = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (out.startsWith("```")) out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return out
    }

    private val SKIP = listOf(
        "coder", "math", "guard", "embed", "vision", "-vl", "audio", "image", "whisper", "tts",
        "compound", "playai", "orpheus", "distil",
    )
}
