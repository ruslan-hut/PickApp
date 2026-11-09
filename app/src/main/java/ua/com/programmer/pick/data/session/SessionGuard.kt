package ua.com.programmer.pick.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.com.programmer.pick.core.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries the server's verdict that this device is no longer linked to a
 * tenant, out of the network layer to the navigation.
 *
 * The transport is connectionless, so a device the tenant released (or the
 * admin rejected) learns it only from the next answer: any authenticated call,
 * a token refresh or the auto-login of a cold start comes back with
 * DEVICE_NOT_APPROVED / DEVICE_NO_TENANT. Whoever sees it reports here; the nav
 * graph drops the session and opens the pairing screen.
 *
 * A StateFlow, not an event, so a verdict reported before the nav graph starts
 * collecting (cold start) is not lost.
 */
@Singleton
class SessionGuard @Inject constructor() {

    private val _unlinked = MutableStateFlow<String?>(null)

    /** The refusal code while an unlink is waiting to be handled, else null. */
    val unlinked: StateFlow<String?> = _unlinked.asStateFlow()

    fun reportUnlinked(code: String) {
        if (_unlinked.compareAndSet(null, code)) {
            AppLog.w(TAG, "server says the device is not linked ($code)")
        }
    }

    /** The unlink was handled (session dropped, pairing screen shown). */
    fun consume() {
        _unlinked.value = null
    }

    private companion object {
        const val TAG = "SessionGuard"
    }
}
