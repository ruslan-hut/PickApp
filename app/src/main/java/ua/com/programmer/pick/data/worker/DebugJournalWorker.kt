package ua.com.programmer.pick.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ua.com.programmer.pick.core.util.AppLog
import ua.com.programmer.pick.data.debug.DebugJournal
import ua.com.programmer.pick.data.debug.DebugJournalUploader

/**
 * Periodic worker that prunes old debug events and uploads pending ones.
 */
@HiltWorker
class DebugJournalWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val journal: DebugJournal,
    private val uploader: DebugJournalUploader
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DebugJournalWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            journal.prune()
            val count = uploader.flush()
            AppLog.d(TAG, "Debug journal cycle: uploaded=$count")
            Result.success()
        } catch (e: Exception) {
            AppLog.w(TAG, "Debug journal worker failed: ${e.message}")
            Result.success() // best-effort, never fail WorkManager chain
        }
    }
}
