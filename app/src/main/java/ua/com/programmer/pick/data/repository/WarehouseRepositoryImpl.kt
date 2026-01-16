package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toDomainList
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.data.mapper.toLocationDomainList
import ua.com.programmer.pick.data.mapper.toLocationEntityList
import ua.com.programmer.pick.domain.model.Warehouse
import ua.com.programmer.pick.domain.model.WarehouseLocation
import ua.com.programmer.pick.domain.repository.WarehouseRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarehouseRepositoryImpl @Inject constructor(
    private val warehouseDao: WarehouseDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : WarehouseRepository {

    override fun getAllActiveWarehouses(): Flow<List<Warehouse>> {
        return warehouseDao.getAllActiveWarehouses().map { entities ->
            entities.map { entity ->
                val locations = warehouseDao.getLocationsByWarehouseId(entity.id)
                entity.toDomain(locations)
            }
        }
    }

    override suspend fun getWarehouseById(id: String): Warehouse? = withContext(ioDispatcher) {
        val entity = warehouseDao.getWarehouseById(id) ?: return@withContext null
        val locations = warehouseDao.getLocationsByWarehouseId(id)
        entity.toDomain(locations)
    }

    override suspend fun getWarehouseByCode(code: String): Warehouse? = withContext(ioDispatcher) {
        val entity = warehouseDao.getWarehouseByCode(code) ?: return@withContext null
        val locations = warehouseDao.getLocationsByWarehouseId(entity.id)
        entity.toDomain(locations)
    }

    override suspend fun getLocationsByWarehouseId(warehouseId: String): List<WarehouseLocation> = withContext(ioDispatcher) {
        warehouseDao.getLocationsByWarehouseId(warehouseId).toLocationDomainList()
    }

    override suspend fun getLocationByBarcode(barcode: String): WarehouseLocation? = withContext(ioDispatcher) {
        warehouseDao.getLocationByBarcode(barcode)?.toDomain()
    }

    override suspend fun saveWarehouse(warehouse: Warehouse): Result<Unit> = withContext(ioDispatcher) {
        try {
            warehouseDao.insertWarehouse(warehouse.toEntity())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save warehouse")
        }
    }

    override suspend fun saveWarehouses(warehouses: List<Warehouse>): Result<Unit> = withContext(ioDispatcher) {
        try {
            warehouseDao.insertWarehouses(warehouses.map { it.toEntity() })
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save warehouses")
        }
    }

    override suspend fun saveWarehouseWithLocations(warehouse: Warehouse, locations: List<WarehouseLocation>): Result<Unit> = withContext(ioDispatcher) {
        try {
            warehouseDao.insertWarehouseWithLocations(
                warehouse.toEntity(),
                locations.toLocationEntityList()
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save warehouse with locations")
        }
    }

    override suspend fun deleteWarehouse(id: String) = withContext(ioDispatcher) {
        warehouseDao.deleteWarehouse(id)
    }

    override suspend fun deleteAllWarehouses() = withContext(ioDispatcher) {
        warehouseDao.deleteAllLocations()
        warehouseDao.deleteAllWarehouses()
    }

    override suspend fun syncWarehouses(): Result<Unit> = withContext(ioDispatcher) {
        // TODO: Implement sync with server
        Result.Success(Unit)
    }
}
