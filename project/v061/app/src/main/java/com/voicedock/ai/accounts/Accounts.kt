package com.voicedock.ai.accounts

import android.content.Context
import com.voicedock.ai.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

object AccountKind {
    const val KEY = "key"
    const val OPENROUTER_LOGIN = "openrouter_login"
    const val CHATGPT = "chatgpt"
}

/**
 * A saved AI account (non-secret part). The secret (API key, or the ChatGPT token record) lives encrypted in
 * [SecretStore] under [Accounts.secretName]. Several accounts per provider are allowed: free ones are tried first.
 */
@Serializable
data class Account(
    val id: String,
    val provider: String,
    val label: String,
    val kind: String = AccountKind.KEY,
    /** The user says billing is on for this key (e.g. a Gemini key from a billing-enabled project). */
    val paid: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val email: String = "",
    /** ChatGPT: issued OAuth client id (kept after sign-out for the next sign-in). */
    val clientId: String = "",
    /** ChatGPT: verified ID-token subject. */
    val subject: String = "",
    /** false after sign-out / an unusable refresh token (the record stays so the user can sign in again). */
    val loggedIn: Boolean = true,
    /** ChatGPT: plan-usage permission (chatgpt.tokens.use.direct) granted. */
    val planUsage: Boolean = true,
)

/** Pure ordering rules (unit-tested): enabled + signed-in only, free before paid, older first; paid dropped unless allowed. */
object AccountOrder {
    fun usable(list: List<Account>, provider: String, allowPaid: Boolean): List<Account> =
        list.filter { it.provider == provider && it.enabled && it.loggedIn && (it.kind != AccountKind.CHATGPT || it.planUsage) && (allowPaid || !it.paid) }
            .sortedWith(compareBy<Account>({ it.paid }, { it.createdAt }))
}

object Accounts {
    private const val FILE = "voicedock_accounts"
    private const val KEY = "list_v1"
    private const val HOST = "chatgpt_host_id"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(Account.serializer())
    private val _version = MutableStateFlow(0)
    /** Bumped on every change (UI recomposition trigger). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun secretName(id: String) = "acct_$id"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Account> =
        prefs(ctx).getString(KEY, null)?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty()

    @Synchronized private fun save(ctx: Context, l: List<Account>) {
        prefs(ctx).edit().putString(KEY, json.encodeToString(ser, l)).apply()
        _version.value++
    }

    fun get(ctx: Context, id: String) = list(ctx).firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun add(ctx: Context, a: Account, secret: String?) {
        if (secret != null) SecretStore.put(ctx, secretName(a.id), secret)
        save(ctx, list(ctx).filterNot { it.id == a.id } + a.copy(createdAt = if (a.createdAt == 0L) System.currentTimeMillis() else a.createdAt))
    }

    @Synchronized fun update(ctx: Context, id: String, f: (Account) -> Account) = save(ctx, list(ctx).map { if (it.id == id) f(it) else it })

    fun putSecret(ctx: Context, id: String, secret: String) { SecretStore.put(ctx, secretName(id), secret); _version.value++ }
    fun secret(ctx: Context, id: String): String? = SecretStore.get(ctx, secretName(id))
    fun clearSecret(ctx: Context, id: String) { SecretStore.remove(ctx, secretName(id)); _version.value++ }

    /** Deletes the account and its secret from the phone. */
    fun remove(ctx: Context, id: String) {
        SecretStore.remove(ctx, secretName(id))
        save(ctx, list(ctx).filterNot { it.id == id })
    }

    fun hasUsable(ctx: Context, provider: String, allowPaid: Boolean = true) = AccountOrder.usable(list(ctx), provider, allowPaid).isNotEmpty()

    /** Stable opaque id of this phone for "Sign in with ChatGPT" (ext_agent_host_id), created once. */
    fun hostId(ctx: Context): String = prefs(ctx).getString(HOST, null) ?: ("urn:uuid:" + UUID.randomUUID()).also { prefs(ctx).edit().putString(HOST, it).apply() }
}
