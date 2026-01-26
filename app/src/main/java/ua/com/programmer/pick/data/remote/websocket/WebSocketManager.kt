package ua.com.programmer.pick.data.remote.websocket

import android.util.Log
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
 * Manages WebSocket connection for real-time synchronization.
 * Handles automatic reconnection with exponential backoff.
 * Implements PING/PONG keep-alive mechanism.
 */
@Singleton
class WebSocketManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val messageParser: MessageParser,
    private val appPreferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val REQUEST_TIMEOUT_MS = 30000L  // 30 seconds timeout for request/response
    }

    private val scope = CoroutineScope(ioDispatcher)

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var pongTimeoutJob: Job? = null
    private var reconnectAttempts = 0
    private var lastPongReceived = System.currentTimeMillis()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SyncMessage> = _incomingMessages.asSharedFlow()

    // Pending responses awaiting server reply (for request/response correlation)
    private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()

    private var isManuallyDisconnected = false

    /**
     * Connect to WebSocket server
     */
    fun connect() {
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting
        ) {
            Log.d(TAG, "Already connected or connecting, skipping")
            return
        }

        isManuallyDisconnected = false
        reconnectAttempts = 0
        startConnection()
    }

    /**
     * Disconnect from WebSocket server
     */
    fun disconnect() {
        isManuallyDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopPingTimer()

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        _connectionState.value = ConnectionState.Disconnected
        pendingResponses.clear()

        Log.d(TAG, "WebSocket disconnected manually")
    }

    /**
     * Send a message through WebSocket
     * @return true if message was sent successfully
     */
    fun sendMessage(message: SyncMessage): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value !is ConnectionState.Connected) {
            Log.w(TAG, "Cannot send message - not connected")
            return false
        }

        return try {
            val json = messageParser.serializeMessage(message)
            val sent = ws.send(json)
            if (sent) {
                Log.d(TAG, "Message sent: ${message.type}")
            } else {
                Log.e(TAG, "Failed to send message")
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}", e)
            false
        }
    }

    /**
     * Send a message and wait for a correlated response
     * @param message The message to send
     * @param responseType The expected response message type
     * @param timeoutMs Timeout in milliseconds (default 30 seconds)
     * @return The response message, or null if timeout or error
     */
    suspend fun <T : SyncMessage> sendAndAwait(
        message: SyncMessage,
        responseType: Class<T>,
        timeoutMs: Long = REQUEST_TIMEOUT_MS
    ): T? {
        if (!sendMessage(message)) {
            return null
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                pendingResponses[message.id] = PendingResponse(
                    messageId = message.id,
                    expectedType = responseType,
                    continuation = continuation
                )

                continuation.invokeOnCancellation {
                    pendingResponses.remove(message.id)
                }
            }
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = _connectionState.value is ConnectionState.Connected

    private fun startConnection() {
        if (!networkMonitor.isCurrentlyConnected()) {
            Log.d(TAG, "No network connectivity, waiting...")
            _connectionState.value = ConnectionState.Disconnected
            scheduleReconnect()
            return
        }

        _connectionState.value = ConnectionState.Connecting

        val token = appPreferences.getAuthTokenSync()
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "No auth token available")
            _connectionState.value = ConnectionState.Error("No auth token", null)
            return
        }

        val wsUrl = buildWebSocketUrl(token)
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        // Create WebSocket client without OkHttp ping (we use our own)
        val wsClient = okHttpClient.newBuilder()
            .pingInterval(0, TimeUnit.SECONDS)  // Disable OkHttp ping
            .build()

        webSocket = wsClient.newWebSocket(request, createWebSocketListener())
    }

    /**
     * Build WebSocket URL with token in query parameter
     * New format: ws://{host}:{port}/ws/connect?token={jwt_token}
     */
    private fun buildWebSocketUrl(token: String): String {
        val baseUrl = Constants.Network.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')

        return "$baseUrl/ws/connect?token=$token"
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened: ${response.code}")
            reconnectAttempts = 0
            lastPongReceived = System.currentTimeMillis()
            _connectionState.value = ConnectionState.Connected

            // Start PING timer
            startPingTimer()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Message received: ${text.take(200)}")

            val message = messageParser.parseMessage(text)
            if (message != null) {
                handleIncomingMessage(message)
            } else {
                Log.w(TAG, "Failed to parse message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code - $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code - $reason")
            handleDisconnection(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            _connectionState.value = ConnectionState.Error(
                t.message ?: "Connection failed",
                response?.code
            )
            handleDisconnection(response?.code ?: -1, t.message ?: "Unknown error")
        }
    }

    private fun handleIncomingMessage(message: SyncMessage) {
        when (message) {
            is SyncMessage.Pong -> {
                handlePong()
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
        // For response messages, try to correlate by message ID or document ID
        val responseId = when (message) {
            is SyncMessage.DocumentLockResult -> message.documentId
            is SyncMessage.DocumentCompleteResult -> message.documentId
            is SyncMessage.ProductLookupResult -> message.id
            is SyncMessage.SyncComplete -> message.syncId
            else -> message.id
        }

        // Try to find pending response by ID
        val pending = pendingResponses.entries.find { (_, response) ->
            response.expectedType.isInstance(message)
        }

        if (pending != null) {
            pendingResponses.remove(pending.key)
            val continuation = pending.value.continuation as? CancellableContinuation<SyncMessage>
            continuation?.resume(message)
        }
    }

    // ============================================
    // PING/PONG Mechanism
    // ============================================

    private fun startPingTimer() {
        stopPingTimer()

        pingJob = scope.launch {
            while (true) {
                delay(Constants.Network.WEBSOCKET_PING_INTERVAL_SECONDS * 1000)

                if (_connectionState.value !is ConnectionState.Connected) {
                    break
                }

                sendPing()
                startPongTimeoutTimer()
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
        Log.d(TAG, "PING sent")
    }

    private fun startPongTimeoutTimer() {
        pongTimeoutJob?.cancel()
        pongTimeoutJob = scope.launch {
            delay(Constants.Network.WEBSOCKET_PONG_TIMEOUT_SECONDS * 1000)

            // Check if PONG was received after last PING
            val timeSinceLastPong = System.currentTimeMillis() - lastPongReceived
            if (timeSinceLastPong > Constants.Network.WEBSOCKET_PONG_TIMEOUT_SECONDS * 1000) {
                Log.w(TAG, "PONG timeout - reconnecting")
                webSocket?.close(4000, "PONG timeout")
            }
        }
    }

    private fun handlePong() {
        lastPongReceived = System.currentTimeMillis()
        pongTimeoutJob?.cancel()
        Log.d(TAG, "PONG received")
    }

    // ============================================
    // Reconnection Logic
    // ============================================

    private fun handleDisconnection(code: Int, reason: String) {
        webSocket = null
        stopPingTimer()

        // Cancel all pending responses
        pendingResponses.forEach { (_, response) ->
            val continuation = response.continuation as? CancellableContinuation<SyncMessage>
            continuation?.cancel()
        }
        pendingResponses.clear()

        if (!isManuallyDisconnected) {
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

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            _connectionState.value = ConnectionState.Error("Max reconnect attempts reached", null)
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = calculateReconnectDelay()
            Log.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1})")

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
        val now = System.currentTimeMillis()
        val staleThreshold = 5 * 60 * 1000L // 5 minutes

        val staleIds = pendingResponses.entries
            .filter { now - it.value.createdAt > staleThreshold }
            .map { it.key }

        staleIds.forEach { id ->
            val response = pendingResponses.remove(id)
            val continuation = response?.continuation as? CancellableContinuation<SyncMessage>
            continuation?.cancel()
            Log.w(TAG, "Removed stale pending response: $id")
        }
    }

    /**
     * Data class to track pending responses
     */
    private data class PendingResponse(
        val messageId: String,
        val expectedType: Class<*>,
        val continuation: CancellableContinuation<*>,
        val createdAt: Long = System.currentTimeMillis()
    )
}
