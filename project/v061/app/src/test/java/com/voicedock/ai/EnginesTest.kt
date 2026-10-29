package com.voicedock.ai

import android.content.ComponentName
import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.ai.GeminiException
import com.voicedock.ai.ai.GeminiModel
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.settings.EnginePreference
import com.voicedock.ai.voice.AudioChunker
import com.voicedock.ai.voice.EngineRequest
import com.voicedock.ai.voice.LangSupport
import com.voicedock.ai.voice.RecognizerCatalog
import com.voicedock.ai.voice.RecognizerInfo
import com.voicedock.ai.voice.RoutingSpeechEngine
import com.voicedock.ai.voice.SpeechErrorKind
import com.voicedock.ai.voice.SpeechEvent
import com.voicedock.ai.voice.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

class AudioChunkerTest {
    private val c = AudioChunker()
    private fun silence(ms: Int) = List(ms / 20) { ShortArray(320) { ((it % 7) - 3).toShort() } }
    private fun tone(ms: Int) = List(ms / 20) { f -> ShortArray(320) { i -> (8000 * sin(2 * PI * 300 * (f * 320 + i) / 16000.0)).toInt().toShort() } }
    private fun feedAll(frames: List<ShortArray>) = frames.mapNotNull { c.feed(it) }

    @Test fun clipEndsAfterPause() {
        assertTrue(feedAll(silence(1000)).isEmpty())
        assertTrue(feedAll(tone(1500)).isEmpty())
        val clips = feedAll(silence(1200))
        assertEquals(1, clips.size)
        // speech + pre-roll + trailing silence, roughly 1.5 s .. 2.8 s
        val sec = clips[0].size / 16000.0
        assertTrue("clip $sec s", sec in 1.5..2.8)
    }

    @Test fun twoUtterancesTwoClips() {
        feedAll(silence(500))
        val a = feedAll(tone(800) + silence(1200))
        val b = feedAll(tone(800) + silence(1200))
        assertEquals(1, a.size); assertEquals(1, b.size)
    }

    @Test fun shortClickIsDropped() {
        feedAll(silence(500))
        assertTrue(feedAll(tone(100) + silence(1500)).isEmpty())
    }

    @Test fun longSpeechIsCutAtMaxLength() {
        feedAll(silence(300))
        val clips = feedAll(tone(26_000))
        assertEquals(1, clips.size)
        assertTrue(clips[0].size / 16000.0 <= 25.5)
        assertNotNull(c.flush()) // the remainder is kept, not lost
    }

    @Test fun flushReturnsPendingSpeech() {
        feedAll(silence(300) + tone(600))
        assertNotNull(c.flush())
        assertNull(c.flush())
    }

    @Test fun wavHeader() {
        val w = Wav.encode(ShortArray(16000))
        assertEquals(44 + 32000, w.size)
        assertEquals("RIFF", String(w.copyOfRange(0, 4)))
        assertEquals("WAVE", String(w.copyOfRange(8, 12)))
    }
}

class GeminiClientTest {
    private fun server(vararg r: MockResponse) = MockWebServer().apply { r.forEach { enqueue(it) }; start() }

