package com.voicedock.ai.voice

import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.settings.EnginePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Chooses the speech engine per settings and falls back automatically:
 *  - GEMINI → Gemini; ONLINE / ON_DEVICE → phone services;
 *  - AUTO → phone services (Google first); if no phone service accepts the language and Gemini is set up
 *    (key + consent + not local-only), switches to Gemini without losing the session.
 * Only events of the active engine are forwarded.
 */
class RoutingSpeechEngine(
    private val phone: SpeechEngine,
    private val gemini: SpeechEngine,
    private val settings: () -> AppSettings,
    scope: CoroutineScope,
    private val whisper: SpeechEngine? = null,
) : SpeechEngine {
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SpeechEvent> = _events
    private val _note = MutableStateFlow<String?>(null)
    override val note: StateFlow<String?> = _note
    private var active: SpeechEngine = phone
    private var lastRequest: EngineRequest? = null
    /** AUTO fell back to Gemini / offline Whisper for this language (until settings/language change). */
    private var autoGeminiFor: String? = null
    private var autoFallback: SpeechEngine? = null

    init {
        scope.launch { phone.events.collect { onChild(phone, it) } }
        scope.launch { gemini.events.collect { onChild(gemini, it) } }
        scope.launch { gemini.note.collect { if (active === gemini) _note.value = it } }
        whisper?.let { w ->
            scope.launch { w.events.collect { onChild(w, it) } }
            scope.launch { w.note.collect { if (active === w) _note.value = it } }
        }
    }

    override val label: String get() = active.label
    override val busy: Boolean get() = active.busy
    override fun isAvailable() = phone.isAvailable() || gemini.isAvailable() || whisper?.isAvailable() == true

    fun choose(s: AppSettings, req: EngineRequest): SpeechEngine = when (s.engine) {
        EnginePreference.GEMINI -> gemini
        EnginePreference.ONLINE, EnginePreference.ON_DEVICE -> phone
        EnginePreference.WHISPER -> whisper ?: phone
        EnginePreference.AUTO -> autoFallback?.takeIf { autoGeminiFor == req.languageTag && it.isAvailable() } ?: phone
    }

    override fun start(request: EngineRequest) {
        lastRequest = request
        val next = choose(settings(), request)
        if (next !== active) { active.cancel(); active = next; _note.value = null }
        active.start(request)
    }

    private fun onChild(src: SpeechEngine, e: SpeechEvent) {
        if (src !== active) return
        if (e is SpeechEvent.Error && src === phone && settings().engine == EnginePreference.AUTO &&
            (e.kind == SpeechErrorKind.LANGUAGE || e.kind == SpeechErrorKind.UNAVAILABLE)) {
            // Prefer Gemini (better Bengali) when the user set it up; otherwise a downloaded offline Whisper model.
            val fb = if (gemini.isAvailable()) gemini else whisper?.takeIf { it.isAvailable() }
            if (fb != null) {
                val req = lastRequest ?: return
                autoGeminiFor = req.languageTag
                autoFallback = fb
                active = fb
                _note.value = if (fb === gemini) "ফোনের ভয়েস সার্ভিস এই ভাষা নেয়নি — Gemini দিয়ে লেখা হচ্ছে"
                else "ফোনের ভয়েস সার্ভিস এই ভাষা নেয়নি — অফলাইন Whisper দিয়ে লেখা হচ্ছে"
                fb.start(req)
                return
            }
        }
        _events.tryEmit(e)
    }

    override fun stop() = active.stop()
    override fun cancel() = active.cancel()
    override fun release() { phone.release(); gemini.release(); whisper?.release() }
}
