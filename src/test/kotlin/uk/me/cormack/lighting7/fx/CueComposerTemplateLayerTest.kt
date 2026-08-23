package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A **template** layer through [CueComposer.cook] — the same cook a GO runs.
 *
 * The point of testing it here rather than only through the resolver: a template layer has to behave
 * like a layer in every respect that is not about resolution (targets, mask, blend, order, stomp,
 * and losing to the cue's own rows), and the only way to know that is to cook one beside a Look.
 */
class CueComposerTemplateLayerTest {

    private val universe = Universe(0, 0)
    private val cueId = 7
    private val priority = 3_002_001

    /** A hex (RGBWA emitters) and a MAC 250 (a colour *wheel*) — two ways to be "amber". */
    private fun mixedRig(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            addFixture(MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", firstChannel = 40))
        }
        return fixtures
    }

    private fun template(name: String, vararg rows: TemplateRowEntry) = TemplateSnapshot(
        templateId = name.hashCode(),
        templateUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        fadeDurationMs = null,
        rows = rows.toList(),
    )

    private fun genericRow(propertyName: String, value: String) =
        TemplateRowEntry(target = null, propertyName = propertyName, value = value)

    private fun layerFor(
        snapshot: TemplateSnapshot,
        sortOrder: Int = 0,
        targets: List<CueTargetDto> = emptyList(),
        propertyMask: String? = null,
        layerId: Int = 1,
    ) = CookLayer(
        source = LayerSource.template(snapshot.templateId, snapshot.templateUuid, snapshot.name),
        sortOrder = sortOrder,
        targets = targets,
        propertyMask = propertyMask,
        layerId = layerId,
    )

    private fun cook(
        fixtures: Fixtures,
        templates: List<TemplateSnapshot>,
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment> = emptyList(),
    ): CookResult {
        val byUuid = templates.associateBy { it.templateUuid }
        return CueComposer.cook(
            fixtures = fixtures,
            cueId = cueId,
            priority = priority,
            layers = layers,
            localRows = localRows,
            resolveTemplate = { byUuid[it] },
        )
    }

    @Test
    fun `one colour template resolves differently on each head and both are amber`() {
        // The session's whole promise, at the level the rig sees it: one row, two heads, two
        // completely different channel shapes — RGBW emitters and a wheel slot.
        val amber = template("Amber Key", genericRow("rgbColour", "#FF9D4A;policy=extract"))
        val rows = cook(
            mixedRig(),
            listOf(amber),
            listOf(layerFor(amber, targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "mac-1")))),
        ).rows

        val onHex = rows.single { it.targetKey == "hex-1" }
        val hexColour = assertIs<CueAssignmentResolver.PropertyValue.Colour>(onHex.value)
        assertEquals(74u.toUByte(), hexColour.value.white, "the neutral part went to the white emitter")

        val onMac = rows.single { it.targetKey == "mac-1" }
        assertIs<CueAssignmentResolver.PropertyValue.Setting>(onMac.value)
        assertEquals("colour", onMac.propertyName, "the wheel carries it, not `rgbColour`")
    }

    @Test
    fun `a template layer names its winner like any other layer`() {
        val amber = template("Amber Key", genericRow("rgbColour", "#FF9D4A;policy=extract"))
        val rows = cook(
            mixedRig(),
            listOf(amber),
            listOf(layerFor(amber, targets = listOf(CueTargetDto("fixture", "hex-1")))),
        ).rows

        val winner = rows.single { it.targetKey == "hex-1" }.layerWinner
        assertEquals("Amber Key", winner?.source?.name)
        assertEquals(LayerSourceKind.TEMPLATE, winner?.source?.kind, "provenance says which kind, so the desk can label it")
    }

    @Test
    fun `a layer with no targets asserts nothing, because a generic row has nowhere to land`() {
        val amber = template("Amber Key", genericRow("rgbColour", "#FF9D4A;policy=extract"))
        val rows = cook(mixedRig(), listOf(amber), listOf(layerFor(amber, targets = emptyList()))).rows
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a head that cannot take the intent drops out without disturbing the others`() {
        // A position template pointed at a rig that is half fixed heads. The hex contributes nothing
        // and says nothing; the MAC still moves. This is the normal case for a template, which is why
        // the cook logs it at debug rather than warn.
        val focus = template("Downstage", genericRow("position", "deg:270,128"))
        val rows = cook(
            mixedRig(),
            listOf(focus),
            listOf(layerFor(focus, targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "mac-1")))),
        ).rows

        assertEquals(listOf("mac-1"), rows.map { it.targetKey })
        assertIs<CueAssignmentResolver.PropertyValue.Position>(rows.single().value)
    }

    @Test
    fun `a per-fixture template row only reaches the head it names`() {
        val focus = template(
            "Downstage",
            TemplateRowEntry(TargetRef.Fixture("mac-1"), "position", "deg:270,128"),
        )
        val rows = cook(
            mixedRig(),
            listOf(focus),
            listOf(layerFor(focus, targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "mac-1")))),
        ).rows
        assertEquals(listOf("mac-1"), rows.map { it.targetKey })
    }

    @Test
    fun `a mask still filters a template layer`() {
        val amber = template("Amber Key", genericRow("rgbColour", "#FF9D4A;policy=extract"))
        val rows = cook(
            mixedRig(),
            listOf(amber),
            listOf(
                layerFor(
                    amber,
                    targets = listOf(CueTargetDto("fixture", "hex-1")),
                    propertyMask = "INTENSITY",
                )
            ),
        ).rows
        assertTrue(rows.isEmpty(), "a colour row masked to intensity asserts nothing")
    }

    @Test
    fun `a template layer loses to the cue's own local row, like every layer does`() {
        val amber = template("Amber Key", genericRow("dimmer", "pct:50"))
        val local = CueAssignmentResolver.Assignment(
            cueId = cueId,
            priority = priority,
            fadeWeight = 1.0,
            targetKey = "hex-1",
            targetIsGroup = false,
            propertyName = "dimmer",
            category = uk.me.cormack.lighting7.fixture.PropertyCategory.DIMMER,
            compositionOverride = uk.me.cormack.lighting7.fixture.CompositionRule.UNSET,
            value = CueAssignmentResolver.PropertyValue.Slider(255u),
        )
        val rows = cook(
            mixedRig(),
            listOf(amber),
            listOf(layerFor(amber, targets = listOf(CueTargetDto("fixture", "hex-1")))),
            localRows = listOf(local),
        ).rows

        val row = rows.single { it.targetKey == "hex-1" && it.propertyName == "dimmer" }
        assertEquals(255u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Slider>(row.value).value)
        assertEquals(null, row.layerWinner, "a local row belongs to no layer")
    }

    @Test
    fun `a later template layer beats an earlier one on the same key`() {
        val half = template("Half Up", genericRow("dimmer", "pct:50"))
        val full = template("Full", genericRow("dimmer", "pct:100"))
        val rows = cook(
            mixedRig(),
            listOf(half, full),
            listOf(
                layerFor(half, sortOrder = 0, targets = listOf(CueTargetDto("fixture", "hex-1")), layerId = 1),
                layerFor(full, sortOrder = 1, targets = listOf(CueTargetDto("fixture", "hex-1")), layerId = 2),
            ),
        ).rows
        val row = rows.single { it.propertyName == "dimmer" }
        assertEquals(255u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Slider>(row.value).value)
        assertEquals("Full", row.layerWinner?.source?.name)
    }

    @Test
    fun `an unreadable template drops its layer without disturbing the rest`() {
        val known = template("Half Up", genericRow("dimmer", "pct:50"))
        val missing = template("Gone", genericRow("dimmer", "pct:100"))
        val rows = cook(
            mixedRig(),
            // `missing` is deliberately absent from the resolver's map.
            listOf(known),
            listOf(
                layerFor(missing, sortOrder = 0, targets = listOf(CueTargetDto("fixture", "hex-1")), layerId = 1),
                layerFor(known, sortOrder = 1, targets = listOf(CueTargetDto("fixture", "hex-1")), layerId = 2),
            ),
        ).rows
        val row = rows.single { it.propertyName == "dimmer" }
        assertEquals(128u.toUByte(), assertIs<CueAssignmentResolver.PropertyValue.Slider>(row.value).value)
    }

    @Test
    fun `a template layer contributes no effects, because a template holds none`() {
        val amber = template("Amber Key", genericRow("rgbColour", "#FF9D4A;policy=extract"))
        val effects = CueComposer.cookEffects(
            fixtures = mixedRig(),
            cueId = cueId,
            layers = listOf(layerFor(amber, targets = listOf(CueTargetDto("fixture", "hex-1")))),
            lookRegistry = null,
        )
        assertTrue(effects.isEmpty())
    }
}
