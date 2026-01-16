package ua.com.programmer.pick.data.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.local.database.dao.ProductDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.local.database.entity.SyncStateEntity
import ua.com.programmer.pick.data.mapper.ClientMapper
import ua.com.programmer.pick.data.mapper.DocumentMapper
import ua.com.programmer.pick.data.mapper.ProductMapper
import ua.com.programmer.pick.data.mapper.WarehouseMapper
import ua.com.programmer.pick.data.remote.api.SyncApi
import ua.com.programmer.pick.data.remote.dto.ClientDto
import ua.com.programmer.pick.data.remote.dto.CompleteDocumentRequestDto
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.dto.DocumentLineUpdateDto
import ua.com.programmer.pick.data.remote.dto.ProductDto
import ua.com.programmer.pick.data.remote.dto.SyncAckRequest
import ua.com.programmer.pick.data.remote.dto.TakeDocumentRequestDto
import ua.com.programmer.pick.data.remote.dto.WarehouseDto
import ua.com.programmer.pick.data.remote.websocket.ConnectionState
import ua.com.programmer.pick.data.remote.websocket.SyncMessage
import ua.com.programmer.pick.data.remote.websocket.WebSocketManager
import ua.com.programmer.pick.domain.repository.EntityType
import ua.com.programmer.pick.domain.repository.OperationStatus
import ua.com.programmer.pick.domain.repository.OperationType
import ua.com.programmer.pick.domain.repository.OutgoingOperation
import ua.com.programmer.pick.domain.repository.OutgoingOperationRepository
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync status for an entity type
 */
enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

/**
 * Overall sync state
 */
data class SyncState(
    val isOnline: Boolean = false,
    val isWebSocketConnected: Boolean = false,
    val isSyncing: Boolean = false,
    val pendingOperationsCount: Int = 0,
    val lastSyncTime: Long? = null,
    val lastError: String? = null,
    val entityStates: Map<String, SyncStatus> = emptyMap()
)

