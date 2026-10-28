package com.voicedock.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.voicedock.ai.accounts.Account
import com.voicedock.ai.accounts.AccountKind
import com.voicedock.ai.accounts.Accounts
import com.voicedock.ai.accounts.ChatGptAuth
import com.voicedock.ai.accounts.LoginManager
import com.voicedock.ai.ai.ChatClient
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiProvidersScreen() {
    val s by SettingsStore.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val order = s.aiOrder.mapNotNull { AiProvider.byId(it) } + AiProvider.entries.filter { it.id !in s.aiOrder }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("AI লেখা গোছানো ও নলেজ-প্রশ্নের জন্য উপরে থেকে নিচে ক্রমে চেষ্টা হয়: একটার সীমা শেষ হলে বা কাজ না করলে পরেরটা। ফ্রি প্রোভাইডার আগে।",
            "Used top-to-bottom for AI formatting and knowledge Q&A: if one fails or hits its limit, the next is tried. Free providers first."), style = MaterialTheme.typography.bodySmall)
        SwitchRow(tr("ক্লাউড AI-তে লেখা পাঠানোর অনুমতি", "Allow sending text to cloud AI"), s.cloudAllowed) { v -> SettingsStore.update { it.copy(cloudAllowed = v) } }
        SwitchRow(tr("শুধু-লোকাল মোড (কিছুই বাইরে যাবে না)", "Local-only mode (nothing leaves the phone)"), s.localOnly) { v -> SettingsStore.update { it.copy(localOnly = v) } }
        SwitchRow(tr("পেইড প্রোভাইডার/মডেল ব্যবহারের অনুমতি", "Allow paid providers/models"), s.allowPaid) { v -> SettingsStore.update { it.copy(allowPaid = v) } }
        Text(if (s.allowPaid) tr("⚠ পেইড চালু: OpenAI/Claude/DeepSeek ও OpenRouter-এর পেইড মডেল ব্যবহার হলে আপনার অ্যাকাউন্ট থেকে টাকা কাটবে।", "⚠ Paid on: using OpenAI/Claude/DeepSeek or paid OpenRouter models will be billed to your account.")
            else tr("পেইড বন্ধ (ডিফল্ট)। নোট: ‘ফ্রি’ প্রোভাইডারের key-তেও billing চালু থাকলে খরচ হতে পারে — ফ্রি key-তে billing বন্ধ রাখুন।", "Paid off (default). Note: even ‘free’ providers can bill if billing is enabled on your key."),
            color = if (s.allowPaid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(tr("কোটা/সীমা অ্যাপ অনুমান করে না — প্রোভাইডার ‘সীমা শেষ’ বললে তবেই পরেরটায় যায়।", "The app never guesses quotas — it moves on only when a provider reports a limit."), style = MaterialTheme.typography.bodySmall)

        LoginBanner()
        Text(tr("পেইড key দিলেও আগে সব ফ্রি অপশন চেষ্টা হয়; সবগুলো ব্যর্থ/সীমা শেষ হলে তবেই পেইড (যদি ‘পেইড অনুমতি’ চালু থাকে)।",
            "Even with a paid key, all free options are tried first; paid is used only after they fail (and only if paid use is on)."), style = MaterialTheme.typography.bodySmall)
        order.forEachIndexed { i, p ->
            ProviderCard(p, i, order.size, tick, onChanged = { tick++ }, move = { d ->
                val ids = order.map { it.id }.toMutableList(); val j = i + d
                if (j in ids.indices) { ids[i] = ids[j].also { ids[j] = ids[i] }; SettingsStore.update { it.copy(aiOrder = ids) } }
            })
        }
    }
}

