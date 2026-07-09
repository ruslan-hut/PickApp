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
        private const val DOCUMENT_COUNT = 3
        private const val WAREHOUSE_NAME = "Основний склад"

        /** Marks in one group package, and how many packages a demo doc holds. */
        private const val MARKS_PER_PACKAGE = 4
        private const val PACKAGES_PER_DOCUMENT = 3
        /** Emitter prefix of a Ukrainian e-excise stamp identifier (3 chars). */
        private const val STAMP_EMITTER = "UA1"
        private val EXCISE_PRODUCT_NAMES = listOf(
            "Горілка «Хлібний Дар» 0.5л",
            "Коньяк «Таврія» 0.5л",
            "Вино «Колоніст» червоне 0.75л",
        )

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
     * Generate the random document set the first time it's needed. Seeds
     * [DOCUMENT_COUNT] documents of every [DemoVariant], so the home screen
     * offers both modes. Idempotent: a re-auth within the same process (e.g.
     * autoLogin after a foreground resume) keeps the existing model, so
     * collected quantities, lock state and completion marks survive.
     */
    @Synchronized
    fun ensureSeeded() {
        if (documents.isNotEmpty()) return
        val rnd = Random(System.nanoTime())
        repeat(DOCUMENT_COUNT) { index ->
            val doc = generateDocument(index + 1, rnd)
            documents[doc.id] = doc
        }
        repeat(DOCUMENT_COUNT) { index ->
            val doc = generateExciseDocument(index + 1, rnd)
            documents[doc.id] = doc
        }
    }

    /**
     * The current model, optionally narrowed to one document type. Mirrors the
     * real server, which filters DOCUMENT_LIST_REFRESH by the type the worker
     * picked on the home screen.
     */
    @Synchronized
    fun documentsJson(documentType: String? = null): JsonElement {
        val visible = documents.values.filter { documentType == null || it.type == documentType }
        return gson.toJsonTree(visible)
    }

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
            type = DemoVariant.SHIPMENT.documentType,
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

    /**
     * An e-excise document: one product, one line per stamped bottle. Lines are
     * grouped into packages of [MARKS_PER_PACKAGE]; every line of a package
     * repeats the package code as its second barcode, so scanning that code
     * closes the whole package at once. `barcodes[0]` is the bottle's unique
     * stamp identifier and must stay unique within the document.
     */
    private fun generateExciseDocument(seq: Int, rnd: Random): DocumentDto {
        val id = "demo-excise-$seq"
        val now = System.currentTimeMillis()
        val name = EXCISE_PRODUCT_NAMES[(seq - 1) % EXCISE_PRODUCT_NAMES.size]
        val productId = "demo-prod-${name.hashCode().toUInt()}"
        val productCode = randomBarcode(rnd)

        var lineNumber = 0
        val lines = (1..PACKAGES_PER_DOCUMENT).flatMap { pkg ->
            val packageCode = "PKG-%04d-%02d".format(rnd.nextInt(1, 9999), pkg)
            (1..MARKS_PER_PACKAGE).map {
                lineNumber += 1
                DocumentLineDto(
                    id = "$id-line-$lineNumber",
                    documentId = id,
                    lineNumber = lineNumber,
                    productId = productId,
                    productCode = productCode,
                    productName = name,
                    unit = "шт",
                    volume = 0,
                    volumeUnit = null,
                    plannedQuantity = 1.0,
                    actualQuantity = 0.0,
                    batchNumber = null,
                    expirationDate = null,
                    locationId = null,
                    locationPath = null,
                    notes = null,
                    isCompleted = false,
                    hasPhoto = false,
                    barcodes = listOf(stampCode(rnd), packageCode),
                )
            }
        }
        return DocumentDto(
            id = id,
            externalId = id,
            type = DemoVariant.EXCISE.documentType,
            number = "ЕА-%05d".format(rnd.nextInt(1, 99999)),
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

    /** 3-char emitter code + 9-digit serial, per the КМУ №890 identifier layout. */
    private fun stampCode(rnd: Random): String =
        STAMP_EMITTER + buildString { repeat(9) { append(rnd.nextInt(10)) } }

    private fun randomBarcode(rnd: Random): String =
        buildString { repeat(13) { append(rnd.nextInt(10)) } }
}
