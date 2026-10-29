package com.voicedock.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.voicedock.ai.VoiceDock
import kotlinx.coroutines.launch
import com.voicedock.ai.insertion.ClipboardInsertion
import com.voicedock.ai.overlay.OverlayService
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.MicRed
import com.voicedock.ai.ui.theme.PauseAmber
import com.voicedock.ai.ui.theme.ReviewGreen
import com.voicedock.ai.voice.DictationController
import com.voicedock.ai.voice.DictationPhase
import kotlinx.coroutines.delay

/** Audio clips that Gemini could not transcribe are kept on the phone; retry adds their text to the draft. */
@Composable
private fun PendingAudioRow(c: DictationController, phase: DictationPhase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember(phase) { mutableIntStateOf(VoiceDock.gemini?.pendingCount() ?: 0) }
    var working by remember { mutableStateOf(false) }
    if (count == 0) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("$count টি অডিও অংশ লেখা হয়নি (ইন্টারনেট/সীমা)। অডিও ফোনে রাখা আছে।", "$count audio clip(s) not transcribed yet (network/limit). Audio is kept."), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !working, onClick = {
                working = true
                scope.launch {
                    val texts = VoiceDock.gemini?.retryPending().orEmpty()
                    c.appendRecovered(texts)
                    count = VoiceDock.gemini?.pendingCount() ?: 0
                    working = false
                    Toast.makeText(ctx, if (texts.isNotEmpty()) tr("${texts.size}টি অংশ যোগ হলো", "${texts.size} part(s) added") else tr("এখনো পাঠানো গেল না", "Still could not send"), Toast.LENGTH_SHORT).show()
                }
            }) { Text(if (working) tr("পাঠানো হচ্ছে…", "Sending…") else tr("আবার চেষ্টা", "Retry")) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(c: DictationController, startDictation: () -> Unit, openAi: () -> Unit = {}) {
    val ctx = LocalContext.current
    val ui by c.ui.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(3000); confirmClear = false } }
    val reviewing = ui.phase == DictationPhase.REVIEW || ui.phase == DictationPhase.INSERTED || ui.phase == DictationPhase.SAVED

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when {
                ui.micOn -> MicRed
                ui.phase == DictationPhase.REVIEW -> ReviewGreen
                ui.phase in setOf(DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED) -> PauseAmber
                else -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(OverlayService.phaseLabel(ui.phase), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (ui.engine.isNotBlank()) Text(ui.engine, style = MaterialTheme.typography.labelSmall)
        }
        ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        ui.note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        PendingAudioRow(c, ui.phase)

        if (reviewing) {
            var text by remember(ui.draftId, ui.phase == DictationPhase.REVIEW) { mutableStateOf(ui.draft) }
            OutlinedTextField(
                value = text, onValueChange = { text = it; c.editDraft(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                label = { Text(tr("রিভিউ ও এডিট করুন", "Review and edit")) },
            )
            if (ui.phase == DictationPhase.REVIEW) AiFormatCard(current = text, onApply = { t -> text = t; c.editDraft(t) }, openAi = openAi)
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp).heightIn(min = 160.dp)) {
                    if (ui.committed.isBlank() && ui.partial.isBlank()) {
                        Text(tr("‘বলা শুরু’ চাপুন এবং কথা বলুন। থামলে বা কাশি দিলেও লেখা থাকবে।",
                            "Tap Start and speak. Pauses or coughs do not lose text."), color = MaterialTheme.colorScheme.outline)
                    } else {
                        Text(ui.committed, style = MaterialTheme.typography.bodyLarge)
                        if (ui.partial.isNotBlank()) Text(ui.partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (ui.phase) {
                DictationPhase.IDLE, DictationPhase.INSERTED, DictationPhase.SAVED -> Button(onClick = startDictation) { Text(tr("বলা শুরু", "Start")) }
                DictationPhase.LISTENING, DictationPhase.TEMP_PAUSE -> {
                    FilledTonalButton(onClick = { c.pause() }) { Text(tr("বিরতি", "Pause")) }
                    Button(onClick = { c.finish() }) { Text(tr("শেষ ও রিভিউ", "Finish & review")) }
                }
                DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED -> {
                    Button(onClick = startDictation) { Text(tr("চালিয়ে যান", "Resume")) }
                    FilledTonalButton(onClick = { c.finish() }) { Text(tr("শেষ ও রিভিউ", "Finish & review")) }
                }
                DictationPhase.PROCESSING -> Text(tr("শেষ অংশ নেওয়া হচ্ছে…", "Collecting the last words…"))
                DictationPhase.REVIEW -> FilledTonalButton(onClick = startDictation) { Text(tr("আরও বলুন", "Add more")) }
            }
            val text = c.currentText()
            OutlinedButton(enabled = text.isNotBlank(), onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VoiceDock", text))
                Toast.makeText(ctx, tr("কপি হয়েছে", "Copied"), Toast.LENGTH_SHORT).show()
            }) { Text(tr("কপি", "Copy")) }
            OutlinedButton(enabled = text.isNotBlank(), onClick = { ctx.startActivity(ClipboardInsertion.shareIntent(text)) }) { Text(tr("শেয়ার", "Share")) }
            if (ui.phase == DictationPhase.REVIEW) OutlinedButton(onClick = {
                c.markSaved(); Toast.makeText(ctx, tr("ড্রাফট ইতিহাসে সংরক্ষিত", "Saved to draft history"), Toast.LENGTH_SHORT).show()
            }) { Text(tr("সংরক্ষণ", "Save")) }
            if (ui.phase != DictationPhase.IDLE) OutlinedButton(onClick = { c.cancel() }) { Text(tr("বাতিল", "Cancel")) }
            OutlinedButton(enabled = text.isNotBlank(), onClick = { if (confirmClear) { c.clear(); confirmClear = false } else confirmClear = true }) {
                Text(if (confirmClear) tr("নিশ্চিত? আবার চাপুন", "Sure? Tap again") else tr("মুছুন", "Clear"))
            }
        }
        Text(tr("অন্য অ্যাপের টেক্সট বক্সে সরাসরি বসাতে ভাসমান বাবল ব্যবহার করুন। ‘বাতিল’ বা ‘মুছুন’ করলেও লেখা ড্রাফট ইতিহাসে থেকে যায়।",
            "Use the floating bubble to insert directly into another app's text field. Cancelled or cleared text stays in draft history."),
            style = MaterialTheme.typography.bodySmall)
    }
}
