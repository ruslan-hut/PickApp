package ua.com.programmer.pick.data.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import ua.com.programmer.pick.data.local.database.dao.ProductImageDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.mapper.ClientMapper
import ua.com.programmer.pick.data.mapper.DocumentMapper
import ua.com.programmer.pick.data.mapper.ProductMapper
import ua.com.programmer.pick.data.mapper.WarehouseMapper
import ua.com.programmer.pick.data.mapper.toEntityForSync
import ua.com.programmer.pick.data.remote.dto.BarcodeDto
import ua.com.programmer.pick.data.remote.dto.ClientDto
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.dto.ProductDto
import ua.com.programmer.pick.data.remote.dto.UserDto
import ua.com.programmer.pick.data.remote.dto.WarehouseDto
import ua.com.programmer.pick.data.remote.websocket.ConnectionState
import ua.com.programmer.pick.data.remote.websocket.DocumentLineUpdate
import ua.com.programmer.pick.data.remote.websocket.MessageParser
import ua.com.programmer.pick.data.remote.websocket.SyncMessage
import ua.com.programmer.pick.data.remote.websocket.UserAuthState
import ua.com.programmer.pick.data.remote.websocket.WebSocketManager
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.repository.EntityType
import ua.com.programmer.pick.domain.repository.OperationType
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
    val isUserAuthenticated: Boolean = false,
    val authenticatedUserId: String? = null,
    val authenticatedUserName: String? = null,
    val authenticatedUserRole: String? = null,
    val isSyncing: Boolean = false,
    val pendingOperationsCount: Int = 0,
    val lastSyncTime: Long? = null,
    val lastError: String? = null,
    val entityStates: Map<String, SyncStatus> = emptyMap()
)

/**
 * Result of document lock operation
 */
data class DocumentLockResult(
    val success: Boolean,
    val documentId: String,
    val lockedBy: String? = null,
    val error: String? = null
)

/**
 * Result of document complete operation
 */
data class DocumentCompleteResult(
    val success: Boolean,
    val documentId: String,
    val state: String? = null,
    val version: Int? = null,
    val error: String? = null
)

