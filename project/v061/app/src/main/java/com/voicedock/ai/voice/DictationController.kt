package com.voicedock.ai.voice

import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftStatus
import com.voicedock.ai.data.repositories.DraftStore
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.settings.EnginePreference
import com.voicedock.ai.text.LocalFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Snapshot rendered by the overlay panel, the in-app workspace and the notification. */
data class DictationUi(
    val phase: DictationPhase = DictationPhase.IDLE,
    val committed: String = "",
    val partial: String = "",
    /** Cleaned text shown in REVIEW (editable). */
    val draft: String = "",
    val draftId: String? = null,
    val message: String? = null,
    val engine: String = "",
    val level: Float = 0f,
    val restarts: Int = 0,
    /** Engine status such as "Gemini লিখছে…". */
    val note: String? = null,
) {
    val live get() = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")
    val micOn get() = phase == DictationPhase.LISTENING || phase == DictationPhase.TEMP_PAUSE
}

/**
 * Continuous dictation = [DictationSession] (logic) + [SpeechEngine] (recognition attempts) +
 * [DraftStore] (every committed result is persisted immediately, so a crash or an interruption
 * never loses text). Runs on the main thread scope.
 */
class DictationController(
    private val engine: SpeechEngine,
    private val store: DraftStore,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
    /** Wait this long after Finish for the last final result before cleaning up. */
    private val finishGraceMs: Long = 1_500,
) {
    private val session = DictationSession(settings().sessionConfig())
    private val _ui = MutableStateFlow(DictationUi())
    val ui: StateFlow<DictationUi> = _ui.asStateFlow()

    private var draftId: String? = null
    private var createdAt = 0L
    private var reviewText: String = ""
    private var restartJob: Job? = null
    private var tickJob: Job? = null
    private var finishJob: Job? = null
    private val saveLock = Mutex()
    private val segJson = Json { ignoreUnknownKeys = true }

    /** Called after Finish when auto-insert is on (set by the app: overlay inserts, activity ignores). */
    var onReviewReady: ((String) -> Unit)? = null
    /** One-shot: the VoiceDock keyboard asked for this Finish and inserts the result itself (overrides auto-insert). */
    @Volatile var finishHandler: ((String) -> Unit)? = null

    init {
        scope.launch {
            engine.events.collect { handle(it) }
        }
        scope.launch { engine.note.collect { n -> _ui.value = _ui.value.copy(note = n) } }
    }

    // ------------------------------------------------------------------ public API

    fun start() = act { session.config = settings().sessionConfig(); if (session.canResume || session.isActive) session.resume(clock()) else { newDraft(); session.start(clock()) } }
    fun startNew() = act { session.config = settings().sessionConfig(); if (session.isActive) session.cancel().also { run(it) }; newDraft(); reviewText = ""; session.start(clock()) }
    fun resume() = act { session.config = settings().sessionConfig(); session.resume(clock()) }
    fun pause() = act { session.pause() }
    fun cancel() = act { session.cancel() }
    fun clear() = act { reviewText = ""; session.clear() }

    fun finish() {
        val effects = session.finish()
        run(effects)
        publish()
        finishJob?.cancel()
        finishJob = scope.launch {
            delay(finishGraceMs)  // a last final result may still arrive after stopListening()
            var waited = 0L      // cloud engines may still be transcribing the last clip
            while (engine.busy && waited < 45_000) { delay(250); waited += 250 }
            completeFinish()
        }
    }

    /** User edited the reviewed text. */
    fun editDraft(text: String) { reviewText = text; run(session.replaceText(text)); publish() }

    fun markInserted(destination: String) = act { session.markInserted().also { lastDestination = destination } }
    fun markSaved() = act { session.markSaved() }

    /** Restore an unfinished draft after app restart: shown as PAUSED/REVIEW, microphone stays off. */
    suspend fun restoreUnfinished(): Boolean {
        if (draftId != null) return false
        val d = store.latestUnfinished() ?: return false
        val segs = runCatching { segJson.decodeFromString(ListSerializer(Segment.serializer()), d.segmentsJson) }.getOrElse { listOf(Segment(d.rawText)) }
        draftId = d.id; createdAt = d.createdAt
        session.restore(segs)
        if (d.status == DraftStatus.REVIEW) { reviewText = d.formattedText; session.finish(); session.completeProcessing() }
        publish()
        return true
    }

    /** Adds text recovered later (e.g. retried audio clips) to the current draft; persisted immediately. */
    fun appendRecovered(texts: List<String>) {
        if (texts.isEmpty()) return
        if (draftId == null) newDraft()
        val joined = (session.text + " " + texts.joinToString(" ")).trim()
        run(session.replaceText(joined))
        if (session.phase == DictationPhase.REVIEW) reviewText = (reviewText + " " + texts.joinToString(" ")).trim()
        persist(); publish()
    }

    fun currentText(): String = if (session.phase == DictationPhase.REVIEW || session.phase == DictationPhase.INSERTED || session.phase == DictationPhase.SAVED) reviewText.ifBlank { session.text } else session.liveText

    private var lastDestination = ""

    // ------------------------------------------------------------------ internals

    private fun act(block: () -> List<Effect>) { run(block()); publish() }

    private fun newDraft() { draftId = UUID.randomUUID().toString(); createdAt = clock(); lastDestination = "" }

    private fun completeFinish() {
        run(session.completeProcessing())
        val raw = session.text
        val s = settings()
        reviewText = if (s.autoCleanup) LocalFormatter.format(session.segments, LocalFormatter.Options(spokenCommands = s.spokenCommands)) else raw
        persist()
        publish()
        val h = finishHandler
        finishHandler = null
        if (h != null) { if (reviewText.isNotBlank()) h(reviewText); return }
        if (s.autoInsert && reviewText.isNotBlank()) onReviewReady?.invoke(reviewText)
    }

    private fun handle(e: SpeechEvent) {
        val now = clock()
        val effects = when (e) {
            SpeechEvent.Ready -> emptyList()
            SpeechEvent.SpeechBegin -> { session.onSpeechActivity(now); emptyList() }
            is SpeechEvent.Level -> { _ui.value = _ui.value.copy(level = e.rms); if (e.rms > 6f) session.onSpeechActivity(now); emptyList() }
            is SpeechEvent.Partial -> { session.onPartial(e.text, now); emptyList() }
            is SpeechEvent.Final -> session.onFinal(e.text, now)
            is SpeechEvent.Error -> session.onError(e.kind, now)
        }
        run(effects)
        publish()
    }

    private fun run(effects: List<Effect>) {
        for (ef in effects) when (ef) {
            Effect.StartEngine -> { restartJob?.cancel(); engine.start(request()); ensureTicker() }
            is Effect.RestartEngine -> {
                restartJob?.cancel()
                restartJob = scope.launch {
                    if (ef.delayMs > 0) delay(ef.delayMs)
                    if (session.isActive) engine.start(request())
                }
            }
            Effect.StopEngine -> { restartJob?.cancel(); engine.stop() }
            Effect.CancelEngine -> { restartJob?.cancel(); engine.cancel() }
            Effect.Persist -> persist()
            is Effect.SnapshotBeforeClear -> snapshot(ef.text)
        }
    }

    private fun request(): EngineRequest {
        val s = settings()
        return EngineRequest(
            languageTag = s.language.tag,
            preferOffline = s.engine == EnginePreference.ON_DEVICE || s.engine == EnginePreference.AUTO,
            offlineOnly = s.engine == EnginePreference.ON_DEVICE,
            preferredService = s.phoneService,
        ).let { if (s.engine != EnginePreference.ON_DEVICE) it.copy(preferOffline = false) else it }
    }

    private fun ensureTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                if (!session.isActive) break
                val before = session.phase
                run(session.onTick(clock()))
                if (session.phase != before) publish()
            }
        }
    }

    private fun status(): DraftStatus = when (session.phase) {
        DictationPhase.REVIEW, DictationPhase.PROCESSING -> DraftStatus.REVIEW
        DictationPhase.INSERTED -> DraftStatus.INSERTED
        DictationPhase.SAVED -> DraftStatus.SAVED
        DictationPhase.IDLE -> DraftStatus.CANCELLED
        else -> DraftStatus.ACTIVE
    }

    private fun persist() {
        val id = draftId ?: return
        val segs = session.segments
        val raw = session.text
        val st = status()
        if (raw.isBlank() && reviewText.isBlank() && st == DraftStatus.ACTIVE) return
        val s = settings()
        val d = DictationDraft(
            id = id, createdAt = createdAt, updatedAt = clock(), rawText = raw,
            formattedText = reviewText.ifBlank { raw }, segmentsJson = segJson.encodeToString(ListSerializer(Segment.serializer()), segs),
            status = st, language = s.language.tag, engine = engine.label, destination = lastDestination,
        )
        scope.launch { saveLock.withLock { store.save(d) } }
    }

    private fun snapshot(text: String) {
        val now = clock()
        val d = DictationDraft(UUID.randomUUID().toString(), createdAt.takeIf { it > 0 } ?: now, now, text, text,
            segJson.encodeToString(ListSerializer(Segment.serializer()), listOf(Segment(text))), DraftStatus.CLEARED, settings().language.tag, engine.label)
        scope.launch { saveLock.withLock { store.save(d) } }
    }

    private fun publish() {
        _ui.value = _ui.value.copy(
            phase = session.phase, committed = session.text, partial = session.partial,
            draft = if (session.phase == DictationPhase.REVIEW || session.phase == DictationPhase.INSERTED || session.phase == DictationPhase.SAVED) reviewText else "",
            draftId = draftId, message = session.message, engine = engine.label, restarts = session.restarts,
        )
    }
}
