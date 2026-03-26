package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.domain.model.Box
import ua.com.programmer.pick.domain.model.DocumentBox

interface BoxRepository {

    fun getAllActiveBoxes(): Flow<List<Box>>

    fun getBoxesByDocumentId(documentId: String): Flow<List<DocumentBox>>

    fun getBoxCountByDocument(documentId: String): Flow<Int>

    fun getPickedUpCountByDocument(documentId: String): Flow<Int>

    fun getDeliveredCountByDocument(documentId: String): Flow<Int>

    suspend fun getBoxByBarcode(barcode: String): Box?

    suspend fun getBoxesByDocumentIdOnce(documentId: String): List<DocumentBox>
}
