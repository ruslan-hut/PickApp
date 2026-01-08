package ua.com.programmer.pick.domain.model

data class SyncState(
    val entityType: String,
    val lastSyncTime: Long,
    val status: SyncStatus,
    val errorMessage: String? = null
)

enum class SyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}
