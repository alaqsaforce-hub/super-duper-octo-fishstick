package com.voicedock.ai.ui.screens

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.ime.BanglaPhonetic
import com.voicedock.ai.settings.tr

/** Enable / pick the VoiceDock keyboard, with a phonetic cheat sheet and a try-it box (converter only). */
@Composable
fun KeyboardSection(resumeTick: Int = 0) {
    val ctx = LocalContext.current
    val imm = remember { ctx.getSystemService(InputMethodManager::class.java) }
    val enabled = remember(resumeTick) { imm.enabledInputMethodList.any { it.packageName == ctx.packageName } }
    var showHelp by remember { mutableStateOf(false) }
    var trial by remember { mutableStateOf("ami bangla likhchi") }

    Text(tr("VoiceDock কিবোর্ড", "VoiceDock keyboard"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("নিজস্ব কিবোর্ড: ইংরেজি অক্ষরে টাইপ করলে বাংলা হয় (ami → আমি), উপরে মাইক — বলুন, তারপর \"বসাও\" চাপলে লেখাটা ঠিক ওই ঘরেই বসে যায়। Accessibility লাগে না। পাসওয়ার্ড ঘরে ভয়েস বন্ধ থাকে; যা টাইপ করেন তা কোথাও জমা বা পাঠানো হয় না।",
                "Own keyboard: type Latin letters to get Bengali (ami → আমি); mic on top — speak, then tap \"বসাও\" to type the text right into that field. No Accessibility needed. Voice is off in password fields; nothing you type is stored or sent."),
                style = MaterialTheme.typography.bodySmall)
            Text(if (enabled) tr("✓ সিস্টেমে চালু আছে", "✓ Enabled in system settings") else tr("এখনো চালু করা হয়নি", "Not enabled yet"),
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text(if (enabled) tr("কিবোর্ড সেটিংস", "Keyboard settings") else tr("১. চালু করুন", "1. Enable"))
                }
                OutlinedButton(onClick = { imm.showInputMethodPicker() }, enabled = enabled) { Text(tr("২. বেছে নিন", "2. Choose")) }
            }
            Text(tr("Android সতর্ক করবে যে কিবোর্ড টাইপ করা লেখা দেখতে পারে — এটা সব কিবোর্ডের জন্যই দেখায়। কিবোর্ডে 🌐 বাং/EN চাপলে ভাষা বদলায়, চেপে ধরলে অন্য কিবোর্ডে যাওয়া যায়।",
                "Android warns that a keyboard can see what you type — it shows this for every keyboard. Tap 🌐 বাং/EN to switch language; long-press to switch keyboard."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = trial, onValueChange = { trial = it }, label = { Text(tr("চেষ্টা করুন (ফোনেটিক)", "Try it (phonetic)")) }, modifier = Modifier.fillMaxWidth())
            Text(BanglaPhonetic.convert(trial), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) tr("নিয়ম লুকান", "Hide rules") else tr("টাইপের নিয়ম দেখুন", "Show typing rules")) }
            if (showHelp) Text(CHEAT_SHEET, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Normal)
        }
    }
}

private const val CHEAT_SHEET = """স্বর: o=অ (ব্যঞ্জনের পরে কিছু না) · a=আ/া · i=ই/ি · I বা ee=ঈ/ী · u=উ/ু · U বা oo=ঊ/ূ · e=এ/ে · O=ও/ো · OI=ঐ/ৈ · OU=ঔ/ৌ · rri=ঋ/ৃ
ব্যঞ্জন: k খ=kh g gh · c=চ ch=ছ j jh · T=ট Th D=ড Dh N=ণ · t th d dh n · p ph/f b bh/v m · z=য r l · sh/S=শ Sh=ষ s h · R=ড় Rh=ঢ় · y=য় · kkh=ক্ষ gg=জ্ঞ
চিহ্ন: ng=ং · ^=ঁ · t``=ৎ · :`=ঃ · .=। · সংখ্যা → ০-৯ · $=৳
যুক্তাক্ষর: সাধারণগুলো নিজে জোড়ে (nt=ন্ত, nd=ন্দ, st=স্ত, sk=স্ক, kt=ক্ত, tt=ত্ত, mp=ম্প, rm=র্ম, kr=ক্র, pr=প্র …)। ব্যঞ্জন+y = য-ফলা (by=ব্য), ব্যঞ্জন+w = ব-ফলা (sw=স্ব)।
জোর করে জোড়া: + (kor+ta → কর্তা) · জোড়া ভাঙতে: ` (kin`te → কিনতে) · ?123 পাতায় ্ (হসন্ত)।
উদাহরণ: ami tOmake bhalobasi → আমি তোমাকে ভালবাসি (ো চাইলে বড় O) · amra korte pari → আমরা করতে পারি · kintu → কিন্তু · coTTogram → চট্টগ্রাম"""
