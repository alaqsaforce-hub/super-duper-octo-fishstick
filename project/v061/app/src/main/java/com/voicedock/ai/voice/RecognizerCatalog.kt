package com.voicedock.ai.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** A speech recognition service installed on the phone (any app that implements android.speech.RecognitionService). */
data class RecognizerInfo(
    val component: ComponentName,
    val label: String,
    val isSystemDefault: Boolean,
    val kind: Kind,
) {
    enum class Kind { GOOGLE, OFFLINE_WHISPER, VENDOR, OTHER }
    val id: String get() = component.flattenToString()
}

/** Result of asking a service whether it supports a language (Android 13+ only; older phones = UNKNOWN). */
enum class LangSupport { ONLINE_OR_INSTALLED, DOWNLOADABLE, NOT_SUPPORTED, UNKNOWN }

/** An app the user can install to get a Bengali-capable recogniser (links only — never installed silently). */
data class SuggestedEngine(val name: String, val noteBn: String, val noteEn: String, val url: String)

object RecognizerCatalog {
    const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"
    private val GOOGLE_PKGS = setOf(GOOGLE_PKG, "com.google.android.tts", "com.google.android.as")
    private val VENDOR_PREFIXES = listOf("com.xiaomi", "com.miui", "com.samsung", "com.huawei", "com.hihonor", "com.oppo",
        "com.coloros", "com.heytap", "com.vivo", "com.bbk", "com.oneplus", "com.realme", "com.transsion", "com.iflytek", "com.baidu")
    private val WHISPER_HINTS = listOf("whisper", "transcribe", "stt", "voiceinput", "marmalade", "kaldi", "vosk", "sherpa")

    val suggestions = listOf(
        SuggestedEngine("Google app (Google ভয়েস)", "বাংলা অনলাইনে ভালো চেনে; Xiaomi-তে প্রায়ই আগে থেকে থাকে — আপডেট করুন",
            "Good Bengali (online); often preinstalled — update it", "market://details?id=$GOOGLE_PKG"),
        SuggestedEngine("Speech Services by Google", "অফলাইন ভাষা-মডেল ডাউনলোডের জন্য", "For downloadable offline language models",
            "market://details?id=com.google.android.tts"),
        SuggestedEngine("whisperIME (অফলাইন Whisper)", "ইন্টারনেট ছাড়া; ~৪৩৫ MB মডেল; বাংলা মান মাঝারি; সিস্টেম স্পিচ সার্ভিস দেয়",
            "Offline; ~435 MB model; Bengali quality moderate; provides a system speech service", "https://github.com/woheller69/whisperIME"),
        SuggestedEngine("Gemini API key (ফ্রি)", "সবচেয়ে সহজ: key বানিয়ে সেটিংসে বসান — বাংলা+ইংরেজি মিশ্রও ভালো",
            "Easiest: create a key and paste it in Settings — handles mixed Bengali+English", "https://aistudio.google.com/apikey"),
    )

    fun systemDefault(ctx: Context): ComponentName? =
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")?.takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString)

    fun kindOf(pkg: String, label: String): RecognizerInfo.Kind {
        val p = pkg.lowercase(); val l = label.lowercase()
        return when {
            pkg in GOOGLE_PKGS -> RecognizerInfo.Kind.GOOGLE
            WHISPER_HINTS.any { p.contains(it) || l.contains(it) } -> RecognizerInfo.Kind.OFFLINE_WHISPER
            VENDOR_PREFIXES.any { p.startsWith(it) } -> RecognizerInfo.Kind.VENDOR
            else -> RecognizerInfo.Kind.OTHER
        }
    }

