package ua.com.programmer.pick.presentation.pairing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.model.DeviceLinkResult
import ua.com.programmer.pick.domain.model.DeviceLinkStatus
import ua.com.programmer.pick.domain.model.EnrollmentQr
import ua.com.programmer.pick.domain.repository.DeviceLinkRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

/**
 * "Connect to company": links this device to a tenant. While the screen is
 * resumed it polls the server for the pairing code to show (the poll is what
 * keeps the code claimable) and listens to the scanner for the tenant's
 * enrollment QR. Either way ends in APPROVED, after which the worker signs in.
 */
@HiltViewModel
class DevicePairingViewModel @Inject constructor(
    private val repository: DeviceLinkRepository,
    private val barcodeService: BarcodeService,
    private val appPreferences: AppPreferences,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        const val POLL_INTERVAL_MS = 3_000L
        private const val TICK_MS = 1_000L
    }

    private val _uiState = MutableStateFlow(DevicePairingUiState())
    val uiState: StateFlow<DevicePairingUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var tickJob: Job? = null

    private val isLinked: Boolean get() = _uiState.value.status == DeviceLinkStatus.APPROVED

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(deviceIdShort = appPreferences.deviceId.first().take(8)) }
        }
        barcodeService.scannedBarcodes
            .onEach { onScanned(it.rawValue) }
            .launchIn(viewModelScope)
        // A QR scanned on the login screen arrives here as a nav argument.
        savedStateHandle.get<String>(Screen.ENROLL_QR_ARG)
            ?.takeIf { it.isNotBlank() }
            ?.let { onScanned(it) }
    }

    /** Starts polling; called when the screen resumes. */
    fun onResume() {
        if (isLinked || pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive && !isLinked) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                _uiState.update { s -> s.copy(secondsLeft = s.secondsLeft?.let { (it - 1).coerceAtLeast(0) }) }
            }
        }
    }

    /** Stops polling; a code the device stops polling for stops being claimable. */
    fun onPause() {
        pollJob?.cancel()
        tickJob?.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun poll() {
        when (val r = repository.pairing()) {
            is DeviceLinkResult.Success -> {
                val p = r.value
                _uiState.update {
                    it.copy(
                        status = p.status,
                        code = p.code,
                        secondsLeft = p.expiresInSec,
                        tenantName = p.tenantName ?: it.tenantName,
                        isServerUnreachable = false,
                    )
                }
            }
            is DeviceLinkResult.Refused -> _uiState.update { it.copy(error = PairingError.Refused(r.code)) }
            is DeviceLinkResult.Unreachable -> _uiState.update { it.copy(isServerUnreachable = true) }
        }
    }

    private fun onScanned(raw: String) {
        if (isLinked || _uiState.value.isEnrolling) return
        when (val qr = EnrollmentQr.parse(raw, Constants.Network.BASE_URL)) {
            null -> Unit // not an enrollment code — ignore any other barcode
            is EnrollmentQr.OtherServer -> _uiState.update { it.copy(error = PairingError.OtherServer) }
            is EnrollmentQr.Valid -> enroll(qr.token)
        }
    }

    private fun enroll(token: String) {
        _uiState.update { it.copy(isEnrolling = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.enroll(token)) {
                is DeviceLinkResult.Success -> {
                    pollJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isEnrolling = false,
                            status = DeviceLinkStatus.APPROVED,
                            tenantName = r.value.tenantName,
                            deviceName = r.value.deviceName,
                            code = null,
                        )
                    }
                }
                is DeviceLinkResult.Refused ->
                    _uiState.update { it.copy(isEnrolling = false, error = PairingError.Refused(r.code)) }
                is DeviceLinkResult.Unreachable ->
                    _uiState.update { it.copy(isEnrolling = false, error = PairingError.Unreachable) }
            }
        }
    }
}
