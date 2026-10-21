package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A press's family replacement and widening (apply-model plan D2, D6, P3), at the value level: a
 * map-backed registry for Looks and palettes and no database. An effect palette here is a snapshot
 * holding an effect; with no `State` it spawns nothing, which is all its replacement needs.
 */
class ProgrammerLayerPressTest {

    private val universe = Universe(0, 0)

    private class Rig(
        val store: ProgrammerStore,
        val stack: ProgrammerLayerStack,
        val looks: MutableMap<UUID, LookSnapshot>,
        val templates: MutableMap<UUID, TemplateSnapshot>,
    )

    private fun newRig(): Rig {
        val fixtures = Fixtures()
        fixtures.register {
            addController(MockDmxController(universe))
            addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
            addFixture(HexFixture(universe, "hex-2", "Hex 2", 13))
            addFixture(HexFixture(universe, "hex-3", "Hex 3", 25))
        }
        val store = ProgrammerStore()
        val looks = HashMap<UUID, LookSnapshot>()
        val templates = HashMap<UUID, TemplateSnapshot>()
        val lookRegistry = LookRegistry(fixtures = { fixtures }, loader = { looks[it] })
        val templateRegistry = TemplateRegistry(loader = { templates[it] })
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
        val stack = ProgrammerLayerStack(
            fixtures = { fixtures },
            lookRegistry = { lookRegistry },
            templateRegistry = { templateRegistry },
            engine = { engine },
            store = store,
            state = { null },
        )
        return Rig(store, stack, looks, templates)
    }

    private fun Rig.colour(name: String, hex: String): LayerSource {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        templates[uuid] = TemplateSnapshot(
            templateId = name.hashCode(), templateUuid = uuid, name = name, fadeDurationMs = null,
            rows = listOf(TemplateRowEntry(target = null, propertyName = "rgbColour", value = "$hex;policy=extract")),
        )
        return LayerSource.template(name.hashCode(), uuid, name)
    }

    private fun Rig.level(name: String, level: Int): LayerSource {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        templates[uuid] = TemplateSnapshot(
            templateId = name.hashCode(), templateUuid = uuid, name = name, fadeDurationMs = null,
            rows = listOf(TemplateRowEntry(target = null, propertyName = "dimmer", value = "pct:$level")),
        )
        return LayerSource.template(name.hashCode(), uuid, name)
    }

    private fun Rig.colourEffect(name: String): LayerSource {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        templates[uuid] = TemplateSnapshot(
            templateId = name.hashCode(), templateUuid = uuid, name = name, fadeDurationMs = null,
            rows = emptyList(),
            effect = StoredEffect(
                effectType = "ColourCycle", category = "colour", propertyName = "rgbColour",
                beatDivision = 4.0, blendMode = "OVERRIDE", distribution = "LINEAR", phaseOffset = 0.0,
                elementMode = null, elementFilter = null, stepTiming = null, parameters = emptyMap(),
                speedMasterUuid = null, rateSpeedMasterUuid = null,
            ),
        )
        return LayerSource.template(name.hashCode(), uuid, name)
    }

    private fun Rig.look(name: String): LayerSource {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        looks[uuid] = LookSnapshot(
            lookId = name.hashCode(), lookUuid = uuid, name = name,
            rows = listOf("hex-1", "hex-2", "hex-3").map {
                LookRowEntry(target = TargetRef.Fixture(it), propertyName = "rgbColour", value = "#00ff00")
            },
            effects = emptyList(),
        )
        return LayerSource.look(name.hashCode(), uuid, name)
    }

    private fun on(vararg keys: String) = keys.map { CueTargetDto("fixture", it) }

    private fun Rig.press(source: LayerSource, vararg keys: String, siblings: Set<UUID> = emptySet()) =
        stack.toggle(
            source, on(*keys),
            propertyMask = if (source.isTemplate) templates.getValue(source.uuid).familyOf()?.name else null,
            releaseSiblings = siblings,
        )

    /** Each layer as `name: heads`, bottom first. */
    private fun Rig.layers() = store.layers.map { layer -> "${layer.source.name}: ${layer.targets.joinToString(",") { it.key }}" }

