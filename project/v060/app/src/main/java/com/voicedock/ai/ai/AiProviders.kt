package com.voicedock.ai.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/**
 * AI providers VoiceDock can use with the user's OWN key. [freeTier] = the provider offers a free tier
 * (it does NOT guarantee the user's key is free — a key with billing enabled can still be charged).
 * Paid-only providers are skipped unless the user switches on "Allow paid providers".
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val freeTier: Boolean,
    val baseUrl: String,
    val keyUrl: String,
    val noteBn: String,
    val noteEn: String,
) {
    GEMINI("gemini", "Google Gemini", true, "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey",
        "ফ্রি সীমা আছে। ফ্রি key-তে পাঠানো কনটেন্ট Google উন্নয়নে ব্যবহার করতে পারে।", "Free tier. On free keys Google may use submitted content."),
    GROQ("groq", "Groq", true, "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
        "ফ্রি সীমা আছে, খুব দ্রুত।", "Free tier, very fast."),
    OPENROUTER("openrouter", "OpenRouter", true, "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
        "শুধু ‘:free’ নামের মডেল ফ্রি; পেইড বন্ধ থাকলে অন্যগুলো বাদ।", "Only ‘:free’ models are free; others skipped while paid is off."),
    MISTRAL("mistral", "Mistral", true, "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys",
        "ফ্রি ‘Experiment’ প্ল্যান আছে।", "Free ‘Experiment’ plan available."),
    OPENAI("openai", "OpenAI", false, "https://api.openai.com/v1", "https://platform.openai.com/api-keys",
        "পেইড — ব্যবহার করলে খরচ হবে।", "Paid — usage is billed."),
    ANTHROPIC("anthropic", "Anthropic Claude", false, "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys",
        "পেইড — ব্যবহার করলে খরচ হবে।", "Paid — usage is billed."),
    DEEPSEEK("deepseek", "DeepSeek", false, "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys",
        "পেইড — ব্যবহার করলে খরচ হবে।", "Paid — usage is billed."),
    CHATGPT("chatgpt", "ChatGPT (লগইন)", true, "https://api.openai.com/v1", "https://chatgpt.com/settings/usage",
        "Key লাগে না — ‘Continue with ChatGPT’ দিয়ে লগইন। আপনার ChatGPT প্ল্যানের (Go/Plus/Pro) ব্যবহার-সীমা থেকে খরচ হয়।", "No key — sign in with ‘Continue with ChatGPT’. Uses your ChatGPT plan (Go/Plus/Pro) usage."),
    CUSTOM("custom", "Custom (OpenAI-compatible)", true, "", "",
        "নিজের সার্ভার (যেমন Ollama/LM Studio)। ঠিকানা আপনি দেবেন; খরচ আপনার সার্ভারের উপর।", "Your own server (e.g. Ollama/LM Studio). Costs depend on your server.");

    /** SecretStore entry name (Gemini keeps its v0.2.0 name so existing keys keep working). */
    val secretName: String get() = if (this == GEMINI) "gemini_api_key" else "key_$id"

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        /** Free-first default order. */
        val DEFAULT_ORDER = listOf(GEMINI, CHATGPT, GROQ, OPENROUTER, MISTRAL, CUSTOM, OPENAI, ANTHROPIC, DEEPSEEK).map { it.id }
    }
}

class AiException(val kind: Kind, val httpCode: Int, message: String, val provider: AiProvider? = null) : Exception(message) {
    enum class Kind { AUTH, RATE_LIMIT, BAD_REQUEST, SERVER, NETWORK, BLOCKED, EMPTY, NOT_CONFIGURED }
}

data class ChatMessage(val role: String, val text: String) {
    companion object {
        fun user(t: String) = ChatMessage("user", t)
        fun assistant(t: String) = ChatMessage("assistant", t)
    }
}

/** One provider's chat API. Blocking — call from Dispatchers.IO. */
interface ChatClient {
    fun listModels(): List<String>
    fun complete(model: String, system: String, messages: List<ChatMessage>): String
    /** Whether this API accepts images at all (the chosen model may still refuse → BAD_REQUEST → next provider). */
    val supportsImages: Boolean get() = false
    /** Like [complete] with JPEG images attached to the last user message. */
    fun completeWithImages(model: String, system: String, messages: List<ChatMessage>, images: List<ByteArray>): String =
        if (images.isEmpty()) complete(model, system, messages) else throw AiException(AiException.Kind.BAD_REQUEST, 0, "no image support")
}

