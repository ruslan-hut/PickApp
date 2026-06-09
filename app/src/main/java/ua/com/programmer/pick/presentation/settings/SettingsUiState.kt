package ua.com.programmer.pick.presentation.settings

data class SettingsUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val appVersion: String = "",
    // true = device-initiated REST transport, false = WebSocket (default).
    // Changing it takes effect on next app start (the binding is resolved once).
    val transportRest: Boolean = false
)
