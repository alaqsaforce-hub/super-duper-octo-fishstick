package com.voicedock.ai.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Events coming from a speech-recognition engine. */
sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object SpeechBegin : SpeechEvent
    /** Input level (dB-ish, engine specific) — used only for the live microphone indicator. */
    data class Level(val rms: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val kind: SpeechErrorKind, val code: Int, val detail: String = "") : SpeechEvent
}

data class EngineRequest(
    val languageTag: String,
    /** Ask for on-device recognition (privacy / offline). */
    val preferOffline: Boolean,
    /** Fail instead of falling back to an online recogniser. */
    val offlineOnly: Boolean,
    /** Flattened ComponentName of a phone service the user picked (null = automatic order). */
    val preferredService: String? = null,
)

/**
 * Speech recognition abstraction. One [start] = one recognition attempt (an utterance); continuous
 * dictation is built on top of it by [DictationController], which restarts the engine as needed.
 * Implementations must deliver events on the main thread.
 */
interface SpeechEngine {
    val events: SharedFlow<SpeechEvent>
    /** Human-readable engine name currently in use (shown in the UI). */
    val label: String
    fun isAvailable(): Boolean
    fun start(request: EngineRequest)
    /** Stop listening but deliver the result of the current utterance. */
    fun stop()
    /** Abort the current utterance without a result. */
    fun cancel()
    fun release()
    /** True while audio is still being turned into text (cloud engines); Finish waits for it. */
    val busy: Boolean get() = false
    /** Short status for the UI, e.g. "Gemini লিখছে…" (null = nothing to show). */
    val note: StateFlow<String?> get() = NO_NOTE

    companion object { val NO_NOTE: StateFlow<String?> = MutableStateFlow(null) }
}
