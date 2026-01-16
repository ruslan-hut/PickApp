package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.ClientEntity
import ua.com.programmer.pick.domain.model.Client

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
