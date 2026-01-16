package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.OutgoingOperationDao
import ua.com.programmer.pick.data.local.database.entity.OutgoingOperationEntity
import ua.com.programmer.pick.domain.repository.EntityType
import ua.com.programmer.pick.domain.repository.OperationStatus
import ua.com.programmer.pick.domain.repository.OperationType
import ua.com.programmer.pick.domain.repository.OutgoingOperation
import ua.com.programmer.pick.domain.repository.OutgoingOperationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OutgoingOperationRepositoryImpl @Inject constructor(
    private val outgoingOperationDao: OutgoingOperationDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : OutgoingOperationRepository {

    override fun observePendingOperations(): Flow<List<OutgoingOperation>> {
        return outgoingOperationDao.observePendingOperations().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getPendingOperationCount(): Flow<Int> {
        return outgoingOperationDao.getPendingOperationCount()
    }

    override suspend fun getNextPendingOperation(): OutgoingOperation? = withContext(ioDispatcher) {
        outgoingOperationDao.getNextPendingOperation()?.toDomain()
    }

    override suspend fun getAllPendingOperations(): List<OutgoingOperation> = withContext(ioDispatcher) {
        outgoingOperationDao.getAllPendingOperations().map { it.toDomain() }
    }

    override suspend fun getOperationById(id: String): OutgoingOperation? = withContext(ioDispatcher) {
        outgoingOperationDao.getOperationById(id)?.toDomain()
    }

    override suspend fun queueOperation(
        operationType: OperationType,
        entityType: EntityType,
        entityId: String,
        payload: String
    ): Result<OutgoingOperation> = withContext(ioDispatcher) {
        try {
            val operation = OutgoingOperationEntity(
                id = UUID.randomUUID().toString(),
                operationType = operationType.name,
                entityType = entityType.name,
                entityId = entityId,
                payload = payload,
                createdAt = System.currentTimeMillis(),
                retryCount = 0,
                lastError = null,
                status = OperationStatus.PENDING.name
            )
            outgoingOperationDao.insertOperation(operation)
            Result.Success(operation.toDomain())
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to queue operation")
        }
    }

    override suspend fun markOperationCompleted(operationId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            outgoingOperationDao.updateOperationStatus(operationId, OperationStatus.COMPLETED.name)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to mark operation as completed")
        }
    }

    override suspend fun markOperationFailed(operationId: String, error: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val operation = outgoingOperationDao.getOperationById(operationId)
            if (operation != null) {
                val newStatus = if (operation.retryCount >= MAX_RETRIES) {
                    OperationStatus.FAILED.name
                } else {
                    OperationStatus.RETRYING.name
                }
                outgoingOperationDao.incrementRetryCount(operationId, error, newStatus)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to mark operation as failed")
        }
    }

    override suspend fun retryOperation(operationId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            outgoingOperationDao.updateOperationStatus(operationId, OperationStatus.PENDING.name)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to retry operation")
        }
    }

    override suspend fun deleteCompletedOperations() = withContext(ioDispatcher) {
        outgoingOperationDao.deleteCompletedOperations()
    }

    override suspend fun deleteFailedOperations(maxRetries: Int) = withContext(ioDispatcher) {
        outgoingOperationDao.deleteFailedOperations(maxRetries)
    }

    override suspend fun deleteAllOperations() = withContext(ioDispatcher) {
        outgoingOperationDao.deleteAllOperations()
    }

    private fun OutgoingOperationEntity.toDomain(): OutgoingOperation {
        return OutgoingOperation(
            id = id,
            operationType = try {
                OperationType.valueOf(operationType)
            } catch (e: IllegalArgumentException) {
                OperationType.UPDATE_DOCUMENT
            },
            entityType = try {
                EntityType.valueOf(entityType)
            } catch (e: IllegalArgumentException) {
                EntityType.DOCUMENT
            },
            entityId = entityId,
            payload = payload,
            createdAt = createdAt,
            retryCount = retryCount,
            lastError = lastError,
            status = try {
                OperationStatus.valueOf(status)
            } catch (e: IllegalArgumentException) {
                OperationStatus.PENDING
            }
        )
    }

    companion object {
        private const val MAX_RETRIES = 5
    }
}
