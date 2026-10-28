package com.voicedock.ai.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.voicedock.ai.R
import com.voicedock.ai.agent.AgentController
import com.voicedock.ai.VoiceDock
import com.voicedock.ai.overlay.AssistantOverlayService
import com.voicedock.ai.overlay.OverlayService
import com.voicedock.ai.permissions.Permissions
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.tr
import com.voicedock.ai.ui.screens.DraftsScreen
import com.voicedock.ai.ui.screens.AiProvidersScreen
import com.voicedock.ai.ui.screens.AgentScreen
import com.voicedock.ai.ui.screens.ConnectorsScreen
import com.voicedock.ai.ui.screens.KnowledgeScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import com.voicedock.ai.ui.screens.HomeScreen
import com.voicedock.ai.ui.screens.PrivacyScreen
import com.voicedock.ai.ui.screens.SettingsScreen
import com.voicedock.ai.ui.screens.WorkspaceScreen
import com.voicedock.ai.ui.theme.VoiceDockTheme

enum class Screen { HOME, WORKSPACE, KNOWLEDGE, DRAFTS, SETTINGS, PRIVACY, AI, CONNECTORS, AGENT }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.HOME)
    /** bumped in onResume so permission status rows refresh after returning from system settings */
    private var resumeTick by mutableIntStateOf(0)
    /** v0.6 just-in-time consent dialog for screen context. */
    private var showAssistantConsent by mutableStateOf(false)
    private var afterMicGrant: (() -> Unit)? = null

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        resumeTick++
        if (ok) afterMicGrant?.invoke() else toast(tr("মাইক্রোফোনের অনুমতি ছাড়া ডিক্টেশন সম্ভব নয়", "Dictation needs microphone permission"))
        afterMicGrant = null
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val dictation = VoiceDock.dictation(this)
        setContent {
            VoiceDockTheme {
                val settings by SettingsStore.state.collectAsState()
                @Suppress("UNUSED_VARIABLE") val lang = settings.uiLanguage // recompose on language change
                BackHandler(enabled = screen != Screen.HOME) { screen = if (screen == Screen.AI || screen == Screen.CONNECTORS || screen == Screen.AGENT) Screen.SETTINGS else Screen.HOME }
                Scaffold(
                    topBar = { TopAppBar(title = { Text(title(screen)) }) },
                    bottomBar = {
                        NavigationBar {
                            // v0.6: 3 primary surfaces. Knowledge / Drafts live behind the Home cards + Settings links.
                            listOf(Screen.HOME to tr("হোম", "Home"), Screen.WORKSPACE to tr("ডিক্টেশন", "Dictate"),
                                Screen.SETTINGS to tr("সেটিংস", "Settings")).forEach { (s, label) ->
                                NavigationBarItem(selected = screen == s || (s == Screen.SETTINGS && screen in setOf(Screen.AI, Screen.CONNECTORS, Screen.PRIVACY, Screen.AGENT)), onClick = { screen = s },
                                    icon = {
                                        when (s) {
                                            Screen.WORKSPACE -> Icon(painterResource(R.drawable.ic_mic), contentDescription = null)
                                            else -> Icon(when (s) { Screen.HOME -> Icons.Filled.Home; Screen.KNOWLEDGE -> Icons.Filled.Search; Screen.DRAFTS -> Icons.Filled.List; else -> Icons.Filled.Settings }, contentDescription = null)
                                        }
                                    }, label = { Text(label, maxLines = 1) })
                            }
                        }
                    },
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Top) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(
                                resumeTick = resumeTick, controller = dictation,
                                requestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                requestNotifications = { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                openOverlaySettings = { startActivity(Permissions.overlaySettings(this@MainActivity)) },
                                openAccessibilitySettings = { startActivity(Permissions.accessibilitySettings()) },
                                startBubble = { startBubble() },
                                openWorkspace = { screen = Screen.WORKSPACE },
                                openPrivacy = { screen = Screen.PRIVACY },
                                openSettings = { screen = Screen.SETTINGS },
                                openAgent = { screen = Screen.AGENT },
                                openKnowledge = { screen = Screen.KNOWLEDGE },
                                openDrafts = { screen = Screen.DRAFTS },
                            )
                            Screen.WORKSPACE -> WorkspaceScreen(dictation, startDictation = { withMic { startInApp() } }, openAi = { screen = Screen.AI })
                            Screen.KNOWLEDGE -> KnowledgeScreen(openConnectors = { screen = Screen.CONNECTORS }, openAi = { screen = Screen.AI })
                            Screen.AI -> AiProvidersScreen()
                            Screen.CONNECTORS -> ConnectorsScreen()
                            Screen.DRAFTS -> DraftsScreen(VoiceDock.drafts(this@MainActivity))
                            Screen.SETTINGS -> SettingsScreen(openPrivacy = { screen = Screen.PRIVACY }, openAi = { screen = Screen.AI }, openConnectors = { screen = Screen.CONNECTORS }, openAgent = { screen = Screen.AGENT }, resumeTick = resumeTick, openKnowledge = { screen = Screen.KNOWLEDGE }, openDrafts = { screen = Screen.DRAFTS })
                            Screen.AGENT -> AgentScreen(resumeTick, start = { startAgent(it) },
                                openAccessibility = { startActivity(Permissions.accessibilitySettings()) }, openAi = { screen = Screen.AI })
                            Screen.PRIVACY -> PrivacyScreen(resumeTick,
                                openAppSettings = { startActivity(Permissions.appSettings(this@MainActivity)) },
                                openAccessibilitySettings = { startActivity(Permissions.accessibilitySettings()) })
                        }
                        if (showAssistantConsent) AssistantConsentDialog()
                    }
                }
            }
        }
    }

    private fun title(s: Screen) = when (s) {
        Screen.HOME -> "VoiceDock AI"
        Screen.WORKSPACE -> tr("ডিক্টেশন ও রিভিউ", "Dictation & review")
        Screen.DRAFTS -> tr("ড্রাফট ইতিহাস", "Draft history")
        Screen.SETTINGS -> tr("সেটিংস", "Settings")
        Screen.PRIVACY -> tr("অনুমতি ও প্রাইভেসি", "Permissions & privacy")
        Screen.KNOWLEDGE -> tr("নলেজ বেস", "Knowledge base")
        Screen.AI -> tr("AI প্রোভাইডার", "AI providers")
        Screen.CONNECTORS -> tr("কানেক্টর", "Connectors")
        Screen.AGENT -> tr("এজেন্ট", "Agent")
    }

    override fun onResume() { super.onResume(); resumeTick++ }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra(EXTRA_SCREEN)) {
            "workspace" -> screen = Screen.WORKSPACE
            "drafts" -> screen = Screen.DRAFTS
            "settings" -> screen = Screen.SETTINGS
            "privacy" -> screen = Screen.PRIVACY
            "ai" -> screen = Screen.AI
            "knowledge" -> screen = Screen.KNOWLEDGE
            "agent" -> screen = Screen.AGENT
        }
    }

    private fun withMic(block: () -> Unit) {
        if (Permissions.mic(this)) block() else { afterMicGrant = block; micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** In-app dictation: the foreground service keeps the microphone session valid if the user switches apps. */
    private fun startInApp() {
        OverlayService.start(this, OverlayService.ACTION_HOLD)
        VoiceDock.dictation(this).start()
    }

    /**
     * v0.6: starts the MIC-FREE assistant bubble. No RECORD_AUDIO permission is needed any more —
     * that was the v0.5 defect. Screen-context consent is asked just-in-time, on first use.
     */
    private fun startBubble() {
        if (!Permissions.overlay(this)) {
            toast(tr("‘অন্য অ্যাপের উপরে দেখানো’ চালু করুন, তারপর ফিরে আসুন", "Enable ‘Display over other apps’, then come back"))
            startActivity(Permissions.overlaySettings(this)); return
        }
        if (!SettingsStore.state.value.assistantScreenContext) { showAssistantConsent = true; return }
        actuallyStartAssistant()
    }

    private fun actuallyStartAssistant() {
        // Avoid two bubbles: the old mic bubble (if running) is closed first.
        if (OverlayService.running) OverlayService.start(this, OverlayService.ACTION_CLOSE)
        AssistantOverlayService.start(this, AssistantOverlayService.ACTION_SHOW)
        lifecycleScope.launch {
            var waited = 0
            while (!AssistantOverlayService.running && waited < 3000) { delay(100); waited += 100 }
            if (AssistantOverlayService.running) {
                toast(tr("সহকারী বাবল চালু — যেকোনো অ্যাপে ট্যাপ করুন", "Assistant bubble is on — tap it in any app"))
                moveTaskToBack(true)
            } else toast(tr("সহকারী চালু হয়নি — নোটিফিকেশন অনুমতি দিয়ে আবার চেষ্টা করুন", "Assistant did not start — allow notifications and retry"))
        }
    }

    @Composable
    private fun AssistantConsentDialog() {
        AlertDialog(
            onDismissRequest = { showAssistantConsent = false },
            title = { Text(tr("স্ক্রিন প্রসঙ্গ অনুমতি", "Screen-context permission")) },
            text = {
                Text(tr(
                    "আপনি বাবলে চাপ দিলে সহকারী একবার দৃশ্যমান লেখা দেখে প্রাসঙ্গিক সাহায্য দেবে। " +
                        "পরিচিত ব্যাংকিং অ্যাপ ও পাসওয়ার্ড ঘর এড়ায়, কিন্তু সব গোপন তথ্য বা সুরক্ষিত স্ক্রিন শনাক্ত করতে পারে না। " +
                        "সংবেদনশীল অ্যাপ খোলার আগে অনুমতি বন্ধ করুন। না দিলেও নিজের বাবলে চ্যাট চলবে।",
                    "On a bubble tap the assistant checks visible text once for context. " +
                        "Known finance apps and password fields are avoided, but not every secret or secure screen can be detected. " +
                        "Turn this off before sensitive apps. If you decline, the assistant still works in its own bubble.",
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    SettingsStore.update { it.copy(assistantScreenContext = true) }
                    showAssistantConsent = false
                    actuallyStartAssistant()
                }) { Text(tr("চালু করুন", "Enable")) }
            },
            dismissButton = {
                TextButton(onClick = { showAssistantConsent = false; actuallyStartAssistant() }) {
                    Text(tr("না, শুধু চ্যাট", "No, chat only"))
                }
            },
        )
    }

    /** The agent needs the bubble service (status strip + Stop) before VoiceDock steps aside. */
    private fun startAgent(goal: String) {
        AgentController.blocker(this)?.let { toast(it); return }
        if (AssistantOverlayService.running) AssistantOverlayService.start(this, AssistantOverlayService.ACTION_HIDE)
        if (OverlayService.running && OverlayService.foregroundOk) { AgentController.start(this, goal, this); return }
        withMic {
            if (!Permissions.overlay(this)) {
                toast(tr("‘অন্য অ্যাপের উপরে দেখানো’ চালু করুন, তারপর ফিরে আসুন", "Enable ‘Display over other apps’, then come back"))
                startActivity(Permissions.overlaySettings(this)); return@withMic
            }
            OverlayService.start(this, OverlayService.ACTION_BUBBLE)
            lifecycleScope.launch {
                var waited = 0
                while (!OverlayService.foregroundOk && waited < 3000) { delay(100); waited += 100 }
                if (OverlayService.foregroundOk) AgentController.start(this@MainActivity, goal, this@MainActivity)
                else toast(tr("বাবল সার্ভিস চালু হয়নি — নোটিফিকেশন অনুমতি দিয়ে আবার চেষ্টা করুন", "Bubble service did not start — allow notifications and retry"))
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { const val EXTRA_SCREEN = "screen" }
}
