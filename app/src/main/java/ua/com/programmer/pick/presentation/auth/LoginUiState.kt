package ua.com.programmer.pick.presentation.auth

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null,
    val isOfflineMode: Boolean = false,
    val deviceId: String = ""
)
