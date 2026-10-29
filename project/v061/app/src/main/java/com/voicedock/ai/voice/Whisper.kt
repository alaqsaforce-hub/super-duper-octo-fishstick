package com.voicedock.ai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.permissions.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** whisper.cpp JNI (arm64-v8a only). [available] is false on other CPUs / in unit tests. */
object WhisperNative {
    val available: Boolean by lazy { runCatching { System.loadLibrary("vdwhisper"); true }.getOrElse { false } }
    @JvmStatic external fun nativeInit(path: String): Long
    @JvmStatic external fun nativeFree(ctx: Long)
    /** audioCtx 0 = full 30 s encoder window (smaller values were tested and badly hurt Bengali accuracy). */
    @JvmStatic external fun nativeTranscribe(ctx: Long, pcm: FloatArray, lang: String?, prompt: String?, threads: Int, audioCtx: Int): ByteArray?
    @JvmStatic external fun nativeSystemInfo(): String
}

/**
 * Downloadable models (whisper.cpp quantised GGML files on Hugging Face). Exact byte size and SHA-256 are checked
 * after download. Small models are weak at Bengali — this is stated in the UI, not hidden.
 */
enum class WhisperModel(val file: String, val bytes: Long, val sha256: String, val bn: String, val en: String) {
    TINY("ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        "Tiny · ৩২ MB — শুধু English (বাংলা লিখতে পারে না)", "Tiny · 32 MB — English only (cannot write Bengali)"),
    BASE("ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        "Base · ৬০ MB — শুধু English (পরীক্ষায় বাংলা উর্দু লিপিতে লিখেছে)", "Base · 60 MB — English only (wrote Bengali in Urdu script in tests)"),
    SMALL("ggml-small-q5_1.bin", 190_085_487, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        "Small · ১৯০ MB — বাংলা দুর্বল, ভুল অনেক (৩GB+ RAM)", "Small · 190 MB — weak Bengali, many errors (3 GB+ RAM)"),
    TURBO("ggml-large-v3-turbo-q5_0.bin", 574_041_195, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        "Large-v3 Turbo · ৫৭৪ MB — অফলাইনে বাংলার জন্য সেরা, তবু বানান ভুল থাকে; ধীর (৬GB+ RAM)", "Large-v3 Turbo · 574 MB — best offline Bengali, still misspells; slow (6 GB+ RAM)");

    val url get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
    companion object { fun byName(n: String?) = entries.firstOrNull { it.name == n } }
}

data class DownloadState(val model: WhisperModel? = null, val done: Long = 0, val total: Long = 0, val error: String? = null, val verifying: Boolean = false)

/** Model files live in app-private storage (filesDir/whisper). Resumable download with Range. */
class WhisperModels(context: Context, private val http: OkHttpClient = defaultHttp) {
    private val dir = File(context.filesDir, "whisper").apply { mkdirs() }
    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state
    @Volatile private var cancelled = false

    fun file(m: WhisperModel) = File(dir, m.file)
    fun installed(m: WhisperModel) = file(m).let { it.exists() && it.length() == m.bytes }
    fun installedModels() = WhisperModel.entries.filter { installed(it) }
    fun delete(m: WhisperModel) { file(m).delete(); File(dir, m.file + ".part").delete() }
    fun cancel() { cancelled = true }
    fun freeBytes(): Long = dir.usableSpace

    /** Blocking — run on Dispatchers.IO. Returns true when the verified file is in place. */
    fun download(m: WhisperModel): Boolean = fetch(m, m.url, file(m), m.bytes, m.sha256)

    internal fun fetch(m: WhisperModel, url: String, target: File, bytes: Long, sha: String): Boolean {
        cancelled = false
        val part = File(target.path + ".part")
        if (freeBytes() < bytes - part.length() + 50_000_000) { _state.value = DownloadState(m, error = "no_space"); return false }
        try {
            var have = if (part.exists()) part.length() else 0L
            if (have > bytes) { part.delete(); have = 0 }
            _state.value = DownloadState(m, have, bytes)
            if (have < bytes) {
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { _state.value = DownloadState(m, have, bytes, error = "HTTP ${r.code}"); return false }
                    if (have > 0 && r.code != 206) have = 0 // server ignored Range: start over
                    RandomAccessFile(part, "rw").use { out ->
                        out.setLength(have); out.seek(have)
                        val input = r.body!!.byteStream(); val buf = ByteArray(256 * 1024); var last = 0L
                        while (true) {
                            if (cancelled) { _state.value = DownloadState(m, have, bytes, error = "cancelled"); return false }
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); have += n
                            if (have - last > 1_000_000) { last = have; _state.value = DownloadState(m, have, bytes) }
                        }
                    }
                }
            }
            if (part.length() != bytes) { _state.value = DownloadState(m, part.length(), bytes, error = "size"); return false }
            _state.value = DownloadState(m, bytes, bytes, verifying = true)
            if (sha256(part) != sha) { part.delete(); _state.value = DownloadState(m, error = "checksum"); return false }
            if (!part.renameTo(target)) { _state.value = DownloadState(m, error = "rename"); return false }
            _state.value = DownloadState()
            return true
        } catch (e: Exception) {
            _state.value = DownloadState(m, part.length(), bytes, error = e.javaClass.simpleName)
            return false
        }
    }

