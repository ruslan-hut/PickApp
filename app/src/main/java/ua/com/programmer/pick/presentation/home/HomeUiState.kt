package ua.com.programmer.pick.presentation.home

import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.model.OpenTask
import ua.com.programmer.pick.domain.model.SyncState
import ua.com.programmer.pick.domain.model.User

data class HomeUiState(
    val currentUser: User? = null,
    val availableDocumentTypes: List<AvailableDocumentType> = emptyList(),
    val selectedDocumentTypeCode: String? = null,
    // The worker's unfinished guided tasks (continue / cancel). Always empty
    // on a tenant without the WMS addressing module.
    val openTasks: List<OpenTask> = emptyList(),
    val syncStates: List<SyncState> = emptyList(),
    val isOnline: Boolean = true,
    val lastSyncTime: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
