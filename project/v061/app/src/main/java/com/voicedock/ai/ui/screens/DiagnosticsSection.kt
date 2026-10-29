package com.voicedock.ai.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.BuildConfig
import com.voicedock.ai.security.CrashLogStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import java.io.File
import java.util.Date

/** Opt-in, app-private crash logs with explicit view/share/delete actions. */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val store = remember(context) { CrashLogStore(File(context.filesDir, "crash-reports")) }
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(refresh) { store.list() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selectedReport = selectedId?.let(store::read)

    Text(tr("ডায়াগনস্টিকস", "Diagnostics"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("স্থানীয় ক্র্যাশ লগ (সর্বশেষ ১০টি)", "Local crash log (keep newest 10)"), Modifier.weight(1f))
        Switch(checked = settings.crashLoggingEnabled, onCheckedChange = { enabled ->
            SettingsStore.update { it.copy(crashLoggingEnabled = enabled) }
        })
    }
    Text(
        tr("লগ শুধু এই ফোনের অ্যাপ-প্রাইভেট স্টোরেজে থাকে। কিছু আপলোড হয় না; শেয়ার চাপলেই কেবল পাঠানোর অ্যাপ খুলবে।",
            "Reports stay in this app's private storage on this phone. Nothing uploads automatically; Share opens another app only when you tap it."),
        style = MaterialTheme.typography.bodySmall,
    )
    if (BuildConfig.DEBUG) {
        TextButton(onClick = {
            store.save("java.lang.IllegalStateException: Synthetic VoiceDock QA crash record\n at com.voicedock.ai.DiagnosticsTest.run(DiagnosticsTest.kt:1)")
            refresh++
        }) { Text(tr("টেস্ট ক্র্যাশ লগ তৈরি করুন", "Create test crash log")) }
        Text(tr("এটি শুধু একটি নমুনা রিপোর্ট তৈরি করে; অ্যাপ বন্ধ করে না।", "Creates a sample report only; it does not crash or close the app."), style = MaterialTheme.typography.bodySmall)
    }
    if (entries.isEmpty()) {
        Text(tr("এখনো কোনো ক্র্যাশ রিপোর্ট নেই।", "No crash reports yet."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { store.deleteAll(); refresh++ }) { Text(tr("সব মুছুন", "Delete all")) }
        }
        entries.forEachIndexed { index, entry ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${Date(entry.createdAt)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { selectedId = entry.id }) { Text(tr("দেখুন", "View")) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = {
                            val report = store.read(entry.id).orEmpty()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                            runCatching { context.startActivity(Intent.createChooser(send, tr("ক্র্যাশ রিপোর্ট শেয়ার", "Share crash report"))) }
                        }) { Text(tr("শেয়ার", "Share")) }
                        TextButton(onClick = { store.delete(entry.id); refresh++ }) { Text(tr("মুছুন", "Delete")) }
                    }
                }
            }
        }
    }
    if (selectedId != null) {
        AlertDialog(
            onDismissRequest = { selectedId = null },
            confirmButton = { TextButton(onClick = { selectedId = null }) { Text(tr("বন্ধ", "Close")) } },
            title = { Text(tr("ক্র্যাশ রিপোর্ট", "Crash report")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(selectedReport ?: tr("রিপোর্ট পাওয়া যায়নি।", "Report not found."), style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}
