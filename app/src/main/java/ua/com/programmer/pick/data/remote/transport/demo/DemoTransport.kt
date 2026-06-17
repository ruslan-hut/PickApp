package ua.com.programmer.pick.data.remote.transport.demo

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
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.remote.transport.AvailableDocumentTypeDto
import ua.com.programmer.pick.data.remote.transport.ConnectionState
import ua.com.programmer.pick.data.remote.transport.MessageParser
import ua.com.programmer.pick.data.remote.transport.SyncMessage
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.data.remote.transport.UserLoginResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline demo implementation of [SyncTransport]. Speaks no network at all —
 * every request is answered locally from [DemoServer], emitting the same
 * server-shaped [SyncMessage]s on [incomingMessages] as [RestTransport] would,
 * so the orchestrator's handlers and every ViewModel run unchanged.
 *
 * Only the operations a single-collector picking demo exercises are
 * implemented; anything else is a benign no-op. Selected by [RoutingTransport]
 * when the demo session flag is set.
 */
@Singleton
class DemoTransport @Inject constructor(
    private val server: DemoServer,
    private val messageParser: MessageParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SyncTransport {

    private companion object {
        const val TAG = "DemoTransport"
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _userAuthState = MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)
    override val userAuthState: StateFlow<UserAuthState> = _userAuthState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 256)
    override val incomingMessages: SharedFlow<SyncMessage> = _incomingMessages.asSharedFlow()

    // Nothing changes behind the demo's back, so the orchestrator never needs to poll.
    override val requiresPolling: Boolean = false

    override fun connect() {
        _connectionState.value = ConnectionState.Connected
    }

    override fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
        _userAuthState.value = UserAuthState.NotAuthenticated
    }

    override fun forceReconnect() {
        _connectionState.value = ConnectionState.Connected
    }

    override fun verifyConnectionHealth() {
        if (_connectionState.value !is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Connected
        }
    }

    override fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    override fun isUserAuthenticated(): Boolean = _userAuthState.value is UserAuthState.Authenticated

    override suspend fun loginUser(login: String, password: String): UserLoginResult {
        _userAuthState.value = UserAuthState.Authenticating
        // Seed once per process; a re-auth preserves any in-progress demo work.
        server.ensureSeeded()
        _connectionState.value = ConnectionState.Connected
        val docType = AvailableDocumentTypeDto(
            code = DemoServer.DOCUMENT_TYPE,
            description = DemoServer.DOCUMENT_TYPE_DESCRIPTION,
            allowsOverPlan = false,
            allowsExtraLines = false,
            requiresPlan = true,
        )
        _userAuthState.value = UserAuthState.Authenticated(
            userId = DemoServer.USER_ID,
            userName = DemoServer.USER_NAME,
            role = "COLLECTOR",
            offlineHash = null,
            availableDocumentTypes = listOf(docType),
            heldStageLocks = null,
            supportsUpdateAck = true,
        )
        AppLog.i(TAG, "demo session authenticated")
        return UserLoginResult(
            success = true,
            userId = DemoServer.USER_ID,
            userExternalId = DemoServer.USER_ID,
            userName = DemoServer.USER_NAME,
            role = "COLLECTOR",
            availableDocumentTypes = listOf(docType),
        )
    }

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
     * Answer [message] from the local model, emitting any server-shaped result on
     * [incomingMessages] and returning the correlated result (mirrors
     * RestTransport.execute so the orchestrator's dual delivery is preserved).
     */
    private suspend fun execute(message: SyncMessage): SyncMessage? {
        val result: SyncMessage? = when (message) {
            is SyncMessage.StageLock -> {
                server.lock(message.documentId)
                SyncMessage.StageLockResult(
                    newId(), now(), message.documentId, message.stage,
                    success = true, lockedBy = DemoServer.USER_ID,
                )
            }

            is SyncMessage.StageComplete -> {
                val r = server.complete(message.documentId)
                SyncMessage.StageCompleteResult(
                    newId(), now(), message.documentId, message.stage,
                    success = r != null, state = r?.state, version = r?.version,
                )
            }

            is SyncMessage.StageUnlock -> {
                server.unlock(message.documentId)
                null
            }

            // Pause keeps the in-process state; nothing to mutate or emit.
            is SyncMessage.StagePause -> null

            is SyncMessage.DocumentUpdate -> {
                server.applyUpdate(message.documentId, message.lines)
                SyncMessage.DocumentUpdateResult(
                    newId(), now(), message.documentId, message.id, success = true,
                )
            }

            is SyncMessage.SyncRequest,
            is SyncMessage.FullSyncRequest,
            is SyncMessage.DocumentListRefresh -> {
                emitFullSet()
                null
            }

            is SyncMessage.DocumentProducts -> {
                emit(productsData())
                emit(syncComplete())
                null
            }

            else -> null
        }
        result?.let { emit(it) }
        return result
    }

    private suspend fun emitFullSet() {
        emit(documentsData())
        emit(productsData())
        emit(syncComplete())
    }

    private fun documentsData() = SyncMessage.SyncData(
        id = newId(),
        timestamp = now(),
        entityType = Constants.SyncEntity.DOCUMENTS,
        data = server.documentsJson(),
        deletedIds = null,
        fullSet = true,
    )

    private fun productsData() = SyncMessage.SyncData(
        id = newId(),
        timestamp = now(),
        entityType = Constants.SyncEntity.PRODUCTS,
        data = server.productsJson(),
        deletedIds = null,
        fullSet = true,
    )

    private fun syncComplete() = SyncMessage.SyncComplete(newId(), now(), newId(), emptyMap())

    private suspend fun emit(message: SyncMessage) {
        _incomingMessages.emit(message)
    }

    private fun newId(): String = messageParser.generateMessageId()
    private fun now(): String = messageParser.getCurrentTimestamp()
}
