package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for cue stack DTOs and serialization.
 *
 * Validates data model correctness without requiring a database or server instance.
 */
class CueStackRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── NewCueStack ──────────────────────────────────────────────────────

    @Test
    fun `NewCueStack serialization round-trips with full data`() {
        val input = NewCueStack(
            name = "Act 1",
            palette = listOf("#ff0000", "#00ff00", "#0000ff"),
            loop = true,
        )
        val serialized = json.encodeToString(input)
        val deserialized = json.decodeFromString<NewCueStack>(serialized)
        assertEquals(input, deserialized)
    }

    @Test
    fun `NewCueStack defaults correctly`() {
        val input = NewCueStack(name = "Minimal Stack")
        assertEquals(emptyList(), input.palette)
        assertEquals(false, input.loop)
    }

    @Test
    fun `NewCueStack minimal serialization round-trips`() {
        val input = NewCueStack(name = "Empty Stack")
        val serialized = json.encodeToString(input)
        val deserialized = json.decodeFromString<NewCueStack>(serialized)
        assertEquals(input, deserialized)
    }

    // ─── CueStackCueEntry ─────────────────────────────────────────────────

    @Test
    fun `CueStackCueEntry serialization round-trips`() {
        val entry = CueStackCueEntry(
            id = 42,
            name = "Opening Look",
            sortOrder = 0,
            paletteSize = 3,
            presetCount = 2,
            adHocEffectCount = 1,
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<CueStackCueEntry>(serialized)
        assertEquals(entry, deserialized)
    }

    // ─── CueStackDetails ──────────────────────────────────────────────────

    @Test
    fun `CueStackDetails serialization round-trips with cues`() {
        val details = CueStackDetails(
            id = 1,
            name = "Act 1",
            palette = listOf("#ff0000", "#0000ff"),
            loop = true,
            cues = listOf(
                CueStackCueEntry(id = 10, name = "Scene 1", sortOrder = 0, paletteSize = 2, presetCount = 1, adHocEffectCount = 0),
                CueStackCueEntry(id = 11, name = "Scene 2", sortOrder = 1, paletteSize = 0, presetCount = 0, adHocEffectCount = 3, fadeDurationMs = 1500, fadeCurve = "CUBIC_IN_OUT"),
            ),
            activeCueId = 10,
            canEdit = true,
            canDelete = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<CueStackDetails>(serialized)
        assertEquals(details, deserialized)
    }

    @Test
    fun `CueStackDetails with no active cue and empty cues`() {
        val details = CueStackDetails(
            id = 2,
            name = "Empty Stack",
            palette = emptyList(),
            loop = false,
            cues = emptyList(),
            activeCueId = null,
            canEdit = true,
            canDelete = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<CueStackDetails>(serialized)
        assertEquals(details, deserialized)
        assertNull(deserialized.activeCueId)
    }

    @Test
    fun `CueStackDetails read-only project`() {
        val details = CueStackDetails(
            id = 3,
            name = "Read Only",
            palette = emptyList(),
            loop = false,
            cues = emptyList(),
            activeCueId = null,
            canEdit = false,
            canDelete = false,
        )
        assertEquals(false, details.canEdit)
        assertEquals(false, details.canDelete)
    }

    // ─── Request DTOs ─────────────────────────────────────────────────────

    @Test
    fun `ReorderCuesRequest serialization round-trips`() {
        val request = ReorderCuesRequest(cueIds = listOf(3, 1, 5, 2))
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<ReorderCuesRequest>(serialized)
        assertEquals(request, deserialized)
        assertEquals(listOf(3, 1, 5, 2), deserialized.cueIds)
    }

    @Test
    fun `AddCueToStackRequest serialization round-trips with sortOrder`() {
        val request = AddCueToStackRequest(cueId = 42, sortOrder = 3)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AddCueToStackRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `AddCueToStackRequest defaults sortOrder to null`() {
        val request = AddCueToStackRequest(cueId = 42)
        assertNull(request.sortOrder)
    }

    @Test
    fun `RemoveCueFromStackRequest serialization round-trips`() {
        val request = RemoveCueFromStackRequest(cueId = 42)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<RemoveCueFromStackRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `ActivateCueStackRequest defaults cueId to null`() {
        val request = ActivateCueStackRequest()
        assertNull(request.cueId)
    }

    @Test
    fun `ActivateCueStackRequest with specific cueId`() {
        val request = ActivateCueStackRequest(cueId = 10)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<ActivateCueStackRequest>(serialized)
        assertEquals(10, deserialized.cueId)
    }

    @Test
    fun `AdvanceCueStackRequest serialization round-trips`() {
        val request = AdvanceCueStackRequest(direction = "FORWARD")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AdvanceCueStackRequest>(serialized)
        assertEquals("FORWARD", deserialized.direction)
    }

    @Test
    fun `GoToCueRequest serialization round-trips`() {
        val request = GoToCueRequest(cueId = 5)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<GoToCueRequest>(serialized)
        assertEquals(5, deserialized.cueId)
    }

    // ─── Response DTOs ────────────────────────────────────────────────────

    @Test
    fun `CueStackActivateResponse serialization round-trips`() {
        val response = CueStackActivateResponse(
            stackId = 1,
            cueId = 10,
            cueName = "Opening",
            effectCount = 5,
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<CueStackActivateResponse>(serialized)
        assertEquals(response, deserialized)
    }

    @Test
    fun `CueStackDeactivateResponse serialization round-trips`() {
        val response = CueStackDeactivateResponse(stackId = 1, removedCount = 8)
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<CueStackDeactivateResponse>(serialized)
        assertEquals(response, deserialized)
    }

    // ─── Complex scenario ─────────────────────────────────────────────────

    @Test
    fun `CueStackCueEntry with auto-advance and crossfade serializes correctly`() {
        val entry = CueStackCueEntry(
            id = 42,
            name = "Scene with Fade",
            sortOrder = 0,
            paletteSize = 3,
            presetCount = 2,
            adHocEffectCount = 1,
            autoAdvance = true,
            autoAdvanceDelayMs = 10000,
            fadeDurationMs = 3000,
            fadeCurve = "EASE_IN_OUT",
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<CueStackCueEntry>(serialized)
        assertEquals(entry, deserialized)
        assertEquals(true, deserialized.autoAdvance)
        assertEquals(10000L, deserialized.autoAdvanceDelayMs)
        assertEquals(3000L, deserialized.fadeDurationMs)
        assertEquals("EASE_IN_OUT", deserialized.fadeCurve)
    }

    @Test
    fun `CueStackDetails with multiple cues maintains order`() {
        val cues = (0..4).map { i ->
            CueStackCueEntry(
                id = 100 + i,
                name = "Cue ${i + 1}",
                sortOrder = i,
                paletteSize = if (i == 0) 3 else 0,
                presetCount = 1,
                adHocEffectCount = i,
                autoAdvance = i < 4,  // all except last auto-advance
                autoAdvanceDelayMs = if (i < 4) 5000L else null,
                fadeDurationMs = 1000,
                fadeCurve = "LINEAR",
            )
        }
        val details = CueStackDetails(
            id = 1,
            name = "Sequential Show",
            palette = listOf("#ff0000"),
            loop = false,
            cues = cues,
            activeCueId = 102,
            canEdit = true,
            canDelete = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<CueStackDetails>(serialized)
        assertEquals(5, deserialized.cues.size)
        assertEquals(102, deserialized.activeCueId)
        assertEquals(0, deserialized.cues[0].sortOrder)
        assertEquals(4, deserialized.cues[4].sortOrder)
        assertEquals(true, deserialized.cues[0].autoAdvance)
        assertEquals(false, deserialized.cues[4].autoAdvance)
    }

    // ─── CueStackCueEntry with new fields ────────────────────────────────

    @Test
    fun `CueStackCueEntry with cueNumber and notes serializes correctly`() {
        val entry = CueStackCueEntry(
            id = 42,
            name = "Scene 14",
            sortOrder = 5,
            paletteSize = 3,
            presetCount = 2,
            adHocEffectCount = 1,
            cueNumber = "14A",
            notes = "Sarah enters p34",
            cueType = "STANDARD",
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<CueStackCueEntry>(serialized)
        assertEquals("14A", deserialized.cueNumber)
        assertEquals("Sarah enters p34", deserialized.notes)
        assertEquals("STANDARD", deserialized.cueType)
    }

    @Test
    fun `CueStackCueEntry MARKER type serializes correctly`() {
        val entry = CueStackCueEntry(
            id = 43,
            name = "Scene 4 marker",
            sortOrder = 6,
            paletteSize = 0,
            presetCount = 0,
            adHocEffectCount = 0,
            cueType = "MARKER",
        )
        val serialized = json.encodeToString(entry)
        val deserialized = json.decodeFromString<CueStackCueEntry>(serialized)
        assertEquals("MARKER", deserialized.cueType)
        assertNull(deserialized.cueNumber)
        assertNull(deserialized.notes)
    }

    @Test
    fun `CueStackCueEntry defaults cueType to STANDARD`() {
        val entry = CueStackCueEntry(
            id = 1,
            name = "Test",
            sortOrder = 0,
            paletteSize = 0,
            presetCount = 0,
            adHocEffectCount = 0,
        )
        assertEquals("STANDARD", entry.cueType)
        assertNull(entry.cueNumber)
        assertNull(entry.notes)
    }

    // ─── AddCueToStackRequest with insertByNumber ────────────────────────

    @Test
    fun `AddCueToStackRequest with insertByNumber serializes correctly`() {
        val request = AddCueToStackRequest(cueId = 42, insertByNumber = true)
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<AddCueToStackRequest>(serialized)
        assertEquals(true, deserialized.insertByNumber)
    }

    @Test
    fun `AddCueToStackRequest defaults insertByNumber to false`() {
        val request = AddCueToStackRequest(cueId = 42)
        assertEquals(false, request.insertByNumber)
    }

    // ─── SortByNumberResponse ────────────────────────────────────────────

    @Test
    fun `SortByNumberResponse serialization round-trips`() {
        val response = SortByNumberResponse(
            updatedCues = listOf(
                CueStackCueEntry(id = 1, name = "Cue 1", sortOrder = 0, paletteSize = 0, presetCount = 0, adHocEffectCount = 0, cueNumber = "1"),
                CueStackCueEntry(id = 2, name = "Cue 2", sortOrder = 1, paletteSize = 0, presetCount = 0, adHocEffectCount = 0, cueNumber = "2"),
            ),
            pinnedCount = 1,
            nullNumberCount = 0,
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<SortByNumberResponse>(serialized)
        assertEquals(response, deserialized)
        assertEquals(2, deserialized.updatedCues.size)
        assertEquals(1, deserialized.pinnedCount)
    }
}
