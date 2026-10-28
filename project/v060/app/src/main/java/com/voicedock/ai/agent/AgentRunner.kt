package com.voicedock.ai.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppTarget(val pkg: String, val label: String)

/** What the agent can do on the phone. Real implementation: [A11yDevice]; tests use a fake. */
interface AgentDevice {
    suspend fun snapshot(): ScreenSnapshot?
    /** JPEG of the current screen without VoiceDock's own overlay; null if unavailable (Android < 11, FLAG_SECURE apps). */
    suspend fun screenshot(): ByteArray?
    suspend fun tap(e: UiElement): Boolean
    suspend fun tapAt(x: Int, y: Int): Boolean
    suspend fun longPress(e: UiElement): Boolean
    suspend fun longPressAt(x: Int, y: Int): Boolean
    suspend fun setText(e: UiElement, text: String, submit: Boolean): Boolean
    suspend fun scroll(target: UiElement?, dir: Dir, screen: ScreenSnapshot): Boolean
    suspend fun back(): Boolean
    suspend fun home(): Boolean
    fun resolveApp(name: String): AppTarget?
    suspend fun launch(t: AppTarget): Boolean
}

data class AgentLimits(
    val maxSteps: Int = 30,
    val maxMillis: Long = 10 * 60_000L,
    val allowScreenshots: Boolean = true,
    val blockedPackages: Set<String> = emptySet(),
    val settleMs: Long = 450,
    val answerInBengali: Boolean = true,
)

enum class StepStatus { OK, FAILED, DENIED, BLOCKED, INFO }

data class AgentStep(val n: Int, val action: String, val thought: String, val result: String, val status: StepStatus)

data class PendingConfirm(val action: String, val reason: String)

enum class AgentPhase { IDLE, RUNNING, WAITING_USER, DONE, FAILED, STOPPED }

data class AgentUi(
    val phase: AgentPhase = AgentPhase.IDLE,
    val goal: String = "",
    val step: Int = 0,
    val maxSteps: Int = 0,
    val status: String = "",
    val steps: List<AgentStep> = emptyList(),
    val pending: PendingConfirm? = null,
    val question: String? = null,
    val answer: String = "",
    val notes: String = "",
    val error: String? = null,
    val model: String = "",
) {
    val running get() = phase == AgentPhase.RUNNING || phase == AgentPhase.WAITING_USER
}

data class AgentResult(val phase: AgentPhase, val answer: String, val notes: String, val steps: Int)

/** Localised strings the runner shows (injected so the runner stays platform-free and testable). */
interface AgentText {
    fun t(bn: String, en: String): String
}

/**
 * The observe → think → (safety check) → act loop. One instance per task.
 * Stops on: finish, user stop, step/time budget, accessibility lost, or repeated provider errors.
 */
