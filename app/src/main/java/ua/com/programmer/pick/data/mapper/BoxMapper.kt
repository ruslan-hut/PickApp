package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.BoxEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentBoxEntity
import ua.com.programmer.pick.data.remote.dto.BoxDto
import ua.com.programmer.pick.data.remote.dto.DocumentBoxDto
import ua.com.programmer.pick.domain.model.Box
import ua.com.programmer.pick.domain.model.DocumentBox

fun BoxDto.toEntity(): BoxEntity {
    return BoxEntity(
        id = id,
        externalId = externalId,
        barcode = barcode,
        name = name,
        length = length,
        width = width,
        height = height,
        isActive = isActive
    )
}

fun BoxEntity.toDomain(): Box {
    return Box(
        id = id,
        externalId = externalId,
        barcode = barcode,
        name = name,
        length = length,
        width = width,
        height = height,
        isActive = isActive
    )
}

fun DocumentBoxDto.toEntity(): DocumentBoxEntity {
    return DocumentBoxEntity(
        id = id,
        documentId = documentId,
        boxId = boxId,
        barcode = barcode,
        weight = weight,
        status = status,
        collectedBy = collectedBy,
        collectedAt = collectedAt,
        pickedUpBy = pickedUpBy,
        pickedUpAt = pickedUpAt,
        deliveredBy = deliveredBy,
        deliveredAt = deliveredAt,
        lastModified = lastModified,
        version = version
    )
}

fun DocumentBoxEntity.toDomain(): DocumentBox {
    return DocumentBox(
        id = id,
        documentId = documentId,
        boxId = boxId,
        barcode = barcode,
        weight = weight,
        status = status,
        collectedBy = collectedBy,
        collectedAt = collectedAt,
        pickedUpBy = pickedUpBy,
        pickedUpAt = pickedUpAt,
        deliveredBy = deliveredBy,
        deliveredAt = deliveredAt,
        lastModified = lastModified,
        version = version
    )
}

fun List<BoxEntity>.toBoxDomainList(): List<Box> = map { it.toDomain() }

fun List<DocumentBoxEntity>.toDocumentBoxDomainList(): List<DocumentBox> = map { it.toDomain() }
