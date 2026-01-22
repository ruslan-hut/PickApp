package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.PasswordHasher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.data.mapper.toEntityForSync
import ua.com.programmer.pick.data.remote.api.AuthApi
import ua.com.programmer.pick.data.remote.api.SyncApi
import ua.com.programmer.pick.data.remote.dto.AuthDto
import ua.com.programmer.pick.data.remote.dto.SyncAckRequest
import ua.com.programmer.pick.data.remote.dto.UserDto
import ua.com.programmer.pick.core.Constants
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val syncApi: SyncApi,
    private val userDao: UserDao,
    private val syncStateDao: SyncStateDao,
    private val appPreferences: AppPreferences,
    private val passwordHasher: PasswordHasher,
    private val networkMonitor: NetworkMonitor,
    private val gson: Gson,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UserRepository {

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

    override suspend fun login(login: String, password: String): Result<User> =
        withContext(ioDispatcher) {
            try {
                // Check if online
                if (networkMonitor.isCurrentlyConnected()) {
                    // Try online login
                    val response = authApi.login(
                        AuthDto.LoginRequest(
                            login = login,
                            password = password
                        )
                    )

                    if (response.isSuccessful && response.body() != null) {
                        val loginResponse = response.body()!!
                        val userDto = loginResponse.user

                        // Hash password for offline login
                        val passwordHash = passwordHasher.hash(password, login)

                        // Save user to local database
                        val userEntity = userDto.toEntity(passwordHash)
                        userDao.insertUser(userEntity)

                        // Update last login time
                        val currentTime = System.currentTimeMillis()
                        userDao.updateLastLoginTime(userDto.id, currentTime)

                        // Save tokens
                        appPreferences.setAuthToken(loginResponse.token)
                        appPreferences.setRefreshToken(loginResponse.refreshToken)
                        appPreferences.setCurrentUserId(userDto.id)

                        val user = userDto.toDomain().copy(lastLoginAt = currentTime)
                        Result.Success(user)
                    } else {
                        // Online login failed, try offline
                        loginOffline(login, password)
                    }
                } else {
                    // No network, try offline login
                    loginOffline(login, password)
                }
            } catch (e: Exception) {
                // Network error, try offline login
                loginOffline(login, password)
            }
        }

    override suspend fun loginOffline(login: String, password: String): Result<User> =
        withContext(ioDispatcher) {
            try {
                val userEntity = userDao.getUserByLogin(login) ?: return@withContext Result.Error(
                    Exception("User not found"),
                    "User not found. Please connect to network for first login."
                )

                // Verify password hash
                val passwordHash = passwordHasher.hash(password, login)
                if (userEntity.passwordHash != passwordHash) {
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

                val user = userEntity.toDomain().copy(lastLoginAt = currentTime)
                Result.Success(user)
            } catch (e: Exception) {
                Result.Error(e, e.message ?: "Offline login failed")
            }
        }

    override suspend fun logout() = withContext(ioDispatcher) {
        try {
            // Try to notify server if online
            if (networkMonitor.isCurrentlyConnected()) {
                try {
                    authApi.logout()
                } catch (_: Exception) {
                    // Ignore network errors during logout
                }
            }
        } finally {
            // Always clear local session
            appPreferences.clearSession()
        }
    }

    override suspend fun getUserById(id: String): User? = withContext(ioDispatcher) {
        userDao.getUserById(id)?.toDomain()
    }

    override suspend fun updateUser(user: User) = withContext(ioDispatcher) {
        val existingEntity = userDao.getUserById(user.id)
        if (existingEntity != null) {
            val updatedEntity = existingEntity.copy(
                name = user.name,
                operatingMode = user.operatingMode.name
            )
            userDao.updateUser(updatedEntity)
        }
    }

    override suspend fun syncUsers(): Result<Unit> = withContext(ioDispatcher) {
        if (!networkMonitor.isCurrentlyConnected()) {
            return@withContext Result.Error(Exception("No network connection"))
        }

        try {
            val lastSyncTime = syncStateDao.getSyncState(Constants.SyncEntity.USERS)?.lastSyncTime ?: 0L
            val isFullSync = lastSyncTime == 0L

            val response = if (isFullSync) {
                syncApi.getFullSync(Constants.SyncEntity.USERS)
            } else {
                syncApi.getDeltaSync(Constants.SyncEntity.USERS, lastSyncTime)
            }

            if (response.isSuccessful) {
                val syncResponse = response.body()
                if (syncResponse != null) {
                    // Parse and apply user data
                    if (syncResponse.data.isJsonArray) {
                        val type = object : TypeToken<List<UserDto>>() {}.type
                        val users: List<UserDto> = gson.fromJson(syncResponse.data, type)

                        users.forEach { dto ->
                            // Preserve existing passwordHash if user already exists
                            val existingUser = userDao.getUserById(dto.id)
                            val entity = dto.toEntityForSync(existingUser?.passwordHash)
                            userDao.insertUser(entity)
                        }
                    }

                    // Delete removed users
                    syncResponse.deletedIds?.forEach { id ->
                        userDao.deleteUser(id)
                    }

                    // Acknowledge sync
                    syncApi.acknowledgSync(
                        SyncAckRequest(
                            entityType = syncResponse.entityType,
                            syncId = syncResponse.syncId,
                            timestamp = syncResponse.timestamp
                        )
                    )

                    // Update sync state
                    syncStateDao.updateSyncSuccess(Constants.SyncEntity.USERS, syncResponse.timestamp)

                    Result.Success(Unit)
                } else {
                    Result.Error(Exception("Empty response"))
                }
            } else {
                val error = "HTTP ${response.code()}: ${response.message()}"
                syncStateDao.updateSyncError(Constants.SyncEntity.USERS, "ERROR", error)
                Result.Error(Exception(error))
            }
        } catch (e: Exception) {
            syncStateDao.updateSyncError(Constants.SyncEntity.USERS, "ERROR", e.message)
            Result.Error(e, e.message ?: "User sync failed")
        }
    }
}
