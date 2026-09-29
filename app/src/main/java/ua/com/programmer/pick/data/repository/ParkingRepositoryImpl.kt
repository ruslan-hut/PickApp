package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.data.remote.api.DeviceRestClient
import ua.com.programmer.pick.domain.model.ParkReason
import ua.com.programmer.pick.domain.repository.ParkingRepository
import ua.com.programmer.pick.domain.repository.ResumeOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls the REST client directly rather than the sync transport: parking is a
 * deliberate online action on an unlocked document — nothing to queue or
 * replay. The document list refresh that follows brings the new state in.
 */
@Singleton
class ParkingRepositoryImpl @Inject constructor(
    private val client: DeviceRestClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ParkingRepository {

    override suspend fun reasons(): Result<List<ParkReason>> = withContext(ioDispatcher) {
        client.parkReasons().map { r -> r.reasons.map { ParkReason(it.id, it.name) } }
    }

    override suspend fun park(documentId: String, reasonId: String, note: String?): Result<Unit> =
        withContext(ioDispatcher) {
            client.parkDocument(documentId, reasonId, note?.takeIf { it.isNotBlank() }).map { }
        }

    override suspend fun resume(documentId: String): Result<ResumeOutcome> = withContext(ioDispatcher) {
        client.resumeDocument(documentId).map { ResumeOutcome(it.state, it.version.toInt(), it.readyToWork) }
    }
}
