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
data class StageLockResult(
    val success: Boolean,
    val documentId: String,
    val stage: String,
    val lockedBy: String? = null,
    val error: String? = null
)

/**
 * Result of stage complete operation
 */
data class StageCompleteResult(
    val success: Boolean,
    val documentId: String,
    val stage: String,
    val state: String? = null,
    val version: Long? = null,
    val error: String? = null
)

/**
 * Thrown when the server reports the document no longer exists during an
 * operation we expected to complete. Distinguishes a transient/timeout error
 * from a permanent one so the UI can navigate the user away instead of
 * looping retries against a phantom document.
 */
class DocumentMissingOnServerException(val documentId: String, message: String) : Exception(message)

private fun isDocumentMissingError(error: String?): Boolean =
    error != null && error.contains("document not found", ignoreCase = true)

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
    private val debugJournal: ua.com.programmer.pick.data.debug.DebugJournal,
    private val debugJournalUploader: ua.com.programmer.pick.data.debug.DebugJournalUploader,
    private val networkMonitor: NetworkMonitor,
    private val documentMapper: DocumentMapper,
    private val productMapper: ProductMapper,
    private val clientMapper: ClientMapper,
    private val warehouseMapper: WarehouseMapper,
    private val gson: Gson,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "SyncOrchestrator"
        private const val SYNC_TIMEOUT_MS = 60_000L
        private const val MAX_QUEUE_RETRIES = 5
        private const val DOCUMENT_PRODUCTS_TIMEOUT_MS = 10_000L
        private const val IN_PROCESS_FLUSH_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncTimeoutJob: Job? = null
    private val syncReceivedCounts = mutableMapOf<String, Int>()
    private val documentSyncJobs = mutableMapOf<String, Job>()


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

                        // Save available document types from server
                        authState.availableDocumentTypes?.let { types ->
                            val json = gson.toJson(types)
                            appPreferences.setAvailableDocumentTypes(json)
                        }

                        // Push local dirty state BEFORE requesting server state.
                        // Otherwise a stale SYNC_DATA response can race ahead of
                        // resyncDirtyDocuments and clobber in-flight edits via
                        // applyDocumentSync's line merge. The merge itself now
                        // preserves dirty rows, but pushing first narrows the
                        // window further and avoids needless overwrite churn.
                        processPendingOperations()
                        requestDeltaSync()

                        // Best-effort: flush any queued debug journal events.
                        // Read the flag fresh from DataStore (not the StateFlow)
                        // because UserRepositoryImpl persists the new flag value
                        // on the same login coroutine; the StateFlow may not
                        // have caught up by the time this collector fires.
                        scope.launch {
                            try {
                                val enabled = appPreferences.debugJournalEnabled.first()
                                if (enabled) debugJournalUploader.flush()
                            } catch (_: Exception) {}
                        }

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

        // Flush whenever the debug-journal flag is (or becomes) true. StateFlow
        // dedups so this only fires on initial collection and on each
        // false→true transition — covering the case where the server flips the
        // flag mid-session via PONG and we want to ship buffered events
        // immediately, without waiting for the 60s tick below.
        debugJournal.enabled
            .onEach { enabled ->
                if (enabled) {
                    AppLog.i(TAG, "debug-journal enabled observed → flushing")
                    try { debugJournalUploader.flush() } catch (_: Exception) {}
                }
            }
            .launchIn(scope)

        // Periodic in-process flush. Survives OEM task killing on Xiaomi /
        // Huawei / Oppo / Vivo etc. that suppress WorkManager periodic jobs —
        // as long as the app process is alive (which is the only time events
        // are being produced anyway), this loop keeps the journal flowing.
        // The WorkManager DebugJournalWorker remains as a safety net for cases
        // where the process is short-lived.
        scope.launch {
            while (true) {
                delay(IN_PROCESS_FLUSH_INTERVAL_MS)
                try { debugJournalUploader.flush() } catch (_: Exception) {}
            }
        }
    }

    private fun connectWebSocket() {
        if (networkMonitor.isCurrentlyConnected()) {
            webSocketManager.connect()
        }
    }

    private fun disconnectWebSocket() {
        webSocketManager.disconnect()
    }

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
    suspend fun requestDocumentListRefresh(documentType: String? = null): Result<Unit> {
        AppLog.d(TAG, "Requesting document list refresh (type=$documentType)")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }
        if (!webSocketManager.isUserAuthenticated()) {
            return Result.Error(Exception("User not authenticated"))
        }

        val message = SyncMessage.DocumentListRefresh(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentType = documentType
        )

        val sent = webSocketManager.sendMessage(message)
        if (!sent) {
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

        val externalId = toExternalDocumentId(documentId)
        val message = SyncMessage.DocumentProducts(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId
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

    /**
     * Add a box to a document during the PACK stage. Translates the Room document
     * id to its ERP external_id so callers stay in domain terms.
     *
     * On success the server returns the full DocumentBox DTO; it is upserted
     * locally with cross-reference translation so the observing UI reflects the
     * new box without waiting for the next delta tick.
     */
    suspend fun sendBoxAdd(
        documentRoomId: String,
        barcode: String,
        weight: Int
    ): SyncMessage.BoxAddResult? {
        val externalId = toExternalDocumentId(documentRoomId)
        val message = SyncMessage.BoxAdd(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            barcode = barcode,
            weight = weight
        )
        val result = webSocketManager.sendAndAwait(
            message,
            SyncMessage.BoxAddResult::class.java
        )
        if (result?.success == true && result.box != null) {
            val dto: DocumentBoxDto = gson.fromJson(result.box, DocumentBoxDto::class.java)
            // Translate ERP external_ids back to local row ids (same rules as
            // batch sync ingest), then upsert so the observing UI reflects the
            // new box before the next delta tick.
            val boxIdMap = resolveExternalBoxIds(listOf(dto))
            val userIdMap = resolveExternalUserIdsForBoxes(listOf(dto))
            val entity = dto.toEntity(documentRoomId).let { e ->
                e.copy(
                    boxId = boxIdMap[e.boxId] ?: e.boxId,
                    packedBy = e.packedBy?.let { userIdMap[it] ?: it },
                    pickedUpBy = e.pickedUpBy?.let { userIdMap[it] ?: it },
                    deliveredBy = e.deliveredBy?.let { userIdMap[it] ?: it },
                )
            }
            documentBoxDao.insertDocumentBox(entity)
            AppLog.d(TAG, "BOX_ADD success, upserted box_number=${dto.boxNumber} locally")
        }
        return result
    }

    /**
     * Remove a previously-added box while the document is still in PACKING.
     * The server identifies the box by its in-document box_number.
     */
    suspend fun sendBoxRemove(
        documentRoomId: String,
        boxNumber: Int
    ): SyncMessage.BoxRemoveResult? {
        val externalId = toExternalDocumentId(documentRoomId)
        val message = SyncMessage.BoxRemove(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            boxNumber = boxNumber
        )
        val result = webSocketManager.sendAndAwait(
            message,
            SyncMessage.BoxRemoveResult::class.java
        )
        if (result?.success == true) {
            documentBoxDao.deleteDocumentBox(documentRoomId, boxNumber)
        }
        return result
    }

    /**
     * Server-side catalog fallback for an unknown barcode. Caches the returned
     * BoxDto locally so the next scan resolves in the offline cache.
     */
    suspend fun sendBoxLookup(barcode: String): SyncMessage.BoxLookupResult? {
        val message = SyncMessage.BoxLookup(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            barcode = barcode
        )
        val result = webSocketManager.sendAndAwait(
            message,
            SyncMessage.BoxLookupResult::class.java
        )
        if (result?.success == true && result.box != null) {
            val dto: BoxDto = gson.fromJson(result.box, BoxDto::class.java)
            boxDao.insertBox(dto.toEntity())
        }
        return result
    }

    // ============================================
    // Document Operations
    // ============================================

    /**
     * Lock a document for editing ("Take into work")
     */
    suspend fun lockForStage(documentId: String, stage: String): Result<StageLockResult> {
        AppLog.d(TAG, "Locking document $documentId for stage: $stage")

        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("Not connected to server"))
        }
        if (!webSocketManager.isUserAuthenticated()) {
            return Result.Error(Exception("User not authenticated"))
        }

        val externalId = toExternalDocumentId(documentId)
        val message = SyncMessage.StageLock(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            stage = stage
        )

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_LOCK_SENT,
            message = "stage lock sent",
            documentId = documentId,
            stage = stage
        )

        val response = webSocketManager.sendAndAwait(
            message,
            SyncMessage.StageLockResult::class.java
        )

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_LOCK_RESULT,
            message = if (response == null) "no response (timeout)" else "success=${response.success} lockedBy=${response.lockedBy}",
            documentId = documentId,
            stage = stage,
            severity = if (response?.success == true) ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO
            else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
            payload = response?.let { mapOf("success" to it.success, "locked_by" to it.lockedBy, "error" to it.error) }
        )

        return if (response != null) {
            val result = StageLockResult(
                success = response.success,
                documentId = response.documentId,
                stage = response.stage,
                lockedBy = response.lockedBy,
                error = response.error
            )

            AppLog.i(TAG, "LOCK_TRACE response: reqDocId=$documentId respDocId=${response.documentId} success=${response.success} lockedBy=${response.lockedBy} error=${response.error}")

            // Trust the server: if success is true, the lock is ours and the
            // state has advanced. No local "is this mine?" logic — per the
            // server-driven architecture rule in CLAUDE.md, authorization
            // decisions live on the server. The lockedBy field is kept for
            // diagnostic logging and error messages only.
            if (response.success) {
                documentDao.updateDocumentStateFromServer(documentId, stageInProcessState(stage), System.currentTimeMillis())
            }
            response.lockedBy?.let { userId ->
                documentDao.updateAssignedUser(documentId, userId, System.currentTimeMillis())
            }

            // Read back from DB to verify what was actually persisted. If this
            // mismatches response.lockedBy, the bug is in the DB write path.
            val persisted = documentDao.getDocumentById(documentId)
            AppLog.i(TAG, "LOCK_TRACE persisted: docId=$documentId state=${persisted?.state} assignedUserId=${persisted?.assignedUserId}")

            Result.Success(result)
        } else {
            Result.Error(Exception("Lock request timeout"))
        }
    }

    /**
     * Unlock a document from its current stage
     */
    suspend fun unlockFromStage(documentId: String, stage: String): Result<Unit> {
        AppLog.d(TAG, "Unlocking document $documentId from stage: $stage")

        val externalId = toExternalDocumentId(documentId)

        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected, queueing unlock operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.STAGE_UNLOCK,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to externalId, "stage" to stage))
            )
            return Result.Success(Unit)
        }

        val message = SyncMessage.StageUnlock(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            stage = stage
        )

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_UNLOCK_SENT,
            message = "stage unlock sent",
            documentId = documentId,
            stage = stage
        )

        val sent = webSocketManager.sendMessage(message)
        return if (sent) {
            documentDao.updateDocumentStateFromServer(documentId, stageStartState(stage), System.currentTimeMillis())
            documentDao.clearAssignedUser(documentId)
            Result.Success(Unit)
        } else {
            Result.Error(Exception("Failed to send unlock request"))
        }
    }

    private fun stageInProcessState(stage: String): String = when (stage) {
        "collect" -> "COLLECTING"
        "pack" -> "PACKING"
        "deliver" -> "DELIVERING"
        else -> "COLLECTING"
    }

    private fun stageStartState(stage: String): String = when (stage) {
        "collect" -> "LOADED"
        "pack" -> "PACK"
        "deliver" -> "DELIVERY"
        else -> "LOADED"
    }

    /**
     * Schedule a debounced document sync in application scope.
     * Reads dirty lines from Room DB and sends them to the server.
     * Safe to call from any scope — survives ViewModel destruction.
     */
    fun scheduleDocumentSync(documentId: String) {
        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.SYNC_SCHEDULED,
            message = "debounced sync scheduled",
            documentId = documentId
        )
        documentSyncJobs[documentId]?.cancel()
        documentSyncJobs[documentId] = scope.launch {
            try {
                delay(500L)
                performDocumentSync(documentId)
            } finally {
                documentSyncJobs.remove(documentId)
            }
        }
    }

    /**
     * Cancel any pending debounced sync for this document and run the sync
     * immediately, suspending until it completes. Used before stage completion
     * so that the latest line edits are guaranteed to reach the server before
     * STAGE_COMPLETE is sent.
     */
    suspend fun flushDocumentSync(documentId: String) {
        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.SYNC_FLUSHED,
            message = "flushing pending sync before completion",
            documentId = documentId
        )
        val pending = documentSyncJobs.remove(documentId)
        if (pending != null) {
            pending.cancel()
            try { pending.join() } catch (_: Exception) {}
        }
        performDocumentSync(documentId)
    }

    private suspend fun performDocumentSync(documentId: String) {
        val doc = documentDao.getDocumentById(documentId) ?: return
        val lineEntities = documentLineDao.getLinesByDocumentIdSync(documentId)
        if (lineEntities.isEmpty()) return

        val lines = lineEntities.map { line ->
            DocumentLineUpdate(
                lineNumber = line.lineNumber,
                actualQuantity = line.actualQuantity,
                batchNumber = line.batchNumber,
                isCompleted = line.isCompleted
            )
        }

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.SYNC_FIRED,
            message = "performing document sync",
            documentId = documentId,
            payload = mapOf(
                "line_count" to lines.size,
                "total_actual_qty" to lines.sumOf { it.actualQuantity },
                "state" to doc.state
            )
        )

        try {
            updateDocument(documentId, doc.state, lines)
        } catch (e: Exception) {
            AppLog.w(TAG, "Document sync failed for $documentId: ${e.message}")
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

        val externalId = toExternalDocumentId(documentId)

        // Offline / send-failure path: do NOT queue a stored snapshot. Each
        // queued DOCUMENT_UPDATE used to carry a frozen copy of all lines; when
        // connectivity came back, processPendingOperations replayed them in
        // order, so an early snapshot taken before any scans (total_actual = 0)
        // would overwrite the server's current totals before the fresh snapshot
        // arrived. Document/line is_dirty flags already drive
        // resyncDirtyDocuments(), which reads the current Room state — that
        // path is authoritative and always up-to-date, so the queue is
        // redundant for this operation type.
        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected; relying on dirty-flag re-sync for document: $documentId")
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_UPDATE_QUEUED,
                message = "WS offline; deferred to dirty re-sync",
                documentId = documentId,
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                payload = mapOf("line_count" to lines.size)
            )
            return Result.Error(Exception("WebSocket not connected, deferred to dirty re-sync"))
        }

        val message = SyncMessage.DocumentUpdate(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            state = state,
            lines = lines
        )

        val sent = webSocketManager.sendMessage(message)
        return if (sent) {
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_UPDATE_SENT,
                message = "document update sent",
                documentId = documentId,
                payload = mapOf(
                    "line_count" to lines.size,
                    "total_actual_qty" to lines.sumOf { it.actualQuantity },
                    "state" to state,
                    "message_id" to message.id
                )
            )
            Result.Success(Unit)
        } else {
            AppLog.w(TAG, "WebSocket send failed for document $documentId; deferred to dirty re-sync")
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_UPDATE_QUEUED,
                message = "WS send failed; deferred to dirty re-sync",
                documentId = documentId,
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                payload = mapOf("line_count" to lines.size)
            )
            Result.Error(Exception("WebSocket send failed, deferred to dirty re-sync"))
        }
    }

    /**
     * Complete a stage for a document
     */
    suspend fun completeStage(documentId: String, stage: String): Result<StageCompleteResult> {
        AppLog.d(TAG, "Completing stage $stage for document: $documentId")

        // Flush any pending debounced line updates so the server receives the
        // latest collected quantities BEFORE we mark the stage complete.
        // Without this, completing within the 500ms debounce window would
        // race against (and lose) the most recent line edits.
        flushDocumentSync(documentId)

        val externalId = toExternalDocumentId(documentId)

        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected, queueing complete operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.STAGE_COMPLETE,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to externalId, "stage" to stage))
            )
            return Result.Error(Exception("WebSocket not connected, operation queued"))
        }

        val message = SyncMessage.StageComplete(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            stage = stage
        )

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_COMPLETE_SENT,
            message = "stage complete sent",
            documentId = documentId,
            stage = stage,
            payload = mapOf("message_id" to message.id)
        )

        val response = webSocketManager.sendAndAwait(
            message,
            SyncMessage.StageCompleteResult::class.java
        )

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_COMPLETE_RESULT,
            message = if (response == null) "no response (timeout)" else "success=${response.success} state=${response.state}",
            documentId = documentId,
            stage = stage,
            severity = if (response?.success == true) ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO
            else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR,
            payload = response?.let { mapOf("success" to it.success, "state" to it.state, "error" to it.error) }
        )

        if (response == null) {
            return Result.Error(Exception("Complete request timeout"))
        }

        val result = StageCompleteResult(
            success = response.success,
            documentId = response.documentId,
            stage = response.stage,
            state = response.state,
            version = response.version,
            error = response.error
        )

        if (response.success) {
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_DELETED_LOCAL,
                message = "local document purged after completion",
                documentId = documentId,
                stage = stage
            )
            // Remove the completed document locally — the server no longer
            // includes it in this user's document set after stage completion.
            documentLineDao.deleteLinesByDocumentId(documentId)
            documentDao.deleteDocument(documentId)

            // Refresh document list to get the next document from server
            scope.launch {
                requestDocumentListRefresh()
            }

            return Result.Success(result)
        }

        // Server explicitly rejected the completion. The previous code returned
        // Result.Success here, so callers (ViewModel) showed a "completed" toast
        // even on rejection — see incident with doc 01-10.04.26. Always surface
        // a rejection as Result.Error.
        if (isDocumentMissingError(response.error)) {
            // The document is gone on the server (e.g. ERP issued GONE while the
            // worker was offline). Stop the resync/retry loop by purging local
            // state and any queued operations bound to it; further retries can
            // never succeed and would just spam the journal.
            purgeMissingDocument(documentId, stage)
            return Result.Error(
                DocumentMissingOnServerException(documentId, response.error ?: "document not found")
            )
        }

        return Result.Error(Exception(response.error ?: "Server rejected complete"))
    }

    /**
     * Drop the local copy of a document the server has confirmed is missing,
     * along with any pending sync operations queued against it. Called when a
     * STAGE_COMPLETE (or queued retry of it) returns "document not found".
     */
    private suspend fun purgeMissingDocument(documentId: String, stage: String) {
        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_DELETED_LOCAL,
            message = "local document purged: server reports it as missing",
            documentId = documentId,
            stage = stage,
            severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
        )
        documentLineDao.deleteLinesByDocumentId(documentId)
        documentDao.deleteDocument(documentId)
        outgoingOperationRepository.deletePendingOperationsForEntity(documentId)
        scope.launch {
            requestDocumentListRefresh()
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
                is SyncMessage.StageLockResult -> {
                    handleStageLockResult(message)
                }
                is SyncMessage.StageCompleteResult -> {
                    handleStageCompleteResult(message)
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
        AppLog.i(TAG, "SYNC_DATA entity=${message.entityType} upsert=$itemCount delete=$deletedCount fullSet=${message.fullSet}")

        try {
            applySync(message.entityType, message.data, message.deletedIds, message.fullSet)
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

    private suspend fun handleStageLockResult(message: SyncMessage.StageLockResult) {
        AppLog.d(TAG, "Stage lock result: ${message.documentId}, stage: ${message.stage}, success: ${message.success}")

        if (message.success) {
            val roomId = toRoomDocumentId(message.documentId)
            documentDao.updateDocumentStateFromServer(roomId, stageInProcessState(message.stage), System.currentTimeMillis())
            message.lockedBy?.let { userId ->
                documentDao.updateAssignedUser(roomId, userId, System.currentTimeMillis())
            }
        }
    }

    private suspend fun handleStageCompleteResult(message: SyncMessage.StageCompleteResult) {
        AppLog.d(TAG, "Stage complete result: ${message.documentId}, stage: ${message.stage}, success: ${message.success}")

        if (message.success) {
            val state = message.state ?: return
            val roomId = toRoomDocumentId(message.documentId)
            documentDao.updateDocumentStateFromServer(roomId, state, System.currentTimeMillis())
            message.version?.let { version ->
                documentDao.updateDocumentVersion(roomId, version.toInt())
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
        deletedIds: List<String>?,
        fullSet: Boolean = false
    ) {
        AppLog.d(TAG, "Applying sync for $entityType")

        when (entityType) {
            Constants.SyncEntity.USERS -> applyUserSync(data, deletedIds)
            Constants.SyncEntity.DOCUMENTS -> applyDocumentSync(data, deletedIds, fullSet)
            Constants.SyncEntity.PRODUCTS -> applyProductSync(data, deletedIds)
            Constants.SyncEntity.CLIENTS -> applyClientSync(data, deletedIds)
            Constants.SyncEntity.WAREHOUSES -> applyWarehouseSync(data, deletedIds)
            Constants.SyncEntity.BOXES -> applyBoxSync(data, deletedIds)
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
        deletedIds: List<String>?,
        fullSet: Boolean
    ) {
        if (!data.isJsonArray) return

        val type = object : TypeToken<List<DocumentDto>>() {}.type
        val documents: List<DocumentDto> = gson.fromJson(data, type)
        val receivedIds = documents.map { it.id }.toSet()

        AppLog.i(TAG, "Sync documents: ${documents.size} upsert, ${deletedIds?.size ?: 0} delete, fullSet=$fullSet")

        // Purge only when the server marks this payload as the full authoritative
        // set for the current filter (e.g. DOCUMENT_LIST_REFRESH). Delta sync
        // payloads must NOT purge — they only carry rows modified since the
        // client's last cursor, and purging non-received docs would silently
        // drop unchanged ones. See SyncDataPayload.full_set on the server.
        if (fullSet) {
            if (receivedIds.isEmpty()) {
                documentDao.deleteAllNonDirtyDocuments()
            } else {
                documentDao.deleteNonDirtyDocumentsNotIn(receivedIds.toList())
            }
        }

        // v2 backend: DocumentLine.product_id arrives as an ERP external_id.
        // We still translate product ids back to the local internal id so
        // ProductImageDao joins continue to work. Document.assigned_user_id
        // is NOT translated — per the server-driven architecture rule, the
        // app does not make authorization decisions locally, so whatever
        // string the server sends in assigned_user_id is stored as-is and
        // only used for display. See CLAUDE.md "Server-Driven Architecture".
        val productIdMap = resolveExternalProductIds(documents)

        // Boxes ride inside each DocumentDto. Resolve master-box and user
        // external_ids once across the whole batch so translation is a
        // map lookup per row.
        val allBoxes = documents.flatMap { it.boxes ?: emptyList() }
        val boxIdMap = resolveExternalBoxIds(allBoxes)
        val userIdMap = resolveExternalUserIdsForBoxes(allBoxes)

        documents.forEach { dto ->
            val existing = documentDao.getDocumentById(dto.id)
            if (existing != null && existing.isDirty) {
                // Server is authoritative for state transitions (e.g., lock released → LOADED).
                // If the server version is newer, accept the state change and clear the dirty flag.
                if (dto.version > existing.version) {
                    AppLog.i(TAG, "Server version ${dto.version} > local ${existing.version} for dirty document ${dto.id}, accepting server state")
                    documentDao.upsertDocument(documentMapper.toEntity(dto))
                    if (dto.lines != null) {
                        mergeDocumentLines(dto.id, dto.lines, productIdMap)
                    }
                    if (dto.boxes != null) {
                        val boxEntities = dto.boxes.map { boxDto ->
                            val e = boxDto.toEntity(dto.id)
                            e.copy(
                                boxId = boxIdMap[e.boxId] ?: e.boxId,
                                packedBy = e.packedBy?.let { userIdMap[it] ?: it },
                                pickedUpBy = e.pickedUpBy?.let { userIdMap[it] ?: it },
                                deliveredBy = e.deliveredBy?.let { userIdMap[it] ?: it },
                            )
                        }
                        if (boxEntities.isEmpty()) {
                            documentBoxDao.deleteBoxesByDocumentId(dto.id)
                        } else {
                            documentBoxDao.deleteBoxesNotIn(dto.id, boxEntities.map { it.boxNumber })
                            documentBoxDao.insertDocumentBoxes(boxEntities)
                        }
                    }
                    // Recalculate totals from synced lines
                    val totalPlanned = documentLineDao.getTotalPlannedQuantity(dto.id) ?: 0.0
                    val totalActual = documentLineDao.getTotalActualQuantity(dto.id) ?: 0.0
                    documentDao.updateTotalPlanned(dto.id, totalPlanned, System.currentTimeMillis())
                    documentDao.updateTotalActual(dto.id, totalActual, System.currentTimeMillis())
                } else {
                    AppLog.w(TAG, "Skipping server upsert for dirty document: ${dto.id}")
                }
                return@forEach
            }

            val entity = documentMapper.toEntity(dto)
            documentDao.upsertDocument(entity)

            // Replace lines — server sends the complete set. The merge preserves
            // any locally-dirty lines so an in-flight edit isn't overwritten by a
            // stale server echo while a push is still outstanding.
            if (dto.lines != null) {
                mergeDocumentLines(dto.id, dto.lines, productIdMap)
            }

            // Replace boxes — server sends the complete set.
            if (dto.boxes != null) {
                val boxEntities = dto.boxes.map { boxDto ->
                    val e = boxDto.toEntity(dto.id)
                    e.copy(
                        boxId = boxIdMap[e.boxId] ?: e.boxId,
                        packedBy = e.packedBy?.let { userIdMap[it] ?: it },
                        pickedUpBy = e.pickedUpBy?.let { userIdMap[it] ?: it },
                        deliveredBy = e.deliveredBy?.let { userIdMap[it] ?: it },
                    )
                }
                if (boxEntities.isEmpty()) {
                    documentBoxDao.deleteBoxesByDocumentId(dto.id)
                } else {
                    documentBoxDao.deleteBoxesNotIn(dto.id, boxEntities.map { it.boxNumber })
                    documentBoxDao.insertDocumentBoxes(boxEntities)
                }
            }

            // Recalculate totals from lines
            val totalPlanned = documentLineDao.getTotalPlannedQuantity(dto.id) ?: 0.0
            if (totalPlanned != entity.totalPlanned) {
                documentDao.updateTotalPlanned(dto.id, totalPlanned, System.currentTimeMillis())
            }
            val totalActual = documentLineDao.getTotalActualQuantity(dto.id) ?: 0.0
            if (totalActual != entity.totalActual) {
                documentDao.updateTotalActual(dto.id, totalActual, System.currentTimeMillis())
            }
        }

        deletedIds?.forEach { id ->
            documentDao.deleteDocument(id)
        }
    }

    // ============================================
    // v2 → v1 ID translation (sync ingest boundary)
    // ============================================
    //
    // The v2 backend emits ERP external_ids in cross-reference fields where
    // the v1 backend used to emit Mongo ObjectID hex. We translate only
    // product references back to local internal IDs so ProductImageDao
    // joins continue to work. Other cross-references (assigned_user_id,
    // warehouse_id, client_id) are stored as whatever the server sent and
    // used only for display — per CLAUDE.md "Server-Driven Architecture",
    // the app does not make authorization decisions locally and therefore
    // has no need to reconcile them against any local identity space.
    //
    // The lookup misses harmlessly when the server still sends ObjectID hex
    // (old backend), so a single binary works against both wire formats.

    // Document id translation layer for the outbound wire format. ViewModels
    // and navigation deal exclusively in Room primary keys (DocumentEntity.id).
    // The sync boundary converts those to ERP external_ids before sending a
    // message, and converts server echoes (StageLockResult.documentId etc.)
    // back to Room ids before mutating the local DB. Fallbacks handle the
    // transitional case where either side still uses the ObjectID-hex form.

    private suspend fun toExternalDocumentId(roomDocumentId: String): String {
        val doc = documentDao.getDocumentById(roomDocumentId)
        return doc?.externalId?.takeIf { it.isNotEmpty() } ?: roomDocumentId
    }

    private suspend fun toRoomDocumentId(incoming: String): String {
        return documentDao.getDocumentByExternalId(incoming)?.id ?: incoming
    }

    /** Collect line.product_id values across the batch and resolve them by external_id. */
    private suspend fun resolveExternalProductIds(documents: List<DocumentDto>): Map<String, String> {
        val externalIds = documents
            .asSequence()
            .flatMap { (it.lines ?: emptyList()).asSequence() }
            .mapNotNull { it.productId.takeIf { id -> id.isNotBlank() } }
            .toSet()
            .toList()
        if (externalIds.isEmpty()) return emptyMap()
        return productDao.findByExternalIds(externalIds)
            .mapNotNull { p -> p.externalId?.let { it to p.id } }
            .toMap()
    }

    private fun translateLineEntity(
        entity: ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity,
        productIdMap: Map<String, String>
    ): ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity {
        val translatedProduct = productIdMap[entity.productId] ?: entity.productId
        if (translatedProduct == entity.productId) return entity
        return entity.copy(productId = translatedProduct)
    }

    // Replacing a document's lines with a server payload used to be a blanket
    // deleteLinesByDocumentId + insertLines. That clobbered in-flight edits:
    // while a reconnect push was still propagating, any SYNC_DATA echo arriving
    // with stale server state would overwrite a dirty local qty AND reset
    // is_dirty to false, so the pending resyncDirtyDocuments() found nothing
    // to push and the edit was silently lost. Dirty rows are now preserved.
    private suspend fun mergeDocumentLines(
        documentId: String,
        serverLineDtos: List<ua.com.programmer.pick.data.remote.dto.DocumentLineDto>,
        productIdMap: Map<String, String>
    ) {
        val localById = documentLineDao.getLinesByDocumentIdSync(documentId)
            .associateBy { it.id }
        val serverEntities = serverLineDtos.map {
            translateLineEntity(documentMapper.toLineEntity(it), productIdMap)
        }
        val serverIds = serverEntities.map { it.id }.toSet()

        localById.values
            .filter { it.id !in serverIds && !it.isDirty }
            .forEach { documentLineDao.deleteLine(it.id) }

        val merged = serverEntities.map { server ->
            val local = localById[server.id]
            if (local != null && local.isDirty) {
                server.copy(
                    actualQuantity = local.actualQuantity,
                    isCompleted = local.isCompleted,
                    notes = local.notes ?: server.notes,
                    isDirty = true
                )
            } else server
        }
        documentLineDao.insertLines(merged)

        val preservedDirty = merged.count { it.isDirty }
        if (preservedDirty > 0) {
            AppLog.i(TAG, "Merge preserved $preservedDirty dirty line(s) for document $documentId")
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

    private suspend fun resolveExternalBoxIds(boxes: List<DocumentBoxDto>): Map<String, String> {
        val externalIds = boxes.mapNotNull { it.boxId.takeIf(String::isNotBlank) }.toSet().toList()
        if (externalIds.isEmpty()) return emptyMap()
        return boxDao.findByExternalIds(externalIds)
            .mapNotNull { b -> b.externalId?.let { it to b.id } }
            .toMap()
    }

    private suspend fun resolveExternalUserIdsForBoxes(boxes: List<DocumentBoxDto>): Map<String, String> {
        val externalIds = boxes
            .asSequence()
            .flatMap { sequenceOf(it.packedBy, it.pickedUpBy, it.deliveredBy) }
            .filterNotNull()
            .filter { it.isNotBlank() }
            .toSet()
            .toList()
        if (externalIds.isEmpty()) return emptyMap()
        return userDao.findByExternalIds(externalIds)
            .mapNotNull { u -> u.externalId?.let { it to u.id } }
            .toMap()
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

        // Re-push dirty documents that were saved locally but never synced
        // (e.g., debounce cancelled by navigation, fire-and-forget lost in transit)
        resyncDirtyDocuments()
    }

    /**
     * Find documents with dirty lines that may not have been synced to the server,
     * and re-send their line data.
     */
    private suspend fun resyncDirtyDocuments() {
        if (!webSocketManager.isConnected() || !webSocketManager.isUserAuthenticated()) return

        try {
            val dirtyDocuments = documentDao.getDirtyDocuments()
            if (dirtyDocuments.isEmpty()) return

            AppLog.d(TAG, "Re-syncing ${dirtyDocuments.size} dirty document(s)")

            for (doc in dirtyDocuments) {
                if (!webSocketManager.isConnected()) break

                val lineEntities = documentLineDao.getLinesByDocumentIdSync(doc.id)
                if (lineEntities.isEmpty()) continue

                val lines = lineEntities.map { line ->
                    DocumentLineUpdate(
                        lineNumber = line.lineNumber,
                        actualQuantity = line.actualQuantity,
                        batchNumber = line.batchNumber,
                        isCompleted = line.isCompleted
                    )
                }

                val message = SyncMessage.DocumentUpdate(
                    id = messageParser.generateMessageId(),
                    timestamp = messageParser.getCurrentTimestamp(),
                    documentId = doc.externalId?.takeIf { it.isNotEmpty() } ?: doc.id,
                    state = doc.state,
                    lines = lines
                )

                val sent = webSocketManager.sendMessage(message)
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.RESYNC_DIRTY,
                    message = if (sent) "re-synced dirty document" else "re-sync send failed",
                    documentId = doc.id,
                    severity = if (sent) ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO
                    else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                    payload = mapOf("line_count" to lines.size, "state" to doc.state)
                )
                if (sent) {
                    AppLog.d(TAG, "Re-synced dirty document: ${doc.id}")
                } else {
                    AppLog.w(TAG, "Failed to re-sync dirty document: ${doc.id}")
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Error during dirty document re-sync: ${e.message}")
        }
    }

    private suspend fun processOperation(operation: ua.com.programmer.pick.domain.repository.OutgoingOperation) {
        val message = buildMessageFromOperation(operation)
        if (message == null) {
            outgoingOperationRepository.markOperationFailed(operation.id, "Failed to build message from operation")
            AppLog.w(TAG, "Failed to build message for operation: ${operation.id}")
            return
        }

        when (operation.operationType) {
            OperationType.STAGE_LOCK -> {
                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.StageLockResult::class.java
                )
                if (response != null) {
                    if (response.success) {
                        documentDao.updateDocumentStateFromServer(
                            response.documentId, stageInProcessState(response.stage), System.currentTimeMillis()
                        )
                        response.lockedBy?.let { userId ->
                            documentDao.updateAssignedUser(response.documentId, userId, System.currentTimeMillis())
                        }
                        outgoingOperationRepository.markOperationCompleted(operation.id)
                        AppLog.d(TAG, "Queued STAGE_LOCK succeeded for ${operation.entityId}")
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id, response.error ?: "Server rejected lock"
                        )
                        AppLog.w(TAG, "Queued STAGE_LOCK rejected for ${operation.entityId}: ${response.error}")
                    }
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Lock request timeout")
                    AppLog.w(TAG, "Queued STAGE_LOCK timeout for ${operation.entityId}")
                }
            }
            OperationType.STAGE_COMPLETE -> {
                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.StageCompleteResult::class.java
                )
                if (response != null) {
                    if (response.success) {
                        response.state?.let { state ->
                            documentDao.updateDocumentStateFromServer(
                                response.documentId, state, System.currentTimeMillis()
                            )
                        }
                        response.version?.let { version ->
                            documentDao.updateDocumentVersion(response.documentId, version.toInt())
                        }
                        outgoingOperationRepository.markOperationCompleted(operation.id)
                        AppLog.d(TAG, "Queued STAGE_COMPLETE succeeded for ${operation.entityId}")
                    } else {
                        outgoingOperationRepository.markOperationFailed(
                            operation.id, response.error ?: "Server rejected complete"
                        )
                        AppLog.w(TAG, "Queued STAGE_COMPLETE rejected for ${operation.entityId}: ${response.error}")
                        if (isDocumentMissingError(response.error)) {
                            // Server says the document no longer exists — drop the
                            // local copy and any other queued ops for it, otherwise
                            // resyncDirtyDocuments keeps the loop alive.
                            purgeMissingDocument(operation.entityId, "")
                        }
                    }
                } else {
                    outgoingOperationRepository.markOperationFailed(operation.id, "Complete request timeout")
                    AppLog.w(TAG, "Queued STAGE_COMPLETE timeout for ${operation.entityId}")
                }
            }
            else -> {
                // Fire-and-forget for DOCUMENT_UNLOCK etc.
                // (DOCUMENT_UPDATE is no longer queued — buildMessageFromOperation
                // returns null for it, so it never reaches this branch.)
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
                OperationType.STAGE_LOCK -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    val stage = payloadJson.get("stage")?.asString
                    if (documentId == null || stage == null) {
                        AppLog.e(TAG, "Missing document_id or stage in STAGE_LOCK payload")
                        return null
                    }
                    SyncMessage.StageLock(id = id, timestamp = timestamp, documentId = documentId, stage = stage)
                }
                OperationType.STAGE_UNLOCK -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    val stage = payloadJson.get("stage")?.asString
                    if (documentId == null || stage == null) {
                        AppLog.e(TAG, "Missing document_id or stage in STAGE_UNLOCK payload")
                        return null
                    }
                    SyncMessage.StageUnlock(id = id, timestamp = timestamp, documentId = documentId, stage = stage)
                }
                OperationType.STAGE_COMPLETE -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    val stage = payloadJson.get("stage")?.asString
                    if (documentId == null || stage == null) {
                        AppLog.e(TAG, "Missing document_id or stage in STAGE_COMPLETE payload")
                        return null
                    }
                    SyncMessage.StageComplete(id = id, timestamp = timestamp, documentId = documentId, stage = stage)
                }
                OperationType.DOCUMENT_UPDATE -> {
                    // DOCUMENT_UPDATE is no longer queued (see updateDocument()).
                    // Legacy entries from older app versions carry stale line
                    // snapshots; dropping them prevents replaying zero totals
                    // over server state. Dirty flags + resyncDirtyDocuments()
                    // cover the offline case with fresh Room data.
                    AppLog.w(TAG, "Dropping legacy queued DOCUMENT_UPDATE for ${operation.entityId}; dirty re-sync will handle it")
                    null
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
