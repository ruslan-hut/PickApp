package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.DocumentEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity
import ua.com.programmer.pick.data.remote.dto.DocumentDto
import ua.com.programmer.pick.data.remote.dto.DocumentLineDto
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.domain.model.DocumentState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentMapper @Inject constructor() {

    fun toEntity(dto: DocumentDto): DocumentEntity {
        return DocumentEntity(
            id = dto.id,
            externalId = dto.externalId,
            type = dto.type,
            number = dto.number,
            date = dto.date,
            state = dto.state,
            clientId = dto.clientId,
            clientName = dto.clientName,
            warehouseId = dto.warehouseId,
            warehouseName = dto.warehouseName,
            notes = dto.notes,
            totalPlanned = dto.totalPlanned,
            totalActual = dto.totalActual,
            assignedUserId = dto.assignedUserId,
            assignedWorkerId = dto.assignedWorkerId,
            courierUserId = dto.courierUserId,
            takenAt = dto.takenAt,
            completedAt = dto.completedAt,
            deliveredAt = dto.deliveredAt,
            lastModified = dto.lastModified,
            version = dto.version,
            isDirty = false
        )
    }

    fun toLineEntity(dto: DocumentLineDto): DocumentLineEntity {
        return DocumentLineEntity(
            id = dto.id,
            documentId = dto.documentId,
            lineNumber = dto.lineNumber,
            productId = dto.productId,
            productCode = dto.productCode ?: "",
            productName = dto.productName ?: "",
            unit = dto.unit ?: "",
            volume = dto.volume,
            volumeUnit = dto.volumeUnit,
            plannedQuantity = dto.plannedQuantity,
            actualQuantity = dto.actualQuantity,
            batchNumber = dto.batchNumber,
            expirationDate = dto.expirationDate,
            locationId = dto.locationId,
            locationPath = dto.locationPath,
            notes = dto.notes,
            isCompleted = dto.isCompleted,
            isDirty = false,
            hasPhoto = dto.hasPhoto
            // photoPath / photoPending are device-local — never populated from the DTO.
        )
    }
}

// Extension functions for domain mapping
fun DocumentEntity.toDomain(): Document {
    return Document(
        id = id,
        externalId = externalId,
        type = type,
        number = number,
        date = date,
        state = DocumentState.fromString(state),
        clientId = clientId,
        clientName = clientName,
        warehouseId = warehouseId,
        warehouseName = warehouseName,
        notes = notes,
        totalPlanned = totalPlanned,
        totalActual = totalActual,
        assignedUserId = assignedUserId,
        assignedWorkerId = assignedWorkerId,
        courierUserId = courierUserId,
        takenAt = takenAt,
        completedAt = completedAt,
        deliveredAt = deliveredAt,
        lastModified = lastModified,
        version = version,
        isDirty = isDirty
    )
}

fun Document.toEntity(): DocumentEntity {
    return DocumentEntity(
        id = id,
        externalId = externalId,
        type = type,
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
        assignedWorkerId = assignedWorkerId,
        courierUserId = courierUserId,
        takenAt = takenAt,
        completedAt = completedAt,
        deliveredAt = deliveredAt,
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
        volume = volume,
        volumeUnit = volumeUnit,
        plannedQuantity = plannedQuantity,
        actualQuantity = actualQuantity,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        locationId = locationId,
        locationPath = locationPath,
        notes = notes,
        isCompleted = isCompleted,
        isDirty = isDirty,
        hasPhoto = hasPhoto,
        photoPath = photoPath,
        photoPending = photoPending
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
        volume = volume,
        volumeUnit = volumeUnit,
        plannedQuantity = plannedQuantity,
        actualQuantity = actualQuantity,
        batchNumber = batchNumber,
        expirationDate = expirationDate,
        locationId = locationId,
        locationPath = locationPath,
        notes = notes,
        isCompleted = isCompleted,
        isDirty = isDirty,
        hasPhoto = hasPhoto,
        photoPath = photoPath,
        photoPending = photoPending
    )
}

fun List<DocumentEntity>.toDomainList(): List<Document> = map { it.toDomain() }

fun List<DocumentLineEntity>.toLineDomainList(): List<DocumentLine> = map { it.toDomain() }
