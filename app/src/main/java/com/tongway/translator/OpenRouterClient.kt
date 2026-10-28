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

/**
 * Перевод нейросетью через OpenRouter (https://openrouter.ai). Ключ у каждого пользователя свой,
 * используются только бесплатные модели (их id кончаются на «:free»).
 */
object OpenRouterClient {

    data class Model(val id: String, val name: String, val contextLength: Int)

    /** Понятная ошибка для карточки; после неё переводим на телефоне. */
    class CloudException(val reason: String, val modelMissing: Boolean = false) : Exception(reason)

    private const val BASE = "https://openrouter.ai/api/v1"
    const val KEYS_PAGE = "https://openrouter.ai/keys"

    private const val SYSTEM_ONE =
        "You are a professional translator. Translate the user's text from English into Russian. " +
            "Keep the meaning, tone, terminology, numbers, names and line breaks. " +
            "If the text is already in Russian, return it unchanged. " +
            "Reply with the translation only: no comments, no quotes, no explanations."

    private const val SYSTEM_MANY =
        "You are a professional translator. The user sends a JSON array of English text fragments " +
            "from one screen or page. Translate every fragment into Russian, using the other fragments " +
            "as context. Keep the meaning, tone, terminology, numbers and names. " +
            "Reply with a JSON array of strings of exactly the same length and order, and nothing else."

    /** Список бесплатных текстовых моделей. Ключ для этого не нужен. */
    suspend fun freeModels(): List<Model> = withContext(Dispatchers.IO) {
        val body = request("GET", "$BASE/models", key = null, payload = null)
        val data = JSONObject(body).getJSONArray("data")
        val out = mutableListOf<Model>()
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val id = m.optString("id")
            val pricing = m.optJSONObject("pricing")
            val free = id.endsWith(":free") ||
                (pricing != null && pricing.optString("prompt") == "0" && pricing.optString("completion") == "0")
            if (!free || id.isEmpty()) continue
            val output = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            if (output != null && !output.toString().contains("text")) continue
            if (SKIP.any { id.contains(it, ignoreCase = true) }) continue
            out.add(Model(id, m.optString("name", id), m.optInt("context_length", 0)))
        }
        out
    }

    /** Автовыбор: сначала известные сильные семейства, потом модель с самым большим контекстом. */
    fun pickDefault(models: List<Model>): Model? {
        for (family in PREFERRED) {
            models.filter { it.id.contains(family, ignoreCase = true) }
                .maxByOrNull { it.contextLength }
                ?.let { return it }
        }
        return models.maxByOrNull { it.contextLength }
    }

    suspend fun translateOne(key: String, model: String, text: String): String = withContext(Dispatchers.IO) {
        clean(chat(key, model, SYSTEM_ONE, text))
    }

    /** Несколько кусков одним запросом — так тратится один запрос из дневного лимита, а не десятки. */
    suspend fun translateMany(key: String, model: String, texts: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val reply = clean(chat(key, model, SYSTEM_MANY, JSONArray(texts).toString()))
            val start = reply.indexOf('[')
            val end = reply.lastIndexOf(']')
            if (start < 0 || end <= start) throw CloudException("нейросеть ответила не в том виде")
            val array = runCatching { JSONArray(reply.substring(start, end + 1)) }
                .getOrElse { throw CloudException("нейросеть ответила не в том виде") }
            if (array.length() != texts.size) throw CloudException("нейросеть перевела не все куски")
            List(array.length()) { array.optString(it) }
        }

    private fun chat(key: String, model: String, system: String, user: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val body = request("POST", "$BASE/chat/completions", key, payload.toString())
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { throw CloudException(it.optString("message", "ошибка сервиса")) }
        val content = json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
        if (content.isNullOrBlank()) throw CloudException("пустой ответ нейросети")
        return content
    }

    private fun request(method: String, url: String, key: String?, payload: String?): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = if (payload == null) 15_000 else 40_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("HTTP-Referer", "https://github.com/lehsemyonov-spec/tongway")
            setRequestProperty("X-Title", "Tongway")
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
            if (code !in 200..299) throw httpError(code, body)
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

    private fun httpError(code: Int, body: String): CloudException {
        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
        AppLog.i("OpenRouter ответил $code: ${message ?: body.take(200)}")
        return when (code) {
            401 -> CloudException("неверный ключ")
            402 -> CloudException("для этой модели нужен баланс на счёте")
            403 -> CloudException("доступ запрещён: ${message ?: "проверьте ключ"}")
            404 -> CloudException("модель недоступна", modelMissing = true)
            429 -> CloudException("лимит запросов: подождите минуту или до завтра")
            400 -> CloudException(message ?: "неверный запрос", modelMissing = message?.contains("model", true) == true)
            in 500..599 -> CloudException("сервис или модель сейчас перегружены")
            else -> CloudException("ошибка $code${message?.let { ": $it" } ?: ""}")
        }
    }

    /** Убираем «размышления» рассуждающих моделей и обрамляющие кавычки. */
    private fun clean(text: String): String {
        var out = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (out.startsWith("```")) out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return out
    }

    private val PREFERRED = listOf("gemini", "deepseek-chat", "deepseek-v3", "llama-4", "llama-3.3-70b", "qwen3", "qwen", "mistral", "gemma")
    private val SKIP = listOf("coder", "math", "guard", "embed", "vision", "-vl", "audio", "image")
}
