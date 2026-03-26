package ua.com.programmer.pick.presentation.splash

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.model.UserRole
import ua.com.programmer.pick.domain.repository.UserRepository
import ua.com.programmer.pick.core.util.Result

class FakeUserRepository(private val user: User?) : UserRepository {
    override fun getCurrentUser() = flow { emit(user) }

    override suspend fun login(login: String, password: String) = Result.Error(Exception("Not implemented"))

    override suspend fun loginOffline(login: String, password: String) = Result.Error(Exception("Not implemented"))

    override suspend fun logout() {}

    override suspend fun getUserById(id: String) = null

    override suspend fun syncUsers() = Result.Error(Exception("Not implemented"))
}

class SplashViewModelTest {

    @Test
    fun `start navigates to home when user exists`() = runBlocking {
        val testUser = User(id = "1", login = "demo", name = "Demo", role = UserRole.COLLECTOR, isActive = true)
        val repo = FakeUserRepository(testUser)
        val vm = SplashViewModel(repo)

        vm.start()

        // Give coroutine time to run
        kotlinx.coroutines.delay(100)

        val state = vm.uiState
        val current = state as kotlinx.coroutines.flow.StateFlow<SplashUiState>
        val ui = current.value

        assertEquals("home", ui.targetRoute)
        assertEquals(false, ui.isLoading)
    }
}
