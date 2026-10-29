package com.voicedock.ai

import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftStatus
import com.voicedock.ai.data.repositories.DraftStore
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.voice.DictationController
import com.voicedock.ai.voice.DictationPhase
import com.voicedock.ai.voice.EngineRequest
import com.voicedock.ai.voice.SpeechEngine
import com.voicedock.ai.voice.SpeechErrorKind
import com.voicedock.ai.voice.SpeechEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEngine : SpeechEngine {
    val flow = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = flow
    override val label = "fake"
    var starts = 0; var stops = 0; var cancels = 0
    var lastRequest: EngineRequest? = null
    override fun isAvailable() = true
    override fun start(request: EngineRequest) { starts++; lastRequest = request }
    override fun stop() { stops++ }
    override fun cancel() { cancels++ }
    override fun release() {}
    fun emit(e: SpeechEvent) { check(flow.tryEmit(e)) }
}

class MemoryStore : DraftStore {
    val rows = MutableStateFlow<Map<String, DictationDraft>>(emptyMap())
    override suspend fun save(draft: DictationDraft) { rows.value = rows.value + (draft.id to draft) }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun latestUnfinished() = rows.value.values.filter { it.status == DraftStatus.ACTIVE || it.status == DraftStatus.REVIEW }.maxByOrNull { it.updatedAt }
    override fun observeRecent(limit: Int): Flow<List<DictationDraft>> = rows.map { it.values.sortedByDescending { d -> d.updatedAt }.take(limit) }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {
    private fun TestScope.controller(engine: FakeEngine, store: DraftStore, settings: AppSettings = AppSettings()) =
        DictationController(engine, store, { settings }, backgroundScope, clock = { testScheduler.currentTime }).also { runCurrent() }

    @Test fun dictatePauseCoughResumeFinish_noTextLost() = runTest {
        val engine = FakeEngine(); val store = MemoryStore()
        val c = controller(engine, store)
        c.start(); runCurrent()
        assertEquals(1, engine.starts)
        assertEquals(DictationPhase.LISTENING, c.ui.value.phase)

        engine.emit(SpeechEvent.Partial("আমি আজ")); runCurrent()
        assertEquals("আমি আজ", c.ui.value.partial)
        engine.emit(SpeechEvent.Final("আমি আজ অফিসে যাব")); runCurrent()
        assertEquals(2, engine.starts) // restarted for the next utterance
        // the draft is already in the database
        assertEquals("আমি আজ অফিসে যাব", store.rows.value.values.single().rawText)

        // cough / silence → recogniser ends with "no match" → restart, nothing lost
        advanceTimeBy(3_000)
        engine.emit(SpeechEvent.Error(SpeechErrorKind.SILENCE, 7)); runCurrent()
        assertEquals(3, engine.starts)

        c.pause(); runCurrent()
        assertEquals(DictationPhase.PAUSED, c.ui.value.phase)
        assertEquals(1, engine.stops)
        c.resume(); runCurrent()
        engine.emit(SpeechEvent.Final("বিকেলে মিটিং আছে")); runCurrent()

        c.finish(); runCurrent()
        assertEquals(DictationPhase.PROCESSING, c.ui.value.phase)
        // the last words arrive after stopListening()
        engine.emit(SpeechEvent.Final("কেউ কি আসবে")); runCurrent()
        advanceTimeBy(1_600); runCurrent()
        assertEquals(DictationPhase.REVIEW, c.ui.value.phase)
        assertEquals("আমি আজ অফিসে যাব। বিকেলে মিটিং আছে। কেউ কি আসবে?", c.ui.value.draft)
        val saved = store.rows.value.values.single()
        assertEquals(DraftStatus.REVIEW, saved.status)
        assertEquals("আমি আজ অফিসে যাব বিকেলে মিটিং আছে কেউ কি আসবে", saved.rawText)
    }

    @Test fun networkErrorsBackOffAndInterruptWithTextKept() = runTest {
        val engine = FakeEngine(); val store = MemoryStore()
        val c = controller(engine, store)
        c.start(); runCurrent()
        engine.emit(SpeechEvent.Final("hello world")); runCurrent()
        val startsBefore = engine.starts
        engine.emit(SpeechEvent.Error(SpeechErrorKind.NETWORK, 2)); runCurrent()
        assertEquals(startsBefore, engine.starts) // waits 1 s before retrying
        advanceTimeBy(1_001); runCurrent()
        assertEquals(startsBefore + 1, engine.starts)
        repeat(3) { engine.emit(SpeechEvent.Error(SpeechErrorKind.NETWORK, 2)); runCurrent(); advanceTimeBy(5_000); runCurrent() }
        assertEquals(DictationPhase.INTERRUPTED, c.ui.value.phase)
        assertEquals("hello world", c.ui.value.committed)
        assertTrue(c.ui.value.message!!.isNotBlank())
    }

    @Test fun clearSavesSnapshotAndCancelKeepsHistory() = runTest {
        val engine = FakeEngine(); val store = MemoryStore()
        val c = controller(engine, store)
        c.start(); runCurrent()
        engine.emit(SpeechEvent.Final("first draft text")); runCurrent()
        c.clear(); runCurrent()
        assertTrue(store.rows.value.values.any { it.status == DraftStatus.CLEARED && it.rawText == "first draft text" })
        engine.emit(SpeechEvent.Final("second text")); runCurrent()
        c.cancel(); runCurrent()
        assertEquals(1, engine.cancels)
        assertTrue(store.rows.value.values.any { it.status == DraftStatus.CANCELLED && it.rawText == "second text" })
    }

    @Test fun longSilenceTurnsMicOffButKeepsDraft() = runTest {
        val engine = FakeEngine(); val store = MemoryStore()
        val c = controller(engine, store, AppSettings(longInactivityMin = 1))
        c.start(); runCurrent()
        engine.emit(SpeechEvent.Final("keep this")); runCurrent()
        advanceTimeBy(61_500); runCurrent()
        assertEquals(DictationPhase.WAITING, c.ui.value.phase)
        assertTrue(engine.stops >= 1)
        assertEquals("keep this", c.ui.value.committed)
    }

    @Test fun unfinishedDraftIsRestoredPausedAfterRestart() = runTest {
        val engine = FakeEngine(); val store = MemoryStore()
        val c1 = controller(engine, store)
        c1.start(); runCurrent()
        engine.emit(SpeechEvent.Final("text before crash")); runCurrent()

        // "process restart": new controller, same database
        val engine2 = FakeEngine()
        val c2 = controller(engine2, store)
        assertTrue(c2.restoreUnfinished())
        assertEquals(DictationPhase.PAUSED, c2.ui.value.phase)
        assertEquals("text before crash", c2.ui.value.committed)
        assertEquals(0, engine2.starts) // never starts listening by itself
    }

    @Test fun onDeviceSettingRequestsOfflineOnly() = runTest {
        val engine = FakeEngine()
        val c = controller(engine, MemoryStore(), AppSettings(engine = com.voicedock.ai.settings.EnginePreference.ON_DEVICE))
        c.start(); runCurrent()
        assertEquals(true, engine.lastRequest!!.offlineOnly)
        assertEquals("bn-BD", engine.lastRequest!!.languageTag)
    }
}
