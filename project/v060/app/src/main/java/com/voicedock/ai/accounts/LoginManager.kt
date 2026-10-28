package com.voicedock.ai.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voicedock.ai.ai.AiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class LoginUi(val busy: Boolean = false, val message: String = "", val error: String = "", val showPlanWelcome: Boolean = false)

/**
 * Browser sign-in flows (like "Sign in" in VS Code): open the provider page in the browser, receive the redirect on
 * a 127.0.0.1 loopback port, finish the exchange, store the result encrypted, and bring the app back to front.
 */
object LoginManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(LoginUi())
    val ui: StateFlow<LoginUi> = _ui.asStateFlow()
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val TIMEOUT_MS = 5 * 60_000L
    const val RETURN_URI = "intent://auth#Intent;scheme=voicedock;package=com.voicedock.ai;end"

    fun dismissWelcome() { _ui.value = _ui.value.copy(showPlanWelcome = false) }
    fun clearMessage() { _ui.value = _ui.value.copy(message = "", error = "") }

    fun cancel() { job?.cancel(); job = null; _ui.value = LoginUi(message = "লগইন বাতিল") }

    internal fun page(ok: Boolean, text: String): String = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>VoiceDock</title></head>
<body style="font-family:sans-serif;padding:24px;text-align:center"><h2>${if (ok) "✅" else "⚠️"} VoiceDock AI</h2>
<p>${text.replace("<", "&lt;")}</p><p><a href="$RETURN_URI" style="font-size:20px">অ্যাপে ফিরে যান</a></p>
<script>setTimeout(function(){location.href="$RETURN_URI"},600)</script></body></html>"""

    private fun openBrowser(ctx: Context, url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun bringBack(ctx: Context) {
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        _ui.value = LoginUi(busy = true, message = "ব্রাউজারে লগইন করুন… (৫ মিনিট পর্যন্ত অপেক্ষা)")
        job = scope.launch {
            try { block() }
            catch (e: TimeoutCancellationException) { _ui.value = LoginUi(error = "সময় শেষ — আবার চেষ্টা করুন") }
            catch (e: AuthException) { _ui.value = LoginUi(error = authText(e)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _ui.value = LoginUi(error = "লগইন ব্যর্থ: ${e.javaClass.simpleName}") }
        }
    }

    fun authText(e: AuthException): String = when (e.code) {
        "access_denied" -> "আপনি অনুমতি দেননি — কিছু সেভ হয়নি"
        "state" -> "নিরাপত্তা যাচাই মেলেনি (state) — আবার চেষ্টা করুন"
        "registration_incomplete" -> "ChatGPT রেজিস্ট্রেশন সম্পূর্ণ হয়নি — আবার চেষ্টা করুন"
        "client_mismatch" -> "ChatGPT ভিন্ন অ্যাপ-আইডি ফেরত দিয়েছে — নিরাপত্তার জন্য বাতিল"
        "invalid_grant" -> "কোড মেয়াদোত্তীর্ণ — আবার লগইন করুন"
        "network" -> "নেটওয়ার্ক সমস্যা"
        else -> "লগইন ব্যর্থ: ${e.code}"
    }

    // ---------------- OpenRouter ----------------
    fun loginOpenRouter(ctx: Context, label: String = "") {
        val app = ctx.applicationContext
        start {
            val login = OpenRouterLogin()
            val verifier = Pkce.verifier()
            LoopbackReceiver(OpenRouterLogin.CALLBACK_PATH, 3000).use { rx ->
                openBrowser(ctx, login.authUrl(rx.redirectUri, verifier))
                val q = rx.await(TIMEOUT_MS) { p -> page(p["code"] != null, if (p["code"] != null) "OpenRouter সংযুক্ত হচ্ছে…" else "অনুমতি পাওয়া যায়নি") }
                val code = q["code"] ?: throw AuthException(q["error"] ?: "access_denied", "no code", true)
                val key = withContext(Dispatchers.IO) { login.exchange(code, verifier) }
                val n = Accounts.list(app).count { it.provider == AiProvider.OPENROUTER.id } + 1
                Accounts.add(app, Account(Accounts.newId(), AiProvider.OPENROUTER.id, label.ifBlank { "OpenRouter লগইন $n" }, AccountKind.OPENROUTER_LOGIN), key)
                bringBack(ctx)
                _ui.value = LoginUi(message = "✅ OpenRouter যুক্ত হয়েছে (ফ্রি মডেল আগে ব্যবহার হবে)")
            }
        }
    }

    // ---------------- ChatGPT ----------------
    fun creds(ctx: Context, id: String): ChatGptCreds? = Accounts.secret(ctx, id)?.let { runCatching { json.decodeFromString(ChatGptCreds.serializer(), it) }.getOrNull() }
    fun saveCreds(ctx: Context, id: String, c: ChatGptCreds) = Accounts.putSecret(ctx, id, json.encodeToString(ChatGptCreds.serializer(), c))

    /** [existingId] = sign in again to a saved account (reuses its issued client id). */
    fun loginChatGpt(ctx: Context, existingId: String? = null, forceConsent: Boolean = false) {
        val app = ctx.applicationContext
        start {
            val auth = ChatGptAuth()
            val existing = existingId?.let { Accounts.get(app, it) }
            val old = existingId?.let { creds(app, it) }
            val hostId = Accounts.hostId(app)
            LoopbackReceiver(ChatGptAuth.CALLBACK_PATH, ChatGptAuth.PREFERRED_PORT).use { rx ->
                val pending = auth.newPending(rx.redirectUri, existing?.clientId?.takeIf { it.isNotBlank() })
                openBrowser(ctx, auth.authorizeUrl(pending, hostId, old?.idToken, existing?.email, forceConsent))
                val q = rx.await(TIMEOUT_MS) { p -> page(p["code"] != null && p["error"] == null,
                    if (p["error"] != null) "লগইন হয়নি: ${p["error"]}" else "ChatGPT সংযুক্ত হচ্ছে…") }
                if (q.isEmpty()) throw AuthException("no_code", "no callback", true)
                val (code, clientId) = auth.checkCallback(pending, q)
                // Persist the issued registration before anything else can fail.
                existing?.let { e -> if (e.clientId != clientId) Accounts.update(app, e.id) { it.copy(clientId = clientId) } }
                val t = withContext(Dispatchers.IO) { auth.exchange(clientId, code, pending.verifier, rx.redirectUri) }
                val (sub, email) = withContext(Dispatchers.IO) { auth.verifyIdToken(t.idToken, clientId, pending.nonce) }
                val plan = ChatGptAuth.PLAN_SCOPE in t.scopes
                // Same ChatGPT identity already saved → update it; a different identity → a new account.
                val same = Accounts.list(app).firstOrNull { it.provider == AiProvider.CHATGPT.id && it.subject == sub && it.clientId == clientId }
                val id = same?.id ?: existing?.takeIf { it.subject.isBlank() || it.subject == sub }?.id ?: Accounts.newId()
                val firstTime = Accounts.list(app).none { it.provider == AiProvider.CHATGPT.id && it.loggedIn }
                val c = ChatGptCreds(clientId, hostId, sub, email, t.idToken, t.accessToken, t.refreshToken, t.expiresAt, t.scopes)
                val acc = Accounts.get(app, id)
                if (acc == null) Accounts.add(app, Account(id, AiProvider.CHATGPT.id, email.ifBlank { "ChatGPT" }, AccountKind.CHATGPT,
                    email = email, clientId = clientId, subject = sub, loggedIn = true, planUsage = plan), null)
                else Accounts.update(app, id) { it.copy(email = email, clientId = clientId, subject = sub, loggedIn = true, planUsage = plan, label = it.label.ifBlank { email }) }
                saveCreds(app, id, c)
                bringBack(ctx)
                _ui.value = LoginUi(
                    message = if (plan) "✅ ChatGPT লগইন: ${email.ifBlank { sub.take(8) }}" else "⚠️ লগইন হয়েছে কিন্তু প্ল্যান-ব্যবহারের অনুমতি দেওয়া হয়নি — \"আবার অনুমতি দিন\" চাপুন",
                    showPlanWelcome = plan && firstTime)
            }
        }
    }

    /** Signs out: revokes the refresh token, deletes tokens; keeps the account row + issued client id for the next sign-in. */
    fun logoutChatGpt(ctx: Context, id: String) {
        val app = ctx.applicationContext
        scope.launch {
            _ui.value = LoginUi(busy = true, message = "লগআউট হচ্ছে…")
            val c = creds(app, id)
            val revoked = if (c != null && c.refreshToken.isNotBlank()) withContext(Dispatchers.IO) { runCatching { ChatGptAuth().revoke(c.clientId, c.refreshToken) }.getOrDefault(false) } else true
            markSignedOut(app, id)
            _ui.value = LoginUi(message = if (revoked) "লগআউট হয়েছে" else "ফোন থেকে টোকেন মুছে ফেলা হয়েছে (সার্ভারে বাতিল নিশ্চিত হয়নি — chatgpt.com থেকে অ্যাক্সেস সরাতে পারেন)")
        }
    }

    /** Local sign-out (also used when the refresh token is no longer valid). */
    fun markSignedOut(ctx: Context, id: String) {
        val c = creds(ctx, id)
        if (c != null) saveCreds(ctx, id, c.copy(idToken = "", accessToken = "", refreshToken = "", expiresAt = 0)) else Accounts.clearSecret(ctx, id)
        Accounts.update(ctx, id) { it.copy(loggedIn = false) }
    }

    fun session(ctx: Context, id: String): ChatGptSession {
        val app = ctx.applicationContext
        return ChatGptSession(load = { creds(app, id) }, save = { saveCreds(app, id, it) }, onSignedOut = { markSignedOut(app, id) })
    }
}
