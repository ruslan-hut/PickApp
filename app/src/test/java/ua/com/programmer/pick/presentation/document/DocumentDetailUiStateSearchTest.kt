package ua.com.programmer.pick.presentation.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.com.programmer.pick.domain.model.DocumentLine

/**
 * In-document search: every typed word must appear in the line's text, in
 * any order, and it narrows what the unchecked filter leaves.
 */
class DocumentDetailUiStateSearchTest {

    private val polygel = line("1", "Полігель 03, 30 мл", "PGEL0330")
    private val smart = line("2", "Смарт Гель 02, 30 мл", "SGEL0230", completed = true)
    private val medium = line("3", "Медіум Гель 15 мл, 05", "MGEL0515", location = "A-01-02")
    private val lines = listOf(polygel, smart, medium)

    @Test
    fun `blank query shows every line`() {
        assertEquals(lines, state("  ").visibleLines)
    }

    @Test
    fun `words match in any order and case`() {
        assertEquals(listOf(polygel, smart), state("30 мл").visibleLines)
        assertEquals(listOf(polygel, smart), state("МЛ 30").visibleLines)
        assertEquals(listOf(smart), state("гель 30 02").visibleLines)
    }

    @Test
    fun `a word without the space still finds the spaced text`() {
        assertEquals(listOf(polygel, smart), state("30мл").visibleLines)
    }

    @Test
    fun `code and cell are searched too`() {
        assertEquals(listOf(smart), state("sgel").visibleLines)
        assertEquals(listOf(medium), state("a-01").visibleLines)
    }

    @Test
    fun `search narrows the unchecked filter`() {
        val state = state("30 мл", showOnlyUnchecked = true)
        assertEquals(listOf(polygel), state.visibleLines)
    }

    @Test
    fun `no match reports an empty search`() {
        assertTrue(state("xyz").isSearchEmpty)
        assertFalse(state("гель").isSearchEmpty)
        assertFalse(state("").isSearchEmpty)
    }

    private fun state(query: String, showOnlyUnchecked: Boolean = false) = DocumentDetailUiState(
        lines = lines,
        searchQuery = query,
        showOnlyUnchecked = showOnlyUnchecked
    )

    private fun line(
        id: String,
        name: String,
        code: String,
        completed: Boolean = false,
        location: String? = null
    ) = DocumentLine(
        id = id,
        documentId = "doc",
        lineNumber = id.toInt(),
        productId = "p$id",
        productCode = code,
        productName = name,
        unit = "шт",
        plannedQuantity = 10.0,
        actualQuantity = 0.0,
        batchNumber = null,
        expirationDate = null,
        locationId = null,
        locationPath = location,
        notes = null,
        isCompleted = completed
    )
}