    companion object {
        val defaultHttp: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val b = ByteArray(1 shl 20); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class WhisperConfig(val model: WhisperModel?, val file: File?)

/**
 * Fully offline speech-to-text: VoiceDock records the mic, cuts clips at pauses ([AudioChunker]) and runs
 * whisper.cpp on the phone's CPU. No network, no key. Text appears after each pause (no live partials).
 */
class WhisperSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: () -> WhisperConfig,
) : SpeechEngine {
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SpeechEvent> = _events
    override val label = "Whisper (offline)"
    private val _note = MutableStateFlow<String?>(null)
    override val note: StateFlow<String?> = _note

    private var recordJob: Job? = null
    @Volatile private var recording = false
    @Volatile private var discard = false
    private val queued = AtomicInteger(0)
    private val clips = Channel<Pair<ShortArray, String>>(Channel.UNLIMITED)
    private var lastLanguage = "bn-BD"
    private var ctx = 0L
    private var ctxFile: String? = null

    init {
        scope.launch(Dispatchers.Default) {
            for ((pcm, lang) in clips) {
                try { transcribe(pcm, lang) } finally { if (queued.decrementAndGet() == 0) _note.value = null }
            }
        }
    }

    override val busy: Boolean get() = recording || queued.get() > 0
    override fun isAvailable(): Boolean = WhisperNative.available && config().file?.exists() == true

    @SuppressLint("MissingPermission")
    override fun start(request: EngineRequest) {
        if (recording) return
        if (!isAvailable()) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.UNAVAILABLE, -20, "whisper_model_missing")); return }
        if (!Permissions.mic(context)) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.PERMISSION, -11)); return }
        lastLanguage = request.languageTag
        recording = true; discard = false
        val previous = recordJob
        recordJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            // Whisper always encodes a 30 s window, so every clip has a fixed cost: cut at longer pauses (1.6 s)
            // and allow long clips, so there are fewer, longer clips.
            val chunker = AudioChunker(endSilenceMs = 1_600, maxClipMs = 28_000)
            val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunker.frameSamples * 2 * 10))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release(); recording = false
                _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.AUDIO, -12)); return@launch
            }
            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.AUDIO, -13)); return@launch }
                _events.tryEmit(SpeechEvent.Ready)
                val frame = ShortArray(chunker.frameSamples)
                var wasSpeech = false; var n = 0
                while (isActive && recording) {
                    var got = 0
                    while (got < frame.size && recording) {
                        val r = rec.read(frame, got, frame.size - got)
                        if (r < 0) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.AUDIO, r)); recording = false; break }
                        got += r
                    }
                    if (got < frame.size) break
                    chunker.feed(frame)?.let { enqueue(it) }
                    val speech = chunker.lastFrameWasSpeech
                    if (speech && !wasSpeech) _events.tryEmit(SpeechEvent.SpeechBegin)
                    wasSpeech = speech
                    if (++n % 5 == 0) _events.tryEmit(SpeechEvent.Level(if (speech) 8f else 0f))
                }
                if (!discard) chunker.flush()?.let { enqueue(it) }
            } finally { runCatching { rec.stop() }; rec.release(); recording = false }
        }
    }

    private fun enqueue(pcm: ShortArray) {
        queued.incrementAndGet(); _note.value = "Whisper লিখছে (ফোনেই)…"
        clips.trySend(pcm to lastLanguage)
    }

    private fun ensureContext(): Long {
        val f = config().file ?: return 0
        if (ctx != 0L && ctxFile == f.path) return ctx
        if (ctx != 0L) { WhisperNative.nativeFree(ctx); ctx = 0 }
        ctx = WhisperNative.nativeInit(f.path); ctxFile = f.path
        return ctx
    }

    private fun transcribe(pcm: ShortArray, lang: String) {
        val c = ensureContext()
        if (c == 0L) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.UNAVAILABLE, -21, "whisper_load_failed")); return }
        val f = FloatArray(pcm.size) { pcm[it] / 32768f }
        val bn = lang.startsWith("bn")
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // No initial prompt: in tests a Bengali prompt made decoding 4-5x slower and caused hallucination loops.
        val bytes = WhisperNative.nativeTranscribe(c, f, if (bn) "bn" else "en", null, threads, 0)
        if (bytes == null) { Log.w("VoiceDock", "whisper failed"); _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.OTHER, -22, "whisper_failed")); return }
        val text = clean(String(bytes, Charsets.UTF_8), bn)
        if (bn && ScriptFix.foreignShare(text) > 0.5)
            _note.value = "মডেলটি বাংলা লিপিতে লিখতে পারেনি — Small বা Turbo মডেল নিন, বা Gemini ব্যবহার করুন"
        if (text.isNotBlank()) _events.tryEmit(SpeechEvent.Final(text))
    }

    override fun stop() { recording = false }
    override fun cancel() { discard = true; recording = false }
    override fun release() {
        cancel()
        scope.launch(Dispatchers.Default) { if (ctx != 0L) { WhisperNative.nativeFree(ctx); ctx = 0; ctxFile = null } }
    }

    companion object {
        private val NOISE = Regex("\\[(?:BLANK_AUDIO|MUSIC|NOISE|SOUND|SILENCE|INAUDIBLE)[^\\]]*\\]|\\((?:music|noise|silence|applause|laughs?)\\)|♪+", RegexOption.IGNORE_CASE)
        /** Phrases Whisper is known to hallucinate on near-silence. Only dropped when they are the whole clip. */
        /** Real words a user might say (e.g. "ধন্যবাদ", "thank you") are NOT in this list — never drop real speech. */
        private val HALLUCINATIONS = setOf("thanks for watching!", "thank you for watching.", "please subscribe.", "subscribe to my channel.",
            "সাবস্ক্রাইব করুন")

        fun clean(raw: String, bengali: Boolean = false): String {
            var t = GeminiClient.cleanTranscript(raw.replace(NOISE, " ").replace(Regex("\\s+"), " ").trim())
            if (bengali) t = ScriptFix.devanagariToBengali(t)
            return if (t.lowercase() in HALLUCINATIONS) "" else t
        }
    }
}

