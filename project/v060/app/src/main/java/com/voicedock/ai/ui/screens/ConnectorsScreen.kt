package com.voicedock.ai.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.connectors.ConnectorException
import com.voicedock.ai.connectors.DriveFile
import com.voicedock.ai.connectors.GhFile
import com.voicedock.ai.connectors.GhRepo
import com.voicedock.ai.connectors.GitHubClient
import com.voicedock.ai.connectors.GoogleAuth
import com.voicedock.ai.connectors.GoogleRest
import com.voicedock.ai.connectors.ImapMail
import com.voicedock.ai.connectors.MailItem
import com.voicedock.ai.data.database.KbSource
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

fun explainConnector(e: Throwable): String = when (e) {
    is ConnectorException -> when (e.kind) {
        ConnectorException.Kind.AUTH -> tr("লগইন/অনুমতি ব্যর্থ — টোকেন/পাসওয়ার্ড দেখুন", "Login/permission failed — check token/password")
        ConnectorException.Kind.NOT_FOUND -> tr("পাওয়া যায়নি", "Not found")
        ConnectorException.Kind.RATE_LIMIT -> tr("সীমা শেষ — পরে চেষ্টা করুন", "Rate limited — try later")
        ConnectorException.Kind.NETWORK -> tr("ইন্টারনেট সমস্যা", "Network problem")
        ConnectorException.Kind.SERVER -> tr("সার্ভারে সমস্যা", "Server error")
        ConnectorException.Kind.OTHER -> if (e.message == "too_big") tr("ফাইল ১৫ MB-এর বেশি", "File larger than 15 MB") else tr("ব্যর্থ", "Failed")
    } + (e.message?.takeIf { it.isNotBlank() && it != "too_big" }?.let { " ($it)" } ?: "")
    else -> e.message ?: e.javaClass.simpleName
}

