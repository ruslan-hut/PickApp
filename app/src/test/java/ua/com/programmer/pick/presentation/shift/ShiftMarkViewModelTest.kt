package ua.com.programmer.pick.presentation.shift

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.core.scanner.BarcodeFormat
import ua.com.programmer.pick.core.scanner.ScannedBarcode
import ua.com.programmer.pick.domain.model.ShiftAction
import ua.com.programmer.pick.domain.model.ShiftMarkResult
import ua.com.programmer.pick.domain.repository.ShiftRepository
import ua.com.programmer.pick.presentation.navigation.Screen

@OptIn(ExperimentalCoroutinesApi::class)
class ShiftMarkViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scans = MutableSharedFlow<ScannedBarcode>(extraBufferCapacity = 4)
    private val online = MutableStateFlow(true)
    private lateinit var repository: ShiftRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk {
            coEvery { mark(any(), any(), any()) } returns ShiftMarkResult.Success("Зміну розпочато о 09:02")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(action: ShiftAction = ShiftAction.START) = ShiftMarkViewModel(
        repository = repository,
        barcodeService = mockk { every { scannedBarcodes } returns scans },
        networkMonitor = mockk { every { isOnline } returns online },
        savedStateHandle = SavedStateHandle(mapOf(Screen.SHIFT_ACTION_ARG to action.name)),
    ).also { it.setScreenActive(true) }

    private fun scan(raw: String) = scans.tryEmit(ScannedBarcode(raw, BarcodeFormat.QR_CODE, null))

    @Test
    fun `a scan marks the shift and shows the server message`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        scan(" PS1.ABCD2345.1.SIG ")
        runCurrent()

        coVerify { repository.mark(ShiftAction.START, qr = "PS1.ABCD2345.1.SIG", adminCode = null) }
        assertTrue(vm.uiState.value.isDone)
        assertEquals("Зміну розпочато о 09:02", vm.uiState.value.doneMessage)
    }

    @Test
    fun `a scan on another screen does nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setScreenActive(false)
        runCurrent()
        scan("PS1.X")
        runCurrent()

        coVerify(exactly = 0) { repository.mark(any(), any(), any()) }
    }

    @Test
    fun `offline the mark is not sent`() = runTest(dispatcher) {
        online.value = false
        val vm = viewModel()
        runCurrent()
        scan("PS1.X")
        runCurrent()

        coVerify(exactly = 0) { repository.mark(any(), any(), any()) }
        assertEquals(ShiftMarkError.Unreachable, vm.uiState.value.error)
    }

    @Test
    fun `the administrator code keeps digits only and needs six`() = runTest(dispatcher) {
        val vm = viewModel(ShiftAction.END)
        vm.onAdminCodeChange("48-29")
        vm.submitAdminCode()
        runCurrent()
        coVerify(exactly = 0) { repository.mark(any(), any(), any()) }

        vm.onAdminCodeChange("4829137")
        assertEquals("482913", vm.uiState.value.adminCode)
        vm.submitAdminCode()
        runCurrent()
        coVerify { repository.mark(ShiftAction.END, qr = null, adminCode = "482913") }
    }

    @Test
    fun `a refused code shows the server text and is cleared`() = runTest(dispatcher) {
        coEvery { repository.mark(any(), any(), any()) } returns
            ShiftMarkResult.Refused("ADMIN_CODE_INVALID", "Невірний код адміністратора")
        val vm = viewModel()
        vm.onAdminCodeChange("111111")
        vm.submitAdminCode()
        runCurrent()

        assertEquals(ShiftMarkError.Refused("Невірний код адміністратора"), vm.uiState.value.error)
        assertEquals("", vm.uiState.value.adminCode)
    }
}
