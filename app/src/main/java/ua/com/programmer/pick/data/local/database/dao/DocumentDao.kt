package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.DocumentEntity

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE external_id = :externalId LIMIT 1")
    suspend fun getDocumentByExternalId(externalId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocumentById(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents ORDER BY date DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE type = :type ORDER BY date DESC")
    fun getDocumentsByType(type: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE state = :state ORDER BY date DESC")
    fun getDocumentsByState(state: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE type = :type AND state = :state ORDER BY date DESC")
    fun getDocumentsByTypeAndState(type: String, state: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE assigned_user_id = :userId ORDER BY date DESC")
    fun getDocumentsByAssignedUser(userId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE is_dirty = 1")
    suspend fun getDirtyDocuments(): List<DocumentEntity>

    // Picks up documents whose own dirty flag may have been cleared (e.g. by a
    // server-pushed payload merge in applyDocumentSync) but that still have one
    // or more locally-dirty lines waiting to be sent. Without this, a long
    // offline session followed by a stage-complete or server resync can land
    // an authoritative server payload that wipes doc.is_dirty while
    // mergeDocumentLines preserves the dirty lines — and resyncDirtyDocuments
    // then never picks the doc up again, silently losing the worker's edits.
    @Query("""
        SELECT DISTINCT d.* FROM documents d
        INNER JOIN document_lines l ON l.document_id = d.id
        WHERE l.is_dirty = 1
    """)
    suspend fun getDocumentsWithDirtyLines(): List<DocumentEntity>

    @Query("SELECT COUNT(*) FROM documents WHERE type = :type")
    fun getDocumentCountByType(type: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents WHERE type = :type AND state = :state")
    fun getDocumentCountByTypeAndState(type: String, state: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocuments(documents: List<DocumentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: DocumentEntity)

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("UPDATE documents SET state = :state, is_dirty = 1, last_modified = :lastModified WHERE id = :documentId")
    suspend fun updateDocumentState(documentId: String, state: String, lastModified: Long)

    @Query("UPDATE documents SET state = :state, last_modified = :lastModified WHERE id = :documentId")
    suspend fun updateDocumentStateFromServer(documentId: String, state: String, lastModified: Long)

    @Query("UPDATE documents SET assigned_user_id = :userId, taken_at = :takenAt, state = :state, is_dirty = 1, last_modified = :lastModified WHERE id = :documentId")
    suspend fun takeDocumentIntoWork(documentId: String, userId: String, takenAt: Long, state: String, lastModified: Long)

    @Query("UPDATE documents SET completed_at = :completedAt, state = :state, is_dirty = 1, last_modified = :lastModified WHERE id = :documentId")
    suspend fun completeDocument(documentId: String, completedAt: Long, state: String, lastModified: Long)

    @Query("UPDATE documents SET total_actual = :totalActual, is_dirty = 1, last_modified = :lastModified WHERE id = :documentId")
    suspend fun updateTotalActual(documentId: String, totalActual: Double, lastModified: Long)

    @Query("UPDATE documents SET total_planned = :totalPlanned, last_modified = :lastModified WHERE id = :documentId")
    suspend fun updateTotalPlanned(documentId: String, totalPlanned: Double, lastModified: Long)

    @Query("UPDATE documents SET is_dirty = 0 WHERE id = :documentId")
    suspend fun markDocumentAsSynced(documentId: String)

    @Query("UPDATE documents SET is_dirty = 1, last_modified = :lastModified WHERE id = :documentId")
    suspend fun markDocumentDirty(documentId: String, lastModified: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET assigned_user_id = :userId, last_modified = :lastModified WHERE id = :documentId")
    suspend fun updateAssignedUser(documentId: String, userId: String, lastModified: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET assigned_user_id = NULL, taken_at = NULL, last_modified = :lastModified WHERE id = :documentId")
    suspend fun clearAssignedUser(documentId: String, lastModified: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET version = :version, is_dirty = 0, last_modified = :lastModified WHERE id = :documentId")
    suspend fun updateDocumentVersion(documentId: String, version: Int, lastModified: Long = System.currentTimeMillis())

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()

    @Query("DELETE FROM documents WHERE id NOT IN (:keepIds)")
    suspend fun deleteDocumentsNotIn(keepIds: List<String>)
}
