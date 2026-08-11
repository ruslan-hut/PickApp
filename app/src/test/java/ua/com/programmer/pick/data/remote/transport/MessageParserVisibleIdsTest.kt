package ua.com.programmer.pick.data.remote.transport

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `visible_ids` is the delta-sync purge signal, and the whole thing hinges on
 * telling three states apart: absent (no purge info — keep everything), an
 * explicit empty list (purge everything), and a populated list. Collapsing the
 * middle case into null is what would let a stale document survive forever;
 * collapsing it into "keep" the other way would wipe the worker's list.
 */
class MessageParserVisibleIdsTest {

    private val parser = MessageParser(Gson())

    private fun syncData(payloadBody: String): SyncMessage.SyncData {
        val raw = """
            {"id":"m-1","type":"SYNC_DATA","timestamp":"2026-08-11T09:40:00Z",
             "payload":{"entity_type":"documents","data":[],$payloadBody}}
        """.trimIndent()
        return parser.parseMessage(raw) as SyncMessage.SyncData
    }

    @Test
    fun `absent visible_ids means no purge information`() {
        assertNull(syncData("\"full_set\":false").visibleIds)
    }

    @Test
    fun `null visible_ids means no purge information`() {
        assertNull(syncData("\"visible_ids\":null").visibleIds)
    }

    @Test
    fun `empty visible_ids survives as an empty list, not null`() {
        val visible = syncData("\"visible_ids\":[]").visibleIds
        assertTrue("empty list must not decode as null", visible != null && visible.isEmpty())
    }

    @Test
    fun `populated visible_ids is parsed in order`() {
        assertEquals(listOf("d-1", "d-2"), syncData("\"visible_ids\":[\"d-1\",\"d-2\"]").visibleIds)
    }
}
