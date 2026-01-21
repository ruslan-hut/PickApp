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
import ua.com.programmer.pick.data.local.database.dao.ProductImageDao
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
                    val currentTime = System.currentTimeMillis()
                    insertDemoUser(db)
                    insertDemoProducts(db, currentTime)
                    insertProductBarcodes(db)
                    insertDemoDocuments(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    val currentTime = System.currentTimeMillis()

                    // Ensure demo user exists (in case onCreate wasn't called)
                    val cursor = db.query("SELECT COUNT(*) FROM users WHERE login = 'demo'")
                    cursor.moveToFirst()
                    val count = cursor.getInt(0)
                    cursor.close()
                    if (count == 0) {
                        insertDemoUser(db)
                    }

                    // Ensure demo products exist
                    val prodCursor = db.query("SELECT COUNT(*) FROM products")
                    prodCursor.moveToFirst()
                    val prodCount = prodCursor.getInt(0)
                    prodCursor.close()
                    if (prodCount == 0) {
                        insertDemoProducts(db, currentTime)
                        insertProductBarcodes(db)
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

                    // Simple placeholder image (valid 48x48 blue PNG)
                    val img = "UklGRsAFAABXRUJQVlA4WAoAAAAQAAAAOAAAOAAAQUxQSIABAAABkHNrmyIp79fVu7i75nuI3Mk4hJbyC5zIIXJCd9fMJXOHkA1xd3fpqu9Fp6q+bLOImAA0gQrQbPDMdcfPXjuzf+OsYc0hYqNApzVvWfur5a3g8pUY9JRBI1R5od5lEun4RANTBo5BmaPEUnomDrwhLl2JO16Z3rNnmUpw0zNrxW5lGoeJI5m5YlkmwSWlZqJ/C0lQjFUa9CuKOAGDBWVziZvhadIfi8M3tUE2kwhpoFVdEIPtauYdYp/R7M4o2h1RF9HBTmiE1NZgh1yE2odZuhoxwtKtiP6GqpMRvczoSaKutmZm6Iq9iPxipfouIhFn1Qgr9kXtMsEMtxYRKGlVPWLlXLByJqroSqv9JAZyxZuo7iK+qKMaUHZ2cXDjTEwukVIO+WzhGtIKblSZPOHSwOFhlUU52iF1ietVBtW5BdI7bKJPFThPkFNkKL2mUM8RRR6gwHoGjdHAA3DILg4LP5DUWr6tbCkCk4JOk048Df/40riwN0Rgu651mxaCJlNWUDggxAMAADASAJ0BKjkAOQA+bS6SRyQiIaEoDACADYlsALyRFltkbm3v6Gtt9zz/od3kjeVP9DdgHUAdv4tisCDHVV4yTxufSH69fAN+sf/J9ar1jfth7FP6ksdazAHb3L22Pwa+Ic2fZrPNj3v8pnq3suYp2/+4jpDCNx7XY4Xfi2M1bRjRx9au0hxfM9bkujVHsA/Eqhaqyo1SuoX3jgAA/vfKoP/2h1+R9ZnQMmKu6Jl/nly2gN/5VsdemfJw0v+wjPLgyCBsPtsOl5FeHXmUKlNCkTO6hBjj9DzySMzhAPINWTnILdqfAvMRirX+0B/34/7pl0DVtm5BEJzg9jk1liL7zb+V5lJVb9yZKLLaWyuizlPD1mh7S3Oolu8ONXED5R4tWhBCalrq7JPXl0EteGyKl+vyT8qSCuJ54co/3DeIiLv47sY5LcH8uFbNQ8Z4Y2WOP0Us7lF/+MSQseDD/uc950SxQldb2UM1y6ei9xYGoGtCNh6bPTxtSEPgKvrNAQ7o9Q/h2iPiKayLLaDSOX7+TEYHUdBj60QeBQBoP/h4vpoh8fvwdDIEguTL+E6l7zAd+CpkEbC/uaDH0v81Bf84iSw00rofH3X75A7Slut9hNPmFKkVDsvkkmO78M4LZ3S6WZIRKfwACXt4Ipn3Y0SjHnIzaBXth5VEkORq3oCaZ4aT72PGk2dQ14xPs44kiPqRoR2C33x9xoqNmbhp9dWO2//dPWdjsDvkfAa7CUT34if4z+XeuaE3bSeMOjhtCqSjzJ2hAk133UloHdjBzmV4o4ZXvfUs8juPpzQD/T9gYZpNIPFmM8nrZqXkrLB9L+PVnEsLTWzHkXmuDHOWO5Q0jz6tDqOUJSMNXhW5iLGAI17Azp5k7ScW9wLqSG0fDnf+sswOKyL/wkaiWNtGXktXykxAFiX8del/m/09/1LkW6LncbunY36/4om9p7GM/sHggVBI67fOczrwdjS+Krjw6WHzcVz0oSoafhsBXldAD9w2dglEZmB0m1MxTljETzeevvqVeAL6JgGeeevCR9KaqPe87Ggs4pArCrg1gKFmvNswzfbqaVye/o9jnePzrwJaf23JaAEcGbesxY0egOVt55cRTtkge9wCPG9JPkz5+5FTAc2boXlkT6ns3tLJhgQQY2Ye+N51P1zbtgYt1fs4hR1WMn43MEs5Q8TR8l0m9wkM1Hl2jUqA5j+h4p4x8yBAw8WRg5HQA7EJ28g5md2CspMQul/yC1JjBP/+goCj84WxZZ0//0OL2A6UGs0kAVSIwo5gAABQU0FJTgAAADhCSU0D7QAAAAAAEACQAAAAAQABAJAAAAABAAE4QklNBCgAAAAAAAwAAAACP/AAAAAAAAA4QklNBEMAAAAAAA5QYmVXARAABgBQAAAAAA=="

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

                    // Products
                    insertDemoProducts(db, currentTime)

                    // Product Barcodes
                    insertProductBarcodes(db)

                    // Product Images
                    insertProductImages(db, img, img, img, img, img, img, img, img, img, img, img)
                }

                private fun insertDemoProducts(db: SupportSQLiteDatabase, currentTime: Long) {
                    val insertSql = """
                        INSERT OR REPLACE INTO products (id, code, name, description, unit, supports_batches, is_active, last_updated)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()

                    db.execSQL(insertSql, arrayOf("prod_001", "SKU-001", "Laptop Dell XPS 15", "15.6 inch, Intel i7, 16GB RAM", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_002", "SKU-002", "Wireless Mouse Logitech", "Bluetooth, ergonomic design", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_003", "SKU-003", "USB-C Cable 2m", "Fast charging, data transfer", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_004", "SKU-004", "Monitor Samsung 27\"", "4K UHD, IPS panel", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_005", "SKU-005", "Keyboard Mechanical", "RGB backlight, Cherry MX switches", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_006", "SKU-006", "Webcam HD 1080p", "Built-in microphone, autofocus", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_007", "SKU-007", "Headphones Wireless", "Active noise cancellation, 30h battery", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_008", "SKU-008", "Power Bank 20000mAh", "USB-C PD, fast charging", "pcs", 1, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_009", "SKU-009", "Phone Case Universal", "Shockproof, clear design", "pcs", 1, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_010", "SKU-010", "Tablet Stand", "Adjustable angle, aluminum", "pcs", 0, 1, currentTime))
                    db.execSQL(insertSql, arrayOf("prod_011", "SKU-011", "Screen Protector", "Tempered glass, 9H hardness", "pcs", 0, 1, currentTime))
                }

                private fun insertProductBarcodes(db: SupportSQLiteDatabase) {
                    val insertSql = """
                        INSERT OR REPLACE INTO product_barcodes (id, product_id, barcode, type, is_primary)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()

                    // EAN-13 barcodes for easy testing (can be generated as QR codes too)
                    db.execSQL(insertSql, arrayOf("bc_001", "prod_001", "4901234567001", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_002", "prod_002", "4901234567002", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_003", "prod_003", "4901234567003", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_004", "prod_004", "4901234567004", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_005", "prod_005", "4901234567005", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_006", "prod_006", "4901234567006", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_007", "prod_007", "4901234567007", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_008", "prod_008", "4901234567008", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_009", "prod_009", "4901234567009", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_010", "prod_010", "4901234567010", "EAN13", 1))
                    db.execSQL(insertSql, arrayOf("bc_011", "prod_011", "4901234567011", "EAN13", 1))

                    // Additional SKU-based barcodes (product codes as barcodes)
                    db.execSQL(insertSql, arrayOf("bc_sku_001", "prod_001", "SKU-001", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_002", "prod_002", "SKU-002", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_003", "prod_003", "SKU-003", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_004", "prod_004", "SKU-004", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_005", "prod_005", "SKU-005", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_006", "prod_006", "SKU-006", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_007", "prod_007", "SKU-007", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_008", "prod_008", "SKU-008", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_009", "prod_009", "SKU-009", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_010", "prod_010", "SKU-010", "CODE128", 0))
                    db.execSQL(insertSql, arrayOf("bc_sku_011", "prod_011", "SKU-011", "CODE128", 0))
                }
            })
            .build()
    }

    private fun insertProductImages(
        db: SupportSQLiteDatabase,
        imgLaptop: String,
        imgMouse: String,
        imgCable: String,
        imgMonitor: String,
        imgKeyboard: String,
        imgWebcam: String,
        imgHeadphones: String,
        imgPowerBank: String,
        imgPhoneCase: String,
        imgTabletStand: String,
        imgScreenProtector: String
    ) {
        val insertSql = """
            INSERT OR REPLACE INTO product_images (id, product_id, url, base64)
            VALUES (?, ?, ?, ?)
        """.trimIndent()

        // Insert product images - url is null, using base64 for demo
        db.execSQL(insertSql, arrayOf("img_001", "prod_001", null, imgLaptop))
        db.execSQL(insertSql, arrayOf("img_002", "prod_002", null, imgMouse))
        db.execSQL(insertSql, arrayOf("img_003", "prod_003", null, imgCable))
        db.execSQL(insertSql, arrayOf("img_004", "prod_004", null, imgMonitor))
        db.execSQL(insertSql, arrayOf("img_005", "prod_005", null, imgKeyboard))
        db.execSQL(insertSql, arrayOf("img_006", "prod_006", null, imgWebcam))
        db.execSQL(insertSql, arrayOf("img_007", "prod_007", null, imgHeadphones))
        db.execSQL(insertSql, arrayOf("img_008", "prod_008", null, imgPowerBank))
        db.execSQL(insertSql, arrayOf("img_009", "prod_009", null, imgPhoneCase))
        db.execSQL(insertSql, arrayOf("img_010", "prod_010", null, imgTabletStand))
        db.execSQL(insertSql, arrayOf("img_011", "prod_011", null, imgScreenProtector))
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
    fun provideProductImageDao(database: AppDatabase): ProductImageDao = database.productImageDao()

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
