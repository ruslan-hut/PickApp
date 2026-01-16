package ua.com.programmer.pick.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.domain.repository.UserRepository
import ua.com.programmer.pick.presentation.navigation.Screen
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    companion object {
        const val ERROR_INITIALIZATION = "ERROR_INITIALIZATION"
    }

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    fun start() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Small delay to show branding; in real app this waits for DB init/migrations
            delay(500)

            try {
                val user = userRepository.getCurrentUser().first()
                val target = if (user != null) Screen.Home.route else Screen.Login.route
                _uiState.update { it.copy(targetRoute = target, isLoading = false) }
            } catch (ex: Exception) {
                // Use an internal error key instead of a hardcoded user-facing string
                _uiState.update { it.copy(errorMessage = ERROR_INITIALIZATION, isLoading = false) }
            }
        }
    }

    fun retry() {
        start()
    }
}
