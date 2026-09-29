package ua.com.programmer.pick.presentation.document

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ua.com.programmer.pick.data.mapper.toDomain
import ua.com.programmer.pick.data.remote.dto.DeviceDto
import ua.com.programmer.pick.domain.model.TaskStepTarget

/**
 * `step.target` carries the step's destination as data; the guided panel
 * composes its one short line from it instead of the server's sentence.
 */
class GuidedStepTargetTest {

    @Test
    fun `cell and quantity read as cell arrow quantity with the line's unit`() {
        assertEquals("AA-1-1 → 4 шт", composeTargetLine(TaskStepTarget(cell = "AA-1-1", qty = 4), "шт"))
    }

    @Test
    fun `either half may be missing`() {
        assertEquals("AA-1-1", composeTargetLine(TaskStepTarget(cell = "AA-1-1"), "шт"))
        assertEquals("4 шт", composeTargetLine(TaskStepTarget(qty = 4), "шт"))
        assertEquals("AA-1-1 → 4", composeTargetLine(TaskStepTarget(cell = "AA-1-1", qty = 4), null))
        assertNull(composeTargetLine(TaskStepTarget(), "шт"))
    }

    @Test
    fun `the wire target maps onto the step`() {
        val step = parse("""{"cell":"AA-1-1","qty":4}""")?.step
        assertEquals(TaskStepTarget(cell = "AA-1-1", qty = 4), step?.target)
    }

    @Test
    fun `an absent or empty target maps to none`() {
        assertNull(parse(null)?.step?.target)
        assertNull(parse("""{"cell":" "}""")?.step?.target)
    }

    private fun parse(target: String?) = Gson().fromJson(
        """
        {"task":{"id":"t1","type":"РасходнаяНакладная","state":"OPEN"},
         "step":{"id":"co_line","title":"Ідіть до AA-1-1 — візьміть 4","expect":"cell"
           ${target?.let { ""","target":$it""" } ?: ""}}}
        """.trimIndent(),
        DeviceDto.TaskResponse::class.java
    ).toDomain()
}
