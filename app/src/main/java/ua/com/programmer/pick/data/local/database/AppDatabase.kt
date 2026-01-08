package ua.com.programmer.pick.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.ProductDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.local.database.entity.ClientEntity
import ua.com.programmer.pick.data.local.database.entity.ProductBarcodeEntity
import ua.com.programmer.pick.data.local.database.entity.ProductEntity
import ua.com.programmer.pick.data.local.database.entity.SyncStateEntity
import ua.com.programmer.pick.data.local.database.entity.UserEntity
import ua.com.programmer.pick.data.local.database.entity.WarehouseEntity
import ua.com.programmer.pick.data.local.database.entity.WarehouseLocationEntity

@Database(
    entities = [
        UserEntity::class,
        SyncStateEntity::class,
        ProductEntity::class,
        ProductBarcodeEntity::class,
        ClientEntity::class,
        WarehouseEntity::class,
        WarehouseLocationEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun productDao(): ProductDao
    abstract fun clientDao(): ClientDao
    abstract fun warehouseDao(): WarehouseDao

    companion object {
        const val DATABASE_NAME = "pick_database"
    }
}
