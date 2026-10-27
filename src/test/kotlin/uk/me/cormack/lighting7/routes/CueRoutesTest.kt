package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.models.Timing
import kotlinx.serialization.encodeToString
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
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

    // ─── CueLayerDto ─────────────────────────────────────────────────────
    //
    // Was `CuePresetApplicationDto`, which retired with `cue_preset_applications` in session 4.
    // `CueLayerDto` is the cue child that names a library entity now, so the coverage moved here.

    @Test
    fun `CueLayerDto serialization round-trips with multiple targets`() {
        val dto = CueLayerDto(
            lookId = 42,
            targets = listOf(
                CueTargetDto(type = "group", key = "front-wash"),
                CueTargetDto(type = "fixture", key = "hex-1"),
            )
        )
        val deserialized = json.decodeFromString<CueLayerDto>(json.encodeToString(dto))
        assertEquals(dto, deserialized)
        assertEquals(2, deserialized.targets.size)
    }

    @Test
    fun `CueLayerDto supports empty targets, meaning the look's own`() {
        // Not "no targets, so it does nothing": an empty set means the Look's bound rows decide
        // where the layer lands. That is the distinction `LayerRow` renders as "look's own targets".
        val dto = CueLayerDto(lookId = 1, targets = emptyList())
        val deserialized = json.decodeFromString<CueLayerDto>(json.encodeToString(dto))
        assertEquals(0, deserialized.targets.size)
    }

    // ─── CueAdHocFxDto ───────────────────────────────────────────────

    @Test
    fun `CueAdHocFxDto serialization round-trips with all fields`() {
        val dto = CueAdHocFxDto(
            targetType = "group",
            targetKey = "front-wash",
            fxType = "Wave",
            family = "intensity",
            attribute = "dimmer",
            cycle = 1.0,
            blendMode = "OVERRIDE",
            order = "NORMAL",
            phaseOffset = 0.25,
            elementMode = "FLAT",
            elementFilter = "ODD",
            timing = Timing.HEAD,
            parameters = mapOf("shape" to "SINE", "low" to "0", "high" to "255"),
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueAdHocFxDto>(serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun `CueAdHocFxDto defaults nullable fields correctly`() {
        val dto = CueAdHocFxDto(
            targetType = "fixture",
            targetKey = "hex-1",
            fxType = "ColourCycle",
            family = "colour",
            cycle = 4.0,
            blendMode = "OVERRIDE",
            order = "NORMAL",
        )
        assertNull(dto.attribute)
        assertNull(dto.elementMode)
        assertNull(dto.elementFilter)
        assertNull(dto.timing)
        assertEquals(0.0, dto.phaseOffset)
        assertEquals(emptyMap(), dto.parameters)
    }

    @Test
    fun `CueAdHocFxDto serialization round-trips with defaults`() {
        val dto = CueAdHocFxDto(
            targetType = "fixture",
            targetKey = "hex-1",
            fxType = "ColourCycle",
            family = "colour",
            cycle = 4.0,
            blendMode = "OVERRIDE",
            order = "NORMAL",
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueAdHocFxDto>(serialized)
        assertEquals(dto, deserialized)
    }

    // ─── CueAttributeAssignmentDto ────────────────────────────────────────

    @Test
    fun `CueAttributeAssignmentDto serialization round-trips with all fields`() {
        val dto = CueAttributeAssignmentDto(
            targetType = "group",
            targetKey = "front-wash",
            attribute = "dimmer",
            value = "200",
            fadeDurationMs = 1500L,
            sortOrder = 2,
        )
        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<CueAttributeAssignmentDto>(serialized)
        assertEquals(dto, deserialized)
    }

    @Test
    fun `CueAttributeAssignmentDto defaults nullable and optional fields correctly`() {
        val dto = CueAttributeAssignmentDto(
            targetType = "fixture",
            targetKey = "hex-1",
            attribute = "colour",
            value = "#00ff00",
        )
        assertNull(dto.fadeDurationMs)
        assertEquals(0, dto.sortOrder)
        assertEquals(AssignmentHealth.Ok, dto.health)
    }

    @Test
    fun `CueAttributeAssignmentDto health field round-trips non-Ok variants`() {
        val deadFixture = CueAttributeAssignmentDto(
            targetType = "fixture",
            targetKey = "hex-renamed",
            attribute = "dimmer",
            value = "200",
            health = AssignmentHealth.MissingFixture("hex-renamed"),
        )
        val round = json.decodeFromString<CueAttributeAssignmentDto>(json.encodeToString(deadFixture))
        assertIs<AssignmentHealth.MissingFixture>(round.health)
        assertEquals("hex-renamed", round.health.fixtureKey)
    }

    @Test
    fun `CueAttributeAssignmentDto input without health deserialises to Ok default`() {
        val inputJson = """{"targetType":"fixture","targetKey":"hex-1","attribute":"dimmer","value":"200"}"""
        val deserialized = json.decodeFromString<CueAttributeAssignmentDto>(inputJson)
        assertEquals(AssignmentHealth.Ok, deserialized.health)
    }

    @Test
    fun `CueAttributeAssignmentDto supports colour, position, and setting value forms`() {
        val colour = CueAttributeAssignmentDto("fixture", "hex-1", "colour", "#ff8800;w64;amber128;uv0")
        val position = CueAttributeAssignmentDto("fixture", "mover-1", "position", "128,200")
        val setting = CueAttributeAssignmentDto("fixture", "par-1", "gobo", "128")

        for (dto in listOf(colour, position, setting)) {
            val serialized = json.encodeToString(dto)
            val deserialized = json.decodeFromString<CueAttributeAssignmentDto>(serialized)
            assertEquals(dto, deserialized)
        }
    }

    // ─── NewCue ──────────────────────────────────────────────────────────

    @Test
    fun `NewCue serialization round-trips with full data`() {
        val newCue = NewCue(
            name = "Test Cue",
            layers = listOf(
                CueLayerDto(lookId = 1, targets = listOf(CueTargetDto("group", "front-wash"))),
            ),
            adHocFx = listOf(
                CueAdHocFxDto(
                    targetType = "fixture",
                    targetKey = "hex-1",
                    fxType = "Wave",
                    family = "intensity",
                    attribute = "dimmer",
                    cycle = 0.5,
                    blendMode = "ADDITIVE",
                    order = "CENTRE_OUT",
                    phaseOffset = 0.1,
                    parameters = mapOf("shape" to "PULSE", "low" to "50", "high" to "200", "attack" to "0.3"),
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
        assertEquals(emptyList(), newCue.layers)
        assertEquals(emptyList(), newCue.adHocFx)
        assertEquals(emptyList(), newCue.attributeAssignments)
    }

    @Test
    fun `NewCue round-trips attributeAssignments alongside other collections`() {
        val newCue = NewCue(
            name = "With Assignments",
            attributeAssignments = listOf(
                CueAttributeAssignmentDto("group", "front-wash", "dimmer", "180"),
                CueAttributeAssignmentDto("fixture", "hex-1", "colour", "#00ffaa"),
            ),
        )
        val serialized = json.encodeToString(newCue)
        val deserialized = json.decodeFromString<NewCue>(serialized)
        assertEquals(newCue, deserialized)
        assertEquals(2, deserialized.attributeAssignments.size)
    }

    // ─── CueDetails ─────────────────────────────────────────────────────

    @Test
    fun `CueDetails serialization round-trips correctly`() {
        val details = CueDetails(
            id = 1,
            name = "Blue Wash",
            layers = listOf(
                CueLayerDto(
                    lookId = 5,
                    targets = listOf(CueTargetDto("group", "front-wash")),
                ),
            ),
            adHocFx = emptyList(),
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
            adHocFx = emptyList(),
            canEdit = false,
            canDelete = false,
        )
        assertEquals(false, details.canEdit)
        assertEquals(false, details.canDelete)
    }

    @Test
    fun `CueLayerDto with no resolved look name`() {
        // Was `CuePresetApplicationDetail with null preset name`. Same property, same reason: the
        // DTO denormalises the library entity's name on read, and a null means "the row survives,
        // the name could not be resolved" rather than "there is no layer". Note there is no separate
        // detail type — `CueLayerDto` carries `source`, populated server-side and ignored on write.
        val detail = CueLayerDto(
            lookId = 99,
            source = null,
            targets = listOf(CueTargetDto("group", "movers")),
        )
        assertNull(detail.source)
        val deserialized = json.decodeFromString<CueLayerDto>(json.encodeToString(detail))
        assertEquals(detail, deserialized)
    }

    // ─── ApplyCueResponse ────────────────────────────────────────────────

    @Test
    fun `ApplyCueResponse serialization round-trips`() {
        val response = ApplyCueResponse(fxCount = 8, cueName = "Party Mode")
        val serialized = json.encodeToString(response)
        val deserialized = json.decodeFromString<ApplyCueResponse>(serialized)
        assertEquals(response, deserialized)
    }

    // ─── Complex scenario ────────────────────────────────────────────────

    @Test
    fun `complex cue with multiple layers and ad-hoc effects serializes correctly`() {
        val newCue = NewCue(
            name = "Full Show Look",
            layers = listOf(
                CueLayerDto(
                    lookId = 1,
                    targets = listOf(
                        CueTargetDto("group", "front-wash"),
                        CueTargetDto("group", "back-wash"),
                    ),
                ),
                CueLayerDto(
                    lookId = 2,
                    targets = listOf(
                        CueTargetDto("fixture", "mover-1"),
                        CueTargetDto("fixture", "mover-2"),
                    ),
                ),
            ),
            adHocFx = listOf(
                CueAdHocFxDto(
                    targetType = "group",
                    targetKey = "movers",
                    fxType = "Path",
                    family = "position",
                    attribute = "position",
                    cycle = 8.0,
                    blendMode = "OVERRIDE",
                    order = "NORMAL",
                    phaseOffset = 0.0,
                    elementMode = "PER_FIXTURE",
                    parameters = mapOf(
                        "shape" to "CIRCLE",
                        "panCentre" to "128",
                        "tiltCentre" to "128",
                        "panSize" to "60",
                        "tiltSize" to "40",
                    ),
                ),
                CueAdHocFxDto(
                    targetType = "fixture",
                    targetKey = "strobe-1",
                    fxType = "Wave",
                    family = "intensity",
                    attribute = "dimmer",
                    cycle = 0.25,
                    blendMode = "MAX",
                    order = "UNIFIED",
                    parameters = mapOf("shape" to "SQUARE", "low" to "0", "high" to "255", "width" to "0.1"),
                ),
            ),
        )
        val serialized = json.encodeToString(newCue)
        val deserialized = json.decodeFromString<NewCue>(serialized)
        assertEquals(newCue, deserialized)
        assertEquals(2, deserialized.layers.size)
        assertEquals(2, deserialized.adHocFx.size)
        assertEquals(2, deserialized.layers[0].targets.size)
        assertEquals(5, deserialized.adHocFx[0].parameters.size)
    }
}
