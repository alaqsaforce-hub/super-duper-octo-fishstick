package com.voicedock.ai.ai

import android.content.Context
import com.voicedock.ai.accounts.Account
import com.voicedock.ai.accounts.AccountKind
import com.voicedock.ai.accounts.AccountOrder
import com.voicedock.ai.accounts.Accounts
import com.voicedock.ai.accounts.LoginManager
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr

/** Builds provider clients from the user's settings + Keystore-encrypted keys. */
object AiSetup {
    fun key(ctx: Context, p: AiProvider): String? = SecretStore.get(ctx, p.secretName)
    /** A legacy single key or at least one usable saved account (no decryption). */
    fun hasKey(ctx: Context, p: AiProvider): Boolean =
        (p != AiProvider.CHATGPT && SecretStore.has(ctx, p.secretName)) || Accounts.hasUsable(ctx, p.id)

    fun model(s: AppSettings, p: AiProvider): String? = if (p == AiProvider.GEMINI) s.geminiModel else s.aiModels[p.id]

    /** Client for a plain key (null for ChatGPT, which needs a signed-in account: see [accountClient]). */
    fun client(ctx: Context, p: AiProvider, key: String? = key(ctx, p), s: AppSettings = SettingsStore.state.value): ChatClient? = when (p) {
        AiProvider.GEMINI -> key?.let { GeminiChatClient(GeminiClient(it)) }
        AiProvider.ANTHROPIC -> key?.let { AnthropicClient(it) }
        AiProvider.CUSTOM -> s.customAiUrl.takeIf { it.isNotBlank() }?.let { OpenAiCompatClient(p, key, it) }
        AiProvider.CHATGPT -> null
        else -> key?.let { OpenAiCompatClient(p, it) }
    }

    fun accountClient(ctx: Context, a: Account, s: AppSettings = SettingsStore.state.value): ChatClient? {
        val p = AiProvider.byId(a.provider) ?: return null
        return if (a.kind == AccountKind.CHATGPT) {
            val session = LoginManager.session(ctx, a.id)
            ChatGptClient({ session.accessToken() })
        } else client(ctx, p, Accounts.secret(ctx, a.id), s)
    }

    /** Client used by "Test / list models": legacy key first, else the first usable account. */
    fun testClient(ctx: Context, p: AiProvider, s: AppSettings = SettingsStore.state.value): ChatClient? =
        (if (p != AiProvider.CHATGPT) key(ctx, p)?.let { client(ctx, p, it, s) } else null)
            ?: AccountOrder.usable(Accounts.list(ctx), p.id, allowPaid = true).firstNotNullOfOrNull { accountClient(ctx, it, s) }
            ?: if (p == AiProvider.CUSTOM) client(ctx, p, null, s) else null

    fun blockReason(ctx: Context, p: AiProvider, s: AppSettings = SettingsStore.state.value): String? =
        AiPolicy.blockReason(p, model(s, p), hasKey(ctx, p), s.customAiUrl, s.allowPaid, s.cloudAllowed, s.localOnly)

    /** One usable credential: the legacy key (account == null) or a saved account. */
    private data class Entry(val p: AiProvider, val account: Account?, val paid: Boolean, val order: Int)

    /**
     * Free-first plan: every provider's free credentials (in the user's provider order), then — only when
     * "allow paid" is on — the paid ones. So a paid key is touched only after all free options failed.
     */
    fun candidates(ctx: Context): List<AiCandidate> {
        val s = SettingsStore.state.value
        val order = s.aiOrder.mapNotNull { AiProvider.byId(it) } + AiProvider.entries.filter { it.id !in s.aiOrder }
        val all = Accounts.list(ctx)
        val entries = order.flatMapIndexed { i, p ->
            val m = model(s, p)
            val legacy = if (p != AiProvider.CHATGPT && (p == AiProvider.CUSTOM || SecretStore.has(ctx, p.secretName))) {
                val paid = !p.freeTier || (p == AiProvider.OPENROUTER && m?.endsWith(":free") == false)
                if (AiPolicy.blockReason(p, m, true, s.customAiUrl, s.allowPaid, s.cloudAllowed, s.localOnly) == null) listOf(Entry(p, null, paid, i)) else emptyList()
            } else emptyList()
            val accts = AccountOrder.usable(all, p.id, s.allowPaid).mapNotNull { a ->
                val paid = a.paid || !p.freeTier || (p == AiProvider.OPENROUTER && m?.endsWith(":free") == false)
                if (AiPolicy.blockReason(p, m, true, s.customAiUrl, s.allowPaid, s.cloudAllowed, s.localOnly, paidOverride = paid) == null) Entry(p, a, paid, i) else null
            }
            // Free accounts before the legacy key only if the legacy key is paid.
            (legacy + accts).sortedBy { it.paid }
        }.sortedWith(compareBy<Entry>({ it.paid }, { it.order }))
        return entries.mapNotNull { e ->
            val c = (if (e.account == null) client(ctx, e.p, key(ctx, e.p), s) else accountClient(ctx, e.account, s)) ?: return@mapNotNull null
            AiCandidate(e.p, model(s, e.p)!!, c, account = e.account?.label ?: "")
        }
    }

