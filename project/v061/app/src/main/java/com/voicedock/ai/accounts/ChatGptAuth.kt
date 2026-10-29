package com.voicedock.ai.accounts

import com.voicedock.ai.ai.GeminiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Saved ChatGPT credential record (encrypted in SecretStore as JSON). */
@Serializable
data class ChatGptCreds(
    val clientId: String,
    val hostId: String,
    val subject: String,
    val email: String = "",
    val idToken: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val scopes: List<String> = emptyList(),
)

/**
 * "Sign in with ChatGPT" with ChatGPT plan usage, for open-source / locally hosted apps
 * (developers.openai.com/siwc/token-sharing-open-source). Public client: PKCE, no client secret.
 * First sign-in registers this app dynamically (client_id=dynamic_agent_client) and returns an issued client id.
 */
class ChatGptAuth(
    private val http: OkHttpClient = GeminiClient.defaultHttp,
    private val authBase: String = ISSUER,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val DYNAMIC_CLIENT = "dynamic_agent_client"
        const val AGENT_NAME = "VoiceDock AI"
        const val CALLBACK_PATH = "/auth/callback"
        const val PREFERRED_PORT = 1455
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
    }

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var discoveryCache: JsonObject? = null
    @Volatile private var jwksCache: JsonObject? = null

    data class Pending(val state: String, val nonce: String, val verifier: String, val redirectUri: String, val clientId: String?)

    data class Tokens(val accessToken: String, val refreshToken: String, val idToken: String, val expiresAt: Long, val scopes: List<String>)

    fun newPending(redirectUri: String, savedClientId: String?) = Pending(Pkce.random(), Pkce.random(), Pkce.verifier(), redirectUri, savedClientId)

    /** Authorization URL. New registration: agent_name_hint; returning account: issued client id + id_token_hint/login_hint. */
    fun authorizeUrl(p: Pending, hostId: String, idTokenHint: String? = null, loginHint: String? = null, forceConsent: Boolean = false): String {
        val q = linkedMapOf(
            "client_id" to (p.clientId ?: DYNAMIC_CLIENT),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to p.redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to p.state,
            "nonce" to p.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to Pkce.challenge(p.verifier),
        )
        if (p.clientId == null) q["agent_name_hint"] = AGENT_NAME
        else {
            idTokenHint?.takeIf { it.isNotBlank() }?.let { q["id_token_hint"] = it }
            loginHint?.takeIf { it.isNotBlank() }?.let { q["login_hint"] = it }
        }
        if (forceConsent) q["prompt"] = "consent"
        return "$authBase/api/accounts/authorize?" + q.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
    }

    /** Checks the browser callback; returns (code, issuedClientId). */
    fun checkCallback(p: Pending, q: Map<String, String>): Pair<String, String> {
        if (q["state"] != p.state) throw AuthException("state", "state mismatch", terminal = true)
        q["error"]?.let { throw AuthException(it, q["error_description"] ?: it, terminal = true) }
        val code = q["code"]?.takeIf { it.isNotBlank() } ?: throw AuthException("no_code", "no authorization code", terminal = true)
        val cbClient = q["client_id"]?.takeIf { it.isNotBlank() }
        val issued = if (p.clientId == null) cbClient ?: throw AuthException("registration_incomplete", "registration incomplete (no client_id)", terminal = true)
        else {
            if (cbClient != null && cbClient != p.clientId) throw AuthException("client_mismatch", "callback client_id differs from the saved registration", terminal = true)
            p.clientId
        }
        if (issued == DYNAMIC_CLIENT) throw AuthException("registration_incomplete", "registration incomplete", terminal = true)
        return code to issued
    }

    fun exchange(clientId: String, code: String, verifier: String, redirectUri: String): Tokens = token(FormBody.Builder()
        .add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
        .add("code_verifier", verifier).add("redirect_uri", redirectUri).add("resource", RESOURCE).build(), previousRefresh = null)

    fun refresh(clientId: String, refreshToken: String): Tokens = token(FormBody.Builder()
        .add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refreshToken).add("resource", RESOURCE).build(), previousRefresh = refreshToken)

    private fun token(body: FormBody, previousRefresh: String?): Tokens {
        val req = Request.Builder().url("$authBase/api/accounts/oauth/token").post(body).build()
        val text = try {
            http.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val o = runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull()
                    val err = o?.get("error")?.let { e -> (e as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: e.jsonPrimitive.contentOrNull } ?: "http_${r.code}"
                    val terminal = err in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused", "invalid_client")
                    throw AuthException(err, o?.get("error_description")?.jsonPrimitive?.contentOrNull ?: err, terminal)
                }
                t
            }
        } catch (e: IOException) { throw AuthException("network", e.javaClass.simpleName) }
        val o = json.parseToJsonElement(text).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val access = s("access_token") ?: throw AuthException("no_token", "no access token")
        val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        return Tokens(access, s("refresh_token") ?: previousRefresh.orEmpty(), s("id_token").orEmpty(), now() + expiresIn * 1000, s("scope").orEmpty().split(' ').filter { it.isNotBlank() })
    }

    private fun getJson(url: String): JsonObject = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw AuthException("http_${r.code}", "HTTP ${r.code} for $url")
            json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        }
    } catch (e: IOException) { throw AuthException("network", e.javaClass.simpleName) }

    fun discovery(): JsonObject = discoveryCache ?: getJson("$authBase/.well-known/openid-configuration").also { discoveryCache = it }

    /** Verifies the ID token (signature via the published JWKS, issuer, audience = issued client id, expiry, nonce). Returns (sub, email). */
    fun verifyIdToken(idToken: String, clientId: String, nonce: String?): Pair<String, String> {
        if (idToken.isBlank()) throw AuthException("no_id_token", "no ID token", terminal = true)
        fun jwks(refresh: Boolean): JsonObject = (if (refresh) null else jwksCache)
            ?: getJson(discovery()["jwks_uri"]?.jsonPrimitive?.contentOrNull ?: "$authBase/.well-known/jwks.json").also { jwksCache = it }
        if (!Jwt.verifyRs256(idToken, jwks(false)) && !Jwt.verifyRs256(idToken, jwks(true)))
            throw AuthException("bad_signature", "ID token signature invalid", terminal = true)
        val p = Jwt.payload(idToken)
        Jwt.claimProblem(p, ISSUER, clientId, nonce, now() / 1000)?.let { throw AuthException("bad_claims", "ID token $it check failed", terminal = true) }
        return p["sub"]!!.jsonPrimitive.content to (p["email"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    /** Ends the renewable session. true = revocation confirmed (HTTP 200). */
    fun revoke(clientId: String, refreshToken: String): Boolean {
        val endpoint = runCatching { discovery()["revocation_endpoint"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return false
        val req = Request.Builder().url(endpoint).post(FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token").add("client_id", clientId).build()).build()
        repeat(3) { attempt ->
            try { http.newCall(req).execute().use { r -> if (r.isSuccessful) return true; if (r.code < 500) return false } } catch (_: IOException) {}
            Thread.sleep(500L * (attempt + 1))
        }
        return false
    }
}

/** Thread-safe per-account session memoizer. Use one cache for the lifetime of the application process. */
class ChatGptSessionCache {
    private val sessions = ConcurrentHashMap<String, ChatGptSession>()

    fun get(accountId: String, create: (String) -> ChatGptSession): ChatGptSession =
        sessions.computeIfAbsent(accountId, create)
}

/** Provides a fresh access token for one saved ChatGPT account (refreshes and rotates the refresh token as needed). */
class ChatGptSession(
    private val load: () -> ChatGptCreds?,
    private val save: (ChatGptCreds) -> Unit,
    private val onSignedOut: () -> Unit,
    private val auth: ChatGptAuth = ChatGptAuth(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun fresh(c: ChatGptCreds): Boolean = c.accessToken.isNotBlank() && c.expiresAt - 120_000 > now()

    /**
     * The lock is useful only when callers share this instance. The LoginManager caches one per account.
     * Re-read credentials after a failed refresh: another owner/process path may already have rotated them.
     */
    @Synchronized
    fun accessToken(): String {
        var current = load() ?: throw AuthException("signed_out", "not signed in", terminal = true)
        repeat(2) { attempt ->
            if (fresh(current)) return current.accessToken
            if (current.refreshToken.isBlank()) {
                onSignedOut()
                throw AuthException("signed_out", "sign in again", terminal = true)
            }
            val tokens = try {
                auth.refresh(current.clientId, current.refreshToken)
            } catch (e: AuthException) {
                val latest = load()
                if (latest != null && credentialsChanged(current, latest)) {
                    if (fresh(latest)) return latest.accessToken
                    if (attempt == 0 && latest.refreshToken.isNotBlank()) {
                        current = latest
                        return@repeat
                    }
                    // Do not sign out a credential record that has already moved on.
                    throw e
                }
                if (e.terminal) onSignedOut()
                throw e
            }

            val latest = load()
            if (latest != null && credentialsChanged(current, latest)) {
                if (fresh(latest)) return latest.accessToken
                if (attempt == 0 && latest.refreshToken.isNotBlank()) {
                    current = latest
                    return@repeat
                }
                throw AuthException("credential_race", "credentials changed during token refresh")
            }
            val updated = current.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.ifBlank { current.refreshToken },
                expiresAt = tokens.expiresAt,
                idToken = tokens.idToken.ifBlank { current.idToken },
                scopes = tokens.scopes.ifEmpty { current.scopes },
            )
            save(updated)
            return updated.accessToken
        }
        throw AuthException("credential_race", "credentials changed during token refresh")
    }

    private fun credentialsChanged(before: ChatGptCreds, after: ChatGptCreds): Boolean =
        before.clientId != after.clientId || before.subject != after.subject ||
            before.accessToken != after.accessToken || before.refreshToken != after.refreshToken ||
            before.expiresAt != after.expiresAt
}