/**
 * Coordinates all synchronization operations via WebSocket:
 * - Incoming data from WebSocket (sync data, push notifications)
 * - Outgoing operations (document lock, update, complete)
 * - Product lookup
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    private val webSocketManager: WebSocketManager,
    private val messageParser: MessageParser,
    private val appPreferences: AppPreferences,
    private val syncStateDao: SyncStateDao,
    private val documentDao: DocumentDao,
    private val documentLineDao: DocumentLineDao,
    private val productDao: ProductDao,
    private val productImageDao: ProductImageDao,
    private val clientDao: ClientDao,
    private val warehouseDao: WarehouseDao,
    private val userDao: UserDao,
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
        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val MAX_QUEUE_RETRIES = 5
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Track current sync session
    private var currentSyncId: String? = null
    private var pendingSyncCursors: Map<String, String>? = null
    private var syncTimeoutJob: Job? = null

    private var isInitialized = false

    /**
     * Initialize sync orchestrator - call once on app startup
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        Log.d(TAG, "Initializing SyncOrchestrator")

        // Reset operations stuck in PROCESSING from a previous crash
        scope.launch {
            try {
                outgoingOperationRepository.resetStaleProcessingOperations()
                Log.d(TAG, "Reset stale PROCESSING operations to PENDING")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset stale operations: ${e.message}", e)
            }
        }

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

                // Note: Sync and pending operations are now triggered after user login
                // (see userAuthState observer below)
            }
            .launchIn(scope)

        // Observe user authentication state (Stage 2 of protocol)
        webSocketManager.userAuthState
            .onEach { authState ->
                when (authState) {
                    is UserAuthState.Authenticated -> {
                        _syncState.value = _syncState.value.copy(
                            isUserAuthenticated = true,
                            authenticatedUserId = authState.userId,
                            authenticatedUserName = authState.userName,
                            authenticatedUserRole = authState.role
                        )

                        // Now that user is authenticated, request sync and process pending operations
                        requestDeltaSync()
                        processPendingOperations()
                    }
                    is UserAuthState.AuthFailed -> {
                        _syncState.value = _syncState.value.copy(
                            isUserAuthenticated = false,
                            authenticatedUserId = null,
                            authenticatedUserName = null,
                            authenticatedUserRole = null,
                            lastError = "User login failed: ${authState.error}"
                        )
                    }
                    else -> {
                        _syncState.value = _syncState.value.copy(
                            isUserAuthenticated = false,
                            authenticatedUserId = null,
                            authenticatedUserName = null,
                            authenticatedUserRole = null
                        )
                    }
                }
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
     * Login user via WebSocket (Stage 2: User Login)
     * This should be called after WebSocket connection is established.
     *
     * @param login User login
     * @param password User password
     * @return Result with success/failure
     */
    suspend fun loginUser(login: String, password: String): Result<Unit> {
        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }

        val result = webSocketManager.loginUser(login, password)

        return if (result.success) {
            // Store credentials for auto-login on reconnect
            appPreferences.setUserCredentials(login, password)

            // Store user ID
            result.userId?.let { userId ->
                appPreferences.setCurrentUserId(userId)
            }

            // Store offline hash if provided (for offline authentication)
            result.offlineHash?.let { hash ->
                appPreferences.setOfflineHash(hash)
            }

            Result.Success(Unit)
        } else {
            Result.Error(Exception(result.errorMessage ?: "Login failed"))
        }
    }

    /**
     * Logout user - clears credentials and resets auth state
     */
    suspend fun logoutUser() {
        appPreferences.clearUserCredentials()
        // Disconnect and reconnect to reset the session
        // (user will need to login again after reconnect)
        disconnectWebSocket()
    }

    /**
     * Check if user is authenticated via WebSocket
     */
    fun isUserAuthenticated(): Boolean = webSocketManager.isUserAuthenticated()

    // ============================================
    // Sync Operations
    // ============================================

    /**
     * Request delta sync for all entities via WebSocket.
     * Requires user authentication (per protocol).
     */
    suspend fun requestDeltaSync(): Result<Unit> {
        Log.d(TAG, "Requesting delta sync")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }

        if (!webSocketManager.isUserAuthenticated()) {
            Log.d(TAG, "User not authenticated, skipping sync request")
            return Result.Error(Exception("User not authenticated"))
        }

        _syncState.value = _syncState.value.copy(isSyncing = true)

        // Get cursors from database
        val cursors = syncStateDao.getCursorsMap()

        val message = SyncMessage.SyncRequest(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            entityTypes = Constants.SyncEntity.ALL,
            cursors = cursors.ifEmpty { null }
        )

        val sent = webSocketManager.sendMessage(message)
        if (!sent) {
            _syncState.value = _syncState.value.copy(isSyncing = false, lastError = "Failed to send sync request")
            return Result.Error(Exception("Failed to send sync request"))
        }

        // Update entity statuses
        Constants.SyncEntity.ALL.forEach { entityType ->
            updateEntitySyncStatus(entityType, SyncStatus.SYNCING)
        }

        // Start sync timeout
        startSyncTimeout()

        return Result.Success(Unit)
    }

    private fun startSyncTimeout() {
        syncTimeoutJob?.cancel()
        syncTimeoutJob = scope.launch {
            delay(SYNC_TIMEOUT_MS)
            if (_syncState.value.isSyncing) {
                Log.e(TAG, "Sync timeout after ${SYNC_TIMEOUT_MS}ms")
                _syncState.value = _syncState.value.copy(
                    isSyncing = false,
                    lastError = "Sync timeout"
                )
                Constants.SyncEntity.ALL.forEach { entityType ->
                    updateEntitySyncStatus(entityType, SyncStatus.ERROR)
                }
            }
        }
    }

    /**
     * Request full sync for all entities via WebSocket.
     * Requires user authentication (per protocol).
     */
    suspend fun requestFullSync(): Result<Unit> {
        Log.d(TAG, "Requesting full sync")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }

        if (!webSocketManager.isUserAuthenticated()) {
            Log.d(TAG, "User not authenticated, skipping full sync request")
            return Result.Error(Exception("User not authenticated"))
        }

        _syncState.value = _syncState.value.copy(isSyncing = true)

        val message = SyncMessage.FullSyncRequest(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            entityTypes = Constants.SyncEntity.ALL
        )

        val sent = webSocketManager.sendMessage(message)
        if (!sent) {
            _syncState.value = _syncState.value.copy(isSyncing = false, lastError = "Failed to send full sync request")
            return Result.Error(Exception("Failed to send full sync request"))
        }

        // Update entity statuses
        Constants.SyncEntity.ALL.forEach { entityType ->
            updateEntitySyncStatus(entityType, SyncStatus.SYNCING)
        }

        return Result.Success(Unit)
    }

    // ============================================
    // Document Operations
    // ============================================

    /**
     * Lock a document for editing ("Take into work")
     */
    suspend fun lockDocument(documentId: String): Result<DocumentLockResult> {
        Log.d(TAG, "Locking document: $documentId")

        if (!webSocketManager.isConnected()) {
            Log.d(TAG, "WebSocket not connected, queueing lock operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.DOCUMENT_LOCK,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to documentId))
            )
            return Result.Error(Exception("WebSocket not connected, operation queued"))
        }

        val message = SyncMessage.DocumentLock(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = documentId
        )

        val response = webSocketManager.sendAndAwait(
            message,
            SyncMessage.DocumentLockResult::class.java
        )

        return if (response != null) {
            val result = DocumentLockResult(
                success = response.success,
                documentId = response.documentId,
                lockedBy = response.lockedBy,
                error = response.error
            )

            if (response.success) {
                // Update local document state from server confirmation (don't mark dirty)
                documentDao.updateDocumentStateFromServer(documentId, "IN_PROGRESS", System.currentTimeMillis())
                response.lockedBy?.let { userId ->
                    documentDao.updateAssignedUser(documentId, userId, System.currentTimeMillis())
                }
            }

            Result.Success(result)
        } else {
            Result.Error(Exception("Lock request timeout"))
        }
    }

    /**
     * Unlock a document
     */
    suspend fun unlockDocument(documentId: String): Result<Unit> {
        Log.d(TAG, "Unlocking document: $documentId")

        if (!webSocketManager.isConnected()) {
            Log.d(TAG, "WebSocket not connected, queueing unlock operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.DOCUMENT_UNLOCK,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to documentId))
            )
            return Result.Success(Unit)
        }

        val message = SyncMessage.DocumentUnlock(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = documentId
        )

        val sent = webSocketManager.sendMessage(message)
        return if (sent) {
            // Update local document state from server action (don't mark dirty)
            documentDao.updateDocumentStateFromServer(documentId, "LOADED", System.currentTimeMillis())
            documentDao.clearAssignedUser(documentId)
            Result.Success(Unit)
        } else {
            Result.Error(Exception("Failed to send unlock request"))
        }
    }

    /**
     * Update document lines
     */
    suspend fun updateDocument(
        documentId: String,
        state: String,
        lines: List<DocumentLineUpdate>
    ): Result<Unit> {
        Log.d(TAG, "Updating document: $documentId with ${lines.size} lines")

        if (!webSocketManager.isConnected()) {
            Log.d(TAG, "WebSocket not connected, queueing update operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.DOCUMENT_UPDATE,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf(
                    "document_id" to documentId,
                    "state" to state,
                    "lines" to lines
                ))
            )
            return Result.Error(Exception("WebSocket not connected, operation queued"))
        }

        val message = SyncMessage.DocumentUpdate(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = documentId,
            state = state,
            lines = lines
        )

        val sent = webSocketManager.sendMessage(message)
        return if (sent) {
            Result.Success(Unit)
        } else {
            Result.Error(Exception("Failed to send document update"))
        }
    }

    /**
     * Complete document processing
     */
    suspend fun completeDocument(documentId: String): Result<DocumentCompleteResult> {
        Log.d(TAG, "Completing document: $documentId")

        if (!webSocketManager.isConnected()) {
            Log.d(TAG, "WebSocket not connected, queueing complete operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.DOCUMENT_COMPLETE,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to documentId))
            )
            return Result.Error(Exception("WebSocket not connected, operation queued"))
        }

        val message = SyncMessage.DocumentComplete(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = documentId
        )

        val response = webSocketManager.sendAndAwait(
            message,
            SyncMessage.DocumentCompleteResult::class.java
        )

        return if (response != null) {
            val result = DocumentCompleteResult(
                success = response.success,
                documentId = response.documentId,
                state = response.state,
                version = response.version,
                error = response.error
            )

            if (response.success) {
                // Update local document state from server confirmation (don't mark dirty)
                documentDao.updateDocumentStateFromServer(documentId, response.state ?: "COMPLETED", System.currentTimeMillis())
                response.version?.let { version ->
                    documentDao.updateDocumentVersion(documentId, version)
                }
            }

            Result.Success(result)
        } else {
            Result.Error(Exception("Complete request timeout"))
        }
    }

    // ============================================
    // Product Lookup
    // ============================================

    /**
     * Lookup product by barcode via WebSocket
     * First checks local database, then queries server
     */
    suspend fun lookupProductByBarcode(barcode: String): Result<ProductDto?> {
        Log.d(TAG, "Looking up product by barcode: $barcode")

        // First check local database
        val localBarcodes = productDao.getProductIdByBarcode(barcode)
        if (localBarcodes.isNotEmpty()) {
            val productId = localBarcodes.first().productId
            val entity = productDao.getProductById(productId)
            if (entity != null) {
                val barcodes = productDao.getBarcodesByProductId(productId)
                Log.d(TAG, "Product found locally: ${entity.name}")
                // Convert to DTO for consistency
                return Result.Success(ProductDto(
                    id = entity.id,
                    code = entity.code,
                    name = entity.name,
                    description = entity.description,
                    unit = entity.unit,
                    supportsBatches = entity.supportsBatches,
                    isActive = entity.isActive,
                    barcodes = barcodes.map {
                        BarcodeDto(
                            id = it.id,
                            barcode = it.barcode,
                            type = it.type,
                            isPrimary = it.isPrimary
                        )
                    }
                ))
            }
        }

        // Not found locally, query server
        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("Product not found locally and WebSocket not connected"))
        }

        val message = SyncMessage.ProductLookup(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            barcode = barcode
        )

        val response = webSocketManager.sendAndAwait(
            message,
            SyncMessage.ProductLookupResult::class.java
        )

        return if (response != null && response.success && response.product != null) {
            val productDto = gson.fromJson(response.product, ProductDto::class.java)

            // Cache locally
            val entity = productMapper.toEntity(productDto)
            productDao.upsertProduct(entity)

            val barcodeEntities = productMapper.toBarcodeEntityList(productDto)
            productDao.deleteBarcodesForProduct(productDto.id)
            barcodeEntities.forEach { productDao.insertBarcode(it) }

            productMapper.toImageEntity(productDto)?.let { imageEntity ->
                productImageDao.deleteByProductId(productDto.id)
                productImageDao.insert(imageEntity)
            }

            Log.d(TAG, "Product found on server and cached: ${productDto.name}")
            Result.Success(productDto)
        } else {
            val error = response?.error ?: "Product not found"
            Log.d(TAG, "Product lookup failed: $error")
            Result.Success(null)
        }
    }

    // ============================================
    // Message Handling
    // ============================================

    private fun handleWebSocketMessage(message: SyncMessage) {
        scope.launch {
            when (message) {
                is SyncMessage.SyncData -> {
                    handleSyncData(message)
                }
                is SyncMessage.SyncComplete -> {
                    handleSyncComplete(message)
                }
                is SyncMessage.DocumentLockResult -> {
                    handleDocumentLockResult(message)
                }
                is SyncMessage.DocumentCompleteResult -> {
                    handleDocumentCompleteResult(message)
                }
                is SyncMessage.Push -> {
                    handlePush(message)
                }
                is SyncMessage.ServerError -> {
                    handleServerError(message)
                }
                is SyncMessage.UserLoginResult -> {
                    // UserLoginResult is handled by WebSocketManager's userAuthState flow
                    // which SyncOrchestrator observes in initialize()
                    Log.d(TAG, "UserLoginResult received, handled via userAuthState flow")
                }
                else -> {
                    Log.d(TAG, "Unhandled message type: ${message.type}")
                }
            }
        }
    }

    private suspend fun handleSyncData(message: SyncMessage.SyncData) {
        Log.d(TAG, "Received sync data for ${message.entityType}")

        try {
            applySync(message.entityType, message.data, message.deletedIds)
            updateEntitySyncStatus(message.entityType, SyncStatus.SUCCESS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply sync data for ${message.entityType}: ${e.message}", e)
            updateEntitySyncStatus(message.entityType, SyncStatus.ERROR)
        }
    }

    private suspend fun handleSyncComplete(message: SyncMessage.SyncComplete) {
        Log.d(TAG, "Sync complete: ${message.syncId}")

        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        currentSyncId = message.syncId
        pendingSyncCursors = message.cursors

        // Update all cursors in database
        syncStateDao.updateCursors(message.cursors)

        // Send ACK
        val ackMessage = SyncMessage.Ack(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            syncId = message.syncId,
            cursors = message.cursors
        )
        webSocketManager.sendMessage(ackMessage)

        // Update sync state
        _syncState.value = _syncState.value.copy(
            isSyncing = false,
            lastSyncTime = System.currentTimeMillis(),
            lastError = null
        )

        // Update all entity statuses to SUCCESS
        Constants.SyncEntity.ALL.forEach { entityType ->
            updateEntitySyncStatus(entityType, SyncStatus.SUCCESS)
        }
    }

    private suspend fun handleDocumentLockResult(message: SyncMessage.DocumentLockResult) {
        Log.d(TAG, "Document lock result: ${message.documentId}, success: ${message.success}")

        if (message.success) {
            documentDao.updateDocumentStateFromServer(message.documentId, "IN_PROGRESS", System.currentTimeMillis())
            message.lockedBy?.let { userId ->
                documentDao.updateAssignedUser(message.documentId, userId, System.currentTimeMillis())
            }
        }
    }

    private suspend fun handleDocumentCompleteResult(message: SyncMessage.DocumentCompleteResult) {
        Log.d(TAG, "Document complete result: ${message.documentId}, success: ${message.success}")

        if (message.success) {
            documentDao.updateDocumentStateFromServer(message.documentId, message.state ?: "COMPLETED", System.currentTimeMillis())
            message.version?.let { version ->
                documentDao.updateDocumentVersion(message.documentId, version)
            }
        }
    }

    private suspend fun handlePush(message: SyncMessage.Push) {
        Log.d(TAG, "Push notification: ${message.event} for ${message.entityType}/${message.entityId}")

        when (message.event) {
            "document_updated", "document_locked", "document_unlocked" -> {
                // Apply the update if data is provided
                message.entityType?.let { entityType ->
                    message.data?.let { data ->
                        applySync(entityType, data, null)
                    }
                }
            }
            "reference_updated" -> {
                // Request delta sync for the updated entity type
                message.entityType?.let { entityType ->
                    requestEntitySync(entityType)
                }
            }
            else -> {
                Log.w(TAG, "Unhandled push event: ${message.event}")
            }
        }
    }

    private fun handleServerError(message: SyncMessage.ServerError) {
        Log.e(TAG, "Server error: ${message.code} - ${message.message}")

        _syncState.value = _syncState.value.copy(
            lastError = "${message.code}: ${message.message}",
            isSyncing = false
        )

        // If FORBIDDEN, trigger reconnect (which will refresh the token)
        if (message.code == "FORBIDDEN") {
            Log.d(TAG, "FORBIDDEN error received, triggering WebSocket reconnect with token refresh")
            webSocketManager.disconnect()
            scope.launch {
                kotlinx.coroutines.delay(500)
                webSocketManager.connect()
            }
        }
    }

    // ============================================
    // Sync Application
    // ============================================

    private suspend fun applySync(
        entityType: String,
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        Log.d(TAG, "Applying sync for $entityType")

        when (entityType) {
            Constants.SyncEntity.USERS -> applyUserSync(data, deletedIds)
            Constants.SyncEntity.DOCUMENTS -> applyDocumentSync(data, deletedIds)
            Constants.SyncEntity.PRODUCTS -> applyProductSync(data, deletedIds)
            Constants.SyncEntity.CLIENTS -> applyClientSync(data, deletedIds)
            Constants.SyncEntity.WAREHOUSES -> applyWarehouseSync(data, deletedIds)
        }
    }

    private suspend fun applyUserSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<UserDto>>() {}.type
            val users: List<UserDto> = gson.fromJson(data, type)

            users.forEach { dto ->
                // Preserve existing passwordHash if user already exists locally
                val existingUser = userDao.getUserById(dto.id)
                val entity = dto.toEntityForSync(existingUser?.passwordHash)
                userDao.insertUser(entity)
            }
        }

        deletedIds?.forEach { id ->
            userDao.deleteUser(id)
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
                // Skip overwriting locally dirty documents — server will get our version when uploaded
                val existing = documentDao.getDocumentById(dto.id)
                if (existing != null && existing.isDirty) {
                    Log.w(TAG, "Skipping server upsert for dirty document: ${dto.id}")
                    return@forEach
                }

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

                // Save product image if present
                val imageEntity = productMapper.toImageEntity(dto)
                if (imageEntity != null) {
                    productImageDao.deleteByProductId(dto.id)
                    productImageDao.insert(imageEntity)
                }
            }
        }

        deletedIds?.forEach { id ->
            productDao.deleteProduct(id)
            productImageDao.deleteByProductId(id)
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

    // ============================================
    // Helpers
    // ============================================

    private suspend fun requestEntitySync(entityType: String) {
        if (!webSocketManager.isConnected()) return
        if (!webSocketManager.isUserAuthenticated()) return

        val cursor = syncStateDao.getCursor(entityType)
        val cursors = if (cursor != null) mapOf(entityType to cursor) else null

        val message = SyncMessage.SyncRequest(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            entityTypes = listOf(entityType),
            cursors = cursors
        )

        webSocketManager.sendMessage(message)
        updateEntitySyncStatus(entityType, SyncStatus.SYNCING)
    }

    private fun onNetworkAvailable() {
        Log.d(TAG, "Network available, connecting WebSocket")
        scope.launch {
            connectWebSocket()
        }
    }

    private fun updateEntitySyncStatus(entityType: String, status: SyncStatus) {
        val currentStates = _syncState.value.entityStates.toMutableMap()
        currentStates[entityType] = status
        _syncState.value = _syncState.value.copy(entityStates = currentStates)
    }

    // ============================================
    // Offline Queue Processing
    // ============================================

    /**
     * Process all pending outgoing operations via WebSocket.
     * Called after user authentication and from SyncWorker.
     * Requires user authentication (per protocol).
     */
    suspend fun processPendingOperations() {
        if (!webSocketManager.isConnected()) {
            Log.d(TAG, "Cannot process pending operations - WebSocket not connected")
            return
        }

        if (!webSocketManager.isUserAuthenticated()) {
            Log.d(TAG, "Cannot process pending operations - user not authenticated")
            return
        }

        val pendingOps = outgoingOperationRepository.getAllRetryableOperations()
        if (pendingOps.isEmpty()) {
            Log.d(TAG, "No pending operations to process")
            return
        }

        Log.d(TAG, "Processing ${pendingOps.size} pending operations")

        for (operation in pendingOps) {
            if (!webSocketManager.isConnected()) {
                Log.w(TAG, "WebSocket disconnected during pending operations processing, stopping")
                break
            }

            outgoingOperationRepository.markOperationProcessing(operation.id)
            processOperation(operation)
        }

        // Clean up completed and old failed operations
        outgoingOperationRepository.deleteCompletedOperations()
        outgoingOperationRepository.deleteFailedOperations(MAX_QUEUE_RETRIES)
    }

    private suspend fun processOperation(operation: ua.com.programmer.pick.domain.repository.OutgoingOperation) {
        val message = buildMessageFromOperation(operation)
        if (message == null) {
            outgoingOperationRepository.markOperationFailed(operation.id, "Failed to build message from operation")
            Log.w(TAG, "Failed to build message for operation: ${operation.id}")
            return
        }

        when (operation.operationType) {
            OperationType.DOCUMENT_LOCK -> {
                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.DocumentLockResult::class.java
                )
                if (response != null) {
                    if (response.success) {
                        documentDao.updateDocumentStateFromServer(
                            response.documentId, "IN_PROGRESS", System.currentTimeMillis()
                        )
                        response.lockedBy?.let { userId ->
                            documentDao.updateAssignedUser(response.documentId, userId, System.currentTimeMillis())
                        }
                        outgoingOperationRepository.markOperationCompleted(operation.id)
                        Log.d(TAG, "Queued DOCUMENT_LOCK succeeded for ${operation.entityId}")
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id, response.error ?: "Server rejected lock"
                        )
                        Log.w(TAG, "Queued DOCUMENT_LOCK rejected for ${operation.entityId}: ${response.error}")
                    }
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Lock request timeout")
                    Log.w(TAG, "Queued DOCUMENT_LOCK timeout for ${operation.entityId}")
                }
            }
            OperationType.DOCUMENT_COMPLETE -> {
                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.DocumentCompleteResult::class.java
                )
                if (response != null) {
                    if (response.success) {
                        documentDao.updateDocumentStateFromServer(
                            response.documentId,
                            response.state ?: "COMPLETED",
                            System.currentTimeMillis()
                        )
                        response.version?.let { version ->
                            documentDao.updateDocumentVersion(response.documentId, version)
                        }
                        outgoingOperationRepository.markOperationCompleted(operation.id)
                        Log.d(TAG, "Queued DOCUMENT_COMPLETE succeeded for ${operation.entityId}")
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id, response.error ?: "Server rejected complete"
                        )
                        Log.w(TAG, "Queued DOCUMENT_COMPLETE rejected for ${operation.entityId}: ${response.error}")
                    }
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Complete request timeout")
                    Log.w(TAG, "Queued DOCUMENT_COMPLETE timeout for ${operation.entityId}")
                }
            }
            else -> {
                // Fire-and-forget for DOCUMENT_UPDATE, DOCUMENT_UNLOCK, etc.
                val sent = webSocketManager.sendMessage(message)
                if (sent) {
                    outgoingOperationRepository.markOperationCompleted(operation.id)
                    Log.d(TAG, "Pending operation sent: ${operation.operationType} for ${operation.entityId}")
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Failed to send via WebSocket")
                    Log.w(TAG, "Failed to send pending operation: ${operation.id}")
                }
            }
        }
    }

    private fun buildMessageFromOperation(operation: ua.com.programmer.pick.domain.repository.OutgoingOperation): SyncMessage? {
        return try {
            val payloadJson = com.google.gson.JsonParser.parseString(operation.payload).asJsonObject
            val id = messageParser.generateMessageId()
            val timestamp = messageParser.getCurrentTimestamp()

            when (operation.operationType) {
                OperationType.DOCUMENT_LOCK -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    if (documentId == null) {
                        Log.e(TAG, "Missing document_id in DOCUMENT_LOCK payload")
                        return null
                    }
                    SyncMessage.DocumentLock(id = id, timestamp = timestamp, documentId = documentId)
                }
                OperationType.DOCUMENT_UNLOCK -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    if (documentId == null) {
                        Log.e(TAG, "Missing document_id in DOCUMENT_UNLOCK payload")
                        return null
                    }
                    SyncMessage.DocumentUnlock(id = id, timestamp = timestamp, documentId = documentId)
                }
                OperationType.DOCUMENT_UPDATE -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    val state = payloadJson.get("state")?.asString
                    val linesJson = payloadJson.get("lines")?.asJsonArray
                    if (documentId == null || state == null || linesJson == null) {
                        Log.e(TAG, "Missing required fields in DOCUMENT_UPDATE payload (documentId=$documentId, state=$state, lines=${linesJson != null})")
                        return null
                    }
                    val lines = linesJson.map { lineElement ->
                        val lineObj = lineElement.asJsonObject
                        DocumentLineUpdate(
                            lineNumber = lineObj.get("lineNumber")?.asInt ?: 0,
                            actualQuantity = lineObj.get("actualQuantity")?.asDouble ?: 0.0,
                            batchNumber = lineObj.get("batchNumber")?.asString,
                            isCompleted = lineObj.get("isCompleted")?.asBoolean ?: false
                        )
                    }
                    SyncMessage.DocumentUpdate(
                        id = id,
                        timestamp = timestamp,
                        documentId = documentId,
                        state = state,
                        lines = lines
                    )
                }
                OperationType.DOCUMENT_COMPLETE -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    if (documentId == null) {
                        Log.e(TAG, "Missing document_id in DOCUMENT_COMPLETE payload")
                        return null
                    }
                    SyncMessage.DocumentComplete(id = id, timestamp = timestamp, documentId = documentId)
                }
                else -> {
                    Log.w(TAG, "Unsupported operation type for offline queue: ${operation.operationType}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error building message from operation: ${e.message}", e)
            null
        }
    }

    // ============================================
    // Legacy Methods (for backward compatibility)
    // ============================================

    /**
     * @deprecated Use requestDeltaSync() instead
     */
    @Deprecated("Use requestDeltaSync() instead", ReplaceWith("requestDeltaSync()"))
    suspend fun syncAll(): Result<Unit> = requestDeltaSync()

    /**
     * @deprecated Use requestDeltaSync() instead - sync is now handled via WebSocket
     */
    @Deprecated("Sync is now handled via WebSocket", ReplaceWith("requestDeltaSync()"))
    suspend fun syncEntity(entityType: String): Result<Unit> {
        requestEntitySync(entityType)
        return Result.Success(Unit)
    }
}
