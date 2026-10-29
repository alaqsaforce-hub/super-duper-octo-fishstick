package com.voicedock.ai.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.ai.GeminiException
import com.voicedock.ai.permissions.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Audio clips that could not be transcribed are kept here (never silently discarded) and can be retried. */
class PendingAudio(context: Context) {
    private val dir = File(context.filesDir, "pending_audio").apply { mkdirs() }
    fun save(wav: ByteArray): File = File(dir, "clip-${System.currentTimeMillis()}.wav").apply { writeBytes(wav) }
    fun list(): List<File> = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
    fun count(): Int = list().size
}

/** Credentials/config the Gemini engine needs at the moment of use (read fresh each time). */
data class GeminiConfig(val apiKey: String?, val model: String, val allowed: Boolean)

/**
 * Speech-to-text with Google Gemini (user's own API key). VoiceDock records the microphone itself (AudioRecord,
 * 16 kHz mono), cuts clips at natural pauses ([AudioChunker]) and sends each clip for verbatim transcription.
 * Works for Bengali, English and mixed speech regardless of which speech services the phone has.
 *
 * Unlike Android recognisers it listens continuously until stop()/cancel(); start() while recording is a no-op,
 * so the controller's restart logic is harmless. Failed clips are retried (1 s/2 s/4 s) and then saved to
 * [PendingAudio] with an honest error.
 */
class GeminiSpeechEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: () -> GeminiConfig,
    private val clientFactory: (String) -> GeminiClient = { GeminiClient(it) },
) : SpeechEngine {
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SpeechEvent> = _events
    override val label = "Gemini AI"
    private val _note = MutableStateFlow<String?>(null)
    override val note: StateFlow<String?> = _note

    private val pending = PendingAudio(context)
    private var recordJob: Job? = null
    @Volatile private var recording = false
    @Volatile private var discard = false
    private val queued = AtomicInteger(0)
    private val clips = Channel<Pair<ShortArray, String>>(Channel.UNLIMITED)
    private var lastLanguage = "bn-BD"

    init {
        scope.launch(Dispatchers.IO) {
            for ((pcm, lang) in clips) {
                try { transcribe(pcm, lang) } finally { if (queued.decrementAndGet() == 0) _note.value = null }
            }
        }
    }

    override val busy: Boolean get() = recording || queued.get() > 0

    override fun isAvailable(): Boolean = config().let { it.allowed && !it.apiKey.isNullOrBlank() }

    @SuppressLint("MissingPermission")
    override fun start(request: EngineRequest) {
        if (recording) return
        val cfg = config()
        if (!cfg.allowed || cfg.apiKey.isNullOrBlank()) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.AUTH, -10)); return }
        if (!Permissions.mic(context)) { _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.PERMISSION, -11)); return }
        lastLanguage = request.languageTag
        recording = true; discard = false
        val previous = recordJob
        recordJob = scope.launch(Dispatchers.IO) {
            previous?.join() // the previous AudioRecord must be released before opening the mic again
            val chunker = AudioChunker()
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
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.AUDIO, -13)); return@launch
                }
                _events.tryEmit(SpeechEvent.Ready)
                val frame = ShortArray(chunker.frameSamples)
                var wasSpeech = false
                var n = 0
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
            } finally {
                runCatching { rec.stop() }; rec.release(); recording = false
            }
        }
    }

    private fun enqueue(pcm: ShortArray) {
        queued.incrementAndGet()
        _note.value = "Gemini লিখছে…"
        clips.trySend(pcm to lastLanguage)
    }

    override fun stop() { recording = false }
    override fun cancel() { discard = true; recording = false }
    override fun release() { cancel() }

    private suspend fun transcribe(pcm: ShortArray, lang: String) {
        val wav = Wav.encode(pcm)
        val cfg = config()
        val key = cfg.apiKey
        if (!cfg.allowed || key.isNullOrBlank()) { pending.save(wav); _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.AUTH, -10)); return }
        val client = clientFactory(key)
        var attempt = 0
        while (true) {
            try {
                val text = client.transcribe(cfg.model, wav, hint(lang))
                if (text.isNotBlank()) _events.tryEmit(SpeechEvent.Final(text))
                return
            } catch (e: GeminiException) {
                val retry = e.kind in setOf(GeminiException.Kind.NETWORK, GeminiException.Kind.SERVER, GeminiException.Kind.RATE_LIMIT)
                if (retry && attempt < 3) { delay(1000L shl attempt); attempt++; continue }
                Log.w("VoiceDock", "Gemini transcription failed: ${e.kind} ${e.httpCode}")
                pending.save(wav)
                _events.tryEmit(SpeechEvent.Error(kindOf(e), e.httpCode, e.kind.name))
                return
            }
        }
    }

    /** Re-sends clips saved after failures. Returns the recovered texts in order (files deleted on success). */
    suspend fun retryPending(): List<String> {
        val cfg = config()
        val key = cfg.apiKey ?: return emptyList()
        if (!cfg.allowed) return emptyList()
        val client = clientFactory(key)
        val out = ArrayList<String>()
        for (f in pending.list()) {
            val text = try { client.transcribe(cfg.model, f.readBytes(), hint(lastLanguage)) } catch (e: GeminiException) { break }
            if (text.isNotBlank()) out += text
            f.delete()
        }
        return out
    }

    fun pendingCount() = pending.count()

    companion object {
        fun hint(tag: String) = if (tag.startsWith("bn")) "Bengali (Bangla), possibly mixed with English words" else "English, possibly mixed with Bengali words"
        fun kindOf(e: GeminiException) = when (e.kind) {
            GeminiException.Kind.AUTH -> SpeechErrorKind.AUTH
            GeminiException.Kind.RATE_LIMIT -> SpeechErrorKind.RATE_LIMIT
            GeminiException.Kind.NETWORK -> SpeechErrorKind.NETWORK
            GeminiException.Kind.SERVER -> SpeechErrorKind.SERVER
            else -> SpeechErrorKind.OTHER
        }
    }
}
