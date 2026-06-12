package ua.com.programmer.pick.data.debug

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.local.database.dao.DebugJournalDao
import ua.com.programmer.pick.data.local.database.entity.DebugJournalEntity
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fail-safe sink for device-side debug events. All log calls are fire-and-forget
 * and must never throw into business logic. When disabled, log() is effectively
 * a StateFlow read plus an early return.
 */
@Singleton
class DebugJournal @Inject constructor(
    private val dao: DebugJournalDao,
    private val appPreferences: AppPreferences,
    private val gson: Gson,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "DebugJournal"
        const val SEVERITY_INFO = "INFO"
        const val SEVERITY_WARN = "WARN"
        const val SEVERITY_ERROR = "ERROR"
        private const val BLOCKING_WRITE_TIMEOUT_MS = 2_000L
    }

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    val enabled: StateFlow<Boolean> = appPreferences.debugJournalEnabled
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = runCatching { appPreferences.getDebugJournalEnabledSync() }.getOrDefault(false)
        )

    fun log(
        eventType: String,
        message: String,
        documentId: String? = null,
        stage: String? = null,
        severity: String = SEVERITY_INFO,
        payload: Any? = null
    ) {
        if (!enabled.value) return
        val createdAt = System.currentTimeMillis()
        val payloadJson = payload?.let {
            try { gson.toJson(it) } catch (e: Exception) { null }
        }
        scope.launch {
            try {
                val tenantId = appPreferences.getTenantIdSync() ?: return@launch
                val deviceId = appPreferences.getDeviceIdSync()
                val userId = runCatching { appPreferences.currentUserId.firstOrNull() }.getOrNull()
                dao.insert(
                    DebugJournalEntity(
                        id = UUID.randomUUID().toString(),
                        tenantId = tenantId,
                        deviceId = deviceId,
                        userId = userId,
                        documentId = documentId,
                        stage = stage,
                        eventType = eventType,
                        severity = severity,
                        message = message,
                        payloadJson = payloadJson,
                        createdAt = createdAt
                    )
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to write debug event: ${e.message}")
            }
        }
    }

    /**
     * Synchronous variant for process-death paths (uncaught exception handler),
     * where a fire-and-forget coroutine would never get to run before the
     * process is torn down. Bounded by a short timeout; never throws.
     */
    fun logBlocking(
        eventType: String,
        message: String,
        severity: String = SEVERITY_ERROR,
        payload: Any? = null
    ) {
        if (!enabled.value) return
        val createdAt = System.currentTimeMillis()
        val payloadJson = payload?.let {
            try { gson.toJson(it) } catch (e: Exception) { null }
        }
        try {
            runBlocking {
                withTimeoutOrNull(BLOCKING_WRITE_TIMEOUT_MS) {
                    withContext(ioDispatcher) {
                        val tenantId = appPreferences.getTenantIdSync() ?: return@withContext
                        dao.insert(
                            DebugJournalEntity(
                                id = UUID.randomUUID().toString(),
                                tenantId = tenantId,
                                deviceId = appPreferences.getDeviceIdSync(),
                                userId = runCatching { appPreferences.currentUserId.firstOrNull() }.getOrNull(),
                                documentId = null,
                                stage = null,
                                eventType = eventType,
                                severity = severity,
                                message = message,
                                payloadJson = payloadJson,
                                createdAt = createdAt
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to write blocking debug event: ${e.message}")
        }
    }

    suspend fun prune() {
        try {
            val cutoff = System.currentTimeMillis() - Constants.DebugJournal.MAX_AGE_MS
            dao.pruneOlderThan(cutoff)
            dao.pruneOverCap(Constants.DebugJournal.MAX_ROWS)
        } catch (e: Exception) {
            AppLog.w(TAG, "Prune failed: ${e.message}")
        }
    }

    fun observeByTenant(tenantId: String): Flow<List<DebugJournalEntity>> =
        dao.observeByTenant(tenantId)

    fun observeByDocument(tenantId: String, documentId: String): Flow<List<DebugJournalEntity>> =
        dao.observeByDocument(tenantId, documentId)

    suspend fun deleteAll() {
        try { dao.deleteAll() } catch (e: Exception) {
            AppLog.w(TAG, "Clear failed: ${e.message}")
        }
    }
}
