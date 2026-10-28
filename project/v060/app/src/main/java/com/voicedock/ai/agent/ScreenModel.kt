package com.voicedock.ai.agent

/** Screen rectangle in pixels. */
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val w get() = r - l
    val h get() = b - t
    fun isEmpty() = w <= 0 || h <= 0
    fun contains(x: Int, y: Int) = x in l until r && y in t until b
}

/**
 * Platform-free copy of one accessibility node. Built from AccessibilityNodeInfo by [A11yDevice] (with [ref] = the
 * real node) or by hand in unit tests.
 */
data class RawNode(
    val text: String? = null,
    val desc: String? = null,
    val cls: String? = null,
    val viewId: String? = null,
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val focused: Boolean = false,
    val children: List<RawNode> = emptyList(),
    val ref: Any? = null,
)

enum class ElemKind(val tag: String) { BUTTON("button"), FIELD("field"), LIST("list"), TOGGLE("toggle"), TEXT("text"), IMAGE("image") }

/** One numbered element the model can refer to by [id]. */
data class UiElement(
    val id: Int,
    val kind: ElemKind,
    val label: String,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val password: Boolean = false,
    val focused: Boolean = false,
    val viewId: String? = null,
    val ref: Any? = null,
)

data class ScreenSnapshot(
    val pkg: String,
    val appLabel: String,
    val width: Int,
    val height: Int,
    val elements: List<UiElement>,
    val truncated: Boolean = false,
) {
    fun byId(id: Int?) = id?.let { i -> elements.firstOrNull { it.id == i } }

    /** Changes when the visible content changes (used to detect "scroll did nothing" and to wait for the UI to settle). */
    val signature: Int get() = (pkg + "#" + elements.joinToString("|") { it.kind.tag + ":" + it.label + "@" + it.bounds.t / 8 }).hashCode()

    fun largestScrollable() = elements.filter { it.scrollable }.maxByOrNull { it.bounds.w.toLong() * it.bounds.h }

    /** Innermost element whose bounds contain the pixel (for coordinate taps). */
    fun elementAt(x: Int, y: Int) = elements.filter { it.bounds.contains(x, y) && it.kind != ElemKind.LIST }
        .minByOrNull { it.bounds.w.toLong() * it.bounds.h }

    /** Text form sent to the model. Labels are untrusted screen content; the caller wraps this in an untrusted block. */
    fun render(maxChars: Int = 7000): String = buildString {
        append("App: ").append(appLabel.ifBlank { pkg }).append(" (").append(pkg).append(") · screen ").append(width).append('x').append(height).append('\n')
        if (elements.isEmpty()) append("(no readable elements — use look to get a screenshot)\n")
        for (e in elements) {
            val line = buildString {
                append('[').append(e.id).append("] ").append(e.kind.tag)
                if (e.label.isNotBlank()) append(" \"").append(e.label).append('"') else {
                    append(" (no label")
                    e.viewId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { append(", id=").append(it) }
                    append(")")
                }
                if (e.checked != null) append(if (e.checked) " = on" else " = off")
                val flags = buildList {
                    if (e.scrollable) add("scrollable")
                    if (e.editable) add("editable")
                    if (e.focused) add("focused")
                    if (e.password) add("password")
                    if (e.longClickable && !e.clickable) add("long-press")
                }
                if (flags.isNotEmpty()) append(" (").append(flags.joinToString(", ")).append(')')
                // position helps with icon-only buttons and layout questions (normalised 0–999 like screenshot coordinates)
                if (e.label.isBlank() || e.kind == ElemKind.IMAGE) append(" @").append(norm(e.bounds.cx, width)).append(',').append(norm(e.bounds.cy, height))
            }
            if (length + line.length + 1 > maxChars) { append("… (more elements not shown — scroll or look)\n"); break }
            append(line).append('\n')
        }
        if (truncated) append("… (screen has more elements than listed)\n")
    }

    companion object {
        fun norm(v: Int, size: Int) = if (size <= 0) 0 else (v.toLong() * 1000 / size).toInt().coerceIn(0, 999)
    }
}

