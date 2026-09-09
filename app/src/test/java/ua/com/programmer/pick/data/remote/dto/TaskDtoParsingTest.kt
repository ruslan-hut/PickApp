package ua.com.programmer.pick.data.remote.dto

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire parsing of the guided-task envelope (server `docs/device-api.md`
 * "Guided tasks"). The samples are the ones documented there, so a drift on
 * either side shows up here rather than on a terminal in the warehouse.
 */
class TaskDtoParsingTest {

    private val gson = Gson()

    @Test
    fun `parses a full task envelope`() {
        val json = """
            {
              "task": { "id": "6a9b", "type": "CELL_RECOUNT", "warehouse_id": "WH-DARK",
                        "state": "OPEN", "step_id": "rc_count", "metric_category": "RECOUNT",
                        "started_at": 1788700000000, "updated_at": 1788700100000 },
              "step": {
                "id": "rc_count",
                "title": "Cell A-01-02 — count",
                "expect": "product",
                "rows": [
                  { "text": "Widget · batch L-2026-09", "highlight": true },
                  { "text": "Gadget · no batch", "planned": 10, "actual": 5 }
                ],
                "actions": [
                  { "code": "empty", "label": "Shelf is empty" },
                  { "code": "confirm", "label": "Save count", "style": "primary" },
                  { "code": "cancel", "label": "Cancel", "style": "danger" }
                ],
                "hint": "Scan each product",
                "lock_info": "locked by you until 14:32"
              },
              "message": { "level": "warning", "text": "Ivan is counting this cell" },
              "replayed": false
            }
        """.trimIndent()

        val response = gson.fromJson(json, DeviceDto.TaskResponse::class.java)

        val task = requireNotNull(response.task)
        assertEquals("6a9b", task.id)
        assertEquals("CELL_RECOUNT", task.type)
        assertEquals("OPEN", task.state)
        assertEquals("rc_count", task.stepId)
        assertEquals(1788700100000L, task.updatedAt)
        assertEquals("", task.documentId ?: "")

        val step = requireNotNull(response.step)
        assertEquals("product", step.expect)
        assertEquals("locked by you until 14:32", step.lockInfo)
        assertEquals(2, step.rows?.size)
        assertTrue(step.rows!![0].highlight)
        assertNull(step.rows[0].planned)
        assertEquals(10L, step.rows[1].planned)
        assertEquals(5L, step.rows[1].actual)
        assertFalse(step.rows[1].highlight)

        val actions = requireNotNull(step.actions)
        assertEquals(listOf("empty", "confirm", "cancel"), actions.map { it.code })
        assertNull(actions[0].style)
        assertEquals("primary", actions[1].style)

        assertEquals("warning", response.message?.level)
        assertFalse(response.replayed)
        assertNull(response.lineUpdates)
    }

