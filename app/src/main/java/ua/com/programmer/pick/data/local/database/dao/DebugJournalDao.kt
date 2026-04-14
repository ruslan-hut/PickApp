package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.DebugJournalEntity

@Dao
interface DebugJournalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DebugJournalEntity)

    @Query("SELECT * FROM debug_journal_events WHERE uploaded = 0 ORDER BY created_at ASC LIMIT :limit")
    suspend fun getUnuploadedBatch(limit: Int): List<DebugJournalEntity>

    @Query("UPDATE debug_journal_events SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("UPDATE debug_journal_events SET upload_attempts = upload_attempts + 1 WHERE id IN (:ids)")
    suspend fun incrementAttempts(ids: List<String>)

    @Query("SELECT * FROM debug_journal_events WHERE tenant_id = :tenantId ORDER BY created_at DESC")
    fun observeByTenant(tenantId: String): Flow<List<DebugJournalEntity>>

    @Query("SELECT * FROM debug_journal_events WHERE tenant_id = :tenantId AND document_id = :documentId ORDER BY created_at DESC")
    fun observeByDocument(tenantId: String, documentId: String): Flow<List<DebugJournalEntity>>

    @Query("DELETE FROM debug_journal_events WHERE created_at < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)

    @Query("DELETE FROM debug_journal_events WHERE id NOT IN (SELECT id FROM debug_journal_events ORDER BY created_at DESC LIMIT :cap)")
    suspend fun pruneOverCap(cap: Int)

    @Query("DELETE FROM debug_journal_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM debug_journal_events")
    suspend fun count(): Int
}
