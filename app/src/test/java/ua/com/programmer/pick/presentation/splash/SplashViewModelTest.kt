package ua.com.programmer.pick.presentation.splash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.model.UserRole
import ua.com.programmer.pick.domain.repository.UserRepository

class FakeUserRepository(private val user: User?) : UserRepository {
    override fun getCurrentUser() = flow { emit(user) }

    override suspend fun login(login: String, password: String) = Result.Error(Exception("Not implemented"))

    override suspend fun autoLogin(): Result<User>? = null

    override suspend fun loginOffline(login: String, password: String) = Result.Error(Exception("Not implemented"))

    override suspend fun logout() {}

    override suspend fun getUserById(id: String) = null

    override suspend fun updateUser(user: User) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main — route it through the
        // test scheduler so start()'s branding delay can be fast-forwarded.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start navigates to home when user exists`() = runTest(dispatcher) {
        val testUser = User(id = "1", login = "demo", name = "Demo", role = UserRole.COLLECTOR, isActive = true)
        val vm = SplashViewModel(FakeUserRepository(testUser))

        vm.start()
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals("home", ui.targetRoute)
        assertEquals(false, ui.isLoading)
    }

    @Test
    fun `start navigates to login when no user`() = runTest(dispatcher) {
        val vm = SplashViewModel(FakeUserRepository(null))

        vm.start()
        advanceUntilIdle()

        val ui = vm.uiState.value
        assertEquals("login", ui.targetRoute)
        assertEquals(false, ui.isLoading)
    }
}
