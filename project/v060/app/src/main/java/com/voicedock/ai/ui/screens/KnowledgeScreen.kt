package com.voicedock.ai.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.ai.AiSetup
import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.data.database.KbDocument
import com.voicedock.ai.data.database.KbSource
import com.voicedock.ai.knowledge.AddResult
import com.voicedock.ai.knowledge.Importer
import com.voicedock.ai.knowledge.KbAnswer
import com.voicedock.ai.knowledge.KbSearch
import com.voicedock.ai.knowledge.ScoredHit
import com.voicedock.ai.security.SecretStore
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.UiLanguage
import com.voicedock.ai.settings.tr
import com.voicedock.ai.text.AiFormatter
import com.voicedock.ai.text.FormatResult
import com.voicedock.ai.text.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Shared import helpers (knowledge screen, share capture, connectors). */
object KbUi {
    fun message(r: AddResult): String = when (r) {
        is AddResult.Added -> tr("✓ নলেজ বেসে যোগ হলো: ${r.doc.title} (${r.chunks} অংশ)", "✓ Added to knowledge: ${r.doc.title} (${r.chunks} parts)")
        is AddResult.Duplicate -> tr("আগেই আছে: ${r.existing.title}", "Already saved: ${r.existing.title}")
        AddResult.Empty -> tr("কোনো লেখা পাওয়া যায়নি", "No text found")
    }

    fun sourceLabel(s: KbSource) = when (s) {
        KbSource.NOTE -> tr("নোট", "Note"); KbSource.SHARE -> tr("শেয়ার", "Shared"); KbSource.PROCESS_TEXT -> tr("নির্বাচিত লেখা", "Selected text")
        KbSource.FILE -> tr("ফাইল", "File"); KbSource.DRIVE -> "Drive"; KbSource.GMAIL -> "Gmail"; KbSource.GITHUB -> "GitHub"; KbSource.DRAFT -> tr("ড্রাফট", "Draft")
    }

    /** Gemini OCR allowed right now? (consent on, not local-only, key present) */
    fun ocrAvailable(ctx: Context): Boolean { val s = SettingsStore.state.value; return s.cloudAllowed && !s.localOnly && SecretStore.has(ctx, SecretStore.GEMINI) }

    suspend fun ocr(ctx: Context, bytes: ByteArray, mime: String): String = withContext(Dispatchers.IO) {
        val key = SecretStore.get(ctx, SecretStore.GEMINI) ?: error("no key")
        GeminiClient(key).extractText(SettingsStore.state.value.geminiModel, bytes, mime)
    }

