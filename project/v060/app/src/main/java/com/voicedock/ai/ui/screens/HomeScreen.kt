package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.EnginePreference
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.voice.RecognizerCatalog
import com.voicedock.ai.voice.RecognizerInfo
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.ReviewGreen
import com.voicedock.ai.voice.DictationController
import com.voicedock.ai.voice.DictationPhase

@Composable
fun HomeScreen(
    resumeTick: Int,
    controller: DictationController,
    requestMic: () -> Unit,
    requestNotifications: () -> Unit,
    openOverlaySettings: () -> Unit,
    openAccessibilitySettings: () -> Unit,
    startBubble: () -> Unit,
    openWorkspace: () -> Unit,
    openPrivacy: () -> Unit,
    openSettings: () -> Unit = {},
    openAgent: () -> Unit = {},
    openKnowledge: () -> Unit = {},
    openDrafts: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val ui by controller.ui.collectAsState()
    // re-read permission state whenever the activity resumes
    val mic = remember(resumeTick) { Permissions.mic(ctx) }
    val notif = remember(resumeTick) { Permissions.notifications(ctx) }
    val overlay = remember(resumeTick) { Permissions.overlay(ctx) }
    val a11y = remember(resumeTick) { Permissions.accessibility(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.committed.isNotBlank() && ui.phase in setOf(DictationPhase.PAUSED, DictationPhase.WAITING, DictationPhase.INTERRUPTED, DictationPhase.REVIEW)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("অসমাপ্ত ড্রাফট আছে", "You have an unfinished draft"), fontWeight = FontWeight.Bold)
                    Text(ui.committed.take(160), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = openWorkspace) { Text(tr("খুলুন ও চালিয়ে যান", "Open and continue")) }
                }
            }
        }

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("সেটআপ", "Setup"), style = MaterialTheme.typography.titleMedium)
            PermRow(tr("মাইক্রোফোন (আবশ্যক)", "Microphone (required)"), mic, tr("অনুমতি দিন", "Allow"), requestMic)
            PermRow(tr("নোটিফিকেশন (মাইক চালু থাকলে দেখাতে)", "Notifications (shown while mic is on)"), notif, tr("অনুমতি দিন", "Allow"), requestNotifications)
            PermRow(tr("অন্য অ্যাপের উপরে দেখানো (বাবলের জন্য)", "Display over other apps (for the bubble)"), overlay, tr("সেটিংস খুলুন", "Open settings"), openOverlaySettings)
            PermRow(tr("Accessibility — বসানো, স্ক্রিন-সাহায্য ও এজেন্ট", "Accessibility — insert, screen help & agent"), a11y, tr("সেটিংস খুলুন", "Open settings"), openAccessibilitySettings)
            Text(tr("Accessibility কাজ করে শুধু আপনার নির্দেশে: ‘বসান’, ‘স্ক্রিন বুঝিয়ে দাও’, বা আপনার শুরু করা এজেন্ট-কাজ। পেছনে স্ক্রিন পড়ে না। v0.5-এ নতুন অনুমতি (ট্যাপ/স্ক্রল) — আপডেটের পর একবার বন্ধ করে আবার চালু করুন।",
                "Accessibility acts only on your command: Insert, Explain screen, or an agent task you started. It never reads the screen in the background. v0.5 adds tap/scroll — turn it off and on once after updating."),
                style = MaterialTheme.typography.bodySmall)
        } }

        EngineStatusCard(resumeTick, openSettings)

        // v0.6: no microphone permission needed for the assistant bubble.
        Button(onClick = startBubble, modifier = Modifier.fillMaxWidth()) { Text(tr("সহকারী বাবল চালু করুন", "Start assistant bubble")) }
        OutlinedButton(onClick = openWorkspace, modifier = Modifier.fillMaxWidth()) { Text(tr("এখানেই ডিক্টেশন করুন", "Dictate here in the app")) }
        OutlinedButton(onClick = openAgent, modifier = Modifier.fillMaxWidth()) { Text(tr("🤖 এজেন্ট — স্ক্রিন পড়ে কাজ করে দেবে", "🤖 Agent — does tasks on screen")) }

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("আজকের ভার্সন (v0.6.0)", "This version (v0.6.0)"), style = MaterialTheme.typography.titleMedium)
            Text(tr("• শুধু একটা ভাসমান মাইক বাবল — চাপ দিলে স্ক্রিন বুঝে নিজে সাহায্য শুরু করে\n• পেছনে ডিক্টেশন, নলেজ ও ড্রাফট — বাকি সব সেটিংসে\n• সব প্রধান কাজ অফলাইনে; ক্লাউড AI ঐচ্ছিক ও ডিফল্ট বন্ধ",
                "• One floating mic bubble — tap it for on-tap screen context\n• Dictation, knowledge and drafts behind the cards below\n• Core work is offline; cloud AI is optional and off by default"),
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = openKnowledge, modifier = Modifier.weight(1f)) { Text(tr("নলেজ", "Knowledge")) }
                OutlinedButton(onClick = openDrafts, modifier = Modifier.weight(1f)) { Text(tr("ড্রাফট", "Drafts")) }
            }
        } }
        TextButton(onClick = openPrivacy) { Text(tr("অনুমতি ও প্রাইভেসি", "Permissions & privacy")) }
    }
}

/** Which engine will be used for the chosen language, in plain words. */
@Composable
private fun EngineStatusCard(resumeTick: Int, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    val services = remember(resumeTick) { RecognizerCatalog.list(ctx) }
    val google = services.any { it.kind == RecognizerInfo.Kind.GOOGLE }
    val geminiReady = remember(resumeTick, s.cloudAllowed, s.localOnly) { VoiceDock.geminiConfig(ctx).let { it.allowed && !it.apiKey.isNullOrBlank() } }
    val whisperReady = remember(resumeTick, s.whisperModel) { com.voicedock.ai.voice.WhisperNative.available && VoiceDock.whisperModels(ctx).installedModels().isNotEmpty() }
    val ok = when (s.engine) {
        EnginePreference.GEMINI -> geminiReady
        EnginePreference.AUTO -> google || geminiReady || whisperReady
        EnginePreference.WHISPER -> whisperReady
        else -> google || services.any { it.kind == RecognizerInfo.Kind.OFFLINE_WHISPER }
    }
    Card(colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("ভয়েস ইঞ্জিন", "Voice engine"), style = MaterialTheme.typography.titleMedium)
            Text(tr("পছন্দ: ", "Choice: ") + tr(s.engine.bn, s.engine.en), style = MaterialTheme.typography.bodyMedium)
            Text(tr("ফোনে ভয়েস সার্ভিস: ${services.size}টি", "Phone speech services: ${services.size}") +
                (if (google) " · Google ✓" else tr(" · Google পাওয়া যায়নি", " · Google not found")) +
                (if (geminiReady) " · Gemini ✓" else tr(" · Gemini সেট করা নেই", " · Gemini not set up")) +
                (if (whisperReady) tr(" · অফলাইন Whisper ✓", " · Offline Whisper ✓") else ""), style = MaterialTheme.typography.bodySmall)
            if (!ok) Text(tr("বাংলা ডিক্টেশনের জন্য একটি ইঞ্জিন দরকার — সেটিংসে Gemini key দিন বা Google ভয়েস নিন।",
                "Bengali dictation needs an engine — add a Gemini key or get Google voice in Settings."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = openSettings) { Text(if (ok) tr("ইঞ্জিন সেটিংস", "Engine settings") else tr("ঠিক করুন", "Fix it")) }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "•", color = if (ok) ReviewGreen else MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}
