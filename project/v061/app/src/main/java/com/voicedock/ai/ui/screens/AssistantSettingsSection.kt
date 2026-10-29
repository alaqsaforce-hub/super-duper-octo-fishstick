package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr

/**
 * Just-in-time, granular assistant controls. Every sensitive switch is OFF by default and can be
 * turned off at any time. This is the UI for the v0.6 `AppSettings.assistant*` fields.
 */
@Composable
fun AssistantSettingsSection(resumeTick: Int) {
    val s by SettingsStore.state.collectAsState()
    val ctx = LocalContext.current
    val a11y = remember(resumeTick) { Permissions.accessibility(ctx) }
    val notif = remember(resumeTick) { Permissions.notificationListener(ctx) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("ভাসমান সহকারী (v0.6)", "Floating assistant (v0.6)"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("বাবলে চাপ দিলেই প্রসঙ্গ বুঝে সাহায্য করে। স্ক্রিন শুধু আপনার ট্যাপে, শুধু দৃশ্যমান লেখা — ব্যাকগ্রাউন্ডে নয়।",
                    "Tap the bubble for contextual help. The screen is read only on your tap, visible text only — never in the background."),
                style = MaterialTheme.typography.bodySmall,
            )
            AssistantToggle(tr("চাপ দিলে স্বয়ংক্রিয় স্ক্রিন স্ক্যান", "Auto screen scan on tap"), s.assistantAutoScan) { v -> SettingsStore.update { it.copy(assistantAutoScan = v) } }
            AssistantToggle(tr("স্ক্রিন প্রসঙ্গ পড়ার অনুমতি", "Screen-context consent"), s.assistantScreenContext) { v -> SettingsStore.update { it.copy(assistantScreenContext = v) } }
            if (!s.assistantScreenContext) Text(
                tr("বন্ধ থাকলে সহকারী শুধু নিজের বাবলে কথা বলবে, কোনো স্ক্রিন তথ্য নেবে না।",
                    "While off, the assistant stays in its own bubble and takes no screen data."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            )
            AssistantToggle(tr("catch-up সারসংক্ষেপ", "Catch-up summary"), s.assistantCatchUp) { v -> SettingsStore.update { it.copy(assistantCatchUp = v) } }
            AssistantToggle(tr("নিজে টাইপ করে greeting দেখাও", "Self-typing greeting"), s.assistantSelfTyping) { v -> SettingsStore.update { it.copy(assistantSelfTyping = v) } }
            AssistantToggle(tr("পেছনের স্ক্রিন dim / blur", "Dim / blur background"), s.assistantDimBlur) { v -> SettingsStore.update { it.copy(assistantDimBlur = v) } }
            AssistantToggle(tr("‘স্ক্রিন বুঝাও’-তে স্ক্রিনশট", "Screenshot for ‘Explain screen’"), s.assistantScreenshot) { v -> SettingsStore.update { it.copy(assistantScreenshot = v) } }

            Text(tr("অনুমতি অবস্থা", "Permission status"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (a11y) "✓" else "•", color = if (a11y) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(tr(" Accessibility — স্ক্রিন প্রসঙ্গের জন্য", " Accessibility — for screen context"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ctx.startActivity(Permissions.accessibilitySettings().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text(tr("খুলুন", "Open"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (notif) "✓" else "•", color = if (notif) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(tr(" Notification access — v0.7 ইভেন্ট", " Notification access — v0.7 events"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ctx.startActivity(Permissions.notificationListenerSettings().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text(tr("খুলুন", "Open"))
                }
            }
            Text(
                tr("ব্যাকগ্রাউন্ড ইভেন্ট সংগ্রহ, কন্টাক্ট ম্যাপিং ও স্থায়ী মেমোরি v0.7–v0.8-এ যোগ হবে; এখন কিছুই সংগ্রহ করা হয় না।",
                    "Background event capture, contact mapping and persistent memory arrive in v0.7–v0.8; nothing is collected now."),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun AssistantToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
