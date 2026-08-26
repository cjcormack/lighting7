package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import uk.me.cormack.lighting7.models.LayerSource

/**
 * The per-row fade's trip through [CueComposer.cook] — sweep item B1.
 *
 * The defect these pin: a template's fade was honoured when the chip was *clicked*
 * (`applyTemplateToProgrammer` passes it straight to the programmer write) and dropped when the same
 * chip was ⌥clicked into a layer, because the cook's `SourceRow` had no field for it. The fade has
 * to survive the cook for a tracked source to arrive the way an applied one does.
 */
class CueComposerRowFadeTest {

    private val universe = Universe(0, 0)
    private val cueId = 11
    private val priority = 3_002_001

    private fun twoHexes(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            createGroup<HexFixture>("front-wash") { addSpread(listOf(hex1, hex2)) }
        }
        return fixtures
    }

    private fun look(name: String, vararg rows: LookRowEntry) = LookSnapshot(
        lookId = name.hashCode(),
        lookUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
        name = name,
        rows = rows.toList(),
        effects = emptyList(),
    )

    private fun registryOf(fixtures: Fixtures, vararg looks: LookSnapshot): LookRegistry {
        val byUuid = looks.associateBy { it.lookUuid }
        return LookRegistry(fixtures = { fixtures }, loader = { byUuid[it] })
    }

    private fun template(name: String, fadeDurationMs: Long?, vararg rows: TemplateRowEntry) =
        TemplateSnapshot(
            templateId = name.hashCode(),
            templateUuid = UUID.nameUUIDFromBytes(name.toByteArray()),
            name = name,
            fadeDurationMs = fadeDurationMs,
            rows = rows.toList(),
        )

    private fun lookLayer(look: LookSnapshot, sortOrder: Int = 0, layerId: Int = look.lookId) =
        CookLayer(
            source = LayerSource.look(look.lookId, look.lookUuid, look.name),
            sortOrder = sortOrder,
            targets = listOf(CueTargetDto("group", "front-wash")),
            layerId = layerId,
        )

    private fun templateLayer(t: TemplateSnapshot, sortOrder: Int = 0, layerId: Int = 1) = CookLayer(
        source = LayerSource.template(t.templateId, t.templateUuid, t.name),
        sortOrder = sortOrder,
        targets = listOf(CueTargetDto("group", "front-wash")),
        layerId = layerId,
    )

    private fun cook(
        fixtures: Fixtures,
        registry: LookRegistry? = null,
        templates: List<TemplateSnapshot> = emptyList(),
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment> = emptyList(),
    ) = CueComposer.cook(
        fixtures = fixtures,
        cueId = cueId,
        priority = priority,
        layers = layers,
        localRows = localRows,
        lookRegistry = registry,
        resolveTemplate = { uuid -> templates.firstOrNull { it.templateUuid == uuid } },
    ).rows

    @Test
    fun `a Look row's fade reaches every fixture the row fanned out to`() {
        val warm = look(
            "Warm Wash",
            LookRowEntry(target = null, propertyName = "dimmer", value = "200", fadeDurationMs = 2_500),
        )
        val rows = cook(twoHexes(), registry = registryOf(twoHexes(), warm), layers = listOf(lookLayer(warm)))
        assertEquals(2, rows.size)
        for (row in rows) assertEquals(2_500L, row.fadeDurationMs, row.targetKey)
    }

    @Test
    fun `a Look's rows carry their own fades independently`() {
        val mixed = look(
            "Mixed",
            LookRowEntry(target = null, propertyName = "dimmer", value = "200", fadeDurationMs = 2_000),
            LookRowEntry(target = null, propertyName = "rgbColour", value = "#FF0000"),
        )
        val rows = cook(twoHexes(), registry = registryOf(twoHexes(), mixed), layers = listOf(lookLayer(mixed)))
        assertEquals(2_000L, rows.first { it.propertyName == "dimmer" }.fadeDurationMs)
        // No fade on the colour row: `null`, not the Look's other row's 2 s.
        assertNull(rows.first { it.propertyName == "rgbColour" }.fadeDurationMs)
    }

    @Test
    fun `a template's fade lives on the template and lands on all of its rows`() {
        val amber = template(
            "Amber Key",
            fadeDurationMs = 1_200,
            TemplateRowEntry(target = null, propertyName = "dimmer", value = "pct:50"),
            TemplateRowEntry(target = null, propertyName = "rgbColour", value = "#FF9D4A;policy=extract"),
        )
        val rows = cook(twoHexes(), templates = listOf(amber), layers = listOf(templateLayer(amber)))
        assertEquals(4, rows.size)
        for (row in rows) assertEquals(1_200L, row.fadeDurationMs, "${row.targetKey}.${row.propertyName}")
    }

    @Test
    fun `the winning layer's fade is the one carried, not the layer beneath it`() {
        val slow = look(
            "Slow",
            LookRowEntry(target = null, propertyName = "dimmer", value = "100", fadeDurationMs = 5_000),
        )
        val snappy = look(
            "Snappy",
            LookRowEntry(target = null, propertyName = "dimmer", value = "255"),
        )
        val fixtures = twoHexes()
        val rows = cook(
            fixtures,
            registry = registryOf(fixtures, slow, snappy),
            layers = listOf(
                lookLayer(slow, sortOrder = 0, layerId = 1),
                lookLayer(snappy, sortOrder = 1, layerId = 2),
            ),
        )
        for (row in rows) {
            assertEquals("Snappy", row.layerWinner?.source?.name)
            assertNull(row.fadeDurationMs, row.targetKey)
        }
    }

    @Test
    fun `a local row overlaying a layer takes over the fade as well as the value`() {
        val slow = look(
            "Slow",
            LookRowEntry(
                target = TargetRef.of("fixture", "hex-1"),
                propertyName = "dimmer",
                value = "100",
                fadeDurationMs = 5_000,
            ),
        )
        val fixtures = twoHexes()
        val local = CueAssignmentResolver.Assignment(
            cueId = cueId,
            priority = priority,
            fadeWeight = 1.0,
            targetKey = "hex-1",
            targetIsGroup = false,
            propertyName = "dimmer",
            category = PropertyCategory.DIMMER,
            compositionOverride = CompositionRule.UNSET,
            value = CueAssignmentResolver.PropertyValue.Slider(255u),
            fadeDurationMs = 750,
        )
        val rows = cook(
            fixtures,
            registry = registryOf(fixtures, slow),
            layers = listOf(lookLayer(slow)),
            localRows = listOf(local),
        )
        val row = rows.single { it.targetKey == "hex-1" && it.propertyName == "dimmer" }
        assertEquals(750L, row.fadeDurationMs)
        assertNull(row.layerWinner)
    }
}
