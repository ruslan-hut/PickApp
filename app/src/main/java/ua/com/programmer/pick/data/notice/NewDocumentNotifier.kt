package ua.com.programmer.pick.data.notice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.NewDocumentNotice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls `GET /device/queue/new` while the app is in the foreground and a worker
 * is signed in, and emits the documents the server reports as new in that
 * worker's queue. Everything is server-decided — whether the option is on for
 * the worker, which documents count, and the cursor; the app only shows them.
 *
 * While the server keeps the option on, polling carries on in the background
 * too ([active] drives [QueueNoticeService], which keeps the process alive), so
 * a locked or switched-off screen still gets the notice. With the option off
 * it stops when the app leaves the foreground — nothing to wait for.
 *
 * The cursor lives in memory per signed-in worker: a new session, or the
 * option switched off, starts from the moment of its first poll, so documents
 * that were already waiting never pop up.
 */
@Singleton
class NewDocumentNotifier @Inject constructor(
    private val client: DeviceRestClient,
    private val transport: SyncTransport,
    @param:IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private companion object {
        const val TAG = "NewDocumentNotifier"
        const val POLL_INTERVAL_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var pollJob: Job? = null

    @Volatile private var cursor: String? = null
    @Volatile private var cursorUserId: String? = null

    private val _active = MutableStateFlow(false)

    /** True while a signed-in worker has the notice option on: the service runs. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _notices = MutableSharedFlow<List<NewDocumentNotice>>(extraBufferCapacity = 8)
    val notices: SharedFlow<List<NewDocumentNotice>> = _notices.asSharedFlow()

    /** Starts polling; called when the app comes to the foreground. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * The app left the foreground. Polling goes on while the option is on;
     * otherwise it stops, and the cursor is kept for the return.
     */
    fun onBackgrounded() {
        if (_active.value) return
        stop()
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun poll() {
        val auth = transport.userAuthState.value as? UserAuthState.Authenticated
        // The demo transport has no server to ask.
        if (auth == null || !transport.requiresPolling) {
            cursor = null
            _active.value = false
            return
        }
        if (auth.userId != cursorUserId) {
            cursor = null
            cursorUserId = auth.userId
        }
        client.queueNew(cursor)
            .onSuccess { r ->
                cursor = if (r.enabled) r.cursor else null
                _active.value = r.enabled
                if (r.documents.isNotEmpty()) {
                    _notices.emit(r.documents.map {
                        NewDocumentNotice(it.id, it.number, it.salesChannel?.takeIf { ch -> ch.isNotBlank() })
                    })
                }
            }
            .onFailure { AppLog.d(TAG, "poll failed: ${it.message}") }
    }
}
