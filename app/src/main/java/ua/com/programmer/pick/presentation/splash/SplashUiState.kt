package ua.com.programmer.pick.presentation.splash

data class SplashUiState(
    val targetRoute: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)
