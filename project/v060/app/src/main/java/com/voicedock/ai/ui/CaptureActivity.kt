package com.voicedock.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.data.database.KbSource
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.screens.AiFormatCard
import com.voicedock.ai.ui.screens.KbUi
import com.voicedock.ai.ui.theme.VoiceDockTheme
import kotlinx.coroutines.launch

/**
 * Entry point for "Share → VoiceDock" (text or files) and the text-selection menu (PROCESS_TEXT).
 * Nothing is saved until the user taps a button; leaving unsaved needs a second Back press.
 */
class CaptureActivity : ComponentActivity() {

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val processText = i.action == Intent.ACTION_PROCESS_TEXT
        val readOnly = i.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val initialText = (if (processText) i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) else i.getCharSequenceExtra(Intent.EXTRA_TEXT))?.toString().orEmpty()
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val uris = streams(i)

        setContent {
            VoiceDockTheme {
                val scope = rememberCoroutineScope()
                var title by remember { mutableStateOf(subject) }
                var text by remember { mutableStateOf(initialText) }
                var status by remember { mutableStateOf<String?>(null) }
                var saved by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var backArmed by remember { mutableStateOf(false) }

                BackHandler(enabled = !saved && !backArmed && (text.isNotBlank() || uris.isNotEmpty())) {
                    backArmed = true
                    Toast.makeText(this, tr("সংরক্ষণ হয়নি। বের হতে আবার Back চাপুন।", "Not saved. Press Back again to leave."), Toast.LENGTH_SHORT).show()
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("VoiceDock", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (uris.isNotEmpty()) {
                            Text(tr("${uris.size}টি ফাইল শেয়ার করা হয়েছে", "${uris.size} file(s) shared"))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy && !saved, onClick = {
                                    busy = true
                                    scope.launch {
                                        val out = mutableListOf<String>()
                                        for (u in uris.take(20)) out += KbUi.importUri(this@CaptureActivity, u, KbSource.SHARE, false)
                                        status = out.joinToString("\n"); busy = false; saved = true
                                    }
                                }) { Text(tr("নলেজ বেসে রাখুন", "Save to knowledge")) }
                                if (KbUi.ocrAvailable(this@CaptureActivity)) OutlinedButton(enabled = !busy && !saved, onClick = {
                                    busy = true
                                    scope.launch {
                                        val out = mutableListOf<String>()
                                        for (u in uris.take(10)) out += KbUi.importUri(this@CaptureActivity, u, KbSource.SHARE, true)
                                        status = out.joinToString("\n"); busy = false; saved = true
                                    }
                                }) { Text(tr("OCR সহ রাখুন (Gemini)", "Save with OCR (Gemini)")) }
                            }
                        }
                        if (text.isNotBlank() || uris.isEmpty()) {
                            OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(tr("শিরোনাম (ঐচ্ছিক)", "Title (optional)")) })
                            OutlinedTextField(value = text, onValueChange = { text = it; saved = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text(tr("লেখা", "Text")) })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(enabled = text.isNotBlank() && !busy, onClick = {
                                    scope.launch {
                                        status = KbUi.message(VoiceDock.knowledge(this@CaptureActivity).addText(title, text, if (processText) KbSource.PROCESS_TEXT else KbSource.SHARE, callingPackage.orEmpty()))
                                        saved = true
                                    }
                                }) { Text(tr("নলেজ বেসে রাখুন", "Save to knowledge")) }
                                if (processText && !readOnly) FilledTonalButton(enabled = text.isNotBlank(), onClick = {
                                    setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text)); saved = true; finish()
                                }) { Text(tr("এই লেখা বসিয়ে দিন", "Replace selection")) }
                                OutlinedButton(enabled = text.isNotBlank(), onClick = {
                                    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VoiceDock", text))
                                    Toast.makeText(this@CaptureActivity, tr("কপি হয়েছে", "Copied"), Toast.LENGTH_SHORT).show()
                                }) { Text(tr("কপি", "Copy")) }
                            }
                            AiFormatCard(current = text, onApply = { text = it; saved = false }, openAi = {
                                startActivity(Intent(this@CaptureActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "ai"))
                            })
                        }
                        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        if (saved) OutlinedButton(onClick = { finish() }) { Text(tr("বন্ধ", "Close")) }
                    }
                }
            }
        }
    }

    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}
