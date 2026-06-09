package ua.com.programmer.pick.data.remote.websocket

import ua.com.programmer.pick.core.util.AppLog
import kotlinx.coroutines.CancellableContinuation
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Clock
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Connection state for WebSocket
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String, val code: Int? = null) : ConnectionState()
    data object Reconnecting : ConnectionState()
}

/**
 * User authentication state (after WebSocket connection)
 */
sealed class UserAuthState {
    data object NotAuthenticated : UserAuthState()
    data object Authenticating : UserAuthState()
    data class Authenticated(
        val userId: String,
        val userName: String,
        val role: String,
        val offlineHash: String?,
        val availableDocumentTypes: List<AvailableDocumentTypeDto>? = null,
        // ERP external_ids of documents the server reports this (user, device)
        // pair still holds an in-process stage lock for. Forwarded from the
        // USER_LOGIN_RESULT payload so the orchestrator can rebuild its
        // session-scoped `heldStageLocks` set immediately — closing the
        // post-restart race where inbound SYNC_DATA could otherwise
        // overwrite worker-owned line data while the device thought the
        // lock was gone.
        val heldStageLocks: List<String>? = null,
        // Forwarded from USER_LOGIN_RESULT — whether the server confirms
        // DOCUMENT_UPDATE writes with a DOCUMENT_UPDATE_RESULT frame. Gates the
        // orchestrator's clear-dirty-only-on-ack path (legacy fallback when false).
        val supportsUpdateAck: Boolean = false
    ) : UserAuthState()
    data class AuthFailed(val error: String) : UserAuthState()
}

/**
 * Result of user login operation
 */
data class UserLoginResult(
    val success: Boolean,
    val userId: String? = null,
    // Worker's ERP external_id, forwarded from the server so UserRepositoryImpl
    // can populate UserEntity.externalId at login time.
    val userExternalId: String? = null,
    val userName: String? = null,
    val role: String? = null,
    val offlineHash: String? = null,
    val tenantId: String? = null,
    val availableDocumentTypes: List<AvailableDocumentTypeDto>? = null,
    val debugJournalEnabled: Boolean? = null,
    val errorMessage: String? = null
)

/**
 * Manages WebSocket connection for real-time synchronization.
 *
 * Authentication Flow (per protocol):
 * 1. Stage 1: Device Connection - Connect with app_token + device_id
 * 2. Stage 2: User Login - After WebSocket established, send USER_LOGIN message
 *
 * Handles automatic reconnection with exponential backoff.
 * Implements PING/PONG keep-alive mechanism.
 */
