package com.voicedock.ai.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicedock.ai.context.AssistantAction
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.VoiceDockTheme

/**
 * Compact, NON-FOCUSABLE context card. Because it never takes input focus, the text field in the
 * app underneath keeps its cursor — so "Insert here" still targets the right box (v0.5 behaviour
 * preserved). Tapping "Type here" switches to the focusable [AssistantBubble] window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContextCard(
    state: AssistantUiState,
    onAction: (AssistantAction) -> Unit,
    onOpenChat: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    val settings by SettingsStore.state.collectAsState()
    val bn = settings.uiLanguage == UiLanguage.BN
    val a = state.assessment
    VoiceDockTheme {
        Card(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                AssistantHeader(state, bn, onClose, onSettings)
                ContextTag(state, bn)
                Text(
                    a?.explanation(bn).orEmpty().ifBlank { tr("কীভাবে সাহায্য করব?", "How can I help?") },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.draft.isNotBlank()) DraftCard(state.draft, bn, onAction)
                if (state.answer.isNotBlank()) AnswerCard(state.answer, bn)
                if (state.note.isNotBlank()) Text(state.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (a?.actions ?: emptyList()).forEach { action ->
                        AssistChip(onClick = { onAction(action) }, label = { Text(action.label(bn), style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalButton(onClick = onOpenChat) { Text(tr("✍️ টাইপ করুন", "✍️ Type here")) }
                    TextButton(onClick = onClose) { Text(tr("বন্ধ", "Close")) }
                }
                Text(
                    tr("Detect → প্রস্তাব → আপনার চাপ → তারপর লেখা বসবে।", "Detect → suggest → your tap → only then insert."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
