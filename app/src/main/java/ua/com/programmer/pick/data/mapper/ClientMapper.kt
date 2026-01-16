package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.ClientEntity
import ua.com.programmer.pick.data.remote.dto.ClientDto
import ua.com.programmer.pick.domain.model.Client
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientMapper @Inject constructor() {

    fun toEntity(dto: ClientDto): ClientEntity {
        return ClientEntity(
            id = dto.id,
            code = dto.code,
            name = dto.name,
            address = dto.address,
            phone = dto.phone,
            isActive = dto.isActive,
            lastUpdated = System.currentTimeMillis()
        )
    }
}

// Extension functions for domain mapping (keeping backward compatibility)
fun ClientEntity.toDomain(): Client {
    return Client(
        id = id,
        code = code,
        name = name,
        address = address,
        phone = phone,
        isActive = isActive
    )
}

fun Client.toEntity(lastUpdated: Long = System.currentTimeMillis()): ClientEntity {
    return ClientEntity(
        id = id,
        code = code,
        name = name,
        address = address,
        phone = phone,
        isActive = isActive,
        lastUpdated = lastUpdated
    )
}

fun List<ClientEntity>.toDomainList(): List<Client> = map { it.toDomain() }
