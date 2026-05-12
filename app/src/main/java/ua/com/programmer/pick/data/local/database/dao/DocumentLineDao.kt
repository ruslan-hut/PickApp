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

    @Query("UPDATE document_lines SET notes = :notes, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineNotes(lineId: String, notes: String?)

    @Query("UPDATE document_lines SET batch_number = :batchNumber, expiration_date = :expirationDate, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineBatchInfo(lineId: String, batchNumber: String?, expirationDate: Long?)

    @Query("UPDATE document_lines SET location_id = :locationId, location_path = :locationPath, is_dirty = 1 WHERE id = :lineId")
    suspend fun updateLineLocation(lineId: String, locationId: String?, locationPath: String?)

    @Query("UPDATE document_lines SET is_dirty = 0 WHERE id = :lineId")
    suspend fun markLineAsSynced(lineId: String)

    @Query("UPDATE document_lines SET is_dirty = 0 WHERE document_id = :documentId")
    suspend fun markAllLinesAsSynced(documentId: String)

    @Query("DELETE FROM document_lines WHERE id = :id")
    suspend fun deleteLine(id: String)

    @Query("DELETE FROM document_lines WHERE document_id = :documentId")
    suspend fun deleteLinesByDocumentId(documentId: String)
}
