package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.fx.TemplateRegistry
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The programmer's Look-layer stack, at the value level.
 *
 * Effects need a real `State` (the FX registry and target resolution both take one) and are covered
 * in `ProgrammerLayerStackEffectsTest`; everything here runs against a map-backed [LookRegistry] and
 * no database, which is what keeps the composition rules cheap to assert.
 */
class ProgrammerLayerStackTest {

    private val universe = Universe(0, 0)

    private class Rig(
        val fixtures: Fixtures,
        val store: ProgrammerStore,
        val engine: FxEngine,
        val stack: ProgrammerLayerStack,
        val looks: MutableMap<UUID, LookSnapshot>,
        val registry: LookRegistry,
    )

    private fun newRig(): Rig {
        val fixtures = Fixtures()
        val controller = MockDmxController(universe)
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
            addFixture(HexFixture(universe, "hex-2", "Hex 2", 13))
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
            // Nothing in these tests layers a template, and a `TemplateRegistry` with a loader that
            // answers null is exactly what "there are none" means to the cook.
            templateRegistry = { TemplateRegistry(loader = { null }) },
            engine = { engine },
            store = store,
            // No effects in these looks, so nothing ever reaches the spawn path.
            state = { null },
        )
        return Rig(fixtures, store, engine, stack, looks, registry)
    }

    /**
     * Register a Look holding a bound dimmer row for **every** head in the rig, and return its uuid.
     *
     * Covering the whole rig rather than only the head a layer names is what keeps the
     * layer-targets-filter-bound-rows rule under test: a Look row is always bound (sweep item B6),
     * so if the layer's target set did nothing, every one of these layers would land on both heads.
     */
    private fun Rig.look(name: String, dimmer: Int): UUID {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        looks[uuid] = LookSnapshot(
            lookId = name.hashCode(),
            lookUuid = uuid,
            name = name,
            rows = rowsForWholeRig(dimmer),
            effects = emptyList(),
        )
        return uuid
    }

    private fun Rig.rowsForWholeRig(dimmer: Int) = fixtures.fixtures.map {
        LookRowEntry(target = TargetRef.Fixture(it.key), propertyName = "dimmer", value = dimmer.toString())
    }

    private fun Rig.add(name: String, dimmer: Int, vararg fixtureKeys: String) =
        stack.add(
            source = LayerSource.look(name.hashCode(), look(name, dimmer), name),
            targets = fixtureKeys.map { CueTargetDto("fixture", it) },
        ).first

    /** The programmer's winning value for a key, as an Int, or null when it holds nothing. */
    private fun Rig.valueOf(fixtureKey: String, propertyName: String = "dimmer"): Int? {
        val slot = store.get(fixtureKey, propertyName) ?: return null
        return (slot.value.resolved as CueAssignmentResolver.PropertyValue.Slider).value.toInt()
    }

    // ─── Materialising ──────────────────────────────────────────────────

    @Test
    fun `adding a layer materialises its values into the store`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        assertEquals(200, rig.valueOf("hex-1"))
        assertEquals(ProgrammerOwner.LAYERS, rig.store.get("hex-1", "dimmer")!!.owner)
        assertNull(rig.valueOf("hex-2"), "a layer asserts only over its own targets")
    }

    @Test
    fun `a later layer wins the keys it shares with an earlier one`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1", "hex-2")
        rig.add("Cool", 40, "hex-1")

        assertEquals(40, rig.valueOf("hex-1"), "later layer wins")
        assertEquals(200, rig.valueOf("hex-2"), "and leaves the key it doesn't cover alone")
    }

    @Test
    fun `removing a layer releases the keys nothing else covers`() {
        val rig = newRig()
        val warm = rig.add("Warm", 200, "hex-1", "hex-2")
        rig.add("Cool", 40, "hex-1")

        rig.stack.remove(warm.layerId)

        assertEquals(40, rig.valueOf("hex-1"), "still covered by the surviving layer")
        assertNull(rig.valueOf("hex-2"), "released")
    }

    @Test
    fun `reordering changes which layer wins, without either being re-added`() {
        val rig = newRig()
        val warm = rig.add("Warm", 200, "hex-1")
        rig.add("Cool", 40, "hex-1")
        assertEquals(40, rig.valueOf("hex-1"))

        rig.stack.move(warm.layerId, 1)

        assertEquals(200, rig.valueOf("hex-1"), "Warm is now on top")
        assertEquals(listOf(0, 1), rig.store.layers.map { it.sortOrder }, "densely renumbered")
    }

    @Test
    fun `disabling a layer takes its contribution out, and enabling puts it back`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")
        val cool = rig.add("Cool", 40, "hex-1")

        rig.stack.patch(cool.layerId, enabled = false)
        assertEquals(200, rig.valueOf("hex-1"))

        rig.stack.patch(cool.layerId, enabled = true)
        assertEquals(40, rig.valueOf("hex-1"))
    }

    @Test
    fun `amount zero contributes nothing rather than contributing zero`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")
        val cool = rig.add("Cool", 40, "hex-1")

        rig.stack.patch(cool.layerId, amount = 0.0)

        assertEquals(200, rig.valueOf("hex-1"), "the layer beneath shows through")
    }

    // ─── Against the operator's own writes ──────────────────────────────

    @Test
    fun `a local write beats a layer added afterwards`() {
        // The rule the whole seq/tail-insertion design exists for, at stack level: the operator's
        // busk is not disturbed by adding a layer under it.
        val rig = newRig()
        rig.store.putValue(
            ProgrammerOwner.WEB, "hex-1", "dimmer",
            ProgrammerValue.Hard(CueAssignmentResolver.PropertyValue.Slider(90u)),
        )

        rig.add("Warm", 200, "hex-1")

        assertEquals(90, rig.valueOf("hex-1"))
    }

    @Test
    fun `layer slots are untouched, so Record captures the stack rather than flattening it`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        assertEquals(false, rig.store.get("hex-1", "dimmer")!!.touched)
    }

    // ─── Look edits, and Clear ──────────────────────────────────────────

    @Test
    fun `editing a Look moves the layers that name it, and only those`() {
        val rig = newRig()
        val warmUuid = rig.add("Warm", 200, "hex-1").source.uuid
        rig.add("Cool", 40, "hex-2")

        // The registry is map-backed, so an "edit" is a replacement plus an invalidation — the same
        // two steps `republishForLookEdit` performs, in the same order. Without the invalidate the
        // recook reads the cached expansion and nothing moves, which is precisely the stale-cache
        // failure that path's step 1 exists to prevent.
        rig.looks[warmUuid] = rig.looks.getValue(warmUuid).copy(
            rows = rig.rowsForWholeRig(77),
        )
        rig.registry.invalidate(warmUuid)
        val moved = rig.stack.recookIfReferences(warmUuid)

        assertEquals(77, rig.valueOf("hex-1"))
        assertEquals(40, rig.valueOf("hex-2"), "untouched")
        assertEquals(
            setOf(CueAssignmentResolver.Key.fixture("hex-1", "dimmer")), moved,
            "only the moved key is handed back to republish",
        )
    }

    @Test
    fun `a Look edit naming no layer in the stack does nothing at all`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        val moved = rig.stack.recookIfReferences(UUID.nameUUIDFromBytes("Unrelated".toByteArray()))

        assertTrue(moved.isEmpty())
        assertEquals(200, rig.valueOf("hex-1"))
    }

    @Test
    fun `clearing the programmer empties the stack, so nothing recooks itself back on`() {
        // The reason the layer list lives in ProgrammerStore: if Clear swept the slots but left the
        // list, the next Look edit anywhere would put the whole look back on stage by itself.
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        rig.store.clearAll()

        assertTrue(rig.store.layers.isEmpty())
        assertNull(rig.valueOf("hex-1"))
    }

    @Test
    fun `reset drops the stack without publishing`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        rig.stack.reset()

        assertTrue(rig.store.layers.isEmpty())
        // Deliberately still materialised: `clearProgrammerCompletely` sweeps the store immediately
        // afterwards, and releasing here as well would double-release.
        assertEquals(200, rig.valueOf("hex-1"))
    }

    // ─── The pad gesture ────────────────────────────────────────────────

    @Test
    fun `toggle adds the layer, then removes the one it added`() {
        val rig = newRig()
        val uuid = rig.look("Warm", 200)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        val applied = rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), targets)
        assertEquals("applied", applied.action)
        assertEquals(200, rig.valueOf("hex-1"))

        val removed = rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), targets)
        assertEquals("removed", removed.action)
        assertNull(rig.valueOf("hex-1"))
    }

    @Test
    fun `the same Look on two target sets toggles as two independent pads`() {
        // What "already on" has to mean for a pad: same Look *and* same targets. Matching on the
        // Look alone would make the second pad turn the first one off.
        val rig = newRig()
        val uuid = rig.look("Warm", 200)

        rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), listOf(CueTargetDto("fixture", "hex-1")))
        rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), listOf(CueTargetDto("fixture", "hex-2")))
        assertEquals(2, rig.store.layers.size)

        rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), listOf(CueTargetDto("fixture", "hex-1")))

        assertEquals(1, rig.store.layers.size)
        assertNull(rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"), "the other pad is still on")
    }

    @Test
    fun `toggle matches the same targets in a different order`() {
        // A7: the same fixture set re-sent in a different order (e.g. re-derived from a Set on the
        // client) must still toggle the existing layer off, not stack a second one on top.
        val rig = newRig()
        val uuid = rig.look("Warm", 200)
        val source = LayerSource.look("Warm".hashCode(), uuid, "Warm")
        val targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2"))
        val reordered = listOf(CueTargetDto("fixture", "hex-2"), CueTargetDto("fixture", "hex-1"))

        val applied = rig.stack.toggle(source, targets)
        assertEquals("applied", applied.action)
        assertEquals(1, rig.store.layers.size)

        val removed = rig.stack.toggle(source, reordered)
        assertEquals("removed", removed.action)
        assertTrue(rig.store.layers.isEmpty())
    }

    /**
     * A pad must keep working after the record behind it is renamed.
     *
     * `LayerSource` is a `data class`, so its equality includes `name` — and a name is mutable.
     * Matching on the whole value meant a rename left the pad unable to turn its own layer off:
     * the stored layer held the old name, the press carried the new one, so "already on" said no
     * and a second layer stacked on top. The first was then removable only by id from the FX
     * sheet. `toggle` matches on `source.uuid`, which is what identity actually means here.
     */
    @Test
    fun `a renamed record's pad still toggles its own layer off`() {
        val rig = newRig()
        val uuid = rig.look("Warm", 200)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        rig.stack.toggle(LayerSource.look(1, uuid, "Warm"), targets)
        assertEquals(1, rig.store.layers.size)

        // The same record, renamed — same id and uuid, new name.
        val renamed = rig.stack.toggle(LayerSource.look(1, uuid, "Warm Wash"), targets)

        assertEquals("removed", renamed.action, "a rename must not make the pad stack a second layer")
        assertTrue(rig.store.layers.isEmpty())
        assertNull(rig.valueOf("hex-1"))
    }

    /**
     * The int PK is *not* enough on its own, which is why the match is not simply `source.id`: a
     * Look and a template can share one, and their pads must not cancel each other.
     */
    @Test
    fun `a Look and a template sharing an int PK toggle independently`() {
        val rig = newRig()
        val uuid = rig.look("Warm", 200)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        rig.stack.toggle(LayerSource.look(7, uuid, "Warm"), targets)
        // Same id, different record. The template resolves to null here (the rig has no templates),
        // so it contributes no values — but it must still take a layer of its own.
        rig.stack.toggle(
            LayerSource.template(7, UUID.nameUUIDFromBytes("t7".toByteArray()), "Amber Key"),
            targets,
        )

        assertEquals(2, rig.store.layers.size, "sharing an int PK must not collapse two pads into one")
        assertEquals(200, rig.valueOf("hex-1"), "the Look's pad is still on")
    }

    // ─── Template groups: exclusivity ───────────────────────────────────

    /**
     * Two "templates" that are really Looks under the hood: the rig has no template registry, and
     * exclusivity is keyed on `source.uuid` alone, so a Look-backed source with a template's shape
     * exercises exactly the same path while letting [valueOf] prove which layer won.
     */
    private fun Rig.groupedSource(name: String, dimmer: Int): LayerSource =
        LayerSource.look(name.hashCode(), look(name, dimmer), name)

    @Test
    fun `toggling with siblings releases a sibling on the same targets in one mutation`() {
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        rig.stack.toggle(amber, targets, releaseSiblings = setOf(blue.uuid))
        assertEquals(1, rig.store.layers.size)

        // Every `layerState` frame the press produces. `Unconfined` so `tryEmit` delivers to the
        // collector synchronously, on the toggling thread — the flow replays one, which is the
        // Amber stack, so the count is taken after subscription has drained it.
        val frames = mutableListOf<List<ProgrammerLayer>>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            rig.store.layersFlow.collect { frames += it }
        }
        frames.clear()

        val outcome = rig.stack.toggle(blue, targets, releaseSiblings = setOf(amber.uuid))
        collector.cancel()

        assertEquals("applied", outcome.action)
        assertEquals(1, outcome.released, "Amber's layer on the same targets is released")
        assertEquals(1, rig.store.layers.size, "release and add are one stack, not two")
        assertEquals(blue.uuid, rig.store.layers.single().source.uuid)
        assertEquals(100, rig.valueOf("hex-1"), "the new sibling's value wins outright")
        assertEquals(1, frames.size, "release and add must be one store mutation, so one layerState frame")
        assertEquals(listOf(blue.uuid), frames.single().map { it.source.uuid })
    }

    @Test
    fun `a sibling on a different target set survives`() {
        // Same-target-set only: Amber on hex-1 and Blue on hex-2 are two pads on two rigs.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.toggle(amber, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, listOf(CueTargetDto("fixture", "hex-2")), releaseSiblings = setOf(amber.uuid))

        assertEquals(0, outcome.released)
        assertEquals(2, rig.store.layers.size)
        assertEquals(200, rig.valueOf("hex-1"), "Amber on hex-1 is untouched")
        assertEquals(100, rig.valueOf("hex-2"))
    }

    @Test
    fun `turning a grouped pad off leaves its siblings alone`() {
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)
        val hex1 = listOf(CueTargetDto("fixture", "hex-1"))
        val hex2 = listOf(CueTargetDto("fixture", "hex-2"))

        rig.stack.toggle(amber, hex2, releaseSiblings = setOf(blue.uuid))
        rig.stack.toggle(blue, hex1, releaseSiblings = setOf(amber.uuid))
        assertEquals(2, rig.store.layers.size)

        // Press Blue again on hex-1: off. Amber on hex-2 is a sibling, but a remove never releases.
        val outcome = rig.stack.toggle(blue, hex1, releaseSiblings = setOf(amber.uuid))

        assertEquals("removed", outcome.action)
        assertEquals(0, outcome.released)
        assertEquals(1, rig.store.layers.size)
        assertEquals(200, rig.valueOf("hex-2"), "the sibling is still on")
    }

    @Test
    fun `siblings never release a layer whose uuid is not named`() {
        // The match is on uuid, the same identity the pad itself uses — so a Look sharing a
        // template's int PK, or an unrelated template on the same targets, is never collateral.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val stranger = rig.groupedSource("Stranger", 50)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        rig.stack.toggle(stranger, targets)
        val outcome = rig.stack.toggle(amber, targets, releaseSiblings = setOf(UUID.nameUUIDFromBytes("blue".toByteArray())))

        assertEquals(0, outcome.released)
        assertEquals(2, rig.store.layers.size, "an unnamed layer on the same targets is left alone")
    }
}
