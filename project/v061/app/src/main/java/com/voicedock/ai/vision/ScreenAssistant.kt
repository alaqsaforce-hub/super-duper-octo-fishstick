package com.voicedock.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.speech.tts.TextToSpeech
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.ai.ChatTurn
import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.ai.GeminiException
import com.voicedock.ai.context.ContextClassifier
import com.voicedock.ai.context.ContextKind
import com.voicedock.ai.device.AgentSafetyOracle
import com.voicedock.ai.device.ScreenProbe
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.coroutines.resume

data class ScreenHelpUi(
    val busy: Boolean = false,
    val question: String = "",
    val answer: String = "",
    val error: String? = null,
    /** User tapped "ask by voice": the next finished dictation is sent as the question. */
    val armed: Boolean = false,
    val history: List<ChatTurn> = emptyList(),
)

/**
 * On-demand screen help: ONE screenshot + visible text, only when the user taps, sent to Gemini with the user's
 * question; the answer is shown (and can be read aloud). Nothing runs in the background; nothing is stored
 * except the last few Q/A turns in memory for follow-up questions.
 */
object ScreenAssistant {
    private val _ui = MutableStateFlow(ScreenHelpUi())
    val ui: StateFlow<ScreenHelpUi> = _ui.asStateFlow()

    val defaultQuestion get() = tr("এই স্ক্রিনে কী দেখাচ্ছে আর এখানে আমার কী করা উচিত, সহজ বাংলায় ধাপে ধাপে বুঝিয়ে বলো।",
        "What is on this screen and what should I do here? Explain step by step.")

    /** Why screen help cannot run right now (null = ready). */
    fun blocker(ctx: Context): String? {
        val cfg = VoiceDock.geminiConfig(ctx)
        val s = SettingsStore.state.value
        return when {
            s.localOnly -> tr("লোকাল-অনলি মোড চালু — ক্লাউডে কিছু পাঠানো হয় না", "Local-only mode is on — nothing is sent to the cloud")
            !s.assistantScreenContext -> tr("সেটিংসে স্ক্রিন প্রসঙ্গের অনুমতি চালু করুন", "Enable screen-context consent in Settings")
            !s.assistantScreenshot -> tr("সেটিংসে স্ক্রিনশটের অনুমতি চালু করুন", "Enable screenshot consent in Settings")
            Build.VERSION.SDK_INT < 34 -> tr("নিরাপদ স্ক্রিন ব্যাখ্যার জন্য Android 14 বা নতুন সংস্করণ লাগবে", "Safe screen explanation requires Android 14 or newer")
            cfg.apiKey.isNullOrBlank() -> tr("সেটিংসে Gemini API key দিন (ফ্রি)", "Add a Gemini API key in Settings (free)")
            !cfg.allowed -> tr("সেটিংসে ‘Gemini-তে পাঠানোর অনুমতি’ চালু করুন", "Enable ‘Allow sending to Gemini’ in Settings")
            InsertAccessibilityService.instance == null -> tr("স্ক্রিন দেখতে VoiceDock-এর Accessibility চালু করুন", "Enable VoiceDock Accessibility to read the screen")
            else -> null
        }
    }

    fun arm(on: Boolean) { _ui.value = _ui.value.copy(armed = on, error = null) }
    fun dismiss() { _ui.value = _ui.value.copy(answer = "", error = null, question = "", armed = false) }
    fun clearHistory() { _ui.value = ScreenHelpUi() }

