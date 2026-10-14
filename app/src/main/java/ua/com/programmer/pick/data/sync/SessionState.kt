package ua.com.programmer.pick.data.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

private const val SESSION_GRACE_MS = 2_000L

/**
 * True once the device has stayed without a server session for a moment. The
 * grace period keeps the warning off the screen while the auto-login of a cold
 * start or a foreground resume is still in flight. Never true in demo mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun SyncOrchestrator.sessionLost(): Flow<Boolean> =
    syncState
        .map { it.isUserAuthenticated }
        .distinctUntilChanged()
        .mapLatest { authenticated ->
            if (authenticated || !hasServerSession) {
                false
            } else {
                delay(SESSION_GRACE_MS)
                true
            }
        }
