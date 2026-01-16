package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.ClientEntity

@Dao
interface ClientDao {

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun getClientById(id: String): ClientEntity?

    @Query("SELECT * FROM clients WHERE code = :code")
    suspend fun getClientByCode(code: String): ClientEntity?

    @Query("SELECT * FROM clients WHERE is_active = 1 ORDER BY name")
    fun getAllActiveClients(): Flow<List<ClientEntity>>

    @Query("SELECT * FROM clients WHERE is_active = 1 AND (name LIKE '%' || :query || '%' OR code LIKE '%' || :query || '%') ORDER BY name LIMIT :limit")
    suspend fun searchClients(query: String, limit: Int = 50): List<ClientEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClient(client: ClientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClients(clients: List<ClientEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClient(client: ClientEntity)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun deleteClient(id: String)

    @Query("DELETE FROM clients")
    suspend fun deleteAllClients()
}
