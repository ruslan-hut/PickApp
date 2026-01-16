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
        Log.d(TAG, "Starting upload work, attempt: $runAttemptCount")

        return try {
            when (val result = syncOrchestrator.uploadPendingOperations()) {
                is ua.com.programmer.pick.core.util.Result.Success -> {
                    val uploadedCount = result.data
                    Log.d(TAG, "Upload completed successfully, uploaded $uploadedCount operations")
                    Result.success()
                }
                is ua.com.programmer.pick.core.util.Result.Error -> {
                    Log.e(TAG, "Upload failed: ${result.exception.message}")
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
            Log.e(TAG, "Upload work failed with exception: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
