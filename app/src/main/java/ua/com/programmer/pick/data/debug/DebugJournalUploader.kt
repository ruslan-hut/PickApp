package ua.com.programmer.pick.data.debug

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.database.dao.DebugJournalDao
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.websocket.DebugEventPayload
import ua.com.programmer.pick.data.remote.websocket.MessageParser
import ua.com.programmer.pick.data.remote.websocket.SyncMessage
import ua.com.programmer.pick.data.remote.websocket.SyncTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Batches unuploaded debug journal events and pushes them to the server via
 * WebSocket. Best-effort: on failure, attempts counter is bumped and we retry
 * on the next trigger. No queueing into OutgoingOperationEntity.
 */
@Singleton
class DebugJournalUploader @Inject constructor(
    private val dao: DebugJournalDao,
    private val journal: DebugJournal,
    private val webSocketManager: SyncTransport,
    private val messageParser: MessageParser,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "DebugJournalUploader"
    }

    private val mutex = Mutex()

    /**
     * Flush any pending debug events. Returns the number of events successfully
     * uploaded. Safe to call concurrently (mutex-guarded).
     */
    suspend fun flush(): Int = mutex.withLock {
        // Promote skip reasons to INFO so logcat reveals why a device isn't
        // shipping events — silent zero-returns made past investigations
        // (e.g. only 2/5 devices uploading) hard to diagnose remotely.
        if (!journal.enabled.value) {
            AppLog.i(TAG, "flush skipped: debug journal disabled")
            return 0
        }
        if (!webSocketManager.isConnected()) {
            AppLog.i(TAG, "flush skipped: WS not connected")
            return 0
        }
        if (!webSocketManager.isUserAuthenticated()) {
            AppLog.i(TAG, "flush skipped: user not authenticated")
            return 0
        }

        var totalUploaded = 0
        try {
            while (true) {
                val batch = dao.getUnuploadedBatch(Constants.DebugJournal.UPLOAD_BATCH)
                if (batch.isEmpty()) {
                    if (totalUploaded == 0) AppLog.i(TAG, "flush: no unuploaded events")
                    break
                }

                val tenantId = appPreferences.getTenantIdSync()
                if (tenantId == null) {
                    AppLog.i(TAG, "flush stopped: no tenant_id in preferences (uploaded so far=$totalUploaded)")
                    return totalUploaded
                }
                val deviceId = appPreferences.getDeviceIdSync()

                val message = SyncMessage.DebugEventBatch(
                    id = messageParser.generateMessageId(),
                    timestamp = messageParser.getCurrentTimestamp(),
                    tenantId = tenantId,
                    deviceId = deviceId,
                    events = batch.map { e ->
                        DebugEventPayload(
                            id = e.id,
                            userId = e.userId,
                            documentId = e.documentId,
                            stage = e.stage,
                            eventType = e.eventType,
                            severity = e.severity,
                            message = e.message,
                            payloadJson = e.payloadJson,
                            createdAt = e.createdAt
                        )
                    }
                )

                val response = webSocketManager.sendAndAwait(
                    message,
                    SyncMessage.DebugEventBatchResult::class.java
                )

                val batchIds = batch.map { it.id }
                if (response != null && response.success) {
                    val accepted = if (response.acceptedIds.isNotEmpty()) response.acceptedIds else batchIds
                    dao.markUploaded(accepted)
                    totalUploaded += accepted.size
                    if (accepted.size < batch.size) {
                        // Server rejected some — bump attempts on the rest and stop
                        dao.incrementAttempts(batchIds - accepted.toSet())
                        AppLog.w(TAG, "flush: server accepted ${accepted.size}/${batch.size}, rest will retry")
                        break
                    }
                } else {
                    dao.incrementAttempts(batchIds)
                    AppLog.w(TAG, "flush: batch rejected (response=${response?.success}, error=${response?.error}, batch_size=${batch.size})")
                    break
                }

                // Stop if we got a partial batch (no more data)
                if (batch.size < Constants.DebugJournal.UPLOAD_BATCH) break
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Debug journal upload failed: ${e.message}")
        }
        if (totalUploaded > 0) {
            AppLog.i(TAG, "flush: uploaded=$totalUploaded events")
        }
        totalUploaded
    }
}
