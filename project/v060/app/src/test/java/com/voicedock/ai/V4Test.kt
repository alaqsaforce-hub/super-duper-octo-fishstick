package com.voicedock.ai

import android.text.InputType
import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.ime.BanglaPhonetic
import com.voicedock.ai.ime.VoiceDockIme
import com.voicedock.ai.settings.AppSettings
import com.voicedock.ai.settings.EnginePreference
import com.voicedock.ai.voice.DictationController
import com.voicedock.ai.voice.DictationPhase
import com.voicedock.ai.voice.EngineRequest
import com.voicedock.ai.voice.RoutingSpeechEngine
import com.voicedock.ai.voice.ScriptFix
import com.voicedock.ai.voice.SpeechEngine
import com.voicedock.ai.voice.SpeechErrorKind
import com.voicedock.ai.voice.SpeechEvent
import com.voicedock.ai.voice.WhisperModel
import com.voicedock.ai.voice.WhisperModels
import com.voicedock.ai.voice.WhisperNative
import com.voicedock.ai.voice.WhisperSpeechEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

class BanglaPhoneticTest {
    private fun t(latin: String, bn: String) = assertEquals(latin, bn, BanglaPhonetic.convert(latin))

    @Test fun basicWords() {
        t("ami", "আমি"); t("bangla", "বাংলা"); t("bhai", "ভাই"); t("khao", "খাও"); t("tOmake", "তোমাকে")
        t("o", "অ"); t("O", "ও"); t("Ami", "আমি")
    }

    @Test fun colloquialClustersStaySeparate() {
        t("amra korte pari", "আমরা করতে পারি"); t("korbe", "করবে"); t("dorkar", "দরকার")
    }

    @Test fun commonConjuncts() {
        t("kintu", "কিন্তু"); t("sundor", "সুন্দর"); t("rasta", "রাস্তা"); t("coTTogram", "চট্টগ্রাম")
        t("kormo", "কর্ম"); t("gram", "গ্রাম"); t("shrI", "শ্রী"); t("swamI", "স্বামী"); t("kkhoma", "ক্ষমা"); t("ggan", "জ্ঞান")
    }

    @Test fun forceAndBreakJoins() {
        t("kor+ta", "কর্তা"); t("kin`te", "কিনতে")
    }

    @Test fun signsDigitsPunctuation() {
        t("pa^c", "পাঁচ"); t("hoThat``", "হঠাৎ"); t("ki.", "কি।"); t("123", "১২৩"); t("$500", "৳৫০০")
        assertEquals("ক", BanglaPhonetic.hint("k")); assertEquals("আ", BanglaPhonetic.hint("a"))
    }
}

class WhisperTextTest {
    @Test fun devanagariOutputBecomesBengali() {
        // real output of ggml-small on a Bengali audiobook clip (tested on the build machine)
        assertEquals("অথা দেশ শাদিনাবর পর কুপ সবজে মেরামদ কর জবে",
            ScriptFix.devanagariToBengali("अथा देश शादिनावर पर कुप सवजे मेरामद कर जबे"))
        assertEquals("জুলৈ 5", ScriptFix.devanagariToBengali("जुलै 5"))
        assertEquals("আমি ami।", ScriptFix.devanagariToBengali("আমি ami।"))
    }

    @Test fun urduScriptIsDetectedNotDropped() {
        val urdu = "اور تدیش شادینا بار پر کوب"
        assertTrue(ScriptFix.foreignShare(urdu) > 0.5)
        assertEquals(0.0, ScriptFix.foreignShare("আমি ami"), 0.0)
        assertEquals(urdu, WhisperSpeechEngine.clean(urdu, bengali = true))   // kept, never silently discarded
    }

    @Test fun cleanRemovesNoiseTagsButKeepsRealWords() {
        assertEquals("", WhisperSpeechEngine.clean(" [BLANK_AUDIO] "))
        assertEquals("ধন্যবাদ।", WhisperSpeechEngine.clean(" ধন্যবাদ। "))
        assertEquals("Thank you.", WhisperSpeechEngine.clean("Thank you."))
        assertEquals("", WhisperSpeechEngine.clean("Thanks for watching!"))
    }

    @Test fun nativeLibraryAbsentOnJvmIsHandled() {
        assertFalse(WhisperNative.available)   // the .so is arm64-only; engines must report unavailable, not crash
    }

