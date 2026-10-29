package com.voicedock.ai.settings

import android.content.Context
import com.voicedock.ai.voice.SessionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SpeechLanguage(val tag: String, val bn: String, val en: String) {
    BN_BD("bn-BD", "বাংলা", "Bengali"),
    EN_US("en-US", "English (US)", "English (US)"),
    EN_IN("en-IN", "English (India)", "English (India)"),
    /** Android recognisers take ONE language; Bengali models also recognise many common English words. */
    MIXED("bn-BD", "বাংলা + English মিশ্র", "Bengali + English mixed"),
}

@Serializable
enum class EnginePreference(val bn: String, val en: String) {
    /** Best phone speech service that supports the language; if none works and Gemini is set up → Gemini. */
    AUTO("স্বয়ংক্রিয় — বাংলা পারে এমন ফোন-সার্ভিস, না পারলে Gemini", "Automatic — a phone service that supports the language, else Gemini"),
    /** Phone speech service, offline / on-device models only. */
    ON_DEVICE("ফোনের সার্ভিস — শুধু অফলাইন", "Phone service — offline only"),
    /** Phone speech service (Google etc.), online allowed. */
    ONLINE("ফোনের ভয়েস সার্ভিস (Google ইত্যাদি)", "Phone speech service (Google etc.)"),
    /** Gemini AI transcription with the user's own API key (audio goes to Google Gemini). */
    GEMINI("Gemini AI — ইন্টারনেট, নিজের API key", "Gemini AI — internet, your own API key"),
    /** whisper.cpp on the phone's CPU with a downloaded model — fully offline. */
    WHISPER("অফলাইন Whisper — ফোনেই, ইন্টারনেট লাগে না", "Offline Whisper — on the phone, no internet"),
}

@Serializable
enum class UiLanguage { BN, EN }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val language: SpeechLanguage = SpeechLanguage.BN_BD,
    val engine: EnginePreference = EnginePreference.AUTO,
    /** Silence (seconds) after which the indicator shows "pause" — the session stays open. */
    val shortPauseSec: Int = 3,
    /** true = keep listening during long silence; false = switch the microphone off after [longInactivityMin] (draft kept). */
    val keepListeningOnLongSilence: Boolean = false,
    val longInactivityMin: Int = 5,
    /** Local Faithful-Dictation cleanup when finishing. */
    val autoCleanup: Boolean = true,
    /** Off by default: the draft is always shown for review before insertion. */
    val autoInsert: Boolean = false,
    val spokenCommands: Boolean = true,
    val uiLanguage: UiLanguage = UiLanguage.BN,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val bubbleX: Int = 0,
    val bubbleY: Int = 360,
    /** Flattened ComponentName of the chosen phone RecognitionService; null = choose automatically. */
    val phoneService: String? = null,
    /** Gemini model id (picked from the live model list after "Test key"). */
    val geminiModel: String = "gemini-flash-latest",
    /** Explicit consent: audio (Gemini engine) and screenshots (screen help) may be sent to Google Gemini. */
    val cloudAllowed: Boolean = false,
    /** Local-only mode: never send anything to a cloud AI, even if a key exists. */
    val localOnly: Boolean = false,
    /** AI providers in fallback order (free-first by default). */
    val aiOrder: List<String> = com.voicedock.ai.ai.AiProvider.DEFAULT_ORDER,
    /** provider id → chosen model id (from the live model list). Gemini uses [geminiModel]. */
    val aiModels: Map<String, String> = emptyMap(),
    /** Off by default: paid-only providers (and non-free OpenRouter models) are never used. */
    val allowPaid: Boolean = false,
    /** Base URL for the Custom OpenAI-compatible provider (e.g. http://192.168.0.10:11434/v1).
     *  Only loopback/LAN hosts may use http:// (see network_security_config.xml + AiPolicy). */
    val customAiUrl: String = "",
    /** Last Mode B style. */
    val formatStyle: String = "CLEAN",
    /** Gmail IMAP address (the app password is in SecretStore). */
    val imapEmail: String = "",
    /** Google account e-mail used for OAuth connectors (display only). */
    val googleAccount: String = "",
    /** Chosen offline Whisper model (WhisperModel name); null = largest installed. */
    val whisperModel: String? = null,
    /** VoiceDock keyboard: Bengali phonetic typing (true) or English (false). */
    val imeBengali: Boolean = true,

    // ---- v0.6 Contextual Assistant (granular; every sensitive switch is OFF by default) ----
    /** Read visible screen text when the user TAPS the bubble. Never in the background. */
    val assistantScreenContext: Boolean = false,
    /** Collect notifications/events while the app is closed. */
    val assistantBackgroundEvents: Boolean = false,
    /** Match senders against contacts. */
    val assistantContacts: Boolean = false,
    /** Allow ONE screenshot only after an explicit "explain screen" tap. */
    val assistantScreenshot: Boolean = false,
    val assistantAutoScan: Boolean = true,
    val assistantSelfTyping: Boolean = true,
    val assistantCatchUp: Boolean = true,
    val assistantDimBlur: Boolean = true,
    /** Opt-in local crash reports: enabled by default only in debug builds. */
    val crashLoggingEnabled: Boolean = com.voicedock.ai.BuildConfig.DEBUG,
) {
    /** Bridge to the pure, unit-tested intelligence layer (no Android types there). */
    fun assistantPrefs() = com.voicedock.ai.context.AssistantPrefs(
        screenContextConsent = assistantScreenContext,
        backgroundEventsConsent = assistantBackgroundEvents,
        contactsConsent = assistantContacts,
        screenshotConsent = assistantScreenshot,
        autoScanOnTap = assistantAutoScan,
        selfTypingGreeting = assistantSelfTyping,
        catchUpSummary = assistantCatchUp,
        dimBlur = assistantDimBlur,
        localOnly = localOnly,
    )
    fun sessionConfig() = SessionConfig(
        shortPauseMs = shortPauseSec.coerceIn(1, 30) * 1000L,
        longInactivityMs = if (keepListeningOnLongSilence) null else longInactivityMin.coerceIn(1, 120) * 60_000L,
    )
}

/** SharedPreferences-backed settings (non-secret values only; secrets will use the Keystore-backed store). */
object SettingsStore {
    private const val FILE = "voicedock_settings"
    private const val KEY = "settings_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    private var ctx: Context? = null

    @Synchronized fun init(context: Context) {
        ctx = context.applicationContext
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        _state.value = raw?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
    }

    @Synchronized fun update(f: (AppSettings) -> AppSettings) {
        val next = f(_state.value)
        _state.value = next
        ctx?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, json.encodeToString(AppSettings.serializer(), next))?.apply()
    }
}

/** Tiny bilingual helper: tr("বাংলা", "English"). */
fun tr(bn: String, en: String): String = if (SettingsStore.state.value.uiLanguage == UiLanguage.BN) bn else en