internal fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

private val JSON_MT = "application/json; charset=utf-8".toMediaType()
private val lenient = Json { ignoreUnknownKeys = true }

private fun kindFor(code: Int, msg: String) = when (code) {
    401, 403 -> AiException.Kind.AUTH
    402 -> AiException.Kind.RATE_LIMIT // "payment required" = no credit: treat like a limit, try the next provider
    429 -> AiException.Kind.RATE_LIMIT
    in 500..599 -> AiException.Kind.SERVER
    400 -> if (msg.contains("api key", true) || msg.contains("api_key", true)) AiException.Kind.AUTH else AiException.Kind.BAD_REQUEST
    else -> AiException.Kind.BAD_REQUEST
}

internal fun httpCall(http: OkHttpClient, request: Request, provider: AiProvider): String {
    try {
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return text
            val msg = runCatching {
                val e = lenient.parseToJsonElement(text).jsonObject["error"]
                (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "HTTP ${r.code}"
            throw AiException(kindFor(r.code, msg), r.code, msg.take(300), provider)
        }
    } catch (e: IOException) {
        throw AiException(AiException.Kind.NETWORK, 0, e.javaClass.simpleName, provider)
    }
}

/** OpenAI-compatible chat API (OpenAI, Groq, OpenRouter, Mistral, DeepSeek, Ollama, LM Studio…). */
class OpenAiCompatClient(
    private val provider: AiProvider,
    private val apiKey: String?,
    baseUrl: String = provider.baseUrl,
    private val http: OkHttpClient = GeminiClient.defaultHttp,
) : ChatClient {
    private val base = baseUrl.trimEnd('/')
    // A local server must not redirect an approved request (including its prompt/key) to the cloud.
    // Apply this to all custom servers so changing modes while a request is in flight is safe.
    private val requestHttp = if (provider == AiProvider.CUSTOM)
        http.newBuilder().followRedirects(false).followSslRedirects(false).build() else http

    private fun req(path: String) = Request.Builder().url("$base$path").apply {
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
        if (provider == AiProvider.OPENROUTER) header("X-Title", "VoiceDock AI")
    }

    override fun listModels(): List<String> {
        val body = httpCall(requestHttp, req("/models").get().build(), provider)
        val data = lenient.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.sorted()
    }

    override val supportsImages get() = true

    override fun complete(model: String, system: String, messages: List<ChatMessage>): String = completeWithImages(model, system, messages, emptyList())

    override fun completeWithImages(model: String, system: String, messages: List<ChatMessage>, images: List<ByteArray>): String {
        val payload = buildJsonObject {
            put("model", model)
            // OpenAI reasoning models reject non-default temperature; other providers accept a low value.
            if (provider != AiProvider.OPENAI) put("temperature", 0.2)
            putJsonArray("messages") {
                if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
                messages.forEachIndexed { i, m ->
                    if (images.isNotEmpty() && i == messages.lastIndex && m.role == "user") add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", m.text) })
                            images.forEach { img -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64," + b64(img)) }) }) }
                        }
                    }) else add(buildJsonObject { put("role", m.role); put("content", m.text) })
                }
            }
        }
        val body = httpCall(requestHttp, req("/chat/completions").post(payload.toString().toRequestBody(JSON_MT)).build(), provider)
        val root = lenient.parseToJsonElement(body).jsonObject
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        if (text.isEmpty()) {
            val reason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            throw AiException(if (reason == "content_filter") AiException.Kind.BLOCKED else AiException.Kind.EMPTY, 200, reason ?: "empty", provider)
        }
        return text
    }
}

