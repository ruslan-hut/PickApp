package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.Product

interface ProductRepository {

    fun getAllActiveProducts(): Flow<List<Product>>

    suspend fun getProductById(id: String): Product?

    suspend fun getProductByCode(code: String): Product?

    suspend fun getProductByBarcode(barcode: String): Product?

    suspend fun searchProducts(query: String, limit: Int = 50): List<Product>

    suspend fun saveProduct(product: Product): Result<Unit>

    suspend fun saveProducts(products: List<Product>): Result<Unit>

    suspend fun deleteProduct(id: String)

    suspend fun deleteAllProducts()

    suspend fun syncProducts(): Result<Unit>
}
