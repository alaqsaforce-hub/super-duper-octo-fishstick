package com.voicedock.ai.knowledge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.voicedock.ai.ai.AiAnswer
import com.voicedock.ai.ai.AiRouter
import com.voicedock.ai.ai.ChatMessage
import com.voicedock.ai.data.database.KbChunk
import com.voicedock.ai.data.database.KbDocument
import com.voicedock.ai.data.database.KbSource
import com.voicedock.ai.data.database.KnowledgeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface AddResult {
    data class Added(val doc: KbDocument, val chunks: Int) : AddResult
    data class Duplicate(val existing: KbDocument) : AddResult
    data object Empty : AddResult
}

data class KbAnswer(val answer: AiAnswer?, val text: String, val sources: List<ScoredHit>)

/** Knowledge base: add, search (FTS + ranking), ask (RAG through the AI router). */
class KnowledgeRepo(private val dao: KnowledgeDao, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun addText(title: String, text: String, source: KbSource, sourceRef: String = "", mime: String = "text/plain"): AddResult {
        val clean = KbChunker.normalize(text).take(TextExtract.MAX_CHARS)
        if (clean.isBlank()) return AddResult.Empty
        val hash = KbChunker.sha256(clean)
        dao.byHash(hash)?.let { return AddResult.Duplicate(it) }
        val now = clock()
        val doc = KbDocument(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { clean.lineSequence().first().take(60) }.take(200),
            source = source, sourceRef = sourceRef.take(500), mime = mime,
            createdAt = now, updatedAt = now, charCount = clean.length, contentHash = hash,
        )
        val chunks = KbChunker.chunk(clean).mapIndexed { i, c -> KbChunk(docId = doc.id, ord = i, text = c) }
        dao.addDocument(doc, chunks)
        return AddResult.Added(doc, chunks.size)
    }

    suspend fun search(query: String, k: Int = 6): List<ScoredHit> {
        val tokens = KbSearch.tokens(query)
        val q = KbSearch.ftsQuery(tokens) ?: return emptyList()
        val hits = runCatching { dao.match(q, 60) }.getOrElse { emptyList() }
        return KbSearch.rank(tokens, hits, k)
    }

    /** RAG. With no matching sources the AI is not called at all (nothing to ground the answer on). */
    suspend fun ask(router: AiRouter, question: String, bn: Boolean): KbAnswer {
        val hits = search(question, 6)
        if (hits.isEmpty()) return KbAnswer(null, "", emptyList())
        val ans = withContext(Dispatchers.IO) { router.complete(KnowledgePrompt.system(bn), listOf(ChatMessage.user(KnowledgePrompt.user(question, hits)))) }
        return KbAnswer(ans, ans.text.trim(), hits)
    }

    suspend fun fullText(docId: String): String = dao.chunksOf(docId).let { joinChunks(it) }

    companion object {
        /** Re-joins overlapping chunks for display (drops the repeated overlap prefix). */
        fun joinChunks(chunks: List<String>): String {
            if (chunks.isEmpty()) return ""
            val sb = StringBuilder(chunks[0])
            for (c in chunks.drop(1)) {
                var cut = 0
                for (len in minOf(200, c.length) downTo 10) { if (sb.endsWith(c.substring(0, len))) { cut = len; break } }
                sb.append(if (cut > 0) c.substring(cut) else "\n$c")
            }
            return sb.toString()
        }
    }
}

/** Reading files picked by the user (file picker — includes Google Drive, Downloads, etc.) or shared to VoiceDock. */
object Importer {
    const val MAX_BYTES = 15L * 1024 * 1024
    @Volatile private var pdfReady = false

    data class FileData(val name: String, val mime: String, val bytes: ByteArray)

    fun read(ctx: Context, uri: Uri): FileData {
        val cr = ctx.contentResolver
        var name = uri.lastPathSegment ?: "file"; var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
        }
        if (size > MAX_BYTES) throw IllegalArgumentException("too_big")
        val bytes = cr.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream(); val b = ByteArray(64 * 1024); var total = 0L
            while (true) { val n = input.read(b); if (n < 0) break; total += n; if (total > MAX_BYTES) throw IllegalArgumentException("too_big"); buf.write(b, 0, n) }
            buf.toByteArray()
        } ?: throw IllegalArgumentException("unreadable")
        return FileData(name, cr.getType(uri) ?: "application/octet-stream", bytes)
    }

    /** Local extraction only; [Extracted.needsOcr] tells the caller to offer Gemini OCR. */
    fun extract(ctx: Context, f: FileData): Extracted {
        val base = TextExtract.extract(f.bytes, f.mime, f.name)
        if (base.kind != "pdf") return base
        if (!pdfReady) { PDFBoxResourceLoader.init(ctx.applicationContext); pdfReady = true }
        val text = runCatching { PDDocument.load(f.bytes).use { d -> PDFTextStripper().getText(d) } }.getOrDefault("")
        return Extracted(text.trim().take(TextExtract.MAX_CHARS), needsOcr = text.isBlank(), kind = "pdf")
    }
}