/** Anthropic Messages API. */
class AnthropicClient(
    private val apiKey: String,
    baseUrl: String = AiProvider.ANTHROPIC.baseUrl,
    private val http: OkHttpClient = GeminiClient.defaultHttp,
) : ChatClient {
    private val base = baseUrl.trimEnd('/')
    private fun req(path: String) = Request.Builder().url("$base$path")
        .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")

    override fun listModels(): List<String> {
        val body = httpCall(http, req("/models?limit=100").get().build(), AiProvider.ANTHROPIC)
        return (lenient.parseToJsonElement(body).jsonObject["data"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }

    override val supportsImages get() = true

    override fun complete(model: String, system: String, messages: List<ChatMessage>): String = completeWithImages(model, system, messages, emptyList())

    override fun completeWithImages(model: String, system: String, messages: List<ChatMessage>, images: List<ByteArray>): String {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                messages.forEachIndexed { i, m ->
                    if (images.isNotEmpty() && i == messages.lastIndex && m.role == "user") add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            images.forEach { img -> add(buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("type", "base64"); put("media_type", "image/jpeg"); put("data", b64(img)) }) }) }
                            add(buildJsonObject { put("type", "text"); put("text", m.text) })
                        }
                    }) else add(buildJsonObject { put("role", m.role); put("content", m.text) })
                }
            }
        }
        val body = httpCall(http, req("/messages").post(payload.toString().toRequestBody(JSON_MT)).build(), AiProvider.ANTHROPIC)
        val content = lenient.parseToJsonElement(body).jsonObject["content"] as? JsonArray
        val text = content?.mapNotNull { c -> c.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")?.trim().orEmpty()
        if (text.isEmpty()) throw AiException(AiException.Kind.EMPTY, 200, "empty", AiProvider.ANTHROPIC)
        return text
    }
}

/** Gemini through the existing [GeminiClient]; errors mapped to [AiException]. */
class GeminiChatClient(private val client: GeminiClient) : ChatClient {
    private fun <T> wrap(f: () -> T): T = try { f() } catch (e: GeminiException) {
        throw AiException(AiException.Kind.valueOf(e.kind.name), e.httpCode, e.message ?: "", AiProvider.GEMINI)
    }
    override fun listModels() = wrap { client.listModels().map { it.id } }
    override fun complete(model: String, system: String, messages: List<ChatMessage>) = wrap { client.chat(model, system, messages) }
    override val supportsImages get() = true
    override fun completeWithImages(model: String, system: String, messages: List<ChatMessage>, images: List<ByteArray>) = wrap { client.chat(model, system, messages, images) }
}

/** One usable provider+model, in fallback order. */
data class AiCandidate(val provider: AiProvider, val model: String, val client: ChatClient, val account: String = "")

data class AiAttempt(val provider: AiProvider, val model: String, val error: String?)

data class AiAnswer(val text: String, val provider: AiProvider, val model: String, val attempts: List<AiAttempt>, val account: String = "")

/**
 * Free-first fallback: tries candidates in order; a limit/server/network/auth error moves on to the next
 * provider. Never retries a provider it was not given (paid gating happens in [AiPolicy]).
 */
class AiRouter(private val candidates: () -> List<AiCandidate>) {
    fun complete(system: String, messages: List<ChatMessage>): AiAnswer = completeVision(system, messages, emptyList())

    /** Same free-first fallback; with images only image-capable APIs are tried. */
    fun completeVision(system: String, messages: List<ChatMessage>, images: List<ByteArray>): AiAnswer {
        val list = candidates().filter { images.isEmpty() || it.client.supportsImages }
        if (list.isEmpty()) throw AiException(AiException.Kind.NOT_CONFIGURED, 0, "no AI provider configured")
        val attempts = mutableListOf<AiAttempt>()
        var last: AiException? = null
        for (c in list) {
            try {
                val t = if (images.isEmpty()) c.client.complete(c.model, system, messages) else c.client.completeWithImages(c.model, system, messages, images)
                attempts += AiAttempt(c.provider, c.model, null)
                return AiAnswer(t, c.provider, c.model, attempts, c.account)
            } catch (e: AiException) {
                attempts += AiAttempt(c.provider, c.model, "${e.kind}: ${e.message}")
                last = e
                if (e.kind == AiException.Kind.NOT_CONFIGURED) continue
            }
        }
        throw AiException(last?.kind ?: AiException.Kind.EMPTY, last?.httpCode ?: 0,
            attempts.joinToString(" | ") { "${it.provider.label}: ${it.error}" }, last?.provider)
    }
}

/** Pure rules deciding whether a provider may be used right now (unit-tested). */
object AiPolicy {
    /**
     * v0.6: a self-hosted endpoint (loopback or RFC1918 LAN) is LOCAL, not cloud.
     * Android's network-security-config cannot express CIDR ranges, so only the exact hosts
     * listed in res/xml/network_security_config.xml may use plain http://. Any other http://
     * URL is refused here (see "insecure_url") — the app never silently downgrades to cleartext.
     */
    /** Parse the actual authority, not a string prefix: userinfo and fake private-IP hostnames
     * must not be mistaken for a local server. Queries/fragments are not valid base URLs. */
    private fun endpoint(url: String): URI? = runCatching { URI(url.trim()) }.getOrNull()?.takeIf { u ->
        u.scheme?.lowercase() in setOf("http", "https") && u.host != null && u.rawUserInfo == null &&
            u.rawQuery == null && u.rawFragment == null && u.port in -1..65535
    }

