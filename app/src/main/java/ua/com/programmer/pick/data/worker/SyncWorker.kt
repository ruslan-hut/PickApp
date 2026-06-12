package ua.com.programmer.pick.data.worker

import android.content.Context
import ua.com.programmer.pick.core.util.AppLog
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.sync.SyncOrchestrator

/**
 * Background worker for periodic synchronization.
 * Downloads delta updates from server and applies them to local database.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncOrchestrator: SyncOrchestrator
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        AppLog.d(TAG, "Starting sync work, attempt: $runAttemptCount")

        return try {
            // First process any pending offline operations via transport
            try {
                syncOrchestrator.processPendingOperations()
                AppLog.d(TAG, "Processed pending operations")
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to process pending operations: ${e.message}")
                // Continue with sync even if processing fails
            }

            // Then request delta sync via transport
            when (val syncResult = syncOrchestrator.requestDeltaSync()) {
                is ua.com.programmer.pick.core.util.Result.Success -> {
                    AppLog.d(TAG, "Delta sync requested successfully")
                    Result.success()
                }
                is ua.com.programmer.pick.core.util.Result.Error -> {
                    AppLog.e(TAG, "Delta sync request failed: ${syncResult.exception.message}")
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
                is ua.com.programmer.pick.core.util.Result.Loading -> {
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Sync work failed with exception: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