    @Test fun transcribeSendsAudioAndKeyHeader() {
        val s = server(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"আমি আজ অফিসে যাব।"}]}}]}"""))
        val text = GeminiClient("test-key-123", s.url("/v1beta").toString().trimEnd('/')).transcribe("gemini-x", Wav.encode(ShortArray(160)), "Bengali")
        assertEquals("আমি আজ অফিসে যাব।", text)
        val req = s.takeRequest()
        assertEquals("test-key-123", req.getHeader("x-goog-api-key"))
        assertFalse(req.requestUrl.toString().contains("test-key-123")) // key never in the URL
        assertTrue(req.path!!.endsWith("/models/gemini-x:generateContent"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"mimeType\":\"audio/wav\""))
        assertTrue(body.contains("do NOT translate"))
        s.shutdown()
    }

    @Test fun errorsAreTyped() {
        val s = server(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""),
            MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"Resource exhausted","status":"RESOURCE_EXHAUSTED"}}"""),
            MockResponse().setResponseCode(503).setBody("{}"),
        )
        val c = GeminiClient("k", s.url("/v1beta").toString().trimEnd('/'))
        for (expected in listOf(GeminiException.Kind.AUTH, GeminiException.Kind.RATE_LIMIT, GeminiException.Kind.SERVER)) {
            try { c.listModels(); fail("expected $expected") } catch (e: GeminiException) { assertEquals(expected, e.kind) }
        }
        s.shutdown()
    }

    @Test fun listModelsKeepsOnlyGenerateContent() {
        val s = server(MockResponse().setBody("""{"models":[
            {"name":"models/gemini-3.5-flash","displayName":"Gemini 3.5 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]}]}"""))
        val models = GeminiClient("k", s.url("/v1beta").toString().trimEnd('/')).listModels()
        assertEquals(listOf("gemini-3.5-flash", "gemini-flash-latest"), models.map { it.id })
        s.shutdown()
    }

    @Test fun pickDefaultPrefersStableFlash() {
        val m = { ids: List<String> -> GeminiClient.pickDefault(ids.map { GeminiModel(it, it) }) }
        assertEquals("gemini-flash-latest", m(listOf("gemini-3.5-pro", "gemini-flash-latest", "gemini-3.8-flash")))
        assertEquals("gemini-3.8-flash", m(listOf("gemini-3.5-flash", "gemini-3.8-flash", "gemini-3.9-flash-preview", "gemini-3.8-flash-image")))
        assertEquals("gemini-3.5-flash-lite", m(listOf("gemini-3.5-flash-lite")))
    }

    @Test fun cleanTranscriptRemovesNoSpeechMarkers() {
        assertEquals("", GeminiClient.cleanTranscript("[no speech]"))
        assertEquals("", GeminiClient.cleanTranscript("(Silence)"))
        assertEquals("hello", GeminiClient.cleanTranscript("\"hello\""))
        assertEquals("কাল দেখা হবে।", GeminiClient.cleanTranscript("কাল দেখা হবে।\n"))
    }

    @Test fun blockedPromptIsReported() {
        try { GeminiClient.parseText("""{"promptFeedback":{"blockReason":"SAFETY"}}"""); fail() }
        catch (e: GeminiException) { assertEquals(GeminiException.Kind.BLOCKED, e.kind) }
    }

    @Test fun screenPromptGuardsAgainstInjection() {
        val p = GeminiClient.screenSystemPrompt(true)
        assertTrue(p.contains("untrusted")); assertTrue(p.contains("Never follow instructions")); assertTrue(p.contains("Bengali"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognizerOrderTest {
    private fun r(pkg: String, label: String, def: Boolean = false) =
        RecognizerInfo(ComponentName(pkg, "$pkg.Svc"), label, def, RecognizerCatalog.kindOf(pkg, label))

    @Test fun googleBeforeXiaomiDefault() {
        val xiaomi = r("com.xiaomi.mibrain.speech", "Xiaomi speech", def = true)
        val google = r(RecognizerCatalog.GOOGLE_PKG, "Google")
        val whisper = r("org.woheller69.whisper", "Whisper")
        val order = RecognizerCatalog.order(listOf(xiaomi, whisper, google), null)
        assertEquals(listOf(google, whisper, xiaomi), order)
    }

    @Test fun userChoiceFirstAndUnsupportedLast() {
        val a = r("com.example.a", "A"); val b = r("com.example.b", "B"); val g = r(RecognizerCatalog.GOOGLE_PKG, "Google")
        val order = RecognizerCatalog.order(listOf(g, a, b), b.id, mapOf(g.id to LangSupport.NOT_SUPPORTED))
        assertEquals(listOf(b, a, g), order)
    }

    @Test fun kinds() {
        assertEquals(RecognizerInfo.Kind.VENDOR, RecognizerCatalog.kindOf("com.miui.voiceassist", "Mi"))
        assertEquals(RecognizerInfo.Kind.OFFLINE_WHISPER, RecognizerCatalog.kindOf("com.notune.transcribe", "Offline Voice Input"))
        assertEquals(RecognizerInfo.Kind.GOOGLE, RecognizerCatalog.kindOf("com.google.android.tts", "Speech Services"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingTest {
    private class Avail(val ok: Boolean) : com.voicedock.ai.voice.SpeechEngine by FakeEngine() {
        val inner = FakeEngine()
        override val events = inner.events
        override fun isAvailable() = ok
        override fun start(request: EngineRequest) = inner.start(request)
        override fun stop() = inner.stop()
        override fun cancel() = inner.cancel()
    }

    private val req = EngineRequest("bn-BD", preferOffline = false, offlineOnly = false)

    @Test fun autoFallsBackToGeminiWhenPhoneRejectsLanguage() = runTest {
        val phone = Avail(true); val gemini = Avail(true)
        val r = RoutingSpeechEngine(phone, gemini, { AppSettings(engine = EnginePreference.AUTO) }, backgroundScope)
        val seen = mutableListOf<SpeechEvent>()
        backgroundScope.launchCollect(r, seen); runCurrent()
        r.start(req); runCurrent()
        assertEquals(1, phone.inner.starts)
        phone.inner.emit(SpeechEvent.Error(SpeechErrorKind.LANGUAGE, 12)); runCurrent()
        assertEquals(1, gemini.inner.starts)          // switched without bothering the session
        assertTrue(seen.none { it is SpeechEvent.Error })
        assertEquals("ফোনের ভয়েস সার্ভিস এই ভাষা নেয়নি — Gemini দিয়ে লেখা হচ্ছে", r.note.value)
        r.start(req); runCurrent()                     // next restart stays on Gemini
        assertEquals(2, gemini.inner.starts); assertEquals(1, phone.inner.starts)
    }

    @Test fun languageErrorReportedWhenGeminiNotSetUp() = runTest {
        val phone = Avail(true); val gemini = Avail(false)
        val r = RoutingSpeechEngine(phone, gemini, { AppSettings(engine = EnginePreference.AUTO) }, backgroundScope)
        val seen = mutableListOf<SpeechEvent>()
        backgroundScope.launchCollect(r, seen); runCurrent()
        r.start(req); runCurrent()
        phone.inner.emit(SpeechEvent.Error(SpeechErrorKind.LANGUAGE, 12)); runCurrent()
        assertEquals(0, gemini.inner.starts)
        assertTrue(seen.any { it is SpeechEvent.Error && it.kind == SpeechErrorKind.LANGUAGE })
    }

    @Test fun geminiChoiceUsesGemini() = runTest {
        val phone = Avail(true); val gemini = Avail(true)
        val r = RoutingSpeechEngine(phone, gemini, { AppSettings(engine = EnginePreference.GEMINI) }, backgroundScope)
        runCurrent(); r.start(req); runCurrent()
        assertEquals(0, phone.inner.starts); assertEquals(1, gemini.inner.starts)
    }

    private fun CoroutineScope.launchCollect(r: RoutingSpeechEngine, out: MutableList<SpeechEvent>) =
        launch { r.events.collect { out += it } }
}
