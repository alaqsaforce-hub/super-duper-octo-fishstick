package com.voicedock.ai.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.voicedock.ai.insertion.InsertAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Hides VoiceDock's own floating windows while a screenshot is taken. */
interface OverlayHider { fun hide(); fun show() }

/**
 * [AgentDevice] on top of VoiceDock's accessibility service. Reads the active window's node tree, acts with node
 * actions first (they do not depend on coordinates and are not blocked by our overlay), and falls back to gestures.
 */
class A11yDevice(private val svc: InsertAccessibilityService, private val overlay: OverlayHider?) : AgentDevice {

    companion object {
        /** True when the running service was granted gesture permission (needs a re-enable after the v0.5 update). */
        fun canGesture(svc: AccessibilityService?): Boolean =
            svc?.serviceInfo?.let { (it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0 } ?: false

        private val ALIASES = mapOf(
            "হোয়াটসঅ্যাপ" to "whatsapp", "হোয়াটসএ্যাপ" to "whatsapp", "হোয়াটস অ্যাপ" to "whatsapp", "whats app" to "whatsapp",
            "গ্যালারি" to "gallery", "গ্যালারী" to "gallery", "ছবি" to "gallery", "photos" to "photos", "ফটো" to "gallery",
            "সেটিংস" to "settings", "সেটিং" to "settings", "মেসেজ" to "messages", "মেসেঞ্জার" to "messenger", "ফেসবুক" to "facebook",
            "ইউটিউব" to "youtube", "জিমেইল" to "gmail", "ক্রোম" to "chrome", "ক্যামেরা" to "camera", "ফোন" to "phone",
            "ফাইল" to "file", "ক্যালেন্ডার" to "calendar", "ম্যাপ" to "maps", "টেলিগ্রাম" to "telegram", "ইমো" to "imo",
        )
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = svc.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
    }

    private fun toRaw(n: AccessibilityNodeInfo?, depth: Int): RawNode? {
        if (n == null || depth > 70) return null
        val r = Rect().also { n.getBoundsInScreen(it) }
        val kids = ArrayList<RawNode>(n.childCount)
        if (!n.isPassword) for (i in 0 until n.childCount) toRaw(n.getChild(i), depth + 1)?.let { kids += it }
        return RawNode(
            text = if (n.isPassword) null else if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) n.hintText?.toString() ?: n.text?.toString() else n.text?.toString(),
            desc = if (n.isPassword) null else n.contentDescription?.toString(),
            cls = n.className?.toString(),
            viewId = n.viewIdResourceName,
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable, scrollable = n.isScrollable,
            checkable = n.isCheckable, checked = n.isChecked, password = n.isPassword, visible = n.isVisibleToUser, focused = n.isFocused,
            children = kids, ref = n,
        )
    }

    override suspend fun snapshot(): ScreenSnapshot? = withContext(Dispatchers.Default) {
        val root = svc.rootInActiveWindow ?: return@withContext null
        val pkg = root.packageName?.toString().orEmpty()
        val label = runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val (w, h) = screenSize()
        // Refuse known sensitive packages before reading any child nodes. AgentRunner also checks
        // before invoking the model, so even a blocked snapshot cannot be uploaded.
        if (pkg.isBlank() || AgentSafety.isBlockedPackage(pkg, emptySet()) || AgentSafety.isFinanceLabel(label))
            ScreenSnapshot(pkg, label, w, h, emptyList())
        else ScreenModel.build(toRaw(root, 0), pkg, label, w, h)
    }

