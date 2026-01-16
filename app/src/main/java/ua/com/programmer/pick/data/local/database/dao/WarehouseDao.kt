package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.WarehouseEntity
import ua.com.programmer.pick.data.local.database.entity.WarehouseLocationEntity

@Dao
interface WarehouseDao {

    @Query("SELECT * FROM warehouses WHERE id = :id")
    suspend fun getWarehouseById(id: String): WarehouseEntity?

    @Query("SELECT * FROM warehouses WHERE code = :code")
    suspend fun getWarehouseByCode(code: String): WarehouseEntity?

    @Query("SELECT * FROM warehouses WHERE is_active = 1 ORDER BY name")
    fun getAllActiveWarehouses(): Flow<List<WarehouseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWarehouse(warehouse: WarehouseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWarehouses(warehouses: List<WarehouseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWarehouse(warehouse: WarehouseEntity)

    @Query("DELETE FROM warehouses WHERE id = :id")
    suspend fun deleteWarehouse(id: String)

    @Query("DELETE FROM warehouses")
    suspend fun deleteAllWarehouses()

    // Location operations
    @Query("SELECT * FROM warehouse_locations WHERE warehouse_id = :warehouseId AND is_active = 1")
    suspend fun getLocationsByWarehouseId(warehouseId: String): List<WarehouseLocationEntity>

    @Query("SELECT * FROM warehouse_locations WHERE barcode = :barcode")
    suspend fun getLocationByBarcode(barcode: String): WarehouseLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocations(locations: List<WarehouseLocationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(location: WarehouseLocationEntity)

    @Query("DELETE FROM warehouse_locations WHERE warehouse_id = :warehouseId")
    suspend fun deleteLocationsByWarehouseId(warehouseId: String)

    @Query("DELETE FROM warehouse_locations")
    suspend fun deleteAllLocations()

    @Transaction
    suspend fun insertWarehouseWithLocations(
        warehouse: WarehouseEntity,
        locations: List<WarehouseLocationEntity>
    ) {
        insertWarehouse(warehouse)
        if (locations.isNotEmpty()) {
            insertLocations(locations)
        }
    }
}
