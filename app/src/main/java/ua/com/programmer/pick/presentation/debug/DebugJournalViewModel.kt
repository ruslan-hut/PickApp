package ua.com.programmer.pick.presentation.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.debug.DebugJournalUploader
import ua.com.programmer.pick.data.local.database.entity.DebugJournalEntity
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.model.UserRole
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

data class DebugJournalUiState(
    val events: List<DebugJournalEntity> = emptyList(),
    val isAdmin: Boolean = false,
    val isLoading: Boolean = true,
    val groupByDocument: Map<String?, List<DebugJournalEntity>> = emptyMap()
)

@HiltViewModel
class DebugJournalViewModel @Inject constructor(
    private val journal: DebugJournal,
    private val uploader: DebugJournalUploader,
    private val userRepository: UserRepository,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugJournalUiState())
    val uiState: StateFlow<DebugJournalUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val user = try { userRepository.getCurrentUser().first() } catch (_: Exception) { null }
            val isAdmin = user?.role == UserRole.ADMINISTRATOR
            _uiState.update { it.copy(isAdmin = isAdmin) }
            if (!isAdmin) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            val tenantId = appPreferences.getTenantIdSync() ?: run {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            journal.observeByTenant(tenantId).collect { events ->
                _uiState.update {
                    it.copy(
                        events = events,
                        isLoading = false,
                        groupByDocument = events.groupBy { e -> e.documentId }
                    )
                }
            }
        }
    }

    fun flushNow() {
        viewModelScope.launch {
            try { uploader.flush() } catch (_: Exception) {}
        }
    }

    fun pruneNow() {
        viewModelScope.launch { journal.prune() }
    }

    fun clearAll() {
        viewModelScope.launch { journal.deleteAll() }
    }
}
