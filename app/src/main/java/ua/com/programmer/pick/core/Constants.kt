package ua.com.programmer.pick.core

object Constants {

    // Sync entity types
    object SyncEntity {
        const val USERS = "users"
        const val PRODUCTS = "products"
        const val CLIENTS = "clients"
        const val WAREHOUSES = "warehouses"
        const val DOCUMENTS = "documents"
    }

    // Database
    object Database {
        const val NAME = "pick_database"
        const val VERSION = 1
    }

    // Network
    object Network {
        const val BASE_URL = "https://api.example.com/"
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val WEBSOCKET_PING_INTERVAL_SECONDS = 30L
    }

    // WorkManager
    object Work {
        const val SYNC_WORK_NAME = "sync_work"
        const val UPLOAD_WORK_NAME = "upload_work"
    }
}
