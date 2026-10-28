package com.voicedock.ai.knowledge

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Result of local text extraction. [needsOcr] = no text layer (image, scanned PDF) — Gemini can read it if allowed. */
data class Extracted(val text: String, val needsOcr: Boolean = false, val kind: String = "text")

/**
 * Pure-Kotlin extractors (JVM-testable). PDF is handled in [Importer] (PdfBox needs an Android context).
 * Office files are ZIP+XML: only the visible text runs are read; macros/embedded objects are ignored.
 */
object TextExtract {
    const val MAX_CHARS = 1_500_000

    fun kindOf(mime: String?, name: String?): String {
        val n = name.orEmpty().lowercase(); val m = mime.orEmpty().lowercase()
        return when {
            n.endsWith(".docx") || m.contains("wordprocessingml") -> "docx"
            n.endsWith(".xlsx") || m.contains("spreadsheetml") -> "xlsx"
            n.endsWith(".pptx") || m.contains("presentationml") -> "pptx"
            n.endsWith(".pdf") || m == "application/pdf" -> "pdf"
            m.startsWith("image/") || Regex("\\.(jpe?g|png|webp|heic)$").containsMatchIn(n) -> "image"
            n.endsWith(".html") || n.endsWith(".htm") || m.contains("html") -> "html"
            m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("csv") ||
                Regex("\\.(txt|md|markdown|csv|tsv|json|xml|log|kt|java|py|js|ts|yml|yaml|ini|gs|sh)$").containsMatchIn(n) -> "text"
            else -> "unknown"
        }
    }

    fun extract(bytes: ByteArray, mime: String?, name: String?): Extracted = when (val k = kindOf(mime, name)) {
        "docx" -> Extracted(docx(bytes), kind = k)
        "xlsx" -> Extracted(xlsx(bytes), kind = k)
        "pptx" -> Extracted(pptx(bytes), kind = k)
        "html" -> Extracted(html(decode(bytes)), kind = k)
        "text" -> Extracted(decode(bytes), kind = k)
        "image" -> Extracted("", needsOcr = true, kind = k)
        "pdf" -> Extracted("", needsOcr = false, kind = k) // handled by Importer
        else -> {
            val t = decode(bytes)
            // Binary files decode to lots of replacement/control characters — refuse instead of storing garbage.
            val bad = t.count { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }
            if (t.isNotEmpty() && bad < t.length / 50) Extracted(t, kind = "text") else Extracted("", kind = k)
        }
    }

    fun decode(b: ByteArray): String {
        var s = String(b, Charsets.UTF_8)
        if (s.startsWith('\uFEFF')) s = s.substring(1)
        return s.take(MAX_CHARS)
    }

    private fun zipEntries(bytes: ByteArray, accept: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && accept(e.name)) out[e.name] = String(z.readBytes(), Charsets.UTF_8)
            }
        }
        return out
    }

    fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m -> runCatching { String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16))) }.getOrDefault("") }
        .replace("&amp;", "&")

    private val DOCX_TOKEN = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|</w:p>|<w:tab/>|<w:br/>|<w:cr/>")

    fun docx(bytes: ByteArray): String {
        val xml = zipEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return ""
        val sb = StringBuilder()
        for (m in DOCX_TOKEN.findAll(xml)) {
            when {
                m.value == "</w:p>" -> sb.append('\n')
                m.value == "<w:tab/>" -> sb.append('\t')
                m.value.startsWith("<w:br") || m.value.startsWith("<w:cr") -> sb.append('\n')
                else -> sb.append(unescapeXml(m.groupValues[1]))
            }
            if (sb.length > MAX_CHARS) break
        }
        return sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    fun pptx(bytes: ByteArray): String {
        val slides = zipEntries(bytes) { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .toSortedMap(compareBy { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toInt() ?: 0 })
        return slides.entries.mapIndexed { i, (_, xml) ->
            val paras = xml.split("</a:p>").map { p -> Regex("<a:t>([^<]*)</a:t>").findAll(p).joinToString("") { unescapeXml(it.groupValues[1]) } }.filter { it.isNotBlank() }
            "— Slide ${i + 1} —\n" + paras.joinToString("\n")
        }.joinToString("\n\n").trim()
    }

    fun xlsx(bytes: ByteArray): String {
        val files = zipEntries(bytes) { it == "xl/sharedStrings.xml" || it == "xl/workbook.xml" || Regex("xl/worksheets/sheet\\d+\\.xml").matches(it) }
        val shared = files["xl/sharedStrings.xml"]?.let { x ->
            x.split("</si>").dropLast(1).map { si -> Regex("<t(?:\\s[^>]*)?>([^<]*)</t>").findAll(si).joinToString("") { unescapeXml(it.groupValues[1]) } }
        }.orEmpty()
        val names = files["xl/workbook.xml"]?.let { Regex("<sheet [^>]*name=\"([^\"]*)\"").findAll(it).map { m -> unescapeXml(m.groupValues[1]) }.toList() }.orEmpty()
        val sheets = files.filterKeys { it.startsWith("xl/worksheets/") }
            .toSortedMap(compareBy { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toInt() ?: 0 })
        val cell = Regex("<c ([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        sheets.values.forEachIndexed { i, xml ->
            sb.append("— ").append(names.getOrNull(i) ?: "Sheet ${i + 1}").append(" —\n")
            for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val values = cell.findAll(row.groupValues[1]).map { c ->
                    val attrs = c.groupValues[1]; val inner = c.groupValues[2]
                    val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                    val v = Regex("<v>([^<]*)</v>").find(inner)?.groupValues?.get(1)
                    when (type) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> Regex("<t(?:\\s[^>]*)?>([^<]*)</t>").findAll(inner).joinToString("") { unescapeXml(it.groupValues[1]) }
                        else -> v?.let { unescapeXml(it) }.orEmpty()
                    }
                }.toList()
                if (values.any { it.isNotBlank() }) sb.append(values.joinToString(" | ").trimEnd(' ', '|')).append('\n')
                if (sb.length > MAX_CHARS) break
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    fun html(s: String): String = unescapeXml(
        s.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
    ).replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}