    fun isValidEndpoint(url: String): Boolean = endpoint(url) != null

    fun isLocalEndpoint(url: String): Boolean {
        val host = endpoint(url)?.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host == "localhost" || host == "::1") return true
        val pieces = host.split('.')
        if (pieces.size != 4 || pieces.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) }) return false
        val octets = pieces.map { it.toInt() }
        if (octets.any { it !in 0..255 }) return false
        return octets == listOf(127, 0, 0, 1) || octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    /** @return null if usable, else a short reason code. */
    fun blockReason(p: AiProvider, model: String?, hasKey: Boolean, customUrl: String, allowPaid: Boolean, cloudAllowed: Boolean, localOnly: Boolean,
                    paidOverride: Boolean? = null): String? = when {
        // Order matters: a bad URL is diagnosed first, in EVERY mode.
        p == AiProvider.CUSTOM && customUrl.isBlank() -> "no_url"
        p == AiProvider.CUSTOM && !isValidEndpoint(customUrl) -> "invalid_url"
        // A public HTTP endpoint is refused even if cloud consent is on (no silent downgrade).
        p == AiProvider.CUSTOM && customUrl.trim().lowercase().startsWith("http://") && !isLocalEndpoint(customUrl) -> "insecure_url"
        // Local-only mode blocks CLOUD providers, never a local/self-hosted one.
        localOnly && !(p == AiProvider.CUSTOM && isLocalEndpoint(customUrl)) -> "local_only"
        // A CUSTOM endpoint on the public internet is a remote provider: same consent as others.
        p == AiProvider.CUSTOM && !isLocalEndpoint(customUrl) && !cloudAllowed -> "no_consent"
        !cloudAllowed && p != AiProvider.CUSTOM -> "no_consent"
        p != AiProvider.CUSTOM && !hasKey -> "no_key"
        model.isNullOrBlank() -> "no_model"
        (paidOverride ?: !p.freeTier) && !allowPaid -> "paid_off"
        p == AiProvider.OPENROUTER && !allowPaid && !model.endsWith(":free") -> "paid_off"
        else -> null
    }

    /** Guard model-list/test requests too; a model has not been selected at that point. */
    fun networkBlockReason(p: AiProvider, customUrl: String, cloudAllowed: Boolean, localOnly: Boolean): String? =
        blockReason(p, "model-test", true, customUrl, allowPaid = true, cloudAllowed = cloudAllowed,
            localOnly = localOnly, paidOverride = false)

    /** Sensible default from the LIVE model list (never a guessed name). */
    fun pickDefault(p: AiProvider, models: List<String>): String? {
        if (models.isEmpty()) return null
        val bad = listOf("embed", "whisper", "tts", "audio", "image", "vision-preview", "guard", "moderation", "realtime", "transcribe", "search", "dall", "ocr")
        val ok = models.filter { id -> bad.none { id.contains(it, true) } }.ifEmpty { models }
        fun first(vararg keys: String) = keys.firstNotNullOfOrNull { k -> ok.firstOrNull { it.contains(k, true) } }
        return when (p) {
            AiProvider.GEMINI -> GeminiClient.pickDefault(ok.map { GeminiModel(it, it) })
            AiProvider.OPENROUTER -> ok.filter { it.endsWith(":free") }.let { free -> free.firstOrNull { it.contains("gemini", true) } ?: free.firstOrNull { it.contains("llama", true) } ?: free.firstOrNull() } ?: ok.first()
            AiProvider.GROQ -> first("llama-3.3-70b", "70b", "llama") ?: ok.first()
            AiProvider.MISTRAL -> first("mistral-small-latest", "mistral-medium-latest", "latest") ?: ok.first()
            AiProvider.OPENAI -> first("-mini", "gpt") ?: ok.first()
            AiProvider.ANTHROPIC -> first("haiku", "sonnet") ?: ok.first()
            AiProvider.DEEPSEEK -> first("deepseek-chat") ?: ok.first()
            AiProvider.CUSTOM -> ok.first()
            AiProvider.CHATGPT -> ok.first() // server order = the account's recommended order
        }
    }
}
