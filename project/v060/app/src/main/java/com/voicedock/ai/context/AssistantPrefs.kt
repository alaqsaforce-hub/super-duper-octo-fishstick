package com.voicedock.ai.context

/**
 * Granular, user-controlled switches for the assistant. The Android `settings/AppSettings`
 * persists the same values; this pure data class is what the intelligence layer receives.
 *
 * Every sensitive switch defaults to OFF. Remote AI stays off; local-only is the default.
 */
data class AssistantPrefs(
    /** Read visible screen text when the user taps the bubble (never in the background). */
    val screenContextConsent: Boolean = false,
    /** Collect notifications / events while the app is closed. Off by default. */
    val backgroundEventsConsent: Boolean = false,
    /** Match senders against the contacts list. Off by default. */
    val contactsConsent: Boolean = false,
    /** Allow a screenshot ONLY after an explicit "explain screen" tap. */
    val screenshotConsent: Boolean = false,
    val autoScanOnTap: Boolean = true,
    val selfTypingGreeting: Boolean = true,
    val catchUpSummary: Boolean = true,
    val dimBlur: Boolean = true,
    val localOnly: Boolean = true,
    val maxScreenChars: Int = 4000,
) {
    fun policy(extraBlocked: Set<String> = emptySet()) = PolicyConfig(
        screenContextConsent = screenContextConsent,
        allowScreenshot = screenshotConsent,
        extraBlockedPackages = extraBlocked,
    )
}
