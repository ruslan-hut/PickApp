package ua.com.programmer.pick.presentation.profile

import ua.com.programmer.pick.domain.model.User

data class ProfileUiState(
    val user: User? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val deviceId: String = "",
    val appVersion: String = ""
)
