package ua.com.programmer.pick.core.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the durable on-device cache of per-line photos under
 * `filesDir/line_photos/`. Files survive navigation/restart and are pruned when
 * their line is purged.
 */
@Singleton
class LinePhotoStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dir: File by lazy {
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }
    }

    /**
     * Write [bytes] as the photo for [lineId] and return the absolute path. The
     * filename carries a timestamp so a re-capture lands on a fresh path and
     * never collides with a stale image cache; the caller deletes the old path.
     */
    fun write(lineId: String, bytes: ByteArray): String {
        val safeId = lineId.replace(UNSAFE_CHARS, "_")
        val file = File(dir, "${safeId}_${System.currentTimeMillis()}.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /**
     * Newest cached photo for [lineId], or null. Lets the UI recover a thumbnail
     * after a complete-set sync deleted and re-created the line row (dropping the
     * device-local photo_path column) while the file itself survived on disk.
     */
    fun findFor(lineId: String): String? {
        val prefix = lineId.replace(UNSAFE_CHARS, "_") + "_"
        return dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".jpg") }
            ?.maxByOrNull { it.name }
            ?.absolutePath
    }

    fun read(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return runCatching { File(path).readBytes() }.getOrNull()
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    /**
     * Delete every cached photo for [lineId] except [keepPath]. Keyed on lineId
     * (not a single known path) so a retake reliably removes the prior file even
     * when the photo_path column was dropped by a sync — leaving exactly one
     * file per line, always the latest capture.
     */
    fun deleteOthers(lineId: String, keepPath: String) {
        val prefix = lineId.replace(UNSAFE_CHARS, "_") + "_"
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".jpg") }
            ?.filter { it.absolutePath != keepPath }
            ?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val DIR_NAME = "line_photos"
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