    /**
     * Explicitly requested screen help. Consent and the privacy policy are rechecked on the
     * current window; a failed/secure window capture never falls back to sending accessibility
     * text. Android 14's per-window screenshot API is used instead of a display screenshot.
     */
    suspend fun ask(ctx: Context, question: String, hideOverlay: () -> Unit, showOverlay: () -> Unit) {
        blocker(ctx)?.let { _ui.value = _ui.value.copy(busy = false, error = it, armed = false); return }
        val q = question.ifBlank { defaultQuestion }
        _ui.value = _ui.value.copy(busy = true, question = q, answer = "", error = null, armed = false)
        val svc = InsertAccessibilityService.instance ?: run {
            _ui.value = _ui.value.copy(busy = false, error = tr("Accessibility চালু নেই", "Accessibility is unavailable")); return
        }
        var bmp: Bitmap? = null
        var text = ""
        hideOverlay()
        try {
            delay(350)
            val snapshot = withContext(Dispatchers.IO) { ScreenProbe.probe(svc, oracle = AgentSafetyOracle) }
            val current = SettingsStore.state.value
            val assessment = ContextClassifier.assess(snapshot, current.assistantPrefs().policy(), AgentSafetyOracle)
            if (assessment.kind == ContextKind.SECURE || assessment.kind == ContextKind.NO_ACCESS) {
                _ui.value = _ui.value.copy(busy = false, error = assessment.explanation(current.uiLanguage == UiLanguage.BN))
                return
            }
            val root = svc.rootInActiveWindow
            if (root == null || root.packageName?.toString() != snapshot.pkg) {
                _ui.value = _ui.value.copy(busy = false, error = tr("স্ক্রিন বদলে গেছে — আবার চেষ্টা করুন", "The screen changed — try again"))
                return
            }
            val windowId = root.windowId
            bmp = suspendCancellableCoroutine { c -> svc.captureWindow(windowId) { if (c.isActive) c.resume(it) else it?.recycle() } }
            if (bmp == null) {
                _ui.value = _ui.value.copy(busy = false, error = tr("স্ক্রিনশট পাওয়া যায়নি বা সুরক্ষিত — কোনো লেখা পাঠানো হয়নি", "Window capture failed or was protected — no screen text was sent"))
                return
            }
            val after = withContext(Dispatchers.IO) { ScreenProbe.probe(svc, oracle = AgentSafetyOracle) }
            val latest = SettingsStore.state.value
            val afterAssessment = ContextClassifier.assess(after, latest.assistantPrefs().policy(), AgentSafetyOracle)
            val afterRoot = svc.rootInActiveWindow
            if (afterAssessment.kind == ContextKind.SECURE || afterAssessment.kind == ContextKind.NO_ACCESS ||
                after.pkg != snapshot.pkg || afterRoot == null || afterRoot.windowId != windowId || afterRoot.packageName?.toString() != snapshot.pkg) {
                bmp?.recycle(); bmp = null
                _ui.value = _ui.value.copy(busy = false, error = tr("স্ক্রিন বা অনুমতি বদলেছে — কিছু পাঠানো হয়নি", "The screen or consent changed — nothing was sent"))
                return
            }
            text = svc.screenText(expectedPackage = snapshot.pkg, expectedWindowId = windowId)
        } catch (e: CancellationException) {
            bmp?.recycle(); throw e
        } catch (_: Exception) {
            bmp?.recycle(); bmp = null
            _ui.value = _ui.value.copy(busy = false, error = tr("স্ক্রিন পড়া যায়নি — কিছু পাঠানো হয়নি", "Could not read the screen — nothing was sent"))
            return
        } finally {
            showOverlay()
        }
        blocker(ctx)?.let {
            bmp?.recycle(); _ui.value = _ui.value.copy(busy = false, error = it); return
        }
        val cfg = VoiceDock.geminiConfig(ctx)
        val bn = SettingsStore.state.value.uiLanguage == UiLanguage.BN
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val jpeg = bmp?.let { compress(it) }
                GeminiClient(cfg.apiKey!!).askAboutScreen(cfg.model, jpeg, text, q, _ui.value.history, bn)
            }
        }
        bmp?.recycle()
        result.onSuccess { a ->
            _ui.value = _ui.value.copy(busy = false, answer = a, history = (_ui.value.history + ChatTurn(q, a)).takeLast(5))
        }.onFailure { e ->
            _ui.value = _ui.value.copy(busy = false, error = explain(e))
        }
    }

    fun explain(e: Throwable): String = when ((e as? GeminiException)?.kind) {
        GeminiException.Kind.AUTH -> tr("Gemini API key ভুল/বাতিল। সেটিংসে ঠিক করুন।", "Gemini API key invalid. Fix it in Settings.")
        GeminiException.Kind.RATE_LIMIT -> tr("Gemini ফ্রি সীমা/হার পূর্ণ। একটু পরে চেষ্টা করুন।", "Gemini free limit/rate reached. Try again later.")
        GeminiException.Kind.NETWORK -> tr("ইন্টারনেট সংযোগ নেই।", "No internet connection.")
        GeminiException.Kind.BLOCKED -> tr("Gemini এই অনুরোধের উত্তর দেয়নি (নিরাপত্তা ফিল্টার)।", "Gemini declined this request (safety filter).")
        GeminiException.Kind.BAD_REQUEST -> tr("মডেল এই অনুরোধ নেয়নি — সেটিংসে অন্য মডেল বেছে দেখুন। (${e.message})", "The model rejected the request — try another model. (${e.message})")
        else -> tr("উত্তর পাওয়া যায়নি: ${e.message}", "No answer: ${e.message}")
    }

    private fun compress(src: Bitmap): ByteArray {
        val maxSide = 1280f
        val scale = minOf(1f, maxSide / maxOf(src.width, src.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        return ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 75, it); it.toByteArray() }
    }
}

/** Reads answers aloud with the phone's text-to-speech (Bengali if the phone has a Bengali voice). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false

    fun speak(ctx: Context, text: String, onProblem: (String) -> Unit) {
        val bn = SettingsStore.state.value.uiLanguage == UiLanguage.BN
        val go = {
            val r = tts?.setLanguage(if (bn) Locale("bn", "BD") else Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (bn && tts?.setLanguage(Locale("bn", "IN")).let { it == TextToSpeech.LANG_MISSING_DATA || it == TextToSpeech.LANG_NOT_SUPPORTED || it == null })
                    onProblem(tr("ফোনে বাংলা ভয়েস নেই — Settings → Text-to-speech → Google → বাংলা ডাউনলোড করুন", "No Bengali voice — download it in Text-to-speech settings"))
                else tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vd")
            } else tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vd")
        }
        if (tts != null && ready) { go(); return }
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) go() else onProblem(tr("ফোনের টেক্সট-টু-স্পিচ চালু হয়নি", "Text-to-speech is not available"))
        }
    }

    fun stop() { tts?.stop() }
}
