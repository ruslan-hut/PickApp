package ua.com.programmer.pick.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.com.programmer.pick.BuildConfig
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val fullId = appPreferences.deviceId.first()
            _uiState.update {
                it.copy(
                    deviceId = fullId.take(8),
                    appVersion = BuildConfig.VERSION_NAME
                )
            }
        }
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                userRepository.getCurrentUser().collect { user ->
                    _uiState.update {
                        it.copy(
                            user = user,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = ERROR_LOADING_PROFILE
                    )
                }
            }
        }
    }

    fun updateName(newName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val currentUser = _uiState.value.user
                if (currentUser != null) {
                    val updatedUser = currentUser.copy(name = newName)
                    userRepository.updateUser(updatedUser)
                    _uiState.update {
                        it.copy(
                            user = updatedUser,
                            isSaving = false,
                            successMessage = SUCCESS_PROFILE_UPDATED
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = ERROR_NO_USER
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = ERROR_SAVING_PROFILE
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    companion object {
        const val ERROR_LOADING_PROFILE = "ERROR_LOADING_PROFILE"
        const val ERROR_SAVING_PROFILE = "ERROR_SAVING_PROFILE"
        const val ERROR_NO_USER = "ERROR_NO_USER"
        const val SUCCESS_PROFILE_UPDATED = "SUCCESS_PROFILE_UPDATED"
    }
}
