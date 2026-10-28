package com.voicedock.ai

import com.voicedock.ai.voice.DictationPhase
import com.voicedock.ai.voice.DictationSession
import com.voicedock.ai.voice.Effect
import com.voicedock.ai.voice.Segment
import com.voicedock.ai.voice.SessionConfig
import com.voicedock.ai.voice.SpeechErrorKind
import com.voicedock.ai.voice.TranscriptMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMergerTest {
    @Test fun overlapAtRestartIsDropped() {
        // the recogniser restarted and repeated the last two words
        assertEquals("কাল যাব", TranscriptMerger.newPart("আজ বাজারে গিয়েছিলাম তারপর আমি", "তারপর আমি কাল যাব"))
    }

    @Test fun singleWordRepeatIsKept() {
        // "very very" can be intentional → a one-word overlap is not removed
        assertEquals("very good", TranscriptMerger.newPart("this is very", "very good"))
    }

    @Test fun finalReplacesProvisionalPartial() {
        var s = TranscriptMerger.keepPartial(emptyList(), "hello wor")
        assertTrue(s.single().provisional)
        s = TranscriptMerger.addFinal(s, "hello world")
        assertEquals(listOf(Segment("hello world")), s)
    }

    @Test fun duplicateFinalNotAppended() {
        var s = TranscriptMerger.addFinal(emptyList(), "আমার নাম রহিম")
        s = TranscriptMerger.addFinal(s, "আমার নাম রহিম")
        assertEquals("আমার নাম রহিম", TranscriptMerger.plain(s))
    }
}

class DictationSessionTest {
    private fun cfg() = SessionConfig(shortPauseMs = 2_500, longInactivityMs = 60_000, maxTransientRetries = 3, rapidFailureWindowMs = 4_000, rapidFailureLimit = 6)

    @Test fun silenceDuringDictationRestartsAndKeepsText() {
        val s = DictationSession(cfg())
        assertEquals(listOf(Effect.StartEngine), s.start(0))
        s.onPartial("আমি আজ", 500)
        s.onFinal("আমি আজ অফিসে যাব", 1_000)
        // user coughs / thinks → no match
        val eff = s.onError(SpeechErrorKind.SILENCE, 4_000)
        assertTrue(eff.any { it is Effect.RestartEngine })
        assertEquals(DictationPhase.TEMP_PAUSE, s.phase)
        s.onSpeechActivity(5_000)
        assertEquals(DictationPhase.LISTENING, s.phase)
        s.onFinal("তারপর বাসায় ফিরব", 6_000)
        assertEquals("আমি আজ অফিসে যাব তারপর বাসায় ফিরব", s.text)
    }

    @Test fun partialIsKeptWhenEngineEndsWithoutFinal() {
        val s = DictationSession(cfg()); s.start(0)
        s.onPartial("meeting at five", 800)
        val eff = s.onError(SpeechErrorKind.SILENCE, 1_000)
        assertTrue(eff.contains(Effect.Persist))
        assertEquals("meeting at five", s.text)
    }

    @Test fun pauseResumeFinishFlow() {
        val s = DictationSession(cfg()); s.start(0)
        s.onFinal("first part", 1_000)
        assertEquals(listOf(Effect.StopEngine, Effect.Persist), s.pause())
        assertEquals(DictationPhase.PAUSED, s.phase)
        // late final after pause is still accepted
        s.onFinal("first part again later", 1_200)
        assertTrue(s.text.startsWith("first part"))
        s.resume(10_000)
        assertEquals(DictationPhase.LISTENING, s.phase)
        s.finish()
        assertEquals(DictationPhase.PROCESSING, s.phase)
        s.completeProcessing()
        assertEquals(DictationPhase.REVIEW, s.phase)
    }

    @Test fun longInactivityGoesToWaitingAndKeepsText() {
        val s = DictationSession(cfg()); s.start(0)
        s.onFinal("keep me", 1_000)
        val eff = s.onTick(70_000)
        assertEquals(DictationPhase.WAITING, s.phase)
        assertTrue(eff.contains(Effect.StopEngine))
        assertEquals("keep me", s.text)
        assertTrue(s.canResume)
    }

    @Test fun transientErrorsBackOffThenInterrupt() {
        val s = DictationSession(cfg()); s.start(0)
        val delays = (1..3).map { (s.onError(SpeechErrorKind.NETWORK, it * 10_000L).filterIsInstance<Effect.RestartEngine>().single()).delayMs }
        assertEquals(listOf(1000L, 2000L, 4000L), delays)
        s.onError(SpeechErrorKind.NETWORK, 50_000)
        assertEquals(DictationPhase.INTERRUPTED, s.phase)
        assertTrue(s.message!!.isNotBlank())
    }

    @Test fun rapidFailureLoopIsStopped() {
        val s = DictationSession(cfg()); s.start(0)
        repeat(5) { s.onError(SpeechErrorKind.BUSY, 100L + it * 100) }
        assertTrue(s.isActive)
        s.onError(SpeechErrorKind.BUSY, 700)
        assertEquals(DictationPhase.INTERRUPTED, s.phase)
    }

    @Test fun cancelKeepsTextAndPersists() {
        val s = DictationSession(cfg()); s.start(0)
        s.onFinal("do not lose", 500)
        val eff = s.cancel()
        assertTrue(eff.contains(Effect.CancelEngine) && eff.contains(Effect.Persist))
        assertEquals(DictationPhase.IDLE, s.phase)
        assertEquals("do not lose", s.text)
    }

    @Test fun clearSnapshotsFirst() {
        val s = DictationSession(cfg()); s.start(0)
        s.onFinal("snapshot this", 500)
        val eff = s.clear()
        assertEquals(Effect.SnapshotBeforeClear("snapshot this"), eff.first())
        assertEquals("", s.text)
        assertTrue(s.isActive)
    }

    @Test fun errorsAfterPauseAreIgnored() {
        val s = DictationSession(cfg()); s.start(0); s.pause()
        assertTrue(s.onError(SpeechErrorKind.SILENCE, 100).isEmpty())
        assertEquals(DictationPhase.PAUSED, s.phase)
    }

    @Test fun restoreNeverAutoListens() {
        val s = DictationSession(cfg())
        s.restore(listOf(Segment("saved text")))
        assertEquals(DictationPhase.PAUSED, s.phase)
        assertFalse(s.micOn)
    }
}
