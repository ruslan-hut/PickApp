package ua.com.programmer.pick.presentation.printer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.domain.model.LabelPrinter
import ua.com.programmer.pick.domain.repository.LabelPrintRepository
import javax.inject.Inject

data class PrinterPickerUiState(
    val loading: Boolean = true,
    val printers: List<LabelPrinter> = emptyList(),
    val selectedId: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * The label printers this worker may pick and the terminal's current pick —
 * both server-side: the list is the warehouse's, set up in the tenant UI, and
 * the pick is stored on the device record, so it outlives restarts.
 */
@HiltViewModel
class PrinterPickerViewModel @Inject constructor(
    private val repository: LabelPrintRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrinterPickerUiState())
    val uiState: StateFlow<PrinterPickerUiState> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.printers().fold(
                onSuccess = { c -> _uiState.update { it.copy(loading = false, printers = c.printers, selectedId = c.selectedId) } },
                onFailure = { e -> _uiState.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    /** Picks [printerId] (null clears); [onDone] gets true when stored. */
    fun select(printerId: String?, onDone: (Boolean) -> Unit) {
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            repository.selectPrinter(printerId).fold(
                onSuccess = {
                    _uiState.update { it.copy(saving = false, selectedId = printerId) }
                    onDone(true)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(saving = false, error = e.message) }
                    onDone(false)
                },
            )
        }
    }
}
