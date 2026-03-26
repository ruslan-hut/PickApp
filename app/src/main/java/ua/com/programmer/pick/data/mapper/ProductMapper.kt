package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.ProductBarcodeEntity
import ua.com.programmer.pick.data.local.database.entity.ProductEntity
import ua.com.programmer.pick.data.local.database.entity.ProductImageEntity
import ua.com.programmer.pick.data.remote.dto.ProductDto
import ua.com.programmer.pick.domain.model.Barcode
import ua.com.programmer.pick.domain.model.BarcodeType
import ua.com.programmer.pick.domain.model.Product
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductMapper @Inject constructor() {

    fun toEntity(dto: ProductDto): ProductEntity {
        return ProductEntity(
            id = dto.id,
            code = dto.code,
            name = dto.name,
            description = dto.description,
            unit = dto.unit,
            supportsBatches = dto.supportsBatches,
            isActive = dto.isActive,
            lastUpdated = System.currentTimeMillis()
        )
    }

    fun toBarcodeEntityList(dto: ProductDto): List<ProductBarcodeEntity> {
        return dto.barcodes?.map { barcodeDto ->
            ProductBarcodeEntity(
                id = "${dto.id}_${barcodeDto.barcode}",
                productId = dto.id,
                barcode = barcodeDto.barcode,
                type = barcodeDto.type ?: "UNKNOWN",
                isPrimary = barcodeDto.isPrimary
            )
        } ?: emptyList()
    }

    fun toImageEntity(dto: ProductDto): ProductImageEntity? {
        val imageUrl = dto.imageUrl ?: return null
        return ProductImageEntity(
            id = UUID.randomUUID().toString(),
            productId = dto.id,
            url = imageUrl,
            base64 = null
        )
    }
}

// Extension functions for domain mapping
fun ProductEntity.toDomain(barcodes: List<ProductBarcodeEntity> = emptyList()): Product {
    return Product(
        id = id,
        code = code,
        name = name,
        description = description,
        unit = unit,
        barcodes = barcodes.map { it.toDomain() },
        supportsBatches = supportsBatches,
        isActive = isActive
    )
}

fun Product.toEntity(lastUpdated: Long = System.currentTimeMillis()): ProductEntity {
    return ProductEntity(
        id = id,
        code = code,
        name = name,
        description = description,
        unit = unit,
        supportsBatches = supportsBatches,
        isActive = isActive,
        lastUpdated = lastUpdated
    )
}

fun ProductBarcodeEntity.toDomain(): Barcode {
    return Barcode(
        id = id,
        productId = productId,
        barcode = barcode,
        type = try {
            BarcodeType.valueOf(type)
        } catch (e: IllegalArgumentException) {
            BarcodeType.UNKNOWN
        },
        isPrimary = isPrimary
    )
}

fun Barcode.toEntity(): ProductBarcodeEntity {
    return ProductBarcodeEntity(
        id = id,
        productId = productId,
        barcode = barcode,
        type = type.name,
        isPrimary = isPrimary
    )
}

fun List<ProductEntity>.toDomainList(): List<Product> = map { it.toDomain() }

fun List<ProductBarcodeEntity>.toBarcodeDomainList(): List<Barcode> = map { it.toDomain() }

fun List<Barcode>.toBarcodeEntityList(): List<ProductBarcodeEntity> = map { it.toEntity() }