class AgentRunner(
    private val device: AgentDevice,
    private val brain: AgentBrain,
    private val limits: AgentLimits = AgentLimits(),
    private val text: AgentText = object : AgentText { override fun t(bn: String, en: String) = bn },
    private val explainError: (Throwable) -> String = { it.message ?: it.javaClass.simpleName },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(AgentUi())
    val ui: StateFlow<AgentUi> = _ui.asStateFlow()

    @Volatile private var stopped = false
    private var confirm: CompletableDeferred<Boolean>? = null
    private var reply: CompletableDeferred<String>? = null

    fun stop() { stopped = true; confirm?.complete(false); reply?.complete("") }
    fun answerConfirm(allow: Boolean) { confirm?.complete(allow) }
    fun answerQuestion(answer: String) { reply?.complete(answer) }

    private fun t(bn: String, en: String) = text.t(bn, en)
    private fun set(f: (AgentUi) -> AgentUi) { _ui.value = f(_ui.value) }

    suspend fun run(goal: String): AgentResult {
        val start = now()
        val history = mutableListOf<String>()
        val replies = mutableListOf<String>()
        var notes = ""
        var lookNext = false
        var hint: String? = null
        var providerErrors = 0
        var badReplies = 0
        var lastKey = ""; var repeats = 0
        set { AgentUi(phase = AgentPhase.RUNNING, goal = goal, maxSteps = limits.maxSteps, status = t("শুরু হচ্ছে…", "Starting…")) }

        fun log(n: Int, action: String, thought: String, result: String, st: StepStatus) {
            history += "$n. $action → $result"
            set { it.copy(steps = it.steps + AgentStep(n, action, thought, result, st)) }
        }
        fun end(phase: AgentPhase, answer: String, error: String? = null): AgentResult {
            set { it.copy(phase = phase, answer = answer, notes = notes, error = error, pending = null, question = null,
                status = when (phase) { AgentPhase.DONE -> t("শেষ", "Done"); AgentPhase.STOPPED -> t("থামানো হয়েছে", "Stopped"); else -> t("শেষ হয়নি", "Not finished") }) }
            return AgentResult(phase, answer, notes, _ui.value.step)
        }
        fun partial(prefixBn: String, prefixEn: String) =
            t(prefixBn, prefixEn) + if (notes.isNotBlank()) "\n\n" + t("এ পর্যন্ত যা পাওয়া গেছে:\n", "What was found so far:\n") + notes else ""

        try {
            for (step in 1..limits.maxSteps) {
                if (stopped) return end(AgentPhase.STOPPED, partial("আপনি থামিয়েছেন।", "You stopped the agent."))
                if (now() - start > limits.maxMillis) return end(AgentPhase.FAILED, partial("সময়সীমা শেষ।", "Time limit reached."), t("সময়সীমা শেষ", "Time limit reached"))
                set { it.copy(step = step, status = t("স্ক্রিন পড়ছে…", "Reading the screen…")) }

                val snap = snapshotRetry()
                    ?: return end(AgentPhase.FAILED, partial("স্ক্রিন পড়া যাচ্ছে না — VoiceDock Accessibility চালু আছে কি না দেখুন।", "Cannot read the screen — check that VoiceDock Accessibility is on."),
                        t("Accessibility বন্ধ", "Accessibility is off"))
                // The old action-only gate ran AFTER the AI had already received the screen.
                // Stop before screenshot or model calls on known sensitive screens.
                val sensitive = snap.pkg.isBlank() || AgentSafety.isBlockedPackage(snap.pkg, limits.blockedPackages) ||
                    AgentSafety.isFinanceLabel(snap.appLabel) || snap.elements.any { it.password || AgentSafety.looksSecret(it.label) }
                if (sensitive) return end(AgentPhase.FAILED,
                    partial("সুরক্ষিত স্ক্রিনে এজেন্ট থেমেছে; স্ক্রিন AI-তে পাঠানো হয়নি।", "Agent stopped on a sensitive screen; it was not sent to the AI."),
                    t("সুরক্ষিত স্ক্রিন", "Sensitive screen"))
                val fewText = snap.elements.count { it.label.isNotBlank() } < 3
                val shot = if (limits.allowScreenshots && (lookNext || fewText)) device.screenshot() else null
                if (lookNext && shot == null) hint = t("স্ক্রিনশট পাওয়া যায়নি (এই অ্যাপ আটকায় বা স্ক্রিনশট বন্ধ) — শুধু লেখা ব্যবহার করো।", "Screenshot unavailable (blocked by the app or disabled) — use the element list only.")
                lookNext = false

                set { it.copy(status = t("ভাবছে…", "Thinking…")) }
                val br = try {
                    brain.decide(BrainInput(goal, step, limits.maxSteps, notes, history.takeLast(14), replies, snap, shot, hint, limits.answerInBengali))
                } catch (e: CancellationException) { throw e
                } catch (e: ProtocolException) {
                    badReplies++
                    log(step, "(invalid reply)", "", e.message ?: "", StepStatus.FAILED)
                    hint = "Your previous reply was not valid: ${e.message}. Reply with ONE JSON object only."
                    if (badReplies >= 3) return end(AgentPhase.FAILED, partial("মডেল ঠিকমতো উত্তর দিচ্ছে না।", "The model keeps replying in a wrong format."), t("মডেলের উত্তর বোঝা যায়নি", "Model reply not understood"))
                    continue
                } catch (e: Exception) {
                    providerErrors++
                    log(step, "(AI error)", "", explainError(e), StepStatus.FAILED)
                    if (providerErrors >= 2) return end(AgentPhase.FAILED, partial("AI থেকে উত্তর পাওয়া যাচ্ছে না: ", "No answer from the AI: ") + explainError(e), explainError(e))
                    delay(1500); continue
                }
                providerErrors = 0
                hint = null
                val d = br.decision
                val desc = d.action.describe(snap)
                set { it.copy(model = br.modelLabel, status = d.thought.ifBlank { desc }) }

                // loop guard: same action on an unchanged screen
                val key = desc + "#" + snap.signature
                repeats = if (key == lastKey) repeats + 1 else 0
                lastKey = key
                if (repeats >= 2 && d.action !is AgentAction.Finish) {
                    hint = "You repeated the same action on an unchanged screen ${repeats + 1} times. Do something different (scroll, back, look) or finish."
                }

                // ---- safety gate (code-level, independent of the model)
                val launch = (d.action as? AgentAction.OpenApp)?.let { device.resolveApp(it.name) }
                when (val v = AgentSafety.check(d, snap, limits.blockedPackages, launch?.pkg)) {
                    is AgentSafety.Verdict.Block -> { log(step, desc, d.thought, t("আটকানো: ", "Blocked: ") + t(v.reasonBn, v.reasonEn), StepStatus.BLOCKED); continue }
                    is AgentSafety.Verdict.Confirm -> {
                        val gate = CompletableDeferred<Boolean>().also { confirm = it }
                        set { it.copy(phase = AgentPhase.WAITING_USER, pending = PendingConfirm(desc, t(v.reasonBn, v.reasonEn)), status = t("আপনার অনুমতির অপেক্ষায়", "Waiting for your permission")) }
                        val ok = gate.await()
                        confirm = null
                        set { it.copy(phase = AgentPhase.RUNNING, pending = null) }
                        if (stopped) return end(AgentPhase.STOPPED, partial("আপনি থামিয়েছেন।", "You stopped the agent."))
                        if (!ok) { log(step, desc, d.thought, t("ব্যবহারকারী অনুমতি দেননি — অন্যভাবে করো বা শেষ করো", "user denied — do it another way or finish"), StepStatus.DENIED); continue }
                    }
                    AgentSafety.Verdict.Allow -> {}
                }

                // ---- act
                set { it.copy(status = d.thought.ifBlank { desc }) }
                val a = d.action
                val result: Pair<String, StepStatus> = when (a) {
                    is AgentAction.Finish -> {
                        log(step, desc, d.thought, "ok", StepStatus.OK)
                        return end(AgentPhase.DONE, a.answer.ifBlank { notes.ifBlank { t("(খালি উত্তর)", "(empty answer)") } })
                    }
                    is AgentAction.Note -> {
                        notes = (if (notes.isBlank()) "" else "$notes\n") + "• " + a.text.trim()
                        if (notes.length > 9000) notes = "…" + notes.takeLast(8500)
                        set { it.copy(notes = notes) }
                        t("নোট রাখা হয়েছে", "noted") to StepStatus.INFO
                    }
                    is AgentAction.AskUser -> {
                        val gate = CompletableDeferred<String>().also { this.reply = it }
                        set { it.copy(phase = AgentPhase.WAITING_USER, question = a.question, status = t("আপনার উত্তরের অপেক্ষায়", "Waiting for your answer")) }
                        val ans = gate.await()
                        this.reply = null
                        set { it.copy(phase = AgentPhase.RUNNING, question = null) }
                        if (stopped) return end(AgentPhase.STOPPED, partial("আপনি থামিয়েছেন।", "You stopped the agent."))
                        replies += "Q: ${a.question.take(120)} → A: ${ans.ifBlank { "(no answer)" }}"
                        t("উত্তর: ", "answer: ") + ans to StepStatus.INFO
                    }
                    AgentAction.Look -> {
                        if (!limits.allowScreenshots) t("স্ক্রিনশট সেটিংসে বন্ধ", "screenshots are disabled in settings") to StepStatus.FAILED
                        else { lookNext = true; t("পরের ধাপে স্ক্রিনশট দেখবে", "screenshot next turn") to StepStatus.INFO }
                    }
                    is AgentAction.Wait -> { delay(a.seconds * 1000L); "ok" to StepStatus.OK }
                    else -> act(a, snap, launch)
                }
                log(step, desc, d.thought, result.first, result.second)
            }
            return end(AgentPhase.FAILED, partial("ধাপের সীমা (${limits.maxSteps}) শেষ।", "Step limit (${limits.maxSteps}) reached."), t("ধাপের সীমা শেষ", "Step limit reached"))
        } catch (e: CancellationException) {
            end(AgentPhase.STOPPED, partial("থামানো হয়েছে।", "Stopped."))
            throw e
        }
    }

    private suspend fun snapshotRetry(): ScreenSnapshot? {
        repeat(4) { i -> device.snapshot()?.let { return it }; delay(300L * (i + 1)) }
        return null
    }

    /** Waits until two consecutive snapshots look the same (the app finished reacting), max ~2.5 s. */
    private suspend fun settle(): ScreenSnapshot? {
        delay(limits.settleMs)
        var prev = device.snapshot()
        repeat(5) {
            delay(300)
            val cur = device.snapshot()
            if (cur != null && prev != null && cur.signature == prev!!.signature) return cur
            prev = cur
        }
        return prev
    }

    private suspend fun act(a: AgentAction, s: ScreenSnapshot, launch: AppTarget?): Pair<String, StepStatus> {
        fun px(x: Int, y: Int) = (x.coerceIn(0, 999) * s.width / 1000) to (y.coerceIn(0, 999) * s.height / 1000)
        val missing = t("এই নম্বরের উপাদান স্ক্রিনে নেই", "no element with that id on screen") to StepStatus.FAILED
        val ok: Boolean = when (a) {
            is AgentAction.Tap -> if (a.id != null) { val e = s.byId(a.id) ?: return missing; device.tap(e) } else { val (x, y) = px(a.x!!, a.y!!); device.tapAt(x, y) }
            is AgentAction.LongPress -> if (a.id != null) { val e = s.byId(a.id) ?: return missing; device.longPress(e) } else { val (x, y) = px(a.x!!, a.y!!); device.longPressAt(x, y) }
            is AgentAction.Type -> {
                val e = s.byId(a.id) ?: s.elements.firstOrNull { it.focused && it.editable } ?: s.elements.firstOrNull { it.editable && !it.password }
                    ?: return t("লেখার ঘর পাওয়া যায়নি", "no text field found") to StepStatus.FAILED
                if (!e.editable) return t("এটা লেখার ঘর নয়", "that element is not a text field") to StepStatus.FAILED
                device.setText(e, a.text, a.submit)
            }
            is AgentAction.Scroll -> {
                val target = s.byId(a.id)?.takeIf { it.scrollable } ?: s.largestScrollable()
                val before = s.signature
                val done = device.scroll(target, a.direction, s)
                val after = settle()
                return if (!done) t("স্ক্রল হয়নি", "scroll failed") to StepStatus.FAILED
                    else if (after != null && after.signature == before) t("কিছু বদলায়নি — সম্ভবত শেষ প্রান্তে", "nothing changed — probably the end") to StepStatus.OK
                    else "ok" to StepStatus.OK
            }
            is AgentAction.OpenApp -> {
                val target = launch ?: return t("“${a.name}” নামে অ্যাপ পাওয়া যায়নি", "no app named “${a.name}” found") to StepStatus.FAILED
                if (!device.launch(target)) return t("অ্যাপ খোলা যায়নি", "could not open the app") to StepStatus.FAILED
                val after = settle()
                return t("খোলা হয়েছে: ", "opened: ") + target.label + (after?.let { if (it.pkg != target.pkg) t(" (এখন সামনে: ${it.appLabel})", " (now in front: ${it.appLabel})") else "" } ?: "") to StepStatus.OK
            }
            AgentAction.Back -> device.back()
            AgentAction.Home -> device.home()
            else -> true
        }
        if (!ok) return t("কাজটা হয়নি", "action failed") to StepStatus.FAILED
        val after = settle()
        return (if (after != null && after.signature == s.signature && a !is AgentAction.Type) t("ok (স্ক্রিন বদলায়নি)", "ok (screen unchanged)") else "ok") to StepStatus.OK
    }
}