    @Test
    fun `a palette pressed on another of its family's heads takes them off it, both ways`() {
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        val blue = rig.colour("Blue", "#0000ff")
        rig.press(red, "hex-1", "hex-2")

        val outcome = rig.press(blue, "hex-2")

        assertEquals(1, outcome.released, "Red gave up hex-2")
        assertEquals(listOf("Red: hex-1", "Blue: hex-2"), rig.layers())

        assertEquals(1, rig.press(red, "hex-2").released)
        assertEquals(listOf("Red: hex-1,hex-2"), rig.layers(), "Blue is gone and Red is back on hex-2")
    }

    @Test
    fun `palettes of different families stack`() {
        val rig = newRig()
        rig.press(rig.colour("Red", "#ff0000"), "hex-1")

        val outcome = rig.press(rig.level("Half", 50), "hex-1")

        assertEquals(0, outcome.released)
        assertEquals(listOf("Red: hex-1", "Half: hex-1"), rig.layers())
    }

    @Test
    fun `an effect palette over a value palette of its family leaves it, and the reverse`() {
        val rig = newRig()
        rig.press(rig.colour("Red", "#ff0000"), "hex-1")
        val cycle = rig.colourEffect("Cycle")

        assertEquals(0, rig.press(cycle, "hex-1").released, "Amber Breathe can run over Red (D6)")
        assertEquals(1, rig.press(rig.colour("Blue", "#0000ff"), "hex-1").released, "only Red is Blue's to replace")
        assertEquals(listOf("Cycle: hex-1", "Blue: hex-1"), rig.layers())
    }

    @Test
    fun `an effect palette replaces another effect palette of its family`() {
        val rig = newRig()
        rig.press(rig.colourEffect("Cycle"), "hex-1", "hex-2")

        assertEquals(1, rig.press(rig.colourEffect("Rainbow"), "hex-1").released)
        assertEquals(listOf("Cycle: hex-2", "Rainbow: hex-1"), rig.layers())
    }

    @Test
    fun `a Look neither replaces a palette nor is replaced by one`() {
        val rig = newRig()
        val green = rig.look("Green wash")
        rig.press(rig.colour("Red", "#ff0000"), "hex-1")

        assertEquals(0, rig.press(green, "hex-1").released)
        assertEquals(1, rig.press(rig.colour("Blue", "#0000ff"), "hex-1").released, "Red only")
        assertEquals(listOf("Green wash: ", "Blue: hex-1"), rig.layers(), "a Look's layer names no heads")
    }

    @Test
    fun `a solo bank still releases siblings of other kinds`() {
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        val half = rig.level("Half", 50)
        rig.press(red, "hex-1", "hex-2")

        val outcome = rig.press(half, "hex-1", siblings = setOf(red.uuid))

        assertEquals(1, outcome.released)
        assertEquals(listOf("Red: hex-2", "Half: hex-1"), rig.layers())
    }

    @Test
    fun `a solo bank takes a Look sibling off whole`() {
        val rig = newRig()
        val green = rig.look("Green wash")
        val half = rig.level("Half", 50)
        rig.press(green, "hex-1", "hex-2")

        val outcome = rig.press(half, "hex-1", siblings = setOf(green.uuid))

        assertEquals(1, outcome.released)
        assertEquals(listOf("Half: hex-1"), rig.layers(), "a Look cannot be narrowed to the heads the press left")
    }

    @Test
    fun `a palette pressed on more heads widens its layer while it is the top`() {
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        val firstId = rig.press(red, "hex-1").let { rig.store.layers.single().layerId }

        rig.press(red, "hex-2")

        assertEquals(listOf("Red: hex-1,hex-2"), rig.layers())
        assertEquals(firstId, rig.store.layers.single().layerId, "the same layer, widened")
        assertEquals("removed", rig.press(red, "hex-1", "hex-2").action, "and one press takes it off")
        assertEquals(emptyList(), rig.layers())
    }

    @Test
    fun `growing the selection and pressing again widens the same layer`() {
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        rig.press(red, "hex-1")
        val firstId = rig.store.layers.single().layerId

        assertEquals("applied", rig.press(red, "hex-1", "hex-2").action)

        assertEquals(listOf("Red: hex-1,hex-2"), rig.layers())
        assertEquals(firstId, rig.store.layers.single().layerId, "kept, so its effects on hex-1 keep their phase")
    }

