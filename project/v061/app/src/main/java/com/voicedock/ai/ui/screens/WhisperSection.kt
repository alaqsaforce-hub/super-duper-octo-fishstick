package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.settings.EnginePreference
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import com.voicedock.ai.voice.WhisperModel
import com.voicedock.ai.voice.WhisperNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Download / choose / delete offline Whisper models. Downloads only when the user taps (data warning shown). */
@Composable
fun OfflineWhisperSection() {
    val ctx = LocalContext.current
    val models = remember { VoiceDock.whisperModels(ctx) }
    val s by SettingsStore.state.collectAsState()
    val dl by models.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(tick, dl) { models.installedModels() }
    val active = WhisperModel.byName(s.whisperModel)?.takeIf { it in installed } ?: installed.lastOrNull()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("অফলাইন Whisper (অ্যাপের ভেতরেই)", "Offline Whisper (built in)"), fontWeight = FontWeight.SemiBold)
            if (!WhisperNative.available) {
                Text(tr("এই ফোনের CPU-তে অফলাইন Whisper চলবে না (শুধু 64-bit ARM / arm64-v8a)।",
                    "Offline Whisper is not supported on this CPU (arm64-v8a only)."), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text(tr("মডেল একবার ডাউনলোড করলে ইন্টারনেট ছাড়াই ফোনে লেখা হয়। একটু লম্বা থামলে (১.৬ সেকেন্ড) লেখা আসে — লাইভ নয়, আর প্রতিটি অংশে কয়েক সেকেন্ড সময় লাগে। সত্যি কথা: Whisper বাংলায় দুর্বল — বাংলার জন্য Gemini অনেক ভালো। অফলাইনে বাংলা চাইলে শুধু Turbo মডেল কাজে লাগার মতো।",
                "Download a model once, then speech is transcribed on the phone without internet. Text appears after a longer pause (1.6 s) — not live, and each part takes a few seconds. Honestly: Whisper is weak at Bengali — Gemini is much better. For offline Bengali only the Turbo model is usable."),
                style = MaterialTheme.typography.bodySmall)
            WhisperModel.entries.forEach { m ->
                val has = m in installed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = active == m, enabled = has, onClick = { SettingsStore.update { it.copy(whisperModel = m.name) } })
                    Text(tr(m.bn, m.en), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    when {
                        dl.model == m && dl.error == null -> TextButton(onClick = { models.cancel() }) { Text(tr("থামাও", "Stop")) }
                        has -> TextButton(onClick = { models.delete(m); tick++ }) { Text(tr("মুছুন", "Delete")) }
                        else -> TextButton(enabled = dl.model == null || dl.error != null, onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { models.download(m) }
                                if (ok) { SettingsStore.update { it.copy(whisperModel = m.name) }; tick++ }
                            }
                        }) { Text(tr("ডাউনলোড", "Download")) }
                    }
                }
                if (dl.model == m) {
                    if (dl.total > 0 && dl.error == null) {
                        LinearProgressIndicator(progress = { (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(if (dl.verifying) tr("যাচাই হচ্ছে…", "Verifying…") else "${dl.done / 1_000_000} / ${dl.total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                    }
                    dl.error?.let { e ->
                        Text(when (e) {
                            "no_space" -> tr("ফোনে জায়গা কম।", "Not enough storage.")
                            "cancelled" -> tr("থামানো হয়েছে — আবার ডাউনলোড চাপলে যেখানে থেমেছিল সেখান থেকে চলবে।", "Stopped — tap Download to resume.")
                            "checksum" -> tr("ফাইল যাচাই ব্যর্থ — নষ্ট ফাইল মুছে দেওয়া হয়েছে, আবার চেষ্টা করুন।", "Checksum failed — the file was removed, try again.")
                            else -> tr("ডাউনলোড হয়নি ($e) — আবার চাপলে চলতে থাকবে।", "Download failed ($e) — tap again to resume.")
                        }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(tr("মোবাইল ডেটায় বড় মডেল নামালে খরচ হবে — Wi-Fi ভালো। ফাইল আসে Hugging Face (ggerganov/whisper.cpp) থেকে, SHA-256 মিলিয়ে দেখা হয়।",
                "Large models use a lot of mobile data — prefer Wi-Fi. Files come from Hugging Face (ggerganov/whisper.cpp) and are SHA-256 verified."),
                style = MaterialTheme.typography.bodySmall)
            if (installed.isNotEmpty() && s.engine != EnginePreference.WHISPER)
                OutlinedButton(onClick = { SettingsStore.update { it.copy(engine = EnginePreference.WHISPER) } }) {
                    Text(tr("অফলাইন Whisper ব্যবহার করুন", "Use offline Whisper"))
                }
        }
    }
}
