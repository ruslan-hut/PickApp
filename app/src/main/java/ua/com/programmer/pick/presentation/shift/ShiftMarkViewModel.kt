package ua.com.programmer.pick.presentation.shift

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.util.NetworkMonitor
import ua.com.programmer.pick.domain.model.ShiftAction
import ua.com.programmer.pick.domain.model.ShiftMarkResult
import ua.com.programmer.pick.domain.repository.ShiftRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

/**
 * "Почати / Завершити зміну": the hardware scanner is live on the whole screen
 * for the TV's QR code; the administrator's one-time code is the fallback when
 * the TV is off. Online only — the server's time is the mark's time.
 *
 * Any scanned value goes to the server untouched: it alone knows a shift code
 * (NOT_SHIFT_CODE otherwise), so the app does not parse the QR.
 */
@HiltViewModel
class ShiftMarkViewModel @Inject constructor(
    private val repository: ShiftRepository,
    barcodeService: BarcodeService,
    networkMonitor: NetworkMonitor,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        const val ADMIN_CODE_LENGTH = 6
    }

    private val _uiState = MutableStateFlow(
        ShiftMarkUiState(
            action = savedStateHandle.get<String>(Screen.SHIFT_ACTION_ARG)
                ?.let { arg -> ShiftAction.entries.firstOrNull { it.name == arg } }
                ?: ShiftAction.START
        )
    )
    val uiState: StateFlow<ShiftMarkUiState> = _uiState.asStateFlow()

    // The scanner service is app-wide: a scan on another screen must not mark.
    private var isScreenActive = false

    init {
        networkMonitor.isOnline
            .onEach { online -> _uiState.update { it.copy(isOnline = online) } }
            .launchIn(viewModelScope)
        barcodeService.scannedBarcodes
            .onEach { if (isScreenActive) submit(qr = it.rawValue.trim()) }
            .launchIn(viewModelScope)
    }

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
    }

    fun onAdminCodeChange(value: String) {
        val digits = value.filter(Char::isDigit).take(ADMIN_CODE_LENGTH)
        _uiState.update { it.copy(adminCode = digits, error = null) }
    }

    fun submitAdminCode() {
        val code = _uiState.value.adminCode
        if (code.length == ADMIN_CODE_LENGTH) submit(adminCode = code)
    }

    private fun submit(qr: String? = null, adminCode: String? = null) {
        val state = _uiState.value
        if (state.isSubmitting || state.isDone) return
        if (qr.isNullOrEmpty() && adminCode.isNullOrEmpty()) return
        if (!state.isOnline) {
            _uiState.update { it.copy(error = ShiftMarkError.Unreachable) }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.mark(state.action, qr = qr, adminCode = adminCode)) {
                is ShiftMarkResult.Success -> _uiState.update {
                    it.copy(isSubmitting = false, isDone = true, doneMessage = r.message)
                }
                is ShiftMarkResult.Refused -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = r.message?.takeIf(String::isNotBlank)?.let(ShiftMarkError::Refused)
                            ?: ShiftMarkError.Refused(r.code),
                        // A wrong code is retyped from scratch.
                        adminCode = if (adminCode != null) "" else it.adminCode,
                    )
                }
                is ShiftMarkResult.Unreachable -> _uiState.update {
                    it.copy(isSubmitting = false, error = ShiftMarkError.Unreachable)
                }
            }
        }
    }
}
