package ua.com.programmer.pick.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.local.database.dao.OutgoingOperationDao
import ua.com.programmer.pick.data.local.database.dao.ProductDao
import ua.com.programmer.pick.data.local.database.dao.ProductImageDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.dao.BoxDao
import ua.com.programmer.pick.data.local.database.dao.DocumentBoxDao
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.local.database.entity.BoxEntity
import ua.com.programmer.pick.data.local.database.entity.ClientEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentBoxEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentLineEntity
import ua.com.programmer.pick.data.local.database.entity.OutgoingOperationEntity
import ua.com.programmer.pick.data.local.database.entity.ProductBarcodeEntity
import ua.com.programmer.pick.data.local.database.entity.ProductEntity
import ua.com.programmer.pick.data.local.database.entity.ProductImageEntity
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
        ProductImageEntity::class,
        ClientEntity::class,
        WarehouseEntity::class,
        WarehouseLocationEntity::class,
        DocumentEntity::class,
        DocumentLineEntity::class,
        OutgoingOperationEntity::class,
        BoxEntity::class,
        DocumentBoxEntity::class
    ],
    version = 9,  // Version 9: Added boxes and document_boxes tables
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun productDao(): ProductDao
    abstract fun productImageDao(): ProductImageDao
    abstract fun clientDao(): ClientDao
    abstract fun warehouseDao(): WarehouseDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentLineDao(): DocumentLineDao
    abstract fun outgoingOperationDao(): OutgoingOperationDao
    abstract fun boxDao(): BoxDao
    abstract fun documentBoxDao(): DocumentBoxDao

    companion object {
        const val DATABASE_NAME = "pick_database"

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rename roles
                db.execSQL("UPDATE users SET role = 'COLLECTOR' WHERE role = 'WAREHOUSE_WORKER'")
                db.execSQL("UPDATE users SET role = 'COURIER' WHERE role = 'PICKER'")
                // Rename document states
                db.execSQL("UPDATE documents SET state = 'COLLECTING' WHERE state = 'IN_PROGRESS'")
                db.execSQL("UPDATE documents SET state = 'COLLECTED' WHERE state = 'COMPLETED'")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN warehouse_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE documents ADD COLUMN assigned_worker_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE documents ADD COLUMN courier_user_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE documents ADD COLUMN delivered_at INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS boxes (
                        id TEXT NOT NULL PRIMARY KEY,
                        external_id TEXT,
                        barcode TEXT NOT NULL,
                        name TEXT NOT NULL,
                        length INTEGER NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        is_active INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_boxes_barcode ON boxes (barcode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_boxes_external_id ON boxes (external_id)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS document_boxes (
                        id TEXT NOT NULL PRIMARY KEY,
                        document_id TEXT NOT NULL,
                        box_id TEXT NOT NULL,
                        barcode TEXT NOT NULL,
                        weight INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        collected_by TEXT,
                        collected_at INTEGER,
                        picked_up_by TEXT,
                        picked_up_at INTEGER,
                        delivered_by TEXT,
                        delivered_at INTEGER,
                        last_modified INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_document_id ON document_boxes (document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_barcode ON document_boxes (barcode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_box_id ON document_boxes (box_id)")
            }
        }
    }
}
