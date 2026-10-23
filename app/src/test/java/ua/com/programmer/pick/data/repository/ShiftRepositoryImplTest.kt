package ua.com.programmer.pick.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.api.DeviceApiException
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.ShiftAction
import ua.com.programmer.pick.domain.model.ShiftMarkResult
import ua.com.programmer.pick.domain.model.ShiftStatus
import java.io.IOException

/**
 * Beyond mapping: a retry repeats the *same* `operation_id` (the server then
 * replays the stored shift), a server verdict is not retried, and the demo
 * session never reaches the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftRepositoryImplTest {

    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())

    private lateinit var client: DeviceRestClient
    private lateinit var authState: MutableStateFlow<UserAuthState>
    private var demoMode = false
    private lateinit var repository: ShiftRepositoryImpl

    private val openShift = DeviceDto.Shift(id = "s1", status = "OPEN", warehouseId = "WH-B2B")

    @Before
    fun setUp() {
        authState = MutableStateFlow(UserAuthState.NotAuthenticated)
        client = mockk {
            coEvery { shift() } returns Result.success(
                DeviceDto.ShiftStatusResponse(enabled = true, shift = null)
            )
        }
        repository = ShiftRepositoryImpl(
            client = client,
            transport = mockk(relaxed = true) { every { userAuthState } returns authState },
            appPreferences = mockk { every { getDemoModeSync() } answers { this@ShiftRepositoryImplTest.demoMode } },
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `refresh maps an open shift and the collecting document`() = runTest(dispatcher) {
        coEvery { client.shift() } returns Result.success(
            DeviceDto.ShiftStatusResponse(
                enabled = true,
                shift = openShift,
                startedText = "Зміну розпочато о 09:02",
                collectingDocument = "РН-0042",
            )
        )

        repository.refresh()

        assertEquals(
            ShiftStatus(true, isOpen = true, startedText = "Зміну розпочато о 09:02", collectingDocument = "РН-0042"),
            repository.status.value,
        )
    }

    @Test
    fun `refresh keeps the last status when the server is unreachable`() = runTest(dispatcher) {
        repository.refresh()
        coEvery { client.shift() } returns Result.failure(IOException("timeout"))

        repository.refresh()

        assertTrue(repository.status.value.enabled)
    }

    @Test
    fun `demo session never asks the server`() = runTest(dispatcher) {
        demoMode = true

        repository.refresh()

        assertEquals(ShiftStatus.DISABLED, repository.status.value)
        coVerify(exactly = 0) { client.shift() }
    }

    @Test
    fun `sign-in reads the status and sign-out clears it`() = runTest(dispatcher) {
        authState.value = mockk<UserAuthState.Authenticated>(relaxed = true)
        assertTrue(repository.status.value.enabled)

        authState.value = UserAuthState.NotAuthenticated
        assertFalse(repository.status.value.enabled)
    }

    @Test
    fun `retries a transport failure with the same operation id`() = runTest(dispatcher) {
        val sent = mutableListOf<DeviceDto.ShiftMarkRequest>()
        var call = 0
        coEvery { client.shiftStart(capture(sent)) } answers {
            call++
            if (call < 3) Result.failure(IOException("reset"))
            else Result.success(DeviceDto.ShiftMarkResponse(openShift, "Зміну розпочато о 09:02"))
        }

        val result = repository.mark(ShiftAction.START, qr = "PS1.ABCD2345.1.SIG")

        assertEquals(ShiftMarkResult.Success("Зміну розпочато о 09:02"), result)
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { it.operationId }.toSet().size)
        assertEquals("PS1.ABCD2345.1.SIG", sent.first().qr)
    }

    @Test
    fun `a server verdict is not retried and refreshes the status`() = runTest(dispatcher) {
        coEvery { client.shiftEnd(any()) } returns
            Result.failure(DeviceApiException("SHIFT_NOT_STARTED", "Зміну не розпочато"))

        val result = repository.mark(ShiftAction.END, adminCode = "482913")

        assertEquals(ShiftMarkResult.Refused("SHIFT_NOT_STARTED", "Зміну не розпочато"), result)
        coVerify(exactly = 1) { client.shiftEnd(any()) }
        coVerify(exactly = 1) { client.shift() }
    }

    @Test
    fun `gives up after three silent failures`() = runTest(dispatcher) {
        coEvery { client.shiftStart(any()) } returns Result.failure(IOException("no route"))

        val result = repository.mark(ShiftAction.START, qr = "PS1.X")

        assertTrue(result is ShiftMarkResult.Unreachable)
        coVerify(exactly = 3) { client.shiftStart(any()) }
    }
}
