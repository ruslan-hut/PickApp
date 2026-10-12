package ua.com.programmer.pick.presentation.settings

data class SettingsUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val appVersion: String = ""
)
