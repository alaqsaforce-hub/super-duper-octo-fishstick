package com.voicedock.ai.accounts

import com.voicedock.ai.ai.GeminiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** OpenRouter "OAuth PKCE": the user signs in on openrouter.ai and approves; the app receives a user-controlled API key. */
class OpenRouterLogin(
    private val http: OkHttpClient = GeminiClient.defaultHttp,
    private val site: String = "https://openrouter.ai",
) {
    companion object { const val CALLBACK_PATH = "/openrouter/callback" }
    private val json = Json { ignoreUnknownKeys = true }

    fun authUrl(callbackUrl: String, verifier: String): String =
        "$site/auth?callback_url=" + URLEncoder.encode(callbackUrl, "UTF-8") +
            "&code_challenge=" + Pkce.challenge(verifier) + "&code_challenge_method=S256"

    fun exchange(code: String, verifier: String): String {
        val body = buildJsonObject { put("code", code); put("code_verifier", verifier); put("code_challenge_method", "S256") }
        val req = Request.Builder().url("$site/api/v1/auth/keys")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            http.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw AuthException("http_${r.code}", "OpenRouter HTTP ${r.code}", terminal = r.code in 400..499)
                return runCatching { json.parseToJsonElement(t).jsonObject["key"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: throw AuthException("no_key", "OpenRouter returned no key", terminal = true)
            }
        } catch (e: IOException) { throw AuthException("network", e.javaClass.simpleName) }
    }
}
