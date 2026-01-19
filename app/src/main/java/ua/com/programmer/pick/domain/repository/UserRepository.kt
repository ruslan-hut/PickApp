package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.User

interface UserRepository {

    fun getCurrentUser(): Flow<User?>

    suspend fun login(login: String, password: String): Result<User>

    suspend fun loginOffline(login: String, password: String): Result<User>

    suspend fun logout()

    suspend fun getUserById(id: String): User?

    suspend fun updateUser(user: User)

    suspend fun syncUsers(): Result<Unit>
}
