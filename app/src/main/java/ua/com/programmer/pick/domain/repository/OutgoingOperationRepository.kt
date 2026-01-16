package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result

interface OutgoingOperationRepository {

    fun observePendingOperations(): Flow<List<OutgoingOperation>>

    fun getPendingOperationCount(): Flow<Int>

    suspend fun getNextPendingOperation(): OutgoingOperation?

    suspend fun getAllPendingOperations(): List<OutgoingOperation>

    suspend fun getOperationById(id: String): OutgoingOperation?

    suspend fun queueOperation(
        operationType: OperationType,
        entityType: EntityType,
        entityId: String,
        payload: String
    ): Result<OutgoingOperation>

    suspend fun markOperationCompleted(operationId: String): Result<Unit>

    suspend fun markOperationFailed(operationId: String, error: String): Result<Unit>

    suspend fun retryOperation(operationId: String): Result<Unit>

    suspend fun deleteCompletedOperations()

    suspend fun deleteFailedOperations(maxRetries: Int)

    suspend fun deleteAllOperations()
}

data class OutgoingOperation(
    val id: String,
    val operationType: OperationType,
    val entityType: EntityType,
    val entityId: String,
    val payload: String,
    val createdAt: Long,
    val retryCount: Int,
    val lastError: String?,
    val status: OperationStatus
)

enum class OperationType {
    TAKE_INTO_WORK,
    UPDATE_DOCUMENT,
    COMPLETE_DOCUMENT,
    UPDATE_LINE
}

enum class EntityType {
    DOCUMENT,
    DOCUMENT_LINE
}

enum class OperationStatus {
    PENDING,
    RETRYING,
    COMPLETED,
    FAILED
}