@Singleton
class WebSocketManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val messageParser: MessageParser,
    private val appPreferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val debugJournal: ua.com.programmer.pick.data.debug.DebugJournal,
    private val clock: Clock,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SyncTransport {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val REQUEST_TIMEOUT_MS = 30000L  // 30 seconds timeout for request/response
        private const val HEALTH_CHECK_TIMEOUT_MS = 7000L  // resume-probe pong wait
        private const val DEVICE_PENDING_CLOSE_CODE = 4003
        private const val DEVICE_REJECTED_CLOSE_CODE = 4004
        private const val NOT_AUTHENTICATED_ERROR_CODE = "NOT_AUTHENTICATED"
    }

    private val scope = CoroutineScope(ioDispatcher)

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var healthCheckJob: Job? = null
    private var reconnectAttempts = 0
    private var lastPongReceived = clock.now()
    // Debounce for half-open-socket teardown. A wedged socket makes every
    // ws.send() return false; without this guard a burst of failed sends (or
    // a failed ping plus failed doc updates) would each fire their own
    // forceReconnect and thrash. @Volatile so the value is visible across the
    // io threads sendMessage runs on.
    @Volatile
    private var lastStaleEscalationAt = 0L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _userAuthState = MutableStateFlow<UserAuthState>(UserAuthState.NotAuthenticated)
    override val userAuthState: StateFlow<UserAuthState> = _userAuthState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<SyncMessage> = _incomingMessages.asSharedFlow()

    // Pending responses awaiting server reply (for request/response correlation)
    private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()

    private var isManuallyDisconnected = false

    /**
     * Connect to WebSocket server (Stage 1: Device Connection)
     * Uses app_token + device_id for initial connection.
     */
    override fun connect() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            AppLog.d(TAG, "Already connected or connecting, skipping")
            return
        }

        isManuallyDisconnected = false
        reconnectAttempts = 0
        startConnection()
    }

    /**
     * Disconnect from WebSocket server
     */
    override fun disconnect() {
        isManuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        stopPingTimer()

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        _connectionState.value = ConnectionState.Disconnected
        _userAuthState.value = UserAuthState.NotAuthenticated
        pendingResponses.clear()

        AppLog.d(TAG, "WebSocket disconnected manually")
    }

    /**
     * Verify the connection is actually alive — call on app foreground/resume.
     *
     * After a long Doze sleep the PING timer (a coroutine `delay` loop) is
     * frozen along with the CPU, so no PONG-timeout is armed. `connectionState`
     * can then read `Connected` while the socket is already dead server-side.
     * A lock attempt issued in that window is sent into a black hole and only
     * fails after the 30s request timeout.
     *
     * This probes a `Connected` socket with an immediate PING and forces a
     * reconnect if no PONG arrives; if the state is already non-connected it
     * kicks off a normal connect.
     */
    override fun verifyConnectionHealth() {
        if (healthCheckJob?.isActive == true) {
            AppLog.d(TAG, "Health check already running, skipping")
            return
        }
        when (_connectionState.value) {
            is ConnectionState.Connected -> {
                healthCheckJob = scope.launch {
                    if (!probeConnection()) {
                        AppLog.w(TAG, "Resume health check failed - stale socket, forcing reconnect")
                        debugJournal.log(
                            eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_DISCONNECT,
                            message = "resume health check failed - stale socket",
                            severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
                        )
                        forceReconnect()
                    }
                }
            }
            is ConnectionState.Disconnected, is ConnectionState.Error -> {
                AppLog.d(TAG, "Resume health check: not connected, connecting")
                connect()
            }
            else -> {
                // Connecting / Reconnecting — already in progress, leave it
            }
        }
    }

    /**
     * Send a PING and wait up to [HEALTH_CHECK_TIMEOUT_MS] for a fresh PONG.
     * @return true if a PONG advanced [lastPongReceived], false otherwise.
     */
    private suspend fun probeConnection(): Boolean {
        if (webSocket == null) return false
        val before = lastPongReceived
        sendPing()
        val deadline = clock.now() + HEALTH_CHECK_TIMEOUT_MS
        while (clock.now() < deadline) {
            delay(200)
            if (lastPongReceived > before) return true
        }
        return false
    }

    /**
     * Tear down the current socket and reconnect immediately, regardless of
     * the cached connection state.
     *
     * Unlike [connect], this does not early-return when the state still reads
     * `Connected` — required to recover from a stale half-open socket left
     * behind by a long Doze sleep.
     */
    override fun forceReconnect() {
        AppLog.d(TAG, "Force reconnect requested")
        isManuallyDisconnected = false
        healthCheckJob?.cancel()
        healthCheckJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        stopPingTimer()

        // Drop the old socket before cancelling it: the listener callbacks
        // key off identity, so onClosed/onFailure from the old socket become
        // no-ops once the field no longer points at it.
        val old = webSocket
        webSocket = null
        old?.cancel()

        // Fail any in-flight request/response waiters so callers unblock now.
        pendingResponses.forEach { (_, response) ->
            @Suppress("UNCHECKED_CAST")
            (response.continuation as? CancellableContinuation<SyncMessage>)?.cancel()
        }
        pendingResponses.clear()

        _userAuthState.value = UserAuthState.NotAuthenticated
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Reconnecting
        startConnection()
    }

    /**
     * Tear down a connection that *looks* alive but isn't.
     *
     * The cached [_connectionState] can read `Connected` while the underlying
     * TCP socket is dead (radio dropped mid-session, NAT timed out, long Doze).
     * In that half-open state `ws.send()` returns false and PONGs stop coming,
     * yet OkHttp never delivers `onClosed`/`onFailure` because a graceful
     * `close()` handshake can't complete over dead TCP — so the socket stays
     * wedged indefinitely (observed: 83 minutes of failed sends with no
     * reconnect). [forceReconnect] hard-cancels instead of closing, which is
     * the only reliable recovery here.
     *
     * Guarded two ways: a time window so a burst of failed sends triggers at
     * most one teardown, and a state check so we don't pile on while a
     * reconnect is already underway. Runs forceReconnect on [scope] rather
     * than inline, so when the caller is the ping coroutine (sendPing → send
     * → here) cancelling pingJob doesn't abort the teardown mid-flight.
     */
    private fun escalateStaleSocket(reason: String) {
        val now = clock.now()
        if (now - lastStaleEscalationAt < HEALTH_CHECK_TIMEOUT_MS) return
        when (_connectionState.value) {
            is ConnectionState.Reconnecting, is ConnectionState.Connecting -> return
            else -> {}
        }
        lastStaleEscalationAt = now
        AppLog.w(TAG, "Stale socket detected ($reason) - forcing reconnect")
        debugJournal.log(
            eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_DISCONNECT,
            message = "stale socket: $reason",
            severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
        )
        scope.launch { forceReconnect() }
    }

    /**
     * Login user after WebSocket connection (Stage 2: User Login)
     * @param login User login
     * @param password User password
     * @return UserLoginResult with success/failure and user info
     */
    override suspend fun loginUser(login: String, password: String): UserLoginResult {
        if (_connectionState.value !is ConnectionState.Connected) {
            return UserLoginResult(success = false, errorMessage = "WebSocket not connected")
        }

        _userAuthState.value = UserAuthState.Authenticating

        val message = SyncMessage.UserLogin(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp(),
            login = login,
            password = password
        )

        val response = sendAndAwait(message, SyncMessage.UserLoginResult::class.java, REQUEST_TIMEOUT_MS)

        return if (response != null && response.success) {
            _userAuthState.value = UserAuthState.Authenticated(
                userId = response.userId ?: "",
                userName = response.userName ?: "",
                role = response.role ?: "",
                offlineHash = response.offlineHash,
                availableDocumentTypes = response.availableDocumentTypes,
                heldStageLocks = response.heldStageLocks,
                supportsUpdateAck = response.supportsUpdateAck
            )
            // Reconcile the persisted current user ID with what the server
            // authoritatively reports. If the user's MongoDB _id changes
            // server-side (e.g. account recreated), auto-login would otherwise
            // leave a stale hex in preferences and break lock-owner comparisons.
            response.userId?.let { serverUserId ->
                val storedUserId = appPreferences.currentUserId.first()
                if (storedUserId != serverUserId) {
                    AppLog.w(TAG, "Current user ID drift detected: stored=$storedUserId server=$serverUserId — updating preferences")
                    appPreferences.setCurrentUserId(serverUserId)
                }
            }
            AppLog.d(TAG, "User authenticated: ${response.userName} (${response.role})")
            UserLoginResult(
                success = true,
                userId = response.userId,
                userExternalId = response.userExternalId,
                userName = response.userName,
                role = response.role,
                offlineHash = response.offlineHash,
                tenantId = response.tenantId,
                availableDocumentTypes = response.availableDocumentTypes,
                debugJournalEnabled = response.debugJournalEnabled
            )
        } else {
            val error = response?.errorMessage ?: "Login failed"
            _userAuthState.value = UserAuthState.AuthFailed(error)
            AppLog.w(TAG, "User authentication failed: $error")
            UserLoginResult(success = false, errorMessage = error)
        }
    }

    /**
     * Check if user is authenticated (Stage 2 complete)
     */
    override fun isUserAuthenticated(): Boolean = _userAuthState.value is UserAuthState.Authenticated

    /**
     * Send a message through WebSocket.
     * Note: Some operations require user authentication (see protocol).
     * @return true if message was sent successfully
     */
    override fun sendMessage(message: SyncMessage): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value !is ConnectionState.Connected) {
            AppLog.w(TAG, "Cannot send message - not connected")
            return false
        }

        // Check if message requires user authentication
        if (requiresUserAuth(message) && !isUserAuthenticated()) {
            AppLog.w(TAG, "Cannot send ${message.type} - user not authenticated")
            return false
        }

        return try {
            val json = messageParser.serializeMessage(message)

            // Enforce max message size
            if (json.length > Constants.Network.WEBSOCKET_MAX_MESSAGE_SIZE) {
                AppLog.w(TAG, "Message exceeds max size (${json.length} > ${Constants.Network.WEBSOCKET_MAX_MESSAGE_SIZE}), type: ${message.type}")
                return false
            }

            val sent = ws.send(json)
            if (sent) {
                AppLog.d(TAG, "Message sent: ${message.type}")
            } else {
                AppLog.e(TAG, "Failed to send message")
                debugJournal.log(
                    eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_SEND_FAIL,
                    message = "ws.send returned false for ${message.type}",
                    severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
                )
                // send() returning false while we still read Connected means the
                // socket is half-open/closing — recover instead of silently
                // deferring every write to dirty re-sync on a dead connection.
                escalateStaleSocket("send returned false for ${message.type}")
            }
            sent
        } catch (e: Exception) {
            AppLog.e(TAG, "Error sending message: ${e.message}", e)
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_SEND_FAIL,
                message = "exception sending ${message.type}: ${e.message}",
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR
            )
            escalateStaleSocket("exception sending ${message.type}")
            false
        }
    }

    /**
     * Check if a message type requires user authentication
     */
    private fun requiresUserAuth(message: SyncMessage): Boolean {
        return when (message) {
            // Operations allowed without user login
            is SyncMessage.Ping -> false
            is SyncMessage.UserLogin -> false
            is SyncMessage.ErrorReport -> false
            // All other operations require user authentication
            else -> true
        }
    }

    /**
     * Send a message and wait for a correlated response
     * @param message The message to send
     * @param responseType The expected response message type
     * @param timeoutMs Timeout in milliseconds (default 30 seconds)
     * @return The response message, or null if timeout or error
     */
    override suspend fun <T : SyncMessage> sendAndAwait(
        message: SyncMessage,
        responseType: Class<T>,
        timeoutMs: Long
    ): T? {
        // Extract correlation ID from outgoing message for response matching
        val correlationId = extractOutgoingCorrelationId(message)

        if (!sendMessage(message)) {
            return null
        }

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T?> { continuation ->
                pendingResponses[message.id] = PendingResponse(
                    messageId = message.id,
                    expectedType = responseType,
                    continuation = continuation,
                    correlationId = correlationId,
                    createdAt = clock.now()
                )

                continuation.invokeOnCancellation {
                    pendingResponses.remove(message.id)
                }
            }
        }
        if (result == null) {
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_ACK_TIMEOUT,
                message = "no ack within ${timeoutMs}ms for ${message.type}",
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN
            )
        }
        return result
    }

    /**
     * Extract correlation ID from outgoing messages for response matching
     */
    private fun extractOutgoingCorrelationId(message: SyncMessage): String? {
        return when (message) {
            is SyncMessage.StageLock -> message.documentId
            is SyncMessage.StageComplete -> message.documentId
            // Correlate by the request's own message id, not document_id —
            // multiple DOCUMENT_UPDATEs for one doc can be in flight at once.
            is SyncMessage.DocumentUpdate -> message.id
            is SyncMessage.ProductLookup -> message.barcode
            // Composite key: multiple lines' photo-URL requests can be in flight.
            is SyncMessage.LinePhotoUploadUrl -> "${message.documentId}:${message.lineNumber}"
            is SyncMessage.UserLogin -> message.login
            else -> null
        }
    }

    /**
     * Check if connected
     */
    override fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    private fun startConnection() {
        if (!networkMonitor.isCurrentlyConnected()) {
            AppLog.d(TAG, "No network connectivity, waiting...")
            _connectionState.value = ConnectionState.Disconnected
            scheduleReconnect()
            return
        }

        _connectionState.value = ConnectionState.Connecting
        _userAuthState.value = UserAuthState.NotAuthenticated

        val deviceId = appPreferences.getDeviceIdSync()
        val wsUrl = buildWebSocketUrl(deviceId)
        AppLog.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .header("X-App-Token", Constants.Network.APP_TOKEN)
            .build()

        // Create WebSocket client without OkHttp ping (we use our own)
        val wsClient = okHttpClient.newBuilder()
            .pingInterval(0, TimeUnit.SECONDS)  // Disable OkHttp ping
            .build()

        webSocket = wsClient.newWebSocket(request, createWebSocketListener())
    }

    /**
     * Build WebSocket URL with device_id in query parameter.
     * Format: ws://{host}:{port}/ws/connect?app_token={app_token}&device_id={device_id}&protocol_version=v2
     *
     * Note: app_token is also sent via X-App-Token header as fallback.
     *
     * protocol_version=v2 marks this client as having the ERP external_id
     * translation logic in SyncOrchestrator. The backend defaults to v1 when
     * absent and currently emits the same DTO format regardless, so the flag
     * is purely a forward marker for the eventual Phase 3 cleanup of the
     * dual-accept heuristic on the server side. See CLAUDE.md
     * "Canonical ID Principle".
     */
    private fun buildWebSocketUrl(deviceId: String): String {
        val baseUrl = Constants.Network.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')

        return "$baseUrl/ws/connect?app_token=${Constants.Network.APP_TOKEN}" +
            "&device_id=$deviceId&protocol_version=v2"
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            AppLog.d(TAG, "WebSocket opened: ${response.code}")
            reconnectAttempts = 0
            lastPongReceived = clock.now()
            _connectionState.value = ConnectionState.Connected

            // Start PING timer
            startPingTimer()

            // Attempt auto-login if credentials are stored
            attemptAutoLogin()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            AppLog.d(TAG, "Message received: ${text.take(200)}")

            val message = messageParser.parseMessage(text)
            if (message != null) {
                handleIncomingMessage(message)
            } else {
                AppLog.w(TAG, "Failed to parse message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.d(TAG, "WebSocket closing: $code - $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== this@WebSocketManager.webSocket) {
                AppLog.d(TAG, "Ignoring onClosed from stale WebSocket")
                return
            }
            AppLog.d(TAG, "WebSocket closed: $code - $reason")
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_DISCONNECT,
                message = "closed: $code $reason",
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_WARN,
                payload = mapOf("code" to code, "reason" to reason)
            )
            handleDisconnection(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== this@WebSocketManager.webSocket) {
                AppLog.d(TAG, "Ignoring onFailure from stale WebSocket: ${t.message}")
                return
            }
            AppLog.e(TAG, "WebSocket failure: ${t.message}", t)
            debugJournal.log(
                eventType = ua.com.programmer.pick.data.debug.DebugEventType.WS_DISCONNECT,
                message = "failure: ${t.message}",
                severity = ua.com.programmer.pick.data.debug.DebugJournal.SEVERITY_ERROR,
                payload = mapOf("http_code" to (response?.code ?: -1), "error" to t.message.orEmpty())
            )
            _connectionState.value = ConnectionState.Error(
                t.message ?: "Connection failed",
                response?.code
            )
            handleDisconnection(response?.code ?: -1, t.message ?: "Unknown error")
        }
    }

    /**
     * Attempt to login with stored credentials after connection.
     * Skips if user is already authenticated or authenticating.
     */
    private fun attemptAutoLogin() {
        scope.launch {
            // Skip if already authenticated or in the process of authenticating
            val currentAuthState = _userAuthState.value
            if (currentAuthState is UserAuthState.Authenticated ||
                currentAuthState is UserAuthState.Authenticating) {
                AppLog.d(TAG, "User already authenticated or authenticating, skipping auto-login")
                return@launch
            }

            val credentials = appPreferences.getUserCredentialsSync()
            if (credentials != null) {
                AppLog.d(TAG, "Attempting auto-login with stored credentials")
                val result = loginUser(credentials.first, credentials.second)
                // Manual login goes through UserRepositoryImpl which persists the
                // debug flag from the server. Auto-login bypasses that path, so
                // without this branch the local flag would only ever update when
                // the user explicitly logs out and in again — defeating the point
                // of a server-controlled toggle. Persist here too.
                if (result.success) {
                    result.debugJournalEnabled?.let { flag ->
                        try {
                            appPreferences.setDebugJournalEnabled(flag)
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Failed to persist debug flag from auto-login: ${e.message}")
                        }
                    }
                }
            } else {
                AppLog.d(TAG, "No stored credentials for auto-login")
            }
        }
    }

    private fun handleIncomingMessage(message: SyncMessage) {
        when (message) {
            is SyncMessage.Pong -> {
                handlePong(message)
            }
            is SyncMessage.UserLoginResult -> {
                // Handle login result - try to resolve pending response
                tryResolvePendingResponse(message)
            }
            is SyncMessage.ServerError -> {
                // Check if NOT_AUTHENTICATED error
                if (message.code == NOT_AUTHENTICATED_ERROR_CODE) {
                    AppLog.w(TAG, "Server requires user authentication for this operation")
                    _userAuthState.value = UserAuthState.NotAuthenticated
                }
                // Forward to observers
                scope.launch {
                    _incomingMessages.emit(message)
                }
            }
            else -> {
                // Check if this is a response to a pending request
                tryResolvePendingResponse(message)

                // Forward to observers
                scope.launch {
                    _incomingMessages.emit(message)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryResolvePendingResponse(message: SyncMessage) {
        // Extract correlation ID from incoming response
        val incomingCorrelationId = extractIncomingCorrelationId(message)

        // Try to find pending response by type AND correlation ID
        val pending = pendingResponses.entries.find { (_, response) ->
            response.expectedType.isInstance(message) &&
                (response.correlationId == null || incomingCorrelationId == null ||
                    response.correlationId == incomingCorrelationId)
        }

        if (pending != null) {
            pendingResponses.remove(pending.key)
            val continuation = pending.value.continuation as? CancellableContinuation<SyncMessage>
            continuation?.resume(message)
        }
    }

    /**
     * Extract correlation ID from incoming response messages
     */
    private fun extractIncomingCorrelationId(message: SyncMessage): String? {
        return when (message) {
            is SyncMessage.StageLockResult -> message.documentId
            is SyncMessage.StageCompleteResult -> message.documentId
            // Match against the request id the server echoed back.
            is SyncMessage.DocumentUpdateResult -> message.requestId
            is SyncMessage.LinePhotoUploadUrlResult ->
                if (message.documentId != null && message.lineNumber != null)
                    "${message.documentId}:${message.lineNumber}" else null
            is SyncMessage.ProductLookupResult -> null  // no document-level correlation
            is SyncMessage.UserLoginResult -> null  // single login at a time
            is SyncMessage.SyncComplete -> null  // single sync at a time
            else -> null
        }
    }

    // ============================================
    // PING/PONG Mechanism
    // ============================================

    private fun startPingTimer() {
        stopPingTimer()

        pingJob = scope.launch {
            var pingCount = 0
            while (true) {
                delay(Constants.Network.WEBSOCKET_PING_INTERVAL_SECONDS * 1000)

                if (_connectionState.value !is ConnectionState.Connected) {
                    break
                }

                // Only send a new ping if the previous one was answered
                val timeSinceLastPong = clock.now() - lastPongReceived
                if (timeSinceLastPong > Constants.Network.WEBSOCKET_PING_INTERVAL_SECONDS * 1000) {
                    // Previous ping still unanswered — let the existing timeout handle it
                    AppLog.w(TAG, "Previous PING still unanswered, skipping new PING")
                } else {
                    sendPing()
                    startPongTimeoutTimer()
                }

                // Cleanup stale pending responses every ~5 minutes (every 10th ping at 30s interval)
                pingCount++
                if (pingCount % 10 == 0) {
                    cleanupStalePendingResponses()
                }
            }
        }
    }

    private fun stopPingTimer() {
        pingJob?.cancel()
        pingJob = null
        pongTimeoutJob?.cancel()
        pongTimeoutJob = null
    }

    private fun sendPing() {
        val pingMessage = SyncMessage.Ping(
            id = messageParser.generateMessageId(),
            timestamp = messageParser.getCurrentTimestamp()
        )
        sendMessage(pingMessage)
        AppLog.d(TAG, "PING sent")
    }

    private fun startPongTimeoutTimer() {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = scope.launch {
            delay(Constants.Network.WEBSOCKET_PONG_TIMEOUT_SECONDS * 1000)

            // Check if PONG was received after last PING
            val timeSinceLastPong = clock.now() - lastPongReceived
            if (timeSinceLastPong > Constants.Network.WEBSOCKET_PONG_TIMEOUT_SECONDS * 1000) {
                // Hard-cancel + reconnect. A plain webSocket.close() here relies
                // on a close-handshake the dead peer can never ack, so onClosed
                // never fires and the socket wedges forever — the 83-minute
                // half-open incident. escalateStaleSocket routes through
                // forceReconnect (cancel()), which doesn't need the peer.
                AppLog.w(TAG, "PONG timeout - reconnecting")
                escalateStaleSocket("pong timeout")
            }
        }
    }

    private fun handlePong(message: SyncMessage.Pong) {
        lastPongReceived = clock.now()
        pongTimeoutJob?.cancel()
        AppLog.d(TAG, "PONG received")

        // Server piggybacks the per-device debug-journal toggle here so a
        // support flip propagates within ~30s without re-login. Only write
        // when the value actually flipped — DataStore writes shouldn't churn
        // every keepalive. Null = older server build, leave local flag alone.
        message.debugJournalEnabled?.let { serverFlag ->
            if (serverFlag != debugJournal.enabled.value) {
                AppLog.i(TAG, "PONG: debug_journal flag changed -> $serverFlag")
                scope.launch {
                    try {
                        appPreferences.setDebugJournalEnabled(serverFlag)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Failed to persist debug flag from PONG: ${e.message}")
                    }
                }
            }
        }
    }

    // ============================================
    // Reconnection Logic
    // ============================================

    private fun handleDisconnection(code: Int, reason: String) {
        webSocket = null
        stopPingTimer()
        _userAuthState.value = UserAuthState.NotAuthenticated

        // Clean up stale pending responses before clearing all
        cleanupStalePendingResponses()

        // Cancel all pending responses
        pendingResponses.forEach { (_, response) ->
            @Suppress("UNCHECKED_CAST")
            val continuation = response.continuation as? CancellableContinuation<SyncMessage>
            continuation?.cancel()
        }
        pendingResponses.clear()

        if (!isManuallyDisconnected) {
            // Check for device-related errors (should not auto-reconnect)
            when (code) {
                DEVICE_PENDING_CLOSE_CODE -> {
                    AppLog.w(TAG, "Device is PENDING approval - not reconnecting")
                    _connectionState.value = ConnectionState.Error("Device pending approval", code)
                    return
                }
                DEVICE_REJECTED_CLOSE_CODE -> {
                    AppLog.w(TAG, "Device is REJECTED - not reconnecting")
                    _connectionState.value = ConnectionState.Error("Device rejected", code)
                    return
                }
                401 -> {
                    AppLog.w(TAG, "Invalid app token - not reconnecting")
                    _connectionState.value = ConnectionState.Error("Invalid app token", code)
                    return
                }
                403 -> {
                    AppLog.w(TAG, "Device forbidden (${reason}) - not reconnecting")
                    _connectionState.value = ConnectionState.Error("Device forbidden: $reason", code)
                    return
                }
            }

            _connectionState.value = ConnectionState.Reconnecting
            scheduleReconnect()
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun scheduleReconnect() {
        if (isManuallyDisconnected) {
            return
        }

        if (reconnectAttempts >= Constants.Network.WEBSOCKET_MAX_RECONNECT_ATTEMPTS) {
            AppLog.w(TAG, "Max reconnect attempts reached")
            _connectionState.value = ConnectionState.Error("Max reconnect attempts reached", null)
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = calculateReconnectDelay()
            AppLog.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1})")

            delay(delayMs)
            reconnectAttempts++
            startConnection()
        }
    }

    private fun calculateReconnectDelay(): Long {
        val delay = INITIAL_RECONNECT_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, reconnectAttempts.toDouble())
        return delay.toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    /**
     * Clean up stale pending responses (older than 5 minutes)
     */
    fun cleanupStalePendingResponses() {
        val now = clock.now()
        val staleThreshold = 5 * 60 * 1000L // 5 minutes

        val staleIds = pendingResponses.entries
            .filter { now - it.value.createdAt > staleThreshold }
            .map { it.key }

        staleIds.forEach { id ->
            val response = pendingResponses.remove(id)
            @Suppress("UNCHECKED_CAST")
            val continuation = response?.continuation as? CancellableContinuation<SyncMessage>
            continuation?.cancel()
            AppLog.w(TAG, "Removed stale pending response: $id")
        }
    }

    /**
     * Data class to track pending responses
     */
    private data class PendingResponse(
        val messageId: String,
        val expectedType: Class<*>,
        val continuation: CancellableContinuation<*>,
        val correlationId: String? = null,
        val createdAt: Long
    )
}
