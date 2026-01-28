package ua.com.programmer.pick.core

import ua.com.programmer.pick.BuildConfig

object Constants {

    // Sync entity types
    object SyncEntity {
        const val USERS = "users"
        const val PRODUCTS = "products"
        const val CLIENTS = "clients"
        const val WAREHOUSES = "warehouses"
        const val DOCUMENTS = "documents"

        val ALL = listOf(USERS, PRODUCTS, CLIENTS, WAREHOUSES, DOCUMENTS)
    }

    // Database
    object Database {
        const val NAME = "pick_database"
        const val VERSION = 1
    }

    // Network
    object Network {
        const val BASE_URL = "https://pick.nomadus.net/"
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L

        // WebSocket
        const val WEBSOCKET_PING_INTERVAL_SECONDS = 30L
        const val WEBSOCKET_PONG_TIMEOUT_SECONDS = 60L
        const val WEBSOCKET_MAX_MESSAGE_SIZE = 512 * 1024  // 512KB
        const val WEBSOCKET_WRITE_WAIT_SECONDS = 10L
        const val WEBSOCKET_MAX_RECONNECT_ATTEMPTS = 10

        // App token - hardcoded identifier for this Android app
        // This validates the app against server config during device connection
        const val APP_TOKEN = BuildConfig.BACK_API_TOKEN
    }

    // WorkManager
    object Work {
        const val SYNC_WORK_NAME = "sync_work"
        const val UPLOAD_WORK_NAME = "upload_work"
        const val SYNC_WORK_TAG = "sync_tag"
        const val UPLOAD_WORK_TAG = "upload_tag"
        const val SYNC_INTERVAL_MINUTES = 15L
        const val BACKOFF_DELAY_SECONDS = 30L
    }

    // Sync
    object Sync {
        const val MAX_RETRIES = 5
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 60000L
        const val BACKOFF_MULTIPLIER = 2.0
    }
}
