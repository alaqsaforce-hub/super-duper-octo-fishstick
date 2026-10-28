package com.voicedock.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedock.ai.agent.AgentController
import com.voicedock.ai.agent.AgentPhase
import com.voicedock.ai.agent.StepStatus
import com.voicedock.ai.settings.tr

@Composable
fun AgentScreen(resumeTick: Int, start: (String) -> Unit, openAccessibility: () -> Unit, openAi: () -> Unit) {
    val ctx = LocalContext.current
    val a by AgentController.ui.collectAsState()
    var goal by rememberSaveable { mutableStateOf("") }
    val blocker = remember(resumeTick, a.phase) { AgentController.blocker(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("এজেন্ট আপনার বলা কাজটা স্ক্রিন পড়ে, ট্যাপ/স্ক্রল/টাইপ করে ধাপে ধাপে করে। প্রতিটি ধাপ বাবলের উপরে দেখা যায়; পাঠানো/মোছা/কল/কেনার আগে আপনার অনুমতি লাগে; ব্যাংক/বিকাশ/নগদ অ্যাপে কাজ করে না। শুধু আপনি শুরু করলেই চলে।",
            "The agent does your task step by step by reading the screen and tapping/scrolling/typing. Every step is shown; send/delete/call/buy need your Allow; banking apps are blocked. It runs only when you start it."),
            style = MaterialTheme.typography.bodySmall)
        if (blocker != null) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(blocker)
                OutlinedButton(onClick = openAccessibility) { Text(tr("Accessibility সেটিংস", "Accessibility settings")) }
                OutlinedButton(onClick = openAi) { Text(tr("AI প্রোভাইডার / লগইন", "AI providers / sign-in")) }
            }
        }
        OutlinedTextField(value = goal, onValueChange = { goal = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            label = { Text(tr("কী করতে হবে? (যেমন: WhatsApp-এ ‘অফিস’ গ্রুপের শেষ বার্তাগুলোর সারাংশ দাও)", "What should it do?")) })
        Button(enabled = blocker == null && !a.running && goal.isNotBlank(), onClick = { start(goal) }) { Text(tr("শুরু করুন", "Start")) }
        Text(tr("তৈরি কাজ:", "Ready-made tasks:"), style = MaterialTheme.typography.labelLarge)
        AgentController.PRESETS.forEach { p ->
            TextButton(enabled = blocker == null && !a.running, onClick = { goal = p.text }) { Text("• " + p.text) }
        }
        Text(tr("টিপ: ‘এই স্ক্রিন’ ধরনের কাজ বাবল থেকে দিন — অ্যাপ থেকে দিলে VoiceDock পেছনে চলে যায় আর আগের অ্যাপের উপর কাজ শুরু হয়।",
            "Tip: give ‘this screen’ tasks from the bubble — from here VoiceDock moves to the back and the agent works on the previous app."), style = MaterialTheme.typography.labelSmall)

        if (a.phase != AgentPhase.IDLE) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🤖 " + a.goal, fontWeight = FontWeight.SemiBold)
                Text("${a.step}/${a.maxSteps} · ${a.status}" + (a.model.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                if (a.running) OutlinedButton(onClick = { AgentController.stop() }) { Text(tr("থামাও", "Stop")) }
                a.pending?.let { p ->
                    Text(tr("অনুমতি দেবেন? ", "Allow? ") + p.action + " — " + p.reason)
                    Button(onClick = { AgentController.answerConfirm(true) }) { Text(tr("হ্যাঁ", "Allow")) }
                    OutlinedButton(onClick = { AgentController.answerConfirm(false) }) { Text(tr("না", "Deny")) }
                }
                if (a.answer.isNotBlank()) {
                    Text(tr("ফলাফল:", "Result:"), style = MaterialTheme.typography.labelLarge)
                    Text(a.answer)
                }
                if (a.steps.isNotEmpty()) {
                    Text(tr("ধাপের লগ:", "Step log:"), style = MaterialTheme.typography.labelLarge)
                    a.steps.takeLast(40).forEach { st ->
                        val mark = when (st.status) { StepStatus.OK -> "✓"; StepStatus.FAILED -> "✗"; StepStatus.DENIED -> "⊘"; StepStatus.BLOCKED -> "⛔"; StepStatus.INFO -> "•" }
                        Text("${st.n}. $mark ${st.action} → ${st.result}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!a.running) TextButton(onClick = { AgentController.clear() }) { Text(tr("মুছে ফেলুন", "Clear")) }
            }
        }
    }
}
