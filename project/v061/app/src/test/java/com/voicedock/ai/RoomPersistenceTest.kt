package com.voicedock.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.data.database.AppDatabase
import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomPersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, "persist-test.db").allowMainThreadQueries().build()

    @Test fun draftSurvivesDatabaseReopen() = runBlocking {
        ctx.deleteDatabase("persist-test.db")
        val d = DictationDraft(
            id = "d1", createdAt = 1, updatedAt = 2, rawText = "আমি আজ অফিসে যাব", formattedText = "আমি আজ অফিসে যাব।",
            segmentsJson = """[{"text":"আমি আজ অফিসে যাব"}]""", status = DraftStatus.ACTIVE, language = "bn-BD", engine = "fake",
        )
        open().apply { drafts().upsert(d); close() }

        val db2 = open()
        val back = db2.drafts().latestUnfinished()
        assertEquals(d, back)
        // status changes are updates, not duplicates
        db2.drafts().upsert(d.copy(status = DraftStatus.SAVED, updatedAt = 3))
        assertNull(db2.drafts().latestUnfinished())
        assertEquals(1, db2.drafts().count())
        assertEquals(DraftStatus.SAVED, db2.drafts().observeRecent(10).first().single().status)
        db2.close()
    }
}
