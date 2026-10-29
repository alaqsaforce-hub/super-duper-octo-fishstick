package com.voicedock.ai.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.MicRed
import com.voicedock.ai.ui.theme.PauseAmber
import com.voicedock.ai.ui.theme.ReviewGreen
import com.voicedock.ai.ui.theme.VoiceDockTheme
import com.voicedock.ai.voice.DictationController
import com.voicedock.ai.voice.DictationPhase
import com.voicedock.ai.voice.DictationUi
import kotlinx.coroutines.delay
import com.voicedock.ai.vision.ScreenAssistant
import com.voicedock.ai.vision.ScreenHelpUi
import com.voicedock.ai.vision.Speaker

/** The compact floating panel (not focusable → typing focus stays in the app underneath). */
@Composable
fun OverlayPanel(
    controller: DictationController,
    onInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
    onCloseBubble: () -> Unit,
    onExplainScreen: (String) -> Unit,
    onAskByVoice: () -> Unit,
    onSpeak: (String) -> Unit,
    onAgentStart: (String) -> Unit = {},
    onAgentVoiceGoal: () -> Unit = {},
) {
    val ui by controller.ui.collectAsState()
    val help by ScreenAssistant.ui.collectAsState()
    VoiceDockTheme {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(10.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusRow(ui, onClose)
                ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                ui.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                ScreenHelpCard(help, onSpeak, onCopy)
                val shown = if (ui.draft.isNotBlank()) ui.draft else ui.live
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        shown.ifBlank { tr("বলুন… (বাংলা / English)", "Speak… (Bangla / English)") },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (shown.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Controls(controller, ui, onInsert, onCopy)
                ScreenHelpButtons(help, ui, onExplainScreen, onAskByVoice)
                AgentPanelSection(ui, onAgentStart, onAgentVoiceGoal, onSpeak, onCopy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onOpenApp) { Text(tr("অ্যাপে খুলুন / এডিট", "Open app / edit")) }
                    TextButton(onClick = onCloseBubble) { Text(tr("বাবল বন্ধ", "Close bubble")) }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(ui: DictationUi, onClose: () -> Unit) {
    val color = when {
        ui.micOn -> MicRed
        ui.phase == DictationPhase.REVIEW -> ReviewGreen
        ui.phase in setOf(DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED) -> PauseAmber
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape).semantics {
            contentDescription = if (ui.micOn) tr("মাইক্রোফোন চালু", "Microphone on") else tr("মাইক্রোফোন বন্ধ", "Microphone off")
        })
        Spacer(Modifier.width(8.dp))
        Text(OverlayService.phaseLabel(ui.phase), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (ui.micOn) Text(tr("মাইক চালু", "MIC ON"), color = MicRed, style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onClose) { Text("✕") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(c: DictationController, ui: DictationUi, onInsert: (String) -> Unit, onCopy: (String) -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(3000); confirmClear = false } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (ui.phase) {
            DictationPhase.IDLE, DictationPhase.INSERTED, DictationPhase.SAVED ->
                Button(onClick = { c.startNew() }) { Text(tr("বলা শুরু", "Start")) }
            DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE -> {
                FilledTonalButton(onClick = { c.pause() }) { Text(tr("বিরতি", "Pause")) }
                Button(onClick = { c.finish() }) { Text(tr("শেষ", "Finish")) }
            }
            DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED -> {
                Button(onClick = { c.resume() }) { Text(tr("চালিয়ে যান", "Resume")) }
                FilledTonalButton(onClick = { c.finish() }) { Text(tr("শেষ", "Finish")) }
            }
            DictationPhase.PROCESSING -> Text(tr("শেষ অংশ নেওয়া হচ্ছে…", "Collecting the last words…"))
            DictationPhase.REVIEW -> {
                Button(onClick = { onInsert(ui.draft) }, enabled = ui.draft.isNotBlank()) { Text(tr("বসান", "Insert")) }
                FilledTonalButton(onClick = { c.resume() }) { Text(tr("আরও বলুন", "Add more")) }
            }
        }
        val text = if (ui.draft.isNotBlank()) ui.draft else ui.live
        OutlinedButton(onClick = { onCopy(text) }, enabled = text.isNotBlank()) { Text(tr("কপি", "Copy")) }
        if (ui.phase != DictationPhase.IDLE) OutlinedButton(onClick = { c.cancel() }) { Text(tr("বাতিল", "Cancel")) }
        OutlinedButton(onClick = { if (confirmClear) { c.clear(); confirmClear = false } else confirmClear = true }, enabled = text.isNotBlank()) {
            Text(if (confirmClear) tr("নিশ্চিত? আবার চাপুন", "Sure? Tap again") else tr("মুছুন", "Clear"))
        }
    }
}

@Composable
private fun ScreenHelpCard(help: ScreenHelpUi, onSpeak: (String) -> Unit, onCopy: (String) -> Unit) {
    if (!help.busy && help.answer.isBlank() && help.error == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (help.question.isNotBlank()) Text("❓ " + help.question, style = MaterialTheme.typography.labelMedium)
            when {
                help.busy -> Text(tr("স্ক্রিন দেখছে ও ভাবছে…", "Looking at the screen…"))
                help.error != null -> Text(help.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) { Text(help.answer, style = MaterialTheme.typography.bodyMedium) }
            }
            if (!help.busy) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (help.answer.isNotBlank()) {
                    TextButton(onClick = { onSpeak(help.answer) }) { Text(tr("শুনুন", "Listen")) }
                    TextButton(onClick = { onCopy(help.answer) }) { Text(tr("কপি", "Copy")) }
                }
                TextButton(onClick = { Speaker.stop(); ScreenAssistant.dismiss() }) { Text(tr("বন্ধ", "Close")) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenHelpButtons(help: ScreenHelpUi, ui: DictationUi, onExplain: (String) -> Unit, onAskByVoice: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = { onExplain("") }, enabled = !help.busy) { Text(tr("স্ক্রিন বুঝিয়ে দাও", "Explain screen")) }
        if (ui.phase == DictationPhase.REVIEW && ui.draft.isNotBlank())
            OutlinedButton(onClick = { onExplain(ui.draft) }, enabled = !help.busy) { Text(tr("এই প্রশ্ন স্ক্রিন নিয়ে জিজ্ঞাসা", "Ask this about the screen")) }
        else if (!ui.micOn)
            OutlinedButton(onClick = onAskByVoice, enabled = !help.busy) { Text(if (help.armed) tr("প্রশ্ন বলুন… শেষে ‘শেষ’", "Say your question… then Finish") else tr("বলে প্রশ্ন করুন", "Ask by voice")) }
        if (help.history.isNotEmpty()) TextButton(onClick = { ScreenAssistant.clearHistory() }) { Text(tr("নতুন আলাপ", "New chat")) }
    }
}
