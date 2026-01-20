package ua.com.programmer.pick.data.mapper

import ua.com.programmer.pick.data.local.database.entity.UserEntity
import ua.com.programmer.pick.data.remote.dto.UserDto
import ua.com.programmer.pick.domain.model.OperatingMode
import ua.com.programmer.pick.domain.model.User
import ua.com.programmer.pick.domain.model.UserRole

fun UserEntity.toDomain(): User {
    return User(
        id = id,
        login = login,
        name = name,
        role = try {
            UserRole.valueOf(role)
        } catch (e: IllegalArgumentException) {
            UserRole.WAREHOUSE_WORKER
        },
        isActive = isActive,
        lastLoginAt = lastLoginAt,
        operatingMode = try {
            OperatingMode.valueOf(operatingMode)
        } catch (e: IllegalArgumentException) {
            OperatingMode.RECEIPT
        }
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
        operatingMode = operatingMode.name,
        lastUpdated = lastUpdated
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
        lastUpdated = lastUpdated
    )
}

fun UserDto.toDomain(): User {
    return User(
        id = id,
        login = login,
        name = name,
        role = try {
            UserRole.valueOf(role.uppercase())
        } catch (e: IllegalArgumentException) {
            UserRole.WAREHOUSE_WORKER
        },
        isActive = isActive,
        lastLoginAt = null
    )
}
