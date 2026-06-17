package ua.com.programmer.pick.data.remote.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.transport.demo.DemoTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bound [SyncTransport] seam. Delegates every call to one of two backing
 * transports — the real [RestTransport] or the offline [DemoTransport] — chosen
 * by the persisted `demo_mode` flag. The orchestrator collects this router's
 * flows exactly once at startup; [flatMapLatest] over [active] re-subscribes to
 * whichever delegate is current, so switching into a demo session needs no
 * orchestrator changes.
 *
 * The active delegate is (re)evaluated from the flag on [connect] and
 * [loginUser] — the two points the login path always crosses — and seeded at
 * construction so a demo session resumed after process death routes to the demo
 * transport before the first sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class RoutingTransport @Inject constructor(
    private val rest: RestTransport,
    private val demo: DemoTransport,
    private val appPreferences: AppPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SyncTransport {

    private val scope = CoroutineScope(ioDispatcher)

    private val active = MutableStateFlow<SyncTransport>(
        if (appPreferences.getDemoModeSync()) demo else rest
    )

    private fun refreshActive() {
        active.value = if (appPreferences.getDemoModeSync()) demo else rest
    }

    override val connectionState: StateFlow<ConnectionState> =
        active.flatMapLatest { it.connectionState }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    override val userAuthState: StateFlow<UserAuthState> =
        active.flatMapLatest { it.userAuthState }
            .stateIn(scope, SharingStarted.Eagerly, UserAuthState.NotAuthenticated)

    // Merge both delegates' inbound streams permanently rather than switching
    // with the active flow. Only the currently-active transport ever emits, so
    // the merge is conflict-free — and because the subscription is established
    // once at construction it avoids the re-subscription gap that would drop the
    // first sync batch right after rest->demo switches at login (replay=0).
    override val incomingMessages: SharedFlow<SyncMessage> =
        merge(rest.incomingMessages, demo.incomingMessages)
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val requiresPolling: Boolean get() = active.value.requiresPolling

    override fun connect() {
        refreshActive()
        active.value.connect()
    }

    override fun disconnect() = active.value.disconnect()

    override fun forceReconnect() = active.value.forceReconnect()

    override fun verifyConnectionHealth() = active.value.verifyConnectionHealth()

    override fun isConnected(): Boolean = active.value.isConnected()

    override fun isUserAuthenticated(): Boolean = active.value.isUserAuthenticated()

    override fun sendMessage(message: SyncMessage): Boolean = active.value.sendMessage(message)

    override suspend fun loginUser(login: String, password: String): UserLoginResult {
        refreshActive()
        return active.value.loginUser(login, password)
    }

    override suspend fun <T : SyncMessage> sendAndAwait(
        message: SyncMessage,
        responseType: Class<T>,
        timeoutMs: Long,
    ): T? = active.value.sendAndAwait(message, responseType, timeoutMs)
}
