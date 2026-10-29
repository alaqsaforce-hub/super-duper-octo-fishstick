package com.voicedock.ai.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** One recogniser to try: a specific installed service, the system on-device recogniser, or the system default. */
data class RecognizerTarget(val component: ComponentName?, val label: String, val onDevice: Boolean = false)

/**
 * Android [SpeechRecognizer] engine.
 *
 * Does NOT blindly use the phone's default service (on Xiaomi/Samsung that is often a vendor service without
 * Bengali). It walks an ordered list of installed services (Google first, see [RecognizerCatalog.order]); when a
 * service rejects the language (errors 12/13) the next one is tried. If all fail, a LANGUAGE error is reported so
 * the router can switch to Gemini or the UI can explain what to install.
 *
 * Android recognisers end after each utterance; [DictationController] restarts them. Main thread only.
 */
class AndroidSpeechEngine(
    private val context: Context,
    /** Ordered targets for the current request (recomputed when settings change). */
    private val targets: (EngineRequest) -> List<RecognizerTarget>,
) : SpeechEngine {
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<SpeechEvent> = _events
    override var label: String = "Android"; private set

    private var recognizer: SpeechRecognizer? = null
    private var list: List<RecognizerTarget> = emptyList()
    private var index = 0
    private var listKey: String? = null
    private var current: RecognizerTarget? = null
    private var lastRequest: EngineRequest? = null
    /** Did the current target ever deliver text? Then a later language error is not a reason to switch. */
    private var currentWorked = false

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    private fun key(r: EngineRequest) = "${r.languageTag}|${r.offlineOnly}|${r.preferredService}"

    private fun ensureRecognizer(request: EngineRequest): Boolean {
        val k = key(request)
        if (k != listKey) {
            list = targets(request); index = 0; listKey = k
            release()
        }
        if (index >= list.size) return false
        val t = list[index]
        if (recognizer != null && t == current) return true
        release()
        recognizer = try {
            when {
                t.onDevice && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                t.component != null -> SpeechRecognizer.createSpeechRecognizer(context, t.component)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "create recogniser failed: ${e.javaClass.simpleName}"); null
        }
        if (recognizer == null) { index++; return ensureRecognizer(request) }
        current = t; currentWorked = false
        label = t.label
        recognizer!!.setRecognitionListener(listener)
        return true
    }

    override fun start(request: EngineRequest) {
        lastRequest = request
        try {
            if (!ensureRecognizer(request)) {
                _events.tryEmit(SpeechEvent.Error(if (list.isEmpty()) SpeechErrorKind.UNAVAILABLE else SpeechErrorKind.LANGUAGE, -1, "no service accepted ${request.languageTag}"))
                listKey = null // next start tries the whole list again
                return
            }
            val t = current!!
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, request.languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // force offline only when the user chose offline (forcing it online fails without the offline pack)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.offlineOnly || t.onDevice)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
            recognizer!!.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed: ${e.javaClass.simpleName}")
            release()
            _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.BUSY, -2, e.javaClass.simpleName))
        }
    }

    override fun stop() { runCatching { recognizer?.stopListening() } }
    override fun cancel() { runCatching { recognizer?.cancel() } }
    override fun release() { runCatching { recognizer?.destroy() }; recognizer = null; current = null }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _events.tryEmit(SpeechEvent.Ready) }
        override fun onBeginningOfSpeech() { _events.tryEmit(SpeechEvent.SpeechBegin) }
        override fun onRmsChanged(rmsdB: Float) { _events.tryEmit(SpeechEvent.Level(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) { currentWorked = true; _events.tryEmit(SpeechEvent.Partial(t)) }
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isBlank()) _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.SILENCE, SpeechRecognizer.ERROR_NO_MATCH, "empty result"))
            else { currentWorked = true; _events.tryEmit(SpeechEvent.Final(t)) }
        }

        override fun onError(error: Int) {
            val kind = mapError(error)
            if (kind == SpeechErrorKind.LANGUAGE && !currentWorked && index + 1 < list.size) {
                // this service does not take the language → try the next installed service
                Log.i(TAG, "service ${current?.label} rejected language → next")
                index++; release()
                _events.tryEmit(SpeechEvent.Error(SpeechErrorKind.BUSY, error, "next service"))
                return
            }
            if (kind == SpeechErrorKind.LANGUAGE) listKey = null
            if (kind == SpeechErrorKind.BUSY) release() // a fresh instance clears most busy/client states
            _events.tryEmit(SpeechEvent.Error(kind, error))
        }
    }

    companion object {
        private const val TAG = "VoiceDock"

        fun mapError(code: Int): SpeechErrorKind = when (code) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> SpeechErrorKind.SILENCE
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> SpeechErrorKind.BUSY
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, 11 /* SERVER_DISCONNECTED */ -> SpeechErrorKind.NETWORK
            SpeechRecognizer.ERROR_SERVER -> SpeechErrorKind.SERVER
            10 /* TOO_MANY_REQUESTS */ -> SpeechErrorKind.RATE_LIMIT
            SpeechRecognizer.ERROR_AUDIO -> SpeechErrorKind.AUDIO
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorKind.PERMISSION
            12 /* LANGUAGE_NOT_SUPPORTED */, 13 /* LANGUAGE_UNAVAILABLE */ -> SpeechErrorKind.LANGUAGE
            else -> SpeechErrorKind.OTHER
        }
    }
}
