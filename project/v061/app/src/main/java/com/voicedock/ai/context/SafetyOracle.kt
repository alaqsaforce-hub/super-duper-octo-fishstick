package com.voicedock.ai.context

import com.voicedock.ai.security.SecretDetector

/**
 * Boundary between the intelligence layer and Android device knowledge. Production wires
 * [AgentSafetyOracle] (in the Android layer) for package and label rules. Every default/test
 * secret check delegates to the same platform-free [SecretDetector] used by the agent and insert gate.
 */
interface SafetyOracle {
    fun blockedPackage(pkg: String): Boolean
    fun financeLabel(label: String): Boolean
    fun secretText(text: String): Boolean
}

/** Standalone finance-screen rules plus the shared PIN/OTP/card detector. */
object DefaultSafetyOracle : SafetyOracle {
    private val financeSegments = setOf("bkash", "nagad", "rocket", "upay", "bank", "banking", "wallet", "pay", "paypal", "mbs")
    private val financeWords = listOf(
        "bank", "banking", "wallet", "bkash", "nagad", "rocket", "upay", "paypal",
        "ব্যাংক", "বিকাশ", "নগদ", "রকেট", "ওয়ালেট", "পেমেন্ট", "টাকা",
    )

    override fun blockedPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.lowercase().split('.').any { segment ->
            segment in financeSegments || segment.contains("bank") || segment.contains("bkash") ||
                segment.contains("nagad") || segment.contains("wallet")
        }
    }

    override fun financeLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val lower = label.lowercase()
        return financeWords.any { lower.contains(it) }
    }

    override fun secretText(text: String): Boolean = SecretDetector.isSecret(text)
}
