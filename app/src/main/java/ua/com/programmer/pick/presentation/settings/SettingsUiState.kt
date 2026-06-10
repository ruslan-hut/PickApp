package ua.com.programmer.pick.presentation.settings

data class SettingsUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val appVersion: String = ""
)