    override suspend fun screenshot(): ByteArray? {
        overlay?.let { withContext(Dispatchers.Main) { it.hide() }; delay(300) }
        val bmp: Bitmap? = try {
            suspendCancellableCoroutine { c -> svc.captureScreen { if (c.isActive) c.resume(it) } }
        } finally { overlay?.let { withContext(Dispatchers.Main) { it.show() } } }
        return bmp?.let { b ->
            withContext(Dispatchers.Default) {
                val scale = minOf(1f, 1280f / maxOf(b.width, b.height))
                val s = if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
                ByteArrayOutputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 72, it); it.toByteArray() }
            }
        }
    }

    private fun node(e: UiElement): AccessibilityNodeInfo? = (e.ref as? AccessibilityNodeInfo)?.also { runCatching { it.refresh() } }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = n; var i = 0
        while (cur != null && i < 8) { if (cur.isClickable) return cur; cur = cur.parent; i++ }
        return null
    }

    override suspend fun tap(e: UiElement): Boolean {
        val n = node(e)
        clickableAncestor(n)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        return tapAt(e.bounds.cx, e.bounds.cy)
    }

    override suspend fun longPress(e: UiElement): Boolean {
        val n = node(e)
        var cur = n; var i = 0
        while (cur != null && i < 8) { if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true; cur = cur.parent; i++ }
        return longPressAt(e.bounds.cx, e.bounds.cy)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean {
        if (!canGesture(svc)) return false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { c ->
                val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                    override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
                }, null)
                if (!ok && c.isActive) c.resume(false)
            }
        }
    }

    override suspend fun tapAt(x: Int, y: Int) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60)
    override suspend fun longPressAt(x: Int, y: Int) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 800)

    override suspend fun setText(e: UiElement, text: String, submit: Boolean): Boolean {
        val n = node(e) ?: return false
        if (n.isPassword) return false
        if (!n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (submit) {
            delay(250)
            if (Build.VERSION.SDK_INT < 30) return false
            return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true
    }

    override suspend fun scroll(target: UiElement?, dir: Dir, screen: ScreenSnapshot): Boolean {
        val n = target?.let { node(it) }
        if (n != null) {
            val directional = when (dir) {
                Dir.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                Dir.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                Dir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                Dir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            }
            if (n.actionList.any { it.id == directional.id } && n.performAction(directional.id)) return true
            val generic = if (dir == Dir.DOWN || dir == Dir.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (n.performAction(generic)) return true
        }
        // swipe fallback (inside the list, or the middle of the screen) — content moves opposite to the finger
        val b = target?.bounds?.takeIf { !it.isEmpty() } ?: Bounds(0, screen.height / 5, screen.width, screen.height * 4 / 5)
        val p = Path()
        when (dir) {
            Dir.DOWN -> { p.moveTo(b.cx.toFloat(), b.t + b.h * 0.75f); p.lineTo(b.cx.toFloat(), b.t + b.h * 0.25f) }
            Dir.UP -> { p.moveTo(b.cx.toFloat(), b.t + b.h * 0.25f); p.lineTo(b.cx.toFloat(), b.t + b.h * 0.75f) }
            Dir.RIGHT -> { p.moveTo(b.l + b.w * 0.85f, b.cy.toFloat()); p.lineTo(b.l + b.w * 0.15f, b.cy.toFloat()) }
            Dir.LEFT -> { p.moveTo(b.l + b.w * 0.15f, b.cy.toFloat()); p.lineTo(b.l + b.w * 0.85f, b.cy.toFloat()) }
        }
        return gesture(p, 350)
    }

    override suspend fun back() = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    override suspend fun home() = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    override fun resolveApp(name: String): AppTarget? {
        val pm = svc.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppTarget(it.activityInfo.packageName, it.loadLabel(pm).toString()) }.distinctBy { it.pkg }
        return AppMatch.best(name, apps, ALIASES)
    }

    override suspend fun launch(t: AppTarget): Boolean = withContext(Dispatchers.Main) {
        val i = svc.packageManager.getLaunchIntentForPackage(t.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return@withContext false
        runCatching { svc.startActivity(i); true }.getOrDefault(false)
    }
}

/** Launcher-label matching (pure; unit-tested). */
object AppMatch {
    fun best(query: String, apps: List<AppTarget>, aliases: Map<String, String> = emptyMap()): AppTarget? {
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return null
        val q = aliases[raw] ?: aliases.entries.firstOrNull { raw.contains(it.key) }?.value ?: raw
        apps.firstOrNull { it.pkg.equals(query.trim(), true) }?.let { return it }
        return apps.firstOrNull { it.label.equals(q, true) }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }
            ?: apps.firstOrNull { it.pkg.lowercase().split('.').any { seg -> seg == q.replace(" ", "") } }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q.replace(" ", "")) }
    }
}
