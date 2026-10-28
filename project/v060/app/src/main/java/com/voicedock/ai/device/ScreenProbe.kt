package com.voicedock.ai.device

import android.view.accessibility.AccessibilityNodeInfo
import com.voicedock.ai.context.DefaultSafetyOracle
import com.voicedock.ai.context.SafetyOracle
import com.voicedock.ai.context.ContextSnapshot
import com.voicedock.ai.context.FieldInfo
import com.voicedock.ai.insertion.InsertAccessibilityService

/**
 * One user-triggered accessibility probe. No screenshot, network call, or disk write occurs here.
 * Package/label blocklists are checked BEFORE traversing the window. Password node text is never
 * read, and other visible strings are used only transiently to count content and spot secrets.
 * Field labels and hints are deliberately not retained in the returned snapshot.
 *
 * Android does not expose another window's FLAG_SECURE through AccessibilityNodeInfo, so this
 * local probe cannot guarantee detection of every secure window. Callers must not assume it can.
 */
object ScreenProbe {
    fun probe(
        svc: InsertAccessibilityService?,
        now: Long = System.currentTimeMillis(),
        maxChars: Int = 4000,
        screenshotBlocked: Boolean = false,
        oracle: SafetyOracle = DefaultSafetyOracle,
    ): ContextSnapshot {
        if (svc == null) return ContextSnapshot(capturedAt = now, probeOk = false)
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null }
            ?: return ContextSnapshot(capturedAt = now, probeOk = false)
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return ContextSnapshot(capturedAt = now, probeOk = false)
        val appLabel = try {
            val pm = svc.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { "" }
        val isSelf = pkg == svc.packageName
        // Do not inspect even a single child of a known financial, blocked, or self window.
        if (isSelf || screenshotBlocked || oracle.blockedPackage(pkg) || oracle.financeLabel(appLabel)) {
            return ContextSnapshot(pkg = pkg, appLabel = appLabel, capturedAt = now,
                flagSecure = screenshotBlocked, isSelf = isSelf)
        }

        val fields = ArrayList<FieldInfo>(8)
        var chars = 0
        var elements = 0
        var secureHits = 0
        var visited = 0
        var incomplete = false
        val charLimit = maxChars.coerceAtLeast(1)

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || incomplete) return
            if (depth > 50 || ++visited > 1000 || chars > charLimit) { incomplete = true; return }
            if (n.isVisibleToUser) {
                val password = n.isPassword
                if (n.isEditable || password) {
                    fields += FieldInfo(focused = n.isFocused, editable = n.isEditable, password = password)
                }
                if (password) return // do not request text, hint, description, or password children
                val text = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val hint = n.hintText?.toString()?.trim().orEmpty()
                val parts = listOf(text, desc, hint).filter { it.isNotEmpty() }.distinct()
                if (parts.isNotEmpty()) {
                    chars += parts.sumOf { it.length }
                    elements++
                    if (parts.any { oracle.secretText(it) }) secureHits++
                    if (chars > charLimit) { incomplete = true; return }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        try { walk(root, 0) } catch (_: Exception) { incomplete = true }

        return ContextSnapshot(
            pkg = pkg, appLabel = appLabel, capturedAt = now, fields = fields,
            visibleCharCount = chars, elementCount = elements, secureTextHits = secureHits,
            flagSecure = false, isSelf = false,
            // A truncated/failed scan cannot safely certify that no password/OTP was present.
            probeOk = !incomplete,
        )
    }
}
