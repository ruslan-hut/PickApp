package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity

@Dao
interface DocumentLineDao {

    @Query("SELECT * FROM document_lines WHERE id = :id")
    suspend fun getLineById(id: String): DocumentLineEntity?

    @Query("SELECT * FROM document_lines WHERE id = :id")
    fun observeLineById(id: String): Flow<DocumentLineEntity?>

    // Lines are ordered by line_number — the ERP assigns it in the intended
    // sort order on document upload, so it is the authoritative display order.
    @Query("SELECT * FROM document_lines WHERE document_id = :documentId ORDER BY line_number ASC")
    fun getLinesByDocumentId(documentId: String): Flow<List<DocumentLineEntity>>

    @Query("SELECT * FROM document_lines WHERE document_id = :documentId ORDER BY line_number ASC")
    suspend fun getLinesByDocumentIdSync(documentId: String): List<DocumentLineEntity>

    @Query("SELECT * FROM document_lines WHERE document_id = :documentId AND product_code = :productCode LIMIT 1")
    suspend fun getLineByProductCode(documentId: String, productCode: String): DocumentLineEntity?

    @Query("SELECT * FROM document_lines WHERE document_id = :documentId AND product_id = :productId LIMIT 1")
    suspend fun getLineByProductId(documentId: String, productId: String): DocumentLineEntity?

    @Query("SELECT * FROM document_lines WHERE document_id = :documentId AND is_completed = 0 ORDER BY line_number ASC")
    fun getIncompleteLines(documentId: String): Flow<List<DocumentLineEntity>>

    @Query("SELECT * FROM document_lines WHERE is_dirty = 1")
    suspend fun getDirtyLines(): List<DocumentLineEntity>

    // Defense-in-depth scrub for the LOADED-actual-leak class of bug. Finds any
    // line on a LOADED document where the server (or a prior pre-guard sync)
    // left a non-zero actual_quantity or is_completed=true on a line that was
    // not edited locally. Worker-edited lines (is_dirty=1) are never touched —
    // they win the merge regardless of doc state and the user's in-flight work
    // must be preserved across app restarts.
    @Query("""
        SELECT * FROM document_lines
        WHERE is_dirty = 0
          AND (actual_quantity != 0 OR is_completed = 1)
          AND document_id IN (SELECT id FROM documents WHERE state = 'LOADED')
    """)
    suspend fun findLoadedDocStaleActuals(): List<DocumentLineEntity>

    @Query("""
        UPDATE document_lines
        SET actual_quantity = 0, is_completed = 0
        WHERE is_dirty = 0
          AND (actual_quantity != 0 OR is_completed = 1)
          AND document_id IN (SELECT id FROM documents WHERE state = 'LOADED')
    """)
    suspend fun scrubLoadedDocStaleActuals(): Int

