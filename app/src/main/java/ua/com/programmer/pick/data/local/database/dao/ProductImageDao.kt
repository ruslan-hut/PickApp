package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.ProductImageEntity

@Dao
interface ProductImageDao {

    @Query("SELECT * FROM product_images WHERE product_id = :productId LIMIT 1")
    suspend fun getByProductId(productId: String): ProductImageEntity?

    @Query("SELECT * FROM product_images WHERE product_id = :productId LIMIT 1")
    fun observeByProductId(productId: String): Flow<ProductImageEntity?>

    @Query("SELECT * FROM product_images WHERE product_id IN (:productIds)")
    suspend fun getByProductIds(productIds: List<String>): List<ProductImageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ProductImageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ProductImageEntity>)

    @Query("DELETE FROM product_images WHERE product_id = :productId")
    suspend fun deleteByProductId(productId: String)

    @Query("DELETE FROM product_images")
    suspend fun deleteAll()
}
