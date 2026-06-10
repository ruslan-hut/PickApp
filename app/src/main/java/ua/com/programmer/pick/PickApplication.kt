package ua.com.programmer.pick

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.com.programmer.pick.core.scanner.BarcodeService
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.core.util.FileLogger
import ua.com.programmer.pick.data.remote.websocket.SyncTransport
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.data.sync.SyncScheduler
import ua.com.programmer.pick.domain.repository.UserRepository
import javax.inject.Inject

@HiltAndroidApp
class PickApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var syncOrchestrator: SyncOrchestrator

    @Inject
    lateinit var barcodeService: BarcodeService

    @Inject
    lateinit var webSocketManager: SyncTransport

    @Inject
    lateinit var userRepository: UserRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize file logger as early as possible so subsequent init is captured
        FileLogger.initialize(this)

        // Initialize sync orchestrator
        syncOrchestrator.initialize()

        // Schedule periodic background sync
        syncScheduler.schedulePeriodicSync()
        syncScheduler.scheduleDebugJournalWork()

        // Initialize barcode service
        barcodeService.initialize()

        // Start hardware scanner so we receive scanner intents (registers receiver)
        barcodeService.startHardwareScanner()

        // Foreground-resume sync hook: when the app returns to the foreground
        // and reference data has aged past FOREGROUND_RESYNC_THRESHOLD_MS,
        // request a delta sync. Covers the gap when WorkManager periodic sync
        // is throttled by Doze/app-standby and ERP-side box catalog updates
        // would otherwise be invisible until the next 15-min cycle.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Probe the WebSocket first: a long Doze sleep freezes the PING
                // timer, so connectionState can read Connected while the socket
                // is dead — making the next lock attempt time out. Force a
                // reconnect if the socket is stale.
                webSocketManager.verifyConnectionHealth()
                appScope.launch {
                    try {
                        // Re-establish auth if the session lapsed (process was
                        // killed, or a disconnect cleared it). The REST transport
                        // does not re-authenticate on its own, so without this the
                        // sync below bails on the "not authenticated" gate.
                        if (!webSocketManager.isUserAuthenticated()) {
                            userRepository.autoLogin()
                        }
                        syncOrchestrator.requestDeltaSyncIfStale(FOREGROUND_RESYNC_THRESHOLD_MS)
                    } catch (e: Exception) {
                        AppLog.w("PickApplication", "foreground resync failed: ${e.message}")
                    }
                }
            }
        })
    }

    private companion object {
        const val FOREGROUND_RESYNC_THRESHOLD_MS = 60_000L
    }
}
