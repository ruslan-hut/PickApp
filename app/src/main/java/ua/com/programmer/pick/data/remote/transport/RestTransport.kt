package ua.com.programmer.pick.data.remote.transport

import com.google.gson.JsonNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.api.DeviceApiException
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.data.remote.dto.LineBatchDto
import ua.com.programmer.pick.domain.model.ScannedBatch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-initiated REST implementation of [SyncTransport].
 *
 * REST is connectionless, so this holds no socket: [connect] simply marks the
 * transport "connected" and [loginUser] authenticates over HTTP. Every other
 * operation maps a request [SyncMessage] to a [DeviceRestClient] call and then
 * **emits the corresponding result message on [incomingMessages]** — exactly as
 * the legacy push path delivered server frames — so [SyncOrchestrator]'s existing
 * handlers run unchanged. [sendAndAwait] additionally returns the correlated
 * result to its caller (mirroring the legacy dual-delivery of results).
 *
 * Sync requests fan a poll loop into synthetic SYNC_DATA + SYNC_COMPLETE frames;
 * the ACK is folded into the next request's applied_cursors by DeviceRestClient,
 * so the client's Ack message is a no-op here.
 */
@Singleton
class RestTransport @Inject constructor(
    private val client: DeviceRestClient,
    private val messageParser: MessageParser,
    private val appPreferences: AppPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SyncTransport {

    private companion object {
        const val TAG = "RestTransport"
        const val LOCK_LOST = "LOCK_LOST"
        const val WRONG_STATE = "WRONG_STATE"
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _userAuthState = MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)
    override val userAuthState: StateFlow<UserAuthState> = _userAuthState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 256)
    override val incomingMessages: SharedFlow<SyncMessage> = _incomingMessages.asSharedFlow()

    // REST has no push — the orchestrator actively polls while a doc is held.
    override val requiresPolling: Boolean = true

    // --- Connection (no socket; "connected" == reachable over HTTP) ---

    override fun connect() {
        AppLog.i(TAG, "DOC_TRACE connect() -> Connected (auth=${_userAuthState.value::class.simpleName})")
        _connectionState.value = ConnectionState.Connected
    }

    override fun disconnect() {
        AppLog.w(TAG, "DOC_TRACE disconnect() -> Disconnected + NotAuthenticated", Throwable("disconnect call site"))
        _connectionState.value = ConnectionState.Disconnected
        _userAuthState.value = UserAuthState.NotAuthenticated
    }

    override fun forceReconnect() {
        AppLog.i(TAG, "DOC_TRACE forceReconnect() -> Connected (auth unchanged=${_userAuthState.value::class.simpleName})")
        _connectionState.value = ConnectionState.Connected
    }

    override fun verifyConnectionHealth() {
        // No persistent socket to probe — REST requests carry their own health.
        if (_connectionState.value !is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Connected
        }
    }

    override fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    override fun isUserAuthenticated(): Boolean = _userAuthState.value is UserAuthState.Authenticated

    // --- Auth ---

    override suspend fun loginUser(login: String, password: String): UserLoginResult {
        _userAuthState.value = UserAuthState.Authenticating
        return client.login(login, password).fold(
            onSuccess = { r ->
                _connectionState.value = ConnectionState.Connected
                _userAuthState.value = UserAuthState.Authenticated(
                    userId = r.userId,
                    userName = r.userName,
                    role = r.role,
                    offlineHash = r.offlineHash,
                    availableDocumentTypes = r.availableDocumentTypes?.map { it.toWire() },
                    heldStageLocks = r.heldStageLocks,
                    // The REST backend confirms every write with a 2xx body, which
                    // this transport surfaces as a DOCUMENT_UPDATE_RESULT — so the
                    // orchestrator's clear-dirty-only-on-ack path is always valid.
                    supportsUpdateAck = true,
                    openTasks = r.openTasks,
                )
                AppLog.d(TAG, "User authenticated (REST): ${r.userName} (${r.role})")
                UserLoginResult(
                    success = true,
                    userId = r.userId,
                    userExternalId = r.userExternalId,
                    userName = r.userName,
                    role = r.role,
                    offlineHash = r.offlineHash,
                    tenantId = r.tenantId,
                    availableDocumentTypes = r.availableDocumentTypes?.map { it.toWire() },
                    debugJournalEnabled = r.debugJournalEnabled,
                    scanOnly = r.scanOnly,
                    openTasks = r.openTasks,
                )
            },
            onFailure = { e ->
                val error = e.message ?: "Login failed"
                _userAuthState.value = UserAuthState.AuthFailed(error)
                AppLog.w(TAG, "User authentication failed (REST): $error")
                UserLoginResult(success = false, errorMessage = error, errorCode = (e as? DeviceApiException)?.code)
            },
        )
    }

    // --- Send ---

    override fun sendMessage(message: SyncMessage): Boolean {
        scope.launch { execute(message) }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : SyncMessage> sendAndAwait(
        message: SyncMessage,
        responseType: Class<T>,
        timeoutMs: Long,
    ): T? {
        val result = withTimeoutOrNull(timeoutMs) { execute(message) }
        return if (result != null && responseType.isInstance(result)) result as T else null
    }

    /**
     * Performs the REST call for [message], emits any resulting server-shaped
     * message(s) on [incomingMessages], and returns the primary correlated
     * result (or null for sync / fire-and-forget messages).
     */
    private suspend fun execute(message: SyncMessage): SyncMessage? {
        val result: SyncMessage? = when (message) {
            is SyncMessage.StageLock -> client.stageLock(message.documentId, message.stage).fold(
                { stageLockResult(it) },
                { stageLockFailure(message.documentId, message.stage, it) },
            )

            is SyncMessage.StageUnlock -> client.stageUnlock(message.documentId, message.stage).fold(
                { stageLockResult(it) },
                { stageLockFailure(message.documentId, message.stage, it) },
            )

            is SyncMessage.StagePause -> client.stagePause(message.documentId, message.stage).fold(
                { stageLockResult(it) },
                { stageLockFailure(message.documentId, message.stage, it) },
            )

            is SyncMessage.StageComplete -> client.stageComplete(message.documentId, message.stage).fold(
                {
                    SyncMessage.StageCompleteResult(
                        newId(), now(), it.documentId, it.stage, it.success,
                        it.state, it.completedAt, it.version, it.error,
                    )
                },
                {
                    SyncMessage.StageCompleteResult(
                        newId(), now(), message.documentId, message.stage, false,
                        error = it.message,
                    )
                },
            )

            is SyncMessage.DocumentUpdate -> {
                val lines = message.lines.map {
                    DeviceDto.DocumentLineUpdate(
                        lineNumber = it.lineNumber,
                        actualQuantity = it.actualQuantity,
                        batchNumber = it.batchNumber,
                        batches = it.batches?.map { b -> LineBatchDto(b.batchId, b.qty) },
                        isCompleted = it.isCompleted,
                        notes = it.notes,
                    )
                }
                client.updateDocument(message.documentId, lines).fold(
                    onSuccess = {
                        SyncMessage.DocumentUpdateResult(
                            newId(), now(), it.documentId, message.id, true, it.version, null,
                        )
                    },
                    onFailure = { e ->
                        val code = (e as? DeviceApiException)?.code
                        // Mirror the legacy write-path rejection: a typed SERVER_ERROR
                        // drives M5″ lock-loss recovery, plus the failed ack so the
                        // awaiter fails fast.
                        if (code == LOCK_LOST || code == WRONG_STATE) {
                            emit(SyncMessage.ServerError(newId(), now(), code, e.message ?: code, null, message.documentId))
                        }
                        SyncMessage.DocumentUpdateResult(
                            newId(), now(), message.documentId, message.id, false, null, code,
                        )
                    },
                )
            }

            is SyncMessage.BoxAdd -> client.boxAdd(message.documentId, message.barcode, message.weight).fold(
                { SyncMessage.BoxAddResult(newId(), now(), it.success, it.box, it.error) },
                { SyncMessage.BoxAddResult(newId(), now(), false, null, it.message) },
            )

            is SyncMessage.BoxRemove -> client.boxRemove(message.documentId, message.boxNumber).fold(
                { SyncMessage.BoxRemoveResult(newId(), now(), it.success, it.boxNumber, it.error) },
                { SyncMessage.BoxRemoveResult(newId(), now(), false, message.boxNumber, it.message) },
            )

            is SyncMessage.BoxLookup -> client.boxLookup(message.barcode).fold(
                { SyncMessage.BoxLookupResult(newId(), now(), it.success, it.box, it.error) },
                { SyncMessage.BoxLookupResult(newId(), now(), false, null, it.message) },
            )

            is SyncMessage.ProductLookup -> client.productLookup(message.barcode).fold(
                {
                    SyncMessage.ProductLookupResult(
                        newId(), now(), it.success, it.product, it.error,
                        batch = it.batch?.let { b -> ScannedBatch(b.id, b.number, b.expiryDate) },
                    )
                },
                { SyncMessage.ProductLookupResult(newId(), now(), false, null, it.message) },
            )

            is SyncMessage.LinePhotoUploadUrl -> client.linePhotoUploadUrl(message.documentId, message.lineNumber).fold(
                {
                    SyncMessage.LinePhotoUploadUrlResult(
                        newId(), now(), it.success, it.documentId, it.lineNumber, it.uploadUrl, it.expiresAt, it.error,
                    )
                },
                {
                    SyncMessage.LinePhotoUploadUrlResult(
                        newId(), now(), false, message.documentId, message.lineNumber, null, null, it.message,
                    )
                },
            )

            is SyncMessage.BoxPickupConfirm ->
                client.boxPickup(message.barcode, message.offlineSeq.toLong(), message.clientTs).fold(
                    { SyncMessage.BoxPickupConfirmResult(newId(), now(), it.success, it.barcode, it.wasNoop) },
                    { SyncMessage.BoxPickupConfirmResult(newId(), now(), false, message.barcode, false) },
                )

            is SyncMessage.BoxDeliveryConfirm ->
                client.boxDelivery(message.barcode, message.offlineSeq.toLong(), message.clientTs).fold(
                    { SyncMessage.BoxDeliveryConfirmResult(newId(), now(), it.success, it.barcode, it.wasNoop) },
                    { SyncMessage.BoxDeliveryConfirmResult(newId(), now(), false, message.barcode, false) },
                )

            is SyncMessage.ErrorReport -> {
                client.errorReport(
                    DeviceDto.ErrorReportRequest(message.errorType, message.message, message.stackTrace, message.metadata),
                )
                null
            }

            is SyncMessage.DebugEventBatch -> {
                val events = message.events.map {
                    DeviceDto.DebugEvent(
                        id = it.id, userId = it.userId, documentId = it.documentId, stage = it.stage,
                        eventType = it.eventType, severity = it.severity, message = it.message,
                        payloadJson = it.payloadJson, createdAt = it.createdAt,
                    )
                }
                client.debugEvents(DeviceDto.DebugEventBatchRequest(message.tenantId, message.deviceId, events)).fold(
                    { SyncMessage.DebugEventBatchResult(newId(), now(), it.success, it.acceptedIds ?: emptyList(), it.error) },
                    { SyncMessage.DebugEventBatchResult(newId(), now(), false, emptyList(), it.message) },
                )
            }

            is SyncMessage.TaskStart ->
                taskResult(client.taskStart(message.taskType, message.documentId))

            is SyncMessage.TaskGet -> taskResult(client.taskGet(message.taskId))

            is SyncMessage.TaskAction -> taskResult(
                client.taskAction(
                    message.taskId,
                    DeviceDto.TaskActionRequest(
                        operationId = message.operationId,
                        stepId = message.stepId,
                        action = message.action,
                        value = message.value,
                        quantity = message.quantity,
                        lineKey = message.lineKey,
                        lineNumber = message.lineNumber,
                    ),
                ),
            )

            is SyncMessage.TaskCancel -> taskResult(client.taskCancel(message.taskId, message.operationId))

            is SyncMessage.TaskOpen -> client.taskOpen().fold(
                { SyncMessage.TaskOpenResult(newId(), now(), true, it) },
                { e ->
                    SyncMessage.TaskOpenResult(
                        newId(), now(), false, emptyList(), (e as? DeviceApiException)?.code, e.message,
                    )
                },
            )

            is SyncMessage.SyncRequest -> { runSync(message.entityTypes, message.cursors, full = false); null }
            is SyncMessage.FullSyncRequest -> { runSync(message.entityTypes, null, full = true); null }
            is SyncMessage.DocumentListRefresh -> { runListRefresh(message.documentType); null }
            is SyncMessage.DocumentProducts -> { runDocumentProducts(message.documentId); null }

            // Cursor commit rides applied_cursors on the next sync; heartbeat is a no-op.
            is SyncMessage.Ack, is SyncMessage.Ping -> null

            else -> {
                AppLog.w(TAG, "unhandled outbound message type: ${message.type}")
                null
            }
        }
        result?.let { emit(it) }
        return result
    }

    // --- Sync helpers ---

    private suspend fun runSync(entityTypes: List<String>, cursors: Map<String, String>?, full: Boolean) {
        var applied = cursors
        var isFull = full
        AppLog.i(TAG, "DOC_TRACE delta sync start full=$full cursors=$cursors")
        while (true) {
            val resp = client.sync(entityTypes, applied, isFull).getOrElse {
                AppLog.e(TAG, "DOC_TRACE delta sync HTTP FAILED: ${it.message}")
                return
            }
            val summary = resp.entities?.joinToString(separator = ", ") { e ->
                val n = e.data?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
                "${e.entityType}=$n(full=${e.fullSet})"
            } ?: "none"
            AppLog.i(TAG, "DOC_TRACE delta sync page OK hasMore=${resp.hasMore} entities=[$summary] nextCursors=${resp.nextCursors}")
            isFull = false
            emitEntities(resp)
            resp.nextCursors?.let { applied = it }
            if (!resp.hasMore) {
                emit(SyncMessage.SyncComplete(newId(), now(), newId(), resp.nextCursors ?: applied ?: emptyMap()))
                return
            }
        }
    }

    private suspend fun runListRefresh(documentType: String?) {
        val resp = client.listDocuments(documentType).getOrElse {
            AppLog.e(TAG, "DOC_TRACE listRefresh HTTP FAILED (type=$documentType): ${it.message}")
            return
        }
        val summary = resp.entities?.joinToString(separator = ", ") { e ->
            val n = e.data?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
            "${e.entityType}=$n(full=${e.fullSet})"
        } ?: "none"
        AppLog.i(TAG, "DOC_TRACE listRefresh HTTP OK (type=$documentType) entities=[$summary]")
        emitEntities(resp)
        emit(SyncMessage.SyncComplete(newId(), now(), newId(), resp.nextCursors ?: emptyMap()))
    }

    private suspend fun runDocumentProducts(documentId: String) {
        val resp = client.documentProducts(documentId).getOrElse {
            AppLog.e(TAG, "document products failed: ${it.message}")
            return
        }
        emitEntities(resp)
        emit(SyncMessage.SyncComplete(newId(), now(), newId(), emptyMap()))
    }

    private suspend fun emitEntities(resp: DeviceDto.SyncResponse) {
        // The server repeats the device "scan only" option on sync, list refresh
        // and document products, so a tenant-admin toggle applies without a
        // re-login — the list and detail screens never call /device/sync.
        resp.scanOnly?.let { appPreferences.setScanOnly(it) }
        resp.entities?.forEach { e ->
            emit(
                SyncMessage.SyncData(
                    id = newId(),
                    timestamp = now(),
                    entityType = e.entityType,
                    data = e.data ?: JsonNull.INSTANCE,
                    deletedIds = e.deletedIds,
                    fullSet = e.fullSet,
                    visibleIds = e.visibleIds,
                ),
            )
        }
    }

    // --- Mapping helpers ---

    private fun stageLockResult(r: DeviceDto.StageLockResult): SyncMessage.StageLockResult =
        SyncMessage.StageLockResult(newId(), now(), r.documentId, r.stage, r.success, r.lockedBy, r.error)

    private fun stageLockFailure(documentId: String, stage: String, e: Throwable): SyncMessage.StageLockResult =
        SyncMessage.StageLockResult(newId(), now(), documentId, stage, false, null, e.message)

    private fun taskResult(result: Result<DeviceDto.TaskResponse>): SyncMessage.TaskResult = result.fold(
        { SyncMessage.TaskResult(newId(), now(), true, it) },
        { e -> SyncMessage.TaskResult(newId(), now(), false, null, (e as? DeviceApiException)?.code, e.message) },
    )

    private suspend fun emit(message: SyncMessage) {
        _incomingMessages.emit(message)
    }

    private fun newId(): String = messageParser.generateMessageId()
    private fun now(): String = messageParser.getCurrentTimestamp()
}

private fun DeviceDto.AvailableDocumentType.toWire(): AvailableDocumentTypeDto =
    AvailableDocumentTypeDto(
        code = code,
        description = description,
        allowsOverPlan = allowsOverPlan,
        allowsExtraLines = allowsExtraLines,
        requiresPlan = requiresPlan,
        mode = mode,
        wmsFlow = wmsFlow,
    )
