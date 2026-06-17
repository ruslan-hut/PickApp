package ua.com.programmer.pick.data.remote.transport.demo

import com.google.gson.Gson
import com.google.gson.JsonElement
import ua.com.programmer.pick.data.remote.dto.BarcodeDto
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.dto.DocumentLineDto
import ua.com.programmer.pick.data.remote.dto.ProductDto
import ua.com.programmer.pick.data.remote.transport.DocumentLineUpdate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * In-memory fake server backing the offline demo session. Holds an authoritative
 * model of a handful of randomly-generated picking documents and answers the
 * same operations a real server would (lock, complete, line update, sync). It is
 * the single source of truth for the demo: [DemoTransport] re-serves this model
 * on every sync as a full set, so progress made by the user (lock state,
 * collected quantities, completion) is reflected consistently across refreshes.
 *
 * All identifiers use the same value for `id` and `external_id`, which makes the
 * orchestrator's Room↔ERP id translation a no-op.
 */
@Singleton
class DemoServer @Inject constructor(
    private val gson: Gson,
) {

    companion object {
        const val USER_ID = "demo-user"
        const val USER_NAME = "Demo"
        const val DOCUMENT_TYPE = "OUTGOING_SHIPMENT"
        const val DOCUMENT_TYPE_DESCRIPTION = "Відвантаження (демо)"
        private const val DOCUMENT_COUNT = 3
        private const val WAREHOUSE_NAME = "Основний склад"

        private val CLIENT_NAMES = listOf(
            "ТОВ «Світанок»", "Магазин №12", "ФОП Коваленко",
            "Супермаркет «Достаток»", "ТОВ «Аврора Трейд»", "Кав'ярня «Зерно»",
        )
        private val PRODUCT_NAMES = listOf(
            "Вода негазована 1.5л", "Сік яблучний 1л", "Печиво вівсяне 300г",
            "Кава мелена 250г", "Чай чорний 100п", "Цукор білий 1кг",
            "Олія соняшникова 1л", "Борошно пшеничне 2кг", "Молоко 2.5% 1л",
            "Шоколад чорний 90г", "Макарони 400г", "Рис довгозернистий 1кг",
            "Сіль кухонна 1кг", "Кетчуп томатний 300г", "Йогурт питний 300г",
        )
        private val UNITS = listOf("шт", "уп", "кг")
    }

    // docId -> document (lines live inside the DTO). LinkedHashMap keeps a stable
    // display order across syncs.
    private val documents = LinkedHashMap<String, DocumentDto>()

    /**
     * Generate the random document set the first time it's needed. Idempotent:
     * a re-auth within the same process (e.g. autoLogin after a foreground
     * resume) keeps the existing model, so collected quantities, lock state and
     * completion marks survive. A fresh set is produced once per process.
     */
    @Synchronized
    fun ensureSeeded() {
        if (documents.isNotEmpty()) return
        val rnd = Random(System.nanoTime())
        repeat(DOCUMENT_COUNT) { index ->
            val doc = generateDocument(index + 1, rnd)
            documents[doc.id] = doc
        }
    }

    @Synchronized
    fun documentsJson(): JsonElement = gson.toJsonTree(documents.values.toList())

    /** Distinct products referenced by the current document lines. */
    @Synchronized
    fun productsJson(): JsonElement {
        val products = documents.values
            .flatMap { it.lines ?: emptyList() }
            .associateBy { it.productId }
            .map { (_, line) ->
                ProductDto(
                    id = line.productId,
                    externalId = line.productId,
                    code = line.productCode ?: line.productId,
                    name = line.productName ?: "",
                    description = null,
                    unit = line.unit ?: "шт",
                    supportsBatches = false,
                    isActive = true,
                    barcodes = line.productCode?.let { listOf(BarcodeDto(barcode = it, isPrimary = true)) },
                    imageUrl = null,
                )
            }
        return gson.toJsonTree(products)
    }

    /** Lock for the collect stage: LOADED -> COLLECTING. Always succeeds in demo. */
    @Synchronized
    fun lock(documentId: String) {
        documents[documentId]?.let {
            documents[documentId] = it.copy(
                state = "COLLECTING",
                assignedUserId = USER_ID,
                takenAt = System.currentTimeMillis(),
            )
        }
    }

    /** Release the collect lock: COLLECTING -> LOADED so the doc can be retaken. */
    @Synchronized
    fun unlock(documentId: String) {
        documents[documentId]?.let {
            documents[documentId] = it.copy(state = "LOADED", assignedUserId = null, takenAt = null)
        }
    }

    /**
     * Complete the collect stage: -> COLLECTED. Returns the new state and version
     * so [DemoTransport] can shape the StageCompleteResult. The doc stays in the
     * model (re-served on the next refresh) so the user sees the finished result.
     */
    @Synchronized
    fun complete(documentId: String): CompleteResult? {
        val doc = documents[documentId] ?: return null
        val newVersion = doc.version + 1
        documents[documentId] = doc.copy(
            state = "COLLECTED",
            version = newVersion,
            completedAt = System.currentTimeMillis(),
            assignedUserId = USER_ID,
        )
        return CompleteResult(state = "COLLECTED", version = newVersion.toLong())
    }

    /** Apply collected quantities / notes / completion from a DOCUMENT_UPDATE. */
    @Synchronized
    fun applyUpdate(documentId: String, lines: List<DocumentLineUpdate>) {
        val doc = documents[documentId] ?: return
        val byNumber = lines.associateBy { it.lineNumber }
        val updatedLines = (doc.lines ?: emptyList()).map { line ->
            byNumber[line.lineNumber]?.let { u ->
                line.copy(
                    actualQuantity = u.actualQuantity,
                    isCompleted = u.isCompleted,
                    notes = u.notes ?: line.notes,
                    batchNumber = u.batchNumber ?: line.batchNumber,
                )
            } ?: line
        }
        documents[documentId] = doc.copy(
            lines = updatedLines,
            totalActual = updatedLines.sumOf { it.actualQuantity },
        )
    }

    data class CompleteResult(val state: String, val version: Long)

    private fun generateDocument(seq: Int, rnd: Random): DocumentDto {
        val id = "demo-doc-$seq"
        val now = System.currentTimeMillis()
        val lineCount = rnd.nextInt(3, 9)
        val productPool = PRODUCT_NAMES.shuffled(rnd)
        val lines = (1..lineCount).map { lineNumber ->
            val name = productPool[(lineNumber - 1) % productPool.size]
            val planned = rnd.nextInt(1, 21).toDouble()
            DocumentLineDto(
                id = "$id-line-$lineNumber",
                documentId = id,
                lineNumber = lineNumber,
                productId = "demo-prod-${name.hashCode().toUInt()}",
                productCode = randomBarcode(rnd),
                productName = name,
                unit = UNITS[rnd.nextInt(UNITS.size)],
                volume = 0,
                volumeUnit = null,
                plannedQuantity = planned,
                actualQuantity = 0.0,
                batchNumber = null,
                expirationDate = null,
                locationId = null,
                locationPath = null,
                notes = null,
                isCompleted = false,
                hasPhoto = false,
            )
        }
        return DocumentDto(
            id = id,
            externalId = id,
            type = DOCUMENT_TYPE,
            number = "ВН-%05d".format(rnd.nextInt(1, 99999)),
            date = now - rnd.nextLong(0, 5L * 24 * 3600 * 1000),
            state = "LOADED",
            clientId = null,
            clientName = CLIENT_NAMES[rnd.nextInt(CLIENT_NAMES.size)],
            warehouseId = null,
            warehouseName = WAREHOUSE_NAME,
            notes = null,
            totalPlanned = lines.sumOf { it.plannedQuantity },
            totalActual = 0.0,
            assignedUserId = null,
            takenAt = null,
            completedAt = null,
            lastModified = now,
            version = 1,
            lines = lines,
        )
    }

    private fun randomBarcode(rnd: Random): String =
        buildString { repeat(13) { append(rnd.nextInt(10)) } }
}