    @Test fun modelTableMatchesHuggingFace() {
        // sizes and SHA-256 checked against huggingface.co/ggerganov/whisper.cpp (X-Linked-Size / X-Linked-ETag)
        assertEquals(32_152_673L, WhisperModel.TINY.bytes)
        assertEquals("ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", WhisperModel.SMALL.sha256)
        assertTrue(WhisperModel.entries.all { it.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-") })
    }

    @Test fun passwordFieldsDetected() {
        assertTrue(VoiceDockIme.isPassword(InputType.TYPE_CLASS_TEXT, InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(VoiceDockIme.isPassword(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(VoiceDockIme.isPassword(InputType.TYPE_CLASS_TEXT, InputType.TYPE_TEXT_VARIATION_NORMAL))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhisperDownloadTest {
    private val data = ByteArray(1000) { (it * 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test fun resumesWithRangeAndVerifiesChecksum() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(data, 400, 600)))
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val models = WhisperModels(ctx)
        val target = File(ctx.filesDir, "whisper/test.bin")
        File(target.path + ".part").writeBytes(data.copyOfRange(0, 400))   // interrupted earlier at 400 bytes
        val ok = models.fetch(WhisperModel.TINY, server.url("/m.bin").toString(), target, 1000, sha)
        assertTrue(ok)
        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
        assertTrue(target.readBytes().contentEquals(data))
        assertFalse(File(target.path + ".part").exists())
        assertNull(models.state.value.error)
        server.shutdown()
    }

    @Test fun wrongChecksumDeletesFile() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        server.start()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val models = WhisperModels(ctx)
        val target = File(ctx.filesDir, "whisper/bad.bin")
        val ok = models.fetch(WhisperModel.TINY, server.url("/m.bin").toString(), target, 1000, "0".repeat(64))
        assertFalse(ok)
        assertEquals("checksum", models.state.value.error)
        assertFalse(target.exists()); assertFalse(File(target.path + ".part").exists())
        server.shutdown()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WhisperRoutingTest {
    private class Avail(val ok: Boolean) : SpeechEngine by FakeEngine() {
        val inner = FakeEngine()
        override val events = inner.events
        override fun isAvailable() = ok
        override fun start(request: EngineRequest) = inner.start(request)
        override fun stop() = inner.stop()
        override fun cancel() = inner.cancel()
    }
    private val req = EngineRequest("bn-BD", preferOffline = false, offlineOnly = false)

    @Test fun whisperChoiceUsesWhisper() = runTest {
        val phone = Avail(true); val gemini = Avail(true); val whisper = Avail(true)
        val r = RoutingSpeechEngine(phone, gemini, { AppSettings(engine = EnginePreference.WHISPER) }, backgroundScope, whisper)
        runCurrent(); r.start(req); runCurrent()
        assertEquals(1, whisper.inner.starts); assertEquals(0, phone.inner.starts); assertEquals(0, gemini.inner.starts)
    }

    @Test fun autoFallsBackToWhisperWhenGeminiNotSetUp() = runTest {
        val phone = Avail(true); val gemini = Avail(false); val whisper = Avail(true)
        val r = RoutingSpeechEngine(phone, gemini, { AppSettings(engine = EnginePreference.AUTO) }, backgroundScope, whisper)
        val seen = mutableListOf<SpeechEvent>()
        backgroundScope.launch { r.events.collect { seen += it } }; runCurrent()
        r.start(req); runCurrent()
        phone.inner.emit(SpeechEvent.Error(SpeechErrorKind.LANGUAGE, 12)); runCurrent()
        assertEquals(1, whisper.inner.starts); assertEquals(0, gemini.inner.starts)
        assertTrue(seen.none { it is SpeechEvent.Error })
        r.start(req); runCurrent()
        assertEquals(2, whisper.inner.starts)                      // stays on Whisper for this language
        whisper.inner.emit(SpeechEvent.Final("অফলাইন লেখা")); runCurrent()
        assertTrue(seen.any { it is SpeechEvent.Final })
    }

    @Test fun autoPrefersGeminiOverWhisper() = runTest {
        val phone = Avail(true); val gemini = Avail(true); val whisper = Avail(true)
        val r = RoutingSpeechEngine(phone, gemini, { AppSettings(engine = EnginePreference.AUTO) }, backgroundScope, whisper)
        runCurrent(); r.start(req); runCurrent()
        phone.inner.emit(SpeechEvent.Error(SpeechErrorKind.LANGUAGE, 12)); runCurrent()
        assertEquals(1, gemini.inner.starts); assertEquals(0, whisper.inner.starts)
    }

    @Test fun keyboardFinishHandlerGetsTextInsteadOfAutoInsert() = runTest {
        val engine = FakeEngine(); val store = MemoryStore()
        val c = DictationController(engine, store, { AppSettings(autoInsert = true) }, backgroundScope, clock = { testScheduler.currentTime })
        runCurrent()
        var auto: String? = null; var ime: String? = null
        c.onReviewReady = { auto = it }
        c.start(); runCurrent()
        engine.emit(SpeechEvent.Final("কিবোর্ড থেকে লেখা")); runCurrent()
        c.finishHandler = { ime = it }
        c.finish(); runCurrent(); advanceTimeBy(1_600); runCurrent()
        assertEquals(DictationPhase.REVIEW, c.ui.value.phase)
        assertTrue(ime!!.startsWith("কিবোর্ড থেকে লেখা"))
        assertNull(auto)                                           // no double insert by the bubble
        assertNull(c.finishHandler)                                // one-shot
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeTypingTest {
    private class FakeIc(v: android.view.View) : android.view.inputmethod.BaseInputConnection(v, true) {
        val ed = android.text.SpannableStringBuilder()
        override fun getEditable(): android.text.Editable = ed
    }

    @Test fun phoneticComposingBackspaceAndCommit() {
        val ime = org.robolectric.Robolectric.buildService(VoiceDockIme::class.java).create().get()
        val fake = FakeIc(android.view.View(ApplicationProvider.getApplicationContext()))
        ime.icOverride = fake
        ime.bengali.value = true
        "ami".forEach { ime.letter(it) }
        assertEquals("আমি", fake.ed.toString())
        ime.text(" ")                                    // space commits the word
        assertEquals("", ime.composing.value)
        "kintu".forEach { ime.letter(it) }
        ime.backspace()                                  // removes the last Latin key, word re-rendered
        assertEquals("আমি কিন্ত", fake.ed.toString())
        ime.letter('u'); ime.text("।")
        assertEquals("আমি কিন্তু।", fake.ed.toString())
        ime.digitOrSymbol("5")
        assertEquals("আমি কিন্তু।৫", fake.ed.toString())
        ime.bengali.value = false                        // English mode types letters directly
        "ok".forEach { ime.letter(it) }
        assertEquals("আমি কিন্তু।৫ok", fake.ed.toString())
    }
}
