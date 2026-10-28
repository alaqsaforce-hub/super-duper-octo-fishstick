package com.voicedock.ai.voice

/**
 * Explicit dictation state machine (pure Kotlin, unit tested).
 *
 *   IDLE → LISTENING ⇄ TEMP_PAUSE → (PAUSED | WAITING | INTERRUPTED) → LISTENING …
 *        → PROCESSING → REVIEW → INSERTED | SAVED
 *
 * - TEMP_PAUSE: the user is silent (thinking, coughing, drinking water). The session stays open and
 *   the recogniser is restarted; nothing is lost.
 * - PAUSED: the user tapped Pause. Microphone off, draft kept.
 * - WAITING: long inactivity (configurable). Microphone off to save battery, draft kept, one tap resumes.
 * - INTERRUPTED: the system/engine stopped listening (network, microphone busy, permission, language).
 *   We show this honestly instead of pretending to listen; the draft is kept and Resume is offered.
 */
enum class DictationPhase { IDLE, LISTENING, TEMP_PAUSE, PAUSED, WAITING, INTERRUPTED, PROCESSING, REVIEW, INSERTED, SAVED }

enum class SpeechErrorKind {
    /** No speech / no match — normal during pauses. */
    SILENCE,
    /** Recogniser busy or client-side hiccup after a restart. */
    BUSY,
    NETWORK, SERVER, RATE_LIMIT,
    AUDIO, PERMISSION, LANGUAGE, UNAVAILABLE,
    /** Cloud engine: API key missing/invalid or cloud use not allowed. */
    AUTH,
    OTHER;

    /** Errors that are part of normal continuous dictation and simply mean "start the next recognition". */
    val restartable get() = this == SILENCE || this == BUSY
    /** Temporary failures that may recover after a short backoff (bounded). */
    val transient get() = this == NETWORK || this == SERVER || this == RATE_LIMIT
}

/** What the controller must do after an event. */
sealed interface Effect {
    data object StartEngine : Effect
    data class RestartEngine(val delayMs: Long) : Effect
    /** stopListening(): lets a final result for the current utterance still arrive. */
    data object StopEngine : Effect
    /** cancel(): discards the in-flight utterance (used only for Cancel). */
    data object CancelEngine : Effect
    data object Persist : Effect
    /** Save a recoverable copy before clearing the text. */
    data class SnapshotBeforeClear(val text: String) : Effect
}

data class SessionConfig(
    /** Silence (ms) after which the indicator shows TEMP_PAUSE (session stays open). */
    val shortPauseMs: Long = 2_500,
    /** null = keep listening while the session is open; otherwise go to WAITING after this much silence. */
    val longInactivityMs: Long? = 5 * 60_000L,
    /** Bounded retries for NETWORK/SERVER/RATE_LIMIT before showing INTERRUPTED. */
    val maxTransientRetries: Int = 3,
    /** Many restartable errors without any speech in this window = the engine is stuck → INTERRUPTED. */
    val rapidFailureWindowMs: Long = 4_000,
    val rapidFailureLimit: Int = 6,
)

class DictationSession(var config: SessionConfig = SessionConfig()) {
    var phase: DictationPhase = DictationPhase.IDLE; private set
    var segments: List<Segment> = emptyList(); private set
    var partial: String = ""; private set
    var message: String? = null; private set
    var lastErrorKind: SpeechErrorKind? = null; private set
    var lastSpeechAt: Long = 0; private set
    var restarts: Int = 0; private set
    /** Set when the text changed since the last Persist effect. */
    private var transientRetries = 0
    private val recentFailures = ArrayDeque<Long>()

