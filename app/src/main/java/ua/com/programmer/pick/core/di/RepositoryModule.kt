package ua.com.programmer.pick.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ua.com.programmer.pick.data.repository.ClientRepositoryImpl
import ua.com.programmer.pick.data.repository.DocumentRepositoryImpl
import ua.com.programmer.pick.data.repository.OutgoingOperationRepositoryImpl
import ua.com.programmer.pick.data.repository.ProductRepositoryImpl
import ua.com.programmer.pick.data.repository.UserRepositoryImpl
import ua.com.programmer.pick.data.repository.WarehouseRepositoryImpl
import ua.com.programmer.pick.domain.repository.ClientRepository
import ua.com.programmer.pick.domain.repository.DocumentRepository
import ua.com.programmer.pick.domain.repository.OutgoingOperationRepository
import ua.com.programmer.pick.domain.repository.ProductRepository
import ua.com.programmer.pick.domain.repository.UserRepository
import ua.com.programmer.pick.domain.repository.WarehouseRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(
        userRepositoryImpl: UserRepositoryImpl
    ): UserRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(
        documentRepositoryImpl: DocumentRepositoryImpl
    ): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindProductRepository(
        productRepositoryImpl: ProductRepositoryImpl
    ): ProductRepository

    @Binds
    @Singleton
    abstract fun bindClientRepository(
        clientRepositoryImpl: ClientRepositoryImpl
    ): ClientRepository

    @Binds
    @Singleton
    abstract fun bindWarehouseRepository(
        warehouseRepositoryImpl: WarehouseRepositoryImpl
    ): WarehouseRepository

    @Binds
    @Singleton
    abstract fun bindOutgoingOperationRepository(
        outgoingOperationRepositoryImpl: OutgoingOperationRepositoryImpl
    ): OutgoingOperationRepository
}
