package ua.com.programmer.pick.domain.repository

import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.core.util.Result
import ua.com.programmer.pick.domain.model.Client

interface ClientRepository {

    fun getAllActiveClients(): Flow<List<Client>>

    suspend fun getClientById(id: String): Client?

    suspend fun getClientByCode(code: String): Client?

    suspend fun searchClients(query: String, limit: Int = 50): List<Client>

    suspend fun saveClient(client: Client): Result<Unit>

    suspend fun saveClients(clients: List<Client>): Result<Unit>

    suspend fun deleteClient(id: String)

    suspend fun deleteAllClients()

    suspend fun syncClients(): Result<Unit>
}
