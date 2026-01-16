package ua.com.programmer.pick.data.remote.websocket

import android.util.Log
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
    }

    private val scope = CoroutineScope(ioDispatcher)

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SyncMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SyncMessage> = _incomingMessages.asSharedFlow()

    private val pendingAcknowledgments = ConcurrentHashMap<String, PendingMessage>()

    private var isManuallyDisconnected = false
    private var sessionId: String? = null

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

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        _connectionState.value = ConnectionState.Disconnected
        sessionId = null
        pendingAcknowledgments.clear()

        Log.d(TAG, "WebSocket disconnected manually")
    }

    /**
     * Send a message through WebSocket
     * @return message ID for tracking acknowledgment
     */
    fun sendMessage(message: SyncMessage): String? {
        val ws = webSocket
        if (ws == null || _connectionState.value !is ConnectionState.Connected) {
            Log.w(TAG, "Cannot send message - not connected")
            return null
        }

        return try {
            val json = messageParser.serializeMessage(message)
            val sent = ws.send(json)
            if (sent) {
                Log.d(TAG, "Message sent: ${message.type}")
                pendingAcknowledgments[message.messageId] = PendingMessage(
                    message = message,
                    sentAt = System.currentTimeMillis()
                )
                message.messageId
            } else {
                Log.e(TAG, "Failed to send message")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}", e)
            null
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

        val wsUrl = buildWebSocketUrl()
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()

        // Create WebSocket client with ping interval
        val wsClient = okHttpClient.newBuilder()
            .pingInterval(Constants.Network.WEBSOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

        webSocket = wsClient.newWebSocket(request, createWebSocketListener())
    }

    private fun buildWebSocketUrl(): String {
        // Convert HTTP URL to WS URL
        val baseUrl = Constants.Network.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')

        return "$baseUrl/ws/sync"
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket opened: ${response.code}")
            reconnectAttempts = 0
            _connectionState.value = ConnectionState.Connected

            // Send subscription for all entity types
            subscribeToEntities()
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
            is SyncMessage.Connected -> {
                sessionId = message.sessionId
                Log.d(TAG, "Session established: ${message.sessionId}")
            }
            is SyncMessage.Acknowledgment -> {
                handleAcknowledgment(message)
            }
            else -> {
                // Forward to observers
                scope.launch {
                    _incomingMessages.emit(message)
                }
            }
        }
    }

    private fun handleAcknowledgment(ack: SyncMessage.Acknowledgment) {
        val pending = pendingAcknowledgments.remove(ack.originalMessageId)
        if (pending != null) {
            Log.d(TAG, "Received ACK for ${ack.originalMessageId}, success: ${ack.success}")
            if (!ack.success) {
                Log.w(TAG, "Operation failed: ${ack.error}")
            }
        } else {
            Log.w(TAG, "Received ACK for unknown message: ${ack.originalMessageId}")
        }

        // Forward acknowledgment to observers for handling
        scope.launch {
            _incomingMessages.emit(ack)
        }
    }

    private fun handleDisconnection(code: Int, reason: String) {
        webSocket = null
        sessionId = null

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

    private fun subscribeToEntities() {
        val subscribeMessage = SyncMessage.Subscribe(
            messageId = messageParser.generateMessageId(),
            entityTypes = listOf(
                Constants.SyncEntity.DOCUMENTS,
                Constants.SyncEntity.PRODUCTS,
                Constants.SyncEntity.CLIENTS,
                Constants.SyncEntity.WAREHOUSES
            )
        )
        sendMessage(subscribeMessage)
    }

    /**
     * Clean up stale pending messages (older than 5 minutes)
     */
    fun cleanupStalePendingMessages() {
        val now = System.currentTimeMillis()
        val staleThreshold = 5 * 60 * 1000L // 5 minutes

        val staleIds = pendingAcknowledgments.entries
            .filter { now - it.value.sentAt > staleThreshold }
            .map { it.key }

        staleIds.forEach { id ->
            pendingAcknowledgments.remove(id)
            Log.w(TAG, "Removed stale pending message: $id")
        }
    }

    /**
     * Data class to track pending messages awaiting acknowledgment
     */
    private data class PendingMessage(
        val message: SyncMessage,
        val sentAt: Long
    )
}
