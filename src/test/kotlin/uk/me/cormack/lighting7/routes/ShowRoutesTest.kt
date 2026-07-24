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
    fun `ShowDetails serialization round-trips`() {
        val details = ShowDetails(projectId = 42, activeStackId = 10, canEdit = true)
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<ShowDetails>(serialized)
        assertEquals(details, deserialized)
        assertEquals(10, deserialized.activeStackId)
        assertEquals(42, deserialized.projectId)
    }

    @Test
    fun `ShowDetails with no active stack`() {
        val details = ShowDetails(projectId = 1, activeStackId = null, canEdit = true)
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<ShowDetails>(serialized)
        assertNull(deserialized.activeStackId)
    }

    @Test
    fun `ShowDetails canEdit reflects isCurrentProject`() {
        val readOnly = ShowDetails(projectId = 1, activeStackId = null, canEdit = false)
        val serialized = json.encodeToString(readOnly)
        val deserialized = json.decodeFromString<ShowDetails>(serialized)
        assertEquals(false, deserialized.canEdit)
    }

    // ─── Request DTOs ────────────────────────────────────────────────────

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
    fun `GoToStackRequest serialization round-trips`() {
        val request = GoToStackRequest(stackId = 7)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<GoToStackRequest>(serialized)
        assertEquals(7, deserialized.stackId)
    }

    // ─── Response DTOs ───────────────────────────────────────────────────

    @Test
    fun `ShowActivateResponse serialization round-trips`() {
        val response = ShowActivateResponse(
            projectId = 42,
            activeStackId = 10,
            activatedStackName = "Act 1 Cues",
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ShowActivateResponse>(serialized)
        assertEquals(response, deserialized)
    }

    @Test
    fun `ShowActivateResponse with null active stack`() {
        val response = ShowActivateResponse(projectId = 42, activeStackId = null, activatedStackName = null)
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ShowActivateResponse>(serialized)
        assertNull(deserialized.activeStackId)
        assertNull(deserialized.activatedStackName)
    }
}
