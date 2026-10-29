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
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voicedock.ai.R
import com.voicedock.ai.agent.AgentController
import com.voicedock.ai.agent.AgentPhase
import com.voicedock.ai.agent.OverlayHider
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.insertion.TextInserter
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.MainActivity
import com.voicedock.ai.voice.DictationPhase
import com.voicedock.ai.voice.DictationUi
import com.voicedock.ai.vision.ScreenAssistant
import com.voicedock.ai.vision.Speaker
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service (type = microphone) that:
 *  - keeps dictation alive while the user works in other apps (persistent notification, Android rule),
 *  - shows the optional floating bubble + panel overlay (needs "Display over other apps").
 * Must be started while VoiceDock is in the foreground (Android 14 while-in-use microphone rule).
 */
class OverlayService : LifecycleService() {
    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: ComposeView? = null
    private var panelOwner: OverlayLifecycleOwner? = null
    private var strip: ComposeView? = null
    private var stripOwner: OverlayLifecycleOwner? = null
    private var stripParams: WindowManager.LayoutParams? = null
    private val hider = object : OverlayHider {
        override fun hide() { bubble?.visibility = View.INVISIBLE; panel?.visibility = View.INVISIBLE; strip?.visibility = View.INVISIBLE }
        override fun show() { bubble?.visibility = View.VISIBLE; panel?.visibility = View.VISIBLE; strip?.visibility = View.VISIBLE }
    }
    private var showBubble = false
    private var lastNotifiedPhase: DictationPhase? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        createChannel()
        AgentController.overlayHider = hider
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        val dictation = VoiceDock.dictation(this)
        when (intent?.action) {
            ACTION_BUBBLE -> { showBubble = true; addBubble() }
            ACTION_HOLD -> {}
            // Start only AFTER startForeground(type=microphone) actually succeeded.
            ACTION_START_DICTATION -> dictation.startNew()
            ACTION_PAUSE -> dictation.pause()
            ACTION_RESUME -> dictation.resume()
            ACTION_FINISH -> dictation.finish()
            ACTION_CLOSE -> { closeEverything(); return START_NOT_STICKY }
        }
        if (!observing) observe()
        return START_NOT_STICKY
    }

    private var observing = false
    private fun observe() {
        observing = true
        val dictation = VoiceDock.dictation(this)
        dictation.onReviewReady = { text -> if (showBubble && !ScreenAssistant.ui.value.armed && AgentController.arm.value == AgentController.VoiceArm.NONE) insertNow(text) }
        lifecycleScope.launch {
            AgentController.ui.collect { a ->
                if (a.running) {
                    if (strip == null) { removePanel(); addStrip() }
                    setStripTouchable(a.phase == AgentPhase.WAITING_USER)
                } else if (strip != null) {
                    removeStrip()
                    if (showBubble) addPanel() // shows the result card
                }
                updateBubble(VoiceDock.dictation(this@OverlayService).ui.value)
            }
        }
        lifecycleScope.launch {
            dictation.ui.collect { ui ->
                updateBubble(ui)
                // "ask by voice": the finished dictation becomes the question about the current screen
                if (ScreenAssistant.ui.value.armed && ui.phase == DictationPhase.REVIEW && ui.draft.isNotBlank()) {
                    ScreenAssistant.arm(false)
                    val q = ui.draft
                    dictation.markSaved()
                    explainScreen(q)
                }
                // "say a task" / "answer by voice" for the agent
                val arm = AgentController.arm.value
                if (arm != AgentController.VoiceArm.NONE && ui.phase == DictationPhase.REVIEW && ui.draft.isNotBlank()) {
                    AgentController.armVoice(AgentController.VoiceArm.NONE)
                    val t = ui.draft
                    dictation.markSaved()
                    if (arm == AgentController.VoiceArm.GOAL) AgentController.start(this@OverlayService, t) else AgentController.answerQuestion(t)
                }
                if (ui.phase != lastNotifiedPhase) {
                    lastNotifiedPhase = ui.phase
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ui))
                }
                // nothing to keep alive: no bubble and no active/paused dictation → stop the service
                if (!showBubble && !AgentController.ui.value.running && ui.phase in setOf(DictationPhase.IDLE, DictationPhase.REVIEW, DictationPhase.INSERTED, DictationPhase.SAVED)) {
                    stopSelfClean()
                }
            }
        }
    }

    private fun goForeground(): Boolean {
        if (!Permissions.mic(this)) {
            Toast.makeText(this, tr("মাইক্রোফোনের অনুমতি নেই", "Microphone permission missing"), Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(VoiceDock.dictation(this).ui.value), type)
            foregroundOk = true
            true
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException when started from background
            Log.w("VoiceDock", "startForeground failed: ${e.javaClass.simpleName}")
            Toast.makeText(this, tr("VoiceDock অ্যাপ খুলে আবার চেষ্টা করুন", "Open VoiceDock and try again"), Toast.LENGTH_LONG).show()
            false
        }
    }

    // ------------------------------------------------------------------ bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (bubble != null || !Permissions.overlay(this)) {
            if (!Permissions.overlay(this)) Toast.makeText(this, tr("‘অন্য অ্যাপের উপরে দেখানো’ অনুমতি দিন", "Allow ‘Display over other apps’"), Toast.LENGTH_LONG).show()
            return
        }
        val s = SettingsStore.state.value
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            val pad = size / 4
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
            contentDescription = tr("VoiceDock ভয়েস বাবল", "VoiceDock voice bubble")
            elevation = 8f
        }
        val p = WindowManager.LayoutParams(size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = s.bubbleX; y = s.bubbleY
        }
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
                    else if (AgentController.ui.value.running) { AgentController.stop(); toast(tr("এজেন্ট থামানো হচ্ছে…", "Stopping the agent…")) }
                    else togglePanel()
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(view, p) }.onSuccess { bubble = view; bubbleParams = p }
            .onFailure { Log.w("VoiceDock", "bubble add failed: ${it.javaClass.simpleName}") }
        updateBubble(VoiceDock.dictation(this).ui.value)
    }

    private fun updateBubble(ui: DictationUi) {
        val b = bubble ?: return
        val color = when {
            AgentController.ui.value.running -> COLOR_AGENT
            ui.micOn -> COLOR_LISTENING
            ui.phase == DictationPhase.PAUSED || ui.phase == DictationPhase.WAITING || ui.phase == DictationPhase.INTERRUPTED -> COLOR_PAUSED
            ui.phase == DictationPhase.REVIEW -> COLOR_REVIEW
            else -> COLOR_IDLE
        }
        (b.background as? GradientDrawable)?.setColor(color)
    }

    // ------------------------------------------------------------------ panel

    private fun togglePanel() { if (panel != null) removePanel() else addPanel() }

    private fun addPanel() {
        if (panel != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view)
        owner.create()
        view.setContent {
            OverlayPanel(
                controller = VoiceDock.dictation(this),
                onInsert = { insertNow(it) },
                onCopy = { com.voicedock.ai.insertion.ClipboardInsertion.insert(this, it); toast(tr("কপি হয়েছে", "Copied")) },
                onOpenApp = { openApp(); removePanel() },
                onClose = { removePanel() },
                onCloseBubble = { closeEverything() },
                onExplainScreen = { q -> explainScreen(q) },
                onAskByVoice = {
                    val b = ScreenAssistant.blocker(this)
                    if (b != null) toast(b) else { ScreenAssistant.arm(true); VoiceDock.dictation(this).startNew() }
                },
                onSpeak = { text -> Speaker.speak(this, text) { toast(it) } },
                onAgentStart = { goal -> startAgent(goal) },
                onAgentVoiceGoal = {
                    val b = AgentController.blocker(this)
                    if (b != null) toast(b) else { AgentController.armVoice(AgentController.VoiceArm.GOAL); VoiceDock.dictation(this).startNew() }
                },
            )
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            // NOT_FOCUSABLE keeps the keyboard/input focus in the app underneath → Insert targets that field
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { wm.addView(view, p) }.onSuccess { panel = view; panelOwner = owner }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "panel add failed: ${it.javaClass.simpleName}") }
    }

    private fun startAgent(goal: String) {
        val b = AgentController.blocker(this)
        if (b != null) { toast(b); return }
        val d = VoiceDock.dictation(this)
        if (d.ui.value.phase == DictationPhase.REVIEW) d.markSaved()
        AgentController.start(this, goal)
    }

    private fun addStrip() {
        if (strip != null) return
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(this)
        owner.attach(view)
        owner.create()
        view.setContent {
            AgentStrip(onVoiceReply = { AgentController.armVoice(AgentController.VoiceArm.REPLY); VoiceDock.dictation(this).startNew() })
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP; y = (24 * resources.displayMetrics.density).toInt() }
        runCatching { wm.addView(view, p) }.onSuccess { strip = view; stripOwner = owner; stripParams = p }
            .onFailure { owner.destroy(); Log.w("VoiceDock", "strip add failed: ${it.javaClass.simpleName}") }
    }

    /** While the agent acts, the strip lets touches through (the agent's taps must reach the app); it takes touches only when asking you. */
    private fun setStripTouchable(on: Boolean) {
        val v = strip ?: return; val p = stripParams ?: return
        // v0.6 fix: flip ONLY the not-touchable bit; the old code replaced p.flags wholesale
        // and silently dropped FLAG_NOT_FOCUSABLE / other window flags.
        val base = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val next = if (on) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (p.flags != next) { p.flags = next; runCatching { wm.updateViewLayout(v, p) } }
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        stripOwner?.destroy()
        strip = null; stripOwner = null; stripParams = null
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panelOwner?.destroy()
        panel = null; panelOwner = null
    }

    private fun explainScreen(question: String) {
        lifecycleScope.launch {
            ScreenAssistant.ask(this@OverlayService, question,
                hideOverlay = { bubble?.visibility = View.INVISIBLE; panel?.visibility = View.INVISIBLE },
                showOverlay = { bubble?.visibility = View.VISIBLE; panel?.visibility = View.VISIBLE })
        }
    }

    private fun insertNow(text: String) {
        val out = TextInserter.insert(this, text)
        toast(tr(out.result.bn, out.result.en))
        VoiceDock.dictation(this).markInserted(out.destination)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, "workspace"))
    }

    private fun closeEverything() {
        val d = VoiceDock.dictation(this)
        if (d.ui.value.micOn) d.pause() // text is kept (persisted) — nothing is discarded
        showBubble = false
        stopSelfClean()
    }

    private fun stopSelfClean() {
        if (AgentController.ui.value.running) AgentController.stop()
        removeStrip()
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (AgentController.overlayHider === hider) AgentController.overlayHider = null
        removeStrip()
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        VoiceDock.dictation(this).onReviewReady = null
        running = false
        foregroundOk = false
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("ভয়েস ডিক্টেশন", "Voice dictation"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("মাইক্রোফোন চালু থাকলে এই নোটিফিকেশন দেখা যায়", "Shown while the microphone may be in use")
            setShowBadge(false)
        })
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, OverlayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(ui: DictationUi): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_SCREEN, "workspace"), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(phaseLabel(ui.phase))
            .setContentText(if (ui.micOn) tr("মাইক্রোফোন চালু — VoiceDock শুনছে", "Microphone on — VoiceDock is listening")
                else tr("মাইক্রোফোন বন্ধ", "Microphone off"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (ui.micOn) b.addAction(0, tr("বিরতি", "Pause"), pi(ACTION_PAUSE, 2))
        else if (ui.phase in setOf(DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED)) b.addAction(0, tr("আবার শুরু", "Resume"), pi(ACTION_RESUME, 3))
        if (ui.phase != DictationPhase.IDLE && ui.phase != DictationPhase.REVIEW) b.addAction(0, tr("শেষ", "Finish"), pi(ACTION_FINISH, 4))
        b.addAction(0, tr("বন্ধ", "Close"), pi(ACTION_CLOSE, 5))
        return b.build()
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    companion object {
        const val CHANNEL = "dictation"
        const val NOTIF_ID = 42
        const val ACTION_BUBBLE = "com.voicedock.ai.BUBBLE"
        const val ACTION_HOLD = "com.voicedock.ai.HOLD"
        const val ACTION_START_DICTATION = "com.voicedock.ai.START_DICTATION"
        const val ACTION_PAUSE = "com.voicedock.ai.PAUSE"
        const val ACTION_RESUME = "com.voicedock.ai.RESUME"
        const val ACTION_FINISH = "com.voicedock.ai.FINISH"
        const val ACTION_CLOSE = "com.voicedock.ai.CLOSE"
        private const val COLOR_IDLE = 0xFF455A64.toInt()
        private const val COLOR_LISTENING = 0xFFD32F2F.toInt()
        private const val COLOR_PAUSED = 0xFFF9A825.toInt()
        private const val COLOR_REVIEW = 0xFF2E7D32.toInt()
        private const val COLOR_AGENT = 0xFF6A1B9A.toInt()
        @Volatile var running = false; private set
        /** True once startForeground(type=microphone) succeeded — the mic stays usable from other apps. */
        @Volatile var foregroundOk = false; private set

        fun phaseLabel(p: DictationPhase): String = when (p) {
            DictationPhase.IDLE -> tr("প্রস্তুত", "Ready")
            DictationPhase.LISTENING -> tr("শুনছে…", "Listening…")
            DictationPhase.TEMP_PAUSE -> tr("থেমেছেন — শুনছে", "Pause detected — still listening")
            DictationPhase.PAUSED -> tr("বিরতিতে", "Paused")
            DictationPhase.WAITING -> tr("অপেক্ষায় (মাইক বন্ধ)", "Waiting (mic off)")
            DictationPhase.INTERRUPTED -> tr("বাধা পেয়েছে", "Interrupted")
            DictationPhase.PROCESSING -> tr("প্রস্তুত করছে…", "Processing…")
            DictationPhase.REVIEW -> tr("রিভিউ করুন", "Review")
            DictationPhase.INSERTED -> tr("বসানো হয়েছে", "Inserted")
            DictationPhase.SAVED -> tr("সংরক্ষিত", "Saved")
        }

        /** Call only from a foreground Activity (Android 14 microphone FGS rule). */
        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action)
            try { androidx.core.content.ContextCompat.startForegroundService(ctx, i); running = true }
            catch (e: Exception) { Log.w("VoiceDock", "service start failed: ${e.javaClass.simpleName}") }
        }
    }
}
