package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.DocumentBoxEntity

@Dao
interface DocumentBoxDao {

    @Query("SELECT * FROM document_boxes WHERE id = :id")
    suspend fun getDocumentBoxById(id: String): DocumentBoxEntity?

    @Query("SELECT * FROM document_boxes WHERE document_id = :documentId")
    fun getBoxesByDocumentId(documentId: String): Flow<List<DocumentBoxEntity>>

    @Query("SELECT * FROM document_boxes WHERE document_id = :documentId")
    suspend fun getBoxesByDocumentIdOnce(documentId: String): List<DocumentBoxEntity>

    @Query("SELECT * FROM document_boxes WHERE barcode = :barcode AND document_id = :documentId")
    suspend fun getBoxByBarcodeAndDocument(barcode: String, documentId: String): DocumentBoxEntity?

    @Query("SELECT COUNT(*) FROM document_boxes WHERE document_id = :documentId")
    fun getBoxCountByDocument(documentId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM document_boxes WHERE document_id = :documentId AND picked_up_at IS NOT NULL")
    fun getPickedUpCountByDocument(documentId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM document_boxes WHERE document_id = :documentId AND delivered_at IS NOT NULL")
    fun getDeliveredCountByDocument(documentId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentBox(documentBox: DocumentBoxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentBoxes(documentBoxes: List<DocumentBoxEntity>)

    @Query("DELETE FROM document_boxes WHERE id IN (:ids)")
    suspend fun deleteDocumentBoxesByIds(ids: List<String>)

    @Query("DELETE FROM document_boxes WHERE document_id = :documentId")
    suspend fun deleteBoxesByDocumentId(documentId: String)
}
