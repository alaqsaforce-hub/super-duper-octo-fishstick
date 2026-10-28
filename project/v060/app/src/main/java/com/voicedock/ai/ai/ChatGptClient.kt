package com.voicedock.ai.ai

import com.voicedock.ai.accounts.AuthException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * ChatGPT plan usage through the public Responses API with the user's OAuth access token
 * (store=false, stream=true, instructions instead of system messages; no temperature/max_output_tokens — preview rules).
 */
class ChatGptClient(
    private val token: () -> String,
    baseUrl: String = AiProvider.CHATGPT.baseUrl,
    private val http: OkHttpClient = GeminiClient.defaultHttp,
) : ChatClient {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val p = AiProvider.CHATGPT

    private fun bearer(): String = try { token() } catch (e: AuthException) {
        throw AiException(if (e.terminal) AiException.Kind.AUTH else AiException.Kind.NETWORK, 0, "ChatGPT: " + (e.message ?: e.code), p)
    }

    override fun listModels(): List<String> {
        val body = httpCall(http, Request.Builder().url("$base/models").header("Authorization", "Bearer ${bearer()}").get().build(), p)
        val root = json.parseToJsonElement(body).jsonObject
        (root["models"] as? JsonArray)?.let { arr ->
            return arr.map { it.jsonObject }.filter { (it["visibility"]?.jsonPrimitive?.contentOrNull ?: "list") == "list" }
                .mapNotNull { it["slug"]?.jsonPrimitive?.contentOrNull ?: it["id"]?.jsonPrimitive?.contentOrNull }
        }
        return (root["data"] as? JsonArray)?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }

    override val supportsImages get() = true
    override fun complete(model: String, system: String, messages: List<ChatMessage>) = completeWithImages(model, system, messages, emptyList())

    override fun completeWithImages(model: String, system: String, messages: List<ChatMessage>, images: List<ByteArray>): String {
        val payload = buildJsonObject {
            put("model", model)
            if (system.isNotBlank()) put("instructions", system)
            putJsonArray("input") {
                messages.forEachIndexed { i, m ->
                    val assistant = m.role == "assistant"
                    add(buildJsonObject {
                        put("role", if (assistant) "assistant" else "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", if (assistant) "output_text" else "input_text"); put("text", m.text) })
                            if (!assistant && i == messages.lastIndex) images.forEach { img ->
                                add(buildJsonObject { put("type", "input_image"); put("image_url", "data:image/jpeg;base64," + b64(img)) })
                            }
                        }
                    })
                }
            }
            put("store", false)
            put("stream", true)
        }
        val req = Request.Builder().url("$base/responses").header("Authorization", "Bearer ${bearer()}").header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    val t = r.body?.string().orEmpty()
                    val (code, msg) = errorOf(t) ?: ("http_${r.code}" to "HTTP ${r.code}")
                    throw AiException(kindFor(code, r.code), r.code, "$code: ${msg.take(200)}", p)
                }
                val src = r.body?.source() ?: throw AiException(AiException.Kind.EMPTY, r.code, "empty", p)
                return readStream { src.readUtf8Line() }
            }
        } catch (e: IOException) {
            throw AiException(AiException.Kind.NETWORK, 0, e.javaClass.simpleName, p)
        }
    }

    /** Server-sent events → final text. Success only after response.completed. */
    internal fun readStream(nextLine: () -> String?): String {
        val sb = StringBuilder()
        while (true) {
            val line = nextLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "response.output_text.delta" -> sb.append(ev["delta"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "response.completed" -> {
                    val text = sb.toString().ifBlank { completedText(ev) }.trim()
                    if (text.isEmpty()) throw AiException(AiException.Kind.EMPTY, 200, "empty", p)
                    return text
                }
                "response.failed" -> {
                    val err = (ev["response"] as? JsonObject)?.get("error") as? JsonObject
                    val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "response_failed"
                    throw AiException(kindFor(code, 0), 0, code + (err?.get("message")?.jsonPrimitive?.contentOrNull?.let { ": ${it.take(160)}" } ?: ""), p)
                }
                "response.incomplete" -> throw AiException(AiException.Kind.EMPTY, 200, "incomplete: " +
                    ((ev["response"] as? JsonObject)?.get("incomplete_details")?.toString()?.take(120) ?: ""), p)
                "error" -> {
                    val code = ev["code"]?.jsonPrimitive?.contentOrNull ?: (ev["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: "error"
                    throw AiException(kindFor(code, 0), 0, code, p)
                }
            }
        }
        throw AiException(AiException.Kind.NETWORK, 0, "stream ended without response.completed", p)
    }

    private fun completedText(ev: JsonObject): String {
        val out = ((ev["response"] as? JsonObject)?.get("output") as? JsonArray) ?: return ""
        return out.flatMap { o -> ((o as? JsonObject)?.get("content") as? JsonArray)?.toList().orEmpty() }
            .mapNotNull { c -> (c as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    }

    private fun errorOf(t: String): Pair<String, String>? = runCatching {
        val o = json.parseToJsonElement(t).jsonObject
        val e = o["error"] as? JsonObject
        if (e != null) (e["code"]?.jsonPrimitive?.contentOrNull ?: e["type"]?.jsonPrimitive?.contentOrNull ?: "error") to (e["message"]?.jsonPrimitive?.contentOrNull ?: "")
        else "detail" to (o["detail"]?.toString() ?: t)
    }.getOrNull()

    companion object {
        fun kindFor(code: String, http: Int): AiException.Kind = when {
            code == "subscription_sharing_usage_limit_exceeded" -> AiException.Kind.RATE_LIMIT
            code in setOf("subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable") -> AiException.Kind.SERVER
            code in setOf("subscription_sharing_invalid_user", "subscription_sharing_user_not_eligible", "chatpass_v2_scope_not_authorized",
                "chatpass_v2_invalid_authorization_context", "subscription_sharing_route_not_supported") -> AiException.Kind.AUTH
            code == "subscription_sharing_unsupported_capability" -> AiException.Kind.BAD_REQUEST
            http == 401 || http == 403 -> AiException.Kind.AUTH
            http == 429 -> AiException.Kind.RATE_LIMIT
            http >= 500 -> AiException.Kind.SERVER
            else -> AiException.Kind.BAD_REQUEST
        }
    }
}