@Composable
private fun SwitchRow(label: String, v: Boolean, on: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f)); Switch(checked = v, onCheckedChange = on)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(p: AiProvider, index: Int, total: Int, tick: Int, onChanged: () -> Unit, move: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by SettingsStore.state.collectAsState()
    val ver by Accounts.version.collectAsState()
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var url by remember(s.customAiUrl) { mutableStateOf(s.customAiUrl) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val legacyKey = remember(tick) { p != AiProvider.CHATGPT && SecretStore.has(ctx, p.secretName) }
    val accounts = remember(tick, ver) { Accounts.list(ctx).filter { it.provider == p.id } }
    val reason = remember(s, tick, ver) { AiSetup.blockReason(ctx, p, s) }
    val model = AiSetup.model(s, p)

    fun setModel(m: String) = SettingsStore.update { if (p == AiProvider.GEMINI) it.copy(geminiModel = m) else it.copy(aiModels = it.aiModels + (p.id to m)) }

    fun test(make: () -> ChatClient?) {
        val current = SettingsStore.state.value
        val blocked = AiPolicy.networkBlockReason(p, current.customAiUrl, current.cloudAllowed, current.localOnly)
        if (blocked != null) { status = "✗ " + AiSetup.reasonText(blocked); return }
        testing = true; status = tr("যাচাই হচ্ছে…", "Checking…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { (make() ?: error(tr("কোনো key/লগইন নেই", "No key or sign-in"))).listModels() } }
            testing = false
            r.onSuccess { list ->
                models = list
                val pick = model?.takeIf { it in list } ?: AiPolicy.pickDefault(p, list)
                pick?.let { setModel(it) }
                status = tr("✓ কাজ করছে — ${list.size}টি মডেল", "✓ Works — ${list.size} models")
            }.onFailure { status = "✗ " + AiSetup.explain(it) }
            onChanged()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { open = !open }) {
                    val kind = when {
                        p == AiProvider.CHATGPT -> tr("· লগইন (প্ল্যান)", "· sign-in (plan)")
                        p.freeTier -> tr("· ফ্রি স্তর", "· free tier")
                        else -> tr("· পেইড", "· paid")
                    }
                    val n = accounts.count { it.loggedIn } + (if (legacyKey) 1 else 0)
                    Text("${index + 1}. ${p.label}  $kind" + (if (n > 1) tr(" · ${n}টি অ্যাকাউন্ট", " · $n accounts") else ""), fontWeight = FontWeight.SemiBold)
                    Text(AiSetup.reasonText(reason) + (model?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall,
                        color = if (reason == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(enabled = index > 0, onClick = { move(-1) }) { Text("↑") }
                TextButton(enabled = index < total - 1, onClick = { move(1) }) { Text("↓") }
            }
            if (!open) return@Column
            Text(tr(p.noteBn, p.noteEn), style = MaterialTheme.typography.bodySmall)

            if (p == AiProvider.CHATGPT) {
                ChatGptSection(accounts, testing, onTest = { test { AiSetup.testClient(ctx, p) } }, onChanged = onChanged)
            } else {
                if (p.keyUrl.isNotBlank()) TextButton(onClick = { openLink(ctx, p.keyUrl) }) { Text(tr("Key বানান →", "Get a key →")) }
                if (p == AiProvider.CUSTOM) {
                    OutlinedTextField(value = url, onValueChange = { url = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL (…/v1)") })
                    OutlinedButton(enabled = url.startsWith("http"), onClick = { SettingsStore.update { it.copy(customAiUrl = url) }; onChanged() }) { Text(tr("ঠিকানা সংরক্ষণ", "Save URL")) }
                }
                if (p == AiProvider.OPENROUTER) {
                    Button(onClick = { LoginManager.loginOpenRouter(ctx) }) { Text(tr("OpenRouter দিয়ে লগইন (key লাগবে না)", "Sign in with OpenRouter (no key)")) }
                }
                if (legacyKey) {
                    Text(tr("মূল key সংরক্ষিত (এনক্রিপ্টেড)", "Main key saved (encrypted)"), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !testing, onClick = { test { AiSetup.testClient(ctx, p) } }) { Text(tr("যাচাই / মডেল তালিকা", "Test / list models")) }
                        TextButton(onClick = { SecretStore.remove(ctx, p.secretName); models = emptyList(); status = null; onChanged() }) { Text(tr("Key মুছুন", "Remove key"), color = MaterialTheme.colorScheme.error) }
                    }
                } else if (accounts.isEmpty()) {
                    OutlinedTextField(value = input, onValueChange = { input = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (p == AiProvider.CUSTOM) tr("API key (ঐচ্ছিক)", "API key (optional)") else "API key") }, visualTransformation = PasswordVisualTransformation())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = input.length >= 8 && !testing, onClick = { SecretStore.put(ctx, p.secretName, input); val k = input; input = ""; onChanged(); test { AiSetup.client(ctx, p, k, SettingsStore.state.value) } }) { Text(tr("সংরক্ষণ ও যাচাই", "Save & test")) }
                        if (p == AiProvider.CUSTOM) OutlinedButton(enabled = s.customAiUrl.isNotBlank() && !testing, onClick = { test { AiSetup.client(ctx, p, null, SettingsStore.state.value) } }) { Text(tr("key ছাড়া যাচাই", "Test without key")) }
                    }
                } else {
                    OutlinedButton(enabled = !testing, onClick = { test { AiSetup.testClient(ctx, p) } }) { Text(tr("যাচাই / মডেল তালিকা", "Test / list models")) }
                }
                if (p != AiProvider.CUSTOM) KeyAccountsSection(p, accounts, onChanged)
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (models.isNotEmpty()) {
                Text(tr("মডেল বাছুন:", "Choose model:"), style = MaterialTheme.typography.labelMedium)
                val shown = if (p == AiProvider.OPENROUTER && !s.allowPaid) models.filter { it.endsWith(":free") } else models
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.take(40).forEach { m -> FilterChip(selected = m == model, onClick = { setModel(m); onChanged() }, label = { Text(m, style = MaterialTheme.typography.labelSmall) }) }
                }
                if (shown.size > 40) Text(tr("আরও ${shown.size - 40}টি মডেল আছে", "${shown.size - 40} more models"), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Extra keys / OpenRouter sign-ins for one provider: free ones are used first; a key marked "paid" only when paid use is on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyAccountsSection(p: AiProvider, accounts: List<Account>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf(!p.freeTier) }
    if (accounts.isNotEmpty()) Text(tr("অ্যাকাউন্টসমূহ (ফ্রি আগে, তারপর পুরনো আগে):", "Accounts (free first, then oldest first):"), style = MaterialTheme.typography.labelMedium)
    accounts.forEach { a ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.bodyMedium)
                Text((if (a.kind == AccountKind.OPENROUTER_LOGIN) tr("লগইন-key", "sign-in key") else "key") + " · " +
                    (if (a.paid || !p.freeTier) tr("পেইড", "paid") else tr("ফ্রি", "free")) + (if (!a.enabled) tr(" · বন্ধ", " · off") else ""),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (p.freeTier) TextButton(onClick = { Accounts.update(ctx, a.id) { it.copy(paid = !it.paid) }; onChanged() }) { Text(if (a.paid) tr("ফ্রি করুন", "Mark free") else tr("পেইড চিহ্ন", "Mark paid")) }
            Switch(checked = a.enabled, onCheckedChange = { v -> Accounts.update(ctx, a.id) { it.copy(enabled = v) }; onChanged() })
            TextButton(onClick = { Accounts.remove(ctx, a.id); onChanged() }) { Text(tr("মুছুন", "Delete"), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (!adding) TextButton(onClick = { adding = true }) { Text(tr("+ আরেকটি key যোগ করুন", "+ Add another key")) }
    else {
        OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("নাম (যেমন ‘অফিস পেইড’)", "Name (e.g. ‘work paid’)")) })
        OutlinedTextField(value = key, onValueChange = { key = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation())
        if (p.freeTier) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("এই key-তে billing চালু (পেইড)", "Billing is on for this key (paid)"), Modifier.weight(1f)); Switch(checked = paid, onCheckedChange = { paid = it })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = key.length >= 8, onClick = {
                val n = accounts.size + 1
                Accounts.add(ctx, Account(Accounts.newId(), p.id, label.ifBlank { "${p.label} $n" }, AccountKind.KEY, paid = paid || !p.freeTier), key)
                key = ""; label = ""; adding = false; onChanged()
            }) { Text(tr("সংরক্ষণ", "Save")) }
            TextButton(onClick = { adding = false; key = "" }) { Text(tr("বাতিল", "Cancel")) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatGptSection(accounts: List<Account>, testing: Boolean, onTest: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    Text(tr("শর্ত: OpenAI-এর ‘Sign in with ChatGPT’ (প্রিভিউ)। ChatGPT Go/Plus/Pro প্ল্যান লাগবে; সীমা ChatGPT অ্যাপের সাথে শেয়ার হয়। OpenAI অনুমতি না দিলে লগইন পেজেই জানাবে।",
        "Requires OpenAI ‘Sign in with ChatGPT’ (preview) and a ChatGPT Go/Plus/Pro plan; limits are shared with ChatGPT. OpenAI shows an error on the sign-in page if not allowed."),
        style = MaterialTheme.typography.bodySmall)
    Button(onClick = { LoginManager.loginChatGpt(ctx) }) { Text("Continue with ChatGPT") }
    accounts.forEach { a ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Text(a.email.ifBlank { a.label }, fontWeight = FontWeight.SemiBold)
                Text(when {
                    !a.loggedIn -> tr("লগআউট করা", "Signed out")
                    !a.planUsage -> tr("প্ল্যান-ব্যবহারের অনুমতি নেই", "Plan usage not granted")
                    else -> "Using ChatGPT plan"
                } + if (!a.enabled) tr(" · বন্ধ", " · off") else "", style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.loggedIn) {
                        if (!a.planUsage) TextButton(onClick = { LoginManager.loginChatGpt(ctx, a.id, forceConsent = true) }) { Text(tr("আবার অনুমতি দিন", "Grant again")) }
                        OutlinedButton(enabled = !testing, onClick = onTest) { Text(tr("যাচাই / মডেল", "Test / models")) }
                        TextButton(onClick = { openLink(ctx, ChatGptAuth.USAGE_URL) }) { Text("Manage usage") }
                        TextButton(onClick = { LoginManager.logoutChatGpt(ctx, a.id) }) { Text(tr("লগআউট", "Sign out")) }
                    } else {
                        TextButton(onClick = { LoginManager.loginChatGpt(ctx, a.id) }) { Text(tr("আবার লগইন", "Sign in again")) }
                        TextButton(onClick = { Accounts.remove(ctx, a.id); onChanged() }) { Text(tr("মুছুন", "Delete"), color = MaterialTheme.colorScheme.error) }
                    }
                    Switch(checked = a.enabled, onCheckedChange = { v -> Accounts.update(ctx, a.id) { it.copy(enabled = v) }; onChanged() })
                }
            }
        }
    }
    if (accounts.count { it.loggedIn } > 1) Text(tr("একাধিক অ্যাকাউন্ট: উপরেরটা আগে ব্যবহার হয়; কোনোটা বন্ধ করে ‘সুইচ’ করুন।", "Several accounts: the first is used first; switch by turning one off."), style = MaterialTheme.typography.labelSmall)
}

/** Login progress + first-time ChatGPT plan notice (shown at the top of the AI providers screen). */
@Composable
fun LoginBanner() {
    val ui by LoginManager.ui.collectAsState()
    if (ui.showPlanWelcome) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("You're using your ChatGPT plan", fontWeight = FontWeight.SemiBold)
            Text(tr("VoiceDock-এ আপনার AI ব্যবহার আপনার ChatGPT প্ল্যানের সীমা থেকে কাটবে (ChatGPT অ্যাপের সাথে শেয়ার)। আলাদা বিল হবে না।",
                "Your VoiceDock AI use counts against your ChatGPT plan limits (shared with ChatGPT). No separate bill."), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { LoginManager.dismissWelcome() }) { Text("Got it") }
        }
    }
    if (ui.busy || ui.message.isNotBlank() || ui.error.isNotBlank()) Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.error.ifBlank { ui.message }, Modifier.weight(1f), color = if (ui.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (ui.busy) TextButton(onClick = { LoginManager.cancel() }) { Text(tr("বাতিল", "Cancel")) }
            else TextButton(onClick = { LoginManager.clearMessage() }) { Text("✕") }
        }
    }
}
