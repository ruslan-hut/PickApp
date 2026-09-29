package ua.com.programmer.pick.presentation.document

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.domain.model.DocumentState

class DocumentDetailUiStateParkingTest {

    private fun doc(state: DocumentState, canPark: Boolean = false, canResume: Boolean = false) = Document(
        id = "d1", externalId = "d1", type = "OUTGOING_SHIPMENT", number = "1", date = 0L,
        state = state, clientId = null, clientName = null, warehouseId = null, warehouseName = null,
        notes = null, totalPlanned = 0.0, totalActual = 0.0, assignedUserId = "w1",
        takenAt = null, completedAt = null, lastModified = 0L, version = 1,
        canPark = canPark, canResume = canResume,
    )

    @Test
    fun `a parked document offers resume, never take into work`() {
        val state = DocumentDetailUiState(document = doc(DocumentState.PARKED, canResume = true))
        assertTrue(state.isParked)
        assertTrue(state.canResume)
        assertFalse(state.canTakeIntoWork)
        assertFalse(state.canPark)
    }

    @Test
    fun `resume is the server's call`() {
        val state = DocumentDetailUiState(document = doc(DocumentState.PARKED, canResume = false))
        assertFalse(state.canResume)
    }

    @Test
    fun `park is offered next to take into work only when the server says so`() {
        assertTrue(DocumentDetailUiState(document = doc(DocumentState.PACK, canPark = true)).let { it.canPark && it.canTakeIntoWork })
        assertFalse(DocumentDetailUiState(document = doc(DocumentState.PACK)).canPark)
    }

    @Test
    fun `no park while this device holds the lock`() {
        val state = DocumentDetailUiState(document = doc(DocumentState.PACK, canPark = true), hasStageLock = true)
        assertFalse(state.canPark)
    }

    @Test
    fun `the action bar hides while a park is under way`() {
        val state = DocumentDetailUiState(document = doc(DocumentState.PACK, canPark = true))
        assertTrue(state.showActionBar)
        assertFalse(state.copy(parkingInFlight = true).showActionBar)
    }
}
