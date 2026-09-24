package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineBarcodeDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toDomainList
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.data.mapper.toLineDomainList
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.TaskLineUpdate
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.model.LineBatchJson
import ua.com.programmer.pick.domain.model.normalizedTo
import ua.com.programmer.pick.domain.model.credit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentLineDao: DocumentLineDao,
    private val documentLineBarcodeDao: DocumentLineBarcodeDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : DocumentRepository {

    override fun getAllDocuments(): Flow<List<Document>> {
        return documentDao.getAllDocuments().map { it.toDomainList() }
    }

    override fun getDocumentsByType(type: String): Flow<List<Document>> {
        return documentDao.getDocumentsByType(type).map { it.toDomainList() }
    }

    override fun getDocumentsByState(state: DocumentState): Flow<List<Document>> {
        return documentDao.getDocumentsByState(state.name).map { it.toDomainList() }
    }

    override fun getDocumentsByTypeAndState(type: String, state: DocumentState): Flow<List<Document>> {
        return documentDao.getDocumentsByTypeAndState(type, state.name).map { it.toDomainList() }
    }

    override fun getDocumentsByAssignedUser(userId: String): Flow<List<Document>> {
        return documentDao.getDocumentsByAssignedUser(userId).map { it.toDomainList() }
    }

    override fun observeDocument(documentId: String): Flow<Document?> {
        return documentDao.observeDocumentById(documentId).map { it?.toDomain() }
    }

    override fun getDocumentCountByType(type: String): Flow<Int> {
        return documentDao.getDocumentCountByType(type)
    }

    override fun getDocumentCountByTypeAndState(type: String, state: DocumentState): Flow<Int> {
        return documentDao.getDocumentCountByTypeAndState(type, state.name)
    }

    override suspend fun getDocumentById(documentId: String): Document? = withContext(ioDispatcher) {
        documentDao.getDocumentById(documentId)?.toDomain()
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

    override suspend fun getLinesByBarcode(documentId: String, barcode: String): List<DocumentLine> =
        withContext(ioDispatcher) {
            documentLineBarcodeDao.getLinesByBarcode(documentId, barcode).toLineDomainList()
        }

    override suspend fun hasLineBarcodes(documentId: String): Boolean = withContext(ioDispatcher) {
        documentLineBarcodeDao.hasLineBarcodes(documentId)
    }

    override suspend fun updateLine(lineId: String, actualQuantity: Double, notes: String?): Result<Unit> = withContext(ioDispatcher) {
        try {
            val rows = documentLineDao.updateActualQuantity(lineId, actualQuantity)
            if (rows == 0) {
                return@withContext Result.Error(
                    StaleLineIdException(lineId),
                    "Line $lineId no longer exists in local DB"
                )
            }
            fitLineBatches(lineId)
            notes?.let { documentLineDao.updateLineNotes(lineId, it) }
            updateDocumentTotals(lineId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line")
        }
    }

    override suspend fun updateLineNote(lineId: String, notes: String?): Result<Unit> = withContext(ioDispatcher) {
        try {
            val rows = documentLineDao.updateLineNotes(lineId, notes)
            if (rows == 0) {
                return@withContext Result.Error(
                    StaleLineIdException(lineId),
                    "Line $lineId no longer exists in local DB"
                )
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line note")
        }
    }

    override suspend fun updateLinePhoto(lineId: String, photoPath: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val rows = documentLineDao.updateLinePhoto(lineId, photoPath)
            if (rows == 0) {
                return@withContext Result.Error(
                    StaleLineIdException(lineId),
                    "Line $lineId no longer exists in local DB"
                )
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to update line photo")
        }
    }

    override suspend fun incrementLineQuantity(lineId: String, delta: Double, batchId: String?): Result<Unit> = withContext(ioDispatcher) {
        try {
            val rows = documentLineDao.incrementActualQuantity(lineId, delta)
            if (rows == 0) {
                return@withContext Result.Error(
                    StaleLineIdException(lineId),
                    "Line $lineId no longer exists in local DB"
                )
            }
            fitLineBatches(lineId, creditBatchId = batchId, creditQty = delta)
            updateDocumentTotals(lineId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to increment line quantity")
        }
    }

    /**
     * Keeps the line's batch-label breakdown consistent with its quantity:
     * credits a batch-label scan when [creditBatchId] is set, and trims the
     * latest entries when the quantity went down (LineBatch.normalizedTo, the
     * server's own rule). A line with no breakdown and no credit is left null
     * — the PATCH then omits the field and the server keeps its value.
     */
    private suspend fun fitLineBatches(lineId: String, creditBatchId: String? = null, creditQty: Double = 0.0) {
        val line = documentLineDao.getLineById(lineId) ?: return
        val current = LineBatchJson.decode(line.batches)
        if (current == null && creditBatchId == null) return
        var next = current.orEmpty()
        if (creditBatchId != null && creditQty > 0.0) next = next.credit(creditBatchId, creditQty)
        next = next.normalizedTo(line.actualQuantity)
        if (next != current) documentLineDao.updateLineBatches(lineId, LineBatchJson.encode(next))
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

    override suspend fun applyServerLineUpdates(
        documentExternalId: String,
        updates: List<TaskLineUpdate>
    ): Int = withContext(ioDispatcher) {
        if (updates.isEmpty()) return@withContext 0
        // The task carries the ERP id; a device that fetched the document by
        // ObjectID hex stores that as the row id instead.
        val document = documentDao.getDocumentByExternalId(documentExternalId)
            ?: documentDao.getDocumentById(documentExternalId)
            ?: return@withContext 0

        var applied = 0
        updates.forEach { update ->
            val byKey = update.lineKey?.let {
                documentLineDao.updateActualByLineKey(
                    document.id, it, update.actualQuantity, update.isCompleted
                )
            } ?: 0
            applied += if (byKey > 0) {
                byKey
            } else {
                documentLineDao.updateActualByLineNumber(
                    document.id, update.lineNumber, update.actualQuantity, update.isCompleted
                )
            }
        }
        if (applied > 0) {
            // Recompute without touching is_dirty / last_modified: these are the
            // server's own numbers, and marking the document dirty would have
            // resyncDirtyDocuments push them back as worker edits.
            documentDao.recomputeTotalActualForDocs(listOf(document.id))
        }
        applied
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

// Signals that a line edit targeted an id that no longer exists locally — almost
// always because a resync (deleteLinesByDocumentId + insertLines) replaced the
// line set under the UI's feet. The VM uses this to distinguish "save lost the
// row" (must revert optimistic UI + surface to user) from a generic DB failure.
class StaleLineIdException(val lineId: String) :
    RuntimeException("Stale line id: $lineId")
