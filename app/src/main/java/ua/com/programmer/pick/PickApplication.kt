package ua.com.programmer.pick

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ua.com.programmer.pick.data.sync.SyncOrchestrator
import ua.com.programmer.pick.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class PickApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var syncOrchestrator: SyncOrchestrator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize sync orchestrator
        syncOrchestrator.initialize()

        // Schedule periodic background sync
        syncScheduler.schedulePeriodicSync()
    }
}
