package ua.com.programmer.pick.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ua.com.programmer.pick.data.session.SessionGuard
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

/**
 * App-wide reaction to "this device is no longer linked": the nav graph moves
 * to the pairing screen, then [onUnlinkHandled] drops the session the way a
 * logout does. Local data goes too — the server refuses this device's writes,
 * and a link to another tenant would wipe it on the first login anyway.
 */
@HiltViewModel
class SessionGuardViewModel @Inject constructor(
    private val sessionGuard: SessionGuard,
    private val userRepository: UserRepository,
) : ViewModel() {

    val unlinked: StateFlow<String?> = sessionGuard.unlinked

    fun onUnlinkHandled() {
        sessionGuard.consume()
        viewModelScope.launch { userRepository.logout() }
    }
}
