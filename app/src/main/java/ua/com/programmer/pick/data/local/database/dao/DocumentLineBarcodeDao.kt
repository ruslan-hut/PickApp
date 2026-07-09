package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ua.com.programmer.pick.data.local.database.entity.DocumentLineBarcodeEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity

@Dao
interface DocumentLineBarcodeDao {

    /**
     * Every line of [documentId] carrying [barcode] — deliberately not `LIMIT 1`.
     * A unique stamp code resolves to one line; a group-package code resolves to
     * all the lines packed inside it, and the caller closes them together.
     */
    @Query(
        """
        SELECT l.* FROM document_lines l
        INNER JOIN document_line_barcodes b ON b.line_id = l.id
        WHERE l.document_id = :documentId AND b.barcode = :barcode
        ORDER BY l.line_number ASC
        """
    )
    suspend fun getLinesByBarcode(documentId: String, barcode: String): List<DocumentLineEntity>

    /**
     * True when the ERP supplied per-line codes for this document. Such a
     * document is scanned by line code only — resolving a scan through the
     * product catalogue would pick an arbitrary line, since every line may
     * share one product.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM document_line_barcodes
            WHERE line_id IN (SELECT id FROM document_lines WHERE document_id = :documentId)
        )
        """
    )
    suspend fun hasLineBarcodes(documentId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(barcodes: List<DocumentLineBarcodeEntity>)

    @Query(
        """
        DELETE FROM document_line_barcodes
        WHERE line_id IN (SELECT id FROM document_lines WHERE document_id = :documentId)
        """
    )
    suspend fun deleteByDocumentId(documentId: String)
}
