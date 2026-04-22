package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import uk.me.cormack.lighting7.fx.AssignmentHealth
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CuePresetApplicationDto
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for cue DTOs, serialization, and route data structures.
 *
 * These tests validate the data model correctness without requiring a database
 * or server instance. Integration testing of the actual HTTP endpoints is done
 * manually via the frontend.
 */
class CueRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─── CueTargetDto ────────────────────────────────────────────────────

    @Test
    fun `CueTargetDto serialization round-trips correctly`() {
        val target = CueTargetDto(type = "group", key = "front-wash")
        val serialized = json.encodeToString(target)
        val deserialized = json.decodeFromString<CueTargetDto>(serialized)
        assertEquals(target, deserialized)
    }

    @Test
    fun `CueTargetDto supports fixture target type`() {
        val target = CueTargetDto(type = "fixture", key = "hex-1")
        val serialized = json.encodeToString(target)
        val deserialized = json.decodeFromString<CueTargetDto>(serialized)
        assertEquals("fixture", deserialized.type)
        assertEquals("hex-1", deserialized.key)
    }

    // ─── CuePresetApplicationDto ─────────────────────────────────────────

    @Test
    fun `CuePresetApplicationDto serialization round-trips with multiple targets`() {
        val dto = CuePresetApplicationDto(
            presetId = 42,
            targets = listOf(
                CueTargetDto(type = "group", key = "front-wash"),
                CueTargetDto(type = "fixture", key = "hex-1"),
            )
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CuePresetApplicationDto>(serialized)
        assertEquals(dto, deserialized)
        assertEquals(2, deserialized.targets.size)
    }

    @Test
    fun `CuePresetApplicationDto supports empty targets list`() {
        val dto = CuePresetApplicationDto(presetId = 1, targets = emptyList())
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CuePresetApplicationDto>(serialized)
        assertEquals(0, deserialized.targets.size)
    }

    // ─── CueAdHocEffectDto ───────────────────────────────────────────────

    @Test
    fun `CueAdHocEffectDto serialization round-trips with all fields`() {
        val dto = CueAdHocEffectDto(
            targetType = "group",
            targetKey = "front-wash",
            effectType = "SineWave",
            category = "dimmer",
            propertyName = "dimmer",
            beatDivision = 1.0,
            blendMode = "OVERRIDE",
            distribution = "LINEAR",
            phaseOffset = 0.25,
            elementMode = "FLAT",
            elementFilter = "ODD",
            stepTiming = true,
            parameters = mapOf("min" to "0", "max" to "255"),
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueAdHocEffectDto>(serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun `CueAdHocEffectDto defaults nullable fields correctly`() {
        val dto = CueAdHocEffectDto(
            targetType = "fixture",
            targetKey = "hex-1",
            effectType = "ColourCycle",
            category = "colour",
            beatDivision = 4.0,
            blendMode = "OVERRIDE",
            distribution = "LINEAR",
        )
        assertNull(dto.propertyName)
        assertNull(dto.elementMode)
        assertNull(dto.elementFilter)
        assertNull(dto.stepTiming)
        assertEquals(0.0, dto.phaseOffset)
        assertEquals(emptyMap(), dto.parameters)
    }

    @Test
    fun `CueAdHocEffectDto serialization round-trips with defaults`() {
        val dto = CueAdHocEffectDto(
            targetType = "fixture",
            targetKey = "hex-1",
            effectType = "ColourCycle",
            category = "colour",
            beatDivision = 4.0,
            blendMode = "OVERRIDE",
            distribution = "LINEAR",
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueAdHocEffectDto>(serialized)
        assertEquals(dto, deserialized)
    }

    // ─── CuePropertyAssignmentDto ────────────────────────────────────────

    @Test
    fun `CuePropertyAssignmentDto serialization round-trips with all fields`() {
        val dto = CuePropertyAssignmentDto(
            targetType = "group",
            targetKey = "front-wash",
            propertyName = "dimmer",
            value = "200",
            fadeDurationMs = 1500L,
            sortOrder = 2,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CuePropertyAssignmentDto>(serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun `CuePropertyAssignmentDto defaults nullable and optional fields correctly`() {
        val dto = CuePropertyAssignmentDto(
            targetType = "fixture",
            targetKey = "hex-1",
            propertyName = "colour",
            value = "#00ff00",
        )
        assertNull(dto.fadeDurationMs)
        assertEquals(0, dto.sortOrder)
        assertEquals(AssignmentHealth.Ok, dto.health)
    }

    @Test
    fun `CuePropertyAssignmentDto health field round-trips non-Ok variants`() {
        val deadFixture = CuePropertyAssignmentDto(
            targetType = "fixture",
            targetKey = "hex-renamed",
            propertyName = "dimmer",
            value = "200",
            health = AssignmentHealth.MissingFixture("hex-renamed"),
        )
        val round = json.decodeFromString<CuePropertyAssignmentDto>(json.encodeToString(deadFixture))
        assertIs<AssignmentHealth.MissingFixture>(round.health)
        assertEquals("hex-renamed", (round.health as AssignmentHealth.MissingFixture).fixtureKey)
    }

    @Test
    fun `CuePropertyAssignmentDto input without health deserialises to Ok default`() {
        val inputJson = """{"targetType":"fixture","targetKey":"hex-1","propertyName":"dimmer","value":"200"}"""
        val deserialized = json.decodeFromString<CuePropertyAssignmentDto>(inputJson)
        assertEquals(AssignmentHealth.Ok, deserialized.health)
    }

    @Test
    fun `CuePropertyAssignmentDto supports colour, position, and setting value forms`() {
        val colour = CuePropertyAssignmentDto("fixture", "hex-1", "colour", "#ff8800;w64;amber128;uv0")
        val position = CuePropertyAssignmentDto("fixture", "mover-1", "position", "128,200")
        val setting = CuePropertyAssignmentDto("fixture", "par-1", "gobo", "128")

        for (dto in listOf(colour, position, setting)) {
            val serialized = json.encodeToString(dto)
            val deserialized = json.decodeFromString<CuePropertyAssignmentDto>(serialized)
            assertEquals(dto, deserialized)
        }
    }

    // ─── NewCue ──────────────────────────────────────────────────────────

    @Test
    fun `NewCue serialization round-trips with full data`() {
        val newCue = NewCue(
            name = "Test Cue",
            palette = listOf("#ff0000", "#00ff00;w128", "#0000ff"),
            presetApplications = listOf(
                CuePresetApplicationDto(
                    presetId = 1,
                    targets = listOf(CueTargetDto("group", "front-wash")),
                ),
            ),
            adHocEffects = listOf(
                CueAdHocEffectDto(
                    targetType = "fixture",
                    targetKey = "hex-1",
                    effectType = "Pulse",
                    category = "dimmer",
                    propertyName = "dimmer",
                    beatDivision = 0.5,
                    blendMode = "ADDITIVE",
                    distribution = "CENTER_OUT",
                    phaseOffset = 0.1,
                    parameters = mapOf("min" to "50", "max" to "200", "attackRatio" to "0.3"),
                ),
            ),
        )
        val serialized = json.encodeToString(newCue)
        val deserialized = json.decodeFromString<NewCue>(serialized)
        assertEquals(newCue, deserialized)
    }

    @Test
    fun `NewCue defaults to empty collections`() {
        val newCue = NewCue(name = "Minimal Cue")
        assertEquals(emptyList(), newCue.palette)
        assertEquals(emptyList(), newCue.presetApplications)
        assertEquals(emptyList(), newCue.adHocEffects)
        assertEquals(emptyList(), newCue.propertyAssignments)
    }

    @Test
    fun `NewCue round-trips propertyAssignments alongside other collections`() {
        val newCue = NewCue(
            name = "With Assignments",
            propertyAssignments = listOf(
                CuePropertyAssignmentDto("group", "front-wash", "dimmer", "180"),
                CuePropertyAssignmentDto("fixture", "hex-1", "colour", "#00ffaa"),
            ),
        )
        val serialized = json.encodeToString(newCue)
        val deserialized = json.decodeFromString<NewCue>(serialized)
        assertEquals(newCue, deserialized)
        assertEquals(2, deserialized.propertyAssignments.size)
    }

    // ─── CueDetails ─────────────────────────────────────────────────────

    @Test
    fun `CueDetails serialization round-trips correctly`() {
        val details = CueDetails(
            id = 1,
            name = "Blue Wash",
            palette = listOf("#0000ff"),
            presetApplications = listOf(
                CuePresetApplicationDetail(
                    presetId = 5,
                    presetName = "Slow Pulse",
                    targets = listOf(CueTargetDto("group", "front-wash")),
                ),
            ),
            adHocEffects = emptyList(),
            canEdit = true,
            canDelete = true,
        )
        val serialized = json.encodeToString(details)
        val deserialized = json.decodeFromString<CueDetails>(serialized)
        assertEquals(details, deserialized)
    }

    @Test
    fun `CueDetails read-only project has canEdit and canDelete false`() {
        val details = CueDetails(
            id = 1,
            name = "Read Only",
            palette = emptyList(),
            presetApplications = emptyList(),
            adHocEffects = emptyList(),
            canEdit = false,
            canDelete = false,
        )
        assertEquals(false, details.canEdit)
        assertEquals(false, details.canDelete)
    }

    @Test
    fun `CuePresetApplicationDetail with null preset name`() {
        val detail = CuePresetApplicationDetail(
            presetId = 99,
            presetName = null,
            targets = listOf(CueTargetDto("group", "movers")),
        )
        assertNull(detail.presetName)
        val serialized = json.encodeToString(detail)
        val deserialized = json.decodeFromString<CuePresetApplicationDetail>(serialized)
        assertEquals(detail, deserialized)
    }

    // ─── CopyCueRequest / CopyCueResponse ────────────────────────────────

    @Test
    fun `CopyCueRequest serialization round-trips`() {
        val request = CopyCueRequest(targetProjectId = 5, newName = "Copied Cue")
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<CopyCueRequest>(serialized)
        assertEquals(request, deserialized)
    }

    @Test
    fun `CopyCueRequest newName defaults to null`() {
        val request = CopyCueRequest(targetProjectId = 5)
        assertNull(request.newName)
    }

    @Test
    fun `CopyCueResponse serialization round-trips`() {
        val response = CopyCueResponse(
            cueId = 10,
            cueName = "Test Cue",
            targetProjectId = 5,
            targetProjectName = "Other Show",
            message = "Cue copied successfully",
        )
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<CopyCueResponse>(serialized)
        assertEquals(response, deserialized)
    }

    // ─── ApplyCueResponse ────────────────────────────────────────────────

    @Test
    fun `ApplyCueResponse serialization round-trips`() {
        val response = ApplyCueResponse(effectCount = 8, cueName = "Party Mode")
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ApplyCueResponse>(serialized)
        assertEquals(response, deserialized)
    }

    // ─── Complex scenario ────────────────────────────────────────────────

    @Test
    fun `complex cue with multiple presets and ad-hoc effects serializes correctly`() {
        val newCue = NewCue(
            name = "Full Show Look",
            palette = listOf("#ff0000;w255", "#00ff00", "#0000ff;w128", "#ffffff"),
            presetApplications = listOf(
                CuePresetApplicationDto(
                    presetId = 1,
                    targets = listOf(
                        CueTargetDto("group", "front-wash"),
                        CueTargetDto("group", "back-wash"),
                    ),
                ),
                CuePresetApplicationDto(
                    presetId = 2,
                    targets = listOf(
                        CueTargetDto("fixture", "mover-1"),
                        CueTargetDto("fixture", "mover-2"),
                    ),
                ),
            ),
            adHocEffects = listOf(
                CueAdHocEffectDto(
                    targetType = "group",
                    targetKey = "movers",
                    effectType = "Circle",
                    category = "position",
                    propertyName = "position",
                    beatDivision = 8.0,
                    blendMode = "OVERRIDE",
                    distribution = "LINEAR",
                    phaseOffset = 0.0,
                    elementMode = "PER_FIXTURE",
                    parameters = mapOf(
                        "panCenter" to "128",
                        "tiltCenter" to "128",
                        "panRadius" to "60",
                        "tiltRadius" to "40",
                    ),
                ),
                CueAdHocEffectDto(
                    targetType = "fixture",
                    targetKey = "strobe-1",
                    effectType = "Strobe",
                    category = "dimmer",
                    propertyName = "dimmer",
                    beatDivision = 0.25,
                    blendMode = "MAX",
                    distribution = "UNIFIED",
                    parameters = mapOf("offValue" to "0", "onValue" to "255", "onRatio" to "0.1"),
                ),
            ),
        )
        val serialized = json.encodeToString(newCue)
        val deserialized = json.decodeFromString<NewCue>(serialized)
        assertEquals(newCue, deserialized)
        assertEquals(4, deserialized.palette.size)
        assertEquals(2, deserialized.presetApplications.size)
        assertEquals(2, deserialized.adHocEffects.size)
        assertEquals(2, deserialized.presetApplications[0].targets.size)
        assertEquals(4, deserialized.adHocEffects[0].parameters.size)
    }
}
