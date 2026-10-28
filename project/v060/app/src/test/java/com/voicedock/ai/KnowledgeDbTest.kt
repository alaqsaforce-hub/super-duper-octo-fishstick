package com.voicedock.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.ai.AiCandidate
import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.AiRouter
import com.voicedock.ai.ai.ChatClient
import com.voicedock.ai.ai.ChatMessage
import com.voicedock.ai.data.database.AppDatabase
import com.voicedock.ai.data.database.KbSource
import com.voicedock.ai.knowledge.AddResult
import com.voicedock.ai.knowledge.KnowledgeRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgeDbTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = KnowledgeRepo(db.knowledge()) { 1_000L }
    @After fun close() = db.close()

    private val doc = "এসকর্ট ডিউটির মাসিক বেতন ১৭০০০ টাকা। হাজিরা বোনাস একদিন অনুপস্থিত হলেই বাতিল হয়। Client rate is 450 taka per day."

    @Test fun addSearchBengaliAndEnglish_dedupe_delete() = runBlocking {
        val added = repo.addText("নিয়ম", doc, KbSource.NOTE) as AddResult.Added
        repo.addText("Other", "Vessel MV Alpha arrives tomorrow at the outer anchorage.", KbSource.NOTE)
        assertTrue(repo.addText("again", doc, KbSource.NOTE) is AddResult.Duplicate)
        assertTrue(repo.addText("", "   ", KbSource.NOTE) is AddResult.Empty)

        val bn = repo.search("হাজিরা বোনাস কখন বাতিল?")
        assertEquals(added.doc.id, bn.first().hit.docId)
        val prefix = repo.search("বেত") // prefix of বেতন
        assertEquals(added.doc.id, prefix.first().hit.docId)
        val en = repo.search("client RATE")
        assertEquals(added.doc.id, en.first().hit.docId)
        assertEquals("Other", repo.search("vessel anchorage").first().hit.title)

        db.knowledge().deleteDocument(added.doc.id)
        assertTrue(repo.search("হাজিরা").isEmpty())
        assertEquals(1, db.knowledge().count())
    }

    @Test fun longDocument_chunked_andFullTextRestored() = runBlocking {
        val long = (1..80).joinToString(" ") { "লাইন $it: জাহাজ নম্বর $it আজ ঘাটে এসেছে।" }
        val r = repo.addText("লগ", long, KbSource.FILE) as AddResult.Added
        assertTrue(r.chunks > 2)
        assertEquals(long, repo.fullText(r.doc.id))
        assertTrue(repo.search("জাহাজ").isNotEmpty())
    }

    private class Fake : ChatClient {
        var last: String? = null
        override fun listModels() = emptyList<String>()
        override fun complete(model: String, system: String, messages: List<ChatMessage>): String { last = messages.last().text; return "১৭০০০ টাকা [1]" }
    }

    @Test fun rag_usesOnlyMatchingSources_andSkipsAiWithoutHits() = runBlocking {
        repo.addText("নিয়ম", doc, KbSource.NOTE)
        val fake = Fake()
        val router = AiRouter { listOf(AiCandidate(AiProvider.GEMINI, "m", fake)) }
        val a = repo.ask(router, "এসকর্টের বেতন কত?", true)
        assertEquals("১৭০০০ টাকা [1]", a.text)
        assertTrue(fake.last!!.contains("untrusted=\"true\"")); assertTrue(fake.last!!.contains("১৭০০০"))
        fake.last = null
        val none = repo.ask(router, "পাসপোর্ট নবায়ন", true)
        assertTrue(none.sources.isEmpty()); assertEquals(null, fake.last)
    }

    @Test fun migration1to2_keepsDrafts_andCreatesKnowledgeTables() = runBlocking {
        val name = "mig-test.db"
        ctx.deleteDatabase(name)
        val f = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            d.execSQL("CREATE TABLE IF NOT EXISTS `dictation_drafts` (`id` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `rawText` TEXT NOT NULL, `formattedText` TEXT NOT NULL, `segmentsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `language` TEXT NOT NULL, `engine` TEXT NOT NULL, `destination` TEXT NOT NULL, PRIMARY KEY(`id`))")
            d.execSQL("CREATE INDEX IF NOT EXISTS `index_dictation_drafts_status` ON `dictation_drafts` (`status`)")
            d.execSQL("CREATE INDEX IF NOT EXISTS `index_dictation_drafts_updatedAt` ON `dictation_drafts` (`updatedAt`)")
            d.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            d.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'db077a37ed93c280fa80afe32060a907')")
            d.execSQL("INSERT INTO dictation_drafts VALUES('old1', 1, 2, 'পুরনো লেখা', 'পুরনো লেখা', '[]', 'SAVED', 'bn-BD', 'x', '')")
            d.version = 1
        }
        val migrated = AppDatabase.build(ctx, name)
        try {
            assertNotNull(migrated.drafts().get("old1"))
            assertEquals("পুরনো লেখা", migrated.drafts().get("old1")!!.rawText)
            val r = KnowledgeRepo(migrated.knowledge())
            assertTrue(r.addText("t", "মাইগ্রেশনের পরে নলেজ কাজ করে", KbSource.NOTE) is AddResult.Added)
            assertTrue(r.search("মাইগ্রেশনের").isNotEmpty())
        } finally { migrated.close(); ctx.deleteDatabase(name) }
    }
}