    /** Imports one picked/shared file. OCR only when [allowOcr] (explicit user tap). */
    suspend fun importUri(ctx: Context, uri: Uri, source: KbSource, allowOcr: Boolean): String = try {
        val f = withContext(Dispatchers.IO) { Importer.read(ctx, uri) }
        val ex = withContext(Dispatchers.IO) { Importer.extract(ctx, f) }
        val text = when {
            ex.text.isNotBlank() -> ex.text
            ex.needsOcr && allowOcr && ocrAvailable(ctx) -> ocr(ctx, f.bytes, if (ex.kind == "pdf") "application/pdf" else f.mime)
            ex.needsOcr -> return tr("${f.name}: ছবি/স্ক্যান — লেখা পড়তে Gemini OCR লাগবে (অনুমতি ও key দিন, তারপর ‘OCR সহ’ চাপুন)", "${f.name}: image/scan — needs Gemini OCR (enable consent + key, then use ‘with OCR’)")
            else -> return tr("${f.name}: এই ধরনের ফাইল পড়া যায় না", "${f.name}: unsupported file type")
        }
        message(VoiceDock.knowledge(ctx).addText(f.name.substringBeforeLast('.'), text, source, f.name, f.mime))
    } catch (e: IllegalArgumentException) {
        if (e.message == "too_big") tr("ফাইল ১৫ MB-এর বেশি", "File larger than 15 MB") else tr("ফাইল পড়া যায়নি", "Could not read the file")
    } catch (e: Exception) { tr("ব্যর্থ: ", "Failed: ") + AiSetup.explain(e) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnowledgeScreen(openConnectors: () -> Unit, openAi: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { VoiceDock.knowledge(ctx) }
    val docs by remember { VoiceDock.db(ctx).knowledge().observeDocuments() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ScoredHit>>(emptyList()) }
    var answer by remember { mutableStateOf<KbAnswer?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var noteTitle by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var showNote by remember { mutableStateOf(false) }
    var withOcr by remember { mutableStateOf(false) }
    var openDoc by remember { mutableStateOf<String?>(null) }
    var openText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; status = tr("যোগ হচ্ছে…", "Importing…")
        scope.launch {
            val msgs = uris.take(20).map { KbUi.importUri(ctx, it, KbSource.FILE, withOcr) }
            status = msgs.joinToString("\n"); busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("আপনার নিজের নোট, ফাইল, ইমেইল ও ডকুমেন্ট এখানে জমা থাকে (শুধু এই ফোনে)। খুঁজুন, বা AI-কে এগুলোর ভিত্তিতে প্রশ্ন করুন।",
            "Your own notes, files, e-mails and documents (stored only on this phone). Search, or ask AI based on them."), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("খুঁজুন / প্রশ্ন করুন", "Search / ask")) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = query.isNotBlank() && !busy, onClick = {
                scope.launch { answer = null; hits = repo.search(query, 10); status = if (hits.isEmpty()) tr("কিছু পাওয়া যায়নি", "Nothing found") else null }
            }) { Text(tr("খুঁজুন", "Search")) }
            FilledTonalButton(enabled = query.isNotBlank() && !busy, onClick = {
                busy = true; status = tr("AI উত্তর খুঁজছে…", "AI is answering…")
                scope.launch {
                    runCatching { repo.ask(AiSetup.router(ctx), query, SettingsStore.state.value.uiLanguage == UiLanguage.BN) }
                        .onSuccess { a -> answer = a; hits = a.sources; status = if (a.sources.isEmpty()) tr("সংরক্ষিত ডকুমেন্টে মিল পাওয়া যায়নি — তাই AI-কে পাঠানো হয়নি।", "No matching saved document — AI was not called.") else null }
                        .onFailure { status = "✗ " + AiSetup.explain(it) }
                    busy = false
                }
            }) { Text(tr("AI-কে জিজ্ঞাসা", "Ask AI")) }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        answer?.takeIf { it.text.isNotBlank() }?.let { a ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(a.text, style = MaterialTheme.typography.bodyLarge)
                    a.answer?.let { Text("${it.provider.label} · ${it.model}", style = MaterialTheme.typography.labelSmall) }
                    Text(tr("উত্তর শুধু নিচের উৎস থেকে — ভুল হতে পারে, মূল লেখা মিলিয়ে নিন।", "Answer is from the sources below only — may be wrong, check the originals."), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (hits.isNotEmpty()) {
            val tokens = KbSearch.tokens(query)
            hits.forEachIndexed { i, h ->
                Card(Modifier.fillMaxWidth().clickable {
                    scope.launch { openDoc = h.hit.docId; openText = repo.fullText(h.hit.docId) }
                }) {
                    Column(Modifier.padding(10.dp)) {
                        Text("[${i + 1}] ${h.hit.title}", fontWeight = FontWeight.SemiBold)
                        Text(KbSearch.snippet(h.hit.text, tokens), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        HorizontalDivider()
        Text(tr("যোগ করুন", "Add"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(tr("ফাইল (ফোন / Google Drive)", "Files (phone / Google Drive)")) }
            OutlinedButton(onClick = { showNote = !showNote }) { Text(tr("লেখা / নোট", "Text / note")) }
            OutlinedButton(onClick = openConnectors) { Text(tr("Gmail · Drive · GitHub", "Gmail · Drive · GitHub")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = withOcr, onClick = { withOcr = !withOcr }, label = { Text(tr("ছবি/স্ক্যান হলে Gemini OCR", "Gemini OCR for images/scans")) })
        }
        Text(tr("ফাইল বাছার সময় বাঁ-পাশের মেনু থেকে ‘Drive’ বেছে নিলে Google Drive-এর ফাইলও আনা যায়। txt, md, csv, docx, xlsx, pptx, pdf, html সমর্থিত।",
            "In the file picker, choose ‘Drive’ in the side menu to import from Google Drive. Supports txt, md, csv, docx, xlsx, pptx, pdf, html."), style = MaterialTheme.typography.bodySmall)
        if (showNote) {
            OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("শিরোনাম", "Title")) })
            OutlinedTextField(value = noteText, onValueChange = { noteText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(tr("লেখা", "Text")) })
            Button(enabled = noteText.isNotBlank(), onClick = {
                scope.launch { status = KbUi.message(repo.addText(noteTitle, noteText, KbSource.NOTE)); noteText = ""; noteTitle = ""; showNote = false }
            }) { Text(tr("সংরক্ষণ", "Save")) }
        }

        HorizontalDivider()
        Text(tr("সংরক্ষিত (${docs.size})", "Saved (${docs.size})"), style = MaterialTheme.typography.titleMedium)
        if (docs.isEmpty()) Text(tr("এখনো কিছু নেই। অন্য অ্যাপ থেকে ‘Share → VoiceDock’ করেও যোগ করা যায়।", "Nothing yet. You can also use ‘Share → VoiceDock’ from other apps."), style = MaterialTheme.typography.bodySmall)
        docs.forEach { d -> DocRow(d, open = openDoc == d.id, text = openText,
            onToggle = { scope.launch { if (openDoc == d.id) openDoc = null else { openDoc = d.id; openText = repo.fullText(d.id) } } },
            confirming = confirmDelete == d.id,
            onDelete = {
                if (confirmDelete == d.id) scope.launch { VoiceDock.db(ctx).knowledge().deleteDocument(d.id); confirmDelete = null; if (openDoc == d.id) openDoc = null }
                else confirmDelete = d.id
            }) }
        LaunchedEffect(confirmDelete) { if (confirmDelete != null) { kotlinx.coroutines.delay(3000); confirmDelete = null } }
        if (!AiSetup.ready(ctx)) TextButton(onClick = openAi) { Text(tr("AI প্রশ্নোত্তরের জন্য AI প্রোভাইডার সেট করুন →", "Set up an AI provider for Q&A →")) }
    }
}

@Composable
private fun DocRow(d: KbDocument, open: Boolean, text: String, onToggle: () -> Unit, confirming: Boolean, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onToggle)) {
                    Text(d.title, fontWeight = FontWeight.SemiBold)
                    Text("${KbUi.sourceLabel(d.source)} · ${d.charCount} ${tr("অক্ষর", "chars")} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(d.updatedAt))}",
                        style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDelete) { Text(if (confirming) tr("নিশ্চিত?", "Sure?") else tr("মুছুন", "Delete"), color = MaterialTheme.colorScheme.error) }
            }
            if (open) Text(text.take(6000) + if (text.length > 6000) "\n…" else "", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Mode B card shown under the review text. The original is kept; "Undo" restores it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiFormatCard(current: String, onApply: (String) -> Unit, openAi: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by SettingsStore.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FormatResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<String?>(null) }
    val style = FormatStyle.entries.firstOrNull { it.name == s.formatStyle } ?: FormatStyle.CLEAN
    val ready = remember(s) { AiSetup.ready(ctx) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("AI দিয়ে গুছান (Mode B)", "Tidy with AI (Mode B)"), fontWeight = FontWeight.SemiBold)
            if (!ready) {
                Text(tr("AI প্রোভাইডার চালু নেই।", "No AI provider set up."), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = openAi) { Text(tr("AI প্রোভাইডার সেট করুন →", "Set up AI providers →")) }
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FormatStyle.entries.forEach { st ->
                    FilterChip(selected = st == style, onClick = { SettingsStore.update { it.copy(formatStyle = st.name) } }, label = { Text(tr(st.bn, st.en)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && current.isNotBlank(), onClick = {
                    busy = true; error = null; result = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { AiFormatter.format(AiSetup.router(ctx), current, style) } }
                            .onSuccess { result = it }.onFailure { error = AiSetup.explain(it) }
                        busy = false
                    }
                }) { Text(if (busy) tr("গোছানো হচ্ছে…", "Working…") else tr("গুছাও", "Tidy")) }
                previous?.let { p -> OutlinedButton(onClick = { onApply(p); previous = null }) { Text(tr("আগের লেখায় ফিরুন", "Undo")) } }
            }
            error?.let { Text("✗ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            result?.let { r ->
                Text(r.text, style = MaterialTheme.typography.bodyMedium)
                Text("${r.answer.provider.label} · ${r.answer.model}", style = MaterialTheme.typography.labelSmall)
                if (r.warnings.isEmpty()) Text(tr("কোনো নতুন সংখ্যা/নাম/লিংক ধরা পড়েনি — তবু একবার পড়ে নিন।", "No new numbers/names/links detected — still, read it once."), style = MaterialTheme.typography.bodySmall)
                r.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { previous = current; onApply(r.text); result = null }) { Text(tr("এটা নিন", "Use this")) }
                    OutlinedButton(onClick = { result = null }) { Text(tr("মূলটাই রাখুন", "Keep original")) }
                }
            }
        }
    }
}
