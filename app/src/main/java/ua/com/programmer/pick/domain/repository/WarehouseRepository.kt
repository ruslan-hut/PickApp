package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.Warehouse
import ua.com.programmer.pick.domain.model.WarehouseLocation

interface WarehouseRepository {

    fun getAllActiveWarehouses(): Flow<List<Warehouse>>

    suspend fun getWarehouseById(id: String): Warehouse?

    suspend fun getWarehouseByCode(code: String): Warehouse?

    suspend fun getLocationsByWarehouseId(warehouseId: String): List<WarehouseLocation>

    suspend fun getLocationByBarcode(barcode: String): WarehouseLocation?

    suspend fun saveWarehouse(warehouse: Warehouse): Result<Unit>

    suspend fun saveWarehouses(warehouses: List<Warehouse>): Result<Unit>

    suspend fun saveWarehouseWithLocations(warehouse: Warehouse, locations: List<WarehouseLocation>): Result<Unit>

    suspend fun deleteWarehouse(id: String)

    suspend fun deleteAllWarehouses()
}
