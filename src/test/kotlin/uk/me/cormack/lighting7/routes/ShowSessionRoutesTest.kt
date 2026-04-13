package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShowSessionRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── NewShowSession ──────────────────────────────────────────────────

    @Test
    fun `NewShowSession serialization round-trips`() {
        val input = NewShowSession(name = "Act 1", sessionType = "SHOW")
        val serialized = json.encodeToString(input)
        val deserialized = json.decodeFromString<NewShowSession>(serialized)
        assertEquals(input, deserialized)
    }

    @Test
    fun `NewShowSession defaults to SHOW`() {
        val input = NewShowSession(name = "Test")
        assertEquals("SHOW", input.sessionType)
    }

    @Test
    fun `NewShowSession with SETLIST type`() {
        val input = NewShowSession(name = "Friday Night", sessionType = "SETLIST")
        val serialized = json.encodeToString(input)
        val deserialized = json.decodeFromString<NewShowSession>(serialized)
        assertEquals("SETLIST", deserialized.sessionType)
    }

    // ─── ShowSessionDetails ──────────────────────────────────────────────

    @Test
    fun `ShowSessionDetails serialization round-trips with entries`() {
        val details = ShowSessionDetails(
            id = 1,
            name = "Opening Night",
            sessionType = "SHOW",
            activeEntryId = 2,
            isActive = true,
            entries = listOf(
                ShowSessionEntryDto(id = 1, entryType = "STACK", sortOrder = 0, label = "Act 1", cueStackId = 10, cueStackName = "Act 1 Cues"),
                ShowSessionEntryDto(id = 2, entryType = "STACK", sortOrder = 1, label = null, cueStackId = 11, cueStackName = "Act 2 Cues"),
                ShowSessionEntryDto(id = 3, entryType = "MARKER", sortOrder = 2, label = "Interval", cueStackId = null, cueStackName = null),
            ),
            canEdit = true,
            canDelete = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<ShowSessionDetails>(serialized)
        assertEquals(details, deserialized)
        assertEquals(3, deserialized.entries.size)
        assertEquals(2, deserialized.activeEntryId)
        assertEquals(true, deserialized.isActive)
    }

    @Test
    fun `ShowSessionDetails with no active entry`() {
        val details = ShowSessionDetails(
            id = 1,
            name = "Empty",
            sessionType = "SHOW",
            activeEntryId = null,
            isActive = false,
            entries = emptyList(),
            canEdit = true,
            canDelete = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<ShowSessionDetails>(serialized)
        assertNull(deserialized.activeEntryId)
        assertEquals(false, deserialized.isActive)
    }

    // ─── ShowSessionEntryDto ─────────────────────────────────────────────

    @Test
    fun `ShowSessionEntryDto STACK entry round-trips`() {
        val entry = ShowSessionEntryDto(
            id = 1,
            entryType = "STACK",
            sortOrder = 0,
            label = "Act 1",
            cueStackId = 10,
            cueStackName = "Opening Cues",
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ShowSessionEntryDto>(serialized)
        assertEquals(entry, deserialized)
    }

    @Test
    fun `ShowSessionEntryDto MARKER entry round-trips`() {
        val entry = ShowSessionEntryDto(
            id = 2,
            entryType = "MARKER",
            sortOrder = 1,
            label = "15 min interval",
            cueStackId = null,
            cueStackName = null,
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<ShowSessionEntryDto>(serialized)
        assertEquals(entry, deserialized)
        assertNull(deserialized.cueStackId)
    }

    // ─── Request DTOs ────────────────────────────────────────────────────

    @Test
    fun `AddStackToSessionRequest serialization round-trips`() {
        val request = AddStackToSessionRequest(cueStackId = 5, sortOrder = 2, label = "Song 3")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AddStackToSessionRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `AddStackToSessionRequest defaults`() {
        val request = AddStackToSessionRequest(cueStackId = 5)
        assertNull(request.sortOrder)
        assertNull(request.label)
    }

    @Test
    fun `AddMarkerToSessionRequest serialization round-trips`() {
        val request = AddMarkerToSessionRequest(label = "Interval", sortOrder = 3)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AddMarkerToSessionRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `UpdateShowSessionEntry serialization round-trips`() {
        val request = UpdateShowSessionEntry(label = "New Label", sortOrder = 5)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<UpdateShowSessionEntry>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `UpdateShowSessionEntry with nulls`() {
        val request = UpdateShowSessionEntry()
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
    fun `AdvanceShowSessionRequest serialization round-trips`() {
        val request = AdvanceShowSessionRequest(direction = "FORWARD", deactivatePrevious = true)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AdvanceShowSessionRequest>(serialized)
        assertEquals("FORWARD", deserialized.direction)
        assertEquals(true, deserialized.deactivatePrevious)
    }

    @Test
    fun `AdvanceShowSessionRequest defaults deactivatePrevious to true`() {
        val request = AdvanceShowSessionRequest(direction = "BACKWARD")
        assertEquals(true, request.deactivatePrevious)
    }

    @Test
    fun `GoToSessionEntryRequest serialization round-trips`() {
        val request = GoToSessionEntryRequest(entryId = 7)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<GoToSessionEntryRequest>(serialized)
        assertEquals(7, deserialized.entryId)
    }

    // ─── Response DTOs ───────────────────────────────────────────────────

    @Test
    fun `ShowSessionActivateResponse serialization round-trips`() {
        val response = ShowSessionActivateResponse(
            sessionId = 1,
            activeEntryId = 2,
            activatedStackId = 10,
            activatedStackName = "Act 1 Cues",
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ShowSessionActivateResponse>(serialized)
        assertEquals(response, deserialized)
    }
}
