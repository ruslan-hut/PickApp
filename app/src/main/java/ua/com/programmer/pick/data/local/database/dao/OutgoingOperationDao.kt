package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.OutgoingOperationEntity

@Dao
interface OutgoingOperationDao {

    @Query("SELECT * FROM outgoing_operations WHERE id = :id")
    suspend fun getOperationById(id: String): OutgoingOperationEntity?

    @Query("SELECT * FROM outgoing_operations WHERE status = :status ORDER BY created_at ASC")
    suspend fun getOperationsByStatus(status: String): List<OutgoingOperationEntity>

    @Query("SELECT * FROM outgoing_operations WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 1")
    suspend fun getNextPendingOperation(): OutgoingOperationEntity?

    @Query("SELECT * FROM outgoing_operations WHERE status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getAllPendingOperations(): List<OutgoingOperationEntity>

    @Query("SELECT * FROM outgoing_operations WHERE status IN ('PENDING', 'RETRYING') ORDER BY created_at ASC")
    suspend fun getAllRetryableOperations(): List<OutgoingOperationEntity>

    @Query("UPDATE outgoing_operations SET status = 'PENDING' WHERE status = 'PROCESSING'")
    suspend fun resetStaleProcessingOperations()

    @Query("SELECT * FROM outgoing_operations WHERE status IN ('PENDING', 'RETRYING') ORDER BY created_at ASC")
    fun observePendingOperations(): Flow<List<OutgoingOperationEntity>>

    @Query("SELECT COUNT(*) FROM outgoing_operations WHERE status IN ('PENDING', 'RETRYING')")
    fun getPendingOperationCount(): Flow<Int>

    @Query("SELECT * FROM outgoing_operations WHERE entity_type = :entityType AND entity_id = :entityId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestOperationForEntity(entityType: String, entityId: String): OutgoingOperationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperation(operation: OutgoingOperationEntity)

    @Update
    suspend fun updateOperation(operation: OutgoingOperationEntity)

    @Query("UPDATE outgoing_operations SET status = :status WHERE id = :operationId")
    suspend fun updateOperationStatus(operationId: String, status: String)

    @Query("UPDATE outgoing_operations SET retry_count = retry_count + 1, last_error = :error, status = :status WHERE id = :operationId")
    suspend fun incrementRetryCount(operationId: String, error: String?, status: String)

    @Query("DELETE FROM outgoing_operations WHERE id = :id")
    suspend fun deleteOperation(id: String)

    @Query("DELETE FROM outgoing_operations WHERE status = 'COMPLETED'")
    suspend fun deleteCompletedOperations()

    @Query("DELETE FROM outgoing_operations WHERE status = 'FAILED' AND retry_count >= :maxRetries")
    suspend fun deleteFailedOperations(maxRetries: Int)

    @Query("DELETE FROM outgoing_operations")
    suspend fun deleteAllOperations()
}
