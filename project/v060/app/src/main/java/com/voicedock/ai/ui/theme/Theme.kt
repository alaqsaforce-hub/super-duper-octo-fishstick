package com.voicedock.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.voicedock.ai.settings.SettingsStore
import com.voicedock.ai.settings.ThemeMode

val MicRed = Color(0xFFD32F2F)
val PauseAmber = Color(0xFFF9A825)
val ReviewGreen = Color(0xFF2E7D32)

private val Light = lightColorScheme(primary = Color(0xFF0B57D0), secondary = Color(0xFF00696B), tertiary = ReviewGreen)
private val Dark = darkColorScheme(primary = Color(0xFFA8C7FA), secondary = Color(0xFF4CDADB), tertiary = Color(0xFF81C784))

@Composable
fun VoiceDockTheme(content: @Composable () -> Unit) {
    val s by SettingsStore.state.collectAsState()
    val dark = when (s.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