/**
 * Coordinates all synchronization operations:
 * - Incoming data from REST API (delta/full sync)
 * - Incoming data from WebSocket (real-time updates)
 * - Outgoing operations queue processing
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    private val syncApi: SyncApi,
    private val webSocketManager: WebSocketManager,
    private val syncStateDao: SyncStateDao,
    private val documentDao: DocumentDao,
    private val documentLineDao: DocumentLineDao,
    private val productDao: ProductDao,
    private val clientDao: ClientDao,
    private val warehouseDao: WarehouseDao,
    private val outgoingOperationRepository: OutgoingOperationRepository,
    private val networkMonitor: NetworkMonitor,
    private val documentMapper: DocumentMapper,
    private val productMapper: ProductMapper,
    private val clientMapper: ClientMapper,
    private val warehouseMapper: WarehouseMapper,
    private val gson: Gson,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "SyncOrchestrator"
        private const val MAX_RETRIES = 5
        private const val EXPONENTIAL_BACKOFF_BASE_MS = 1000L
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var isInitialized = false

    /**
     * Initialize sync orchestrator - call once on app startup
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        Log.d(TAG, "Initializing SyncOrchestrator")

        // Observe network state
        networkMonitor.isOnline
            .onEach { isOnline ->
                _syncState.value = _syncState.value.copy(isOnline = isOnline)
                if (isOnline) {
                    onNetworkAvailable()
                }
            }
            .launchIn(scope)

        // Observe WebSocket connection state
        webSocketManager.connectionState
            .onEach { connectionState ->
                val isConnected = connectionState is ConnectionState.Connected
                _syncState.value = _syncState.value.copy(isWebSocketConnected = isConnected)
            }
            .launchIn(scope)

        // Observe WebSocket messages
        webSocketManager.incomingMessages
            .onEach { message ->
                handleWebSocketMessage(message)
            }
            .launchIn(scope)

        // Observe pending operations count
        outgoingOperationRepository.getPendingOperationCount()
            .onEach { count ->
                _syncState.value = _syncState.value.copy(pendingOperationsCount = count)
            }
            .launchIn(scope)
    }

    /**
     * Start WebSocket connection
     */
    fun connectWebSocket() {
        if (networkMonitor.isCurrentlyConnected()) {
            webSocketManager.connect()
        }
    }

    /**
     * Disconnect WebSocket
     */
    fun disconnectWebSocket() {
        webSocketManager.disconnect()
    }

    /**
     * Perform delta sync for all entities
     */
    suspend fun syncAll(): Result<Unit> {
        Log.d(TAG, "Starting full sync for all entities")

        if (!networkMonitor.isCurrentlyConnected()) {
            return Result.Error(Exception("No network connection"))
        }

        _syncState.value = _syncState.value.copy(isSyncing = true)

        val entities = listOf(
            Constants.SyncEntity.PRODUCTS,
            Constants.SyncEntity.CLIENTS,
            Constants.SyncEntity.WAREHOUSES,
            Constants.SyncEntity.DOCUMENTS
        )

        var hasError = false
        var lastError: String? = null

        for (entity in entities) {
            val result = syncEntity(entity)
            if (result is Result.Error) {
                hasError = true
                lastError = result.exception.message
                Log.e(TAG, "Failed to sync $entity: ${result.exception.message}")
            }
        }

        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            lastSyncTime = if (!hasError) System.currentTimeMillis() else _syncState.value.lastSyncTime,
            lastError = lastError
        )

        return if (hasError) {
            Result.Error(Exception(lastError ?: "Sync failed"))
        } else {
            Result.Success(Unit)
        }
    }

    /**
     * Sync a specific entity type
     */
    suspend fun syncEntity(entityType: String): Result<Unit> {
        Log.d(TAG, "Syncing entity: $entityType")

        updateEntitySyncStatus(entityType, SyncStatus.SYNCING)

        return try {
            val lastSyncTime = syncStateDao.getSyncState(entityType)?.lastSyncTime ?: 0L
            val isFullSync = lastSyncTime == 0L

            val response = if (isFullSync) {
                syncApi.getFullSync(entityType)
            } else {
                syncApi.getDeltaSync(entityType, lastSyncTime)
            }

            if (response.isSuccessful) {
                val syncResponse = response.body()
                if (syncResponse != null) {
                    applySync(syncResponse.entityType, syncResponse.data, syncResponse.deletedIds)

                    // Acknowledge sync
                    syncApi.acknowledgSync(
                        SyncAckRequest(
                            entityType = syncResponse.entityType,
                            syncId = syncResponse.syncId,
                            timestamp = syncResponse.timestamp
                        )
                    )

                    // Update sync state
                    syncStateDao.updateSyncSuccess(entityType, syncResponse.timestamp)
                    updateEntitySyncStatus(entityType, SyncStatus.SUCCESS)

                    Log.d(TAG, "Successfully synced $entityType")
                    Result.Success(Unit)
                } else {
                    updateEntitySyncStatus(entityType, SyncStatus.ERROR)
                    Result.Error(Exception("Empty response"))
                }
            } else {
                val error = "HTTP ${response.code()}: ${response.message()}"
                syncStateDao.updateSyncError(entityType, "ERROR", error)
                updateEntitySyncStatus(entityType, SyncStatus.ERROR)
                Result.Error(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing $entityType: ${e.message}", e)
            syncStateDao.updateSyncError(entityType, "ERROR", e.message)
            updateEntitySyncStatus(entityType, SyncStatus.ERROR)
            Result.Error(e)
        }
    }

    /**
     * Upload pending outgoing operations
     */
    suspend fun uploadPendingOperations(): Result<Int> {
        if (!networkMonitor.isCurrentlyConnected()) {
            return Result.Error(Exception("No network connection"))
        }

        var uploadedCount = 0
        var continueProcessing = true

        while (continueProcessing) {
            val operation = outgoingOperationRepository.getNextPendingOperation()
            if (operation == null) {
                continueProcessing = false
                continue
            }

            val result = processOperation(operation)
            when (result) {
                is Result.Success -> {
                    outgoingOperationRepository.markOperationCompleted(operation.id)
                    uploadedCount++
                }
                is Result.Error -> {
                    if (operation.retryCount >= MAX_RETRIES) {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id,
                            result.exception.message ?: "Max retries exceeded"
                        )
                    } else {
                        outgoingOperationRepository.retryOperation(operation.id)
                    }
                    // Continue to next operation
                }
                is Result.Loading -> { /* Shouldn't happen */ }
            }
        }

        Log.d(TAG, "Uploaded $uploadedCount operations")
        return Result.Success(uploadedCount)
    }

    private suspend fun processOperation(operation: OutgoingOperation): Result<Unit> {
        Log.d(TAG, "Processing operation: ${operation.operationType} for ${operation.entityId}")

        return try {
            when (operation.operationType) {
                OperationType.TAKE_INTO_WORK -> processTakeIntoWork(operation)
                OperationType.UPDATE_DOCUMENT -> processUpdateDocument(operation)
                OperationType.UPDATE_LINE -> processUpdateLine(operation)
                OperationType.COMPLETE_DOCUMENT -> processCompleteDocument(operation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Operation failed: ${e.message}", e)
            Result.Error(e)
        }
    }

    private suspend fun processTakeIntoWork(operation: OutgoingOperation): Result<Unit> {
        val request = gson.fromJson(operation.payload, TakeDocumentRequestDto::class.java)

        // Try WebSocket first, fall back to REST
        if (webSocketManager.isConnected()) {
            val message = SyncMessage.TakeIntoWork(
                messageId = operation.id,
                documentId = request.documentId,
                userId = request.userId,
                timestamp = request.timestamp
            )
            webSocketManager.sendMessage(message)
            // Note: ACK handling is asynchronous
            return Result.Success(Unit)
        }

        // REST fallback would go here
        return Result.Success(Unit)
    }

    private suspend fun processUpdateDocument(operation: OutgoingOperation): Result<Unit> {
        // Document update via WebSocket
        if (webSocketManager.isConnected()) {
            val document = documentDao.getDocumentById(operation.entityId)
            if (document != null) {
                val message = SyncMessage.DocumentUpdate(
                    messageId = operation.id,
                    documentId = document.id,
                    state = document.state,
                    notes = document.notes,
                    totalActual = document.totalActual,
                    version = document.version,
                    timestamp = System.currentTimeMillis()
                )
                webSocketManager.sendMessage(message)
            }
        }
        return Result.Success(Unit)
    }

    private suspend fun processUpdateLine(operation: OutgoingOperation): Result<Unit> {
        if (webSocketManager.isConnected()) {
            val line = documentLineDao.getLineById(operation.entityId)
            if (line != null) {
                val message = SyncMessage.LineUpdate(
                    messageId = operation.id,
                    documentId = line.documentId,
                    lineId = line.id,
                    actualQuantity = line.actualQuantity,
                    batchNumber = line.batchNumber,
                    locationId = line.locationId,
                    notes = line.notes,
                    isCompleted = line.isCompleted,
                    timestamp = System.currentTimeMillis()
                )
                webSocketManager.sendMessage(message)
            }
        }
        return Result.Success(Unit)
    }

    private suspend fun processCompleteDocument(operation: OutgoingOperation): Result<Unit> {
        val request = gson.fromJson(operation.payload, CompleteDocumentRequestDto::class.java)

        if (webSocketManager.isConnected()) {
            val message = SyncMessage.CompleteDocument(
                messageId = operation.id,
                documentId = request.documentId,
                userId = request.userId,
                completedAt = request.completedAt,
                version = request.version
            )
            webSocketManager.sendMessage(message)
        }
        return Result.Success(Unit)
    }

    private suspend fun applySync(
        entityType: String,
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        Log.d(TAG, "Applying sync for $entityType")

        when (entityType) {
            Constants.SyncEntity.DOCUMENTS -> applyDocumentSync(data, deletedIds)
            Constants.SyncEntity.PRODUCTS -> applyProductSync(data, deletedIds)
            Constants.SyncEntity.CLIENTS -> applyClientSync(data, deletedIds)
            Constants.SyncEntity.WAREHOUSES -> applyWarehouseSync(data, deletedIds)
        }
    }

    private suspend fun applyDocumentSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<DocumentDto>>() {}.type
            val documents: List<DocumentDto> = gson.fromJson(data, type)

            documents.forEach { dto ->
                val entity = documentMapper.toEntity(dto)
                documentDao.upsertDocument(entity)

                // Save lines if present
                dto.lines?.forEach { lineDto ->
                    val lineEntity = documentMapper.toLineEntity(lineDto)
                    documentLineDao.upsertLine(lineEntity)
                }
            }
        }

        deletedIds?.forEach { id ->
            documentDao.deleteDocument(id)
        }
    }

    private suspend fun applyProductSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<ProductDto>>() {}.type
            val products: List<ProductDto> = gson.fromJson(data, type)

            products.forEach { dto ->
                val entity = productMapper.toEntity(dto)
                productDao.upsertProduct(entity)

                // Save barcodes
                val barcodes = productMapper.toBarcodeEntityList(dto)
                productDao.deleteBarcodesForProduct(dto.id)
                barcodes.forEach { barcode ->
                    productDao.insertBarcode(barcode)
                }
            }
        }

        deletedIds?.forEach { id ->
            productDao.deleteProduct(id)
        }
    }

    private suspend fun applyClientSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<ClientDto>>() {}.type
            val clients: List<ClientDto> = gson.fromJson(data, type)

            clients.forEach { dto ->
                val entity = clientMapper.toEntity(dto)
                clientDao.upsertClient(entity)
            }
        }

        deletedIds?.forEach { id ->
            clientDao.deleteClient(id)
        }
    }

    private suspend fun applyWarehouseSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<WarehouseDto>>() {}.type
            val warehouses: List<WarehouseDto> = gson.fromJson(data, type)

            warehouses.forEach { dto ->
                val entity = warehouseMapper.toEntity(dto)
                warehouseDao.upsertWarehouse(entity)

                // Save locations
                dto.locations?.forEach { locationDto ->
                    val locationEntity = warehouseMapper.toLocationEntity(locationDto, dto.id)
                    warehouseDao.upsertLocation(locationEntity)
                }
            }
        }

        deletedIds?.forEach { id ->
            warehouseDao.deleteWarehouse(id)
        }
    }

    private fun handleWebSocketMessage(message: SyncMessage) {
        scope.launch {
            when (message) {
                is SyncMessage.DeltaUpdate -> {
                    Log.d(TAG, "Received delta update for ${message.entityType}")
                    applySync(message.entityType, message.data, message.deletedIds)
                    syncStateDao.updateSyncSuccess(message.entityType, message.timestamp)
                }
                is SyncMessage.DocumentLock -> {
                    Log.d(TAG, "Document ${message.documentId} locked by ${message.lockedByName}")
                    // Update local document state if needed
                    documentDao.updateAssignedUser(message.documentId, message.lockedBy, message.lockedAt)
                }
                is SyncMessage.Acknowledgment -> {
                    Log.d(TAG, "Received ACK for ${message.originalMessageId}: ${message.success}")
                    if (message.success) {
                        outgoingOperationRepository.markOperationCompleted(message.originalMessageId)

                        // Update version if provided
                        message.newVersion?.let { newVersion ->
                            // Could update document version here
                        }
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            message.originalMessageId,
                            message.error ?: "Server rejected operation"
                        )
                    }
                }
                is SyncMessage.ServerError -> {
                    Log.e(TAG, "Server error: ${message.code} - ${message.message}")
                    _syncState.value = _syncState.value.copy(
                        lastError = "${message.code}: ${message.message}"
                    )
                }
                else -> {
                    Log.d(TAG, "Unhandled message type: ${message.type}")
                }
            }
        }
    }

    private fun onNetworkAvailable() {
        Log.d(TAG, "Network available, triggering sync")

        scope.launch {
            // Connect WebSocket
            connectWebSocket()

            // Upload pending operations
            uploadPendingOperations()

            // Perform delta sync
            syncAll()
        }
    }

    private fun updateEntitySyncStatus(entityType: String, status: SyncStatus) {
        val currentStates = _syncState.value.entityStates.toMutableMap()
        currentStates[entityType] = status
        _syncState.value = _syncState.value.copy(entityStates = currentStates)
    }
}
