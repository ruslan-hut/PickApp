package ua.com.programmer.pick.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.com.programmer.pick.core.di.IoDispatcher
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.mapper.toDomainList
import ua.com.programmer.pick.data.mapper.toEntity
import ua.com.programmer.pick.domain.model.Client
import ua.com.programmer.pick.domain.repository.ClientRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientRepositoryImpl @Inject constructor(
    private val clientDao: ClientDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ClientRepository {

    override fun getAllActiveClients(): Flow<List<Client>> {
        return clientDao.getAllActiveClients().map { it.toDomainList() }
    }

    override suspend fun getClientById(id: String): Client? = withContext(ioDispatcher) {
        clientDao.getClientById(id)?.toDomain()
    }

    override suspend fun getClientByCode(code: String): Client? = withContext(ioDispatcher) {
        clientDao.getClientByCode(code)?.toDomain()
    }

    override suspend fun searchClients(query: String, limit: Int): List<Client> = withContext(ioDispatcher) {
        clientDao.searchClients(query, limit).toDomainList()
    }

    override suspend fun saveClient(client: Client): Result<Unit> = withContext(ioDispatcher) {
        try {
            clientDao.insertClient(client.toEntity())
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save client")
        }
    }

    override suspend fun saveClients(clients: List<Client>): Result<Unit> = withContext(ioDispatcher) {
        try {
            clientDao.insertClients(clients.map { it.toEntity() })
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "Failed to save clients")
        }
    }

    override suspend fun deleteClient(id: String) = withContext(ioDispatcher) {
        clientDao.deleteClient(id)
    }

    override suspend fun deleteAllClients() = withContext(ioDispatcher) {
        clientDao.deleteAllClients()
    }
}
