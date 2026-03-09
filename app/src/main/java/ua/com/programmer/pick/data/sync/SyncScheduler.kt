package ua.com.programmer.pick.data.sync

import android.content.Context
import ua.com.programmer.pick.core.util.AppLog
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import ua.com.programmer.pick.core.Constants
import ua.com.programmer.pick.data.worker.SyncWorker
import ua.com.programmer.pick.data.worker.UploadWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and manages background sync operations using WorkManager.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SyncScheduler"
    }

    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule periodic background sync.
     * Called once on app initialization.
     */
    fun schedulePeriodicSync() {
        AppLog.d(TAG, "Scheduling periodic sync")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            Constants.Work.SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                Constants.Work.BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(Constants.Work.SYNC_WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            Constants.Work.SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    /**
     * Trigger immediate sync when network becomes available.
     */
    fun triggerImmediateSync() {
        AppLog.d(TAG, "Triggering immediate sync")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag(Constants.Work.SYNC_WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(
            "${Constants.Work.SYNC_WORK_NAME}_immediate",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    /**
     * Schedule immediate upload of pending operations.
     * Called when there are dirty records to sync.
     */
    fun scheduleUpload() {
        AppLog.d(TAG, "Scheduling upload")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                Constants.Work.BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(Constants.Work.UPLOAD_WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(
            Constants.Work.UPLOAD_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            uploadRequest
        )
    }

    /**
     * Cancel all scheduled sync work.
     */
    fun cancelAllSync() {
        AppLog.d(TAG, "Cancelling all sync work")
        workManager.cancelUniqueWork(Constants.Work.SYNC_WORK_NAME)
        workManager.cancelUniqueWork(Constants.Work.UPLOAD_WORK_NAME)
    }

    /**
     * Cancel only periodic sync (keep one-time jobs).
     */
    fun cancelPeriodicSync() {
        AppLog.d(TAG, "Cancelling periodic sync")
        workManager.cancelUniqueWork(Constants.Work.SYNC_WORK_NAME)
    }
}
