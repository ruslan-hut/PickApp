package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toDomainList
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.data.mapper.toLineDomainList
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.DocumentType
import ua.com.programmer.pick.domain.repository.DocumentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentLineDao: DocumentLineDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DocumentRepository {

    override fun getDocumentsByType(type: DocumentType): Flow<List<Document>> {
        return documentDao.getDocumentsByType(type.name).map { it.toDomainList() }
    }

    override fun getDocumentsByState(state: DocumentState): Flow<List<Document>> {
        return documentDao.getDocumentsByState(state.name).map { it.toDomainList() }
    }

    override fun getDocumentsByTypeAndState(type: DocumentType, state: DocumentState): Flow<List<Document>> {
        return documentDao.getDocumentsByTypeAndState(type.name, state.name).map { it.toDomainList() }
    }

    override fun getDocumentsByAssignedUser(userId: String): Flow<List<Document>> {
        return documentDao.getDocumentsByAssignedUser(userId).map { it.toDomainList() }
    }

    override fun observeDocument(documentId: String): Flow<Document?> {
        return documentDao.observeDocumentById(documentId).map { it?.toDomain() }
    }

    override fun getDocumentCountByType(type: DocumentType): Flow<Int> {
        return documentDao.getDocumentCountByType(type.name)
    }

    override fun getDocumentCountByTypeAndState(type: DocumentType, state: DocumentState): Flow<Int> {
        return documentDao.getDocumentCountByTypeAndState(type.name, state.name)
    }

    override suspend fun getDocumentById(documentId: String): Document? = withContext(ioDispatcher) {
        documentDao.getDocumentById(documentId)?.toDomain()
    }

    override suspend fun takeIntoWork(documentId: String, userId: String): Result<Document> = withContext(ioDispatcher) {
        try {
            val document = documentDao.getDocumentById(documentId)
                ?: return@withContext Result.Error(Exception("Document not found"), "Document not found")

            if (document.state != DocumentState.LOADED.name && document.state != DocumentState.COLLECTING.name) {
                return@withContext Result.Error(
                    Exception("Document cannot be taken into work"),
                    "Document is already in work or completed"
                )
            }

            val currentTime = System.currentTimeMillis()
            documentDao.takeDocumentIntoWork(
                documentId = documentId,
                userId = userId,
                takenAt = currentTime,
                state = DocumentState.COLLECTING.name,
                lastModified = currentTime
            )

            val updatedDocument = documentDao.getDocumentById(documentId)?.toDomain()
                ?: return@withContext Result.Error(Exception("Document not found after update"), "Document not found")

            Result.Success(updatedDocument)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to take document into work")
        }
    }

    override suspend fun packageDocument(documentId: String): Result<Document> = withContext(ioDispatcher) {
        try {
            val document = documentDao.getDocumentById(documentId)
                ?: return@withContext Result.Error(Exception("Document not found"), "Document not found")

            if (document.state != DocumentState.COLLECTING.name) {
                return@withContext Result.Error(
                    Exception("Document cannot be packaged"),
                    "Document must be in progress to package"
                )
            }

            val currentTime = System.currentTimeMillis()
            documentDao.updateDocumentState(documentId, DocumentState.PACKAGING.name, currentTime)

            val updatedDocument = documentDao.getDocumentById(documentId)?.toDomain()
                ?: return@withContext Result.Error(Exception("Document not found after update"), "Document not found")

            Result.Success(updatedDocument)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to package document")
        }
    }

    override suspend fun completeDocument(documentId: String): Result<Document> = withContext(ioDispatcher) {
        try {
            val document = documentDao.getDocumentById(documentId)
                ?: return@withContext Result.Error(Exception("Document not found"), "Document not found")

            if (document.state != DocumentState.PACKAGING.name) {
                return@withContext Result.Error(
                    Exception("Document cannot be completed"),
                    "Document must be in packaging state to complete"
                )
            }

            val currentTime = System.currentTimeMillis()
            documentDao.completeDocument(
                documentId = documentId,
                completedAt = currentTime,
                state = DocumentState.COLLECTED.name,
                lastModified = currentTime
            )

            val updatedDocument = documentDao.getDocumentById(documentId)?.toDomain()
                ?: return@withContext Result.Error(Exception("Document not found after update"), "Document not found")

            Result.Success(updatedDocument)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to complete document")
        }
    }

    override suspend fun updateDocumentState(documentId: String, state: DocumentState): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentDao.updateDocumentState(documentId, state.name, System.currentTimeMillis())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update document state")
        }
    }

    override suspend fun saveDocument(document: Document): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentDao.insertDocument(document.toEntity())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save document")
        }
    }

    override suspend fun saveDocuments(documents: List<Document>): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentDao.insertDocuments(documents.map { it.toEntity() })
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save documents")
        }
    }

    override suspend fun getDirtyDocuments(): List<Document> = withContext(ioDispatcher) {
        documentDao.getDirtyDocuments().toDomainList()
    }

    override suspend fun markDocumentAsSynced(documentId: String) = withContext(ioDispatcher) {
        documentDao.markDocumentAsSynced(documentId)
    }

    // Line operations

    override fun getLinesByDocumentId(documentId: String): Flow<List<DocumentLine>> {
        return documentLineDao.getLinesByDocumentId(documentId).map { it.toLineDomainList() }
    }

    override fun observeLine(lineId: String): Flow<DocumentLine?> {
        return documentLineDao.observeLineById(lineId).map { it?.toDomain() }
    }

    override fun getIncompleteLines(documentId: String): Flow<List<DocumentLine>> {
        return documentLineDao.getIncompleteLines(documentId).map { it.toLineDomainList() }
    }

    override fun getLineCount(documentId: String): Flow<Int> {
        return documentLineDao.getLineCount(documentId)
    }

    override fun getCompletedLineCount(documentId: String): Flow<Int> {
        return documentLineDao.getCompletedLineCount(documentId)
    }

    override suspend fun getLineById(lineId: String): DocumentLine? = withContext(ioDispatcher) {
        documentLineDao.getLineById(lineId)?.toDomain()
    }

    override suspend fun getLineByProductCode(documentId: String, productCode: String): DocumentLine? = withContext(ioDispatcher) {
        documentLineDao.getLineByProductCode(documentId, productCode)?.toDomain()
    }

    override suspend fun getLineByProductId(documentId: String, productId: String): DocumentLine? = withContext(ioDispatcher) {
        documentLineDao.getLineByProductId(documentId, productId)?.toDomain()
    }

    override suspend fun updateLine(lineId: String, actualQuantity: Double, notes: String?): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.updateActualQuantity(lineId, actualQuantity)
            notes?.let { documentLineDao.updateLineNotes(lineId, it) }
            updateDocumentTotals(lineId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line")
        }
    }

    override suspend fun incrementLineQuantity(lineId: String, delta: Double): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.incrementActualQuantity(lineId, delta)
            updateDocumentTotals(lineId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to increment line quantity")
        }
    }

    override suspend fun updateLineCompleted(lineId: String, isCompleted: Boolean): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.updateLineCompleted(lineId, isCompleted)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line completed status")
        }
    }

    override suspend fun updateLineBatchInfo(lineId: String, batchNumber: String?, expirationDate: Long?): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.updateLineBatchInfo(lineId, batchNumber, expirationDate)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line batch info")
        }
    }

    override suspend fun updateLineLocation(lineId: String, locationId: String?, locationPath: String?): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.updateLineLocation(lineId, locationId, locationPath)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line location")
        }
    }

    override suspend fun saveLine(line: DocumentLine): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.insertLine(line.toEntity())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save line")
        }
    }

    override suspend fun saveLines(lines: List<DocumentLine>): Result<Unit> = withContext(ioDispatcher) {
        try {
            documentLineDao.insertLines(lines.map { it.toEntity() })
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save lines")
        }
    }

    override suspend fun getDirtyLines(): List<DocumentLine> = withContext(ioDispatcher) {
        documentLineDao.getDirtyLines().toLineDomainList()
    }

    override suspend fun markLineAsSynced(lineId: String) = withContext(ioDispatcher) {
        documentLineDao.markLineAsSynced(lineId)
    }

    override suspend fun markAllLinesAsSynced(documentId: String) = withContext(ioDispatcher) {
        documentLineDao.markAllLinesAsSynced(documentId)
    }

    private suspend fun updateDocumentTotals(lineId: String) {
        val line = documentLineDao.getLineById(lineId) ?: return
        val now = System.currentTimeMillis()
        val totalActual = documentLineDao.getTotalActualQuantity(line.documentId) ?: 0.0
        documentDao.updateTotalActual(line.documentId, totalActual, now)
        val totalPlanned = documentLineDao.getTotalPlannedQuantity(line.documentId) ?: 0.0
        documentDao.updateTotalPlanned(line.documentId, totalPlanned, now)
    }
}