    /** All installed recognition services (package visibility is granted by the <queries> entry in the manifest). */
    fun list(ctx: Context): List<RecognizerInfo> {
        val pm = ctx.packageManager
        val def = systemDefault(ctx)
        @Suppress("DEPRECATION")
        val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
        return services.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (si.packageName == ctx.packageName) return@mapNotNull null
            val cn = ComponentName(si.packageName, si.name)
            val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(si.packageName, 0)).toString() }.getOrDefault(si.packageName)
            val svcLabel = ri.loadLabel(pm)?.toString().orEmpty()
            val label = if (svcLabel.isNotBlank() && svcLabel != appLabel) "$appLabel — $svcLabel" else appLabel
            RecognizerInfo(cn, label, cn == def, kindOf(si.packageName, label))
        }.distinctBy { it.id }
    }

    /**
     * Candidate order for a language. Pure ordering (unit-tested): user's choice first, then Google, offline Whisper apps,
     * the system default, other apps, and vendor (Xiaomi/Samsung…) services last — vendor services rarely support Bengali.
     */
    fun order(all: List<RecognizerInfo>, preferredId: String?, support: Map<String, LangSupport> = emptyMap()): List<RecognizerInfo> {
        val rank = { r: RecognizerInfo ->
            when {
                r.id == preferredId -> 0
                support[r.id] == LangSupport.NOT_SUPPORTED -> 90
                r.kind == RecognizerInfo.Kind.GOOGLE && r.component.packageName == GOOGLE_PKG -> 10
                r.kind == RecognizerInfo.Kind.GOOGLE -> 12
                support[r.id] == LangSupport.ONLINE_OR_INSTALLED -> 15
                r.kind == RecognizerInfo.Kind.OFFLINE_WHISPER -> 20
                r.isSystemDefault && r.kind != RecognizerInfo.Kind.VENDOR -> 30
                r.kind == RecognizerInfo.Kind.OTHER -> 40
                else -> 60
            }
        }
        return all.sortedBy(rank)
    }

    /** Android 13+: ask a service whether it can recognise [languageTag]. Must run on the main thread internally. */
    suspend fun checkSupport(ctx: Context, info: RecognizerInfo, languageTag: String): LangSupport {
        if (Build.VERSION.SDK_INT < 33) return LangSupport.UNKNOWN
        return withContext(Dispatchers.Main) {
            val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx, info.component) }.getOrNull() ?: return@withContext LangSupport.UNKNOWN
            try {
                withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine { cont ->
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        rec.checkRecognitionSupport(intent, Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
                            override fun onSupportResult(s: RecognitionSupport) { if (cont.isActive) cont.resume(classify(s, languageTag)) }
                            override fun onError(error: Int) { if (cont.isActive) cont.resume(LangSupport.UNKNOWN) }
                        })
                    }
                } ?: LangSupport.UNKNOWN
            } catch (e: Exception) { LangSupport.UNKNOWN } finally { runCatching { rec.destroy() } }
        }
    }

    private fun classify(s: RecognitionSupport, tag: String): LangSupport {
        val lang = tag.substringBefore('-').lowercase()
        fun has(list: List<String>) = list.any { it.equals(tag, true) || it.substringBefore('-').equals(lang, true) }
        return when {
            has(s.installedOnDeviceLanguages) || has(s.onlineLanguages) -> LangSupport.ONLINE_OR_INSTALLED
            has(s.supportedOnDeviceLanguages) || has(s.pendingOnDeviceLanguages) -> LangSupport.DOWNLOADABLE
            s.installedOnDeviceLanguages.isEmpty() && s.onlineLanguages.isEmpty() && s.supportedOnDeviceLanguages.isEmpty() -> LangSupport.UNKNOWN
            else -> LangSupport.NOT_SUPPORTED
        }
    }

    /** Android 13+: ask a service to download its offline model for the language (user-initiated). */
    fun triggerDownload(ctx: Context, info: RecognizerInfo, languageTag: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val rec = SpeechRecognizer.createSpeechRecognizer(ctx, info.component)
            rec.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag))
            rec.destroy(); true
        }.getOrDefault(false)
    }
}