    val text: String get() = TranscriptMerger.plain(segments)
    /** What the user sees right now: committed text + live partial. */
    val liveText: String get() = listOf(text, partial.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    val isActive get() = phase == DictationPhase.LISTENING || phase == DictationPhase.TEMP_PAUSE
    val micOn get() = isActive
    val canResume get() = phase == DictationPhase.PAUSED || phase == DictationPhase.WAITING ||
        phase == DictationPhase.INTERRUPTED || phase == DictationPhase.REVIEW

    /** Restore an unfinished draft after an app restart (phase becomes PAUSED, never auto-listening). */
    fun restore(saved: List<Segment>) {
        segments = saved; partial = ""; phase = if (saved.isEmpty()) DictationPhase.IDLE else DictationPhase.PAUSED
    }

    // ------------------------------------------------------------------ user actions

    /** Start a NEW dictation (fresh text) — from IDLE / INSERTED / SAVED. From other states it resumes. */
    fun start(now: Long): List<Effect> {
        if (isActive) return emptyList()
        if (canResume) return resume(now)
        segments = emptyList(); partial = ""; message = null; lastErrorKind = null
        restarts = 0; transientRetries = 0; recentFailures.clear()
        phase = DictationPhase.LISTENING; lastSpeechAt = now
        return listOf(Effect.StartEngine)
    }

    fun resume(now: Long): List<Effect> {
        if (!canResume) return if (phase == DictationPhase.IDLE || phase == DictationPhase.INSERTED || phase == DictationPhase.SAVED) start(now) else emptyList()
        phase = DictationPhase.LISTENING; message = null; lastErrorKind = null
        transientRetries = 0; recentFailures.clear(); lastSpeechAt = now
        return listOf(Effect.StartEngine, Effect.Persist)
    }

    fun pause(): List<Effect> {
        if (!isActive) return emptyList()
        keepPartial()
        phase = DictationPhase.PAUSED
        return listOf(Effect.StopEngine, Effect.Persist)
    }

    /** Finish: stop listening and wait for the last final result; controller then calls [completeProcessing]. */
    fun finish(): List<Effect> {
        if (phase == DictationPhase.IDLE || phase == DictationPhase.PROCESSING || phase == DictationPhase.INSERTED || phase == DictationPhase.SAVED) return emptyList()
        val wasActive = isActive
        phase = DictationPhase.PROCESSING
        return if (wasActive) listOf(Effect.StopEngine, Effect.Persist) else listOf(Effect.Persist)
    }

    fun completeProcessing(): List<Effect> {
        if (phase != DictationPhase.PROCESSING) return emptyList()
        keepPartial()
        phase = DictationPhase.REVIEW
        return listOf(Effect.Persist)
    }

    /**
     * Cancel = stop the session. The text is NOT destroyed: it stays in history as a cancelled draft
     * (documented behaviour), so an accidental tap never loses work.
     */
    fun cancel(): List<Effect> {
        if (phase == DictationPhase.IDLE) return emptyList()
        val effects = mutableListOf<Effect>()
        if (isActive) effects += Effect.CancelEngine
        keepPartial()
        effects += Effect.Persist
        phase = DictationPhase.IDLE
        return effects
    }

    /** Clear = empty the current text, keeping listening state. A recoverable snapshot is saved first. */
    fun clear(): List<Effect> {
        val snapshot = liveText
        segments = emptyList(); partial = ""
        return if (snapshot.isBlank()) listOf(Effect.Persist) else listOf(Effect.SnapshotBeforeClear(snapshot), Effect.Persist)
    }

    /** User edited the draft text in the review editor. */
    fun replaceText(newText: String): List<Effect> {
        segments = if (newText.isBlank()) emptyList() else listOf(Segment(newText.trim()))
        partial = ""
        return listOf(Effect.Persist)
    }

    fun markInserted(): List<Effect> { phase = DictationPhase.INSERTED; return listOf(Effect.Persist) }
    fun markSaved(): List<Effect> { phase = DictationPhase.SAVED; return listOf(Effect.Persist) }

    // ------------------------------------------------------------------ engine events

    fun onSpeechActivity(now: Long) {
        lastSpeechAt = now
        if (phase == DictationPhase.TEMP_PAUSE) phase = DictationPhase.LISTENING
    }

    fun onPartial(text: String, now: Long) {
        if (!isActive && phase != DictationPhase.PROCESSING && phase != DictationPhase.PAUSED) return
        if (text.isNotBlank()) { partial = text; onSpeechActivity(now); recentFailures.clear(); transientRetries = 0 }
    }

    /**
     * A final result. Accepted while active AND shortly after Pause/Finish (stopListening still delivers
     * the last utterance) so the end of a sentence is never lost.
     */
    fun onFinal(text: String, now: Long): List<Effect> {
        val accept = isActive || phase == DictationPhase.PAUSED || phase == DictationPhase.PROCESSING ||
            phase == DictationPhase.WAITING || phase == DictationPhase.INTERRUPTED
        if (!accept) return emptyList()
        val before = segments
        segments = TranscriptMerger.addFinal(segments, text)
        partial = ""
        if (text.isNotBlank()) { onSpeechActivity(now); recentFailures.clear(); transientRetries = 0 }
        val effects = mutableListOf<Effect>()
        if (segments != before) effects += Effect.Persist
        if (isActive) { restarts++; effects += Effect.RestartEngine(0) }
        return effects
    }

    /** The recogniser stopped (error or end without result). */
    fun onError(kind: SpeechErrorKind, now: Long): List<Effect> {
        lastErrorKind = kind
        if (!isActive) return emptyList() // after Pause/Finish/Cancel the engine reports its end — ignore
        val changed = keepPartial()
        val effects = mutableListOf<Effect>()
        if (changed) effects += Effect.Persist

        if (kind.restartable) {
            recentFailures.addLast(now)
            while (recentFailures.isNotEmpty() && now - recentFailures.first() > config.rapidFailureWindowMs) recentFailures.removeFirst()
            if (recentFailures.size >= config.rapidFailureLimit) {
                return effects + interrupt("স্পিচ ইঞ্জিন বারবার থেমে যাচ্ছে। আবার শুরু করতে ‘চালিয়ে যান’ চাপুন।")
            }
            val silentFor = now - lastSpeechAt
            val limit = config.longInactivityMs
            if (limit != null && silentFor >= limit) {
                phase = DictationPhase.WAITING
                message = "অনেকক্ষণ কথা নেই — মাইক বন্ধ রাখা হলো। লেখা সংরক্ষিত আছে।"
                return effects + listOf(Effect.StopEngine, Effect.Persist)
            }
            if (silentFor >= config.shortPauseMs) phase = DictationPhase.TEMP_PAUSE
            restarts++
            return effects + Effect.RestartEngine(if (kind == SpeechErrorKind.BUSY) 400L else 0L)
        }
        if (kind.transient && transientRetries < config.maxTransientRetries) {
            transientRetries++
            restarts++
            // exponential backoff: 1 s, 2 s, 4 s …
            return effects + Effect.RestartEngine(1000L shl (transientRetries - 1))
        }
        return effects + interrupt(messageFor(kind))
    }

    /** Called by the controller's ticker so the indicator reflects silence even while the engine waits. */
    fun onTick(now: Long): List<Effect> {
        if (!isActive) return emptyList()
        val silentFor = now - lastSpeechAt
        val limit = config.longInactivityMs
        if (limit != null && silentFor >= limit) {
            keepPartial()
            phase = DictationPhase.WAITING
            message = "অনেকক্ষণ কথা নেই — মাইক বন্ধ রাখা হলো। লেখা সংরক্ষিত আছে।"
            return listOf(Effect.StopEngine, Effect.Persist)
        }
        if (phase == DictationPhase.LISTENING && silentFor >= config.shortPauseMs) phase = DictationPhase.TEMP_PAUSE
        return emptyList()
    }

    /** Audio focus lost / microphone taken by a call: stop honestly, keep text. */
    fun onSystemInterruption(reason: String): List<Effect> {
        if (!isActive) return emptyList()
        keepPartial()
        return interrupt(reason)
    }

    // ------------------------------------------------------------------ helpers

    private fun interrupt(msg: String): List<Effect> {
        phase = DictationPhase.INTERRUPTED
        message = msg
        return listOf(Effect.StopEngine, Effect.Persist)
    }

    /** Commits an unfinished partial as provisional text. Returns true if the text changed. */
    private fun keepPartial(): Boolean {
        if (partial.isBlank()) return false
        val before = segments
        segments = TranscriptMerger.keepPartial(segments, partial)
        partial = ""
        return segments != before
    }

    companion object {
        fun messageFor(kind: SpeechErrorKind): String = when (kind) {
            SpeechErrorKind.NETWORK -> "ইন্টারনেট সংযোগ নেই বা দুর্বল। লেখা সংরক্ষিত আছে — সংযোগ ফিরলে ‘চালিয়ে যান’ চাপুন।"
            SpeechErrorKind.SERVER -> "স্পিচ সার্ভারে সমস্যা। লেখা সংরক্ষিত আছে — একটু পরে ‘চালিয়ে যান’ চাপুন।"
            SpeechErrorKind.RATE_LIMIT -> "সার্ভিসের ফ্রি সীমা বা অনুরোধের হার পূর্ণ (যেমন Gemini ফ্রি কোটা)। লেখা সংরক্ষিত আছে — একটু পরে ‘চালিয়ে যান’ চাপুন।"
            SpeechErrorKind.AUDIO -> "মাইক্রোফোন পাওয়া যাচ্ছে না (অন্য অ্যাপ বা কল ব্যবহার করছে)।"
            SpeechErrorKind.PERMISSION -> "মাইক্রোফোনের অনুমতি নেই। অনুমতি দিয়ে আবার চেষ্টা করুন।"
            SpeechErrorKind.LANGUAGE -> "ফোনের কোনো ভয়েস সার্ভিস এই ভাষা নিচ্ছে না। সেটিংস → স্পিচ ইঞ্জিন থেকে Google ভয়েস বা Gemini AI চালু করুন।"
            SpeechErrorKind.AUTH -> "Gemini API key নেই/ভুল, অথবা ক্লাউডে পাঠানোর অনুমতি বন্ধ। সেটিংস → Gemini দেখুন।"
            SpeechErrorKind.UNAVAILABLE -> "ফোনে কোনো স্পিচ রিকগনিশন সার্ভিস পাওয়া যায়নি (যেমন Google অ্যাপ)।"
            else -> "স্পিচ রিকগনিশন থেমে গেছে। লেখা সংরক্ষিত আছে — ‘চালিয়ে যান’ চাপুন।"
        }
    }
}
