package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import uk.me.cormack.lighting7.models.CuePresetApplicationDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.CueTriggerDto
import uk.me.cormack.lighting7.models.CueTriggerDetailDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for cue trigger DTOs, timed preset application DTOs, and serialization.
 */
class CueTriggerRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── CueTriggerDto (script-only) ───────────────────────────────────

    @Test
    fun `CueTriggerDto ACTIVATION script round-trips correctly`() {
        val dto = CueTriggerDto(
            triggerType = "ACTIVATION",
            scriptId = 5,
            sortOrder = 0,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueTriggerDto>(serialized)
        assertEquals(dto, deserialized)
        assertEquals(5, deserialized.scriptId)
    }

    @Test
    fun `CueTriggerDto DELAYED script round-trips correctly`() {
        val dto = CueTriggerDto(
            triggerType = "DELAYED",
            delayMs = 5000,
            scriptId = 12,
            sortOrder = 1,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueTriggerDto>(serialized)
        assertEquals(dto, deserialized)
        assertEquals(5000L, deserialized.delayMs)
        assertEquals(12, deserialized.scriptId)
    }

    @Test
    fun `CueTriggerDto RECURRING script round-trips correctly`() {
        val dto = CueTriggerDto(
            triggerType = "RECURRING",
            intervalMs = 40000,
            randomWindowMs = 5000,
            scriptId = 3,
            sortOrder = 2,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueTriggerDto>(serialized)
        assertEquals(dto, deserialized)
        assertEquals(40000L, deserialized.intervalMs)
        assertEquals(5000L, deserialized.randomWindowMs)
    }

    @Test
    fun `CueTriggerDto DEACTIVATION defaults nullable fields`() {
        val dto = CueTriggerDto(
            triggerType = "DEACTIVATION",
            scriptId = 7,
        )
        assertNull(dto.delayMs)
        assertNull(dto.intervalMs)
        assertNull(dto.randomWindowMs)
        assertEquals(0, dto.sortOrder)
    }

    // ─── CueTriggerDetailDto ────────────────────────────────────────────

    @Test
    fun `CueTriggerDetailDto includes resolved script name`() {
        val dto = CueTriggerDetailDto(
            triggerType = "ACTIVATION",
            scriptId = 10,
            scriptName = "Setup Effects",
            sortOrder = 0,
        )
        assertEquals("Setup Effects", dto.scriptName)
    }

    @Test
    fun `CueTriggerDetailDto RECURRING with script name round-trips`() {
        val dto = CueTriggerDetailDto(
            triggerType = "RECURRING",
            intervalMs = 30000,
            randomWindowMs = 2000,
            scriptId = 5,
            scriptName = "Ambient Pulse",
            sortOrder = 1,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueTriggerDetailDto>(serialized)
        assertEquals(dto, deserialized)
    }

    // ─── CuePresetApplicationDto with timing ────────────────────────────

    @Test
    fun `CuePresetApplicationDto immediate round-trips correctly`() {
        val dto = CuePresetApplicationDto(
            presetId = 1,
            targets = listOf(CueTargetDto("group", "wash")),
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CuePresetApplicationDto>(serialized)
        assertEquals(dto, deserialized)
        assertNull(deserialized.delayMs)
        assertNull(deserialized.intervalMs)
    }

    @Test
    fun `CuePresetApplicationDto delayed round-trips correctly`() {
        val dto = CuePresetApplicationDto(
            presetId = 5,
            targets = listOf(CueTargetDto("fixture", "hex-1")),
            delayMs = 2000,
            sortOrder = 1,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CuePresetApplicationDto>(serialized)
        assertEquals(dto, deserialized)
        assertEquals(2000L, deserialized.delayMs)
        assertNull(deserialized.intervalMs)
    }

    @Test
    fun `CuePresetApplicationDto recurring round-trips correctly`() {
        val dto = CuePresetApplicationDto(
            presetId = 3,
            targets = listOf(CueTargetDto("group", "uv-bars")),
            intervalMs = 40000,
            randomWindowMs = 5000,
            sortOrder = 2,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CuePresetApplicationDto>(serialized)
        assertEquals(dto, deserialized)
        assertEquals(40000L, deserialized.intervalMs)
        assertEquals(5000L, deserialized.randomWindowMs)
    }

    // ─── NewCue with triggers and timed presets ─────────────────────────

    @Test
    fun `NewCue with script triggers round-trips correctly`() {
        val newCue = NewCue(
            name = "Show Cue",
            presetApplications = listOf(
                CuePresetApplicationDto(
                    presetId = 1,
                    targets = listOf(CueTargetDto("group", "wash")),
                ),
                CuePresetApplicationDto(
                    presetId = 3,
                    targets = listOf(CueTargetDto("fixture", "uv-strip-1")),
                    intervalMs = 40000,
                    randomWindowMs = 5000,
                    sortOrder = 1,
                ),
            ),
            triggers = listOf(
                CueTriggerDto(
                    triggerType = "RECURRING",
                    intervalMs = 40000,
                    randomWindowMs = 5000,
                    scriptId = 3,
                    sortOrder = 0,
                ),
                CueTriggerDto(
                    triggerType = "DEACTIVATION",
                    scriptId = 4,
                    sortOrder = 1,
                ),
            ),
        )
        val serialized = json.encodeToString(newCue)
        val deserialized = json.decodeFromString<NewCue>(serialized)
        assertEquals(2, deserialized.presetApplications.size)
        assertEquals(2, deserialized.triggers.size)
        assertEquals("RECURRING", deserialized.triggers[0].triggerType)
        assertEquals("DEACTIVATION", deserialized.triggers[1].triggerType)
        assertEquals(40000L, deserialized.triggers[0].intervalMs)
        // Second preset has timing
        assertEquals(40000L, deserialized.presetApplications[1].intervalMs)
        // First preset is immediate
        assertNull(deserialized.presetApplications[0].intervalMs)
    }

    @Test
    fun `NewCue without triggers defaults to empty list`() {
        val newCue = NewCue(name = "No Triggers")
        assertEquals(emptyList(), newCue.triggers)
    }

    // ─── CueTriggerManager.computeRandomisedInterval ────────────────────

    @Test
    fun `computeRandomisedInterval returns base when no window`() {
        val result = uk.me.cormack.lighting7.fx.CueTriggerManager.computeRandomisedInterval(5000L, null)
        assertEquals(5000L, result)
    }

    @Test
    fun `computeRandomisedInterval returns base when window is zero`() {
        val result = uk.me.cormack.lighting7.fx.CueTriggerManager.computeRandomisedInterval(5000L, 0L)
        assertEquals(5000L, result)
    }

    @Test
    fun `computeRandomisedInterval respects minimum floor`() {
        repeat(100) {
            val result = uk.me.cormack.lighting7.fx.CueTriggerManager.computeRandomisedInterval(200L, 500L)
            assert(result >= 100L) { "Expected >= 100ms, got $result" }
        }
    }

    @Test
    fun `computeRandomisedInterval produces variation within window`() {
        val results = (1..100).map {
            uk.me.cormack.lighting7.fx.CueTriggerManager.computeRandomisedInterval(10000L, 3000L)
        }
        assert(results.all { it in 7000L..13000L }) {
            "Expected all results in [7000, 13000], got min=${results.min()}, max=${results.max()}"
        }
        assert(results.distinct().size > 1) { "Expected variation, got only ${results.distinct()}" }
    }
}
