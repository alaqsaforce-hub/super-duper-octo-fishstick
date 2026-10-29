package com.voicedock.ai.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voicedock.ai.R
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.ai.AiException
import com.voicedock.ai.ai.AiSetup
import com.voicedock.ai.security.SecretDetector
import com.voicedock.ai.device.AgentSafetyOracle
import com.voicedock.ai.context.AssistantAction
import com.voicedock.ai.context.AssistantGreeting
import com.voicedock.ai.context.CatchUpSummary
import com.voicedock.ai.context.ContextClassifier
import com.voicedock.ai.context.ContextKind
import com.voicedock.ai.context.ContextPolicy
import com.voicedock.ai.context.ContextSnapshot
import com.voicedock.ai.context.LocalIntentRouter
import com.voicedock.ai.device.ScreenProbe
import com.voicedock.ai.insertion.ClipboardInsertion
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.insertion.TextInserter
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.MainActivity
import com.voicedock.ai.vision.ScreenAssistant
import com.voicedock.ai.voice.DictationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * v0.6 "Contextual Mic Bubble".
 *
 * This is the mic-FREE overlay owner (design decision #3: Collection != Activation).
 * It has NO microphone permission requirement: on Android 14+ it runs as a `specialUse`
 * foreground service, on Android 10-13 as `dataSync`, on older as an untyped FGS.
 * The existing [OverlayService] remains the microphone FGS and is started only when the user
 * actually chooses "Dictate" — so a text/screen assistant works with the mic OFF.
 *
 * Tap flow: SCANNING (one ScreenProbe) -> ContextClassifier -> ContextCard (non-focusable) or
 * AssistantBubble (focusable, types its own greeting). Nothing is inserted into another app
 * without an explicit user tap on "Insert here".
 */
class AssistantOverlayService : LifecycleService() {

    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var scrim: ScrimController? = null
    private var cardView: ComposeView? = null
    private var cardOwner: OverlayLifecycleOwner? = null
    private var chatView: ComposeView? = null
    private var chatOwner: OverlayLifecycleOwner? = null

    private val _ui = MutableStateFlow(AssistantUiState())
    val ui: StateFlow<AssistantUiState> = _ui.asStateFlow()

    private var probeJob: Job? = null
    private var assistantDictationStarted = false
    private var assistantHelpPending = false
    private var lastDictationDraft = ""
    private var lastHelpAnswer = ""

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        createChannel()
        scrim = ScrimController(this, wm, overlayType()) { closeWindows() }.also { it.attach() }
        addBubble()
        observeDictation()
        observeScreenHelp()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_HIDE -> removeBubble()
            ACTION_RESCAN -> activate()
            ACTION_CLOSE -> { closeEverything(); return START_NOT_STICKY }
            else -> addBubble()
        }
        return START_NOT_STICKY
    }

    /** No RECORD_AUDIO check here — that was the v0.5 defect. */
    private fun goForeground(): Boolean = try {
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        Log.w("VoiceDock", "assistant startForeground failed: ${e.javaClass.simpleName}")
        Toast.makeText(this, tr("VoiceDock অ্যাপ খুলে আবার চেষ্টা করুন", "Open VoiceDock and try again"), Toast.LENGTH_LONG).show()
        false
    }

    // ------------------------------------------------------------------ mic bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (bubble != null) return
        if (!Permissions.overlay(this)) {
            toast(tr("‘অন্য অ্যাপের উপরে দেখানো’ অনুমতি দিন", "Allow ‘Display over other apps’"))
            return
        }
        val s = SettingsStore.state.value
        val size = (54 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setPadding(size / 4, size / 4, size / 4, size / 4)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
            contentDescription = tr("VoiceDock সহকারী বাবল", "VoiceDock assistant bubble")
            elevation = 8f
        }
        val p = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = s.bubbleX; y = s.bubbleY }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(v, p) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) SettingsStore.update { it.copy(bubbleX = p.x, bubbleY = p.y) }
                    else if (_ui.value.phase != AssistantPhase.IDLE) closeWindows()
                    else activate()
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(view, p) }.onSuccess { bubble = view; bubbleParams = p }
            .onFailure { Log.w("VoiceDock", "assistant bubble add failed: ${it.javaClass.simpleName}") }
        updateBubble()
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    private fun updateBubble() {
        val b = bubble ?: return
        val color = when (_ui.value.phase) {
            AssistantPhase.SCANNING -> COLOR_SCANNING
            AssistantPhase.CONTEXT_CARD -> COLOR_CONTEXT
            AssistantPhase.CHAT -> COLOR_CHAT
            else -> COLOR_IDLE
        }
        (b.background as? GradientDrawable)?.setColor(color)
    }

    // ------------------------------------------------------------------ activation

    private fun activate() {
        val s = SettingsStore.state.value
        closeWindows(reset = false)
        scrim?.show(s.assistantDimBlur, s.assistantDimBlur)
        _ui.value = AssistantUiState(phase = AssistantPhase.SCANNING, busy = true, localOnly = s.localOnly)
        updateBubble()
        probeJob?.cancel()
        probeJob = lifecycleScope.launch {
            if (s.assistantAutoScan) delay(240)
            val snapshot = withContext(Dispatchers.IO) {
                if (s.assistantAutoScan && s.assistantScreenContext)
                    ScreenProbe.probe(InsertAccessibilityService.instance, oracle = AgentSafetyOracle)
                else ContextSnapshot(probeOk = true) // no consent / auto-scan off -> generic assistant
            }
            val current = SettingsStore.state.value
            val assessment = if (!current.assistantScreenContext || !current.assistantAutoScan)
                ContextClassifier.noConsentAssessment()
            else ContextClassifier.assess(snapshot, current.assistantPrefs().policy(), AgentSafetyOracle)

            val cu = if (s.assistantCatchUp) CatchUpSummary.build(emptyList(), System.currentTimeMillis(), 6) else null
            val bn = s.uiLanguage == UiLanguage.BN
            val greeting = AssistantGreeting.build(assessment.kind, cu, bn, assessment.snapshot.appLabel)
            val phase = if (assessment.kind == ContextKind.FIELD_FOUND || assessment.kind == ContextKind.CONTENT_NO_FIELD)
                AssistantPhase.CONTEXT_CARD else AssistantPhase.CHAT
            _ui.value = AssistantUiState(phase, assessment, greeting, cu, localOnly = s.localOnly)
            showWindow(phase)
            updateBubble()
        }
    }

    private fun showWindow(phase: AssistantPhase) {
        removeCard(); removeChat()
        when (phase) {
            AssistantPhase.CONTEXT_CARD -> addCard()
            AssistantPhase.CHAT -> addChat()
            else -> Unit
        }
    }

    private fun addCard() {
        if (cardView != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view); owner.create()
        view.setContent {
            val state by ui.collectAsState()
            ContextCard(state, ::onAction, ::switchToChat, { closeWindows() }, ::openSettings, ::selectDraft)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { wm.addView(view, p) }.onSuccess { cardView = view; cardOwner = owner }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "card add failed: ${it.javaClass.simpleName}") }
    }

    /** Focusable window: the user can actually TYPE here (v0.5 panel was NOT_FOCUSABLE). */
    private fun addChat() {
        if (chatView != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view); owner.create()
        view.setContent {
            val state by ui.collectAsState()
            AssistantBubble(state, ::onAction, ::onSubmit, ::selectDraft, { closeWindows() }, ::openSettings,
                onPauseDictation = { VoiceDock.dictation(this).pause() },
                onResumeDictation = { VoiceDock.dictation(this).resume() },
                onFinishDictation = { VoiceDock.dictation(this).finish() })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { wm.addView(view, p) }.onSuccess { chatView = view; chatOwner = owner }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "chat add failed: ${it.javaClass.simpleName}") }
    }

    private fun switchToChat() {
        _ui.value = _ui.value.copy(phase = AssistantPhase.CHAT)
        showWindow(AssistantPhase.CHAT)
        updateBubble()
    }

    private fun removeCard() {
        cardView?.let { runCatching { wm.removeView(it) } }
        cardOwner?.destroy(); cardView = null; cardOwner = null
    }

    private fun removeChat() {
        chatView?.let { runCatching { wm.removeView(it) } }
        chatOwner?.destroy(); chatView = null; chatOwner = null
    }

    private fun closeWindows(reset: Boolean = true) {
        probeJob?.cancel(); probeJob = null
        removeCard(); removeChat(); scrim?.hide()
        if (reset) _ui.value = AssistantUiState()
        updateBubble()
    }

    private fun closeEverything() {
        probeJob?.cancel()
        closeWindows()
        removeBubble()
        scrim?.detach()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ actions

    private fun onAction(a: AssistantAction) {
        val s = SettingsStore.state.value
        val bn = s.uiLanguage == UiLanguage.BN
        when (a) {
            AssistantAction.ASK_QUESTION -> switchToChat()
            AssistantAction.DICTATE -> startDictation()
            AssistantAction.DRAFT_REPLY -> draftReply("")
            AssistantAction.INSERT_APPROVED -> insertApprovedDraft()
            AssistantAction.COPY -> {
                val text = _ui.value.draft.ifBlank { _ui.value.answer }
                if (text.isBlank()) { toast(tr("কপি করার মতো লেখা নেই", "Nothing to copy")); return }
                ClipboardInsertion.insert(this, text)
                toast(tr("কপি হয়েছে", "Copied"))
            }
            AssistantAction.EXPLAIN_SCREEN -> explainScreen("")
            AssistantAction.SUMMARISE -> explainScreen(
                tr("এই স্ক্রিনের মূল কথাগুলো সহজ ভাষায় সংক্ষেপে বলো।", "Summarise the key points of this screen in simple words."),
            )
            AssistantAction.WHY_BLOCKED -> {
                val reason = _ui.value.assessment?.blockReason
                _ui.value = _ui.value.copy(answer = if (reason != null) ContextPolicy.blockExplanation(reason, bn) else "")
                switchToChat()
            }
            AssistantAction.OPEN_SETTINGS -> openSettings()
            else -> {
                _ui.value = _ui.value.copy(answer = LocalIntentRouter.reply("", a, bn))
                switchToChat()
            }
        }
    }

    /** Close our focusable chat, then verify the original target again before touching another app. */
    private fun insertApprovedDraft() {
        val state = _ui.value
        val text = state.draft
        if (text.isBlank()) { toast(tr("আগে একটি খসড়া তৈরি করুন", "Create a draft first")); return }
        val target = state.assessment?.takeIf { it.kind == ContextKind.FIELD_FOUND }?.snapshot?.pkg.orEmpty()
        closeWindows()
        lifecycleScope.launch {
            // Give the app underneath a chance to regain focus; never guess a target.
            delay(250)
            val settings = SettingsStore.state.value
            val snapshot = if (target.isNotBlank() && settings.assistantScreenContext)
                withContext(Dispatchers.IO) { ScreenProbe.probe(InsertAccessibilityService.instance, oracle = AgentSafetyOracle) }
            else ContextSnapshot(probeOk = false)
            val assessment = ContextClassifier.assess(snapshot, settings.assistantPrefs().policy(), AgentSafetyOracle)
            if (assessment.kind != ContextKind.FIELD_FOUND || snapshot.pkg != target || snapshot.focusedField == null) {
                ClipboardInsertion.insert(this@AssistantOverlayService, text)
                if (text == lastDictationDraft) VoiceDock.dictation(this@AssistantOverlayService).markInserted("clipboard")
                toast(tr("নিরাপদ সক্রিয় ঘর পাওয়া যায়নি — কপি করা হলো", "No verified focused field — copied instead"))
                return@launch
            }
            val out = TextInserter.insert(this@AssistantOverlayService, text, expectedPackage = target)
            toast(tr(out.result.bn, out.result.en))
            if (text == lastDictationDraft) VoiceDock.dictation(this@AssistantOverlayService).markInserted(out.destination)
        }
    }

    private fun onSubmit(text: String) {
        if (text.isBlank()) return
        val action = LocalIntentRouter.route(text)
        when (action) {
            // A typed instruction is not the separate approval tap promised by the UI.
            AssistantAction.INSERT_APPROVED -> {
                _ui.value = _ui.value.copy(answer = "", providerLabel = "", note = tr("খসড়ার ‘বসাও’ বোতাম চাপুন", "Tap Insert on the visible draft"))
            }
            AssistantAction.COPY, AssistantAction.EXPLAIN_SCREEN, AssistantAction.SUMMARISE -> {
                _ui.value = _ui.value.copy(answer = "", providerLabel = "", note = "")
                onAction(action)
            }
            AssistantAction.DRAFT_REPLY -> draftReply(text)
            AssistantAction.CATCH_UP, AssistantAction.TODAY_TASKS, AssistantAction.REMEMBER, AssistantAction.GENERAL_HELP -> {
                val bn = SettingsStore.state.value.uiLanguage == UiLanguage.BN
                _ui.value = _ui.value.copy(answer = LocalIntentRouter.reply(text, action, bn), providerLabel = "", note = "")
                switchToChat()
            }
            else -> askAi(text)
        }
    }

    private fun askAi(text: String) {
        val settings = SettingsStore.state.value
        val bn = settings.uiLanguage == UiLanguage.BN
        if (SecretDetector.isSecret(text)) {
            _ui.value = _ui.value.copy(answer = "", providerLabel = "", busy = false,
                note = tr("কোড/পিন-সদৃশ লেখা AI-তে পাঠানো হয়নি।", "Code/PIN-like text was not sent to AI."), localOnly = settings.localOnly)
            switchToChat()
            return
        }
        _ui.value = _ui.value.copy(answer = "", providerLabel = "", note = "", busy = true,
            busyLabel = tr("AI উত্তর তৈরি করছে…", "Asking your configured AI…"), localOnly = settings.localOnly)
        switchToChat()
        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    AiSetup.router(this@AssistantOverlayService).complete(
                        BubbleAi.generalSystemPrompt(bn), BubbleAi.generalMessages(text),
                    )
                }
                _ui.value = _ui.value.copy(answer = answer.text, providerLabel = BubbleAi.providerLabel(answer),
                    busy = false, busyLabel = "", note = "")
            } catch (e: Exception) {
                val current = SettingsStore.state.value
                val localOnly = e is AiException && e.kind == AiException.Kind.NOT_CONFIGURED
                val notice = if (localOnly)
                    tr("কোনো অনুমোদিত প্রোভাইডার প্রস্তুত নেই; অফলাইন নিয়মে উত্তর দিলাম।", "No usable approved provider; using offline rules.")
                else tr("AI অনুরোধ সম্পূর্ণ হয়নি; এটি প্রোভাইডারে পৌঁছে থাকতে পারে। অফলাইন উত্তর দেখানো হলো।", "The AI request did not complete; the provider may have received it. Showing an offline fallback.")
                _ui.value = _ui.value.copy(
                    answer = LocalIntentRouter.reply(text, null, current.uiLanguage == UiLanguage.BN, requestMayHaveBeenSent = !localOnly),
                    providerLabel = "", busy = false, busyLabel = "", note = notice, localOnly = current.localOnly,
                )
            }
        }
    }

    /** Re-check the target after hiding VoiceDock, read it once, reject secrets, then ask an approved provider. */
    private fun draftReply(instruction: String) {
        val state = _ui.value
        val settings = SettingsStore.state.value
        val assessment = state.assessment
        val target = assessment?.takeIf { it.kind == ContextKind.FIELD_FOUND }?.snapshot?.pkg.orEmpty()
        val bn = settings.uiLanguage == UiLanguage.BN
        fun offlineDraft(message: String) {
            _ui.value = _ui.value.copy(
                draft = if (bn) "আপনার বার্তা পেয়েছি। দ্রুত দেখে জানাচ্ছি।" else "Got your message. I will check and get back to you shortly.",
                draftOptions = emptyList(), providerLabel = "", busy = false, busyLabel = "", localOnly = settings.localOnly,
                note = message,
            )
            switchToChat()
        }
        if (!settings.assistantScreenContext || target.isBlank() || assessment?.kind != ContextKind.FIELD_FOUND) {
            offlineDraft(tr("যাচাইকৃত স্ক্রিন-প্রসঙ্গ নেই — অফলাইন খসড়া দেখানো হলো।", "No verified screen context; showing an offline template."))
            return
        }
        if (!AiSetup.ready(this)) {
            offlineDraft(tr("কোনো অনুমোদিত AI প্রোভাইডার প্রস্তুত নেই; স্ক্রিন পড়া বা পাঠানো হয়নি।", "No usable approved AI provider; the screen was not read or sent."))
            return
        }

        _ui.value = _ui.value.copy(answer = "", draft = "", draftOptions = emptyList(), providerLabel = "",
            busy = true, busyLabel = tr("অ্যাপ যাচাই করে খসড়া তৈরি হচ্ছে…", "Verifying the app and drafting replies…"),
            note = tr("স্ক্রিনের লেখা অনুমোদিত AI-তে যেতে পারে; এখনো কিছু পাঠানো বা বসানো হবে না।", "Visible conversation may go to your approved AI; nothing will be sent or inserted."),
            localOnly = settings.localOnly)
        closeWindows(reset = false)
        lifecycleScope.launch {
            try {
                delay(300)
                val service = InsertAccessibilityService.instance
                // screenText verifies that the same package is still active and reads content only once.
                val visible = withContext(Dispatchers.IO) {
                    service?.screenText(maxChars = 4000, expectedPackage = target).orEmpty()
                }
                if (visible.isBlank()) {
                    offlineDraft(tr("আগের অ্যাপ আর সামনে নেই বা স্ক্রিন পড়া যায়নি — অফলাইন খসড়া দেখানো হলো।", "The original app is no longer in front or could not be read; using an offline template."))
                    return@launch
                }
                if (SecretDetector.containsSecretScreenText(visible)) {
                    _ui.value = _ui.value.copy(draft = "", draftOptions = emptyList(), providerLabel = "", busy = false, busyLabel = "",
                        note = tr("কোড/পিন-সদৃশ লেখা শনাক্ত হয়েছে; কোনো স্ক্রিনের লেখা AI-তে পাঠানো হয়নি।", "A code/PIN-like item was found; no screen text was sent to AI."))
                    switchToChat()
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    AiSetup.router(this@AssistantOverlayService).complete(
                        BubbleAi.draftSystemPrompt(bn), BubbleAi.draftMessages(instruction, visible),
                    )
                }
                val options = BubbleAi.parseDraftOptions(result.text)
                val chosen = options.firstOrNull().orEmpty()
                _ui.value = _ui.value.copy(draft = chosen, draftOptions = options, providerLabel = BubbleAi.providerLabel(result),
                    busy = false, busyLabel = "", note = tr("খসড়া তৈরি হয়েছে — এখনো কোথাও পাঠানো বা বসানো হয়নি।", "Drafts ready — nothing has been sent or inserted."))
                switchToChat()
            } catch (e: Exception) {
                val current = SettingsStore.state.value
                val notice = if (e is AiException && e.kind == AiException.Kind.NOT_CONFIGURED)
                    tr("কোনো ব্যবহারযোগ্য AI পাওয়া যায়নি; অফলাইন খসড়া দেখানো হলো।", "No usable AI provider; showing an offline template.")
                else tr("AI খসড়া তৈরি হয়নি; স্ক্রিনের লেখা প্রোভাইডারে পৌঁছে থাকতে পারে। অফলাইন টেমপ্লেট দেখানো হলো।", "AI drafting failed; the provider may have received the conversation. Showing an offline template.")
                _ui.value = _ui.value.copy(
                    draft = if (current.uiLanguage == UiLanguage.BN) "আপনার বার্তা পেয়েছি। দ্রুত দেখে জানাচ্ছি." else "Got your message. I will check and get back to you shortly.",
                    draftOptions = emptyList(), providerLabel = "", busy = false, busyLabel = "", note = notice, localOnly = current.localOnly,
                )
                switchToChat()
            }
        }
    }

    private fun selectDraft(text: String) {
        if (text in _ui.value.draftOptions) _ui.value = _ui.value.copy(draft = text)
    }

    private fun startDictation() {
        if (!Permissions.mic(this)) {
            _ui.value = _ui.value.copy(note = tr("মাইক্রোফোনের অনুমতি দিন", "Grant microphone permission"))
            openSettings()
            return
        }
        val phase = VoiceDock.dictation(this).ui.value.phase
        if (phase in setOf(DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED, DictationPhase.PROCESSING)) {
            assistantDictationStarted = true
            _ui.value = _ui.value.copy(dictationPhase = phase)
            switchToChat()
            return
        }
        assistantDictationStarted = true
        lastDictationDraft = ""
        _ui.value = _ui.value.copy(note = tr("মাইক চালু করার চেষ্টা হচ্ছে…", "Starting the microphone…"), dictationPhase = phase)
        switchToChat()
        // The microphone service itself starts the session after it is foregrounded.
        OverlayService.start(this, OverlayService.ACTION_START_DICTATION)
        lifecycleScope.launch {
            delay(1500)
            if (assistantDictationStarted && !OverlayService.foregroundOk) {
                assistantDictationStarted = false
                _ui.value = _ui.value.copy(dictationPhase = null, note = tr("মাইক চালু হয়নি — VoiceDock খুলে আবার চেষ্টা করুন", "Microphone could not start — open VoiceDock and try again"))
            }
        }
    }

    private fun explainScreen(question: String) {
        val blocker = ScreenAssistant.blocker(this)
        if (blocker != null) {
            _ui.value = _ui.value.copy(note = tr("স্ক্রিন ব্যাখ্যার জন্য অনুমতি লাগে: ", "Screen explain needs permission: ") + blocker)
            return
        }
        _ui.value = _ui.value.copy(busy = true, note = tr("অনুমতি অনুযায়ী Gemini-তে পাঠানো হচ্ছে…", "Sending to Gemini with your permission…"))
        ScreenAssistant.dismiss()
        assistantHelpPending = true
        lastHelpAnswer = ""
        lifecycleScope.launch {
            ScreenAssistant.ask(
                this@AssistantOverlayService, question,
                hideOverlay = {
                    bubble?.visibility = View.INVISIBLE
                    cardView?.visibility = View.INVISIBLE
                    chatView?.visibility = View.INVISIBLE
                },
                showOverlay = {
                    bubble?.visibility = View.VISIBLE
                    cardView?.visibility = View.VISIBLE
                    chatView?.visibility = View.VISIBLE
                },
            )
        }
    }

    private fun observeDictation() {
        lifecycleScope.launch {
            VoiceDock.dictation(this@AssistantOverlayService).ui.collect { d ->
                if (assistantDictationStarted) {
                    _ui.value = _ui.value.copy(dictationPhase = d.phase)
                    if (d.phase == DictationPhase.REVIEW) {
                        assistantDictationStarted = false
                        if (d.draft.isNotBlank() && d.draft != lastDictationDraft) {
                            lastDictationDraft = d.draft
                            _ui.value = _ui.value.copy(
                                draft = d.draft,
                                note = tr("রিভিউ করুন, তারপর ‘বসাও’ চাপুন।", "Review, then tap ‘Insert’."),
                            )
                            switchToChat()
                        }
                    }
                }
            }
        }
    }

    private fun observeScreenHelp() {
        lifecycleScope.launch {
            ScreenAssistant.ui.collect { h ->
                if (!assistantHelpPending) return@collect
                if (h.answer.isNotBlank() && h.answer != lastHelpAnswer) {
                    assistantHelpPending = false
                    lastHelpAnswer = h.answer
                    _ui.value = _ui.value.copy(answer = h.answer, note = "", busy = false)
                    switchToChat()
                } else if (h.error != null && h.error != lastHelpAnswer) {
                    assistantHelpPending = false
                    lastHelpAnswer = h.error.orEmpty()
                    _ui.value = _ui.value.copy(note = h.error.orEmpty(), busy = false)
                }
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, "settings"))
        closeWindows()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("ভাসমান সহকারী", "Floating assistant"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("বাবল চালু থাকলে Android-এর বাধ্যতামূলক নোটিফিকেশন", "Android-required notification while the bubble is on")
            setShowBadge(false)
        })
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 3,
            Intent(this, AssistantOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(tr("VoiceDock সহকারী চালু", "VoiceDock assistant is on"))
            .setContentText(tr("বাবলে চাপ দিয়ে শুরু করুন · স্ক্রিন শুধু আপনার ট্যাপে পড়া হয়", "Tap the bubble · screen is read only on your tap"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, tr("বন্ধ", "Close"), close)
            .build()
    }

    override fun onDestroy() {
        probeJob?.cancel()
        removeCard(); removeChat(); removeBubble(); scrim?.detach()
        running = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "assistant"
        const val NOTIF_ID = 43
        const val ACTION_SHOW = "com.voicedock.ai.ASSISTANT_SHOW"
        const val ACTION_HIDE = "com.voicedock.ai.ASSISTANT_HIDE"
        const val ACTION_RESCAN = "com.voicedock.ai.ASSISTANT_RESCAN"
        const val ACTION_CLOSE = "com.voicedock.ai.ASSISTANT_CLOSE"

        private const val COLOR_IDLE = 0xFF455A64.toInt()
        private const val COLOR_SCANNING = 0xFF3DDCBF.toInt()
        private const val COLOR_CONTEXT = 0xFF2E7D32.toInt()
        private const val COLOR_CHAT = 0xFF6A1B9A.toInt()

        @Volatile var running = false; private set

        /** Call from a visible Activity the first time (Android background-start rules). */
        fun start(ctx: Context, action: String = ACTION_SHOW) {
            val i = Intent(ctx, AssistantOverlayService::class.java).setAction(action)
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, i) }
            catch (e: Exception) { Log.w("VoiceDock", "assistant service start failed: ${e.javaClass.simpleName}") }
        }
    }
}