/** Builds the numbered element list from a node tree (pure; unit-tested). */
object ScreenModel {
    private const val MAX_LABEL = 220

    fun clean(s: String?): String = s.orEmpty().replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("\\s*\\n\\s*"), " ⏎ ").replace(Regex("\\s+"), " ").trim()

    private fun own(n: RawNode): String = listOf(clean(n.text), clean(n.desc)).filter { it.isNotEmpty() }.distinct().joinToString(" / ")

    private fun descendantText(n: RawNode, out: MutableList<String>, budget: IntArray) {
        for (c in n.children) {
            if (budget[0] <= 0 || !c.visible || c.password) continue
            val t = own(c)
            if (t.isNotEmpty() && out.lastOrNull() != t) { out += t; budget[0] -= t.length }
            descendantText(c, out, budget)
        }
    }

    fun build(root: RawNode?, pkg: String, appLabel: String, width: Int, height: Int, maxElements: Int = 160): ScreenSnapshot {
        val out = mutableListOf<UiElement>()
        var truncated = false
        fun add(n: RawNode, kind: ElemKind, label: String) {
            if (out.size >= maxElements) { truncated = true; return }
            out += UiElement(
                id = out.size + 1, kind = kind, label = label.take(MAX_LABEL), bounds = n.bounds,
                clickable = n.clickable, longClickable = n.longClickable, editable = n.editable, scrollable = n.scrollable,
                checked = if (n.checkable) n.checked else null, password = n.password, focused = n.focused,
                viewId = n.viewId, ref = n.ref,
            )
        }

        /** [textInsideRow] = true while inside a clickable row whose text was already aggregated into the row's label. */
        fun walk(n: RawNode, depth: Int, textInsideRow: Boolean) {
            if (depth > 70 || !n.visible) return
            if (out.size >= maxElements) { truncated = true; return }
            val label = own(n)
            var childrenInsideRow = textInsideRow
            if (n.scrollable) add(n, ElemKind.LIST, label)
            when {
                n.password -> { if (n.editable) add(n, ElemKind.FIELD, "(password field — never filled by the agent)"); return }
                n.editable -> { add(n, ElemKind.FIELD, label); return }
                n.checkable -> {
                    val l = label.ifEmpty { mutableListOf<String>().also { descendantText(n, it, intArrayOf(MAX_LABEL)) }.joinToString(" · ") }
                    add(n, ElemKind.TOGGLE, l); childrenInsideRow = true
                }
                (n.clickable || n.longClickable) && !n.scrollable -> {
                    val l = if (label.isNotEmpty()) label
                        else mutableListOf<String>().also { descendantText(n, it, intArrayOf(MAX_LABEL)) }.joinToString(" · ")
                    val isImage = n.cls?.contains("Image", true) == true && clean(n.text).isEmpty()
                    add(n, if (isImage && l.isEmpty()) ElemKind.IMAGE else ElemKind.BUTTON, l)
                    childrenInsideRow = true
                }
                label.isNotEmpty() && !textInsideRow && !n.scrollable -> {
                    val isImage = n.cls?.contains("Image", true) == true && clean(n.text).isEmpty()
                    add(n, if (isImage) ElemKind.IMAGE else ElemKind.TEXT, label)
                }
            }
            for (c in n.children) {
                // inside an aggregated row only interactive children get their own entry
                if (childrenInsideRow && !(c.clickable || c.longClickable || c.editable || c.checkable || c.scrollable) && !hasInteractive(c)) continue
                walk(c, depth + 1, childrenInsideRow)
            }
        }
        root?.let { walk(it, 0, false) }
        return ScreenSnapshot(pkg, appLabel, width, height, out, truncated)
    }

    private fun hasInteractive(n: RawNode): Boolean = n.children.any { it.visible && (it.clickable || it.longClickable || it.editable || it.checkable || it.scrollable || hasInteractive(it)) }
}