    @Test
    fun `a per-fixture palette replaces only on the heads its rows name`() {
        val rig = newRig()
        rig.press(rig.colour("Red", "#ff0000"), "hex-1", "hex-3")
        val uuid = UUID.nameUUIDFromBytes("Spot".toByteArray())
        rig.templates[uuid] = TemplateSnapshot(
            templateId = 98, templateUuid = uuid, name = "Spot", fadeDurationMs = null,
            rows = listOf(
                TemplateRowEntry(target = TargetRef.Fixture("hex-1"), propertyName = "rgbColour", value = "#ffffff;policy=extract"),
                TemplateRowEntry(target = TargetRef.Fixture("hex-2"), propertyName = "rgbColour", value = "#ffffff;policy=extract"),
            ),
        )

        val outcome = rig.stack.toggle(LayerSource.template(98, uuid, "Spot"), on("hex-1", "hex-3"), propertyMask = "COLOUR")

        assertEquals(1, outcome.released)
        assertEquals(listOf("Red: hex-3", "Spot: hex-1,hex-3"), rig.layers(), "hex-3, which Spot does not reach, keeps Red")
    }

    @Test
    fun `a layer pressed on between them stops the widening`() {
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        rig.press(red, "hex-1")
        rig.press(rig.colour("Blue", "#0000ff"), "hex-3")

        rig.press(red, "hex-2")

        assertEquals(listOf("Red: hex-1", "Blue: hex-3", "Red: hex-2"), rig.layers())
    }

    @Test
    fun `a layer the press replaces outright no longer stands between`() {
        // Blue on hex-2 alone is dropped by Red's press there, so Red's own layer is the top again.
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        rig.press(red, "hex-1")
        rig.press(rig.colour("Blue", "#0000ff"), "hex-2")

        rig.press(red, "hex-2")

        assertEquals(listOf("Red: hex-1,hex-2"), rig.layers())
    }

    @Test
    fun `a retuned layer is not widened`() {
        val rig = newRig()
        val red = rig.colour("Red", "#ff0000")
        rig.press(red, "hex-1")
        rig.stack.patch(rig.store.layers.single().layerId, amount = 0.5)

        rig.press(red, "hex-2")

        assertEquals(listOf("Red: hex-1", "Red: hex-2"), rig.layers())
    }

    private fun Rig.perFixture(name: String, vararg keys: String): LayerSource {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        templates[uuid] = TemplateSnapshot(
            templateId = name.hashCode(), templateUuid = uuid, name = name, fadeDurationMs = null,
            rows = keys.map { TemplateRowEntry(target = TargetRef.Fixture(it), propertyName = "rgbColour", value = "#ffffff;policy=extract") },
        )
        return LayerSource.template(name.hashCode(), uuid, name)
    }

    @Test
    fun `a per-fixture palette pressed with no targets names its rows' heads and replaces on them`() {
        val rig = newRig()
        rig.press(rig.colour("Red", "#ff0000"), "hex-2", "hex-3")
        val focus = rig.perFixture("Focus", "hex-1", "hex-2")

        val on = rig.stack.toggle(focus, emptyList(), propertyMask = "COLOUR")

        assertEquals(1, on.released)
        assertEquals(listOf("Red: hex-3", "Focus: hex-1,hex-2"), rig.layers())
        assertEquals("removed", rig.stack.toggle(focus, emptyList(), propertyMask = "COLOUR").action, "and a second press takes it off")
    }

    @Test
    fun `a per-fixture palette's layer naming no targets is replaced on the heads its rows name`() {
        // The shape Include leaves: the palette's layer with no targets of its own.
        val rig = newRig()
        val focus = rig.perFixture("Focus", "hex-1", "hex-2")
        rig.stack.add(focus, emptyList(), propertyMask = "COLOUR")

        assertEquals(1, rig.press(rig.colour("Red", "#ff0000"), "hex-1").released)

        assertEquals(listOf("Focus: hex-2", "Red: hex-1"), rig.layers())
    }
}
