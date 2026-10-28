package com.voicedock.ai.insertion

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.core.content.ContextCompat
import com.voicedock.ai.agent.AgentSafety
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Method B — Accessibility-assisted insertion (optional, user-enabled).
 *
 * Scope is deliberately minimal: it does nothing until the user taps Insert. Then it looks up ONLY the
 * input field that currently has focus, never reads other screen content, never stores anything and
 * never fills password fields.
 */
class InsertAccessibilityService : AccessibilityService() {

    data class NodeResult(val result: InsertResult, val pkg: String = "")

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no event processing */ }
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }

    fun insertIntoFocused(text: String, expectedPackage: String? = null): NodeResult {
        val root = rootInActiveWindow ?: return NodeResult(InsertResult.NO_FIELD)
        val rootPkg = root.packageName?.toString().orEmpty()
        if (rootPkg.isBlank() || (expectedPackage != null && rootPkg != expectedPackage))
            return NodeResult(InsertResult.NO_FIELD)
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(rootPkg, 0)).toString()
        }.getOrDefault("")
        if (AgentSafety.isBlockedPackage(rootPkg, emptySet()) || AgentSafety.isFinanceLabel(label) || AgentSafety.looksSecret(text))
            return NodeResult(InsertResult.SECURE_FIELD, rootPkg)
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return NodeResult(InsertResult.NO_FIELD)
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg != rootPkg) return NodeResult(InsertResult.NO_FIELD, pkg)
        if (node.isPassword) return NodeResult(InsertResult.SECURE_FIELD, pkg)
        if (!node.isEditable) return NodeResult(InsertResult.NO_FIELD, pkg)

        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val r = InsertionMath.insert(existing, node.textSelectionStart, node.textSelectionEnd, text)

        // 1) ACTION_SET_TEXT with the full new text, then move the cursor after the inserted part
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, r.text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, r.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, r.cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            node.refresh()
            val now = node.text?.toString().orEmpty()
            if (now.contains(text.trim().take(40))) return NodeResult(InsertResult.INSERTED, pkg)
        }
        // 2) paste at the cursor (some editors ignore SET_TEXT)
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VoiceDock", text))
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            node.refresh()
            if (node.text?.toString().orEmpty().contains(text.trim().take(40))) return NodeResult(InsertResult.INSERTED, pkg)
        }
        return NodeResult(InsertResult.REJECTED, pkg)
    }

    /** Display capture used by the separately initiated legacy agent (Android 11+). */
    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { onResult(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null } finally { r.hardwareBuffer.close() }
                    onResult(bmp)
                }
                override fun onFailure(errorCode: Int) { onResult(null) }
            })
        } catch (_: Exception) { onResult(null) }
    }

    /** Capture this exact window on API 34+. Secure/unavailable windows fail closed. */
    fun captureWindow(windowId: Int, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 34 || windowId < 0) { onResult(null); return }
        try {
            takeScreenshotOfWindow(windowId, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) { null } finally { r.hardwareBuffer.close() }
                    onResult(bmp)
                }
                override fun onFailure(errorCode: Int) { onResult(null) }
            })
        } catch (_: Exception) { onResult(null) }
    }

    /** Visible text of the active window (labels, buttons), password fields skipped. Only on explicit tap. */
    fun screenText(maxChars: Int = 4000, expectedPackage: String? = null, expectedWindowId: Int? = null): String {
        val root = rootInActiveWindow ?: return ""
        if (expectedPackage != null && root.packageName?.toString() != expectedPackage) return ""
        if (expectedWindowId != null && root.windowId != expectedWindowId) return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || sb.length > maxChars) return
            if (n.isVisibleToUser && !n.isPassword) {
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                val label = listOf(t, d).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
                if (label.isNotEmpty()) {
                    val tag = when { n.isEditable -> "[field] "; n.isClickable -> "[button] "; n.isCheckable -> if (n.isChecked) "[on] " else "[off] "; else -> "" }
                    sb.append(tag).append(label.take(200)).append('\n')
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    companion object {
        @Volatile var instance: InsertAccessibilityService? = null; private set
    }
}
