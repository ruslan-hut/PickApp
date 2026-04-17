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

    // Sort parcels first (is_parcel DESC) so the Boxes tab can render
    // delivery places above packages without a client-side sort pass.
    @Query("SELECT * FROM document_boxes WHERE document_id = :documentId ORDER BY is_parcel DESC, packed_at ASC")
    fun getBoxesByDocumentId(documentId: String): Flow<List<DocumentBoxEntity>>

    @Query("SELECT * FROM document_boxes WHERE document_id = :documentId ORDER BY is_parcel DESC, packed_at ASC")
    suspend fun getBoxesByDocumentIdOnce(documentId: String): List<DocumentBoxEntity>

    @Query("SELECT COUNT(*) FROM document_boxes WHERE document_id = :documentId AND is_parcel = 1")
    fun getParcelCountByDocument(documentId: String): Flow<Int>

    @Query("DELETE FROM document_boxes WHERE id = :id")
    suspend fun deleteDocumentBoxById(id: String)

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
