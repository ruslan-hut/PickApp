package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.ProductBarcodeEntity
import ua.com.programmer.pick.data.local.database.entity.ProductEntity

@Dao
interface ProductDao {

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: String): ProductEntity?

    /**
     * Batch-fetch products by ERP external_id. Used by SyncOrchestrator to
     * translate v2-format DocumentLine.product_id values back to local
     * internal IDs before insert.
     */
    @Query("SELECT * FROM products WHERE external_id IN (:externalIds)")
    suspend fun findByExternalIds(externalIds: List<String>): List<ProductEntity>

    @Query("SELECT * FROM products WHERE code = :code")
    suspend fun getProductByCode(code: String): ProductEntity?

    @Query("SELECT * FROM products WHERE is_active = 1 ORDER BY name")
    fun getAllActiveProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE is_active = 1 AND (name LIKE '%' || :query || '%' OR code LIKE '%' || :query || '%') ORDER BY name LIMIT :limit")
    suspend fun searchProducts(query: String, limit: Int = 50): List<ProductEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProduct(product: ProductEntity)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: String)

    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    // Barcode operations
    @Query("SELECT * FROM product_barcodes WHERE product_id = :productId")
    suspend fun getBarcodesByProductId(productId: String): List<ProductBarcodeEntity>

    @Query("SELECT * FROM product_barcodes WHERE barcode = :barcode")
    suspend fun getProductIdByBarcode(barcode: String): List<ProductBarcodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBarcodes(barcodes: List<ProductBarcodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBarcode(barcode: ProductBarcodeEntity)

    @Query("DELETE FROM product_barcodes WHERE product_id = :productId")
    suspend fun deleteBarcodesByProductId(productId: String)

    @Query("DELETE FROM product_barcodes WHERE product_id = :productId")
    suspend fun deleteBarcodesForProduct(productId: String)

    @Query("DELETE FROM product_barcodes")
    suspend fun deleteAllBarcodes()

    @Transaction
    suspend fun insertProductWithBarcodes(product: ProductEntity, barcodes: List<ProductBarcodeEntity>) {
        insertProduct(product)
        if (barcodes.isNotEmpty()) {
            insertBarcodes(barcodes)
        }
    }
}
