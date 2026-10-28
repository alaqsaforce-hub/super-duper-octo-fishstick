package com.voicedock.ai.agent

/**
 * Code-level safety gate. It does NOT trust the model: even if the model says "not risky", sending, deleting, paying,
 * calling, installing or changing a setting needs the user's explicit "Allow". Banking/payment apps, VoiceDock itself
 * and password fields are refused outright.
 */
object AgentSafety {
    sealed class Verdict {
        data object Allow : Verdict()
        data class Confirm(val reasonBn: String, val reasonEn: String) : Verdict()
        data class Block(val reasonBn: String, val reasonEn: String) : Verdict()
    }

    /** English words matched as whole words; Bengali matched as substrings (no word boundaries in \b for Bengali). */
    private val RISK_EN = listOf(
        "send", "delete", "remove", "pay", "payment", "buy", "purchase", "order", "checkout", "confirm", "install", "uninstall",
        "allow", "grant", "subscribe", "post", "publish", "share", "call", "transfer", "submit", "sign out", "log out", "logout",
        "reset", "erase", "format", "block", "report", "forward", "accept", "agree", "unsubscribe", "clear data", "clear storage",
        "factory", "deactivate", "disable", "enable", "turn off", "turn on", "save changes", "apply", "update now", "reply",
    )
    private val RISK_BN = listOf(
        "পাঠান", "পাঠাও", "সেন্ড", "মুছ", "ডিলিট", "সরান", "পেমেন্ট", "পরিশোধ", "কিনুন", "কিনুন", "অর্ডার", "নিশ্চিত", "ইনস্টল", "আনইনস্টল",
        "অনুমতি দিন", "সাবস্ক্রাইব", "পোস্ট", "শেয়ার", "কল", "ট্রান্সফার", "জমা দিন", "সাবমিট", "লগ আউট", "লগআউট", "রিসেট", "ব্লক", "রিপোর্ট",
        "ফরোয়ার্ড", "গ্রহণ", "সম্মত", "বন্ধ করুন", "চালু করুন", "টাকা", "রিপ্লাই", "উত্তর দিন",
    )
    private val riskRegex = Regex("\\b(" + RISK_EN.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)

    /** Package-name segments that mark finance apps (never operated by the agent). */
    private val FINANCE_SEGMENTS = setOf("bkash", "nagad", "rocket", "upay", "bank", "banking", "wallet", "pay", "paypal", "mbs")
    val DEFAULT_BLOCKED = setOf(
        "com.bKash.customerapp", "com.konasl.nagad", "com.dbbl.mbs.apps.main", "com.google.android.apps.walletnfcrel",
        "com.android.vending", // Play Store: installs/purchases
    )
    const val SELF = "com.voicedock.ai"

    /**
     * Risk word in a SHORT, button-like label ("Send", "মুছুন", "Voice call"). Long labels are list rows/messages
     * (e.g. a chat row whose last message says "please send the file") and are not treated as action buttons.
     */
    fun riskWord(label: String): String? {
        val l = label.trim()
        if (l.isEmpty() || l.length > 40 || l.split(Regex("\\s+")).size > 5) return null
        riskRegex.find(l)?.let { return it.value }
        val tokens = l.split(Regex("[\\s/·,.:;!?()\\[\\]\"'|]+")).filter { it.isNotEmpty() }
        return RISK_BN.firstOrNull { w -> if (w.contains(' ')) l.contains(w) else tokens.any { it.startsWith(w) } }
    }

    fun isFinance(pkg: String): Boolean =
        pkg.lowercase().split('.').any { seg -> seg in FINANCE_SEGMENTS || seg.contains("bank") || seg.contains("bkash") || seg.contains("nagad") || seg.contains("wallet") }

    fun isBlockedPackage(pkg: String, extraBlocked: Set<String>): Boolean =
        pkg == SELF || pkg in DEFAULT_BLOCKED || pkg in extraBlocked || isFinance(pkg)

    private val FINANCE_LABEL = Regex("(?i)(\\b(bank|banking|wallet|bkash|nagad|rocket|upay|paypal)\\b|ব্যাংক|বিকাশ|নগদ|রকেট)")
    /** App names such as "DBBL Bank", "বিকাশ" (package names do not always say "bank"). */
    fun isFinanceLabel(label: String): Boolean = FINANCE_LABEL.containsMatchIn(label)

