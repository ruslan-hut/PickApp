package ua.com.programmer.pick.data.remote.transport

/**
 * Transport-level state and result types shared by [SyncTransport] and its
 * implementations. (Formerly defined in the legacy transport client, which has been
 * removed — the device speaks REST only.)
 */

/** Device-connection state. For the connectionless REST transport this is
 *  Connected once login succeeds; Disconnected on logout. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String, val code: Int? = null) : ConnectionState()
    data object Reconnecting : ConnectionState()
}

/** Worker authentication state. */
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
        // pair still holds an in-process stage lock for. Lets the orchestrator
        // rebuild its session-scoped `heldStageLocks` set immediately on login —
        // closing the post-restart race where inbound SYNC_DATA could otherwise
        // overwrite worker-owned line data while the device thought the lock
        // was gone.
        val heldStageLocks: List<String>? = null,
        // Whether the server confirms DOCUMENT_UPDATE writes with a
        // DOCUMENT_UPDATE_RESULT frame. Gates the orchestrator's
        // clear-dirty-only-on-ack path. REST always sets this true (a 2xx is the
        // confirmation, surfaced as a synthetic DOCUMENT_UPDATE_RESULT).
        val supportsUpdateAck: Boolean = false
    ) : UserAuthState()
    data class AuthFailed(val error: String) : UserAuthState()
}

/** Result of the user-login operation (returned by [SyncTransport.loginUser]). */
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
