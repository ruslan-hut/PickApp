package ua.com.programmer.pick.data.remote.websocket

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The device transport seam. The orchestrator and repositories depend on this
 * interface rather than a concrete client. The app speaks REST only —
 * [RestTransport] is the sole implementation; it satisfies the contract by
 * emitting synthetic inbound [SyncMessage]s on [incomingMessages] so the
 * orchestrator's handlers stay transport-agnostic. (The legacy WebSocket
 * implementation was removed at cutover; the interface is kept as the seam.)
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
