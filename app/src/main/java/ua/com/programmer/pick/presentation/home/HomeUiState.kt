package ua.com.programmer.pick.presentation.home

import ua.com.programmer.pick.domain.model.SyncState
import ua.com.programmer.pick.domain.model.User

data class HomeUiState(
    val currentUser: User? = null,
    val syncStates: List<SyncState> = emptyList(),
    val isOnline: Boolean = true,
    val lastSyncTime: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
