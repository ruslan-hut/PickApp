package ua.com.programmer.pick.presentation.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.com.programmer.pick.R
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentState

/**
 * D7: `collect_mode` picks the detail screen's primary action, per load. The
 * classic "Take into work" bar must never appear on a guided document — the
 * task engine takes the Collect lock itself, and a second, classic claim would
 * arm the cooperative force-release path on top of it (D3).
 */
class DocumentDetailUiStateGuidedTest {

    @Test
    fun `a guided document offers the guided bar, not take-into-work`() {
        val state = state(collectMode = "guided", documentState = DocumentState.LOADED)

        assertTrue(state.isGuidedCollect)
        assertTrue(state.canStartGuided)
        assertFalse(state.canTakeIntoWork)
    }

    @Test
    fun `a document without collect_mode keeps the classic bar`() {
        val state = state(collectMode = null, documentState = DocumentState.LOADED)

        assertFalse(state.isGuidedCollect)
        assertFalse(state.canStartGuided)
        assertTrue(state.canTakeIntoWork)
    }

    @Test
    fun `an in-flight guided document whose flag disappears finishes classically`() {
        // The tenant's emergency switch: the next list load drops collect_mode
        // and the worker completes the document on the classic screen.
        val state = state(collectMode = null, documentState = DocumentState.COLLECTING)

        assertFalse(state.canStartGuided)
        assertTrue(state.canTakeIntoWork)
    }

    @Test
    fun `guided only applies to the collect stage`() {
        val state = state(collectMode = "guided", documentState = DocumentState.PACK)

        assertFalse(state.isGuidedCollect)
        assertTrue(state.canTakeIntoWork)
    }

    @Test
    fun `the bar says start on LOADED and resume on COLLECTING`() {
        assertEquals(
            R.string.task_start_picking,
            state(collectMode = "guided", documentState = DocumentState.LOADED).guidedButtonLabelRes,
        )
        assertEquals(
            R.string.task_resume_picking,
            state(collectMode = "guided", documentState = DocumentState.COLLECTING).guidedButtonLabelRes,
        )
    }

    @Test
    fun `a receipt says receiving rather than picking`() {
        assertEquals(
            R.string.task_start_receiving,
            state(
                collectMode = "guided",
                documentState = DocumentState.LOADED,
                type = AvailableDocumentType.CODE_INCOMING_RECEIPT,
            ).guidedButtonLabelRes,
        )
        assertEquals(
            R.string.task_continue_receiving,
            state(
                collectMode = "guided",
                documentState = DocumentState.COLLECTING,
                type = AvailableDocumentType.CODE_INCOMING_RECEIPT,
            ).guidedButtonLabelRes,
        )
    }

    @Test
    fun `holding a stage lock still hides the classic bar on a guided document`() {
        val state = state(collectMode = "guided", documentState = DocumentState.COLLECTING)
            .copy(hasStageLock = true)

        assertFalse(state.canTakeIntoWork)
        assertTrue(state.canStartGuided)
    }

    private fun state(
        collectMode: String?,
        documentState: DocumentState,
        type: String = "OUTGOING_SHIPMENT",
    ) = DocumentDetailUiState(
        document = Document(
            id = "doc-1",
            externalId = "ERP-DOC-1",
            type = type,
            number = "001",
            date = 0,
            state = documentState,
            clientId = null,
            clientName = null,
            warehouseId = null,
            warehouseName = null,
            notes = null,
            totalPlanned = 10.0,
            totalActual = 0.0,
            assignedUserId = null,
            takenAt = null,
            completedAt = null,
            lastModified = 0,
            version = 1,
            collectMode = collectMode,
        ),
    )
}
