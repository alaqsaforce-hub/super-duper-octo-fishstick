package com.voicedock.ai.context

/**
 * Boundary between the intelligence layer and Android device knowledge.
 *
 * Production wires [AgentSafetyOracle] (in the Android layer) to the already-tested
 * `agent/AgentSafety` rules, so there is exactly ONE safety vocabulary in the app.
 * Tests use [DefaultSafetyOracle] or a fake — no Android classes needed.
 */
interface SafetyOracle {
    fun blockedPackage(pkg: String): Boolean
    fun financeLabel(label: String): Boolean
    fun secretText(text: String): Boolean
}

/**
 * Standalone mirror of the AgentSafety rules (kept deliberately dependency-free).
 * No regex backslashes are used so this file is easy to review and safe to copy.
 */
object DefaultSafetyOracle : SafetyOracle {
    private val FINANCE_SEGMENTS = setOf(
        "bkash", "nagad", "rocket", "upay", "bank", "banking", "wallet", "pay", "paypal", "mbs",
    )
    private val FINANCE_WORDS = listOf(
        "bank", "banking", "wallet", "bkash", "nagad", "rocket", "upay", "paypal",
        "ব্যাংক", "বিকাশ", "নগদ", "রকেট", "ওয়ালেট", "পেমেন্ট", "টাকা",
    )

    override fun blockedPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.lowercase().split('.').any { seg ->
            seg in FINANCE_SEGMENTS || seg.contains("bank") || seg.contains("bkash") ||
                seg.contains("nagad") || seg.contains("wallet")
        }
    }

    override fun financeLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val l = label.lowercase()
        return FINANCE_WORDS.any { l.contains(it) }
    }

    /** 4-8 bare digits = PIN/OTP; 13-19 digits separated by space/dash = card-like. */
    override fun secretText(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        if (s.length in 4..8 && s.all { it.isDigit() }) return true
        val digits = s.filter { it.isDigit() }
        return s.length in 13..23 &&
            s.all { it.isDigit() || it == ' ' || it == '-' } &&
            digits.length in 13..19 &&
            !s.startsWith("+") && !s.startsWith("01")
    }
}
