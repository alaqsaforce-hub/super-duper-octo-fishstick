package com.voicedock.ai

import android.app.Application
import android.content.Context
import com.voicedock.ai.data.database.AppDatabase
import com.voicedock.ai.data.repositories.DraftStore
import com.voicedock.ai.data.repositories.RoomDraftStore
import com.voicedock.ai.settings.SettingsStore
import android.os.Build
import android.speech.SpeechRecognizer
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.security.CrashLogUncaughtExceptionHandler
import com.voicedock.ai.voice.AndroidSpeechEngine
import com.voicedock.ai.voice.EngineRequest
import com.voicedock.ai.voice.GeminiConfig
import com.voicedock.ai.voice.GeminiSpeechEngine
import com.voicedock.ai.voice.RecognizerCatalog
import com.voicedock.ai.voice.RecognizerTarget
import com.voicedock.ai.voice.RoutingSpeechEngine
import com.voicedock.ai.voice.DictationController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class VoiceDockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        if (Thread.getDefaultUncaughtExceptionHandler() !is CrashLogUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(CrashLogUncaughtExceptionHandler(this))
        }
    }
}

/** Simple service locator (one process, one dictation session shared by the overlay and the app screens). */
object VoiceDock {
    private val scope = MainScope()
    @Volatile private var db: AppDatabase? = null
    @Volatile private var controller: DictationController? = null

    @Volatile private var router: RoutingSpeechEngine? = null
    @Volatile var gemini: GeminiSpeechEngine? = null; private set

    /** Gemini key/model/consent read fresh on every use. */
    fun geminiConfig(ctx: Context): GeminiConfig {
        val s = SettingsStore.state.value
        return GeminiConfig(SecretStore.get(ctx, SecretStore.GEMINI), s.geminiModel, s.cloudAllowed && !s.localOnly)
    }

    private fun engine(ctx: Context): RoutingSpeechEngine = router ?: run {
        val app = ctx.applicationContext
        val phone = AndroidSpeechEngine(app) { req -> phoneTargets(app, req) }
        val g = GeminiSpeechEngine(app, scope, { geminiConfig(app) })
        gemini = g
        val models = whisperModels(app)
        val w = com.voicedock.ai.voice.WhisperSpeechEngine(app, scope, {
            val s = SettingsStore.state.value
            val m = com.voicedock.ai.voice.WhisperModel.byName(s.whisperModel)?.takeIf { models.installed(it) } ?: models.installedModels().lastOrNull()
            com.voicedock.ai.voice.WhisperConfig(m, m?.let { models.file(it) })
        })
        RoutingSpeechEngine(phone, g, { SettingsStore.state.value }, scope, w).also { router = it }
    }

    /** Ordered phone recognisers: user's pick, Google, offline Whisper apps, … vendor services last. */
    fun phoneTargets(ctx: Context, req: EngineRequest): List<RecognizerTarget> {
        val list = RecognizerCatalog.order(RecognizerCatalog.list(ctx), req.preferredService)
            .map { RecognizerTarget(it.component, it.label) }
        val onDevice = if (req.offlineOnly && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            listOf(RecognizerTarget(null, "Android on-device", onDevice = true)) else emptyList()
        return (onDevice + list).ifEmpty { listOf(RecognizerTarget(null, "Android default")) }
    }

    @Volatile private var wm: com.voicedock.ai.voice.WhisperModels? = null
    fun whisperModels(ctx: Context): com.voicedock.ai.voice.WhisperModels = wm ?: synchronized(this) { wm ?: com.voicedock.ai.voice.WhisperModels(ctx.applicationContext).also { wm = it } }

    fun db(ctx: Context): AppDatabase = db ?: synchronized(this) { db ?: AppDatabase.build(ctx).also { db = it } }
    fun drafts(ctx: Context): DraftStore = RoomDraftStore(db(ctx).drafts())
    fun knowledge(ctx: Context): com.voicedock.ai.knowledge.KnowledgeRepo = com.voicedock.ai.knowledge.KnowledgeRepo(db(ctx).knowledge())

    /** Must be called on the main thread (SpeechRecognizer requirement). */
    fun dictation(ctx: Context): DictationController = controller ?: synchronized(this) {
        controller ?: DictationController(
            engine = engine(ctx),
            store = drafts(ctx),
            settings = { SettingsStore.state.value },
            scope = scope,
        ).also { c ->
            controller = c
            scope.launch { c.restoreUnfinished() } // an unfinished draft from before a restart comes back (mic stays off)
        }
    }
}
