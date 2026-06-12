package ua.com.programmer.pick.data.repository

import ua.com.programmer.pick.core.util.AppLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.PasswordHasher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.AppDatabase
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.entity.UserEntity
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.remote.transport.ConnectionState
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.model.UserRole
import ua.com.programmer.pick.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val transport: SyncTransport,
    private val userDao: UserDao,
    private val appPreferences: AppPreferences,
    private val appDatabase: AppDatabase,
    private val passwordHasher: PasswordHasher,
    private val networkMonitor: NetworkMonitor,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UserRepository {

    companion object {
        private const val TAG = "UserRepository"
        private const val TRANSPORT_CONNECT_TIMEOUT_MS = 10000L
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getCurrentUser(): Flow<User?> {
        return appPreferences.currentUserId.flatMapLatest { userId ->
            if (userId != null) {
                userDao.getUserByIdFlow(userId).map { it?.toDomain() }
            } else {
                flowOf(null)
            }
        }
    }

    /**
     * Login user via transport.
     *
     * Flow:
     * 1. If online: Connect transport, send USER_LOGIN, receive USER_LOGIN_RESULT
     * 2. If offline: Use stored offline hash for local authentication
     */
    override suspend fun login(login: String, password: String): Result<User> =
        withContext(ioDispatcher) {
            try {
                // Check if online
                if (networkMonitor.isCurrentlyConnected()) {
                    // Try online login via transport
                    loginViaTransport(login, password)
                } else {
                    // No network, try offline login
                    AppLog.d(TAG, "No network, attempting offline login")
                    loginOffline(login, password)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Login error: ${e.message}", e)
                // Network error, try offline login
                loginOffline(login, password)
            }
        }

    override suspend fun autoLogin(): Result<User>? = withContext(ioDispatcher) {
        // Already authenticated (e.g. user just logged in manually) — nothing to do.
        if (transport.isUserAuthenticated()) {
            return@withContext null
        }
        val creds = appPreferences.getUserCredentialsSync() ?: run {
            AppLog.d(TAG, "autoLogin: no stored credentials, skipping")
            return@withContext null
        }
        AppLog.i(TAG, "DOC_TRACE autoLogin: re-authenticating stored session for ${creds.first}")
        login(creds.first, creds.second)
    }

    /**
     * Login via transport using USER_LOGIN message (per protocol)
     */
    private suspend fun loginViaTransport(login: String, password: String): Result<User> {
        AppLog.d(TAG, "Attempting transport login for user: $login")

        // Ensure transport is connected
        if (!transport.isConnected()) {
            AppLog.d(TAG, "transport not connected, connecting...")
            transport.connect()

            // Wait for connection with timeout
            val connectionError = waitForTransportConnection()
            if (connectionError != null) {
                AppLog.w(TAG, "transport connection failed: $connectionError, falling back to offline login")
                val offlineResult = loginOffline(login, password)
                if (offlineResult is Result.Success) return offlineResult
                return Result.Error(Exception(connectionError), connectionError)
            }
        }

        // Send USER_LOGIN via transport
        val loginResult = transport.loginUser(login, password)

        return if (loginResult.success) {
            AppLog.d(TAG, "transport login successful: ${loginResult.userName}")

            // Detect tenant change and wipe local data if needed
            val newTenantId = loginResult.tenantId
            if (newTenantId != null) {
                val currentTenantId = appPreferences.getTenantIdSync()
                if (currentTenantId != null && currentTenantId != newTenantId) {
                    AppLog.w(TAG, "Tenant changed from $currentTenantId to $newTenantId, clearing local data")
                    appDatabase.clearAllTables()
                }
                appPreferences.setTenantId(newTenantId)
            }

            // Persist debug-journal enablement flag set by the server for this device.
            loginResult.debugJournalEnabled?.let {
                appPreferences.setDebugJournalEnabled(it)
            }

            // Hash password for offline login
            val passwordHash = passwordHasher.hash(password, login)

            // Create or update user in local database
            val userId = loginResult.userId ?: return Result.Error(
                Exception("Missing user ID in login response")
            )

            val currentTime = System.currentTimeMillis()

            // Check if user already exists. On both paths, persist the worker's
            // ERP external_id (sent by v2 backend in the login response) so that
            // cross-reference fields coming back from the server — e.g.
            // StageLockResult.locked_by, Document.assigned_user_id — can be
            // translated back to the local internal user id without waiting for
            // the full users sync to run.
            val existingUser = userDao.getUserById(userId)
            val userEntity = if (existingUser != null) {
                existingUser.copy(
                    login = login,
                    name = loginResult.userName ?: existingUser.name,
                    passwordHash = passwordHash,
                    role = loginResult.role ?: existingUser.role,
                    externalId = loginResult.userExternalId ?: existingUser.externalId,
                    lastLoginAt = currentTime,
                    lastUpdated = currentTime
                )
            } else {
                UserEntity(
                    id = userId,
                    externalId = loginResult.userExternalId,
                    login = login,
                    name = loginResult.userName ?: login,
                    passwordHash = passwordHash,
                    role = loginResult.role ?: UserRole.COLLECTOR.name,
                    isActive = true,
                    lastLoginAt = currentTime,
                    lastUpdated = currentTime
                )
            }
            userDao.insertUser(userEntity)

            // Save user preferences
            appPreferences.setCurrentUserId(userId)

            // Save offline hash from server (for offline authentication)
            loginResult.offlineHash?.let { hash ->
                appPreferences.setOfflineHash(hash)
            }

            // Store credentials for transport auto-login on reconnect
            appPreferences.setUserCredentials(login, password)
            appPreferences.setLastLogin(login)

            val user = userEntity.toDomain()
            Result.Success(user)
        } else {
            val error = loginResult.errorMessage ?: "Login failed"
            AppLog.w(TAG, "transport login failed: $error")

            // If transport login fails, try offline login as fallback
            // (in case user exists locally with valid credentials)
            val offlineResult = loginOffline(login, password)
            if (offlineResult is Result.Success) {
                offlineResult
            } else {
                Result.Error(Exception(error), error)
            }
        }
    }

    /**
     * Wait for transport to connect with timeout.
     * Returns null on success, or the error message string on failure.
     *
     * Uses reactive Flow collection so the brief Error state set in onFailure
     * is captured even if handleDisconnection immediately overwrites it with Reconnecting.
     */
    private suspend fun waitForTransportConnection(): String? {
        val finalState = withTimeoutOrNull(TRANSPORT_CONNECT_TIMEOUT_MS) {
            transport.connectionState
                .first { state ->
                    state is ConnectionState.Connected || state is ConnectionState.Error
                }
        }
        return when {
            finalState is ConnectionState.Connected -> null
            finalState is ConnectionState.Error -> finalState.message
            else -> "Connection timeout"
        }
    }

    override suspend fun loginOffline(login: String, password: String): Result<User> =
        withContext(ioDispatcher) {
            try {
                AppLog.d(TAG, "Attempting offline login for user: $login")

                val userEntity = userDao.getUserByLogin(login) ?: return@withContext Result.Error(
                    Exception("User not found"),
                    "User not found. Please connect to network for first login."
                )

                // Verify password: prefer server-provided offline_hash, fallback to local SHA-256
                val storedOfflineHash = appPreferences.getOfflineHashSync()
                val passwordHash = passwordHasher.hash(password, login)
                val isValid = if (!storedOfflineHash.isNullOrEmpty()) {
                    storedOfflineHash == passwordHash
                } else {
                    userEntity.passwordHash == passwordHash
                }
                if (!isValid) {
                    return@withContext Result.Error(
                        Exception("Invalid credentials"),
                        "Invalid login or password"
                    )
                }

                // Update last login time
                val currentTime = System.currentTimeMillis()
                userDao.updateLastLoginTime(userEntity.id, currentTime)

                // Set current user
                appPreferences.setCurrentUserId(userEntity.id)

                // Store credentials for transport auto-login on reconnect
                appPreferences.setUserCredentials(login, password)
                appPreferences.setLastLogin(login)

                AppLog.d(TAG, "Offline login successful for user: $login")
                val user = userEntity.toDomain().copy(lastLoginAt = currentTime)
                Result.Success(user)
            } catch (e: Exception) {
                AppLog.e(TAG, "Offline login error: ${e.message}", e)
                Result.Error(e, e.message ?: "Offline login failed")
            }
        }

    override suspend fun logout() = withContext(ioDispatcher) {
        AppLog.d(TAG, "Logging out user")
        // Clear local session and credentials
        appPreferences.clearSession()
        // Clear all local data
        appDatabase.clearAllTables()
        // transport will be disconnected by SyncOrchestrator when user logs out
    }

    override suspend fun getUserById(id: String): User? = withContext(ioDispatcher) {
        userDao.getUserById(id)?.toDomain()
    }

    override suspend fun updateUser(user: User) = withContext(ioDispatcher) {
        val existingEntity = userDao.getUserById(user.id)
        if (existingEntity != null) {
            val updatedEntity = existingEntity.copy(
                name = user.name
            )
            userDao.updateUser(updatedEntity)
        }
    }

}
