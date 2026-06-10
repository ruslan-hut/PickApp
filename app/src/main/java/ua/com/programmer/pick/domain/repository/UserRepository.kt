package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.User

interface UserRepository {

    fun getCurrentUser(): Flow<User?>

    suspend fun login(login: String, password: String): Result<User>

    /**
     * Re-authenticate a persisted session from stored credentials. Needed by the
     * REST transport, which (unlike the old WebSocket handshake) does not
     * re-authenticate on connect — after a process restart the transport is
     * Connected but NotAuthenticated, gating off all sync until this runs.
     * Returns null when no credentials are stored (no session to restore).
     */
    suspend fun autoLogin(): Result<User>?

    suspend fun loginOffline(login: String, password: String): Result<User>

    suspend fun logout()

    suspend fun getUserById(id: String): User?

    suspend fun updateUser(user: User)
}