    @Test
    fun `parses line updates on a document-bound envelope`() {
        val json = """
            {
              "task": { "id": "t1", "type": "OUTGOING_SHIPMENT", "document_id": "ERP-DOC-001",
                        "state": "OPEN", "step_id": "co_line" },
              "step": { "id": "co_line", "title": "A-01-02", "expect": "cell", "rows": [], "actions": [] },
              "replayed": true,
              "line_updates": [
                { "line_key": "K1", "line_number": 1, "actual_quantity": 8, "is_completed": true },
                { "line_number": 2, "actual_quantity": 0.5, "is_completed": false }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, DeviceDto.TaskResponse::class.java)

        assertEquals("ERP-DOC-001", response.task?.documentId)
        assertTrue(response.replayed)
        val updates = requireNotNull(response.lineUpdates)
        assertEquals(2, updates.size)
        assertEquals("K1", updates[0].lineKey)
        assertEquals(8.0, updates[0].actualQuantity, 0.0)
        assertTrue(updates[0].isCompleted)
        assertNull(updates[1].lineKey)
        assertEquals(2, updates[1].lineNumber)
        assertEquals(0.5, updates[1].actualQuantity, 0.0)
        assertFalse(updates[1].isCompleted)
    }

    @Test
    fun `parses a final envelope without a step`() {
        val json = """
            { "task": { "id": "t1", "type": "CELL_RECOUNT", "state": "CANCELLED", "step_id": "" } }
        """.trimIndent()

        val response = gson.fromJson(json, DeviceDto.TaskResponse::class.java)

        assertEquals("CANCELLED", response.task?.state)
        assertNull(response.step)
        assertNull(response.message)
        assertFalse(response.replayed)
    }

    @Test
    fun `parses the open tasks list of a login response`() {
        val json = """
            {
              "access_token": "a", "refresh_token": "r", "expires_at": 1, "user_id": "u",
              "user_external_id": "ue", "user_name": "n", "role": "COLLECTOR",
              "offline_hash": "h", "tenant_id": "t",
              "available_document_types": [
                { "code": "OUTGOING_SHIPMENT", "description": "Outgoing shipment" },
                { "code": "CELL_RECOUNT", "description": "Cell recount", "mode": "guided", "requires_plan": false }
              ],
              "open_tasks": [
                { "id": "6a9b", "type": "CELL_RECOUNT", "step_title": "Cell A-01-02 — count", "started_at": 1788700000000 }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, DeviceDto.LoginResponse::class.java)

        val types = requireNotNull(response.availableDocumentTypes)
        assertNull(types[0].mode)
        assertNull(types[0].requiresPlan)
        assertEquals("guided", types[1].mode)
        assertEquals(false, types[1].requiresPlan)

        val open = requireNotNull(response.openTasks)
        assertEquals(1, open.size)
        assertEquals("6a9b", open[0].id)
        assertEquals("Cell A-01-02 — count", open[0].stepTitle)
        assertEquals(1788700000000L, open[0].startedAt)
        assertNull(open[0].documentId)
    }

    @Test
    fun `a login response from a server without the module leaves open tasks null`() {
        val json = """
            {
              "access_token": "a", "refresh_token": "r", "expires_at": 1, "user_id": "u",
              "user_external_id": "ue", "user_name": "n", "role": "COLLECTOR",
              "offline_hash": "h", "tenant_id": "t",
              "available_document_types": [ { "code": "INVENTORY", "description": "Inventory" } ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, DeviceDto.LoginResponse::class.java)

        assertNull(response.openTasks)
        assertNull(response.availableDocumentTypes?.single()?.mode)
    }

    @Test
    fun `open endpoint returns an array of envelopes`() {
        val json = """
            [
              { "task": { "id": "t1", "type": "CELL_RECOUNT", "state": "OPEN", "step_id": "rc_count" },
                "step": { "id": "rc_count", "title": "A-01-02", "expect": "qty", "rows": [], "actions": [] } },
              { "task": { "id": "t2", "type": "PLACEMENT", "state": "OPEN", "step_id": "pl_scan" } }
            ]
        """.trimIndent()

        val listType = object : TypeToken<List<DeviceDto.TaskResponse>>() {}.type
        val tasks: List<DeviceDto.TaskResponse> = gson.fromJson(json, listType)

        assertEquals(2, tasks.size)
        assertEquals("qty", tasks[0].step?.expect)
        assertNull(tasks[1].step)
    }

    @Test
    fun `serializes an action request with the wire field names`() {
        val json = gson.toJson(
            DeviceDto.TaskActionRequest(
                operationId = "b7a1",
                stepId = "rc_enter_qty",
                action = "confirm",
                quantity = 45,
            ),
        )

        assertTrue(json.contains("\"operation_id\":\"b7a1\""))
        assertTrue(json.contains("\"step_id\":\"rc_enter_qty\""))
        assertTrue(json.contains("\"quantity\":45"))
        // Gson omits nulls: an action without a value must not send an empty one.
        assertFalse(json.contains("value"))
    }
}
