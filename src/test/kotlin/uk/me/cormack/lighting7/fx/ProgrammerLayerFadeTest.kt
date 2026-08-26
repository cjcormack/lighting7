package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-row fades through the programmer's layer stack — the half of sweep item B1 the item text
 * actually names: clicking a template chip applied literals *with* the template's fade, and
 * ⌥clicking the same chip added a tracking layer that snapped.
 *
 * The line these pin is arrival-versus-edit. `amount` is folded into the cooked value, so honouring
 * the row's fade on a `patch` would restart the ramp on every drag event of an Amount slider and the
 * rig would never track it.
 */
class ProgrammerLayerFadeTest {

    private val universe = Universe(0, 0)

    private class Rig(
        val controller: MockDmxController,
        val stack: ProgrammerLayerStack,
        val looks: MutableMap<UUID, LookSnapshot>,
    )

    private fun newRig(): Rig {
        val fixtures = Fixtures()
        val controller = MockDmxController(universe)
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
        }
        val store = ProgrammerStore()
        val looks = HashMap<UUID, LookSnapshot>()
        val registry = LookRegistry(fixtures = { fixtures }, loader = { looks[it] })
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
        val stack = ProgrammerLayerStack(
            fixtures = { fixtures },
            lookRegistry = { registry },
            templateRegistry = { TemplateRegistry(loader = { null }) },
            engine = { engine },
            store = store,
            state = { null },
        )
        return Rig(controller, stack, looks)
    }

    /** A Look of one deferred dimmer row, optionally asking for a fade. */
    private fun Rig.source(name: String, dimmer: Int, fadeDurationMs: Long?): LayerSource {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        looks[uuid] = LookSnapshot(
            lookId = name.hashCode(),
            lookUuid = uuid,
            name = name,
            rows = listOf(
                LookRowEntry(
                    target = null,
                    propertyName = "dimmer",
                    value = dimmer.toString(),
                    fadeDurationMs = fadeDurationMs,
                ),
            ),
            effects = emptyList(),
        )
        return LayerSource.look(name.hashCode(), uuid, name)
    }

    private val onHex1 = listOf(CueTargetDto("fixture", "hex-1"))

    @Test
    fun `adding a layer ramps the channel at the row's own fade`() {
        val rig = newRig()
        rig.stack.add(source = rig.source("Warm", 200, fadeDurationMs = 1_800), targets = onHex1)
        assertEquals(1_800L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `an explicit caller fade overrides the row's stored default`() {
        val rig = newRig()
        // Include's own fadeMs is the operator's instruction, the way `request.fadeMs` overrides
        // `snapshot.fadeDurationMs` in `POST /templates/{id}/apply`.
        rig.stack.add(
            source = rig.source("Warm", 200, fadeDurationMs = 1_800),
            targets = onHex1,
            fadeMs = 400,
        )
        assertEquals(400L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `dragging a layer's amount does not restart the row's ramp`() {
        val rig = newRig()
        val layer = rig.stack.add(
            source = rig.source("Warm", 200, fadeDurationMs = 2_000),
            targets = onHex1,
        ).first
        assertEquals(2_000L, rig.controller.changesTo(1).last().fadeMs)

        // Three drag events on the Amount slider. Each folds into the cooked value, so each moves
        // the channel; none may re-ramp, or the rig lags the slider by 2 s and keeps crawling after
        // release.
        for (amount in listOf(0.75, 0.5, 0.25)) {
            rig.stack.patch(layer.layerId, amount = amount)
            assertEquals(
                0L, rig.controller.changesTo(1).last().fadeMs,
                "an amount drag is an edit, not an arrival",
            )
        }
    }

    @Test
    fun `removing a layer snaps rather than fading out at the row's rate`() {
        val rig = newRig()
        val layer = rig.stack.add(
            source = rig.source("Warm", 200, fadeDurationMs = 2_000),
            targets = onHex1,
        ).first
        rig.stack.remove(layer.layerId)
        // The key releases to whatever sits underneath — here Layer 5 zero — which the departing
        // row has no business timing.
        assertEquals(0L, rig.controller.changesTo(1).last().fadeMs)
    }

    @Test
    fun `a Look with no row fade still snaps on arrival`() {
        val rig = newRig()
        rig.stack.add(source = rig.source("Warm", 200, fadeDurationMs = null), targets = onHex1)
        assertTrue(rig.controller.changesTo(1).all { it.fadeMs == 0L })
    }
}
