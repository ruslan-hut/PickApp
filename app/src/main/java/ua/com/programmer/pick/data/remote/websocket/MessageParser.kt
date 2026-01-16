package ua.com.programmer.pick.data.remote.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parser for WebSocket messages.
 * Handles conversion between JSON and SyncMessage objects.
 */
@Singleton
class MessageParser @Inject constructor(
    private val gson: Gson
) {
    companion object {
        private const val TAG = "MessageParser"

        private const val FIELD_TYPE = "type"
        private const val FIELD_MESSAGE_ID = "message_id"
        private const val FIELD_ENTITY_TYPE = "entity_type"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_IS_FULL_SYNC = "is_full_sync"
        private const val FIELD_DATA = "data"
        private const val FIELD_DELETED_IDS = "deleted_ids"
        private const val FIELD_DOCUMENT_ID = "document_id"
        private const val FIELD_LOCKED_BY = "locked_by"
        private const val FIELD_LOCKED_BY_NAME = "locked_by_name"
        private const val FIELD_LOCKED_AT = "locked_at"
        private const val FIELD_ORIGINAL_MESSAGE_ID = "original_message_id"
        private const val FIELD_SUCCESS = "success"
        private const val FIELD_NEW_VERSION = "new_version"
        private const val FIELD_ERROR = "error"
        private const val FIELD_CODE = "code"
        private const val FIELD_MESSAGE = "message"
        private const val FIELD_RELATED_MESSAGE_ID = "related_message_id"
        private const val FIELD_SERVER_TIME = "server_time"
        private const val FIELD_SESSION_ID = "session_id"
    }

    /**
     * Parse incoming JSON string to SyncMessage
     */
    fun parseMessage(json: String): SyncMessage? {
        return try {
            val jsonObject = JsonParser.parseString(json).asJsonObject
            val typeStr = jsonObject.get(FIELD_TYPE)?.asString ?: return null
            val messageId = jsonObject.get(FIELD_MESSAGE_ID)?.asString ?: generateMessageId()

            when (typeStr.uppercase()) {
                MessageType.DELTA_UPDATE.name -> parseDeltaUpdate(jsonObject, messageId)
                MessageType.DOCUMENT_LOCK.name -> parseDocumentLock(jsonObject, messageId)
                MessageType.ACK.name -> parseAcknowledgment(jsonObject, messageId)
                MessageType.ERROR.name -> parseServerError(jsonObject, messageId)
                MessageType.CONNECTED.name -> parseConnected(jsonObject, messageId)
                else -> {
                    Log.w(TAG, "Unknown message type: $typeStr")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}", e)
            null
        }
    }

    /**
     * Serialize SyncMessage to JSON string
     */
    fun serializeMessage(message: SyncMessage): String {
        val jsonObject = JsonObject()
        jsonObject.addProperty(FIELD_TYPE, message.type.name)
        jsonObject.addProperty(FIELD_MESSAGE_ID, message.messageId)

        when (message) {
            is SyncMessage.TakeIntoWork -> {
                jsonObject.addProperty(FIELD_DOCUMENT_ID, message.documentId)
                jsonObject.addProperty("user_id", message.userId)
                jsonObject.addProperty(FIELD_TIMESTAMP, message.timestamp)
            }
            is SyncMessage.DocumentUpdate -> {
                jsonObject.addProperty(FIELD_DOCUMENT_ID, message.documentId)
                jsonObject.addProperty("state", message.state)
                message.notes?.let { jsonObject.addProperty("notes", it) }
                jsonObject.addProperty("total_actual", message.totalActual)
                jsonObject.addProperty("version", message.version)
                jsonObject.addProperty(FIELD_TIMESTAMP, message.timestamp)
            }
            is SyncMessage.LineUpdate -> {
                jsonObject.addProperty(FIELD_DOCUMENT_ID, message.documentId)
                jsonObject.addProperty("line_id", message.lineId)
                jsonObject.addProperty("actual_quantity", message.actualQuantity)
                message.batchNumber?.let { jsonObject.addProperty("batch_number", it) }
                message.locationId?.let { jsonObject.addProperty("location_id", it) }
                message.notes?.let { jsonObject.addProperty("notes", it) }
                jsonObject.addProperty("is_completed", message.isCompleted)
                jsonObject.addProperty(FIELD_TIMESTAMP, message.timestamp)
            }
            is SyncMessage.CompleteDocument -> {
                jsonObject.addProperty(FIELD_DOCUMENT_ID, message.documentId)
                jsonObject.addProperty("user_id", message.userId)
                jsonObject.addProperty("completed_at", message.completedAt)
                jsonObject.addProperty("version", message.version)
            }
            is SyncMessage.Subscribe -> {
                jsonObject.add("entity_types", gson.toJsonTree(message.entityTypes))
            }
            is SyncMessage.Unsubscribe -> {
                jsonObject.add("entity_types", gson.toJsonTree(message.entityTypes))
            }
            // Server messages are not serialized
            is SyncMessage.DeltaUpdate,
            is SyncMessage.DocumentLock,
            is SyncMessage.Acknowledgment,
            is SyncMessage.ServerError,
            is SyncMessage.Connected -> {
                // These are server-to-client messages, not typically serialized by client
            }
        }

        return gson.toJson(jsonObject)
    }

    private fun parseDeltaUpdate(json: JsonObject, messageId: String): SyncMessage.DeltaUpdate {
        return SyncMessage.DeltaUpdate(
            messageId = messageId,
            entityType = json.get(FIELD_ENTITY_TYPE)?.asString ?: "",
            timestamp = json.get(FIELD_TIMESTAMP)?.asLong ?: 0L,
            isFullSync = json.get(FIELD_IS_FULL_SYNC)?.asBoolean ?: false,
            data = json.get(FIELD_DATA) ?: JsonObject(),
            deletedIds = json.get(FIELD_DELETED_IDS)?.asJsonArray?.map { it.asString }
        )
    }

    private fun parseDocumentLock(json: JsonObject, messageId: String): SyncMessage.DocumentLock {
        return SyncMessage.DocumentLock(
            messageId = messageId,
            documentId = json.get(FIELD_DOCUMENT_ID)?.asString ?: "",
            lockedBy = json.get(FIELD_LOCKED_BY)?.asString ?: "",
            lockedByName = json.get(FIELD_LOCKED_BY_NAME)?.asString ?: "",
            lockedAt = json.get(FIELD_LOCKED_AT)?.asLong ?: 0L
        )
    }

    private fun parseAcknowledgment(json: JsonObject, messageId: String): SyncMessage.Acknowledgment {
        return SyncMessage.Acknowledgment(
            messageId = messageId,
            originalMessageId = json.get(FIELD_ORIGINAL_MESSAGE_ID)?.asString ?: "",
            success = json.get(FIELD_SUCCESS)?.asBoolean ?: false,
            newVersion = json.get(FIELD_NEW_VERSION)?.asInt,
            error = json.get(FIELD_ERROR)?.asString
        )
    }

    private fun parseServerError(json: JsonObject, messageId: String): SyncMessage.ServerError {
        return SyncMessage.ServerError(
            messageId = messageId,
            code = json.get(FIELD_CODE)?.asString ?: "UNKNOWN",
            message = json.get(FIELD_MESSAGE)?.asString ?: "Unknown error",
            relatedMessageId = json.get(FIELD_RELATED_MESSAGE_ID)?.asString
        )
    }

    private fun parseConnected(json: JsonObject, messageId: String): SyncMessage.Connected {
        return SyncMessage.Connected(
            messageId = messageId,
            serverTime = json.get(FIELD_SERVER_TIME)?.asLong ?: System.currentTimeMillis(),
            sessionId = json.get(FIELD_SESSION_ID)?.asString ?: ""
        )
    }

    /**
     * Generate unique message ID for client messages
     */
    fun generateMessageId(): String = UUID.randomUUID().toString()
}
