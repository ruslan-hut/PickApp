package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.WarehouseEntity
import ua.com.programmer.pick.data.local.database.entity.WarehouseLocationEntity
import ua.com.programmer.pick.data.remote.dto.WarehouseDto
import ua.com.programmer.pick.data.remote.dto.WarehouseLocationDto
import ua.com.programmer.pick.domain.model.Warehouse
import ua.com.programmer.pick.domain.model.WarehouseLocation
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarehouseMapper @Inject constructor() {

    fun toEntity(dto: WarehouseDto): WarehouseEntity {
        return WarehouseEntity(
            id = dto.id,
            code = dto.code,
            name = dto.name,
            isAddressed = dto.isAddressed,
            isActive = dto.isActive,
            lastUpdated = System.currentTimeMillis()
        )
    }

    fun toLocationEntity(dto: WarehouseLocationDto, warehouseId: String): WarehouseLocationEntity {
        return WarehouseLocationEntity(
            id = dto.id,
            warehouseId = warehouseId,
            row = dto.row,
            shelf = dto.shelf,
            barcode = dto.barcode,
            isActive = dto.isActive
        )
    }
}

// Extension functions for domain mapping
fun WarehouseEntity.toDomain(locations: List<WarehouseLocationEntity> = emptyList()): Warehouse {
    return Warehouse(
        id = id,
        code = code,
        name = name,
        isAddressed = isAddressed,
        isActive = isActive,
        locations = locations.map { it.toDomain() }
    )
}

fun Warehouse.toEntity(lastUpdated: Long = System.currentTimeMillis()): WarehouseEntity {
    return WarehouseEntity(
        id = id,
        code = code,
        name = name,
        isAddressed = isAddressed,
        isActive = isActive,
        lastUpdated = lastUpdated
    )
}

fun WarehouseLocationEntity.toDomain(): WarehouseLocation {
    return WarehouseLocation(
        id = id,
        warehouseId = warehouseId,
        row = row,
        shelf = shelf,
        barcode = barcode,
        isActive = isActive
    )
}

fun WarehouseLocation.toEntity(): WarehouseLocationEntity {
    return WarehouseLocationEntity(
        id = id,
        warehouseId = warehouseId,
        row = row,
        shelf = shelf,
        barcode = barcode,
        isActive = isActive
    )
}

fun List<WarehouseEntity>.toDomainList(): List<Warehouse> = map { it.toDomain() }

fun List<WarehouseLocationEntity>.toLocationDomainList(): List<WarehouseLocation> = map { it.toDomain() }

fun List<WarehouseLocation>.toLocationEntityList(): List<WarehouseLocationEntity> = map { it.toEntity() }
