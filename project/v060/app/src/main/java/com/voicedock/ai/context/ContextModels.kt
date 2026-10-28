package com.voicedock.ai.context

/**
 * Pure data model of ONE on-tap screen probe.
 *
 * No Android types are used here on purpose: the whole `context` package compiles and is tested
 * on a plain JVM. The Android layer (ScreenProbe) only *builds* this snapshot; it never decides.
 * This is design decision #2 from the v0.6 plan: Device Access != Intelligence.
 */

enum class ContextKind {
    /** A focused / editable, non-password text or message box exists. */
    FIELD_FOUND,
    /** Readable page or document, but no editable box. */
    CONTENT_NO_FIELD,
    /** Launcher / home / empty screen: nothing to read, nothing to type into. */
    EMPTY_SCREEN,
    /** Password, PIN/OTP/card, finance or FLAG_SECURE: screen content must NOT be used. */
    SECURE,
    /** Accessibility unavailable or the probe failed (honest fallback). */
    NO_ACCESS,
}

enum class BlockReason {
    NONE,
    CONSENT_OFF,
    SELF_PACKAGE,
    FINANCE_PACKAGE,
    FINANCE_LABEL,
    PASSWORD_FIELD,
    OTP_LIKE_TEXT,
    CARD_LIKE_TEXT,
    FLAG_SECURE,
}

/** One editable (or password) field seen on the screen. Labels are untrusted screen content. */
data class FieldInfo(
    val label: String = "",
    val hint: String = "",
    val focused: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
)

data class ContextSnapshot(
    val pkg: String = "",
    val appLabel: String = "",
    val capturedAt: Long = 0L,
    val fields: List<FieldInfo> = emptyList(),
    /** Number of visible characters (labels), already counted by the probe. Text itself is NOT stored. */
    val visibleCharCount: Int = 0,
    val elementCount: Int = 0,
    /** How many visible strings looked like a PIN/OTP/card number. */
    val secureTextHits: Int = 0,
    /** The foreground window is FLAG_SECURE (screenshot would be black / forbidden). */
    val flagSecure: Boolean = false,
    /** VoiceDock's own window: never read ourselves. */
    val isSelf: Boolean = false,
    /** False when rootInActiveWindow was null or the probe threw. */
    val probeOk: Boolean = true,
) {
    val focusedField: FieldInfo? get() = fields.firstOrNull { it.focused && it.editable && !it.password }
    val editableField: FieldInfo? get() = fields.firstOrNull { it.editable && !it.password } ?: focusedField
    val hasPasswordField: Boolean get() = fields.any { it.password }

    /** Blocked screens keep only numeric counters/flags, not the app name, package or field details. */
    fun redacted(): ContextSnapshot = copy(pkg = "", appLabel = "", fields = emptyList())
}