    @Query("SELECT COUNT(*) FROM document_lines WHERE document_id = :documentId")
    fun getLineCount(documentId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM document_lines WHERE document_id = :documentId AND is_completed = 1")
    fun getCompletedLineCount(documentId: String): Flow<Int>

    @Query("SELECT SUM(actual_quantity) FROM document_lines WHERE document_id = :documentId")
    suspend fun getTotalActualQuantity(documentId: String): Double?

    @Query("SELECT SUM(planned_quantity) FROM document_lines WHERE document_id = :documentId")
    suspend fun getTotalPlannedQuantity(documentId: String): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLine(line: DocumentLineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<DocumentLineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLine(line: DocumentLineEntity)

    @Update
    suspend fun updateLine(line: DocumentLineEntity)

    // Returns the number of rows affected. Zero means the lineId did not match
    // any row (e.g. the UI is holding a stale id because a sync replaced the
    // line set underneath it). Callers MUST check this — Room does not raise an
    // error for a no-op UPDATE, and silently-dropped edits are exactly how the
    // "UI shows 484 but server stored 467" drift happens.
    @Query("UPDATE document_lines SET actual_quantity = :actualQuantity, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateActualQuantity(lineId: String, actualQuantity: Double): Int

    @Query("UPDATE document_lines SET actual_quantity = actual_quantity + :delta, is_dirty = 1 WHERE id = :lineId")
    suspend fun incrementActualQuantity(lineId: String, delta: Double): Int

    @Query("UPDATE document_lines SET is_completed = :isCompleted, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineCompleted(lineId: String, isCompleted: Boolean)

    // Returns rows affected (0 = stale lineId, see updateActualQuantity).
    @Query("UPDATE document_lines SET notes = :notes, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineNotes(lineId: String, notes: String?): Int

    // Guided-task line updates. The values are server-authored (the task engine
    // already mirrored the confirmed line into the document), so is_dirty stays
    // untouched — arming it would make resyncDirtyDocuments push the server's
    // own numbers back as worker edits. Never use updateActualQuantity /
    // updateLineCompleted for these. Returns rows affected; 0 means the key
    // matched no line.
    @Query("""
        UPDATE document_lines
        SET actual_quantity = :actualQuantity, is_completed = :isCompleted
        WHERE document_id = :documentId AND line_key = :lineKey
    """)
    suspend fun updateActualByLineKey(
        documentId: String,
        lineKey: String,
        actualQuantity: Double,
        isCompleted: Boolean
    ): Int

    @Query("""
        UPDATE document_lines
        SET actual_quantity = :actualQuantity, is_completed = :isCompleted
        WHERE document_id = :documentId AND line_number = :lineNumber
    """)
    suspend fun updateActualByLineNumber(
        documentId: String,
        lineNumber: Int,
        actualQuantity: Double,
        isCompleted: Boolean
    ): Int

    // Records a freshly captured photo's local cache path and marks it awaiting
    // upload. photo_path / photo_pending are device-local — not synced.
    @Query("UPDATE document_lines SET photo_path = :photoPath, photo_pending = 1 WHERE id = :lineId")
    suspend fun updateLinePhoto(lineId: String, photoPath: String): Int

    // Upload confirmed (HTTP 200): flip the server marker locally and clear the
    // pending flag so the reconnect drain skips it.
    @Query("UPDATE document_lines SET has_photo = 1, photo_pending = 0 WHERE id = :lineId")
    suspend fun markLinePhotoUploaded(lineId: String): Int

    // Lines holding a captured-but-unuploaded photo, drained on reconnect.
    @Query("SELECT * FROM document_lines WHERE photo_pending = 1 AND photo_path IS NOT NULL")
    suspend fun getLinesWithPendingPhotos(): List<DocumentLineEntity>

    // Stop retrying an upload (file gone, or rejected as too large) without
    // flipping has_photo.
    @Query("UPDATE document_lines SET photo_pending = 0 WHERE id = :lineId")
    suspend fun clearLinePhotoPending(lineId: String): Int

    @Query("UPDATE document_lines SET batch_number = :batchNumber, expiration_date = :expirationDate, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineBatchInfo(lineId: String, batchNumber: String?, expirationDate: Long?)

    @Query("UPDATE document_lines SET location_id = :locationId, location_path = :locationPath, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineLocation(lineId: String, locationId: String?, locationPath: String?)

    @Query("UPDATE document_lines SET is_dirty = 0 WHERE id = :lineId")
    suspend fun markLineAsSynced(lineId: String)

    // Drops every worker-owned edit on a document by zeroing actuals,
    // clearing is_completed/batch, and turning is_dirty off — but ONLY on
    // rows that are currently dirty (i.e. carried unsent worker input).
    // Used by the M5″ lock-loss recovery give-up branch: after three
    // failed silent-relock attempts the worker's edits have been
    // repudiated by the server, so the local state must match the
    // server's truth before the next fresh fetch lands. The is_dirty=0
    // condition guarantees we never wipe non-dirty rows (those already
    // reflect server state). Returns the number of rows zeroed so the
    // caller can journal a meaningful audit row.
    @Query("""
        UPDATE document_lines
        SET actual_quantity = 0,
            is_completed = 0,
            batch_number = '',
            is_dirty = 0
        WHERE document_id = :documentId AND is_dirty = 1
    """)
    suspend fun dropDirtyEdits(documentId: String): Int

    @Query("UPDATE document_lines SET is_dirty = 0 WHERE document_id = :documentId")
    suspend fun markAllLinesAsSynced(documentId: String)

    @Query("DELETE FROM document_lines WHERE id = :id")
    suspend fun deleteLine(id: String)

    @Query("DELETE FROM document_lines WHERE document_id = :documentId")
    suspend fun deleteLinesByDocumentId(documentId: String)
}