private fun mailText(m: MailItem) = "From: ${m.from}\nDate: ${m.date}\nSubject: ${m.subject}\n\n${m.body}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectorsScreen() {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val scope = rememberCoroutineScope()
    val s by SettingsStore.state.collectAsState()
    val repo = remember { VoiceDock.knowledge(ctx) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // ── Google OAuth state
    var gToken by remember { mutableStateOf(GoogleAuth.token) }
    var gNotSetUp by remember { mutableStateOf(false) }
    var driveQuery by remember { mutableStateOf("") }
    var driveFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var gmailQuery by remember { mutableStateOf("newer_than:30d") }
    var mails by remember { mutableStateOf<List<MailItem>>(emptyList()) }
    val picked = remember { mutableStateOf(setOf<String>()) }

    // ── IMAP
    var imapEmail by remember(s.imapEmail) { mutableStateOf(s.imapEmail) }
    var imapPass by remember { mutableStateOf("") }
    var imapTick by remember { mutableIntStateOf(0) }
    val hasImap = remember(imapTick) { SecretStore.has(ctx, SecretStore.IMAP) }
    var imapQuery by remember { mutableStateOf("") }

    // ── GitHub
    var ghInput by remember { mutableStateOf("") }
    var ghTick by remember { mutableIntStateOf(0) }
    val hasGh = remember(ghTick) { SecretStore.has(ctx, SecretStore.GITHUB) }
    var repos by remember { mutableStateOf<List<GhRepo>>(emptyList()) }
    var repo0 by remember { mutableStateOf<GhRepo?>(null) }
    var ghFiles by remember { mutableStateOf<List<GhFile>>(emptyList()) }
    val ghPicked = remember { mutableStateOf(setOf<String>()) }

    fun run(label: String, block: suspend () -> String?) {
        busy = true; status = label
        scope.launch { status = runCatching { block() }.getOrElse { "✗ " + explainConnector(it) }; busy = false }
    }

    fun onAuth(o: GoogleAuth.Outcome) {
        when (o) {
            is GoogleAuth.Outcome.Token -> { gToken = o.token; gNotSetUp = false; o.email?.let { e -> SettingsStore.update { it.copy(googleAccount = e) } }; status = tr("✓ Google সংযুক্ত (শুধু-পড়া)", "✓ Google connected (read-only)") }
            is GoogleAuth.Outcome.Failed -> { gNotSetUp = o.notSetUp; status = "✗ " + if (o.notSetUp) tr("OAuth সেটআপ বাকি — নিচের নির্দেশনা দেখুন", "OAuth not set up — see the note below") else o.message }
            is GoogleAuth.Outcome.NeedsConsent -> Unit
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        activity?.let { onAuth(GoogleAuth.fromConsentResult(it, r.data)) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run(tr("আনা হচ্ছে…", "Importing…")) {
            val out = mutableListOf<String>()
            for (u in uris.take(20)) out += KbUi.importUri(ctx, u, if (u.authority.orEmpty().contains("com.google.android.apps.docs")) KbSource.DRIVE else KbSource.FILE, false)
            out.joinToString("\n")
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("সব সংযোগ শুধু-পড়ার: VoiceDock কোনো ইমেইল পাঠায় না, ফাইল বদলায় বা মোছে না। যা আনবেন তা শুধু এই ফোনের নলেজ বেসে থাকে।",
            "All connectors are read-only: VoiceDock never sends e-mail or changes/deletes files. Imported items stay in this phone's knowledge base."), style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

        // ───── Google Drive
        Text("Google Drive", style = MaterialTheme.typography.titleMedium)
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(tr("ফাইল পিকার দিয়ে আনুন (সেটআপ লাগে না)", "Import with the file picker (no setup)")) }
        Text(tr("পিকারে বাঁ-পাশের মেনু → Drive বেছে নিন।", "In the picker, open the side menu → Drive."), style = MaterialTheme.typography.bodySmall)

        // ───── Google account (OAuth) for Drive search + Gmail
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Google অ্যাকাউন্ট (Drive খোঁজা + Gmail) — OAuth", "Google account (Drive search + Gmail) — OAuth"), fontWeight = FontWeight.SemiBold)
                if (gToken == null) {
                    FilledTonalButton(enabled = activity != null && !busy, onClick = {
                        scope.launch {
                            when (val o = GoogleAuth.authorize(activity!!, listOf(GoogleRest.SCOPE_DRIVE_READONLY, GoogleRest.SCOPE_GMAIL_READONLY))) {
                                is GoogleAuth.Outcome.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(o.sender).build())
                                else -> onAuth(o)
                            }
                        }
                    }) { Text(tr("Google দিয়ে সংযোগ (শুধু-পড়া)", "Connect Google (read-only)")) }
                    if (gNotSetUp) Text(tr("এই APK-এর জন্য Google Cloud-এ OAuth Client এখনো নিবন্ধিত নয়। docs/OAUTH-SETUP.md-তে ১০ মিনিটের ধাপ আছে। ততক্ষণ উপরের ফাইল পিকার ও নিচের Gmail IMAP ব্যবহার করুন।",
                        "No OAuth client is registered for this APK yet. See docs/OAUTH-SETUP.md (10 min). Meanwhile use the file picker above and Gmail IMAP below."),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(tr("সংযুক্ত: ", "Connected: ") + s.googleAccount.ifBlank { "Google" }, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = driveQuery, onValueChange = { driveQuery = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(tr("Drive-এ খুঁজুন", "Search Drive")) })
                        TextButton(enabled = !busy, onClick = { run(tr("খোঁজা হচ্ছে…", "Searching…")) { driveFiles = withContext(Dispatchers.IO) { GoogleRest(gToken!!).driveSearch(driveQuery) }; tr("${driveFiles.size}টি ফাইল", "${driveFiles.size} files") } }) { Text(tr("খুঁজুন", "Search")) }
                    }
                    driveFiles.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !busy, onClick = { run(tr("আনা হচ্ছে…", "Importing…")) {
                                val (mime, bytes) = withContext(Dispatchers.IO) { GoogleRest(gToken!!).driveContent(f) }
                                val ex = withContext(Dispatchers.IO) { com.voicedock.ai.knowledge.Importer.extract(ctx, com.voicedock.ai.knowledge.Importer.FileData(f.name, mime, bytes)) }
                                if (ex.text.isBlank()) tr("${f.name}: লেখা পাওয়া যায়নি (ছবি/স্ক্যান হলে নলেজ স্ক্রিনে ‘OCR’ দিয়ে আনুন)", "${f.name}: no text (for scans use OCR on the Knowledge screen)")
                                else KbUi.message(repo.addText(f.name, ex.text, KbSource.DRIVE, "drive:${f.id}", mime))
                            } }) { Text(tr("আনুন", "Import")) }
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = gmailQuery, onValueChange = { gmailQuery = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(tr("Gmail খোঁজা (যেমন from:bank)", "Gmail search (e.g. from:bank)")) })
                        TextButton(enabled = !busy, onClick = { run(tr("খোঁজা হচ্ছে…", "Searching…")) { mails = withContext(Dispatchers.IO) { GoogleRest(gToken!!).gmailSearch(gmailQuery) }; picked.value = emptySet(); tr("${mails.size}টি ইমেইল", "${mails.size} e-mails") } }) { Text(tr("খুঁজুন", "Search")) }
                    }
                    TextButton(onClick = { GoogleAuth.clear(); gToken = null; driveFiles = emptyList(); mails = emptyList() }) { Text(tr("সংযোগ বিচ্ছিন্ন", "Disconnect")) }
                }
            }
        }

        // ───── Gmail via IMAP
        Text("Gmail", style = MaterialTheme.typography.titleMedium)
        Text(tr("সহজ পথ: Gmail-এ লেখা সিলেক্ট করে মেনু থেকে ‘VoiceDock-এ রাখুন’। অথবা নিচে Google ‘অ্যাপ পাসওয়ার্ড’ (2-Step Verification লাগবে) দিয়ে ইনবক্স পড়ুন — শুধু-পড়া মোডে খোলে।",
            "Easy: select text in Gmail → ‘Save to VoiceDock’. Or read the inbox with a Google ‘App password’ (needs 2-Step Verification) — opened read-only."), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { openLink(ctx, "https://myaccount.google.com/apppasswords") }) { Text(tr("অ্যাপ পাসওয়ার্ড বানান →", "Create an app password →")) }
        if (!hasImap) {
            OutlinedTextField(value = imapEmail, onValueChange = { imapEmail = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Gmail address") })
            OutlinedTextField(value = imapPass, onValueChange = { imapPass = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("অ্যাপ পাসওয়ার্ড (১৬ অক্ষর)", "App password (16 letters)")) }, visualTransformation = PasswordVisualTransformation())
            Button(enabled = imapEmail.contains('@') && imapPass.replace(" ", "").length >= 16, onClick = {
                SecretStore.put(ctx, SecretStore.IMAP, imapPass.replace(" ", "")); imapPass = ""
                SettingsStore.update { it.copy(imapEmail = imapEmail) }; imapTick++
            }) { Text(tr("সংরক্ষণ", "Save")) }
        } else {
            Text(tr("IMAP: ", "IMAP: ") + s.imapEmail, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = imapQuery, onValueChange = { imapQuery = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text(tr("খুঁজুন (ফাঁকা = সাম্প্রতিক)", "Search (empty = recent)")) })
                TextButton(enabled = !busy, onClick = { run(tr("ইনবক্স পড়া হচ্ছে…", "Reading inbox…")) {
                    val pass = SecretStore.get(ctx, SecretStore.IMAP) ?: return@run tr("পাসওয়ার্ড পড়া যায়নি — আবার দিন", "Password unreadable — enter again")
                    mails = withContext(Dispatchers.IO) { ImapMail(s.imapEmail, pass).recent(imapQuery) }; picked.value = emptySet()
                    tr("${mails.size}টি ইমেইল", "${mails.size} e-mails")
                } }) { Text(tr("পড়ুন", "Read")) }
            }
            TextButton(onClick = { SecretStore.remove(ctx, SecretStore.IMAP); imapTick++; mails = emptyList() }) { Text(tr("IMAP মুছুন", "Remove IMAP"), color = MaterialTheme.colorScheme.error) }
        }
        if (mails.isNotEmpty()) {
            mails.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = m.id in picked.value, onCheckedChange = { c -> picked.value = if (c) picked.value + m.id else picked.value - m.id })
                    Column(Modifier.weight(1f)) {
                        Text(m.subject.ifBlank { tr("(বিষয় নেই)", "(no subject)") }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text("${m.from} · ${m.snippet.take(80)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Button(enabled = picked.value.isNotEmpty() && !busy, onClick = { run(tr("আনা হচ্ছে…", "Importing…")) {
                val out = mutableListOf<String>()
                for (m in mails.filter { it.id in picked.value }) out += KbUi.message(repo.addText(m.subject.ifBlank { "Email" }, mailText(m), KbSource.GMAIL, m.id, "message/rfc822"))
                out.joinToString("\n")
            } }) { Text(tr("নির্বাচিত ${picked.value.size}টি নলেজে আনুন", "Import ${picked.value.size} selected")) }
        }

        // ───── GitHub
        HorizontalDivider()
        Text("GitHub", style = MaterialTheme.typography.titleMedium)
        Text(tr("Fine-grained টোকেন বানান: Repository access → যেগুলো চান; Permissions → Contents: Read-only। টোকেন ফোনে এনক্রিপ্ট করে রাখা হয়।",
            "Create a fine-grained token: Repository access → the repos you want; Permissions → Contents: Read-only. Stored encrypted."), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { openLink(ctx, "https://github.com/settings/personal-access-tokens/new") }) { Text(tr("টোকেন বানান →", "Create a token →")) }
        if (!hasGh) {
            OutlinedTextField(value = ghInput, onValueChange = { ghInput = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation())
            Button(enabled = ghInput.length >= 20 && !busy, onClick = {
                val t = ghInput; ghInput = ""
                run(tr("যাচাই হচ্ছে…", "Checking…")) {
                    val login = withContext(Dispatchers.IO) { GitHubClient(t).login() }
                    SecretStore.put(ctx, SecretStore.GITHUB, t); ghTick++
                    repos = withContext(Dispatchers.IO) { GitHubClient(t).repos() }
                    tr("✓ $login — ${repos.size}টি রিপো", "✓ $login — ${repos.size} repos")
                }
            }) { Text(tr("সংরক্ষণ ও যাচাই", "Save & verify")) }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { run(tr("রিপো তালিকা…", "Listing repos…")) {
                    val t = SecretStore.get(ctx, SecretStore.GITHUB) ?: return@run tr("টোকেন পড়া যায়নি", "Token unreadable")
                    repos = withContext(Dispatchers.IO) { GitHubClient(t).repos() }; repo0 = null; ghFiles = emptyList()
                    tr("${repos.size}টি রিপো", "${repos.size} repos")
                } }) { Text(tr("রিপো দেখান", "Show repos")) }
                TextButton(onClick = { SecretStore.remove(ctx, SecretStore.GITHUB); ghTick++; repos = emptyList(); ghFiles = emptyList() }) { Text(tr("টোকেন মুছুন", "Remove token"), color = MaterialTheme.colorScheme.error) }
            }
        }
        if (repo0 == null) repos.forEach { r ->
            TextButton(enabled = !busy, onClick = { run(tr("ফাইল তালিকা…", "Listing files…")) {
                val t = SecretStore.get(ctx, SecretStore.GITHUB) ?: return@run tr("টোকেন পড়া যায়নি", "Token unreadable")
                ghFiles = withContext(Dispatchers.IO) { GitHubClient(t).textFiles(r) }; repo0 = r
                ghPicked.value = ghFiles.filter { it.path.endsWith(".md", true) }.take(30).map { it.path }.toSet()
                tr("${ghFiles.size}টি টেক্সট ফাইল", "${ghFiles.size} text files")
            } }) { Text((if (r.private) "🔒 " else "") + r.fullName) }
        }
        repo0?.let { r ->
            Text(r.fullName, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { repo0 = null; ghFiles = emptyList() }) { Text(tr("← রিপো তালিকা", "← Repos")) }
            ghFiles.take(150).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = f.path in ghPicked.value, onCheckedChange = { c -> ghPicked.value = if (c) ghPicked.value + f.path else ghPicked.value - f.path })
                    Text("${f.path}  (${f.size / 1024 + 1} KB)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(enabled = ghPicked.value.isNotEmpty() && ghPicked.value.size <= 50 && !busy, onClick = { run(tr("আনা হচ্ছে…", "Importing…")) {
                val t = SecretStore.get(ctx, SecretStore.GITHUB) ?: return@run tr("টোকেন পড়া যায়নি", "Token unreadable")
                val gh = GitHubClient(t)
                var added = 0; var dup = 0; var fail = 0
                for (path in ghPicked.value) {
                    runCatching { withContext(Dispatchers.IO) { gh.file(r, path) } }.onSuccess { text ->
                        when (repo.addText("${r.fullName}/$path", text, KbSource.GITHUB, "${r.fullName}@${r.defaultBranch}:$path", if (path.endsWith(".md")) "text/markdown" else "text/plain")) {
                            is com.voicedock.ai.knowledge.AddResult.Added -> added++
                            is com.voicedock.ai.knowledge.AddResult.Duplicate -> dup++
                            else -> fail++
                        }
                    }.onFailure { fail++ }
                }
                tr("✓ যোগ $added, আগেই ছিল $dup, ব্যর্থ $fail", "✓ added $added, already $dup, failed $fail")
            } }) { Text(tr("নির্বাচিত ${ghPicked.value.size}টি আনুন (সর্বোচ্চ ৫০)", "Import ${ghPicked.value.size} selected (max 50)")) }
        }
        Text(tr("সতর্কতা: আনা লেখা ‘অবিশ্বস্ত’ হিসেবে AI-কে দেওয়া হয় — ভেতরের কোনো নির্দেশ AI মানে না।", "Imported text is given to AI as untrusted — instructions inside it are not followed."),
            style = MaterialTheme.typography.labelSmall)
    }
}
