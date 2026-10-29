package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.SpeechLanguage
import com.voicedock.ai.settings.ThemeMode
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    openPrivacy: () -> Unit,
    openAi: () -> Unit = {},
    openConnectors: () -> Unit = {},
    openAgent: () -> Unit = {},
    resumeTick: Int = 0,
    openKnowledge: () -> Unit = {},
    openDrafts: () -> Unit = {},
) {
    val s by SettingsStore.state.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section(tr("ডিক্টেশনের ভাষা", "Dictation language"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpeechLanguage.entries.forEach { l ->
                FilterChip(selected = s.language == l, onClick = { SettingsStore.update { it.copy(language = l) } }, label = { Text(tr(l.bn, l.en)) })
            }
        }
        Text(tr("মিশ্র ভাষা: Android-এর রিকগনাইজার একবারে একটি ভাষা নেয়; বাংলা মোডে ইংরেজি শব্দ অনেক সময় বাংলা বানানে আসে।",
            "Mixed: Android recognisers take one language at a time; in Bangla mode English words may come out in Bangla script."),
            style = MaterialTheme.typography.bodySmall)

        AssistantSettingsSection(resumeTick)
        HorizontalDivider()
        EngineSection()
        KeyboardSection()
        HorizontalDivider()
        GeminiSection()
        HorizontalDivider()

        Section(tr("বিরতি", "Pauses"))
        Text(tr("ছোট বিরতি ধরা হবে: ${s.shortPauseSec} সেকেন্ড (মাইক চালু থাকে)", "Short pause after: ${s.shortPauseSec} s (mic stays on)"))
        Slider(value = s.shortPauseSec.toFloat(), onValueChange = { v -> SettingsStore.update { it.copy(shortPauseSec = v.toInt()) } }, valueRange = 1f..10f, steps = 8)
        Toggle(tr("লম্বা নীরবতায়ও শুনতে থাকুক (বেশি ব্যাটারি)", "Keep listening during long silence (more battery)"), s.keepListeningOnLongSilence) { v -> SettingsStore.update { it.copy(keepListeningOnLongSilence = v) } }
        if (!s.keepListeningOnLongSilence) {
            Text(tr("${s.longInactivityMin} মিনিট কথা না বললে মাইক বন্ধ হবে (লেখা থাকবে)", "Mic turns off after ${s.longInactivityMin} min without speech (text kept)"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, 5, 10, 30).forEach { m ->
                    FilterChip(selected = s.longInactivityMin == m, onClick = { SettingsStore.update { it.copy(longInactivityMin = m) } }, label = { Text("$m") })
                }
            }
        }

        Section(tr("লেখা", "Text"))
        Toggle(tr("লোকাল যতিচিহ্ন ও পরিষ্কার (Mode A — শব্দ বদলায় না)", "Local punctuation & cleanup (Mode A — words unchanged)"), s.autoCleanup) { v -> SettingsStore.update { it.copy(autoCleanup = v) } }
        Toggle(tr("মুখে বলা কমান্ড (দাঁড়ি, কমা, প্রশ্নবোধক, নতুন লাইন…)", "Spoken commands (full stop, comma, question mark, new line…)"), s.spokenCommands) { v -> SettingsStore.update { it.copy(spokenCommands = v) } }
        Toggle(tr("রিভিউ ছাড়াই সরাসরি বসাও (বাবল থেকে)", "Insert without review (from the bubble)"), s.autoInsert) { v -> SettingsStore.update { it.copy(autoInsert = v) } }
        if (s.autoInsert) Text(tr("সতর্কতা: ভুল শোনা শব্দও সরাসরি চলে যাবে। ডিফল্ট বন্ধ রাখাই নিরাপদ।", "Warning: misheard words go straight in. Keeping this off is safer."),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        Section(tr("চেহারা", "Appearance"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = s.uiLanguage == UiLanguage.BN, onClick = { SettingsStore.update { it.copy(uiLanguage = UiLanguage.BN) } }, label = { Text("বাংলা") })
            FilterChip(selected = s.uiLanguage == UiLanguage.EN, onClick = { SettingsStore.update { it.copy(uiLanguage = UiLanguage.EN) } }, label = { Text("English") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ThemeMode.SYSTEM to tr("সিস্টেম", "System"), ThemeMode.LIGHT to tr("লাইট", "Light"), ThemeMode.DARK to tr("ডার্ক", "Dark")).forEach { (m, l) ->
                FilterChip(selected = s.theme == m, onClick = { SettingsStore.update { it.copy(theme = m) } }, label = { Text(l) })
            }
        }
        HorizontalDivider()
        TextButton(onClick = openAi) { Text(tr("AI প্রোভাইডার ও অ্যাকাউন্ট (Gemini, ChatGPT লগইন, OpenRouter, Claude…) →", "AI providers & accounts (Gemini, ChatGPT sign-in, OpenRouter, Claude…) →")) }
        TextButton(onClick = openAgent) { Text(tr("🤖 এজেন্ট (স্ক্রিন পড়ে কাজ করে) →", "🤖 Agent (reads the screen and acts) →")) }
        TextButton(onClick = openConnectors) { Text(tr("কানেক্টর (Gmail · Drive · GitHub) →", "Connectors (Gmail · Drive · GitHub) →")) }
        TextButton(onClick = openKnowledge) { Text(tr("নলেজ বেস →", "Knowledge base →")) }
        TextButton(onClick = openDrafts) { Text(tr("ড্রাফট ইতিহাস →", "Draft history →")) }
        TextButton(onClick = openPrivacy) { Text(tr("অনুমতি ও প্রাইভেসি", "Permissions & privacy")) }
        HorizontalDivider()
        DiagnosticsSection()
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
