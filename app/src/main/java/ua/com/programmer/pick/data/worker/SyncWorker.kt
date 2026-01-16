package ua.com.programmer.pick.data.worker

import android.content.Context
import android.util.Log
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
        Log.d(TAG, "Starting sync work, attempt: $runAttemptCount")

        return try {
            // First upload any pending operations
            when (val uploadResult = syncOrchestrator.uploadPendingOperations()) {
                is ua.com.programmer.pick.core.util.Result.Success -> {
                    Log.d(TAG, "Uploaded ${uploadResult.data} pending operations")
                }
                is ua.com.programmer.pick.core.util.Result.Error -> {
                    Log.w(TAG, "Failed to upload pending operations: ${uploadResult.exception.message}")
                    // Continue with sync even if upload fails
                }
                is ua.com.programmer.pick.core.util.Result.Loading -> { /* Shouldn't happen */ }
            }

            // Then download updates
            when (val syncResult = syncOrchestrator.syncAll()) {
                is ua.com.programmer.pick.core.util.Result.Success -> {
                    Log.d(TAG, "Sync completed successfully")
                    Result.success()
                }
                is ua.com.programmer.pick.core.util.Result.Error -> {
                    Log.e(TAG, "Sync failed: ${syncResult.exception.message}")
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
            Log.e(TAG, "Sync work failed with exception: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
