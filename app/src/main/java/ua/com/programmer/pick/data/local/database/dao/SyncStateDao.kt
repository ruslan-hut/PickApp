package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}
