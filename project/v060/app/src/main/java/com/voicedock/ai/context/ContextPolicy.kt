package com.voicedock.ai.context

/**
 * The privacy gate. Runs BEFORE any screen content reaches the assistant UI, memory or a model.
 *
 * Hard rules (v0.6):
 *  - No consent -> no read at all.
 *  - Detected password/secret, known finance app/label, a reported secure window, or VoiceDock itself
 *    -> SECURE. The snapshot is redacted. Detection is heuristic; the local probe cannot query FLAG_SECURE.
 *  - Screenshots are NEVER taken by the probe; they require a separate explicit user tap.
 */
data class PolicyConfig(
    /** Just-in-time consent: "read visible screen text when I tap the bubble". Default OFF. */
    val screenContextConsent: Boolean = false,
    val allowScreenshot: Boolean = false,
    val extraBlockedPackages: Set<String> = emptySet(),
    val minContentChars: Int = 120,
    val minContentElements: Int = 4,
)

object ContextPolicy {

    fun evaluate(
        s: ContextSnapshot,
        cfg: PolicyConfig,
        oracle: SafetyOracle = DefaultSafetyOracle,
    ): Pair<ContextKind, BlockReason> {
        if (!cfg.screenContextConsent) return ContextKind.SECURE to BlockReason.CONSENT_OFF
        if (s.isSelf) return ContextKind.SECURE to BlockReason.SELF_PACKAGE
        if (oracle.blockedPackage(s.pkg) || s.pkg in cfg.extraBlockedPackages)
            return ContextKind.SECURE to BlockReason.FINANCE_PACKAGE
        if (oracle.financeLabel(s.appLabel)) return ContextKind.SECURE to BlockReason.FINANCE_LABEL
        if (s.hasPasswordField) return ContextKind.SECURE to BlockReason.PASSWORD_FIELD
        if (s.flagSecure) return ContextKind.SECURE to BlockReason.FLAG_SECURE
        if (s.secureTextHits > 0) {
            // The probe already used oracle.secretText() while counting; no text is stored.
            return ContextKind.SECURE to BlockReason.OTP_LIKE_TEXT
        }
        if (!s.probeOk) return ContextKind.NO_ACCESS to BlockReason.NONE
        if (s.focusedField != null || s.editableField != null)
            return ContextKind.FIELD_FOUND to BlockReason.NONE
        if (s.visibleCharCount >= cfg.minContentChars || s.elementCount >= cfg.minContentElements)
            return ContextKind.CONTENT_NO_FIELD to BlockReason.NONE
        return ContextKind.EMPTY_SCREEN to BlockReason.NONE
    }

    /** Never hand blocked or incomplete snapshot details to the UI or memory. */
    fun safeSnapshot(s: ContextSnapshot, kind: ContextKind): ContextSnapshot =
        if (kind == ContextKind.SECURE || kind == ContextKind.NO_ACCESS) s.redacted() else s

    fun blockExplanation(reason: BlockReason, bn: Boolean): String = when (reason) {
        BlockReason.NONE -> ""
        BlockReason.CONSENT_OFF -> if (bn)
            "স্ক্রিন প্রসঙ্গ পড়ার অনুমতি দেওয়া হয়নি। চাইলে সেটিংসে চালু করতে পারেন।"
        else "Screen-context consent is off. You can enable it in Settings."
        BlockReason.SELF_PACKAGE -> if (bn)
            "এটা VoiceDock-এর নিজের স্ক্রিন — নিজেকে পড়ব না।"
        else "This is VoiceDock's own window; I never read myself."
        BlockReason.FINANCE_PACKAGE, BlockReason.FINANCE_LABEL -> if (bn)
            "আর্থিক অ্যাপ — এখানে স্ক্রিন পড়া বন্ধ।"
        else "Finance app — screen reading is blocked here."
        BlockReason.PASSWORD_FIELD -> if (bn)
            "পাসওয়ার্ড ঘর আছে — স্ক্রিনের লেখা সংরক্ষণ বা পাঠানো হয়নি।"
        else "A password field is present — screen text was not retained or sent."
        BlockReason.OTP_LIKE_TEXT, BlockReason.CARD_LIKE_TEXT -> if (bn)
            "OTP/পিন/কার্ড-সদৃশ তথ্য — স্ক্রিন পড়া বাদ দেওয়া হয়েছে।"
        else "OTP/PIN/card-like content — screen reading skipped."
        BlockReason.FLAG_SECURE -> if (bn)
            "এই অ্যাপ স্ক্রিনশট/কনটেন্ট আটকায় — কিছু নেওয়া হয়নি।"
        else "This app blocks screen content — nothing was taken."
    }
}
