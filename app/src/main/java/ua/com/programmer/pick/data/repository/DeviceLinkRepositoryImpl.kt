package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.data.remote.api.DeviceApiException
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.domain.model.DeviceLinkResult
import ua.com.programmer.pick.domain.model.DeviceLinkStatus
import ua.com.programmer.pick.domain.model.LinkedTenant
import ua.com.programmer.pick.domain.model.PairingState
import ua.com.programmer.pick.domain.repository.DeviceLinkRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the REST client directly rather than the sync transport: linking
 * happens before login, never in the offline demo, and has no transport
 * semantics (no queueing, no retry — the pairing screen polls anyway).
 */
@Singleton
class DeviceLinkRepositoryImpl @Inject constructor(
    private val client: DeviceRestClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DeviceLinkRepository {

    override suspend fun pairing(): DeviceLinkResult<PairingState> = withContext(ioDispatcher) {
        client.pairing().toLinkResult { r ->
            PairingState(
                status = DeviceLinkStatus.fromWire(r.status),
                code = r.code,
                expiresInSec = r.expiresInSec,
                tenantName = r.tenantName,
            )
        }
    }

    override suspend fun enroll(token: String): DeviceLinkResult<LinkedTenant> = withContext(ioDispatcher) {
        client.enroll(token).toLinkResult { r -> LinkedTenant(r.tenantName.orEmpty(), r.deviceName) }
    }

    private fun <T, R> Result<T>.toLinkResult(map: (T) -> R): DeviceLinkResult<R> = fold(
        onSuccess = { DeviceLinkResult.Success(map(it)) },
        onFailure = { e ->
            // A DeviceApiException carrying a code is the server's verdict;
            // anything else (IO, timeout, parse) means we never got one.
            val code = (e as? DeviceApiException)?.code
            if (code != null) DeviceLinkResult.Refused(code, e.message)
            else DeviceLinkResult.Unreachable(e.message)
        },
    )
}
