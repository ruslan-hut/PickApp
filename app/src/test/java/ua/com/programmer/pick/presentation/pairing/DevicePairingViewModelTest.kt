package ua.com.programmer.pick.presentation.pairing

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.scanner.BarcodeFormat
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.scanner.ScannedBarcode
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.model.DeviceLinkResult
import ua.com.programmer.pick.domain.model.DeviceLinkStatus
import ua.com.programmer.pick.domain.model.LinkedTenant
import ua.com.programmer.pick.domain.model.PairingState
import ua.com.programmer.pick.domain.repository.DeviceLinkRepository
import ua.com.programmer.pick.presentation.navigation.Screen

@OptIn(ExperimentalCoroutinesApi::class)
class DevicePairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scans = MutableSharedFlow<ScannedBarcode>(extraBufferCapacity = 4)
    private lateinit var repository: DeviceLinkRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk {
            coEvery { pairing() } returns DeviceLinkResult.Success(
                PairingState(DeviceLinkStatus.PENDING, code = "48271930", expiresInSec = 600)
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(args: Map<String, Any?> = emptyMap()) = DevicePairingViewModel(
        repository = repository,
        barcodeService = mockk { every { scannedBarcodes } returns scans },
        appPreferences = mockk { every { deviceId } returns flowOf("1dd09980-aaaa") },
        savedStateHandle = SavedStateHandle(args),
    )

    private fun qr(token: String, server: String = Constants.Network.BASE_URL) =
        "pick://enroll?s=${URLEncoder.encode(server, "UTF-8")}&t=$token"

    private fun scan(raw: String) = scans.tryEmit(ScannedBarcode(raw, BarcodeFormat.QR_CODE, null))

    @Test
    fun `resuming polls and shows the pairing code with its countdown`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResume()
        runCurrent()

        val s = vm.uiState.value
        assertEquals(DeviceLinkStatus.PENDING, s.status)
        assertEquals("48271930", s.code)
        assertEquals(600, s.secondsLeft)
        assertEquals("1dd09980", s.deviceIdShort)

        advanceTimeBy(2_500)
        assertEquals(598, vm.uiState.value.secondsLeft)
        vm.onPause()
    }

    @Test
    fun `a claim seen by the poll ends polling`() = runTest(dispatcher) {
        coEvery { repository.pairing() } returns DeviceLinkResult.Success(
            PairingState(DeviceLinkStatus.APPROVED, tenantName = "Dark")
        )
        val vm = viewModel()
        vm.onResume()
        runCurrent()
        advanceTimeBy(DevicePairingViewModel.POLL_INTERVAL_MS * 3)

        assertEquals(DeviceLinkStatus.APPROVED, vm.uiState.value.status)
        assertEquals("Dark", vm.uiState.value.tenantName)
        coVerify(exactly = 1) { repository.pairing() }
        vm.onPause()
    }

    @Test
    fun `pausing stops the poll`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onResume()
        runCurrent()
        vm.onPause()
        advanceTimeBy(DevicePairingViewModel.POLL_INTERVAL_MS * 5)

        coVerify(exactly = 1) { repository.pairing() }
    }

    @Test
    fun `scanning the company QR links the device`() = runTest(dispatcher) {
        coEvery { repository.enroll("tok") } returns DeviceLinkResult.Success(LinkedTenant("Dark", "T 1"))
        val vm = viewModel()
        runCurrent()

        scan(qr("tok"))
        runCurrent()

        val s = vm.uiState.value
        assertEquals(DeviceLinkStatus.APPROVED, s.status)
        assertEquals("Dark", s.tenantName)
        assertFalse(s.isEnrolling)
        // Further scans are ignored once linked.
        scan(qr("tok"))
        runCurrent()
        coVerify(exactly = 1) { repository.enroll(any()) }
    }

    @Test
    fun `a refused enrollment keeps the screen and shows why`() = runTest(dispatcher) {
        coEvery { repository.enroll("old") } returns DeviceLinkResult.Refused("ENROLLMENT_INVALID", "used up")
        val vm = viewModel()
        runCurrent()

        scan(qr("old"))
        runCurrent()

        assertEquals(PairingError.Refused("ENROLLMENT_INVALID"), vm.uiState.value.error)
        assertNull(vm.uiState.value.status?.takeIf { it == DeviceLinkStatus.APPROVED })
    }

    @Test
    fun `a code for another server is refused without calling it`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        scan(qr("tok", server = "https://elsewhere.example.com"))
        runCurrent()

        assertEquals(PairingError.OtherServer, vm.uiState.value.error)
        coVerify(exactly = 0) { repository.enroll(any()) }
    }

    @Test
    fun `other barcodes are ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        scan("4820000000017")
        runCurrent()

        assertNull(vm.uiState.value.error)
        coVerify(exactly = 0) { repository.enroll(any()) }
    }

    @Test
    fun `a QR scanned on the login screen is enrolled on arrival`() = runTest(dispatcher) {
        coEvery { repository.enroll("tok") } returns DeviceLinkResult.Success(LinkedTenant("Dark", null))
        val vm = viewModel(mapOf(Screen.ENROLL_QR_ARG to qr("tok")))
        runCurrent()

        assertEquals(DeviceLinkStatus.APPROVED, vm.uiState.value.status)
    }
}
