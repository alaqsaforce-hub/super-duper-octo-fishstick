package com.voicedock.ai.agent

import android.app.Activity
import android.content.Context
import com.voicedock.ai.ai.AiException
import com.voicedock.ai.ai.AiSetup
import com.voicedock.ai.ai.ChatMessage
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-wide entry point for agent tasks (bubble + app screen share it). One task at a time. */
object AgentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(AgentUi())
    val ui: StateFlow<AgentUi> = _ui.asStateFlow()
    private var runner: AgentRunner? = null
    private var job: Job? = null

    /** Set by the floating panel so screenshots never contain VoiceDock's own bubble. */
    @Volatile var overlayHider: OverlayHider? = null

    /** Voice input routing in the bubble: the next finished dictation becomes the task goal or the answer to the agent's question. */
    enum class VoiceArm { NONE, GOAL, REPLY }
    private val _arm = MutableStateFlow(VoiceArm.NONE)
    val arm: StateFlow<VoiceArm> = _arm.asStateFlow()
    fun armVoice(a: VoiceArm) { _arm.value = a }

    data class Preset(val bn: String, val en: String) { val text get() = tr(bn, en) }

    val PRESETS = listOf(
        Preset("এই স্ক্রিনে কী আছে সংক্ষেপে বলো এবং আমার কী করা উচিত পরামর্শ দাও।", "Summarise this screen and advise me what to do."),
        Preset("এই পেজটা শেষ পর্যন্ত স্ক্রল করে পড়ো, তারপর মূল কথাগুলো সারাংশ করো।", "Scroll through this whole page, then summarise the key points."),
        Preset("গ্যালারি খুলে সাম্প্রতিক ছবিগুলো একে একে দেখে প্রতিটার সংক্ষিপ্ত বর্ণনা দাও (কিছু মুছবে না/শেয়ার করবে না)।", "Open the gallery, look at the recent photos one by one and describe each (do not delete or share)."),
        Preset("WhatsApp খুলে উপরের ৫টি চ্যাট একে একে খুলে পড়ো এবং প্রতিটার সারাংশ দাও। কিছু পাঠাবে না।", "Open WhatsApp, open the top 5 chats one by one and summarise each. Do not send anything."),
        Preset("এই সেটিংস পেজের অপশনগুলো সহজ ভাষায় ব্যাখ্যা করো; কিছু পরিবর্তন করবে না।", "Explain the options on this settings page simply; do not change anything."),
    )

    /** Why a task cannot start (null = ready). */
    fun blocker(ctx: Context): String? {
        val s = SettingsStore.state.value
        val svc = InsertAccessibilityService.instance
        return when {
            s.localOnly -> tr("শুধু-লোকাল মোড চালু — এজেন্টের জন্য ক্লাউড AI লাগে", "Local-only mode is on — the agent needs cloud AI")
            svc == null -> tr("এজেন্টের জন্য VoiceDock Accessibility চালু করুন (সেটিংস → Accessibility)", "Turn on VoiceDock Accessibility for the agent")
            !A11yDevice.canGesture(svc) -> tr("নতুন অনুমতি (ট্যাপ/স্ক্রল) পেতে VoiceDock Accessibility একবার বন্ধ করে আবার চালু করুন", "Turn VoiceDock Accessibility off and on once to grant the new tap/scroll permission")
            !AiSetup.ready(ctx) -> tr("কোনো AI প্রোভাইডার প্রস্তুত নেই — সেটিংস → AI প্রোভাইডার", "No AI provider ready — Settings → AI providers")
            else -> null
        }
    }

    fun start(ctx: Context, goal: String, fromActivity: Activity? = null) {
        if (_ui.value.running) return
        val g = goal.trim()
        if (g.isEmpty()) return
        blocker(ctx)?.let { _ui.value = AgentUi(phase = AgentPhase.FAILED, goal = g, error = it, answer = it); return }
        val app = ctx.applicationContext
        val svc = InsertAccessibilityService.instance!!
        val router = AiSetup.router(app)
        val call: ModelCall = { system, user, images ->
            withContext(Dispatchers.IO) {
                val msgs = listOf(ChatMessage.user(user))
                val ans = try { router.completeVision(system, msgs, images) } catch (e: AiException) {
                    if (images.isEmpty()) throw e
                    // No image-capable provider worked: continue with the element list only.
                    router.complete(system, msgs)
                }
                ans.text to (ans.provider.label + " · " + ans.model + (ans.account.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            }
        }
        val bn = SettingsStore.state.value.uiLanguage == UiLanguage.BN
        val r = AgentRunner(
            device = A11yDevice(svc, overlayHider),
            brain = PromptBrain(call),
            limits = AgentLimits(answerInBengali = bn, blockedPackages = setOf(app.packageName)),
            text = object : AgentText { override fun t(bn: String, en: String) = tr(bn, en) },
            explainError = { AiSetup.explain(it) },
        )
        runner = r
        _ui.value = AgentUi(phase = AgentPhase.RUNNING, goal = g, status = tr("শুরু হচ্ছে…", "Starting…"))
        job = scope.launch {
            val mirror = launch { r.ui.collect { if (it.phase != AgentPhase.IDLE) _ui.value = it } }
            if (fromActivity != null) { fromActivity.moveTaskToBack(true); delay(800) } else delay(300)
            try { r.run(g) } catch (_: kotlinx.coroutines.CancellationException) {} finally {
                _ui.value = r.ui.value
                mirror.cancel()
                if (runner === r) runner = null
            }
        }
    }

    fun stop() { runner?.stop(); scope.launch { delay(4000); if (runner != null) job?.cancel() } }
    fun answerConfirm(allow: Boolean) { runner?.answerConfirm(allow) }
    fun answerQuestion(answer: String) { runner?.answerQuestion(answer) }
    fun clear() { if (!_ui.value.running) _ui.value = AgentUi() }
}
