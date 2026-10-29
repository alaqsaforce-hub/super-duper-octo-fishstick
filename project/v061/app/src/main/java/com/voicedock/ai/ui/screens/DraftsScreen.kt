package com.voicedock.ai.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftStatus
import com.voicedock.ai.data.repositories.DraftStore
import com.voicedock.ai.insertion.ClipboardInsertion
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun statusLabel(s: DraftStatus) = when (s) {
    DraftStatus.ACTIVE -> tr("চলমান", "In progress")
    DraftStatus.REVIEW -> tr("রিভিউ বাকি", "Needs review")
    DraftStatus.INSERTED -> tr("বসানো হয়েছে", "Inserted")
    DraftStatus.SAVED -> tr("সংরক্ষিত", "Saved")
    DraftStatus.CANCELLED -> tr("বাতিল (লেখা রাখা আছে)", "Cancelled (text kept)")
    DraftStatus.CLEARED -> tr("মোছার আগের কপি", "Copy before clear")
}

@Composable
fun DraftsScreen(store: DraftStore) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drafts by store.observeRecent(200).collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<DictationDraft?>(null) }
    var confirmDelete by remember { mutableStateOf<DictationDraft?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    if (drafts.isEmpty()) {
        Text(tr("এখনো কোনো ড্রাফট নেই।", "No drafts yet."), Modifier.padding(24.dp))
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(drafts, key = { it.id }) { d ->
            Card(Modifier.fillMaxWidth().clickable { open = d }) {
                Column(Modifier.padding(12.dp)) {
                    Text("${fmt.format(Date(d.updatedAt))} · ${statusLabel(d.status)}", style = MaterialTheme.typography.labelMedium)
                    Text(d.formattedText.ifBlank { d.rawText }.take(180), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    open?.let { d ->
        val text = d.formattedText.ifBlank { d.rawText }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(statusLabel(d.status)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VoiceDock", text))
                    Toast.makeText(ctx, tr("কপি হয়েছে", "Copied"), Toast.LENGTH_SHORT).show()
                }) { Text(tr("কপি", "Copy")) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { ctx.startActivity(ClipboardInsertion.shareIntent(text)) }) { Text(tr("শেয়ার", "Share")) }
                    TextButton(onClick = {
                        scope.launch {
                            val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: tr("ডিক্টেশন", "Dictation")
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.voicedock.ai.VoiceDock.knowledge(ctx).addText(title, text, com.voicedock.ai.data.database.KbSource.DRAFT, sourceRef = "draft:${d.id}")
                            }
                            Toast.makeText(ctx, when (r) {
                                is com.voicedock.ai.knowledge.AddResult.Added -> tr("✓ নলেজ বেসে যোগ হলো", "✓ Added to knowledge")
                                is com.voicedock.ai.knowledge.AddResult.Duplicate -> tr("আগেই নলেজ বেসে আছে", "Already in knowledge")
                                else -> tr("কোনো লেখা নেই", "No text")
                            }, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(tr("নলেজে সেভ", "Save to knowledge")) }
                    TextButton(onClick = { confirmDelete = d; open = null }) { Text(tr("মুছে ফেলুন", "Delete"), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("স্থায়ীভাবে মুছবেন?", "Delete permanently?")) },
            text = { Text(tr("এই ড্রাফট ফোন থেকে মুছে যাবে, ফেরত আনা যাবে না।", "This draft will be removed from the phone and cannot be recovered.")) },
            confirmButton = { TextButton(onClick = { scope.launch { store.delete(d.id) }; confirmDelete = null }) { Text(tr("মুছুন", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("না", "No")) } },
        )
    }
}
