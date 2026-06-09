package ua.com.programmer.pick.data.sync

import ua.com.programmer.pick.core.util.AppLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import ua.com.programmer.pick.data.remote.LinePhotoUploader
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
import ua.com.programmer.pick.data.remote.websocket.SyncTransport
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
 * Server rejected a worker operation because the document is not locked by
 * this user. When we see this the session claim is stale and must be dropped
 * so the UI returns to "Take into work" and the next server snapshot is
 * accepted instead of suppressed.
 */
private fun isLockDeniedError(error: String?): Boolean =
    error != null && (
        error.contains("FORBIDDEN", ignoreCase = true) ||
            error.contains("must be locked", ignoreCase = true)
    )

/**
 * Coordinates all synchronization operations via WebSocket:
 * - Incoming data from WebSocket (sync data, push notifications)
 * - Outgoing operations (document lock, update, complete)
 * - Product lookup
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    private val webSocketManager: SyncTransport,
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
    private val linePhotoUploader: ua.com.programmer.pick.data.remote.LinePhotoUploader,
    private val linePhotoStore: ua.com.programmer.pick.core.util.LinePhotoStore,
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
        // While WS is wedged, emit one DOC_UPDATE_QUEUED journal row per
        // doc per minute instead of one per debounce tick. Carries the
        // running totals so we can see how far the offline drift went.
        private const val QUEUED_HEARTBEAT_INTERVAL_MS = 60_000L

        // States in which this device holds the stage lock and therefore owns
        // the document's line actuals and box data. While the local doc sits in
        // one of these, incoming server document payloads are suppressed for
        // that doc (see applyDocumentSync). Mirrors the backend's in-process
        // states that trigger `erp_sync_blocked`.
        private val WORKER_AUTHORITATIVE_STATES = setOf("COLLECTING", "PACKING", "DELIVERING")

        // M5″ lock-loss recovery budget: when the server rejects a worker
        // write (LOCK_LOST / WRONG_STATE), the orchestrator silently re-issues
        // STAGE_LOCK up to LOCK_LOSS_MAX_ATTEMPTS times with
        // LOCK_LOSS_RETRY_BACKOFF_MS between attempts. Tuned to ride out
        // transient server-state hiccups (admin force-release followed by
        // re-assignment, brief Mongo race) without burning battery on a
        // permanent rejection. After exhaustion the doc transitions to a
        // local read-only state and the dirty edits are dropped + journalled.
        private const val LOCK_LOSS_MAX_ATTEMPTS = 3
        private const val LOCK_LOSS_RETRY_BACKOFF_MS = 10_000L

        // Active-poll cadence for the REST transport (no server push). Only runs
        // while this device holds a stage lock (a document is being worked), so
        // it stays quiet — and battery-cheap — when idle.
        private const val ACTIVE_POLL_INTERVAL_MS = 8_000L
    }

    /**
     * One-shot events emitted by the orchestrator that the UI layer should
     * surface to the worker. Currently used only by the M5″ lock-loss
     * recovery to notify the document detail screen when its document has
     * been repudiated by the server. Subscribe with `replay = 0` semantics —
     * each event is delivered to whichever ViewModel is currently observing.
     */
    sealed class DocSyncEvent {
        /**
         * The orchestrator gave up trying to re-acquire the stage lock for
         * this document after the M5″ budget was exhausted. Dirty edits have
         * been dropped locally and the doc has been refreshed from the server.
         * The UI must navigate the worker off the doc and show a banner.
         */
        data class LockLost(
            val documentId: String,
            val droppedLineCount: Int,
            val droppedActualSum: Double,
        ) : DocSyncEvent()
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncTimeoutJob: Job? = null
    private val syncReceivedCounts = mutableMapOf<String, Int>()
    private val documentSyncJobs = mutableMapOf<String, Job>()

    // Session-scoped claim of stage-lock ownership. Populated only after a
    // confirmed STAGE_LOCK_RESULT success and cleared on unlock / complete /
    // server rejection / app process restart. Never persisted — every document
    // open starts with no claim, and the user must re-confirm the lock with
    // the server before any authoritative worker edits are accepted. This
    // replaces the previous "derive lock from local document.state" approach,
    // which could get stuck when the server and client desynced (e.g. missed
    // STAGE_UNLOCK_RESULT), leaving the UI with no way to recover.
    private val heldStageLocks: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // Whether the connected server confirms DOCUMENT_UPDATE writes with a
    // DOCUMENT_UPDATE_RESULT frame (advertised in USER_LOGIN_RESULT). When true,
    // resyncDirtyDocuments clears is_dirty only on a confirmed ack; when false
    // (old server) it falls back to the legacy clear-on-buffer-accept path.
    @Volatile
    private var supportsUpdateAck: Boolean = false

    // M5″ lock-loss recovery state. `lockLossInProgress` is checked at the
    // top of every outbound line-update path (`performDocumentSync`) so we
    // don't fan out more DOCUMENT_UPDATEs that the server is currently
    // rejecting — the worker can keep scanning, the edits accumulate as
    // `is_dirty=1` rows, and the recovery either re-acquires the lock and
    // drains them, or gives up and drops them. `lockLossJobs` lets us
    // collapse repeat rejection signals for the same doc onto a single
    // running recovery coroutine. Keyed by internal (Room) document id.
    private val lockLossInProgress: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val lockLossJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    // One-shot UI events. UnlimitedReplay-free buffered flow with extraBufferCapacity
    // so a fast-firing emit() never suspends the orchestrator on a slow collector,
    // and a freshly-attached collector doesn't replay stale events from minutes ago.
    private val _docSyncEvents = MutableSharedFlow<DocSyncEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val docSyncEvents: SharedFlow<DocSyncEvent> = _docSyncEvents.asSharedFlow()

    // Tracks the last DOC_UPDATE_QUEUED journal-write per document so we can
    // coalesce a long offline burst into one full row plus a periodic
    // "still offline" heartbeat. The 05-27.04.26 incident produced 189
    // identical-shape DOC_UPDATE_QUEUED rows in 56 minutes — they buried the
    // signal. Keys are document ids; values are the wall-clock ms of the last
    // journalled queue event for that doc. Cleared on a successful send.
    private val lastQueuedJournalAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Wall-clock at the most recent transition into Connected. Lets us
    // attribute reconnect-driven resyncs and emit a WS_RECONNECT event with
    // accurate offline_ms on the next reconnect.
    @Volatile private var lastDisconnectAt: Long = 0L

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

        // Startup scrub: zero any non-dirty actual_quantity / is_completed left
        // on lines of LOADED documents. The inbound-merge guard in
        // mergeDocumentLines stops new contamination, but it cannot undo rows
        // that landed in the DB before that guard existed (or via a code path
        // that bypassed it). Worker-edited lines (is_dirty=1) are never
        // touched. Each affected document is journalled at SEVERITY_ERROR
        // with source=startup_scrub so it remains visible in the Debug Journal.
        scope.launch {
            try {
                scrubLoadedDocActualQuantitiesOnStartup()
            } catch (e: Exception) {
                AppLog.e(TAG, "Startup LOADED-actual scrub failed: ${e.message}", e)
            }
        }

        // Active poll for transports without a server push (REST). Runs only
        // while a stage lock is held so a worked document picks up server-side
        // changes — including a cooperative `release_requested` — promptly,
        // without a persistent connection. No-op for the WebSocket transport.
        if (webSocketManager.requiresPolling) {
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(ACTIVE_POLL_INTERVAL_MS)
                    if (heldStageLocks.isNotEmpty() &&
                        webSocketManager.isUserAuthenticated() &&
                        !_syncState.value.isSyncing
                    ) {
                        try {
                            requestDeltaSync()
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Active poll sync failed: ${e.message}")
                        }
                    }
                }
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

        // Observe WebSocket connection state. We resync dirty data on every
        // transition into Connected — even if the userAuthState observer also
        // catches it. Belt-and-braces: the userAuthState path only fires when
        // auth flips NotAuthenticated → Authenticated, which can be skipped
        // if the StateFlow was already at Authenticated when the listener
        // attached, or if the auto-login path is bypassed for any reason.
        // Losing the worker's offline edits because one observer didn't fire
        // is the failure mode we're fixing.
        var wasConnected = false
        webSocketManager.connectionState
            .onEach { connectionState ->
                val isConnected = connectionState is ConnectionState.Connected
                _syncState.value = _syncState.value.copy(isWebSocketConnected = isConnected)

                if (!isConnected && wasConnected) {
                    // Just lost connection — record the moment so the next
                    // reconnect can journal an accurate offline duration.
                    lastDisconnectAt = System.currentTimeMillis()
                }

                if (isConnected && !wasConnected) {
                    // Transition into Connected. Journal a WS_RECONNECT row
                    // with the offline duration + a snapshot of pending
                    // work, so future post-mortems can locate the boundary
                    // between "what the device buffered offline" and "what
                    // the device pushed/received after coming back". The
                    // event ties to any currently-held lock document so it
                    // shows up in per-document filters.
                    val offlineMs = if (lastDisconnectAt > 0) {
                        System.currentTimeMillis() - lastDisconnectAt
                    } else 0L
                    scope.launch {
                        try {
                            val dirtyDocs = run {
                                val byId = HashSet<String>()
                                documentDao.getDirtyDocuments().forEach { byId.add(it.id) }
                                documentDao.getDocumentsWithDirtyLines().forEach { byId.add(it.id) }
                                byId
                            }
                            val locks = heldStageLocks.toList()
                            val tagDoc = locks.firstOrNull() ?: dirtyDocs.firstOrNull()
                            debugJournal.log(
                                eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_RECONNECT,
                                message = "ws connected" +
                                    (if (offlineMs > 0) " after ${offlineMs}ms offline" else ""),
                                documentId = tagDoc,
                                payload = mapOf(
                                    "offline_ms" to offlineMs,
                                    "dirty_doc_count" to dirtyDocs.size,
                                    "dirty_doc_ids" to dirtyDocs.toList(),
                                    "held_locks" to locks,
                                    "user_authenticated" to webSocketManager.isUserAuthenticated()
                                )
                            )
                        } catch (_: Exception) {}
                    }

                    // If the user is already authenticated (e.g. fast
                    // reconnect on the same socket without re-auth), push
                    // dirty data immediately. The userAuthState observer
                    // will fire its own pass when / if auto-login completes
                    // — resyncDirtyDocuments is a no-op when there's
                    // nothing dirty, so calling it twice is cheap.
                    if (webSocketManager.isUserAuthenticated()) {
                        try { resyncDirtyDocuments() } catch (_: Exception) {}
                        try { drainPendingPhotos() } catch (_: Exception) {}
                    }
                }
                wasConnected = isConnected
            }
            .launchIn(scope)

        // Observe user authentication state (Stage 2 of protocol)
        webSocketManager.userAuthState
            .onEach { authState ->
                when (authState) {
                    is UserAuthState.Authenticated -> {
                        supportsUpdateAck = authState.supportsUpdateAck
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

                        // Re-populate the in-memory `heldStageLocks` set from
                        // the server's authoritative view (USER_LOGIN_RESULT
                        // payload). After a process death / WS reset the set
                        // is empty, but the server may still hold an
                        // in-process lock for this (user, device) pair — and
                        // any inbound SYNC_DATA for those docs would slip
                        // past suppression and merge over the worker's
                        // is_dirty data. Reasserting here closes that race
                        // before the first sync round-trip fires below.
                        authState.heldStageLocks
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { reassertHeldStageLocksFromLogin(it) }

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
                            // Flush any photos captured while offline.
                            try { drainPendingPhotos() } catch (_: Exception) {}
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

    /**
     * Request a delta sync only if the cache is older than [maxAgeMs].
     * No-op when WS is offline, the user is not authenticated, a sync is
     * already in flight, or the cache is fresh. Used by foreground-resume
     * hooks to refresh reference data without spamming on every navigation.
     */
    suspend fun requestDeltaSyncIfStale(maxAgeMs: Long) {
        if (_syncState.value.isSyncing) return
        if (!webSocketManager.isConnected()) return
        if (!webSocketManager.isUserAuthenticated()) return
        val last = _syncState.value.lastSyncTime ?: 0L
        if (System.currentTimeMillis() - last < maxAgeMs) return
        AppLog.d(TAG, "Cache stale (last=$last), requesting delta sync on resume")
        requestDeltaSync()
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

    /**
     * Request a short-lived signed URL to upload [lineNumber]'s photo on
     * [documentId] (Room id). Returns the absolute upload URL on success.
     * Tokens expire quickly, so callers must request a fresh URL per attempt.
     */
    suspend fun requestLinePhotoUploadUrl(documentId: String, lineNumber: Int): Result<String> {
        if (!webSocketManager.isConnected()) {
            return Result.Error(Exception("WebSocket not connected"))
        }
        if (!webSocketManager.isUserAuthenticated()) {
            return Result.Error(Exception("User not authenticated"))
        }

        val message = SyncMessage.LinePhotoUploadUrl(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = toExternalDocumentId(documentId),
            lineNumber = lineNumber
        )

        val response = webSocketManager.sendAndAwait(
            message,
            SyncMessage.LinePhotoUploadUrlResult::class.java
        )

        return when {
            response == null -> Result.Error(Exception("No response for photo upload URL"))
            !response.success || response.uploadUrl.isNullOrBlank() ->
                Result.Error(Exception(response.error ?: "Upload URL request failed"))
            else -> Result.Success(response.uploadUrl)
        }
    }

    /**
     * Upload the captured photo for [lineId]: mint a fresh signed URL, POST the
     * cached JPEG, and on success flip has_photo + clear the pending flag.
     * Returns true when the upload (and marker update) succeeded. A transient
     * failure leaves photo_pending set for the next drain; a permanent one
     * (missing file, oversized) clears it to stop a retry loop.
     */
    suspend fun uploadLinePhoto(lineId: String): Boolean {
        val line = documentLineDao.getLineById(lineId) ?: return false
        if (!line.photoPending) return true
        val bytes = linePhotoStore.read(line.photoPath)
        if (bytes == null) {
            AppLog.w(TAG, "Pending photo file missing for line $lineId; clearing flag")
            documentLineDao.clearLinePhotoPending(lineId)
            return false
        }

        // A 401 means the freshly minted URL's token already expired — refresh
        // and retry once.
        var result = requestAndUpload(line.documentId, line.lineNumber, bytes)
        if (result is LinePhotoUploader.Result.Unauthorized) {
            result = requestAndUpload(line.documentId, line.lineNumber, bytes)
        }

        return when (result) {
            LinePhotoUploader.Result.Success -> {
                documentLineDao.markLinePhotoUploaded(lineId)
                true
            }
            LinePhotoUploader.Result.TooLarge -> {
                AppLog.w(TAG, "Photo for line $lineId rejected as too large; clearing flag")
                documentLineDao.clearLinePhotoPending(lineId)
                false
            }
            // null URL (offline/server error), repeated 401, or other failure:
            // keep pending and retry on the next drain.
            else -> false
        }
    }

    // Mint a fresh signed URL and POST the bytes. Returns null when no URL could
    // be obtained (offline / server error); otherwise the raw upload result.
    private suspend fun requestAndUpload(
        documentId: String,
        lineNumber: Int,
        bytes: ByteArray
    ): LinePhotoUploader.Result? {
        val url = (requestLinePhotoUploadUrl(documentId, lineNumber) as? Result.Success)?.data
            ?: return null
        return linePhotoUploader.upload(url, bytes)
    }

    /**
     * Upload every captured-but-unuploaded photo. Called on reconnect so photos
     * taken offline flush once the socket is back.
     */
    suspend fun drainPendingPhotos() {
        if (!webSocketManager.isConnected() || !webSocketManager.isUserAuthenticated()) return
        val pending = documentLineDao.getLinesWithPendingPhotos()
        if (pending.isEmpty()) return
        AppLog.d(TAG, "Draining ${pending.size} pending line photo(s)")
        for (line in pending) {
            if (!webSocketManager.isConnected()) break
            uploadLinePhoto(line.id)
        }
    }

    // Delete cached photo files for a document before its lines are purged, so
    // filesDir/line_photos/ doesn't accumulate orphans on stage-complete/purge.
    private suspend fun purgeLinePhotoFiles(documentId: String) {
        documentLineDao.getLinesByDocumentIdSync(documentId)
            .forEach { linePhotoStore.delete(it.photoPath) }
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
        } else if (result?.success == false && isLockDeniedError(result.error)) {
            heldStageLocks.remove(documentRoomId)
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
        } else if (result?.success == false && isLockDeniedError(result.error)) {
            heldStageLocks.remove(documentRoomId)
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

        // Push any pending dirty state for THIS document before asking the
        // server to advance its stage. Symmetric to the flush in completeStage.
        // Without this, advancing COLLECTING→PACKING (or any stage transition)
        // takes the server into the new stage on the basis of whatever it
        // last received — and the response payload (or a SYNC_DATA echo
        // that follows) merges back over the local copy with stale totals.
        // Best-effort: failures are logged inside performDocumentSync; if
        // WS is wedged the lock attempt below will fail naturally.
        try { flushDocumentSync(documentId) } catch (_: Exception) {}
        try { resyncDirtyDocuments() } catch (_: Exception) {}

        val externalId = toExternalDocumentId(documentId)
        val message = SyncMessage.StageLock(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            stage = stage
        )

        val sentSnapshot = stageSnapshot(documentId)
        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_LOCK_SENT,
            message = "stage lock sent",
            documentId = documentId,
            stage = stage,
            payload = sentSnapshot
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
            payload = response?.let {
                stageSnapshot(documentId) + mapOf(
                    "success" to it.success,
                    "locked_by" to it.lockedBy,
                    "error" to it.error
                )
            }
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
                heldStageLocks.add(documentId)
            } else {
                heldStageLocks.remove(documentId)
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
            // No ack within the request timeout — the socket is very likely a
            // stale half-open connection (common right after a long Doze
            // sleep). Tear it down so the user's retry runs on a fresh socket
            // instead of timing out again.
            AppLog.w(TAG, "Stage lock timed out, forcing WebSocket reconnect")
            webSocketManager.forceReconnect()
            Result.Error(Exception("Lock request timeout"))
        }
    }

    /**
     * Unlock a document from its current stage
     */
    suspend fun unlockFromStage(documentId: String, stage: String): Result<Unit> {
        AppLog.d(TAG, "Unlocking document $documentId from stage: $stage")

        // Flush before unlock for the same reason as completeStage: once the
        // lock is released, ERP is free to push and our preserveLineActuals
        // / preserveBoxes invariant only protects what the server already has.
        // Anything still local (debounced / queued) needs to land first.
        try { flushDocumentSync(documentId) } catch (_: Exception) {}
        try { resyncDirtyDocuments() } catch (_: Exception) {}

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
            stage = stage,
            payload = stageSnapshot(documentId)
        )

        // Release the session claim immediately — regardless of whether the
        // WS send succeeds, the user has expressed intent to stop editing and
        // the UI must not continue to treat this device as the lock owner.
        // Do NOT mutate local document.state or assignedUserId here: the server
        // is authoritative for those and will push the post-unlock snapshot on
        // the next SYNC_DATA. Optimistic local mutation here was the root cause
        // of the 09-14.04.26 desync incident — if the unlock was lost or the
        // server kept the lock held, the client's guess diverged from the
        // server truth and the suppression guard then blocked every recovery.
        heldStageLocks.remove(documentId)

        val sent = webSocketManager.sendMessage(message)
        return if (sent) {
            Result.Success(Unit)
        } else {
            Result.Error(Exception("Failed to send unlock request"))
        }
    }

    /**
     * Pause work on a document's current stage. Releases the session lock but
     * the server keeps the document at the in-process state (COLLECTING /
     * PACKING) and in this worker's queue. Time spent paused is excluded from
     * the worker's effective work duration on the server side.
     *
     * Resume is the regular [lockForStage] handshake on the same document.
     */
    suspend fun pauseStage(documentId: String, stage: String): Result<Unit> {
        AppLog.d(TAG, "Pausing document $documentId at stage: $stage")

        // Same flush rationale as unlockFromStage / completeStage.
        try { flushDocumentSync(documentId) } catch (_: Exception) {}
        try { resyncDirtyDocuments() } catch (_: Exception) {}

        val externalId = toExternalDocumentId(documentId)

        if (!webSocketManager.isConnected()) {
            AppLog.d(TAG, "WebSocket not connected, queueing pause operation for document: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.STAGE_PAUSE,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to externalId, "stage" to stage))
            )
            return Result.Success(Unit)
        }

        val message = SyncMessage.StagePause(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            documentId = externalId,
            stage = stage
        )

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_PAUSE_SENT,
            message = "stage pause sent",
            documentId = documentId,
            stage = stage,
            payload = stageSnapshot(documentId)
        )

        // Same lock-claim release semantics as unlockFromStage: the user's
        // intent is to stop editing this device's session, regardless of how
        // the server responds. The server is authoritative for state — do not
        // mutate document.state locally; the next SYNC_DATA will carry the
        // post-pause snapshot (state stays at the in-process value, locked_by
        // null, active_work_ms incremented).
        heldStageLocks.remove(documentId)

        val sent = webSocketManager.sendMessage(message)
        return if (sent) {
            Result.Success(Unit)
        } else {
            // isConnected() was true but the write failed → half-open socket.
            // WebSocketManager.sendMessage has already escalated to a forced
            // reconnect; queue the pause (exactly as the offline branch above)
            // so the worker's intent replays on the fresh socket instead of
            // being silently dropped. Treat as success — the pause will land.
            AppLog.w(TAG, "Pause send failed (half-open socket), queueing for replay: $documentId")
            outgoingOperationRepository.queueOperation(
                operationType = OperationType.STAGE_PAUSE,
                entityType = EntityType.DOCUMENT,
                entityId = documentId,
                payload = gson.toJson(mapOf("document_id" to externalId, "stage" to stage))
            )
            Result.Success(Unit)
        }
    }

    /**
     * Forget any session claim this device has made for `documentId`. Called by
     * the UI whenever the user (re-)opens a document, so the worker is forced
     * through a fresh STAGE_LOCK handshake — see feedback memory
     * "Document lock state is never client-persisted".
     */
    fun clearStageLockClaim(documentId: String) {
        heldStageLocks.remove(documentId)
    }

    /** True when this device holds a confirmed stage lock on `documentId`. */
    fun hasStageLockClaim(documentId: String): Boolean =
        documentId in heldStageLocks

    /**
     * Builds a snapshot of the local doc + line state for inclusion in stage
     * action journal payloads. Captures the totals + dirty counts at the
     * moment the action is initiated/answered so the journal shows what the
     * worker was actually looking at when they pressed the button. Best-effort
     * — never throws; returns an empty map on any DB error.
     */
    private suspend fun stageSnapshot(documentId: String): Map<String, Any?> {
        return try {
            val doc = documentDao.getDocumentById(documentId)
            val lines = documentLineDao.getLinesByDocumentIdSync(documentId)
            val dirtyLineCount = lines.count { it.isDirty }
            val totalActual = lines.sumOf { it.actualQuantity }
            val totalPlanned = lines.sumOf { it.plannedQuantity }
            mapOf(
                "local_state" to doc?.state,
                "local_version" to (doc?.version ?: -1),
                "doc_dirty" to (doc?.isDirty ?: false),
                "line_count" to lines.size,
                "dirty_line_count" to dirtyLineCount,
                "total_actual_qty" to totalActual,
                "total_planned_qty" to totalPlanned
            )
        } catch (_: Exception) { emptyMap() }
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
        // Intentionally NOT journalled. Each line edit triggered one of these
        // events; in real-world traffic that meant ~50% of journal rows were
        // SYNC_SCHEDULED with zero diagnostic value beyond what the matching
        // SYNC_FIRED row already carries. The signal lives in SYNC_FIRED +
        // SYNC_FLUSHED + DOC_UPDATE_SENT/QUEUED — those are still recorded.
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
        // M5″ guard: if the server is currently rejecting writes for this doc
        // and a silent re-lock attempt is in flight, queue more dirty edits
        // locally but don't fan out a fresh DOCUMENT_UPDATE — it would just
        // get rejected again and pad the journal. The recovery's success
        // path calls flushDocumentSync() to drain whatever accumulated.
        if (documentId in lockLossInProgress) {
            AppLog.d(TAG, "performDocumentSync: lock-loss recovery in progress for $documentId, skipping")
            return
        }
        val doc = documentDao.getDocumentById(documentId) ?: return
        val lineEntities = documentLineDao.getLinesByDocumentIdSync(documentId)
        if (lineEntities.isEmpty()) return

        val lines = lineEntities.map { line ->
            DocumentLineUpdate(
                lineNumber = line.lineNumber,
                actualQuantity = line.actualQuantity,
                batchNumber = line.batchNumber,
                isCompleted = line.isCompleted,
                notes = line.notes
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
            // Coalesce: log the first one fully, then a 60s heartbeat. A
            // long offline session previously emitted hundreds of identical
            // rows (189 in the 05-27.04.26 incident); the heartbeat keeps
            // the running total visible without burying the rest of the
            // journal.
            val now = System.currentTimeMillis()
            val last = lastQueuedJournalAt[documentId]
            if (last == null || now - last >= QUEUED_HEARTBEAT_INTERVAL_MS) {
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_UPDATE_QUEUED,
                    message = if (last == null) "WS offline; deferred to dirty re-sync"
                              else "WS still offline; running totals snapshot",
                    documentId = documentId,
                    severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                    payload = mapOf(
                        "line_count" to lines.size,
                        "total_actual_qty" to lines.sumOf { it.actualQuantity },
                        "state" to state,
                        "first_in_burst" to (last == null)
                    )
                )
                lastQueuedJournalAt[documentId] = now
            }
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
            // Send succeeded → reset the offline-coalesce window so the next
            // disconnect starts fresh and emits a full row.
            lastQueuedJournalAt.remove(documentId)
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
            // Same coalescing rule as the offline branch above: only log the
            // first failure of a burst; subsequent failures within the
            // heartbeat window are noise.
            val now = System.currentTimeMillis()
            val last = lastQueuedJournalAt[documentId]
            if (last == null || now - last >= QUEUED_HEARTBEAT_INTERVAL_MS) {
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_UPDATE_QUEUED,
                    message = if (last == null) "WS send failed; deferred to dirty re-sync"
                              else "WS send still failing; running totals snapshot",
                    documentId = documentId,
                    severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                    payload = mapOf(
                        "line_count" to lines.size,
                        "total_actual_qty" to lines.sumOf { it.actualQuantity },
                        "state" to state,
                        "first_in_burst" to (last == null)
                    )
                )
                lastQueuedJournalAt[documentId] = now
            }
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
        // Belt-and-braces: push every locally-dirty doc/line in case some
        // never made it on the offline-queue path (DOC_UPDATE_QUEUED leaves
        // no queue entry — only the line/doc dirty flags). Without this, a
        // long offline session that ended right before STAGE_COMPLETE would
        // commit the server transition against stale totals.
        try { resyncDirtyDocuments() } catch (_: Exception) {}

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
            payload = stageSnapshot(documentId) + mapOf("message_id" to message.id)
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
            payload = response?.let {
                stageSnapshot(documentId) + mapOf(
                    "success" to it.success,
                    "state" to it.state,
                    "version" to it.version,
                    "error" to it.error
                )
            }
        )

        if (response == null) {
            // No ack within the timeout — same half-open-socket recovery as
            // lockStage. The send may have succeeded into a dead socket (so the
            // manager's send()==false escalation never fires); tear it down so
            // the user's retry runs on a fresh connection instead of timing out
            // against the same wedged one.
            AppLog.w(TAG, "Stage complete timed out, forcing WebSocket reconnect")
            webSocketManager.forceReconnect()
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
            purgeLinePhotoFiles(documentId)
            documentLineDao.deleteLinesByDocumentId(documentId)
            documentDao.deleteDocument(documentId)
            heldStageLocks.remove(documentId)

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

        if (isLockDeniedError(response.error)) {
            // The server no longer considers this device the lock owner.
            // Drop the session claim so the suppression guard stops blocking
            // incoming server snapshots and the UI returns to "Take into work".
            heldStageLocks.remove(documentId)
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
        purgeLinePhotoFiles(documentId)
        documentLineDao.deleteLinesByDocumentId(documentId)
        documentDao.deleteDocument(documentId)
        outgoingOperationRepository.deletePendingOperationsForEntity(documentId)
        heldStageLocks.remove(documentId)
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
                is SyncMessage.ForceReleaseRequest -> {
                    handleForceReleaseRequest(message)
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

        val roomId = toRoomDocumentId(message.documentId)
        if (message.success) {
            documentDao.updateDocumentStateFromServer(roomId, stageInProcessState(message.stage), System.currentTimeMillis())
            heldStageLocks.add(roomId)
            message.lockedBy?.let { userId ->
                documentDao.updateAssignedUser(roomId, userId, System.currentTimeMillis())
            }
        } else {
            heldStageLocks.remove(roomId)
        }
    }

    private suspend fun handleStageCompleteResult(message: SyncMessage.StageCompleteResult) {
        AppLog.d(TAG, "Stage complete result: ${message.documentId}, stage: ${message.stage}, success: ${message.success}")

        val roomId = toRoomDocumentId(message.documentId)
        if (message.success) {
            val state = message.state ?: return
            documentDao.updateDocumentStateFromServer(roomId, state, System.currentTimeMillis())
            message.version?.let { version ->
                documentDao.updateDocumentVersion(roomId, version.toInt())
            }
            heldStageLocks.remove(roomId)
        } else {
            // Any failed stage-complete is a signal the lock may no longer
            // belong to this device (most commonly FORBIDDEN: "document must
            // be locked by the current user"). Drop the session claim so the
            // UI falls back to requiring a fresh STAGE_LOCK.
            heldStageLocks.remove(roomId)
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

        // Lock-loss class of errors (M5″). The server rejected a worker-write
        // because we no longer hold the stage lock (LOCK_LOST) or the doc
        // moved out of the in-process state (WRONG_STATE). Both shapes carry
        // a document_id so the recovery can target the specific doc. Route
        // to the silent re-lock machine BEFORE the legacy FORBIDDEN→reconnect
        // branch: these errors are not auth failures, they must not trigger
        // a token-refresh disconnect loop.
        if (message.code == "LOCK_LOST" || message.code == "WRONG_STATE") {
            val externalDocId = message.documentId
            if (!externalDocId.isNullOrBlank()) {
                scope.launch { onDocumentWriteRejected(externalDocId, message.code) }
            } else {
                AppLog.w(TAG, "ServerError ${message.code} arrived without document_id; ignoring")
            }
            return
        }

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

    /**
     * M5″ lock-loss recovery. Triggered by a server-side rejection of a
     * worker write (DOCUMENT_UPDATE). Plan:
     *
     *   1. Coalesce — if a recovery for this doc is already running, ignore.
     *   2. Pause outbound sync for the doc (lockLossInProgress flag).
     *   3. Attempt STAGE_LOCK in the doc's current stage, up to
     *      LOCK_LOSS_MAX_ATTEMPTS times with LOCK_LOSS_RETRY_BACKOFF_MS
     *      between attempts.
     *   4. On success: drain accumulated dirty edits via flushDocumentSync,
     *      journal LOCK_LOST_RECOVERED, clear the flag.
     *   5. On final failure: drop dirty edits (zero actuals on is_dirty rows),
     *      journal LOCK_LOST_EDIT_DROPPED with the dropped quantities, refresh
     *      the doc from the server, emit a DocSyncEvent.LockLost so the UI
     *      can navigate off + show a banner.
     */
    private suspend fun onDocumentWriteRejected(externalDocumentId: String, code: String) {
        val roomId = toRoomDocumentId(externalDocumentId)
        // Coalesce repeat signals for the same doc onto the existing recovery.
        // putIfAbsent semantics on the Set: returns false if already present.
        if (!lockLossInProgress.add(roomId)) {
            AppLog.d(TAG, "Lock-loss recovery already in progress for $roomId, ignoring duplicate $code signal")
            return
        }
        val existingJob = lockLossJobs[roomId]
        existingJob?.cancel()
        val job = scope.launch {
            try {
                runLockLossRecovery(roomId, code)
            } finally {
                lockLossInProgress.remove(roomId)
                lockLossJobs.remove(roomId)
            }
        }
        lockLossJobs[roomId] = job
    }

    /**
     * Attempts up to LOCK_LOSS_MAX_ATTEMPTS silent STAGE_LOCKs for the
     * document, derived from its current local state's stage. Returns on
     * the first success (after draining dirty edits) or after the budget
     * is exhausted (after invoking the give-up branch).
     */
    private suspend fun runLockLossRecovery(roomDocumentId: String, triggerCode: String) {
        // Capture the stage from local state. The doc may have already been
        // mutated by an inbound SYNC_DATA between rejection and recovery
        // start — that's fine: stageOf maps both the start state (LOADED/
        // PACK/DELIVERY) and the in-process state (COLLECTING/PACKING/
        // DELIVERING) to the same stage label, so a stale local state
        // doesn't break the lock attempt.
        val doc = documentDao.getDocumentById(roomDocumentId)
        if (doc == null) {
            AppLog.w(TAG, "Lock-loss recovery: doc $roomDocumentId not found locally, nothing to recover")
            return
        }
        val stage = ua.com.programmer.pick.domain.model.DocumentState.stageOf(
            ua.com.programmer.pick.domain.model.DocumentState.fromString(doc.state)
        )
        if (stage == null) {
            AppLog.w(TAG, "Lock-loss recovery: doc $roomDocumentId in terminal state ${doc.state}, cannot relock")
            finalizeLockLossGiveUp(roomDocumentId, reason = "terminal_state_${doc.state}")
            return
        }

        AppLog.i(TAG, "Lock-loss recovery starting for $roomDocumentId (stage=$stage, trigger=$triggerCode)")

        var attempt = 0
        while (attempt < LOCK_LOSS_MAX_ATTEMPTS) {
            attempt++
            val result = try {
                lockForStage(roomDocumentId, stage)
            } catch (e: Exception) {
                AppLog.w(TAG, "Lock-loss recovery attempt $attempt failed with exception: ${e.message}")
                Result.Error(e)
            }
            val success = (result as? Result.Success)?.data?.success == true
            if (success) {
                AppLog.i(TAG, "Lock-loss recovery succeeded on attempt $attempt for $roomDocumentId")
                // Drain accumulated dirty edits now that the lock is back.
                // Recovery flag clears in the finally{} of onDocumentWriteRejected.
                lockLossInProgress.remove(roomDocumentId)
                try { flushDocumentSync(roomDocumentId) } catch (_: Exception) {}
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.LOCK_LOST_RECOVERED,
                    message = "silent re-lock succeeded on attempt $attempt",
                    documentId = roomDocumentId,
                    stage = stage,
                    payload = mapOf(
                        "attempt" to attempt,
                        "trigger_code" to triggerCode,
                    )
                )
                return
            }
            if (attempt < LOCK_LOSS_MAX_ATTEMPTS) {
                AppLog.w(TAG, "Lock-loss recovery attempt $attempt denied for $roomDocumentId; backing off ${LOCK_LOSS_RETRY_BACKOFF_MS}ms")
                delay(LOCK_LOSS_RETRY_BACKOFF_MS)
            }
        }

        AppLog.w(TAG, "Lock-loss recovery exhausted ${LOCK_LOSS_MAX_ATTEMPTS} attempts for $roomDocumentId; dropping dirty edits")
        finalizeLockLossGiveUp(roomDocumentId, reason = "max_attempts_exhausted")
    }

    /**
     * Give-up branch of M5″. Captures the about-to-be-dropped dirty
     * quantities for the journal, zeroes them in Room, marks the doc as
     * non-dirty, refreshes the doc from the server, and emits a one-shot
     * DocSyncEvent.LockLost so the detail screen can navigate off and
     * show a banner.
     *
     * Called either when 3 retries failed, or when the doc was in a
     * terminal state that can't be re-locked.
     */
    private suspend fun finalizeLockLossGiveUp(roomDocumentId: String, reason: String) {
        // Snapshot the dirty rows for the audit journal before dropping them.
        val dirtyBefore = try {
            documentLineDao.getLinesByDocumentIdSync(roomDocumentId).filter { it.isDirty }
        } catch (_: Exception) { emptyList() }
        val droppedSum = dirtyBefore.sumOf { it.actualQuantity }
        val droppedCount = dirtyBefore.size

        val samples = dirtyBefore.take(5).map {
            mapOf(
                "line_id" to it.id,
                "line_number" to it.lineNumber,
                "product_id" to it.productId,
                "dropped_actual_quantity" to it.actualQuantity,
                "dropped_is_completed" to it.isCompleted,
                "planned_quantity" to it.plannedQuantity,
            )
        }

        // Drop the worker's unsent edits to match server's truth.
        val rowsZeroed = try {
            documentLineDao.dropDirtyEdits(roomDocumentId)
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to drop dirty edits for $roomDocumentId: ${e.message}", e)
            0
        }
        // Recompute totals so the UI doesn't show stale aggregates between
        // here and the next inbound SYNC_DATA.
        try {
            documentDao.recomputeTotalActualForDocs(listOf(roomDocumentId))
        } catch (_: Exception) {}
        try { documentDao.markDocumentAsSynced(roomDocumentId) } catch (_: Exception) {}

        // Release any session claim and trigger a fresh server fetch so the
        // local doc realigns with whatever state the server actually has now
        // (assigned to someone else, locked elsewhere, etc.). UI re-renders
        // off the refreshed entity.
        heldStageLocks.remove(roomDocumentId)
        try { requestDocumentProducts(roomDocumentId) } catch (_: Exception) {}

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.LOCK_LOST_EDIT_DROPPED,
            message = "gave up after lock-loss recovery; dropped $droppedCount dirty line(s) totalling $droppedSum",
            documentId = roomDocumentId,
            severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR,
            payload = mapOf(
                "reason" to reason,
                "dropped_line_count" to droppedCount,
                "dropped_actual_sum" to droppedSum,
                "rows_zeroed" to rowsZeroed,
                "samples" to samples,
            )
        )

        // Notify the UI. tryEmit instead of emit so the orchestrator never
        // blocks on a slow / detached collector — the buffer absorbs.
        _docSyncEvents.tryEmit(
            DocSyncEvent.LockLost(
                documentId = roomDocumentId,
                droppedLineCount = droppedCount,
                droppedActualSum = droppedSum,
            )
        )
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
            Constants.SyncEntity.BOXES -> applyBoxSync(data, deletedIds, fullSet)
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
                documentDao.deleteAllDocuments()
            } else {
                documentDao.deleteDocumentsNotIn(receivedIds.toList())
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
            // Cooperative force-release under polling (REST): the backend flags
            // the held document `release_requested` — the equivalent of the
            // FORCE_RELEASE_REQUEST push. Run exit-without-saving before any
            // suppression/merge so the worker's lock is released and the server
            // clears the marker on the resulting STAGE_UNLOCK. dto.id is already
            // the local room id at this point (translated at the sync boundary).
            if (dto.releaseRequested && dto.id in heldStageLocks) {
                runCooperativeRelease(dto.id, dto.externalId ?: dto.id)
                return@forEach
            }

            val existing = documentDao.getDocumentById(dto.id)

            // Worker-authoritative window. Symmetric to the backend's
            // `erp_sync_blocked` + `preserveLineActuals` / `preserveBoxes`
            // invariant: while this device holds the stage lock (doc is
            // in-process locally) and the server still reports the same
            // in-process state, the app is the source of truth for line
            // actuals, batch/is_completed, and boxes. Any SYNC_DATA echo
            // in this window carries either the server's stale view of our
            // own in-flight edits or, at best, a no-op — either way, letting
            // it replace local lines/boxes clobbers the worker's data.
            //
            // The earlier per-row "preserve dirty" merge was a best-effort
            // heuristic that failed when a successful DOC_UPDATE_SENT landed
            // just before a stale server echo: the ack path left the line's
            // is_dirty flag untouched, but the reopened server payload still
            // raced with the next user scan and could reset actual_quantity
            // to 0 between keystrokes. Dropping the whole payload while
            // locked closes that window.
            //
            // If the server transitions the doc OUT of this in-process state
            // (e.g., admin force-unlock, ERROR) the dto.state will differ
            // and we fall through to the normal merge, which correctly
            // accepts the server's authority.
            // Suppression requires a confirmed, in-memory session claim of
            // the stage lock. Deriving this from local state alone was the
            // root cause of the 09-14.04.26 incident: local state drifted
            // into a worker-authoritative value without the server agreeing,
            // and every subsequent server payload was then silently dropped
            // — the UI never recovered, and BOX_ADD kept being rejected with
            // FORBIDDEN. Now the guard only fires while THIS device actually
            // holds a confirmed STAGE_LOCK.
            if (existing != null
                && dto.id in heldStageLocks
                && existing.state in WORKER_AUTHORITATIVE_STATES
                && dto.state == existing.state) {
                // Phantom-line heal. Quantities, is_completed, and newly-added
                // server lines stay suppressed (see comments above) but a local
                // non-dirty line that the server does not know about is pure
                // drift — deleting it is safe and prevents it from persisting
                // across the session the way it did in doc 14-21.04.26.
                if (dto.lines != null) {
                    val serverIds = dto.lines.map { it.id }.toSet()
                    val localLines = documentLineDao.getLinesByDocumentIdSync(dto.id)
                    val phantoms = localLines.filter { it.id !in serverIds && !it.isDirty }
                    if (phantoms.isNotEmpty()) {
                        phantoms.forEach { documentLineDao.deleteLine(it.id) }
                        debugJournal.log(
                            eventType = ua.com.programmer.pick.data.debug.DebugEventType.PHANTOM_LINE_PURGED,
                            message = "removed ${phantoms.size} local-only non-dirty line(s) during suppression",
                            documentId = dto.id,
                            severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                            payload = mapOf(
                                "removed_count" to phantoms.size,
                                "removed_ids" to phantoms.map { it.id },
                                "server_line_count" to dto.lines.size,
                                "local_line_count_before" to localLines.size
                            )
                        )
                    }
                }
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_SYNC_SUPPRESSED,
                    message = "ignored server document payload while stage lock held",
                    documentId = dto.id,
                    payload = mapOf(
                        "state" to existing.state,
                        "server_version" to dto.version,
                        "local_version" to existing.version,
                        "server_line_count" to (dto.lines?.size ?: 0)
                    )
                )
                return@forEach
            }

            if (existing != null && existing.isDirty) {
                // Server is authoritative for state transitions (e.g., lock released → LOADED).
                // If the server version is newer, accept the state change and clear the dirty flag.
                if (dto.version > existing.version) {
                    AppLog.i(TAG, "Server version ${dto.version} > local ${existing.version} for dirty document ${dto.id}, accepting server state")
                    val totalBefore = documentLineDao.getTotalActualQuantity(dto.id) ?: 0.0
                    val localLineCountBefore = documentLineDao.getLinesByDocumentIdSync(dto.id).size
                    documentDao.upsertDocument(documentMapper.toEntity(dto))
                    var preservedDirty = 0
                    if (dto.lines != null) {
                        preservedDirty = mergeDocumentLines(dto.id, dto.lines, productIdMap, dto.state, dto.recollection)
                    }
                    // The upsert above wrote `isDirty = false` for the document
                    // (DocumentMapper.toEntity always emits false). If
                    // mergeDocumentLines preserved any locally-dirty lines, the
                    // worker still owes the server a DOCUMENT_UPDATE for them —
                    // re-arm doc.is_dirty so resyncDirtyDocuments picks it up
                    // on the next pass instead of silently dropping the edits.
                    val anyLineDirty = documentLineDao.getLinesByDocumentIdSync(dto.id).any { it.isDirty }
                    if (anyLineDirty) {
                        documentDao.markDocumentDirty(dto.id, System.currentTimeMillis())
                    }
                    val totalAfter = documentLineDao.getTotalActualQuantity(dto.id) ?: 0.0
                    debugJournal.log(
                        eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_SYNC_APPLIED,
                        message = "applied newer server version over dirty local doc",
                        documentId = dto.id,
                        severity = if (totalAfter < totalBefore)
                            ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
                            else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO,
                        payload = mapOf(
                            "branch" to "dirty_local_newer_server",
                            "server_version" to dto.version,
                            "local_version_before" to existing.version,
                            "server_state" to dto.state,
                            "local_state_before" to existing.state,
                            "total_actual_before" to totalBefore,
                            "total_actual_after" to totalAfter,
                            "local_line_count_before" to localLineCountBefore,
                            "server_line_count" to (dto.lines?.size ?: 0),
                            "preserved_dirty_lines" to preservedDirty,
                            "any_line_dirty_after" to anyLineDirty
                        )
                    )
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

            val totalBefore = if (existing != null)
                documentLineDao.getTotalActualQuantity(dto.id) ?: 0.0 else 0.0
            val localLineCountBefore = if (existing != null)
                documentLineDao.getLinesByDocumentIdSync(dto.id).size else 0

            val entity = documentMapper.toEntity(dto)
            documentDao.upsertDocument(entity)

            // Replace lines — server sends the complete set. The merge preserves
            // any locally-dirty lines so an in-flight edit isn't overwritten by a
            // stale server echo while a push is still outstanding.
            var preservedDirty = 0
            if (dto.lines != null) {
                preservedDirty = mergeDocumentLines(dto.id, dto.lines, productIdMap, dto.state, dto.recollection)
            }

            // The upsert above wrote isDirty = false for the document. If the
            // line merge kept or re-armed any dirty line (worker data the server
            // is still behind on), re-arm doc.is_dirty so resyncDirtyDocuments
            // re-pushes it instead of silently dropping the edits.
            if (preservedDirty > 0) {
                documentDao.markDocumentDirty(dto.id, System.currentTimeMillis())
            }

            // Only journal DOC_SYNC_APPLIED for actual merges over an
            // existing local copy — first-seen documents from a delta sync
            // are noise. WARN if the apply caused a total drop, since that's
            // the failure shape we care about (offline edits being clobbered).
            if (existing != null) {
                val totalAfter = documentLineDao.getTotalActualQuantity(dto.id) ?: 0.0
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.DOC_SYNC_APPLIED,
                    message = "applied server doc payload",
                    documentId = dto.id,
                    severity = if (totalAfter < totalBefore)
                        ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
                        else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO,
                    payload = mapOf(
                        "branch" to "normal_merge",
                        "server_version" to dto.version,
                        "local_version_before" to existing.version,
                        "server_state" to dto.state,
                        "local_state_before" to existing.state,
                        "total_actual_before" to totalBefore,
                        "total_actual_after" to totalAfter,
                        "local_line_count_before" to localLineCountBefore,
                        "server_line_count" to (dto.lines?.size ?: 0),
                        "preserved_dirty_lines" to preservedDirty
                    )
                )
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

    /**
     * Cooperative force-release: the server (admin action) asked us to
     * release the lock ourselves. Semantics match "exit without saving":
     *   1. Cancel any pending debounced sync — do NOT flush dirty edits.
     *   2. Drop the worker's dirty actuals via the existing M5″ helper.
     *   3. Send STAGE_UNLOCK so the server completes the cooperative flow
     *      and the admin's HTTP request returns success.
     *   4. Emit DocSyncEvent.LockLost so any open detail screen surfaces
     *      the same banner used for M5″ give-up; the worker sees how
     *      many lines / units were lost and is navigated back to the
     *      list.
     *
     * If the device is offline at the moment the request arrives, this
     * codepath can't run — the WS frame would never have been delivered.
     * The server's hard-release fallback handles that case independently.
     */
    private suspend fun handleForceReleaseRequest(message: SyncMessage.ForceReleaseRequest) {
        val externalId = message.documentId
        val roomId = toRoomDocumentId(externalId)
        AppLog.i(TAG, "Cooperative force-release request received for $externalId (room=$roomId)")
        runCooperativeRelease(roomId, externalId)
    }

    /**
     * Exit-without-saving: cancel the pending debounced sync, drop dirty edits,
     * release the held lock, and send STAGE_UNLOCK so the server completes the
     * cooperative force-release. Shared by the WebSocket FORCE_RELEASE_REQUEST
     * push and the REST polling path (a synced document carrying
     * `release_requested` while this device holds the lock).
     */
    private suspend fun runCooperativeRelease(roomId: String, externalId: String) {
        // Cancel any pending debounced sync so it doesn't fire mid-release.
        documentSyncJobs.remove(roomId)?.cancel()

        // Snapshot dirty rows for the audit journal before clearing them.
        val dirtyBefore = try {
            documentLineDao.getLinesByDocumentIdSync(roomId).filter { it.isDirty }
        } catch (_: Exception) { emptyList() }
        val droppedSum = dirtyBefore.sumOf { it.actualQuantity }
        val droppedCount = dirtyBefore.size
        val samples = dirtyBefore.take(5).map {
            mapOf(
                "line_id" to it.id,
                "line_number" to it.lineNumber,
                "product_id" to it.productId,
                "dropped_actual_quantity" to it.actualQuantity,
                "dropped_is_completed" to it.isCompleted,
                "planned_quantity" to it.plannedQuantity,
            )
        }

        try { documentLineDao.dropDirtyEdits(roomId) } catch (_: Exception) {}
        try { documentDao.markDocumentAsSynced(roomId) } catch (_: Exception) {}
        heldStageLocks.remove(roomId)

        // Derive the stage from current local state to populate STAGE_UNLOCK.
        // If the local state has already drifted out of in-process (e.g. an
        // inbound SYNC_DATA already moved it), stageOf can still resolve
        // start states; we send unlock anyway so the server's pending
        // cooperative wait completes deterministically.
        val doc = documentDao.getDocumentById(roomId)
        val stage = doc?.let {
            ua.com.programmer.pick.domain.model.DocumentState.stageOf(
                ua.com.programmer.pick.domain.model.DocumentState.fromString(it.state)
            )
        }

        if (stage != null && webSocketManager.isConnected()) {
            val unlockMsg = SyncMessage.StageUnlock(
                id = messageParser.generateMessageId(),
                timestamp = messageParser.getCurrentTimestamp(),
                documentId = externalId,
                stage = stage,
            )
            try {
                webSocketManager.sendMessage(unlockMsg)
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.STAGE_UNLOCK_SENT,
                    message = "stage unlock sent (cooperative force-release)",
                    documentId = roomId,
                    stage = stage,
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Cooperative force-release: failed to send STAGE_UNLOCK: ${e.message}")
            }
        } else {
            AppLog.w(TAG, "Cooperative force-release: cannot send STAGE_UNLOCK (stage=$stage, connected=${webSocketManager.isConnected()})")
        }

        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.LOCK_LOST_EDIT_DROPPED,
            message = "cooperative force-release: dropped $droppedCount dirty line(s) totalling $droppedSum",
            documentId = roomId,
            severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR,
            payload = mapOf(
                "reason" to "cooperative_force_release",
                "dropped_line_count" to droppedCount,
                "dropped_actual_sum" to droppedSum,
                "samples" to samples,
            )
        )

        _docSyncEvents.tryEmit(
            DocSyncEvent.LockLost(
                documentId = roomId,
                droppedLineCount = droppedCount,
                droppedActualSum = droppedSum,
            )
        )
    }

    /**
     * Rebuild the session-scoped `heldStageLocks` set from the server-asserted
     * list of held locks in USER_LOGIN_RESULT. Each entry is an ERP external_id;
     * we map back to the local Room id via `documentDao.getDocumentByExternalId`
     * and only add entries whose local row actually exists — a server-reported
     * lock for a document the device doesn't have yet (fresh install, cleared
     * data) cannot drive suppression anyway, since suppression keys on the
     * local doc's `state in WORKER_AUTHORITATIVE_STATES`. The subsequent delta
     * sync will fetch the doc; the next inbound SYNC_DATA for it will then be
     * suppressed correctly because by that point both heldStageLocks contains
     * the doc AND the local state matches the server's in-process state.
     *
     * No journal row — the diagnostic value is captured by the existing
     * DOC_SYNC_SUPPRESSED rows that fire when suppression actually engages.
     */
    private fun reassertHeldStageLocksFromLogin(externalIds: List<String>) {
        scope.launch {
            var reasserted = 0
            var unknown = 0
            externalIds.forEach { ext ->
                val local = documentDao.getDocumentByExternalId(ext)
                if (local != null) {
                    if (heldStageLocks.add(local.id)) reasserted++
                } else {
                    unknown++
                }
            }
            if (reasserted > 0 || unknown > 0) {
                AppLog.i(
                    TAG,
                    "Reasserted $reasserted held stage lock(s) from USER_LOGIN_RESULT (unknown_to_local=$unknown, total_reported=${externalIds.size})"
                )
            }
        }
    }

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

    // Defense-in-depth complement to the LOADED-actual guard in
    // mergeDocumentLines. The merge guard prevents new contamination; this
    // function fixes rows that already landed in the local DB before the
    // guard shipped, or that arrived through any future path that bypassed
    // it. Only non-dirty lines on LOADED docs are touched; worker edits are
    // preserved. The recompute step updates each affected document's
    // total_actual WITHOUT marking the doc dirty — otherwise the resync
    // worker would push the scrubbed state to the server, but the server
    // is exactly the upstream we're trying to override locally.
    private suspend fun scrubLoadedDocActualQuantitiesOnStartup() {
        val affected = documentLineDao.findLoadedDocStaleActuals()
        if (affected.isEmpty()) return

        val byDoc = affected.groupBy { it.documentId }
        val updatedLines = documentLineDao.scrubLoadedDocStaleActuals()
        documentDao.recomputeTotalActualForDocs(byDoc.keys.toList())

        AppLog.w(
            TAG,
            "Startup scrub: zeroed $updatedLines stale line actual(s) across ${byDoc.size} LOADED doc(s)"
        )

        byDoc.forEach { (docId, lines) ->
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.LOADED_ACTUAL_REJECTED,
                message = "startup scrub: zeroed ${lines.size} stale line actual(s)",
                documentId = docId,
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR,
                payload = mapOf(
                    "source" to "startup_scrub",
                    "rejected_line_count" to lines.size,
                    "rejected_actual_sum" to lines.sumOf { it.actualQuantity },
                    "samples" to lines.take(5).map {
                        mapOf(
                            "line_id" to it.id,
                            "line_number" to it.lineNumber,
                            "product_id" to it.productId,
                            "stale_actual_quantity" to it.actualQuantity,
                            "stale_is_completed" to it.isCompleted,
                            "planned_quantity" to it.plannedQuantity
                        )
                    }
                )
            )
        }
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
    //
    // Returns the count of locally-dirty lines that were preserved during the
    // merge — callers use it for DOC_SYNC_APPLIED journal payloads so an
    // operator can see how much in-flight worker data this merge protected.
    //
    // documentState is the server-asserted state for the document this batch
    // belongs to. When it is LOADED we enforce a hard invariant: a LOADED doc
    // cannot carry non-zero actuals or is_completed=true on any line. Any
    // such server values are coerced to 0/false before the dirty-preserve
    // step (so a locally-dirty line still wins). This is a belt-and-braces
    // guard against upstream contract violations — cross-device propagation
    // after admin unlock, ERP edits that re-emit prior-session actuals,
    // stale snapshot replays on re-issue, server field-mapping bugs — none
    // of which we can distinguish from inside the app without server logs.
    // Each occurrence is journalled at ERROR severity so future repeats are
    // visible even when the customer can't reproduce on demand.
    private suspend fun mergeDocumentLines(
        documentId: String,
        serverLineDtos: List<ua.com.programmer.pick.data.remote.dto.DocumentLineDto>,
        productIdMap: Map<String, String>,
        documentState: String,
        recollection: Boolean
    ): Int {
        val localById = documentLineDao.getLinesByDocumentIdSync(documentId)
            .associateBy { it.id }
        val rawServerEntities = serverLineDtos.map {
            translateLineEntity(documentMapper.toLineEntity(it), productIdMap)
        }
        // A recollection document is a LOADED doc whose per-line progress was
        // deliberately pre-seeded by the server (an ERP review sent it back for
        // partial re-collection). Skip the LOADED corruption defense for it so
        // the curated is_completed / actual_quantity on kept lines survive.
        val isLoaded = documentState.equals("LOADED", ignoreCase = true) && !recollection
        val rejectedSamples = mutableListOf<Map<String, Any?>>()
        var rejectedLineCount = 0
        var rejectedActualSum = 0.0
        val serverEntities = if (isLoaded) {
            rawServerEntities.map { server ->
                val hasActual = server.actualQuantity != 0.0
                val hasCompleted = server.isCompleted
                if (hasActual || hasCompleted) {
                    rejectedLineCount += 1
                    rejectedActualSum += server.actualQuantity
                    if (rejectedSamples.size < 5) {
                        rejectedSamples += mapOf(
                            "line_id" to server.id,
                            "line_number" to server.lineNumber,
                            "product_id" to server.productId,
                            "server_actual_quantity" to server.actualQuantity,
                            "server_is_completed" to server.isCompleted,
                            "planned_quantity" to server.plannedQuantity
                        )
                    }
                    server.copy(actualQuantity = 0.0, isCompleted = false)
                } else server
            }
        } else rawServerEntities

        if (rejectedLineCount > 0) {
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.LOADED_ACTUAL_REJECTED,
                message = "coerced $rejectedLineCount line(s) on LOADED doc to actual_quantity=0",
                documentId = documentId,
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR,
                payload = mapOf(
                    "rejected_line_count" to rejectedLineCount,
                    "rejected_actual_sum" to rejectedActualSum,
                    "server_line_count" to rawServerEntities.size,
                    "samples" to rejectedSamples
                )
            )
            AppLog.w(TAG, "LOADED doc $documentId: rejected $rejectedLineCount non-zero server line(s) (sum=$rejectedActualSum)")
        }

        val serverIds = serverEntities.map { it.id }.toSet()

        localById.values
            .filter { it.id !in serverIds && !it.isDirty && !it.photoPending }
            .forEach {
                // Prune the local photo cache so purged lines don't leak files.
                linePhotoStore.delete(it.photoPath)
                documentLineDao.deleteLine(it.id)
            }

        // Worker Data Invariant (mirrors the backend's preserveLineActuals):
        // while the document is in a worker-authoritative in-process state,
        // actual_quantity / is_completed / batch_number are owned by the
        // worker. A server payload can only carry a stale or equal view of
        // them, so it must never be allowed to regress local progress.
        //
        // Relying on the per-line is_dirty flag alone is unsafe: DOCUMENT_UPDATE
        // is fire-and-forget (the backend sends no success ack — see
        // client.go:handleDocumentUpdate), and resyncDirtyDocuments clears
        // is_dirty the moment OkHttp buffers the send. If the socket drops
        // between buffer-accept and server-receive, the edit lives only on the
        // device with is_dirty already cleared. A later reconnect that pulls
        // the server's stale copy would then merge over it with nothing flagged
        // to preserve. This is exactly the doc 27-26.05.26 data loss (312 → 94).
        //
        // So while in-process we keep the local worker fields for every matched
        // line regardless of is_dirty, and re-arm is_dirty when the server is
        // behind so resyncDirtyDocuments re-pushes the truth.
        val workerInProcess = documentState.uppercase() in WORKER_AUTHORITATIVE_STATES
        val merged = serverEntities.map { server ->
            val local = localById[server.id]
            // photoPath / photoPending are device-local: the server payload
            // never carries them, so preserve the local values on every matched
            // line. hasPhoto stays server-authoritative.
            when {
                local == null -> server
                local.isDirty -> server.copy(
                    actualQuantity = local.actualQuantity,
                    isCompleted = local.isCompleted,
                    batchNumber = local.batchNumber,
                    notes = local.notes ?: server.notes,
                    photoPath = local.photoPath,
                    photoPending = local.photoPending,
                    isDirty = true
                )
                workerInProcess -> {
                    val serverBehind = server.actualQuantity != local.actualQuantity ||
                        server.isCompleted != local.isCompleted ||
                        server.batchNumber != local.batchNumber
                    server.copy(
                        actualQuantity = local.actualQuantity,
                        isCompleted = local.isCompleted,
                        batchNumber = local.batchNumber,
                        notes = local.notes ?: server.notes,
                        photoPath = local.photoPath,
                        photoPending = local.photoPending,
                        isDirty = serverBehind
                    )
                }
                else -> server.copy(
                    photoPath = local.photoPath,
                    photoPending = local.photoPending
                )
            }
        }
        documentLineDao.insertLines(merged)

        val preservedDirty = merged.count { it.isDirty }
        if (preservedDirty > 0) {
            AppLog.i(TAG, "Merge preserved $preservedDirty dirty line(s) for document $documentId")
        }
        return preservedDirty
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
        deletedIds: List<String>?,
        fullSet: Boolean
    ) {
        if (data.isJsonArray) {
            val type = object : TypeToken<List<BoxDto>>() {}.type
            val boxes: List<BoxDto> = gson.fromJson(data, type)

            AppLog.i(TAG, "Sync boxes: ${boxes.size} upsert, ${deletedIds?.size ?: 0} delete, fullSet=$fullSet")

            // Boxes sync is full-authoritative: the server ships the entire
            // active catalog on every sync so is_parcel/dimensions changes can
            // never get stuck behind a stale cursor. Replace the local cache
            // wholesale — otherwise rows inserted by a schema migration with
            // default column values would persist forever.
            if (fullSet) {
                boxDao.deleteAllBoxes()
            }
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

        // Re-push dirty documents FIRST. Order matters: a queued STAGE_COMPLETE
        // that runs before resync makes the server transition the document on
        // the basis of stale line totals — and a successful STAGE_COMPLETE
        // clears doc.is_dirty (via updateDocumentVersion) so the worker's later
        // resync sees nothing to send. The 05-27.04.26 incident is exactly
        // this race: COLLECTING went offline at 14:28 with total=2965, the
        // worker collected to 3777 offline, WS came back, the queued
        // STAGE_COMPLETE [collect] processed first against server's stale
        // 2965, the doc.is_dirty flag cleared on success, and the 813 units
        // of offline edits never reached the server.
        resyncDirtyDocuments()

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

        // One more pass: a stage-complete success path (in processOperation)
        // can clear doc.is_dirty even though dirty lines remain — same shape
        // as the inbound-merge case handled in applyDocumentSync. Re-running
        // resync here catches any leftover unsynced lines.
        resyncDirtyDocuments()
    }

    /**
     * Find documents with dirty lines that may not have been synced to the server,
     * and re-send their line data. Also picks up documents whose own dirty flag
     * was cleared by an inbound server payload merge while their lines remain
     * locally-dirty (this is what caused the 05-27.04.26 incident: 813 units of
     * offline-collected edits silently dropped because the queued STAGE_COMPLETE
     * landed before the dirty data got to flush).
     *
     * Returns the number of documents that successfully re-sent their data.
     */
    suspend fun resyncDirtyDocuments(): Int {
        if (!webSocketManager.isConnected() || !webSocketManager.isUserAuthenticated()) return 0

        return try {
            // Union: docs flagged dirty + docs with any dirty line. Map by id so
            // we don't re-send the same doc twice when both queries match.
            val byId = LinkedHashMap<String, ua.com.programmer.pick.data.local.database.entity.DocumentEntity>()
            documentDao.getDirtyDocuments().forEach { byId[it.id] = it }
            documentDao.getDocumentsWithDirtyLines().forEach { byId.putIfAbsent(it.id, it) }
            val dirtyDocuments = byId.values
            if (dirtyDocuments.isEmpty()) return 0

            AppLog.d(TAG, "Re-syncing ${dirtyDocuments.size} dirty document(s)")

            var sentCount = 0
            for (doc in dirtyDocuments) {
                if (!webSocketManager.isConnected()) break

                val lineEntities = documentLineDao.getLinesByDocumentIdSync(doc.id)
                if (lineEntities.isEmpty()) continue

                val lines = lineEntities.map { line ->
                    DocumentLineUpdate(
                        lineNumber = line.lineNumber,
                        actualQuantity = line.actualQuantity,
                        batchNumber = line.batchNumber,
                        isCompleted = line.isCompleted,
                        notes = line.notes
                    )
                }

                val message = SyncMessage.DocumentUpdate(
                    id = messageParser.generateMessageId(),
                    timestamp = messageParser.getCurrentTimestamp(),
                    documentId = doc.externalId?.takeIf { it.isNotEmpty() } ?: doc.id,
                    state = doc.state,
                    lines = lines
                )

                if (supportsUpdateAck) {
                    // Confirmed path: clear is_dirty only when the server
                    // acknowledges the write (DOCUMENT_UPDATE_RESULT). This is
                    // the fix for the doc 27-26.05.26 loss — the legacy path
                    // below cleared on buffer-accept, so a socket drop between
                    // buffer and server orphaned the edits with is_dirty already
                    // off, and a later server pull merged over them.
                    val ack = webSocketManager.sendAndAwait(message, SyncMessage.DocumentUpdateResult::class.java)
                    val confirmed = ack?.success == true
                    debugJournal.log(
                        eventType = ua.com.programmer.pick.data.debug.DebugEventType.RESYNC_DIRTY,
                        message = when {
                            confirmed -> "re-synced dirty document (confirmed)"
                            ack != null -> "re-sync rejected: ${ack.errorCode}"
                            else -> "re-sync unconfirmed (no ack)"
                        },
                        documentId = doc.id,
                        severity = if (confirmed) ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO
                        else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                        payload = mapOf(
                            "line_count" to lines.size,
                            "total_actual_qty" to lines.sumOf { it.actualQuantity },
                            "state" to doc.state,
                            "confirmed" to confirmed,
                            "server_version" to (ack?.version ?: -1L),
                            "error_code" to (ack?.errorCode ?: "")
                        )
                    )
                    if (confirmed) {
                        sentCount++
                        // Precise clear: only retire is_dirty on lines whose
                        // current value still matches what we just confirmed.
                        // A line edited during the round-trip won't match and
                        // stays dirty for the next resync — closing the
                        // snapshot/clear race the old markAllLinesAsSynced had.
                        val sentById = lineEntities.associateBy({ it.id }, {
                            Triple(it.actualQuantity, it.isCompleted, it.batchNumber)
                        })
                        documentLineDao.getLinesByDocumentIdSync(doc.id).forEach { current ->
                            val snap = sentById[current.id] ?: return@forEach
                            if (current.actualQuantity == snap.first &&
                                current.isCompleted == snap.second &&
                                current.batchNumber == snap.third) {
                                documentLineDao.markLineAsSynced(current.id)
                            }
                        }
                        // Adopt the server version only when the doc is now fully
                        // clean (updateDocumentVersion also forces is_dirty=0).
                        // If edits raced in, leave the doc dirty and its version
                        // stale so the next pass re-confirms.
                        val stillDirty = documentLineDao.getLinesByDocumentIdSync(doc.id).any { it.isDirty }
                        val version = ack?.version
                        if (!stillDirty && version != null) {
                            documentDao.updateDocumentVersion(doc.id, version.toInt(), System.currentTimeMillis())
                        } else if (!stillDirty) {
                            documentDao.markDocumentAsSynced(doc.id)
                        }
                    } else {
                        // No ack / rejected → keep dirty; next resync retries.
                        // A rejection (LOCK_LOST / WRONG_STATE) is also delivered
                        // as a typed SERVER_ERROR that drives M5″ recovery.
                        AppLog.w(TAG, "Re-sync not confirmed for ${doc.id}")
                    }
                } else {
                    // Legacy fallback (old server, no ack): clear on buffer-accept.
                    val sent = webSocketManager.sendMessage(message)
                    debugJournal.log(
                        eventType = ua.com.programmer.pick.data.debug.DebugEventType.RESYNC_DIRTY,
                        message = if (sent) "re-synced dirty document" else "re-sync send failed",
                        documentId = doc.id,
                        severity = if (sent) ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_INFO
                        else ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                        payload = mapOf(
                            "line_count" to lines.size,
                            "total_actual_qty" to lines.sumOf { it.actualQuantity },
                            "state" to doc.state
                        )
                    )
                    if (sent) {
                        sentCount++
                        documentLineDao.markAllLinesAsSynced(doc.id)
                        documentDao.markDocumentAsSynced(doc.id)
                    } else {
                        AppLog.w(TAG, "Failed to re-sync dirty document: ${doc.id}")
                    }
                }
            }
            sentCount
        } catch (e: Exception) {
            AppLog.w(TAG, "Error during dirty document re-sync: ${e.message}")
            0
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
                OperationType.STAGE_PAUSE -> {
                    val documentId = payloadJson.get("document_id")?.asString
                    val stage = payloadJson.get("stage")?.asString
                    if (documentId == null || stage == null) {
                        AppLog.e(TAG, "Missing document_id or stage in STAGE_PAUSE payload")
                        return null
                    }
                    SyncMessage.StagePause(id = id, timestamp = timestamp, documentId = documentId, stage = stage)
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
