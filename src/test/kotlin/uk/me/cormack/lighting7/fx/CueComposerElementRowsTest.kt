package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A Look's **element row** composes onto its cell (`FU-LOOK-ELEMENT-ROWS`), and a layer whose
 * targets are cells lands a template's generic rows on exactly those cells. The accumulator is
 * keyed by target key, so a cell's row lives under its element key, beside its parent's.
 */
class CueComposerElementRowsTest {

    private val universe = Universe(0, 0)
    private val cueId = 9
    private val priority = 3_002_001
    private val bar = CueTargetDto("fixture", "bar-1")
    private fun cell(i: Int) = CueTargetDto("fixture", "bar-1.pixel-$i")

    private fun rig(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar-1", "Bar 1", 1))
            addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 100))
        }
        return fixtures
    }

    private fun elementRow(index: Int, value: String) = LookRowEntry(
        target = TargetRef.Fixture("bar-1"),
        propertyName = "rgbColour",
        value = value,
        elementKey = "bar-1.pixel-$index",
    )

    private fun look(vararg rows: LookRowEntry) = LookSnapshot(
        lookId = 5,
        lookUuid = UUID.nameUUIDFromBytes("Cells".toByteArray()),
        name = "Cells",
        rows = rows.toList(),
        effects = emptyList(),
    )

    private fun template(vararg rows: TemplateRowEntry) = TemplateSnapshot(
        templateId = 6,
        templateUuid = UUID.nameUUIDFromBytes("Amber".toByteArray()),
        name = "Amber",
        fadeDurationMs = null,
        rows = rows.toList(),
    )

    private fun lookLayer(look: LookSnapshot, targets: List<CueTargetDto>) = CookLayer(
        source = LayerSource.look(look.lookId, look.lookUuid, look.name),
        sortOrder = 0,
        targets = targets,
        layerId = 1,
    )

    private fun templateLayer(template: TemplateSnapshot, targets: List<CueTargetDto>) = CookLayer(
        source = LayerSource.template(template.templateId, template.templateUuid, template.name),
        sortOrder = 0,
        targets = targets,
        layerId = 1,
    )

    private fun cook(
        fixtures: Fixtures,
        layer: CookLayer,
        look: LookSnapshot? = null,
        template: TemplateSnapshot? = null,
    ): CookResult = CueComposer.cook(
        fixtures = fixtures,
        cueId = cueId,
        priority = priority,
        layers = listOf(layer),
        localRows = emptyList(),
        resolveLook = { if (it == look?.lookUuid) look else null },
        resolveTemplate = { if (it == template?.templateUuid) template else null },
    )

    private fun colourAt(rows: List<CueAssignmentResolver.Assignment>, key: String): ExtendedColour {
        val row = rows.single { it.targetKey == key && it.propertyName == "rgbColour" }
        assertTrue(!row.targetIsGroup)
        return assertIs<CueAssignmentResolver.PropertyValue.Colour>(row.value).value
    }

    @Test
    fun `an element row composes onto its cell and nothing else`() {
        val look = look(elementRow(2, "#ff0000"), elementRow(5, "#00ff00"))
        val result = cook(rig(), lookLayer(look, targets = emptyList()), look = look)

        assertEquals(setOf("bar-1.pixel-2", "bar-1.pixel-5"), result.rows.map { it.targetKey }.toSet())
        assertEquals(Color.RED, colourAt(result.rows, "bar-1.pixel-2").color)
        assertEquals(Color.GREEN, colourAt(result.rows, "bar-1.pixel-5").color)
        assertTrue(FxEngine.PropertyKey("bar-1.pixel-2", "rgbColour") in result.assertedKeys, "asserted under the cell's key")
    }

    @Test
    fun `a layer on the whole bar carries the cells' rows`() {
        val look = look(elementRow(2, "#ff0000"), elementRow(5, "#00ff00"))
        val result = cook(rig(), lookLayer(look, targets = listOf(bar)), look = look)
        assertEquals(setOf("bar-1.pixel-2", "bar-1.pixel-5"), result.rows.map { it.targetKey }.toSet())
    }

    @Test
    fun `a layer on one cell filters the rows to that cell`() {
        val look = look(elementRow(2, "#ff0000"), elementRow(5, "#00ff00"))
        val result = cook(rig(), lookLayer(look, targets = listOf(cell(2))), look = look)
        assertEquals(listOf("bar-1.pixel-2"), result.rows.map { it.targetKey })
    }

    @Test
    fun `a layer on another fixture drops the cells' rows`() {
        val look = look(elementRow(2, "#ff0000"))
        val result = cook(rig(), lookLayer(look, targets = listOf(CueTargetDto("fixture", "hex-1"))), look = look)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `a generic template row fans over cell targets and resolves on the cell's own emitters`() {
        val amber = template(TemplateRowEntry(target = null, propertyName = "rgbColour", value = "#FF9D4A;policy=extract"))
        val result = cook(rig(), templateLayer(amber, targets = listOf(cell(2), cell(5))), template = amber)

        assertEquals(setOf("bar-1.pixel-2", "bar-1.pixel-5"), result.rows.map { it.targetKey }.toSet())
        assertEquals(74u.toUByte(), colourAt(result.rows, "bar-1.pixel-2").white, "the neutral part went to the pixel's white")
    }

    @Test
    fun `a bound row spelled as a cell target lands under a layer on its parent`() {
        // The write boundary accepts `{fixture, <elementKey>}` with no elementKey — the shape a
        // cell target has everywhere else — and the parent covers it like an element row.
        val look = look(LookRowEntry(target = TargetRef.Fixture("bar-1.pixel-2"), propertyName = "rgbColour", value = "#ff0000"))
        val result = cook(rig(), lookLayer(look, targets = listOf(bar)), look = look)
        assertEquals(listOf("bar-1.pixel-2"), result.rows.map { it.targetKey })
        assertTrue(cook(rig(), lookLayer(look, targets = listOf(CueTargetDto("fixture", "hex-1"))), look = look).rows.isEmpty())
    }
}
