package com.voicedock.ai.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.overlay.OverlayLifecycleOwner
import com.voicedock.ai.overlay.OverlayService
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.ui.MainActivity
import com.voicedock.ai.voice.DictationPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * VoiceDock keyboard: QWERTY with Avro-style phonetic Bengali ([BanglaPhonetic]) or English, plus a voice strip
 * that uses the same dictation engine as the bubble. "বসাও" finishes dictation and types the text right here
 * (no Accessibility needed). Voice is disabled in password fields; nothing typed is stored or sent anywhere.
 */
class VoiceDockIme : InputMethodService() {
    private val owner = OverlayLifecycleOwner()
    internal val bengali = mutableStateOf(true)
    private val shift = mutableIntStateOf(0)          // 0 off, 1 once, 2 caps lock
    private val symbols = mutableStateOf(false)
    internal val composing = mutableStateOf("")       // Latin keys of the current Bengali word
    private val secure = mutableStateOf(false)
    private val bengaliAllowed = mutableStateOf(true)
    private var lastEditAt = 0L
    /** Test seam: a fake editor connection (unit tests); null = the real one from the system. */
    @androidx.annotation.VisibleForTesting internal var icOverride: android.view.inputmethod.InputConnection? = null
    private val ic: android.view.inputmethod.InputConnection? get() = icOverride ?: currentInputConnection