/**
 * Small Whisper models often hear Bengali correctly but write it in Devanagari (Hindi script). The two Unicode
 * blocks are parallel (offset 0x80), so the text can be converted letter by letter. Urdu/Arabic-script output
 * (seen with the Base model) cannot be converted — it is kept (never discarded) and the user is told.
 */
object ScriptFix {
    private val special = mapOf(
        '\u0935' to '\u09AC', '\u0931' to '\u09B0', '\u0933' to '\u09B2', '\u0934' to '\u09B2', '\u0929' to '\u09A8',
        '\u090D' to '\u098F', '\u090E' to '\u098F', '\u0911' to '\u0993', '\u0912' to '\u0993',
        '\u0945' to '\u09C7', '\u0946' to '\u09C7', '\u0949' to '\u09CB', '\u094A' to '\u09CB',
        '\u0958' to '\u0995', '\u0959' to '\u0996', '\u095A' to '\u0997', '\u095B' to '\u099C', '\u095E' to '\u09AB',
    )

    fun devanagariToBengali(s: String): String {
        if (s.none { it in '\u0900'..'\u097F' }) return s
        val b = StringBuilder(s.length)
        for (c in s) {
            if (c !in '\u0900'..'\u097F' || c == '\u0964' || c == '\u0965') { b.append(c); continue } // । ॥ are shared
            val mapped = special[c] ?: (c + 0x80).takeIf { Character.isDefined(it) && Character.UnicodeBlock.of(it) == Character.UnicodeBlock.BENGALI }
            b.append(mapped ?: c)
        }
        return b.toString()
    }

    /** Share of letters that are neither Bengali nor Latin (e.g. Arabic/Urdu script). */
    fun foreignShare(s: String): Double {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        val foreign = letters.count { Character.UnicodeBlock.of(it) != Character.UnicodeBlock.BENGALI && it !in 'a'..'z' && it !in 'A'..'Z' }
        return foreign.toDouble() / letters.length
    }
}
