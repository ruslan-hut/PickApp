package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.TaskLineUpdate

interface DocumentRepository {

    fun getAllDocuments(): Flow<List<Document>>

    fun getDocumentsByType(type: String): Flow<List<Document>>

    fun getDocumentsByState(state: DocumentState): Flow<List<Document>>

    fun getDocumentsByTypeAndState(type: String, state: DocumentState): Flow<List<Document>>

    fun getDocumentsByAssignedUser(userId: String): Flow<List<Document>>

    fun observeDocument(documentId: String): Flow<Document?>

    fun getDocumentCountByType(type: String): Flow<Int>

    fun getDocumentCountByTypeAndState(type: String, state: DocumentState): Flow<Int>

    suspend fun getDocumentById(documentId: String): Document?

    suspend fun updateDocumentState(documentId: String, state: DocumentState): Result<Unit>

    suspend fun saveDocument(document: Document): Result<Unit>

    suspend fun saveDocuments(documents: List<Document>): Result<Unit>

    suspend fun getDirtyDocuments(): List<Document>

    suspend fun markDocumentAsSynced(documentId: String)

    // Line operations
    fun getLinesByDocumentId(documentId: String): Flow<List<DocumentLine>>

    fun observeLine(lineId: String): Flow<DocumentLine?>

    fun getIncompleteLines(documentId: String): Flow<List<DocumentLine>>

    fun getLineCount(documentId: String): Flow<Int>

    fun getCompletedLineCount(documentId: String): Flow<Int>

    suspend fun getLineById(lineId: String): DocumentLine?

    suspend fun getLineByProductCode(documentId: String, productCode: String): DocumentLine?

    suspend fun getLineByProductId(documentId: String, productId: String): DocumentLine?

    /**
     * All lines of the document carrying [barcode] among their ERP-supplied
     * per-line scan codes. A unique stamp code yields one line; a group-package
     * code yields every line packed inside it. Empty when the document has no
     * per-line codes at all — the caller then falls back to product lookup.
     */
    suspend fun getLinesByBarcode(documentId: String, barcode: String): List<DocumentLine>

    /**
     * True when the document's lines carry ERP-supplied scan codes. Such a
     * document must be scanned by line code only — falling back to the product
     * catalogue would close an arbitrary line.
     */
    suspend fun hasLineBarcodes(documentId: String): Boolean

    suspend fun updateLine(lineId: String, actualQuantity: Double, notes: String?): Result<Unit>

    suspend fun updateLineNote(lineId: String, notes: String?): Result<Unit>

    suspend fun updateLinePhoto(lineId: String, photoPath: String): Result<Unit>

    /**
     * Adds [delta] to the line's quantity. [batchId] set = the unit was
     * identified by a batch-label scan: it is also credited to that batch in
     * the line's breakdown (DocumentLine.batches).
     */
    suspend fun incrementLineQuantity(lineId: String, delta: Double, batchId: String? = null): Result<Unit>

    suspend fun updateLineCompleted(lineId: String, isCompleted: Boolean): Result<Unit>

    suspend fun updateLineBatchInfo(lineId: String, batchNumber: String?, expirationDate: Long?): Result<Unit>

    suspend fun updateLineLocation(lineId: String, locationId: String?, locationPath: String?): Result<Unit>

    suspend fun saveLine(line: DocumentLine): Result<Unit>

    suspend fun saveLines(lines: List<DocumentLine>): Result<Unit>

    suspend fun getDirtyLines(): List<DocumentLine>

    suspend fun markLineAsSynced(lineId: String)

    suspend fun markAllLinesAsSynced(documentId: String)

    /**
     * Writes the line values a guided task's last action produced straight into
     * the cached document, so the classic detail screen and the list progress
     * follow the task in real time without a sync round-trip.
     *
     * [documentExternalId] is the ERP id the task carries. Lines are matched by
     * `line_key` when the update has one, else by `line_number`. The values are
     * server-authored — the task engine already mirrored them into the document
     * — so `is_dirty` is deliberately left alone and no DOCUMENT_UPDATE is
     * emitted. Returns the number of lines actually written.
     */
    suspend fun applyServerLineUpdates(
        documentExternalId: String,
        updates: List<TaskLineUpdate>
    ): Int
}
