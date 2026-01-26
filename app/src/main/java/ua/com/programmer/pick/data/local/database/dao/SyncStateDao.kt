package ua.com.programmer.pick.data.local.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.SyncStateEntity

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType")
    suspend fun getSyncState(entityType: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE entity_type = :entityType")
    fun observeSyncState(entityType: String): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state")
    fun observeAllSyncStates(): Flow<List<SyncStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("UPDATE sync_state SET last_sync_time = :timestamp, status = :status, error_message = NULL WHERE entity_type = :entityType")
    suspend fun updateSyncSuccess(entityType: String, timestamp: Long, status: String = "SUCCESS")

    @Query("UPDATE sync_state SET status = :status, error_message = :errorMessage WHERE entity_type = :entityType")
    suspend fun updateSyncError(entityType: String, status: String = "ERROR", errorMessage: String?)

    @Query("UPDATE sync_state SET status = :status WHERE entity_type = :entityType")
    suspend fun updateSyncStatus(entityType: String, status: String)

    // ============================================
    // Cursor Operations
    // ============================================

    /**
     * Get cursor for a specific entity type
     */
    @Query("SELECT cursor FROM sync_state WHERE entity_type = :entityType")
    suspend fun getCursor(entityType: String): String?

    /**
     * Get all cursors as a map
     */
    @Query("SELECT entity_type, cursor FROM sync_state WHERE cursor IS NOT NULL")
    suspend fun getAllCursors(): List<CursorEntry>

    /**
     * Update cursor for a specific entity type
     */
    @Query("UPDATE sync_state SET cursor = :cursor, last_sync_time = :timestamp, status = 'SUCCESS', error_message = NULL WHERE entity_type = :entityType")
    suspend fun updateCursor(entityType: String, cursor: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Initialize sync state for an entity type if it doesn't exist
     */
    @Transaction
    suspend fun initializeIfNeeded(entityType: String) {
        val existing = getSyncState(entityType)
        if (existing == null) {
            upsertSyncState(SyncStateEntity(entityType = entityType))
        }
    }

    /**
     * Update multiple cursors in a single transaction
     */
    @Transaction
    suspend fun updateCursors(cursors: Map<String, String>) {
        val timestamp = System.currentTimeMillis()
        cursors.forEach { (entityType, cursor) ->
            // Ensure entity type exists
            initializeIfNeeded(entityType)
            updateCursor(entityType, cursor, timestamp)
        }
    }

    /**
     * Get cursors as a map for building sync requests
     */
    suspend fun getCursorsMap(): Map<String, String> {
        return getAllCursors()
            .filter { it.cursor != null }
            .associate { it.entityType to it.cursor!! }
    }

    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}

/**
 * Helper class for cursor queries
 */
data class CursorEntry(
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "cursor")
    val cursor: String?
)
