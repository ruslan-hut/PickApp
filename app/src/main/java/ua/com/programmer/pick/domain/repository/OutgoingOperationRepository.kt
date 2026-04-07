package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result

interface OutgoingOperationRepository {

    fun observePendingOperations(): Flow<List<OutgoingOperation>>

    fun getPendingOperationCount(): Flow<Int>

    suspend fun getNextPendingOperation(): OutgoingOperation?

    suspend fun getAllPendingOperations(): List<OutgoingOperation>

    suspend fun getAllRetryableOperations(): List<OutgoingOperation>

    suspend fun resetStaleProcessingOperations()

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

    suspend fun markOperationProcessing(operationId: String): Result<Unit>

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
    STAGE_LOCK,
    STAGE_UNLOCK,
    STAGE_COMPLETE,
    DOCUMENT_UPDATE,
    BOX_SCAN,
    BOX_PICKUP_CONFIRM,
    BOX_DELIVERY_CONFIRM,
    SYNC_REQUEST,
    PRODUCT_LOOKUP
}

enum class EntityType {
    DOCUMENT,
    DOCUMENT_LINE,
    DOCUMENT_BOX
}

enum class OperationStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED
}
