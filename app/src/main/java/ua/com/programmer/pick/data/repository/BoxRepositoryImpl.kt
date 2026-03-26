package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.data.local.database.dao.BoxDao
import ua.com.programmer.pick.data.local.database.dao.DocumentBoxDao
import ua.com.programmer.pick.data.mapper.toBoxDomainList
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toDocumentBoxDomainList
import ua.com.programmer.pick.domain.model.Box
import ua.com.programmer.pick.domain.model.DocumentBox
import ua.com.programmer.pick.domain.repository.BoxRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BoxRepositoryImpl @Inject constructor(
    private val boxDao: BoxDao,
    private val documentBoxDao: DocumentBoxDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BoxRepository {

    override fun getAllActiveBoxes(): Flow<List<Box>> {
        return boxDao.getAllActiveBoxes().map { it.toBoxDomainList() }
    }

    override fun getBoxesByDocumentId(documentId: String): Flow<List<DocumentBox>> {
        return documentBoxDao.getBoxesByDocumentId(documentId).map { it.toDocumentBoxDomainList() }
    }

    override fun getBoxCountByDocument(documentId: String): Flow<Int> {
        return documentBoxDao.getBoxCountByDocument(documentId)
    }

    override fun getPickedUpCountByDocument(documentId: String): Flow<Int> {
        return documentBoxDao.getPickedUpCountByDocument(documentId)
    }

    override fun getDeliveredCountByDocument(documentId: String): Flow<Int> {
        return documentBoxDao.getDeliveredCountByDocument(documentId)
    }

    override suspend fun getBoxByBarcode(barcode: String): Box? = withContext(ioDispatcher) {
        boxDao.getBoxByBarcode(barcode)?.toDomain()
    }

    override suspend fun getBoxesByDocumentIdOnce(documentId: String): List<DocumentBox> = withContext(ioDispatcher) {
        documentBoxDao.getBoxesByDocumentIdOnce(documentId).toDocumentBoxDomainList()
    }
}
