package ua.com.programmer.pick.presentation.auth

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val errorMessage: String? = null,
    val isOfflineMode: Boolean = false,
    val deviceId: String = "",
    val lastLogin: String = "",
    /** The server refused this device, not the worker: open "Connect to company". */
    val needsDeviceLink: Boolean = false,
    /** An enrollment QR scanned on this screen, handed on to the pairing screen. */
    val enrollQr: String? = null
)
