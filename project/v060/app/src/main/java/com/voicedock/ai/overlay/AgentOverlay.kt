package com.voicedock.ai.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicedock.ai.agent.AgentController
import com.voicedock.ai.agent.AgentPhase
import com.voicedock.ai.agent.AgentUi
import com.voicedock.ai.agent.StepStatus
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.VoiceDockTheme
import com.voicedock.ai.voice.DictationPhase
import com.voicedock.ai.voice.DictationUi

/** Agent block inside the bubble panel: presets, typed/voice goal, and the last result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentPanelSection(ui: DictationUi, onStart: (String) -> Unit, onVoiceGoal: () -> Unit, onSpeak: (String) -> Unit, onCopy: (String) -> Unit) {
    val a by AgentController.ui.collectAsState()
    val arm by AgentController.arm.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (a.phase == AgentPhase.DONE || a.phase == AgentPhase.FAILED || a.phase == AgentPhase.STOPPED) AgentResultCard(a, onSpeak, onCopy)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("🤖 এজেন্ট (স্ক্রিন পড়ে নিজে কাজ করে)", "🤖 Agent (reads the screen and acts)"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { open = !open }) { Text(if (open) "▲" else "▼") }
    }
    if (!open) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ui.phase == DictationPhase.REVIEW && ui.draft.isNotBlank())
            FilledTonalButton(onClick = { onStart(ui.draft) }) { Text(tr("এই লেখাটা কাজ হিসেবে দাও", "Run this text as a task")) }
        else if (!ui.micOn)
            FilledTonalButton(onClick = onVoiceGoal) { Text(if (arm == AgentController.VoiceArm.GOAL) tr("কাজটা বলুন… শেষে ‘শেষ’", "Say the task… then Finish") else tr("🎙 বলে কাজ দিন", "🎙 Say a task")) }
    }
    AgentController.PRESETS.forEach { p ->
        AssistChip(onClick = { onStart(p.text) }, label = { Text(p.text, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis) })
    }
    Text(tr("পাঠানো/মোছা/কল/কেনা-র আগে অনুমতি চাইবে; ব্যাংক/বিকাশ/নগদ অ্যাপ বন্ধ। চলার সময় বাবল চাপলে থামবে।",
        "Asks before send/delete/call/buy; banking apps are blocked. Tap the bubble to stop while it runs."), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun AgentResultCard(a: AgentUi, onSpeak: (String) -> Unit, onCopy: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("🤖 " + a.goal, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when (a.phase) { AgentPhase.DONE -> tr("✓ শেষ", "✓ Done"); AgentPhase.STOPPED -> tr("■ থামানো", "■ Stopped"); else -> tr("⚠ শেষ হয়নি", "⚠ Not finished") } +
                " · ${a.step} " + tr("ধাপ", "steps") + (a.model.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
            Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { Text(a.answer.ifBlank { a.error ?: "" }, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (a.answer.isNotBlank()) {
                    TextButton(onClick = { onSpeak(a.answer) }) { Text(tr("শুনুন", "Listen")) }
                    TextButton(onClick = { onCopy(a.answer) }) { Text(tr("কপি", "Copy")) }
                }
                TextButton(onClick = { AgentController.clear() }) { Text(tr("বন্ধ", "Close")) }
            }
        }
    }
}

/** Small status window shown at the top while a task runs (touch-through except when it asks you something). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentStrip(onVoiceReply: () -> Unit) {
    val a by AgentController.ui.collectAsState()
    VoiceDockTheme {
        Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
            elevation = CardDefaults.cardElevation(6.dp)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("🤖 ${a.step}/${a.maxSteps} · " + a.status, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                a.steps.lastOrNull()?.let { st ->
                    val mark = when (st.status) { StepStatus.OK -> "✓"; StepStatus.FAILED -> "✗"; StepStatus.DENIED -> "⊘"; StepStatus.BLOCKED -> "⛔"; StepStatus.INFO -> "•" }
                    Text("$mark ${st.action} → ${st.result}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                a.pending?.let { p ->
                    Text(tr("অনুমতি দেবেন? ", "Allow? ") + p.action, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(p.reason, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { AgentController.answerConfirm(true) }) { Text(tr("হ্যাঁ, করো", "Allow")) }
                        OutlinedButton(onClick = { AgentController.answerConfirm(false) }) { Text(tr("না", "Deny")) }
                        TextButton(onClick = { AgentController.stop() }) { Text(tr("থামাও", "Stop")) }
                    }
                }
                a.question?.let { q ->
                    Text("❓ $q", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onVoiceReply) { Text(tr("🎙 উত্তর বলুন", "🎙 Answer by voice")) }
                        OutlinedButton(onClick = { AgentController.answerQuestion(tr("হ্যাঁ", "yes")) }) { Text(tr("হ্যাঁ", "Yes")) }
                        OutlinedButton(onClick = { AgentController.answerQuestion(tr("না", "no")) }) { Text(tr("না", "No")) }
                        TextButton(onClick = { AgentController.stop() }) { Text(tr("থামাও", "Stop")) }
                    }
                }
                if (a.pending == null && a.question == null)
                    Text(tr("থামাতে বাবল চাপুন", "Tap the bubble to stop"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
