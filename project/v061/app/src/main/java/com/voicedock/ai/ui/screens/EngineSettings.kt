package com.voicedock.ai.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicedock.ai.ai.AiPolicy
import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.AiSetup
import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.ai.GeminiModel
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.EnginePreference
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.theme.ReviewGreen
import com.voicedock.ai.vision.ScreenAssistant
import com.voicedock.ai.voice.LangSupport
import com.voicedock.ai.voice.RecognizerCatalog
import com.voicedock.ai.voice.RecognizerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun openLink(ctx: Context, url: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (e: Exception) {
        // no Play Store app → open the web page instead
        if (url.startsWith("market://")) runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("market://", "https://play.google.com/store/apps/"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun supportLabel(s: LangSupport?) = when (s) {
    LangSupport.ONLINE_OR_INSTALLED -> tr("✓ এই ভাষা পারে", "✓ supports this language")
    LangSupport.DOWNLOADABLE -> tr("⬇ অফলাইন মডেল ডাউনলোড করা যায়", "⬇ offline model downloadable")
    LangSupport.NOT_SUPPORTED -> tr("✗ এই ভাষা পারে না", "✗ does not support this language")
    LangSupport.UNKNOWN, null -> tr("? যাচাই করা যায়নি (চালিয়ে দেখা হবে)", "? could not check (will be tried)")
}

private fun kindLabel(k: RecognizerInfo.Kind) = when (k) {
    RecognizerInfo.Kind.GOOGLE -> "Google"
    RecognizerInfo.Kind.OFFLINE_WHISPER -> tr("অফলাইন (Whisper ধরনের)", "Offline (Whisper-type)")
    RecognizerInfo.Kind.VENDOR -> tr("ফোন কোম্পানির নিজস্ব", "Phone vendor's own")
    RecognizerInfo.Kind.OTHER -> tr("অন্যান্য", "Other")
}

/** Speech engine choice + auto-detected phone speech services + install suggestions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineSection() {
    val ctx = LocalContext.current
    val s by SettingsStore.state.collectAsState()
    var services by remember { mutableStateOf<List<RecognizerInfo>?>(null) }
    var support by remember { mutableStateOf<Map<String, LangSupport>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh, s.language) {
        val list = withContext(Dispatchers.Default) { RecognizerCatalog.list(ctx) }
        services = RecognizerCatalog.order(list, s.phoneService)
        val map = HashMap<String, LangSupport>()
        for (r in list) { map[r.id] = RecognizerCatalog.checkSupport(ctx, r, s.language.tag); support = HashMap(map) }
    }

    Text(tr("স্পিচ ইঞ্জিন", "Speech engine"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EnginePreference.entries.forEach { e ->
            FilterChip(selected = s.engine == e, onClick = { SettingsStore.update { it.copy(engine = e) } }, label = { Text(tr(e.bn, e.en)) })
        }
    }

    OfflineWhisperSection()

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("ফোনে পাওয়া ভয়েস সার্ভিস", "Speech services found on this phone"), fontWeight = FontWeight.SemiBold)
            val list = services
            when {
                list == null -> Text(tr("খোঁজা হচ্ছে…", "Searching…"))
                list.isEmpty() -> Text(tr("কোনো ভয়েস সার্ভিস পাওয়া যায়নি। নিচের যেকোনো একটি ইনস্টল করুন বা Gemini key দিন।",
                    "No speech service found. Install one below or add a Gemini key."), color = MaterialTheme.colorScheme.error)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.phoneService == null, onClick = { SettingsStore.update { it.copy(phoneService = null) } })
                        Text(tr("স্বয়ংক্রিয় ক্রম (Google আগে, ফোন কোম্পানির সার্ভিস শেষে)", "Automatic order (Google first, vendor services last)"))
                    }
                    list.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.phoneService == r.id, onClick = { SettingsStore.update { it.copy(phoneService = r.id) } })
                            Column(Modifier.weight(1f)) {
                                Text(r.label + if (r.isSystemDefault) tr(" (ফোনের ডিফল্ট)", " (phone default)") else "", style = MaterialTheme.typography.bodyMedium)
                                Text("${kindLabel(r.kind)} · ${supportLabel(support[r.id])}", style = MaterialTheme.typography.bodySmall,
                                    color = if (support[r.id] == LangSupport.ONLINE_OR_INSTALLED) ReviewGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (support[r.id] == LangSupport.DOWNLOADABLE) TextButton(onClick = {
                                val ok = RecognizerCatalog.triggerDownload(ctx, r, s.language.tag)
                                Toast.makeText(ctx, if (ok) tr("ডাউনলোড শুরু করতে বলা হয়েছে", "Download requested") else tr("এই ফোনে সম্ভব নয়", "Not possible on this phone"), Toast.LENGTH_LONG).show()
                            }) { Text(tr("ডাউনলোড", "Download")) }
                        }
                    }
                    if (list.none { it.kind == RecognizerInfo.Kind.GOOGLE || support[it.id] == LangSupport.ONLINE_OR_INSTALLED })
                        Text(tr("বাংলা পারে এমন সার্ভিস নিশ্চিত হওয়া যায়নি। নিচের যেকোনোটি নিন — সবচেয়ে সহজ Gemini key।",
                            "No service confirmed for Bengali. Get one below — the easiest is a Gemini key."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { refresh++ }) { Text(tr("আবার খুঁজুন", "Search again")) }
        }
    }

    Text(tr("বাংলার জন্য যা নেওয়া যায় (লিংক)", "Options for Bengali (links)"), fontWeight = FontWeight.SemiBold)
    RecognizerCatalog.suggestions.forEach { sug ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sug.name, style = MaterialTheme.typography.bodyMedium)
                Text(tr(sug.noteBn, sug.noteEn), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { openLink(ctx, sug.url) }) { Text(tr("খুলুন", "Open")) }
        }
    }
    Text(tr("নোট: অন্য অ্যাপ (যেমন Hermes) যে Whisper/OCR মডেল ফাইল নামিয়েছে, Android-এর নিরাপত্তার কারণে VoiceDock সেগুলো সরাসরি পড়তে পারে না। ওই অ্যাপ যদি সিস্টেম ভয়েস সার্ভিস দেয়, তাহলে উপরের তালিকায় নিজেই দেখা যাবে।",
        "Note: model files downloaded by other apps (e.g. Hermes) cannot be read by VoiceDock (Android sandbox). If that app provides a system speech service, it appears in the list above automatically."),
        style = MaterialTheme.typography.bodySmall)
}

/** Gemini API key (Keystore-encrypted), live model list, consent and local-only switch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeminiSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by SettingsStore.state.collectAsState()
    var hasKey by remember { mutableStateOf(SecretStore.has(ctx, SecretStore.GEMINI)) }
    var input by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<GeminiModel>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun test(key: String) {
        val current = SettingsStore.state.value
        val blocked = AiPolicy.networkBlockReason(AiProvider.GEMINI, "", current.cloudAllowed, current.localOnly)
        if (blocked != null) { status = "✗ " + AiSetup.reasonText(blocked); return }
        testing = true; status = tr("যাচাই করা হচ্ছে…", "Checking…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { GeminiClient(key).listModels() } }
            testing = false
            r.onSuccess { list ->
                models = list
                val pick = if (list.any { it.id == s.geminiModel }) s.geminiModel else GeminiClient.pickDefault(list)
                if (pick != null) SettingsStore.update { it.copy(geminiModel = pick) }
                status = tr("✓ Key কাজ করছে। ${list.size}টি মডেল পাওয়া গেছে।", "✓ Key works. ${list.size} models available.")
            }.onFailure { status = "✗ " + ScreenAssistant.explain(it) }
        }
    }

    Text("Gemini AI", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(tr("বাংলা/ইংরেজি/মিশ্র ডিক্টেশন এবং ‘স্ক্রিন বুঝিয়ে দাও’-এর জন্য। আপনার নিজের ফ্রি key — ফোনে এনক্রিপ্ট করে রাখা হয়, কোথাও দেখানো বা পাঠানো হয় না (শুধু Google Gemini-কে)।",
        "For Bengali/English/mixed dictation and ‘Explain screen’. Your own free key — stored encrypted on the phone, sent only to Google Gemini."),
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { openLink(ctx, "https://aistudio.google.com/apikey") }) { Text(tr("ফ্রি key বানান (Google AI Studio)", "Create a free key (Google AI Studio)")) }

    if (hasKey) {
        Text(tr("Key সংরক্ষিত: ", "Key saved: ") + SecretStore.mask(SecretStore.get(ctx, SecretStore.GEMINI)), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = !testing, onClick = { SecretStore.get(ctx, SecretStore.GEMINI)?.let { test(it) } ?: run { status = tr("Key পড়া যায়নি — আবার দিন", "Key unreadable — enter again") } }) { Text(tr("Key যাচাই / মডেল তালিকা", "Test key / list models")) }
            TextButton(onClick = { SecretStore.remove(ctx, SecretStore.GEMINI); hasKey = false; models = emptyList(); status = null }) { Text(tr("Key মুছুন", "Remove key"), color = MaterialTheme.colorScheme.error) }
        }
    } else {
        OutlinedTextField(value = input, onValueChange = { input = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation())
        Button(enabled = input.length >= 20 && !testing, onClick = {
            SecretStore.put(ctx, SecretStore.GEMINI, input); hasKey = true; val k = input; input = ""; test(k)
        }) { Text(tr("সংরক্ষণ ও যাচাই", "Save & test")) }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    Text(tr("মডেল: ", "Model: ") + s.geminiModel, style = MaterialTheme.typography.bodyMedium)
    if (models.isNotEmpty()) {
        val shown = models.filter { m -> m.id.contains("flash") || m.id.contains("pro") }.ifEmpty { models }.take(12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { m -> FilterChip(selected = s.geminiModel == m.id, onClick = { SettingsStore.update { it.copy(geminiModel = m.id) } }, label = { Text(m.id) }) }
        }
        Text(tr("ফ্রি সীমা Google ঠিক করে ও বদলায়; VoiceDock কোনো সংখ্যা অনুমান করে দেখায় না। ‘lite’ মডেল সাধারণত দ্রুত।",
            "Free limits are set by Google and change; VoiceDock does not guess numbers. ‘lite’ models are usually faster."), style = MaterialTheme.typography.bodySmall)
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Gemini-তে অডিও ও স্ক্রিনশট পাঠানোর অনুমতি", "Allow sending audio and screenshots to Gemini"), Modifier.weight(1f))
        Switch(checked = s.cloudAllowed, onCheckedChange = { v -> SettingsStore.update { it.copy(cloudAllowed = v) } })
    }
    Text(tr("চালু করলে: Gemini ইঞ্জিনে আপনার কথার অডিও, আর ‘স্ক্রিন বুঝিয়ে দাও’ চাপলে সেই মুহূর্তের স্ক্রিনশট Google-এ যায়। ফ্রি key-তে Google এগুলো তাদের সার্ভিস উন্নয়নে ব্যবহার করতে পারে — ব্যক্তিগত/গোপন স্ক্রিনে ব্যবহার করবেন না।",
        "When on: audio (Gemini engine) and, on ‘Explain screen’, that screenshot go to Google. On free keys Google may use them to improve services — avoid private screens."),
        style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("লোকাল-অনলি মোড (ক্লাউডে কিছুই যাবে না)", "Local-only mode (nothing goes to the cloud)"), Modifier.weight(1f))
        Switch(checked = s.localOnly, onCheckedChange = { v -> SettingsStore.update { it.copy(localOnly = v) } })
    }
}
