package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.DocumentType

interface DocumentRepository {

    fun getAllDocuments(): Flow<List<Document>>

    fun getDocumentsByType(type: DocumentType): Flow<List<Document>>

    fun getDocumentsByState(state: DocumentState): Flow<List<Document>>

    fun getDocumentsByTypeAndState(type: DocumentType, state: DocumentState): Flow<List<Document>>

    fun getDocumentsByAssignedUser(userId: String): Flow<List<Document>>

    fun observeDocument(documentId: String): Flow<Document?>

    fun getDocumentCountByType(type: DocumentType): Flow<Int>

    fun getDocumentCountByTypeAndState(type: DocumentType, state: DocumentState): Flow<Int>

    suspend fun getDocumentById(documentId: String): Document?

    suspend fun takeIntoWork(documentId: String, userId: String): Result<Document>

    suspend fun packageDocument(documentId: String): Result<Document>

    suspend fun completeDocument(documentId: String): Result<Document>

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

    suspend fun updateLine(lineId: String, actualQuantity: Double, notes: String?): Result<Unit>

    suspend fun incrementLineQuantity(lineId: String, delta: Double): Result<Unit>

    suspend fun updateLineCompleted(lineId: String, isCompleted: Boolean): Result<Unit>

    suspend fun updateLineBatchInfo(lineId: String, batchNumber: String?, expirationDate: Long?): Result<Unit>

    suspend fun updateLineLocation(lineId: String, locationId: String?, locationPath: String?): Result<Unit>

    suspend fun saveLine(line: DocumentLine): Result<Unit>

    suspend fun saveLines(lines: List<DocumentLine>): Result<Unit>

    suspend fun getDirtyLines(): List<DocumentLine>

    suspend fun markLineAsSynced(lineId: String)

    suspend fun markAllLinesAsSynced(documentId: String)
}
