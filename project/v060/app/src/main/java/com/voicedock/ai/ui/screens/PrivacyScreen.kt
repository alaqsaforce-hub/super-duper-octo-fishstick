package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.tr

@Composable
fun PrivacyScreen(resumeTick: Int, openAppSettings: () -> Unit, openAccessibilitySettings: () -> Unit) {
    val ctx = LocalContext.current
    val rows = remember(resumeTick) {
        listOf(
            Triple(tr("মাইক্রোফোন", "Microphone"), Permissions.mic(ctx), tr("শুধু আপনি ‘বলা শুরু’ চাপলে; চালু থাকলে লাল চিহ্ন ও নোটিফিকেশন দেখায়।", "Only after you tap Start; a red indicator and a notification show while on.")),
            Triple(tr("নোটিফিকেশন", "Notifications"), Permissions.notifications(ctx), tr("মাইক চালু থাকার নোটিফিকেশন ও দ্রুত বোতাম।", "Mic-on notification with quick buttons.")),
            Triple(tr("অন্য অ্যাপের উপরে দেখানো", "Display over other apps"), Permissions.overlay(ctx), tr("শুধু ভাসমান বাবল ও প্যানেলের জন্য।", "Only for the floating bubble and panel.")),
            Triple(tr("Notification access (ঐচ্ছিক)", "Notification access (optional)"), Permissions.notificationListener(ctx), tr("ঘোষিত কিন্তু ব্যবহার বন্ধ যতক্ষণ না আপনি v0.7-এ ইভেন্ট সংগ্রহ চালু করেন।", "Declared but unused until you enable event capture in v0.7.")),
            Triple("Accessibility", Permissions.accessibility(ctx), tr("ঐচ্ছিক। অনুমতি দিলে বাবলে চাপের সময় স্ক্রিন যাচাই করে; অনুমোদিত ড্রাফট নিরাপদ ফোকাস করা ঘরে বসায় বা কপি করে। আপনার চালু করা এজেন্ট টাস্ক চলাকালেও স্ক্রিন পড়তে পারে।", "Optional. With consent, the bubble checks the screen on tap; an approved draft is inserted into a verified focused field or copied. A user-started agent task may also read screens while it runs.")),
        )
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (name, ok, why) ->
            Card { Column(Modifier.padding(12.dp)) {
                Text("$name — ${if (ok) tr("চালু", "granted") else tr("বন্ধ", "off")}", style = MaterialTheme.typography.titleSmall)
                Text(why, style = MaterialTheme.typography.bodySmall)
            } }
        }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("আপনার ডেটা কোথায় যায়", "Where your data goes"), style = MaterialTheme.typography.titleSmall)
            Text(tr(
                "• আপনার কথা ফোনের স্পিচ রিকগনাইজার (সাধারণত Google অ্যাপ) প্রসেস করে। অনলাইন মোডে অডিও Google-এর সার্ভারে যেতে পারে; ‘ডিভাইসে’ মোডে ফোনেই থাকে (যদি মডেল থাকে)।\n" +
                "• লেখা শুধু এই ফোনের অ্যাপ ডাটাবেসে থাকে। ক্লাউড ব্যাকআপ বন্ধ।\n" +
                "• Gemini শুধু তখনই ব্যবহার হয় যখন আপনি নিজের key দেন ও ‘পাঠানোর অনুমতি’ চালু করেন: তখন Gemini ইঞ্জিনে কথার অডিও এবং ‘স্ক্রিন বুঝিয়ে দাও’ চাপলে সেই স্ক্রিনশট Google-এ যায়। Key ফোনে এনক্রিপ্ট করা থাকে। লোকাল-অনলি মোডে কিছুই যায় না। কোনো অ্যানালিটিক্স নেই।\n" +
                "• ক্লিপবোর্ড শুধু আপনি কপি/বসান চাপলে লেখা হয়; ব্যাকগ্রাউন্ডে পড়া হয় না।\n• বাবলের স্ক্রিন-নিয়ম সব OTP বা সুরক্ষিত স্ক্রিন চিনতে পারে না। সংবেদনশীল অ্যাপ ব্যবহারের আগে স্ক্রিন প্রসঙ্গ বন্ধ করুন।",
                "• Your speech is processed by the phone's speech recogniser (usually the Google app). In online mode audio may go to Google's servers; in on-device mode it stays on the phone (if the model is installed).\n" +
                "• Text is stored only in this app's database on the phone. Cloud backup is disabled.\n" +
                "• Gemini is used only with your own key and ‘Allow sending’ on: then audio (Gemini engine) and, on ‘Explain screen’, that screenshot go to Google. The key is stored encrypted. Local-only mode sends nothing. No analytics.\n" +
                "• The clipboard is written only when you tap Copy/Insert; it is never read in the background.\n" +
                "• Screen rules cannot detect every OTP or protected screen. Turn screen-context consent off before sensitive apps."),
                style = MaterialTheme.typography.bodySmall)
        } }
        OutlinedButton(onClick = openAppSettings) { Text(tr("অ্যাপের অনুমতি পরিবর্তন", "Change app permissions")) }
        OutlinedButton(onClick = openAccessibilitySettings) { Text(tr("Accessibility সেটিংস", "Accessibility settings")) }
    }
}