    override fun onCreate() { super.onCreate(); owner.create() }
    override fun onDestroy() { owner.destroy(); super.onDestroy() }
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { owner.attach(it) }
        return ComposeView(this).apply { owner.attach(this); setContent { KeyboardUi() } }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.value = ""
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        secure.value = isPassword(cls, v)
        bengaliAllowed.value = cls == InputType.TYPE_CLASS_TEXT && !secure.value && v !in NO_BENGALI_VARIATIONS
        bengali.value = SettingsStore.state.value.imeBengali
        symbols.value = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        shift.value = 0
    }

    override fun onFinishInputView(finishingInput: Boolean) { commitComposing(); super.onFinishInputView(finishingInput) }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composing.value.isEmpty()) return
        // Ignore echoes of our own edits; afterwards a cursor outside the composing word means the user moved it.
        if (SystemClock.uptimeMillis() - lastEditAt < 400) return
        val outside = candidatesStart < 0 || newSelStart != newSelEnd || newSelStart < candidatesStart || newSelStart > candidatesEnd
        if (outside) { composing.value = ""; ic?.finishComposingText() }
    }

    internal val typingBengali get() = bengali.value && bengaliAllowed.value

    // ---------- typing ----------
    private fun touched() { lastEditAt = SystemClock.uptimeMillis() }

    internal fun letter(c: Char) {
        val ic = ic ?: return
        val ch = if (shift.intValue > 0) c.uppercaseChar() else c
        if (shift.intValue == 1) shift.intValue = 0
        touched()
        if (typingBengali) {
            composing.value += ch
            ic.setComposingText(BanglaPhonetic.convert(composing.value), 1)
        } else ic.commitText(ch.toString(), 1)
    }

    /** Phonetic helper keys (` and +) belong to the composing word. */
    internal fun phoneticKey(c: Char) {
        if (!typingBengali) { text(c.toString()); return }
        val ic = ic ?: return
        touched(); composing.value += c
        ic.setComposingText(BanglaPhonetic.convert(composing.value), 1)
    }

    internal fun commitComposing() {
        if (composing.value.isNotEmpty()) { touched(); ic?.finishComposingText(); composing.value = "" }
    }

    internal fun text(s: String) {
        commitComposing(); touched()
        ic?.commitText(s, 1)
    }

    internal fun digitOrSymbol(s: String) {
        val out = if (typingBengali && s.length == 1 && s[0].isDigit()) BanglaPhonetic.convert(s) else s
        text(out)
    }

    internal fun backspace() {
        val ic = ic ?: return
        touched()
        val c = composing.value
        if (c.isNotEmpty()) {
            composing.value = c.dropLast(1)
            if (composing.value.isEmpty()) ic.commitText("", 1) else ic.setComposingText(BanglaPhonetic.convert(composing.value), 1)
        } else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    internal fun enter() {
        commitComposing()
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic?.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun toggleLanguage() {
        commitComposing()
        bengali.value = !bengali.value
        SettingsStore.update { it.copy(imeBengali = bengali.value) }
    }

    private fun showPicker() = getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    // ---------- voice ----------
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun micTap() {
        if (secure.value) return
        if (!Permissions.mic(this)) {
            toast("মাইকের অনুমতি দরকার — VoiceDock অ্যাপ খুলছি")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val d = VoiceDock.dictation(this)
        val ui = d.ui.value
        if (ui.micOn) { d.pause(); return }
        commitComposing()
        // Keep the mic legal in the background (foreground service). Some phones may refuse from a keyboard —
        // the dictation then may stop when the keyboard hides; documented in LIMITATIONS.
        runCatching { OverlayService.start(this, OverlayService.ACTION_HOLD) }
        owner.lifecycleScope.launch {
            var waited = 0
            while (!OverlayService.foregroundOk && waited < 1500) { delay(100); waited += 100 }
            when (ui.phase) {
                DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED, DictationPhase.TEMP_PAUSE -> d.resume()
                else -> d.startNew()
            }
        }
    }

    private fun insertTap() {
        val d = VoiceDock.dictation(this)
        val ui = d.ui.value
        when (ui.phase) {
            DictationPhase.REVIEW -> commitDictation(ui.draft.ifBlank { d.currentText() })
            DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED -> {
                d.finishHandler = { t -> commitDictation(t) }
                d.finish()
            }
            else -> {}
        }
    }

    private fun commitDictation(t: String) {
        val ic = ic
        if (ic == null || !isInputViewShown || t.isBlank()) { toast("লেখাটা ড্রাফটে রাখা আছে — VoiceDock → ড্রাফট"); return }
        commitComposing()
        ic.commitText(t, 1)
        VoiceDock.dictation(this).markInserted("ime:" + (currentInputEditorInfo?.packageName ?: "?"))
    }

    private fun cancelTap() {
        val d = VoiceDock.dictation(this)
        if (d.ui.value.phase == DictationPhase.REVIEW) d.clear() else d.cancel()
    }

    // ---------- UI ----------
    @Composable
    private fun KeyboardUi() {
        Column(Modifier.fillMaxWidth().background(BG).navigationBarsPadding().padding(horizontal = 3.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            VoiceStrip()
            if (symbols.value) SymbolRows() else LetterRows()
            BottomRow()
        }
    }

    @Composable
    private fun VoiceStrip() {
        val ui by VoiceDock.dictation(this).ui.collectAsState()
        val c = composing.value
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (secure.value) {
                Text("🔒 পাসওয়ার্ড ঘর — ভয়েস বন্ধ", color = DIM, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                return@Row
            }
            val active = ui.phase in setOf(DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED, DictationPhase.WAITING,
                DictationPhase.INTERRUPTED, DictationPhase.PROCESSING, DictationPhase.REVIEW)
            StripButton(if (ui.micOn) "⏸" else "🎤", if (ui.micOn) Color(0xFFE5534B) else ACCENT) { micTap() }
            val shown = when {
                c.isNotEmpty() -> "$c → ${BanglaPhonetic.convert(c)}"
                ui.phase == DictationPhase.REVIEW -> ui.draft
                active -> ui.live.ifBlank { ui.note ?: if (ui.micOn) "বলুন…" else "থামানো আছে" }
                else -> if (typingBengali) "বাংলা ফোনেটিক · ami → আমি" else "VoiceDock keyboard"
            }
            Text(shown, color = if (active || c.isNotEmpty()) Color.White else DIM, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (active) {
                StripButton("✓ বসাও", Color(0xFF2E7D32)) { insertTap() }
                StripButton("✕", KEY2) { cancelTap() }
            }
        }
    }

    @Composable
    private fun StripButton(label: String, color: Color, onClick: () -> Unit) {
        val view = LocalView.current
        Box(Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(color)
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }

    @Composable
    private fun LetterRows() {
        KeyRow { "qwertyuiop".forEach { LetterKey(it) } }
        KeyRow { Spacer(Modifier.weight(0.5f)); "asdfghjkl".forEach { LetterKey(it) }; Spacer(Modifier.weight(0.5f)) }
        KeyRow {
            val s = shift.intValue
            Key(if (s == 2) "⇪" else "⇧", Modifier.weight(1.5f), if (s > 0) ACCENT else KEY2) { shift.intValue = (s + 1) % 3 }
            "zxcvbnm".forEach { LetterKey(it) }
            BackspaceKey(Modifier.weight(1.5f))
        }
    }

    @Composable
    private fun SymbolRows() {
        KeyRow { "1234567890".forEach { ch -> Key(if (typingBengali) BanglaPhonetic.convert(ch.toString()) else ch.toString(), Modifier.weight(1f)) { digitOrSymbol(ch.toString()) } } }
        KeyRow { listOf("@", "#", if (typingBengali) "৳" else "$", "_", "&", "-", "+", "(", ")", "/").forEach { s -> Key(s, Modifier.weight(1f)) { text(s) } } }
        KeyRow {
            if (typingBengali) {
                Key("্", Modifier.weight(1f), KEY2) { text("\u09CD") }
                Key("ঃ", Modifier.weight(1f), KEY2) { text("ঃ") }
            } else Key("=", Modifier.weight(1f)) { text("=") }
            listOf("*", "\"", "'", ":", ";", "!", "?").forEach { s -> Key(s, Modifier.weight(1f)) { text(s) } }
            BackspaceKey(Modifier.weight(1.4f))
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BottomRow() {
        val view = LocalView.current
        KeyRow {
            Key(if (symbols.value) "ABC" else "?123", Modifier.weight(1.4f), KEY2) { commitComposing(); symbols.value = !symbols.value }
            Box(Modifier.weight(1.3f).height(KEY_H).clip(RoundedCornerShape(6.dp)).background(KEY2)
                .combinedClickable(onClick = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); toggleLanguage() }, onLongClick = { showPicker() }),
                contentAlignment = Alignment.Center) {
                Text(if (!bengaliAllowed.value) "EN" else if (bengali.value) "বাং" else "EN", color = Color.White, fontSize = 15.sp)
            }
            if (typingBengali) Key("`", Modifier.weight(0.9f), KEY2) { phoneticKey('`') } else Key(",", Modifier.weight(0.9f)) { text(",") }
            Key(if (typingBengali) "বাংলা" else "English", Modifier.weight(3.6f)) { text(" ") }
            Key(if (typingBengali) "।" else ".", Modifier.weight(0.9f)) { text(if (typingBengali) "।" else ".") }
            Key("↵", Modifier.weight(1.4f), ACCENT) { enter() }
        }
    }

    @Composable
    private fun KeyRow(content: @Composable RowScope.() -> Unit) =
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)

    @Composable
    private fun RowScope.LetterKey(c: Char) {
        val up = shift.intValue > 0
        val label = if (up) c.uppercaseChar() else c
        val hint = if (typingBengali) BanglaPhonetic.hint(label.toString()) else null
        Key(label.toString(), Modifier.weight(1f), hint = hint) { letter(c) }
    }

    @Composable
    private fun Key(label: String, modifier: Modifier, color: Color = KEY, hint: String? = null, onClick: () -> Unit) {
        val view = LocalView.current
        Box(modifier.height(KEY_H).clip(RoundedCornerShape(6.dp)).background(color)
            .clickable { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() },
            contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = if (label.length > 3) 14.sp else 20.sp)
            if (hint != null) Text(hint, color = DIM, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp))
        }
    }

    @Composable
    private fun BackspaceKey(modifier: Modifier) {
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        Box(modifier.height(KEY_H).clip(RoundedCornerShape(6.dp)).background(KEY2).pointerInput(Unit) {
            detectTapGestures(onPress = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                backspace()
                val repeat = scope.launch { delay(400); while (true) { backspace(); delay(55) } }
                tryAwaitRelease()
                repeat.cancel()
            })
        }, contentAlignment = Alignment.Center) { Text("⌫", color = Color.White, fontSize = 20.sp) }
    }

    companion object {
        private val BG = Color(0xFF1B1C20)
        private val KEY = Color(0xFF3B3D44)
        private val KEY2 = Color(0xFF2A2C31)
        private val ACCENT = Color(0xFF3F6FD8)
        private val DIM = Color(0xFFB0B3BA)
        private val KEY_H = 48.dp
        private val NO_BENGALI_VARIATIONS = setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER)

        fun isPassword(cls: Int, variation: Int) =
            (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}
