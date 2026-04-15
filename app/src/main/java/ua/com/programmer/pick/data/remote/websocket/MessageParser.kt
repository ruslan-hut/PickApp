package ua.com.programmer.pick.data.remote.websocket

import ua.com.programmer.pick.core.util.AppLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parser for WebSocket messages.
 * Handles conversion between JSON and SyncMessage objects.
 *
 * All messages follow the envelope format:
 * {
 *   "id": "unique-id",
 *   "type": "MESSAGE_TYPE",
 *   "timestamp": "2024-01-01T12:00:00Z",
 *   "payload": { ... }
 * }
 */
@Singleton
class MessageParser @Inject constructor(
    private val gson: Gson
) {
    companion object {
        private const val TAG = "MessageParser"

        // Envelope fields
        private const val FIELD_ID = "id"
        private const val FIELD_TYPE = "type"
        private const val FIELD_TIMESTAMP = "timestamp"
        private const val FIELD_PAYLOAD = "payload"

        // Payload fields
        private const val FIELD_ENTITY_TYPES = "entity_types"
        private const val FIELD_ENTITY_TYPE = "entity_type"
        private const val FIELD_CURSORS = "cursors"
        private const val FIELD_DATA = "data"
        private const val FIELD_DELETED_IDS = "deleted_ids"
        private const val FIELD_FULL_SET = "full_set"
        private const val FIELD_SYNC_ID = "sync_id"
        private const val FIELD_DOCUMENT_ID = "document_id"
        private const val FIELD_STATE = "state"
        private const val FIELD_LINES = "lines"
        private const val FIELD_LINE_NUMBER = "line_number"
        private const val FIELD_ACTUAL_QUANTITY = "actual_quantity"
        private const val FIELD_BATCH_NUMBER = "batch_number"
        private const val FIELD_IS_COMPLETED = "is_completed"
        private const val FIELD_SUCCESS = "success"
        private const val FIELD_LOCKED_BY = "locked_by"
        private const val FIELD_COMPLETED_AT = "completed_at"
        private const val FIELD_VERSION = "version"
        private const val FIELD_ERROR = "error"
        private const val FIELD_BARCODE = "barcode"
        private const val FIELD_PRODUCT = "product"
        private const val FIELD_ERROR_TYPE = "error_type"
        private const val FIELD_MESSAGE = "message"
        private const val FIELD_STACK_TRACE = "stack_trace"
        private const val FIELD_METADATA = "metadata"
        private const val FIELD_CODE = "code"
        private const val FIELD_DETAILS = "details"
        private const val FIELD_EVENT = "event"
        private const val FIELD_ENTITY_ID = "entity_id"
        private const val FIELD_WEIGHT = "weight"
        private const val FIELD_BOX_ID = "box_id"
        private const val FIELD_OFFLINE_SEQ = "offline_seq"
        private const val FIELD_CLIENT_TS = "client_ts"
        private const val FIELD_WAS_NOOP = "was_noop"
        private const val FIELD_STAGE = "stage"

        // User login fields
        private const val FIELD_LOGIN = "login"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_USER_ID = "user_id"
        private const val FIELD_USER_EXTERNAL_ID = "user_external_id"
        private const val FIELD_USER_NAME = "user_name"
        private const val FIELD_ROLE = "role"
        private const val FIELD_OFFLINE_HASH = "offline_hash"
        private const val FIELD_TENANT_ID = "tenant_id"
        private const val FIELD_ERROR_MESSAGE = "error_message"
        private const val FIELD_AVAILABLE_DOCUMENT_TYPES = "available_document_types"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_DOCUMENT_TYPE = "document_type"
        private const val FIELD_DEBUG_JOURNAL_ENABLED = "debug_journal_enabled"
        private const val FIELD_DEVICE_ID = "device_id"
        private const val FIELD_EVENTS = "events"
        private const val FIELD_ACCEPTED_IDS = "accepted_ids"
        private const val FIELD_EVENT_TYPE_PAYLOAD = "event_type"
        private const val FIELD_SEVERITY = "severity"
        private const val FIELD_PAYLOAD_JSON = "payload_json"
        private const val FIELD_CREATED_AT = "created_at"

        private val ISO_8601_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Parse incoming JSON string to SyncMessage
     */
    fun parseMessage(json: String): SyncMessage? {
        return try {
            val jsonObject = JsonParser.parseString(json).asJsonObject

            val id = jsonObject.get(FIELD_ID)?.asString ?: generateMessageId()
            val typeStr = jsonObject.get(FIELD_TYPE)?.asString ?: return null
            val timestamp = jsonObject.get(FIELD_TIMESTAMP)?.asString ?: getCurrentTimestamp()
            val payload = jsonObject.get(FIELD_PAYLOAD)?.asJsonObject

            when (typeStr.uppercase()) {
                MessageType.PONG.name -> parsePong(id, timestamp)
                MessageType.USER_LOGIN_RESULT.name -> parseUserLoginResult(id, timestamp, payload)
                MessageType.SYNC_DATA.name -> parseSyncData(id, timestamp, payload)
                MessageType.SYNC_COMPLETE.name -> parseSyncComplete(id, timestamp, payload)
                MessageType.STAGE_LOCK_RESULT.name -> parseStageLockResult(id, timestamp, payload)
                MessageType.STAGE_COMPLETE_RESULT.name -> parseStageCompleteResult(id, timestamp, payload)
                MessageType.PRODUCT_LOOKUP_RESULT.name -> parseProductLookupResult(id, timestamp, payload)
                MessageType.BOX_SCAN_RESULT.name -> parseBoxScanResult(id, timestamp, payload)
                MessageType.BOX_PICKUP_CONFIRM_RESULT.name -> parseBoxPickupConfirmResult(id, timestamp, payload)
                MessageType.BOX_DELIVERY_CONFIRM_RESULT.name -> parseBoxDeliveryConfirmResult(id, timestamp, payload)
                MessageType.SERVER_ERROR.name -> parseServerError(id, timestamp, payload)
                MessageType.PUSH.name -> parsePush(id, timestamp, payload)
                MessageType.DEBUG_EVENT_BATCH_RESULT.name -> parseDebugEventBatchResult(id, timestamp, payload)
                else -> {
                    AppLog.w(TAG, "Unknown message type: $typeStr")
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to parse message: ${e.message}", e)
            null
        }
    }

    /**
     * Serialize SyncMessage to JSON string
     */
    fun serializeMessage(message: SyncMessage): String {
        val jsonObject = JsonObject().apply {
            addProperty(FIELD_ID, message.id)
            addProperty(FIELD_TYPE, message.type.name)
            addProperty(FIELD_TIMESTAMP, message.timestamp)
        }

        val payload = buildPayload(message)
        if (payload != null) {
            jsonObject.add(FIELD_PAYLOAD, payload)
        } else {
            jsonObject.add(FIELD_PAYLOAD, null as JsonObject?)
        }

        return gson.toJson(jsonObject)
    }

    private fun buildPayload(message: SyncMessage): JsonObject? {
        return when (message) {
            is SyncMessage.Ping -> null  // PING has null payload

            is SyncMessage.UserLogin -> JsonObject().apply {
                addProperty(FIELD_LOGIN, message.login)
                addProperty(FIELD_PASSWORD, message.password)
            }

            is SyncMessage.SyncRequest -> JsonObject().apply {
                add(FIELD_ENTITY_TYPES, gson.toJsonTree(message.entityTypes))
                message.cursors?.let { cursors ->
                    add(FIELD_CURSORS, gson.toJsonTree(cursors))
                }
            }

            is SyncMessage.FullSyncRequest -> JsonObject().apply {
                add(FIELD_ENTITY_TYPES, gson.toJsonTree(message.entityTypes))
            }

            is SyncMessage.DocumentListRefresh -> message.documentType?.let {
                JsonObject().apply { addProperty(FIELD_DOCUMENT_TYPE, it) }
            }

            is SyncMessage.DocumentProducts -> JsonObject().apply {
                addProperty(FIELD_DOCUMENT_ID, message.documentId)
            }

            is SyncMessage.Ack -> JsonObject().apply {
                addProperty(FIELD_SYNC_ID, message.syncId)
                add(FIELD_CURSORS, gson.toJsonTree(message.cursors))
            }

            is SyncMessage.StageLock -> JsonObject().apply {
                addProperty(FIELD_DOCUMENT_ID, message.documentId)
                addProperty(FIELD_STAGE, message.stage)
            }

            is SyncMessage.StageUnlock -> JsonObject().apply {
                addProperty(FIELD_DOCUMENT_ID, message.documentId)
                addProperty(FIELD_STAGE, message.stage)
            }

            is SyncMessage.StageComplete -> JsonObject().apply {
                addProperty(FIELD_DOCUMENT_ID, message.documentId)
                addProperty(FIELD_STAGE, message.stage)
            }

            is SyncMessage.DocumentUpdate -> JsonObject().apply {
                addProperty(FIELD_DOCUMENT_ID, message.documentId)
                addProperty(FIELD_STATE, message.state)
                val linesArray = JsonArray()
                message.lines.forEach { line ->
                    val lineObj = JsonObject().apply {
                        addProperty(FIELD_LINE_NUMBER, line.lineNumber)
                        addProperty(FIELD_ACTUAL_QUANTITY, line.actualQuantity)
                        line.batchNumber?.let { addProperty(FIELD_BATCH_NUMBER, it) }
                        addProperty(FIELD_IS_COMPLETED, line.isCompleted)
                    }
                    linesArray.add(lineObj)
                }
                add(FIELD_LINES, linesArray)
            }

            is SyncMessage.ProductLookup -> JsonObject().apply {
                addProperty(FIELD_BARCODE, message.barcode)
            }

            is SyncMessage.ErrorReport -> JsonObject().apply {
                addProperty(FIELD_ERROR_TYPE, message.errorType)
                addProperty(FIELD_MESSAGE, message.message)
                message.stackTrace?.let { addProperty(FIELD_STACK_TRACE, it) }
                message.metadata?.let { add(FIELD_METADATA, gson.toJsonTree(it)) }
            }

            is SyncMessage.BoxScan -> JsonObject().apply {
                addProperty(FIELD_DOCUMENT_ID, message.documentId)
                addProperty(FIELD_BARCODE, message.barcode)
                addProperty(FIELD_WEIGHT, message.weight)
            }

            is SyncMessage.BoxPickupConfirm -> JsonObject().apply {
                addProperty(FIELD_BARCODE, message.barcode)
                addProperty(FIELD_OFFLINE_SEQ, message.offlineSeq)
                addProperty(FIELD_CLIENT_TS, message.clientTs)
            }

            is SyncMessage.BoxDeliveryConfirm -> JsonObject().apply {
                addProperty(FIELD_BARCODE, message.barcode)
                addProperty(FIELD_OFFLINE_SEQ, message.offlineSeq)
                addProperty(FIELD_CLIENT_TS, message.clientTs)
            }

            is SyncMessage.DebugEventBatch -> JsonObject().apply {
                addProperty(FIELD_TENANT_ID, message.tenantId)
                addProperty(FIELD_DEVICE_ID, message.deviceId)
                val arr = JsonArray()
                message.events.forEach { evt ->
                    arr.add(JsonObject().apply {
                        addProperty(FIELD_ID, evt.id)
                        evt.userId?.let { addProperty(FIELD_USER_ID, it) }
                        evt.documentId?.let { addProperty(FIELD_DOCUMENT_ID, it) }
                        evt.stage?.let { addProperty(FIELD_STAGE, it) }
                        addProperty(FIELD_EVENT_TYPE_PAYLOAD, evt.eventType)
                        addProperty(FIELD_SEVERITY, evt.severity)
                        addProperty(FIELD_MESSAGE, evt.message)
                        evt.payloadJson?.let { addProperty(FIELD_PAYLOAD_JSON, it) }
                        addProperty(FIELD_CREATED_AT, evt.createdAt)
                    })
                }
                add(FIELD_EVENTS, arr)
            }

            // Server-to-client messages (not serialized by client)
            is SyncMessage.Pong,
            is SyncMessage.UserLoginResult,
            is SyncMessage.SyncData,
            is SyncMessage.SyncComplete,
            is SyncMessage.StageLockResult,
            is SyncMessage.StageCompleteResult,
            is SyncMessage.ProductLookupResult,
            is SyncMessage.BoxScanResult,
            is SyncMessage.BoxPickupConfirmResult,
            is SyncMessage.BoxDeliveryConfirmResult,
            is SyncMessage.ServerError,
            is SyncMessage.Push,
            is SyncMessage.DebugEventBatchResult -> null
        }
    }

    // ============================================
    // Parse Methods
    // ============================================

    private fun parsePong(id: String, timestamp: String): SyncMessage.Pong {
        return SyncMessage.Pong(id = id, timestamp = timestamp)
    }

    private fun parseUserLoginResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.UserLoginResult {
        val availableTypes = payload?.getAsJsonArray(FIELD_AVAILABLE_DOCUMENT_TYPES)?.map { element ->
            val obj = element.asJsonObject
            AvailableDocumentTypeDto(
                code = obj.get(FIELD_CODE)?.asString ?: "",
                description = obj.get(FIELD_DESCRIPTION)?.asString ?: ""
            )
        }

        return SyncMessage.UserLoginResult(
            id = id,
            timestamp = timestamp,
            success = payload?.get(FIELD_SUCCESS)?.asBoolean ?: false,
            userId = payload?.get(FIELD_USER_ID)?.asString,
            userExternalId = payload?.get(FIELD_USER_EXTERNAL_ID)?.asString,
            userName = payload?.get(FIELD_USER_NAME)?.asString,
            role = payload?.get(FIELD_ROLE)?.asString,
            offlineHash = payload?.get(FIELD_OFFLINE_HASH)?.asString,
            tenantId = payload?.get(FIELD_TENANT_ID)?.asString,
            availableDocumentTypes = availableTypes,
            debugJournalEnabled = payload?.get(FIELD_DEBUG_JOURNAL_ENABLED)?.let {
                if (it.isJsonNull) null else it.asBoolean
            },
            errorMessage = payload?.get(FIELD_ERROR_MESSAGE)?.asString
        )
    }

    private fun parseSyncData(id: String, timestamp: String, payload: JsonObject?): SyncMessage.SyncData? {
        if (payload == null) return null
        val rawData = payload.get(FIELD_DATA)
        val data = if (rawData == null || rawData.isJsonNull) {
            AppLog.w(TAG, "SYNC_DATA payload has null/missing 'data' field, using empty array")
            JsonArray()
        } else {
            rawData
        }
        return SyncMessage.SyncData(
            id = id,
            timestamp = timestamp,
            entityType = payload.get(FIELD_ENTITY_TYPE)?.asString ?: return null,
            data = data,
            deletedIds = payload.get(FIELD_DELETED_IDS)?.asJsonArray?.map { it.asString },
            fullSet = payload.get(FIELD_FULL_SET)?.let { if (it.isJsonNull) false else it.asBoolean } ?: false
        )
    }

    private fun parseSyncComplete(id: String, timestamp: String, payload: JsonObject?): SyncMessage.SyncComplete? {
        if (payload == null) return null
        val cursorsJson = payload.get(FIELD_CURSORS)?.asJsonObject ?: return null
        val cursors = mutableMapOf<String, String>()
        cursorsJson.entrySet().forEach { (key, value) ->
            val str = value?.asString
            if (str != null) {
                cursors[key] = str
            }
        }
        return SyncMessage.SyncComplete(
            id = id,
            timestamp = timestamp,
            syncId = payload.get(FIELD_SYNC_ID)?.asString ?: "",
            cursors = cursors
        )
    }

    private fun parseStageLockResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.StageLockResult? {
        if (payload == null) return null
        return SyncMessage.StageLockResult(
            id = id,
            timestamp = timestamp,
            documentId = payload.get(FIELD_DOCUMENT_ID)?.asString ?: return null,
            stage = payload.get(FIELD_STAGE)?.asString ?: return null,
            success = payload.get(FIELD_SUCCESS)?.asBoolean ?: false,
            lockedBy = payload.get(FIELD_LOCKED_BY)?.asString,
            error = payload.get(FIELD_ERROR)?.asString
        )
    }

    private fun parseStageCompleteResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.StageCompleteResult? {
        if (payload == null) return null
        return SyncMessage.StageCompleteResult(
            id = id,
            timestamp = timestamp,
            documentId = payload.get(FIELD_DOCUMENT_ID)?.asString ?: return null,
            stage = payload.get(FIELD_STAGE)?.asString ?: return null,
            success = payload.get(FIELD_SUCCESS)?.asBoolean ?: false,
            state = payload.get(FIELD_STATE)?.asString,
            completedAt = payload.get(FIELD_COMPLETED_AT)?.asString,
            version = payload.get(FIELD_VERSION)?.asLong,
            error = payload.get(FIELD_ERROR)?.asString
        )
    }

    private fun parseProductLookupResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.ProductLookupResult? {
        if (payload == null) return null
        return SyncMessage.ProductLookupResult(
            id = id,
            timestamp = timestamp,
            success = payload.get(FIELD_SUCCESS)?.asBoolean ?: false,
            product = payload.get(FIELD_PRODUCT),
            error = payload.get(FIELD_ERROR)?.asString
        )
    }

    private fun parseServerError(id: String, timestamp: String, payload: JsonObject?): SyncMessage.ServerError {
        return SyncMessage.ServerError(
            id = id,
            timestamp = timestamp,
            code = payload?.get(FIELD_CODE)?.asString ?: "UNKNOWN",
            message = payload?.get(FIELD_MESSAGE)?.asString ?: "Unknown error",
            details = payload?.get(FIELD_DETAILS)?.asString
        )
    }

    private fun parsePush(id: String, timestamp: String, payload: JsonObject?): SyncMessage.Push? {
        if (payload == null) return null
        return SyncMessage.Push(
            id = id,
            timestamp = timestamp,
            event = payload.get(FIELD_EVENT)?.asString ?: return null,
            entityType = payload.get(FIELD_ENTITY_TYPE)?.asString,
            entityId = payload.get(FIELD_ENTITY_ID)?.asString,
            data = payload.get(FIELD_DATA)
        )
    }

    private fun parseBoxScanResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.BoxScanResult {
        return SyncMessage.BoxScanResult(
            id = id,
            timestamp = timestamp,
            success = payload?.get(FIELD_SUCCESS)?.asBoolean ?: false,
            boxId = payload?.get(FIELD_BOX_ID)?.asString,
            error = payload?.get(FIELD_ERROR)?.asString
        )
    }

    private fun parseBoxPickupConfirmResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.BoxPickupConfirmResult {
        return SyncMessage.BoxPickupConfirmResult(
            id = id,
            timestamp = timestamp,
            success = payload?.get(FIELD_SUCCESS)?.asBoolean ?: false,
            barcode = payload?.get(FIELD_BARCODE)?.asString,
            wasNoop = payload?.get(FIELD_WAS_NOOP)?.asBoolean ?: false
        )
    }

    private fun parseDebugEventBatchResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.DebugEventBatchResult {
        val acceptedIds = payload?.get(FIELD_ACCEPTED_IDS)?.asJsonArray?.map { it.asString } ?: emptyList()
        return SyncMessage.DebugEventBatchResult(
            id = id,
            timestamp = timestamp,
            success = payload?.get(FIELD_SUCCESS)?.asBoolean ?: false,
            acceptedIds = acceptedIds,
            error = payload?.get(FIELD_ERROR)?.asString
        )
    }

    private fun parseBoxDeliveryConfirmResult(id: String, timestamp: String, payload: JsonObject?): SyncMessage.BoxDeliveryConfirmResult {
        return SyncMessage.BoxDeliveryConfirmResult(
            id = id,
            timestamp = timestamp,
            success = payload?.get(FIELD_SUCCESS)?.asBoolean ?: false,
            barcode = payload?.get(FIELD_BARCODE)?.asString,
            wasNoop = payload?.get(FIELD_WAS_NOOP)?.asBoolean ?: false
        )
    }

    // ============================================
    // Utility Methods
    // ============================================

    /**
     * Generate unique message ID (nanosecond timestamp)
     */
    fun generateMessageId(): String = System.nanoTime().toString()

    /**
     * Get current timestamp in ISO 8601 format
     */
    fun getCurrentTimestamp(): String = ISO_8601_FORMATTER.format(Date())
}
