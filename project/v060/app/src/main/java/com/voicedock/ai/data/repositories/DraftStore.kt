package com.voicedock.ai.data.repositories

import com.voicedock.ai.data.database.DictationDraft
import com.voicedock.ai.data.database.DraftDao
import kotlinx.coroutines.flow.Flow

/** Persistence boundary for dictation drafts (Room in the app, in-memory in tests). */
interface DraftStore {
    suspend fun save(draft: DictationDraft)
    suspend fun get(id: String): DictationDraft?
    suspend fun latestUnfinished(): DictationDraft?
    fun observeRecent(limit: Int = 100): Flow<List<DictationDraft>>
    suspend fun delete(id: String)
}

class RoomDraftStore(private val dao: DraftDao) : DraftStore {
    override suspend fun save(draft: DictationDraft) = dao.upsert(draft)
    override suspend fun get(id: String) = dao.get(id)
    override suspend fun latestUnfinished() = dao.latestUnfinished()
    override fun observeRecent(limit: Int) = dao.observeRecent(limit)
    override suspend fun delete(id: String) = dao.delete(id)
}
