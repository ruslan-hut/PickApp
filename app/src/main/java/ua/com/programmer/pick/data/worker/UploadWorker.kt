package ua.com.programmer.pick.data.worker

import android.content.Context
import ua.com.programmer.pick.core.util.AppLog
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ua.com.programmer.pick.data.sync.SyncOrchestrator

/**
 * Background worker for immediate upload of pending operations.
 * Processes the outgoing queue and sends changes to the server.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncOrchestrator: SyncOrchestrator
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UploadWorker"
    }

    override suspend fun doWork(): Result {
        AppLog.d(TAG, "Starting upload work, attempt: $runAttemptCount")

        return try {
            syncOrchestrator.processPendingOperations()
            AppLog.d(TAG, "Upload completed successfully")
            Result.success()
        } catch (e: Exception) {
            AppLog.e(TAG, "Upload work failed with exception: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
