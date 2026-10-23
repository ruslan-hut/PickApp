package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.api.DeviceApiException
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.data.remote.transport.SyncTransport
import ua.com.programmer.pick.data.remote.transport.UserAuthState
import ua.com.programmer.pick.domain.model.ShiftAction
import ua.com.programmer.pick.domain.model.ShiftMarkResult
import ua.com.programmer.pick.domain.model.ShiftStatus
import ua.com.programmer.pick.domain.repository.ShiftRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the REST client directly, like device linking: a shift mark is
 * online-only and has no transport semantics (no queue, no demo data). The
 * offline demo session has no shifts at all.
 *
 * Retry policy — the guided tasks' one: a failure without a server error code
 * is retried up to [MAX_ATTEMPTS] times with the **same** `operation_id`, so a
 * mark that landed before the connection dropped is answered with the stored
 * shift instead of SHIFT_ALREADY_STARTED. A server verdict is never retried.
 */
@Singleton
class ShiftRepositoryImpl @Inject constructor(
    private val client: DeviceRestClient,
    private val transport: SyncTransport,
    private val appPreferences: AppPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ShiftRepository {

    private companion object {
        const val TAG = "ShiftRepository"
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
        const val STATUS_OPEN = "OPEN"
    }

    private val scope = CoroutineScope(ioDispatcher)

    private val _status = MutableStateFlow(ShiftStatus.DISABLED)
    override val status: StateFlow<ShiftStatus> = _status.asStateFlow()

    init {
        // Asked once per sign-in here; Home asks again every time it resumes.
        transport.userAuthState
            .onEach { state ->
                when (state) {
                    is UserAuthState.Authenticated -> scope.launch { refresh() }
                    is UserAuthState.NotAuthenticated -> _status.value = ShiftStatus.DISABLED
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    override suspend fun refresh() = withContext(ioDispatcher) {
        if (appPreferences.getDemoModeSync()) {
            _status.value = ShiftStatus.DISABLED
            return@withContext
        }
        client.shift()
            .onSuccess { r ->
                _status.value = ShiftStatus(
                    enabled = r.enabled,
                    isOpen = r.enabled && r.shift?.status == STATUS_OPEN,
                    startedText = r.startedText,
                    collectingDocument = r.collectingDocument?.takeIf { it.isNotBlank() },
                )
            }
            .onFailure { AppLog.w(TAG, "shift status failed: ${it.message}") }
        Unit
    }

    override suspend fun mark(action: ShiftAction, qr: String?, adminCode: String?): ShiftMarkResult {
        val request = DeviceDto.ShiftMarkRequest(
            qr = qr,
            adminCode = adminCode,
            operationId = UUID.randomUUID().toString(),
        )
        var lastMessage: String? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            val result = withContext(ioDispatcher) {
                when (action) {
                    ShiftAction.START -> client.shiftStart(request)
                    ShiftAction.END -> client.shiftEnd(request)
                }
            }
            result.onSuccess { r ->
                refresh()
                return ShiftMarkResult.Success(r.message)
            }
            val error = result.exceptionOrNull()
            val code = (error as? DeviceApiException)?.code
            if (code != null) {
                AppLog.w(TAG, "shift ${action.name.lowercase()} refused: $code")
                // SHIFT_ALREADY_STARTED / SHIFT_NOT_STARTED: Home was out of date.
                refresh()
                return ShiftMarkResult.Refused(code, error.message)
            }
            lastMessage = error?.message
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAYS_MS[attempt])
        }
        AppLog.w(TAG, "shift ${action.name.lowercase()} unreachable: $lastMessage")
        return ShiftMarkResult.Unreachable(lastMessage)
    }
}
