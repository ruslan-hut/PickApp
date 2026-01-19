package ua.com.programmer.pick.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ua.com.programmer.pick.data.local.database.AppDatabase
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.local.database.dao.OutgoingOperationDao
import ua.com.programmer.pick.data.local.database.dao.ProductDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.local.database.entity.UserEntity
import java.security.MessageDigest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    insertDemoUser(db)
                    insertDemoDocuments(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // Ensure demo user exists (in case onCreate wasn't called)
                    val cursor = db.query("SELECT COUNT(*) FROM users WHERE login = 'demo'")
                    cursor.moveToFirst()
                    val count = cursor.getInt(0)
                    cursor.close()
                    if (count == 0) {
                        insertDemoUser(db)
                    }
                    // Ensure demo documents exist
                    val docCursor = db.query("SELECT COUNT(*) FROM documents")
                    docCursor.moveToFirst()
                    val docCount = docCursor.getInt(0)
                    docCursor.close()
                    if (docCount == 0) {
                        insertDemoDocuments(db)
                    }
                }

                private fun insertDemoUser(db: SupportSQLiteDatabase) {
                    val demoUser = createDemoUser()
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO users (id, login, name, password_hash, role, is_active, last_login_at, last_updated)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            demoUser.id,
                            demoUser.login,
                            demoUser.name,
                            demoUser.passwordHash,
                            demoUser.role,
                            if (demoUser.isActive) 1 else 0,
                            demoUser.lastLoginAt,
                            demoUser.lastUpdated
                        )
                    )
                }

                private fun insertDemoDocuments(db: SupportSQLiteDatabase) {
                    val currentTime = System.currentTimeMillis()
                    val dayInMs = 24 * 60 * 60 * 1000L

                    // Document 1: Outgoing Shipment - LOADED
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO documents
                        (id, external_id, type, number, date, state, client_id, client_name, warehouse_id, warehouse_name, notes, total_planned, total_actual, assigned_user_id, taken_at, completed_at, last_modified, version, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("doc_001", "EXT-001", "OUTGOING_SHIPMENT", "OUT-2024-001", currentTime, "LOADED", "client_001", "ACME Corporation", "wh_001", "Main Warehouse", "Urgent delivery", 150.0, 0.0, null, null, null, currentTime, 1, 0)
                    )

                    // Document 2: Outgoing Shipment - IN_PROGRESS
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO documents
                        (id, external_id, type, number, date, state, client_id, client_name, warehouse_id, warehouse_name, notes, total_planned, total_actual, assigned_user_id, taken_at, completed_at, last_modified, version, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("doc_002", "EXT-002", "OUTGOING_SHIPMENT", "OUT-2024-002", currentTime - dayInMs, "IN_PROGRESS", "client_002", "Global Tech Ltd", "wh_001", "Main Warehouse", null, 75.0, 30.0, "demo_user_001", currentTime - dayInMs, null, currentTime, 1, 0)
                    )

                    // Document 3: Outgoing Shipment - COMPLETED
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO documents
                        (id, external_id, type, number, date, state, client_id, client_name, warehouse_id, warehouse_name, notes, total_planned, total_actual, assigned_user_id, taken_at, completed_at, last_modified, version, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("doc_003", "EXT-003", "OUTGOING_SHIPMENT", "OUT-2024-003", currentTime - 2 * dayInMs, "COMPLETED", "client_003", "Local Store", "wh_001", "Main Warehouse", "Completed order", 50.0, 50.0, "demo_user_001", currentTime - 2 * dayInMs, currentTime - dayInMs, currentTime, 1, 0)
                    )

                    // Document 4: Incoming Receipt - LOADED
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO documents
                        (id, external_id, type, number, date, state, client_id, client_name, warehouse_id, warehouse_name, notes, total_planned, total_actual, assigned_user_id, taken_at, completed_at, last_modified, version, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("doc_004", "EXT-004", "INCOMING_RECEIPT", "IN-2024-001", currentTime, "LOADED", "client_004", "Supplier Inc", "wh_001", "Main Warehouse", "Expected delivery", 200.0, 0.0, null, null, null, currentTime, 1, 0)
                    )

                    // Document 5: Inventory - LOADED
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO documents
                        (id, external_id, type, number, date, state, client_id, client_name, warehouse_id, warehouse_name, notes, total_planned, total_actual, assigned_user_id, taken_at, completed_at, last_modified, version, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("doc_005", "EXT-005", "INVENTORY", "INV-2024-001", currentTime, "LOADED", null, null, "wh_001", "Main Warehouse", "Monthly inventory check", 100.0, 0.0, null, null, null, currentTime, 1, 0)
                    )

                    // Document Lines for doc_001
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_001_1", "doc_001", 1, "prod_001", "SKU-001", "Laptop Dell XPS 15", "pcs", 10.0, 0.0, null, null, "loc_001", "A-01-01", null, 0, 0)
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_001_2", "doc_001", 2, "prod_002", "SKU-002", "Wireless Mouse Logitech", "pcs", 50.0, 0.0, null, null, "loc_002", "A-01-02", null, 0, 0)
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_001_3", "doc_001", 3, "prod_003", "SKU-003", "USB-C Cable 2m", "pcs", 90.0, 0.0, null, null, "loc_003", "A-02-01", null, 0, 0)
                    )

                    // Document Lines for doc_002
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_002_1", "doc_002", 1, "prod_004", "SKU-004", "Monitor Samsung 27\"", "pcs", 25.0, 15.0, null, null, "loc_004", "B-01-01", null, 0, 0)
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_002_2", "doc_002", 2, "prod_005", "SKU-005", "Keyboard Mechanical", "pcs", 50.0, 15.0, null, null, "loc_005", "B-01-02", "Check stock", 0, 0)
                    )

                    // Document Lines for doc_003
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_003_1", "doc_003", 1, "prod_006", "SKU-006", "Webcam HD 1080p", "pcs", 30.0, 30.0, null, null, "loc_006", "C-01-01", null, 1, 0)
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_003_2", "doc_003", 2, "prod_007", "SKU-007", "Headphones Wireless", "pcs", 20.0, 20.0, null, null, "loc_007", "C-01-02", null, 1, 0)
                    )

                    // Document Lines for doc_004
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_004_1", "doc_004", 1, "prod_008", "SKU-008", "Power Bank 20000mAh", "pcs", 100.0, 0.0, "BATCH-2024-01", currentTime + 365 * dayInMs, "loc_008", "D-01-01", null, 0, 0)
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_004_2", "doc_004", 2, "prod_009", "SKU-009", "Phone Case Universal", "pcs", 100.0, 0.0, "BATCH-2024-02", currentTime + 365 * dayInMs, "loc_009", "D-01-02", null, 0, 0)
                    )

                    // Document Lines for doc_005 (Inventory)
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_005_1", "doc_005", 1, "prod_010", "SKU-010", "Tablet Stand", "pcs", 25.0, 0.0, null, null, "loc_010", "E-01-01", null, 0, 0)
                    )
                    db.execSQL(
                        """
                        INSERT OR REPLACE INTO document_lines
                        (id, document_id, line_number, product_id, product_code, product_name, unit, planned_quantity, actual_quantity, batch_number, expiration_date, location_id, location_path, notes, is_completed, is_dirty)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf("line_005_2", "doc_005", 2, "prod_011", "SKU-011", "Screen Protector", "pcs", 75.0, 0.0, null, null, "loc_011", "E-01-02", null, 0, 0)
                    )
                }
            })
            .build()
    }

    private fun createDemoUser(): UserEntity {
        // Hash "demo" password with "demo" login as salt
        val password = "demo"
        val login = "demo"
        val input = password + login
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val passwordHash = bytes.joinToString("") { "%02x".format(it) }

        return UserEntity(
            id = "demo_user_001",
            login = "demo",
            name = "Demo User",
            passwordHash = passwordHash,
            role = "ADMINISTRATOR",
            isActive = true,
            lastLoginAt = null,
            lastUpdated = System.currentTimeMillis()
        )
    }

    @Provides
    @Singleton
    fun provideUserDao(database: AppDatabase): UserDao = database.userDao()

    @Provides
    @Singleton
    fun provideSyncStateDao(database: AppDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    @Singleton
    fun provideProductDao(database: AppDatabase): ProductDao = database.productDao()

    @Provides
    @Singleton
    fun provideClientDao(database: AppDatabase): ClientDao = database.clientDao()

    @Provides
    @Singleton
    fun provideWarehouseDao(database: AppDatabase): WarehouseDao = database.warehouseDao()

    @Provides
    @Singleton
    fun provideDocumentDao(database: AppDatabase): DocumentDao = database.documentDao()

    @Provides
    @Singleton
    fun provideDocumentLineDao(database: AppDatabase): DocumentLineDao = database.documentLineDao()

    @Provides
    @Singleton
    fun provideOutgoingOperationDao(database: AppDatabase): OutgoingOperationDao = database.outgoingOperationDao()
}
