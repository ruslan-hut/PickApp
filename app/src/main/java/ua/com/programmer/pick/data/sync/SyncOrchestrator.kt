package ua.com.programmer.pick.data.sync

import ua.com.programmer.pick.core.util.AppLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.BoxDao
import ua.com.programmer.pick.data.local.database.entity.ProductBarcodeEntity
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.DocumentBoxDao
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
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.data.mapper.toEntityForSync
import ua.com.programmer.pick.data.remote.dto.BarcodeDto
import ua.com.programmer.pick.data.remote.dto.ClientDto
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.dto.ProductDto
import ua.com.programmer.pick.data.remote.dto.BoxDto
import ua.com.programmer.pick.data.remote.dto.DocumentBoxDto
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
    val version: Long? = null,
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
    private val boxDao: BoxDao,
    private val documentBoxDao: DocumentBoxDao,
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
        private const val DOCUMENT_PRODUCTS_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Track current sync session
    private var currentSyncId: String? = null
    private var pendingSyncCursors: Map<String, String>? = null
    private var syncTimeoutJob: Job? = null
    private var isFullSyncActive = false
    private val syncReceivedCounts = mutableMapOf<String, Int>()

    private var isInitialized = false

    /**
     * Initialize sync orchestrator - call once on app startup
     */
    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        AppLog.d(TAG, "Initializing SyncOrchestrator")

        // Reset operations stuck in PROCESSING from a previous crash
        scope.launch {
            try {
                outgoingOperationRepository.resetStaleProcessingOperations()
                AppLog.d(TAG, "Reset stale PROCESSING operations to PENDING")
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to reset stale operations: ${e.message}", e)
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

                        // Documents are loaded via delta sync for all roles.
                        // Locking is an explicit user action (not automatic).
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

        // Log overall sync state changes
        syncState
            .onEach { state ->
                AppLog.i(TAG, buildString {
                    append("SyncState | ")
                    append("online=${state.isOnline} ")
                    append("ws=${state.isWebSocketConnected} ")
                    append("auth=${state.isUserAuthenticated} ")
                    append("syncing=${state.isSyncing} ")
                    append("pending=${state.pendingOperationsCount}")
                    state.lastSyncTime?.let { append(" lastSync=${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}") }
                    if (state.entityStates.isNotEmpty()) append(" entities=${state.entityStates}")
                    state.lastError?.let { append(" error=$it") }
                })
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
        AppLog.d(TAG, "Requesting delta sync")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }

        if (!webSocketManager.isUserAuthenticated()) {
            AppLog.d(TAG, "User not authenticated, skipping sync request")
            return Result.Error(Exception("User not authenticated"))
        }

        syncReceivedCounts.clear()
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
                AppLog.e(TAG, "Sync timeout after ${SYNC_TIMEOUT_MS}ms")
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
        AppLog.d(TAG, "Requesting full sync")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }

        if (!webSocketManager.isUserAuthenticated()) {
            AppLog.d(TAG, "User not authenticated, skipping full sync request")
            return Result.Error(Exception("User not authenticated"))
        }

        syncReceivedCounts.clear()
        _syncState.value = _syncState.value.copy(isSyncing = true)
        isFullSyncActive = true

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
    // Targeted Refresh
    // ============================================

    /**
     * Request document list with related warehouses and clients.
     * Server sends only documents + referenced warehouses/clients (no products).
     */
    suspend fun requestDocumentListRefresh(): Result<Unit> {
        AppLog.d(TAG, "Requesting document list refresh")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }
        if (!webSocketManager.isUserAuthenticated()) {
            return Result.Error(Exception("User not authenticated"))
        }

        isFullSyncActive = true

        val message = SyncMessage.DocumentListRefresh(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp()
        )

        val sent = webSocketManager.sendMessage(message)
        if (!sent) {
            isFullSyncActive = false
            return Result.Error(Exception("Failed to send document list refresh"))
        }

        return Result.Success(Unit)
    }

    /**
     * Request products for a specific document's lines and wait for
     * the server to deliver them via SYNC_DATA. This ensures product
     * barcodes are available in the local DB before the user starts scanning.
     */
    suspend fun requestDocumentProducts(documentId: String): Result<Unit> {
        AppLog.d(TAG, "Requesting products for document: $documentId")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }
        if (!webSocketManager.isUserAuthenticated()) {
            return Result.Error(Exception("User not authenticated"))
        }

        val message = SyncMessage.DocumentProducts(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = documentId
        )

        val sent = webSocketManager.sendMessage(message)
        if (!sent) {
            return Result.Error(Exception("Failed to send document products request"))
        }

        // Wait for the products SYNC_DATA response to arrive and be processed.
        // The server responds with SYNC_DATA(entity_type="products") which triggers
        // handleSyncData → applyProductSync. We wait for that message to arrive,
        // then yield briefly so the handler coroutine can apply the data.
        val received = withTimeoutOrNull(DOCUMENT_PRODUCTS_TIMEOUT_MS) {
            webSocketManager.incomingMessages.first { msg ->
                (msg is SyncMessage.SyncData && msg.entityType == Constants.SyncEntity.PRODUCTS) ||
                        msg is SyncMessage.SyncComplete
            }
        }

        if (received != null) {
            // Give the handler coroutine time to apply sync data to the database
            delay(200)
        } else {
            AppLog.w(TAG, "Timeout waiting for document products response")
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
        AppLog.d(TAG, "Locking document: $documentId")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("Not connected to server"))
        }
        if (!webSocketManager.isUserAuthenticated()) {
            return Result.Error(Exception("User not authenticated"))
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
                documentDao.updateDocumentStateFromServer(documentId, "COLLECTING", System.currentTimeMillis())
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
        AppLog.d(TAG, "Unlocking document: $documentId")

        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected, queueing unlock operation for document: $documentId")
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
        AppLog.d(TAG, "Updating document: $documentId with ${lines.size} lines")

        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected, queueing update operation for document: $documentId")
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
        AppLog.d(TAG, "Completing document: $documentId")

        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected, queueing complete operation for document: $documentId")
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
                documentDao.updateDocumentStateFromServer(documentId, response.state ?: "COLLECTED", System.currentTimeMillis())
                response.version?.let { version ->
                    documentDao.updateDocumentVersion(documentId, version.toInt())
                }

                // After completion, the document list will refresh via delta sync
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
        AppLog.d(TAG, "Looking up product by barcode: $barcode")

        // First check local database
        val localBarcodes = productDao.getProductIdByBarcode(barcode)
        if (localBarcodes.isNotEmpty()) {
            val productId = localBarcodes.first().productId
            val entity = productDao.getProductById(productId)
            if (entity != null) {
                val barcodes = productDao.getBarcodesByProductId(productId)
                AppLog.d(TAG, "Product found locally: ${entity.name}")
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

            AppLog.d(TAG, "Product found on server and cached: ${productDto.name}")
            Result.Success(productDto)
        } else {
            val error = response?.error ?: "Product not found"
            AppLog.d(TAG, "Product lookup failed: $error")
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
                    AppLog.d(TAG, "UserLoginResult received, handled via userAuthState flow")
                }
                else -> {
                    AppLog.d(TAG, "Unhandled message type: ${message.type}")
                }
            }
        }
    }

    private suspend fun handleSyncData(message: SyncMessage.SyncData) {
        val itemCount = if (message.data.isJsonArray) message.data.asJsonArray.size() else 0
        val deletedCount = message.deletedIds?.size ?: 0
        syncReceivedCounts[message.entityType] = (syncReceivedCounts[message.entityType] ?: 0) + itemCount
        AppLog.i(TAG, "SYNC_DATA entity=${message.entityType} upsert=$itemCount delete=$deletedCount")

        try {
            applySync(message.entityType, message.data, message.deletedIds)
            updateEntitySyncStatus(message.entityType, SyncStatus.SUCCESS)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to apply sync data for ${message.entityType}: ${e.message}", e)
            updateEntitySyncStatus(message.entityType, SyncStatus.ERROR)
        }
    }

    private suspend fun handleSyncComplete(message: SyncMessage.SyncComplete) {
        AppLog.i(TAG, "Sync complete: syncId=${message.syncId} received=$syncReceivedCounts cursors=${message.cursors}")
        syncReceivedCounts.clear()

        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        currentSyncId = message.syncId
        pendingSyncCursors = message.cursors
        isFullSyncActive = false

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
        AppLog.d(TAG, "Document lock result: ${message.documentId}, success: ${message.success}")

        if (message.success) {
            documentDao.updateDocumentStateFromServer(message.documentId, "COLLECTING", System.currentTimeMillis())
            message.lockedBy?.let { userId ->
                documentDao.updateAssignedUser(message.documentId, userId, System.currentTimeMillis())
            }
        }
    }

    private suspend fun handleDocumentCompleteResult(message: SyncMessage.DocumentCompleteResult) {
        AppLog.d(TAG, "Document complete result: ${message.documentId}, success: ${message.success}")

        if (message.success) {
            documentDao.updateDocumentStateFromServer(message.documentId, message.state ?: "COLLECTED", System.currentTimeMillis())
            message.version?.let { version ->
                documentDao.updateDocumentVersion(message.documentId, version.toInt())
            }
        }
    }

    private suspend fun handlePush(message: SyncMessage.Push) {
        AppLog.d(TAG, "Push notification: ${message.event} for ${message.entityType}/${message.entityId}")

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
                AppLog.w(TAG, "Unhandled push event: ${message.event}")
            }
        }
    }

    private fun handleServerError(message: SyncMessage.ServerError) {
        AppLog.e(TAG, "Server error: ${message.code} - ${message.message}")

        _syncState.value = _syncState.value.copy(
            lastError = "${message.code}: ${message.message}",
            isSyncing = false
        )

        // If FORBIDDEN, trigger reconnect (which will refresh the token)
        if (message.code == "FORBIDDEN") {
            AppLog.d(TAG, "FORBIDDEN error received, triggering WebSocket reconnect with token refresh")
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
        AppLog.d(TAG, "Applying sync for $entityType")

        when (entityType) {
            Constants.SyncEntity.USERS -> applyUserSync(data, deletedIds)
            Constants.SyncEntity.DOCUMENTS -> applyDocumentSync(data, deletedIds)
            Constants.SyncEntity.PRODUCTS -> applyProductSync(data, deletedIds)
            Constants.SyncEntity.CLIENTS -> applyClientSync(data, deletedIds)
            Constants.SyncEntity.WAREHOUSES -> applyWarehouseSync(data, deletedIds)
            Constants.SyncEntity.BOXES -> applyBoxSync(data, deletedIds)
            Constants.SyncEntity.DOCUMENT_BOXES -> applyDocumentBoxSync(data, deletedIds)
        }
    }

    private suspend fun applyUserSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<UserDto>>() {}.type
            val users: List<UserDto> = gson.fromJson(data, type)

            AppLog.i(TAG, "Sync users: ${users.size} upsert, ${deletedIds?.size ?: 0} delete")
            users.forEach { dto ->
                // Preserve existing passwordHash if user already exists locally
                val existingUser = userDao.getUserById(dto.id)
                val entity = dto.toEntityForSync(existingUser?.passwordHash, existingUser?.operatingMode)
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
        if (isFullSyncActive) {
            AppLog.d(TAG, "Full sync: clearing all non-dirty documents and their lines")
            documentLineDao.deleteAllNonDirtyDocumentLines()
            documentDao.deleteAllNonDirtyDocuments()
        }

        if (data.isJsonArray) {
            val type = object : TypeToken<List<DocumentDto>>() {}.type
            val documents: List<DocumentDto> = gson.fromJson(data, type)

            AppLog.i(TAG, "Sync documents: ${documents.size} upsert, ${deletedIds?.size ?: 0} delete")
            documents.forEach { dto ->
                // Skip overwriting locally dirty documents — server will get our version when uploaded
                val existing = documentDao.getDocumentById(dto.id)
                if (existing != null && existing.isDirty) {
                    AppLog.w(TAG, "Skipping server upsert for dirty document: ${dto.id}")
                    return@forEach
                }

                val entity = documentMapper.toEntity(dto)
                documentDao.upsertDocument(entity)

                // Save lines if present
                dto.lines?.forEach { lineDto ->
                    val lineEntity = documentMapper.toLineEntity(lineDto)
                    documentLineDao.upsertLine(lineEntity)
                }

                // Recalculate totalPlanned from lines
                val totalPlanned = documentLineDao.getTotalPlannedQuantity(dto.id) ?: 0.0
                if (totalPlanned != entity.totalPlanned) {
                    documentDao.updateTotalPlanned(dto.id, totalPlanned, System.currentTimeMillis())
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

            AppLog.i(TAG, "Sync products: ${products.size} upsert, ${deletedIds?.size ?: 0} delete")

            // Log raw JSON of first product to debug barcode format
            if (data.asJsonArray.size() > 0) {
                val firstRaw = data.asJsonArray[0]
                AppLog.d(TAG, "Product sync sample raw JSON: $firstRaw")
            }

            val rawArray = data.asJsonArray

            products.forEachIndexed { index, dto ->
                val entity = productMapper.toEntity(dto)
                productDao.upsertProduct(entity)

                // Extract barcodes — handle both object array and string array formats.
                // The server may send: [{barcode:"...", ...}] or ["barcode1","barcode2"]
                val rawProduct = rawArray[index].asJsonObject
                val rawBarcodes = rawProduct.get("barcodes")

                val barcodeEntities: List<ProductBarcodeEntity>? = when {
                    dto.barcodes != null -> {
                        // Gson parsed successfully as List<BarcodeDto>
                        productMapper.toBarcodeEntityList(dto)
                    }
                    rawBarcodes != null && rawBarcodes.isJsonArray -> {
                        // Gson couldn't parse — try manual extraction (string array format)
                        val arr = rawBarcodes.asJsonArray
                        arr.mapIndexedNotNull { i, element ->
                            val barcodeValue = when {
                                element.isJsonPrimitive -> element.asString
                                element.isJsonObject -> element.asJsonObject.get("barcode")?.asString
                                else -> null
                            }
                            barcodeValue?.let { bc ->
                                ProductBarcodeEntity(
                                    id = "${dto.id}_$bc",
                                    productId = dto.id,
                                    barcode = bc,
                                    type = "UNKNOWN",
                                    isPrimary = i == 0
                                )
                            }
                        }
                    }
                    else -> null // No barcodes in this payload — preserve existing
                }

                AppLog.d(TAG, "Product ${dto.code}: barcodes=${barcodeEntities?.size ?: "null (preserved)"}")

                if (barcodeEntities != null) {
                    productDao.deleteBarcodesForProduct(dto.id)
                    barcodeEntities.forEach { barcode ->
                        productDao.insertBarcode(barcode)
                    }
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

            AppLog.i(TAG, "Sync clients: ${clients.size} upsert, ${deletedIds?.size ?: 0} delete")
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

            AppLog.i(TAG, "Sync warehouses: ${warehouses.size} upsert, ${deletedIds?.size ?: 0} delete")
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

    private suspend fun applyBoxSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<BoxDto>>() {}.type
            val boxes: List<BoxDto> = gson.fromJson(data, type)

            AppLog.i(TAG, "Sync boxes: ${boxes.size} upsert, ${deletedIds?.size ?: 0} delete")
            boxDao.insertBoxes(boxes.map { it.toEntity() })
        }

        deletedIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            boxDao.deleteBoxesByIds(ids)
        }
    }

    private suspend fun applyDocumentBoxSync(
        data: com.google.gson.JsonElement,
        deletedIds: List<String>?
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<DocumentBoxDto>>() {}.type
            val documentBoxes: List<DocumentBoxDto> = gson.fromJson(data, type)

            AppLog.i(TAG, "Sync document_boxes: ${documentBoxes.size} upsert, ${deletedIds?.size ?: 0} delete")
            documentBoxDao.insertDocumentBoxes(documentBoxes.map { it.toEntity() })
        }

        deletedIds?.takeIf { it.isNotEmpty() }?.let { ids ->
            documentBoxDao.deleteDocumentBoxesByIds(ids)
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
        AppLog.d(TAG, "Network available, connecting WebSocket")
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
            AppLog.d(TAG, "Cannot process pending operations - WebSocket not connected")
            return
        }

        if (!webSocketManager.isUserAuthenticated()) {
            AppLog.d(TAG, "Cannot process pending operations - user not authenticated")
            return
        }

        val pendingOps = outgoingOperationRepository.getAllRetryableOperations()
        if (pendingOps.isEmpty()) {
            AppLog.d(TAG, "No pending operations to process")
            return
        }

        AppLog.d(TAG, "Processing ${pendingOps.size} pending operations")

        for (operation in pendingOps) {
            if (!webSocketManager.isConnected()) {
                AppLog.w(TAG, "WebSocket disconnected during pending operations processing, stopping")
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
            AppLog.w(TAG, "Failed to build message for operation: ${operation.id}")
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
                            response.documentId, "COLLECTING", System.currentTimeMillis()
                        )
                        response.lockedBy?.let { userId ->
                            documentDao.updateAssignedUser(response.documentId, userId, System.currentTimeMillis())
                        }
                        outgoingOperationRepository.markOperationCompleted(operation.id)
                        AppLog.d(TAG, "Queued DOCUMENT_LOCK succeeded for ${operation.entityId}")
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id, response.error ?: "Server rejected lock"
                        )
                        AppLog.w(TAG, "Queued DOCUMENT_LOCK rejected for ${operation.entityId}: ${response.error}")
                    }
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Lock request timeout")
                    AppLog.w(TAG, "Queued DOCUMENT_LOCK timeout for ${operation.entityId}")
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
                            response.state ?: "COLLECTED",
                            System.currentTimeMillis()
                        )
                        response.version?.let { version ->
                            documentDao.updateDocumentVersion(response.documentId, version.toInt())
                        }
                        outgoingOperationRepository.markOperationCompleted(operation.id)
                        AppLog.d(TAG, "Queued DOCUMENT_COMPLETE succeeded for ${operation.entityId}")
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id, response.error ?: "Server rejected complete"
                        )
                        AppLog.w(TAG, "Queued DOCUMENT_COMPLETE rejected for ${operation.entityId}: ${response.error}")
                    }
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Complete request timeout")
                    AppLog.w(TAG, "Queued DOCUMENT_COMPLETE timeout for ${operation.entityId}")
                }
            }
            else -> {
                // Fire-and-forget for DOCUMENT_UPDATE, DOCUMENT_UNLOCK, etc.
                val sent = webSocketManager.sendMessage(message)
                if (sent) {
                    outgoingOperationRepository.markOperationCompleted(operation.id)
                    AppLog.d(TAG, "Pending operation sent: ${operation.operationType} for ${operation.entityId}")
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Failed to send via WebSocket")
                    AppLog.w(TAG, "Failed to send pending operation: ${operation.id}")
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
                        AppLog.e(TAG, "Missing document_id in DOCUMENT_LOCK payload")
                        return null
                    }
                    SyncMessage.DocumentLock(id = id, timestamp = timestamp, documentId = documentId)
                }
                OperationType.DOCUMENT_UNLOCK -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    if (documentId == null) {
                        AppLog.e(TAG, "Missing document_id in DOCUMENT_UNLOCK payload")
                        return null
                    }
                    SyncMessage.DocumentUnlock(id = id, timestamp = timestamp, documentId = documentId)
                }
                OperationType.DOCUMENT_UPDATE -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    val state = payloadJson.get("state")?.asString
                    val linesJson = payloadJson.get("lines")?.asJsonArray
                    if (documentId == null || state == null || linesJson == null) {
                        AppLog.e(TAG, "Missing required fields in DOCUMENT_UPDATE payload (documentId=$documentId, state=$state, lines=${linesJson != null})")
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
                        AppLog.e(TAG, "Missing document_id in DOCUMENT_COMPLETE payload")
                        return null
                    }
                    SyncMessage.DocumentComplete(id = id, timestamp = timestamp, documentId = documentId)
                }
                else -> {
                    AppLog.w(TAG, "Unsupported operation type for offline queue: ${operation.operationType}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Error building message from operation: ${e.message}", e)
            null
        }
    }

}
