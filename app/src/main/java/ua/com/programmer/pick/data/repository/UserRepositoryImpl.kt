package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.core.util.PasswordHasher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.data.remote.api.AuthApi
import ua.com.programmer.pick.data.remote.dto.AuthDto
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val userDao: UserDao,
    private val appPreferences: AppPreferences,
    private val passwordHasher: PasswordHasher,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UserRepository {

    override fun getCurrentUser(): Flow<User?> {
        return appPreferences.currentUserId.map { userId ->
            userId?.let { userDao.getUserById(it)?.toDomain() }
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

    override suspend fun syncUsers(): Result<Unit> = withContext(ioDispatcher) {
        // TODO: Implement user sync from server
        Result.Success(Unit)
    }
}
