package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.ProductDao
import ua.com.programmer.pick.data.mapper.toBarcodeEntityList
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.domain.model.Product
import ua.com.programmer.pick.domain.repository.ProductRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProductRepositoryImpl @Inject constructor(
    private val productDao: ProductDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ProductRepository {

    override fun getAllActiveProducts(): Flow<List<Product>> {
        return productDao.getAllActiveProducts().map { entities ->
            entities.map { entity ->
                val barcodes = productDao.getBarcodesByProductId(entity.id)
                entity.toDomain(barcodes)
            }
        }
    }

    override suspend fun getProductById(id: String): Product? = withContext(ioDispatcher) {
        val entity = productDao.getProductById(id) ?: return@withContext null
        val barcodes = productDao.getBarcodesByProductId(id)
        entity.toDomain(barcodes)
    }

    override suspend fun getProductByCode(code: String): Product? = withContext(ioDispatcher) {
        val entity = productDao.getProductByCode(code) ?: return@withContext null
        val barcodes = productDao.getBarcodesByProductId(entity.id)
        entity.toDomain(barcodes)
    }

    override suspend fun getProductByBarcode(barcode: String): Product? = withContext(ioDispatcher) {
        val barcodeEntities = productDao.getProductIdByBarcode(barcode)
        if (barcodeEntities.isEmpty()) return@withContext null

        val productId = barcodeEntities.first().productId
        val entity = productDao.getProductById(productId) ?: return@withContext null
        val allBarcodes = productDao.getBarcodesByProductId(productId)
        entity.toDomain(allBarcodes)
    }

    override suspend fun searchProducts(query: String, limit: Int): List<Product> = withContext(ioDispatcher) {
        productDao.searchProducts(query, limit).map { entity ->
            val barcodes = productDao.getBarcodesByProductId(entity.id)
            entity.toDomain(barcodes)
        }
    }

    override suspend fun saveProduct(product: Product): Result<Unit> = withContext(ioDispatcher) {
        try {
            val entity = product.toEntity()
            val barcodeEntities = product.barcodes.toBarcodeEntityList()
            productDao.insertProductWithBarcodes(entity, barcodeEntities)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save product")
        }
    }

    override suspend fun saveProducts(products: List<Product>): Result<Unit> = withContext(ioDispatcher) {
        try {
            products.forEach { product ->
                val entity = product.toEntity()
                val barcodeEntities = product.barcodes.toBarcodeEntityList()
                productDao.insertProductWithBarcodes(entity, barcodeEntities)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save products")
        }
    }

    override suspend fun deleteProduct(id: String) = withContext(ioDispatcher) {
        productDao.deleteProduct(id)
    }

    override suspend fun deleteAllProducts() = withContext(ioDispatcher) {
        productDao.deleteAllBarcodes()
        productDao.deleteAllProducts()
    }
}
