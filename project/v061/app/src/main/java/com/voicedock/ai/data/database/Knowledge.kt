package com.voicedock.ai.data.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Where a knowledge document came from. Imported content is always treated as untrusted by AI prompts. */
enum class KbSource { NOTE, SHARE, PROCESS_TEXT, FILE, DRIVE, GMAIL, GITHUB, DRAFT }

@Entity(tableName = "kb_documents", indices = [Index("updatedAt"), Index("contentHash")])
data class KbDocument(
    @PrimaryKey val id: String,
    val title: String,
    val source: KbSource,
    /** e.g. file name, "owner/repo@main:README.md", Gmail message id. */
    val sourceRef: String,
    val mime: String,
    val createdAt: Long,
    val updatedAt: Long,
    val charCount: Int,
    /** SHA-256 of the normalised text — the same content is not stored twice. */
    val contentHash: String,
)

/** ~900-character pieces of a document; rowid is shared with the FTS index. */
@Entity(tableName = "kb_chunks", indices = [Index("docId")])
data class KbChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: String,
    val ord: Int,
    val text: String,
)

/**
 * Full-text index with its own copy of each chunk, pre-normalised by [indexText] (lower-case, ZWJ/ZWNJ removed,
 * punctuation → spaces) and the `simple` tokenizer, which keeps every non-ASCII character inside words. This makes
 * Bengali words (vowel signs, hasanta) tokenise identically on every SQLite version. rowid = [KbChunk.id].
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_SIMPLE)
@Entity(tableName = "kb_chunks_fts")
data class KbChunkFts(@PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long, val text: String)

private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
private val ZW = Regex("[\\u200C\\u200D]")
/** Same normalisation for indexing and querying. */
fun indexText(s: String): String = s.lowercase().replace(ZW, "").replace(NON_WORD, " ").trim()

data class ChunkHit(val id: Long, val docId: String, val ord: Int, val text: String, val title: String)

@Dao
interface KnowledgeDao {
    @Insert suspend fun insertDoc(d: KbDocument)
    @Insert suspend fun insertChunk(c: KbChunk): Long
    @Insert suspend fun insertFts(f: KbChunkFts)

    @Transaction
    suspend fun addDocument(d: KbDocument, chunks: List<KbChunk>) {
        insertDoc(d)
        for (c in chunks) { val id = insertChunk(c); insertFts(KbChunkFts(id, indexText(c.text))) }
    }

    @Query("SELECT * FROM kb_documents ORDER BY updatedAt DESC")
    fun observeDocuments(): Flow<List<KbDocument>>

    @Query("SELECT * FROM kb_documents WHERE contentHash = :hash LIMIT 1")
    suspend fun byHash(hash: String): KbDocument?

    @Query("SELECT * FROM kb_documents WHERE id = :id")
    suspend fun document(id: String): KbDocument?

    @Query("SELECT text FROM kb_chunks WHERE docId = :id ORDER BY ord")
    suspend fun chunksOf(id: String): List<String>

    @Query("DELETE FROM kb_chunks_fts WHERE rowid IN (SELECT id FROM kb_chunks WHERE docId = :id)") suspend fun deleteIndex(id: String)
    @Query("DELETE FROM kb_chunks WHERE docId = :id") suspend fun deleteChunks(id: String)
    @Query("DELETE FROM kb_documents WHERE id = :id") suspend fun deleteDocumentRow(id: String)

    /** Explicit user action only (two-tap confirm in the UI). */
    @Transaction
    suspend fun deleteDocument(id: String) { deleteIndex(id); deleteChunks(id); deleteDocumentRow(id) }

    @Query("SELECT COUNT(*) FROM kb_documents") suspend fun count(): Int

    @Query(
        "SELECT c.id AS id, c.docId AS docId, c.ord AS ord, c.text AS text, d.title AS title " +
            "FROM kb_chunks_fts JOIN kb_chunks c ON c.id = kb_chunks_fts.rowid JOIN kb_documents d ON d.id = c.docId " +
            "WHERE kb_chunks_fts MATCH :query LIMIT :limit"
    )
    suspend fun match(query: String, limit: Int): List<ChunkHit>
}
