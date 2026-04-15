package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShowRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── ShowDetails ─────────────────────────────────────────────────────

    @Test
    fun `ShowDetails serialization round-trips with entries`() {
        val details = ShowDetails(
            projectId = 42,
            activeEntryId = 2,
            entries = listOf(
                ShowEntryDto(id = 1, entryType = "STACK", sortOrder = 0, label = "Act 1", cueStackId = 10, cueStackName = "Act 1 Cues"),
                ShowEntryDto(id = 2, entryType = "STACK", sortOrder = 1, label = null, cueStackId = 11, cueStackName = "Act 2 Cues"),
                ShowEntryDto(id = 3, entryType = "MARKER", sortOrder = 2, label = "Interval", cueStackId = null, cueStackName = null),
            ),
            canEdit = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<ShowDetails>(serialized)
        assertEquals(details, deserialized)
        assertEquals(3, deserialized.entries.size)
        assertEquals(2, deserialized.activeEntryId)
        assertEquals(42, deserialized.projectId)
    }

    @Test
    fun `ShowDetails with no active entry and empty entries`() {
        val details = ShowDetails(
            projectId = 1,
            activeEntryId = null,
            entries = emptyList(),
            canEdit = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<ShowDetails>(serialized)
        assertNull(deserialized.activeEntryId)
        assertEquals(0, deserialized.entries.size)
    }

    @Test
    fun `ShowDetails canEdit reflects isCurrentProject`() {
        val readOnly = ShowDetails(
            projectId = 1,
            activeEntryId = null,
            entries = emptyList(),
            canEdit = false,
        )
        val serialized = json.encodeToString(readOnly)
        val deserialized = json.decodeFromString<ShowDetails>(serialized)
        assertEquals(false, deserialized.canEdit)
    }

    // ─── ShowEntryDto ────────────────────────────────────────────────────

    @Test
    fun `ShowEntryDto STACK entry round-trips`() {
        val entry = ShowEntryDto(
            id = 1,
            entryType = "STACK",
            sortOrder = 0,
            label = "Act 1",
            cueStackId = 10,
            cueStackName = "Opening Cues",
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ShowEntryDto>(serialized)
        assertEquals(entry, deserialized)
    }

    @Test
    fun `ShowEntryDto MARKER entry round-trips`() {
        val entry = ShowEntryDto(
            id = 2,
            entryType = "MARKER",
            sortOrder = 1,
            label = "15 min interval",
            cueStackId = null,
            cueStackName = null,
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ShowEntryDto>(serialized)
        assertEquals(entry, deserialized)
        assertNull(deserialized.cueStackId)
    }

    // ─── Request DTOs ────────────────────────────────────────────────────

    @Test
    fun `AddStackToShowRequest serialization round-trips`() {
        val request = AddStackToShowRequest(cueStackId = 5, sortOrder = 2, label = "Song 3")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AddStackToShowRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `AddStackToShowRequest defaults`() {
        val request = AddStackToShowRequest(cueStackId = 5)
        assertNull(request.sortOrder)
        assertNull(request.label)
    }

    @Test
    fun `AddMarkerToShowRequest serialization round-trips`() {
        val request = AddMarkerToShowRequest(label = "Interval", sortOrder = 3)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AddMarkerToShowRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `UpdateShowEntryRequest serialization round-trips`() {
        val request = UpdateShowEntryRequest(label = "New Label", sortOrder = 5)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<UpdateShowEntryRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `UpdateShowEntryRequest with nulls`() {
        val request = UpdateShowEntryRequest()
        assertNull(request.label)
        assertNull(request.sortOrder)
    }

    @Test
    fun `ReorderEntriesRequest serialization round-trips`() {
        val request = ReorderEntriesRequest(entryIds = listOf(3, 1, 5, 2))
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<ReorderEntriesRequest>(serialized)
        assertEquals(listOf(3, 1, 5, 2), deserialized.entryIds)
    }

    @Test
    fun `AdvanceShowRequest serialization round-trips`() {
        val request = AdvanceShowRequest(direction = "FORWARD", deactivatePrevious = true)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AdvanceShowRequest>(serialized)
        assertEquals("FORWARD", deserialized.direction)
        assertEquals(true, deserialized.deactivatePrevious)
    }

    @Test
    fun `AdvanceShowRequest defaults deactivatePrevious to true`() {
        val request = AdvanceShowRequest(direction = "BACKWARD")
        assertEquals(true, request.deactivatePrevious)
    }

    @Test
    fun `GoToShowEntryRequest serialization round-trips`() {
        val request = GoToShowEntryRequest(entryId = 7)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<GoToShowEntryRequest>(serialized)
        assertEquals(7, deserialized.entryId)
    }

    // ─── Response DTOs ───────────────────────────────────────────────────

    @Test
    fun `ShowActivateResponse serialization round-trips`() {
        val response = ShowActivateResponse(
            projectId = 42,
            activeEntryId = 2,
            activatedStackId = 10,
            activatedStackName = "Act 1 Cues",
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ShowActivateResponse>(serialized)
        assertEquals(response, deserialized)
    }

    @Test
    fun `ShowActivateResponse with null active entry`() {
        val response = ShowActivateResponse(
            projectId = 42,
            activeEntryId = null,
            activatedStackId = null,
            activatedStackName = null,
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ShowActivateResponse>(serialized)
        assertNull(deserialized.activeEntryId)
        assertNull(deserialized.activatedStackId)
        assertNull(deserialized.activatedStackName)
    }
}
