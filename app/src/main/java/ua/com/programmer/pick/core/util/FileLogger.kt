package ua.com.programmer.pick.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple thread-safe rotating file logger.
 *
 * Captures logs to a file in the app's internal storage so that users can
 * export and share them via the Settings screen. Rotation is size-based: when
 * the current file exceeds [MAX_FILE_BYTES] it is renamed to `app.log.1` and
 * a fresh `app.log` is started. Only the current + previous file are kept.
 *
 * Safe to call before initialization — calls will be dropped until
 * [initialize] is called from [ua.com.programmer.pick.PickApplication].
 */
object FileLogger {

    private const val LOG_DIR = "logs"
    private const val CURRENT_FILE = "app.log"
    private const val PREVIOUS_FILE = "app.log.1"
    private const val MAX_FILE_BYTES = 1_000_000L // ~1 MB per file, ~2 MB total
    private const val TAG = "FileLogger"

    @Volatile
    private var logDir: File? = null

    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create log directory: ${dir.absolutePath}")
            return
        }
        logDir = dir
        log("I", TAG, "FileLogger initialized at ${dir.absolutePath}")
    }

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val dir = logDir ?: return
        val line = buildString {
            append(dateFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable))
            }
            append('\n')
        }
        synchronized(lock) {
            try {
                val current = File(dir, CURRENT_FILE)
                if (current.exists() && current.length() + line.length > MAX_FILE_BYTES) {
                    rotate(dir)
                }
                PrintWriter(FileWriter(current, true)).use { it.print(line) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write log line: ${e.message}")
            }
        }
    }

    private fun rotate(dir: File) {
        val current = File(dir, CURRENT_FILE)
        val previous = File(dir, PREVIOUS_FILE)
        if (previous.exists()) previous.delete()
        current.renameTo(previous)
    }

    /**
     * Returns the combined log contents (previous + current) as a single file
     * suitable for sharing. The returned file lives in a cache subdirectory
     * exposed via FileProvider. Caller is responsible for deleting it.
     *
     * Returns null if there are no logs or initialization failed.
     */
    fun exportToShareableFile(context: Context): File? {
        val dir = logDir ?: return null
        val exportDir = File(context.cacheDir, "log_export")
        if (!exportDir.exists() && !exportDir.mkdirs()) return null

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "pick_logs_$timestamp.txt")

        synchronized(lock) {
            try {
                PrintWriter(FileWriter(exportFile, false)).use { writer ->
                    val previous = File(dir, PREVIOUS_FILE)
                    if (previous.exists()) writer.print(previous.readText())
                    val current = File(dir, CURRENT_FILE)
                    if (current.exists()) writer.print(current.readText())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to export logs: ${e.message}")
                return null
            }
        }

        return if (exportFile.length() > 0) exportFile else null
    }

    fun clear() {
        val dir = logDir ?: return
        synchronized(lock) {
            File(dir, CURRENT_FILE).delete()
            File(dir, PREVIOUS_FILE).delete()
        }
    }
}