    fun router(ctx: Context): AiRouter { val app = ctx.applicationContext; return AiRouter { candidates(app) } }

    /** Cheap check (no decryption): at least one provider passes the policy. */
    fun ready(ctx: Context): Boolean { val s = SettingsStore.state.value; return AiProvider.entries.any { blockReason(ctx, it, s) == null } }

    fun reasonText(code: String?): String = when (code) {
        null -> tr("✓ ব্যবহারযোগ্য", "✓ Ready")
        "local_only" -> tr("শুধু-লোকাল মোড চালু", "Local-only mode is on")
        "no_consent" -> tr("‘ক্লাউড AI-তে পাঠানোর অনুমতি’ বন্ধ", "Cloud AI consent is off")
        "no_url" -> tr("সার্ভারের ঠিকানা দিন", "Enter the server URL")
        "invalid_url" -> tr("সার্ভারের ঠিকানা সঠিক নয়", "Invalid server URL")
        "insecure_url" -> tr("http:// শুধু লোকাল/LAN সার্ভারের জন্য; বাকিদের https:// লাগবে", "http:// is allowed only for local/LAN servers; use https:// otherwise")
        "no_key" -> tr("Key দেওয়া হয়নি", "No key")
        "no_model" -> tr("মডেল বাছাই হয়নি — ‘যাচাই’ চাপুন", "No model chosen — tap Test")
        "paid_off" -> tr("পেইড — ‘পেইড অনুমতি’ বন্ধ, তাই বাদ", "Paid — skipped while paid use is off")
        else -> code
    }

    fun explain(e: Throwable): String = when (e) {
        is AiException -> when (e.kind) {
            AiException.Kind.AUTH -> if (e.provider == AiProvider.CHATGPT) tr("ChatGPT লগইন/অনুমতি সমস্যা — আবার লগইন করুন", "ChatGPT sign-in problem — sign in again")
                else tr("Key ভুল বা অনুমতি নেই", "Invalid key or no permission")
            AiException.Kind.RATE_LIMIT -> if (e.provider == AiProvider.CHATGPT) tr("ChatGPT প্ল্যানের ব্যবহার-সীমা শেষ — chatgpt.com/settings/usage দেখুন", "ChatGPT plan usage limit reached — see chatgpt.com/settings/usage") else tr("সীমা শেষ / ক্রেডিট নেই — পরে চেষ্টা করুন বা অন্য প্রোভাইডার", "Limit reached / no credit — try later or another provider")
            AiException.Kind.NETWORK -> tr("ইন্টারনেট সংযোগ নেই", "No internet connection")
            AiException.Kind.SERVER -> tr("প্রোভাইডারের সার্ভারে সমস্যা", "Provider server error")
            AiException.Kind.BLOCKED -> tr("নিরাপত্তা ফিল্টারে আটকে গেছে", "Blocked by the safety filter")
            AiException.Kind.EMPTY -> tr("খালি উত্তর", "Empty answer")
            AiException.Kind.NOT_CONFIGURED -> tr("কোনো AI প্রোভাইডার চালু নেই — সেটিংস → AI প্রোভাইডার", "No AI provider set up — Settings → AI providers")
            AiException.Kind.BAD_REQUEST -> tr("অনুরোধ গ্রহণ হয়নি", "Request rejected")
        } + (e.message?.takeIf { it.isNotBlank() && e.kind != AiException.Kind.NOT_CONFIGURED }?.let { " ($it)" } ?: "")
        else -> e.message ?: e.javaClass.simpleName
    }
}
