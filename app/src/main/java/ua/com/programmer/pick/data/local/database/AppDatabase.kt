package ua.com.programmer.pick.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.DebugJournalDao
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineBarcodeDao
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
import ua.com.programmer.pick.data.local.database.entity.DebugJournalEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentBoxEntity
import ua.com.programmer.pick.data.local.database.entity.DocumentLineBarcodeEntity
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
        DocumentLineBarcodeEntity::class,
        OutgoingOperationEntity::class,
        BoxEntity::class,
        DocumentBoxEntity::class,
        DebugJournalEntity::class
    ],
    version = 18, // Version 18: documents.client_language
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
    abstract fun documentLineBarcodeDao(): DocumentLineBarcodeDao
    abstract fun outgoingOperationDao(): OutgoingOperationDao
    abstract fun boxDao(): BoxDao
    abstract fun documentBoxDao(): DocumentBoxDao
    abstract fun debugJournalDao(): DebugJournalDao

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

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add ERP external_id to products and users so the sync layer can
                // translate v2-format cross-references (DocumentLine.product_id,
                // Document.assigned_user_id, DocumentBox.collected_by, etc.)
                // back to local internal IDs. Backfilled by the next product/user
                // sync from the server.
                db.execSQL("ALTER TABLE products ADD COLUMN external_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_products_external_id ON products (external_id)")
                db.execSQL("ALTER TABLE users ADD COLUMN external_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_users_external_id ON users (external_id)")
            }
        }

        // Pack-stage boxing: boxes now carry `is_parcel` (ERP declares delivery
        // places vs packages). DocumentBox.collected_* fields are renamed to
        // packed_* to reflect that boxing happens during the PACK stage; the
        // initial status is `PACKED` instead of `COLLECTED`.
        // SQLite on minSdk 24 predates RENAME COLUMN, so document_boxes is
        // rebuilt via the canonical create-copy-drop-rename pattern.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // boxes: new column + index
                db.execSQL("ALTER TABLE boxes ADD COLUMN is_parcel INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_boxes_is_parcel ON boxes (is_parcel)")

                // document_boxes: rebuild table, migrating column renames + status value.
                db.execSQL("""
                    CREATE TABLE document_boxes_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        document_id TEXT NOT NULL,
                        box_id TEXT NOT NULL,
                        barcode TEXT NOT NULL,
                        is_parcel INTEGER NOT NULL DEFAULT 0,
                        weight INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        packed_by TEXT,
                        packed_at INTEGER,
                        picked_up_by TEXT,
                        picked_up_at INTEGER,
                        delivered_by TEXT,
                        delivered_at INTEGER,
                        last_modified INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO document_boxes_new (
                        id, document_id, box_id, barcode, is_parcel, weight, status,
                        packed_by, packed_at, picked_up_by, picked_up_at,
                        delivered_by, delivered_at, last_modified, version
                    )
                    SELECT
                        id, document_id, box_id, barcode, 0 AS is_parcel, weight,
                        CASE WHEN status = 'COLLECTED' THEN 'PACKED' ELSE status END AS status,
                        collected_by, collected_at, picked_up_by, picked_up_at,
                        delivered_by, delivered_at, last_modified, version
                    FROM document_boxes
                """.trimIndent())
                db.execSQL("DROP TABLE document_boxes")
                db.execSQL("ALTER TABLE document_boxes_new RENAME TO document_boxes")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_document_id ON document_boxes (document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_barcode ON document_boxes (barcode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_box_id ON document_boxes (box_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_is_parcel ON document_boxes (is_parcel)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add ERP external_id to clients and warehouses. The backend now
                // emits external_id on ClientSyncDto and WarehouseSyncDto; storing
                // it locally enables future translation of Document.client_id and
                // Document.warehouse_id (and User.warehouse_id) back to local row ids.
                // Backfilled by the next client/warehouse sync.
                db.execSQL("ALTER TABLE clients ADD COLUMN external_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clients_external_id ON clients (external_id)")
                db.execSQL("ALTER TABLE warehouses ADD COLUMN external_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_warehouses_external_id ON warehouses (external_id)")
            }
        }

        // Server-side boxes moved from a standalone collection into Document.Boxes
        // as an embedded array keyed by (document_id, box_number). The local cache
        // mirrors this: drop the single-row-PK id/last_modified/version columns and
        // rebuild around the (document_id, box_number) composite. Existing rows are
        // discarded — the next sync rebuilds from the authoritative server payload.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS document_boxes")
                db.execSQL("""
                    CREATE TABLE document_boxes (
                        document_id TEXT NOT NULL,
                        box_number INTEGER NOT NULL,
                        box_id TEXT NOT NULL,
                        barcode TEXT NOT NULL,
                        is_parcel INTEGER NOT NULL DEFAULT 0,
                        weight INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        packed_by TEXT,
                        packed_at INTEGER,
                        picked_up_by TEXT,
                        picked_up_at INTEGER,
                        delivered_by TEXT,
                        delivered_at INTEGER,
                        PRIMARY KEY (document_id, box_number),
                        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_document_id ON document_boxes (document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_barcode ON document_boxes (barcode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_box_id ON document_boxes (box_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_boxes_is_parcel ON document_boxes (is_parcel)")
            }
        }

        // Per-item volume (e.g. 10) + its unit (e.g. "ml") arrive on each
        // document line from the ERP and drive line ordering on the device.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_lines ADD COLUMN volume INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE document_lines ADD COLUMN volume_unit TEXT DEFAULT NULL")
            }
        }

        // Per-line photo: server-set has_photo marker + device-local photo_path
        // cache and photo_pending upload flag (the latter two are never synced).
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE document_lines ADD COLUMN has_photo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE document_lines ADD COLUMN photo_path TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE document_lines ADD COLUMN photo_pending INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Per-line scan codes owned by the ERP: a unique stamp code plus, for
        // e-excise documents, the shared code of the group package the item
        // travels in. Rebuilt from the server payload on the next document sync.
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS document_line_barcodes (
                        line_id TEXT NOT NULL,
                        barcode TEXT NOT NULL,
                        PRIMARY KEY (line_id, barcode),
                        FOREIGN KEY (line_id) REFERENCES document_lines(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_line_barcodes_barcode ON document_line_barcodes (barcode)")
                db.execSQL("ALTER TABLE document_lines ADD COLUMN mark_code TEXT DEFAULT NULL")
            }
        }

        // Client's preferred communication language, an ERP-owned display label
        // carried on the document header. Backfilled from the server on the next
        // document sync, so an empty column on existing rows is fine.
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN client_language TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS debug_journal_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        tenant_id TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        user_id TEXT,
                        document_id TEXT,
                        stage TEXT,
                        event_type TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        message TEXT NOT NULL,
                        payload_json TEXT,
                        created_at INTEGER NOT NULL,
                        uploaded INTEGER NOT NULL DEFAULT 0,
                        upload_attempts INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_journal_events_document_id ON debug_journal_events (document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_journal_events_created_at ON debug_journal_events (created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_journal_events_uploaded ON debug_journal_events (uploaded)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_debug_journal_events_tenant_id ON debug_journal_events (tenant_id)")
            }
        }
    }
}