    fun check(d: AgentDecision, s: ScreenSnapshot?, extraBlocked: Set<String> = emptySet(), launchTarget: String? = null): Verdict {
        val a = d.action
        // reading/navigation-only actions never need a confirmation
        if (a is AgentAction.Note || a is AgentAction.Finish || a is AgentAction.Look || a is AgentAction.Wait || a is AgentAction.AskUser) return Verdict.Allow
        if (a is AgentAction.OpenApp) {
            if ((launchTarget != null && isBlockedPackage(launchTarget, extraBlocked)) || isFinanceLabel(a.name))
                return Verdict.Block("এই অ্যাপে (${launchTarget ?: a.name}) এজেন্ট কাজ করে না (টাকা-পয়সা/নিরাপত্তা)।", "The agent does not operate this app (${launchTarget ?: a.name}) (finance/safety).")
            return if (d.risky) Verdict.Confirm("এজেন্ট বলছে কাজটা ঝুঁকিপূর্ণ।", "The agent marked this as risky.") else Verdict.Allow
        }
        if (a is AgentAction.Back || a is AgentAction.Home) return Verdict.Allow
        // everything below acts inside the current app
        if (s != null && (isBlockedPackage(s.pkg, extraBlocked) || isFinanceLabel(s.appLabel)) && a !is AgentAction.Scroll)
            return Verdict.Block("এই অ্যাপে (${s.appLabel.ifBlank { s.pkg }}) এজেন্ট কিছু চাপে না/লেখে না।", "The agent does not tap or type in ${s.appLabel.ifBlank { s.pkg }}.")
        val target: UiElement? = when (a) {
            is AgentAction.Tap -> s?.byId(a.id) ?: coord(s, a.x, a.y)
            is AgentAction.LongPress -> s?.byId(a.id) ?: coord(s, a.x, a.y)
            is AgentAction.Type -> s?.byId(a.id) ?: s?.elements?.firstOrNull { it.focused && it.editable }
            is AgentAction.Scroll -> s?.byId(a.id)
            else -> null
        }
        if (a is AgentAction.Type) {
            if (target?.password == true) return Verdict.Block("পাসওয়ার্ড/পিন ঘরে এজেন্ট লেখে না।", "The agent never types into password/PIN fields.")
            if (looksSecret(a.text)) return Verdict.Block("এটা পিন/ওটিপি/কার্ড নম্বরের মতো দেখাচ্ছে — এজেন্ট লিখবে না।", "This looks like a PIN/OTP/card number — the agent will not type it.")
            if (a.submit) return Verdict.Confirm("লিখে ‘এন্টার/পাঠান’ চাপবে: “${a.text.take(80)}”", "Will type and press enter/send: “${a.text.take(80)}”")
        }
        if (a is AgentAction.Tap || a is AgentAction.LongPress) {
            val label = target?.label.orEmpty()
            riskWord(label)?.let { w -> return Verdict.Confirm("“${label.take(60)}” চাপবে (‘$w’ — পাঠানো/মোছা/বদল হতে পারে)।", "Will tap “${label.take(60)}” (‘$w’ may send/delete/change something).") }
            if (target?.kind == ElemKind.TOGGLE) return Verdict.Confirm("একটা সেটিং বদলাবে: “${label.take(60)}”।", "Will change a setting: “${label.take(60)}”.")
        }
        if (d.risky) return Verdict.Confirm("এজেন্ট বলছে কাজটা ঝুঁকিপূর্ণ: ${d.thought.take(100)}", "The agent marked this as risky: ${d.thought.take(100)}")
        return Verdict.Allow
    }

    private fun coord(s: ScreenSnapshot?, x: Int?, y: Int?): UiElement? {
        if (s == null || x == null || y == null) return null
        return s.elementAt(x * s.width / 1000, y * s.height / 1000)
    }

    /** 4–8 digit codes alone (PIN/OTP) or 13–19 digit card-like numbers. Phone numbers with + or spaces are allowed. */
    fun looksSecret(t: String): Boolean {
        val s = t.trim()
        if (Regex("^\\d{4,8}$").matches(s)) return true
        val digits = s.filter { it.isDigit() }
        return Regex("^[\\d -]{13,23}$").matches(s) && digits.length in 13..19 && !s.startsWith("+") && !s.startsWith("01")
    }
}
