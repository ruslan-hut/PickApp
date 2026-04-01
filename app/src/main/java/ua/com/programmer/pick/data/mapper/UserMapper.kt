package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.UserEntity
import ua.com.programmer.pick.data.remote.dto.UserDto
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.model.UserRole

fun UserEntity.toDomain(): User {
    return User(
        id = id,
        login = login,
        name = name,
        role = UserRole.fromString(role),
        isActive = isActive,
        lastLoginAt = lastLoginAt,
        warehouseId = warehouseId
    )
}

fun User.toEntity(passwordHash: String, lastUpdated: Long): UserEntity {
    return UserEntity(
        id = id,
        login = login,
        name = name,
        passwordHash = passwordHash,
        role = role.name,
        isActive = isActive,
        lastLoginAt = lastLoginAt,
        lastUpdated = lastUpdated,
        warehouseId = warehouseId
    )
}

fun UserDto.toEntity(passwordHash: String): UserEntity {
    return UserEntity(
        id = id,
        login = login,
        name = name,
        passwordHash = passwordHash,
        role = role,
        isActive = isActive,
        lastLoginAt = null,
        warehouseId = warehouseId,
        lastUpdated = lastUpdated
    )
}

fun UserDto.toDomain(): User {
    return User(
        id = id,
        login = login,
        name = name,
        role = UserRole.fromString(role),
        isActive = isActive,
        lastLoginAt = null,
        warehouseId = warehouseId
    )
}

/**
 * Convert UserDto to entity for sync operations.
 * Preserves existing passwordHash if user already exists,
 * otherwise sets empty passwordHash (user must login online first).
 */
fun UserDto.toEntityForSync(existingPasswordHash: String?): UserEntity {
    return UserEntity(
        id = id,
        login = login,
        name = name,
        passwordHash = existingPasswordHash ?: "",
        role = role,
        isActive = isActive,
        lastLoginAt = null,
        lastUpdated = lastUpdated,
        warehouseId = warehouseId
    )
}
