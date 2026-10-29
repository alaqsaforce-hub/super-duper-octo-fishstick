package com.voicedock.ai.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.voicedock.ai.context.AssistantAction
import com.voicedock.ai.context.AssistantGreeting
import com.voicedock.ai.context.CatchUp
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.VoiceDockTheme
import com.voicedock.ai.voice.DictationPhase
import kotlinx.coroutines.delay

/**
 * The assistant's OWN chat bubble — the "auto text input bubble" from the product brief.
 * It types its own greeting, then lets the user type or speak. Rendered in a FOCUSABLE window
 * (see AssistantOverlayService), unlike the v0.5 dictation panel which was NOT_FOCUSABLE.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantBubble(
    state: AssistantUiState,
    onAction: (AssistantAction) -> Unit,
    onSubmit: (String) -> Unit,
    onSelectDraft: (String) -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onPauseDictation: () -> Unit,
    onResumeDictation: () -> Unit,
    onFinishDictation: () -> Unit,
) {
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp
    VoiceDockTheme {
        Card(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(10.dp),
        ) {
            Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantHeader(state, bn, onClose, onSettings)
                ContextTag(state, bn)
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.busyLabel.ifBlank { tr("স্ক্রিন পড়ছি…", "Reading the screen…") }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.greeting.isNotEmpty()) TypingBubble(state.greeting, settings.assistantSelfTyping)
                CatchUpBlock(state.catchUp, bn)
                if (state.answer.isNotBlank()) AnswerCard(state.answer, state.providerLabel, bn)
                if (state.draft.isNotBlank()) DraftCard(
                    state.draft, state.draftOptions, state.providerLabel, bn, onAction, onSelectDraft,
                )
                if (state.note.isNotBlank()) Text(state.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (state.dictationPhase in setOf(DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE, DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(OverlayService.phaseLabel(state.dictationPhase!!), style = MaterialTheme.typography.labelSmall)
                        if (state.dictationPhase in setOf(DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE))
                            TextButton(onClick = onPauseDictation) { Text(tr("বিরতি", "Pause")) }
                        else TextButton(onClick = onResumeDictation) { Text(tr("আবার শুরু", "Resume")) }
                        TextButton(onClick = onFinishDictation) { Text(tr("শেষ করুন", "Finish")) }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (state.assessment?.actions ?: defaultActions()).forEach { a ->
                        AssistChip(onClick = { onAction(a) }, label = { Text(a.label(bn), style = MaterialTheme.typography.labelSmall) })
                    }
                }

                var input by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(tr("আমি কীভাবে সাহায্য করব?", "How can I help?"), style = MaterialTheme.typography.bodySmall) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (input.isNotBlank()) { onSubmit(input); input = "" }
                        }),
                    )
                    Spacer(Modifier.width(6.dp))
                    FilledTonalButton(
                        enabled = state.dictationPhase == null || state.dictationPhase in setOf(DictationPhase.IDLE, DictationPhase.REVIEW, DictationPhase.INSERTED, DictationPhase.SAVED),
                        onClick = { onAction(AssistantAction.DICTATE) },
                    ) { Text("🎤") }
                }
                Text(
                    tr("লেখা অন্য অ্যাপে আপনার চাপ ছাড়া যাবে না।", "Nothing is typed into another app without your tap."),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun defaultActions() = listOf(
    AssistantAction.CATCH_UP, AssistantAction.TODAY_TASKS,
    AssistantAction.ASK_QUESTION, AssistantAction.GENERAL_HELP,
)

@Composable
internal fun AssistantHeader(state: AssistantUiState, bn: Boolean, onClose: () -> Unit, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("🤖", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            state.assessment?.title(bn) ?: tr("VoiceDock সহকারী", "VoiceDock assistant"),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        Text(
            if (state.localOnly) "🔒 local" else "☁ remote",
            style = MaterialTheme.typography.labelSmall,
            color = if (state.localOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onSettings) { Text("⚙") }
        TextButton(onClick = onClose) { Text("✕") }
    }
}

@Composable
internal fun ContextTag(state: AssistantUiState, bn: Boolean) {
    val a = state.assessment ?: return
    val label = when (a.kind) {
        com.voicedock.ai.context.ContextKind.FIELD_FOUND -> tr("✏️ লেখার ঘর পাওয়া গেছে", "✏️ Text box found")
        com.voicedock.ai.context.ContextKind.CONTENT_NO_FIELD -> tr("📄 পড়ার মতো কনটেন্ট", "📄 Readable content")
        com.voicedock.ai.context.ContextKind.EMPTY_SCREEN -> tr("🏠 খালি স্ক্রিন", "🏠 Empty screen")
        com.voicedock.ai.context.ContextKind.NO_ACCESS -> tr("⚠️ স্ক্রিন পড়া যায়নি", "⚠️ Screen unavailable")
        com.voicedock.ai.context.ContextKind.SECURE -> tr("⛔ সুরক্ষিত স্ক্রিন — পড়া বন্ধ", "⛔ Secure screen — not read")
    } + " · " + a.snapshot.appLabel.take(28)
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun TypingBubble(text: String, animate: Boolean) {
    var shown by remember(text, animate) { mutableStateOf(if (animate) "" else text) }
    LaunchedEffect(text, animate) {
        if (!animate) { shown = text; return@LaunchedEffect }
        shown = ""
        val chars = text.toCharArray()
        var i = 0
        while (i < chars.size) {
            i = (i + 2).coerceAtMost(chars.size)
            shown = String(chars, 0, i)
            delay(22)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text(
            shown + if (shown.length < text.length) "▍" else "",
            modifier = Modifier.padding(11.dp).heightIn(min = 44.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun CatchUpBlock(c: CatchUp?, bn: Boolean) {
    if (c != null && !c.isEmpty) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(Modifier.padding(10.dp)) {
                Text(tr("গত ${c.windowHours} ঘণ্টার সারসংক্ষেপ", "Last ${c.windowHours} hours"), style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${AssistantGreeting.bnDigits(c.total)} ${tr("ঘটনা", "events")}")
                    Text("${AssistantGreeting.bnDigits(c.important)} ${tr("গুরুত্বপূর্ণ", "important")}")
                    Text("${AssistantGreeting.bnDigits(c.todos)} ${tr("করণীয়", "to-dos")}")
                }
                c.top.take(3).forEach { r ->
                    Text("• ${r.event.title.take(70)} — ${r.why(bn)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun AnswerCard(answer: String, providerLabel: String, bn: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(answer, style = MaterialTheme.typography.bodyMedium)
            if (providerLabel.isNotBlank()) Text(providerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DraftCard(
    draft: String,
    options: List<String>,
    providerLabel: String,
    bn: Boolean,
    onAction: (AssistantAction) -> Unit,
    onSelectDraft: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text(tr("প্রস্তাবিত খসড়া (এখনো পাঠানো হয়নি)", "Suggested replies (not sent yet)"), style = MaterialTheme.typography.labelSmall)
            if (options.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEachIndexed { index, option ->
                        AssistChip(
                            onClick = { onSelectDraft(option) },
                            label = { Text("${index + 1}. ${option.take(72)}", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            Box(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                Text(draft, style = MaterialTheme.typography.bodyMedium)
            }
            if (providerLabel.isNotBlank()) Text(providerLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row {
                TextButton(onClick = { onAction(AssistantAction.INSERT_APPROVED) }) { Text(tr("✅ বসাও", "✅ Insert")) }
                TextButton(onClick = { onAction(AssistantAction.COPY) }) { Text(tr("📋 কপি", "📋 Copy")) }
            }
        }
    }
}
