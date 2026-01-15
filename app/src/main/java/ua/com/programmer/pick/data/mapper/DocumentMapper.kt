package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.DocumentEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import ua.com.programmer.pick.domain.model.DocumentType

fun DocumentEntity.toDomain(): Document {
    return Document(
        id = id,
        externalId = externalId,
        type = try {
            DocumentType.valueOf(type)
        } catch (e: IllegalArgumentException) {
            DocumentType.INCOMING_RECEIPT
        },
        number = number,
        date = date,
        state = try {
            DocumentState.valueOf(state)
        } catch (e: IllegalArgumentException) {
            DocumentState.LOADED
        },
        clientId = clientId,
        clientName = clientName,
        warehouseId = warehouseId,
        warehouseName = warehouseName,
        notes = notes,
        totalPlanned = totalPlanned,
        totalActual = totalActual,
        assignedUserId = assignedUserId,
        takenAt = takenAt,
        completedAt = completedAt,
        lastModified = lastModified,
        version = version,
        isDirty = isDirty
    )
}

fun Document.toEntity(): DocumentEntity {
    return DocumentEntity(
        id = id,
        externalId = externalId,
        type = type.name,
        number = number,
        date = date,
        state = state.name,
        clientId = clientId,
        clientName = clientName,
        warehouseId = warehouseId,
        warehouseName = warehouseName,
        notes = notes,
        totalPlanned = totalPlanned,
        totalActual = totalActual,
        assignedUserId = assignedUserId,
        takenAt = takenAt,
        completedAt = completedAt,
        lastModified = lastModified,
        version = version,
        isDirty = isDirty
    )
}

fun DocumentLineEntity.toDomain(): DocumentLine {
    return DocumentLine(
        id = id,
        documentId = documentId,
        lineNumber = lineNumber,
        productId = productId,
        productCode = productCode,
        productName = productName,
        unit = unit,
        plannedQuantity = plannedQuantity,
        actualQuantity = actualQuantity,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        locationId = locationId,
        locationPath = locationPath,
        notes = notes,
        isCompleted = isCompleted,
        isDirty = isDirty
    )
}

fun DocumentLine.toEntity(): DocumentLineEntity {
    return DocumentLineEntity(
        id = id,
        documentId = documentId,
        lineNumber = lineNumber,
        productId = productId,
        productCode = productCode,
        productName = productName,
        unit = unit,
        plannedQuantity = plannedQuantity,
        actualQuantity = actualQuantity,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        locationId = locationId,
        locationPath = locationPath,
        notes = notes,
        isCompleted = isCompleted,
        isDirty = isDirty
    )
}

fun List<DocumentEntity>.toDomainList(): List<Document> = map { it.toDomain() }

fun List<DocumentLineEntity>.toLineDomainList(): List<DocumentLine> = map { it.toDomain() }
