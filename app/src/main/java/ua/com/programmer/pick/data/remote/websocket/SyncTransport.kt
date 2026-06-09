package ua.com.programmer.pick.data.remote.websocket

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The backend transport seam. [SyncOrchestrator] and the repositories depend on
 * this interface rather than a concrete client, so the implementation can be
 * swapped by the `transport` flag (AppPreferences.transportMode) without
 * touching the business logic:
 *  - [WebSocketManager] — the legacy persistent-socket push transport.
 *  - RestTransport — device-initiated REST polling; it satisfies the same
 *    contract by emitting synthetic inbound [SyncMessage]s on [incomingMessages]
 *    so the orchestrator's existing handlers are unchanged.
 *
 * The surface mirrors exactly what callers used from WebSocketManager.
 */
interface SyncTransport {

    /** Device-connection state. For REST this flips to Connected on connect()/login. */
    val connectionState: StateFlow<ConnectionState>

    /** Worker auth state (drives isUserAuthenticated and the orchestrator's session). */
    val userAuthState: StateFlow<UserAuthState>

    /** Inbound server messages. RestTransport synthesizes these from REST responses. */
    val incomingMessages: SharedFlow<SyncMessage>

    /**
     * True when there is no server push and the orchestrator must actively poll
     * for updates while a document is being worked. WebSocket = false (pushes),
     * REST = true.
     */
    val requiresPolling: Boolean get() = false

    fun connect()
    fun disconnect()
    fun forceReconnect()
    fun verifyConnectionHealth()

    fun isConnected(): Boolean
    fun isUserAuthenticated(): Boolean

    /** Fire-and-forget send (REST performs the call async and emits any result). */
    fun sendMessage(message: SyncMessage): Boolean

    /** Stage-2 worker authentication. */
    suspend fun loginUser(login: String, password: String): UserLoginResult

    /**
     * Send a request and await its correlated response (or null on timeout).
     * The default timeout lives here; concrete implementations override without
     * a default per Kotlin's override rules.
     */
    suspend fun <T : SyncMessage> sendAndAwait(
        message: SyncMessage,
        responseType: Class<T>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): T?

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    }
}
