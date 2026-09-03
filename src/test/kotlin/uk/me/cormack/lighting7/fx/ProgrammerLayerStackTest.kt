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
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", 13))
            // Both heads, so a `{group: wash}` target and the two `{fixture: …}` ones are the same
            // selection written two ways — which is what the coverage rules are about.
            createGroup<HexFixture>("wash") { addSpread(listOf(hex1, hex2)) }
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
    fun `a sibling on a disjoint target set survives`() {
        // Amber on hex-1 and Blue on hex-2 are two pads on two rigs — the press names neither of
        // the other's targets, so there is nothing to take away.
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
    fun `a press over a sibling's whole target set takes the sibling off`() {
        // The bug the whole-set rule shipped with: Amber on hex-1, then Blue on hex-1 *and* hex-2.
        // Blue's layer sat on top so the stage looked right, but Amber's layer still covered hex-1
        // — which is what lit its pad — and releasing Blue would have popped Amber back.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.toggle(amber, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(
            blue,
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
            releaseSiblings = setOf(amber.uuid),
        )

        assertEquals(1, outcome.released, "Amber held nothing the press did not name")
        assertEquals(listOf(blue.uuid), rig.store.layers.map { it.source.uuid })
        assertEquals(100, rig.valueOf("hex-1"))
        assertEquals(100, rig.valueOf("hex-2"))
    }

    @Test
    fun `a press narrows a sibling to the targets it did not name`() {
        // The other half of per-target release: Amber keeps hex-2, which the press never claimed.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.toggle(
            amber,
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
            releaseSiblings = setOf(blue.uuid),
        )
        val outcome = rig.stack.toggle(blue, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released, "a narrowed sibling counts as released from the pressed targets")
        assertEquals(2, rig.store.layers.size)
        assertEquals(
            listOf(CueTargetDto("fixture", "hex-2")),
            rig.store.layers.single { it.source.uuid == amber.uuid }.targets,
        )
        assertEquals(100, rig.valueOf("hex-1"), "the press owns hex-1 outright")
        assertEquals(200, rig.valueOf("hex-2"), "and Amber keeps the head it never lost")
    }

    @Test
    fun `a sibling layer with no targets is left alone`() {
        // Empty targets means "the source's own bound rows", whose fixtures this class cannot read
        // off the list — so a press has nothing to subtract and must not guess.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.add(source = amber, targets = emptyList())
        val outcome = rig.stack.toggle(blue, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(amber.uuid))

        assertEquals(0, outcome.released)
        assertEquals(2, rig.store.layers.size)
        assertEquals(200, rig.valueOf("hex-2"), "Amber's rig-wide rows still hold hex-2")
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
    fun `a press on a group releases a sibling held on one of its members`() {
        // A group is its fixtures: pressing the wash covers hex-1, so Amber's layer on hex-1 goes.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.toggle(amber, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, listOf(CueTargetDto("group", "wash")), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released)
        assertEquals(listOf(blue.uuid), rig.store.layers.map { it.source.uuid })
        assertEquals(100, rig.valueOf("hex-1"))
        assertEquals(100, rig.valueOf("hex-2"))
    }

    @Test
    fun `a press on a member narrows a sibling held on the group`() {
        // The other direction, and the one that costs the layer its group spelling: Amber keeps
        // hex-2 as a fixture target, because "the wash minus hex-1" has no other way to be written.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.toggle(amber, listOf(CueTargetDto("group", "wash")), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released)
        assertEquals(
            listOf(CueTargetDto("fixture", "hex-2")),
            rig.store.layers.single { it.source.uuid == amber.uuid }.targets,
        )
        assertEquals(100, rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"))
    }

    @Test
    fun `a pad on a group is turned off by a press naming its members`() {
        // "Already on" reads coverage too, so selecting the wash and selecting both its heads are
        // the same press: the second one takes the layer off rather than stacking a twin.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)

        rig.stack.toggle(amber, listOf(CueTargetDto("group", "wash")))
        val outcome = rig.stack.toggle(
            amber,
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
        )

        assertEquals("removed", outcome.action)
        assertTrue(rig.store.layers.isEmpty())
    }

    @Test
    fun `a layer on an unknown group is still its own pad`() {
        // An unresolvable group stands for itself: the press that named it can still turn it off,
        // and a press on a real fixture leaves it alone rather than treating it as covering
        // everything or nothing.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)
        val gone = listOf(CueTargetDto("group", "deleted-wash"))

        rig.stack.toggle(amber, gone, releaseSiblings = setOf(blue.uuid))
        val press = rig.stack.toggle(blue, listOf(CueTargetDto("fixture", "hex-1")), releaseSiblings = setOf(amber.uuid))
        assertEquals(0, press.released, "a group nothing resolves covers no fixture")

        val off = rig.stack.toggle(amber, gone, releaseSiblings = setOf(blue.uuid))
        assertEquals("removed", off.action, "and it is still the same pad by name")
    }

    @Test
    fun `a press extends its own layer to a wider selection`() {
        // The reported bug, first half: Red on hex-1, then hex-2 added to the selection and the pad
        // pressed. The press has to *extend* the record rather than stack a second layer over
        // hex-1, or the press that follows can only take one of them off.
        val rig = newRig()
        val red = rig.groupedSource("Red", 200)

        rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-1")))
        val press = rig.stack.toggle(
            red,
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
        )

        assertEquals("applied", press.action, "the record covered only part of the selection")
        assertEquals(0, press.released, "extending its own layer releases no other pad")
        assertEquals(1, rig.store.layers.size, "one layer per record per head, not two")
        assertEquals(200, rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"))
    }

    @Test
    fun `pressing off clears the record from every pressed target`() {
        // The reported bug, second half. Before this the off-press removed the one layer whose
        // target set matched and left the earlier hex-1 layer behind, so the pad went from "full"
        // to "partial" on a gesture that says *off*.
        val rig = newRig()
        val red = rig.groupedSource("Red", 200)
        val both = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2"))

        rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-1")))
        rig.stack.toggle(red, both)
        val off = rig.stack.toggle(red, both)

        assertEquals("removed", off.action)
        assertTrue(rig.store.layers.isEmpty(), "nothing of the record survives the press")
        assertNull(rig.valueOf("hex-1"))
        assertNull(rig.valueOf("hex-2"))
    }

    @Test
    fun `an off press narrows a layer to the heads it did not name`() {
        // "Take red off this head" — the same per-target rule the sibling release uses, applied to
        // the record's own layer. The pad reads dark for hex-1 and lit for hex-2 afterwards.
        val rig = newRig()
        val red = rig.groupedSource("Red", 200)

        rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")))
        val off = rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-1")))

        assertEquals("removed", off.action, "the record covered every target the press named")
        assertEquals(
            listOf(CueTargetDto("fixture", "hex-2")),
            rig.store.layers.single().targets,
        )
        assertNull(rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"))
    }

    @Test
    fun `a press on one head of a lit group turns that head off`() {
        // Coverage, not target lists: the wash is lit, so pressing with one of its heads selected
        // is an *off* press for that head — the same answer the pad's own ring gives.
        val rig = newRig()
        val red = rig.groupedSource("Red", 200)

        rig.stack.toggle(red, listOf(CueTargetDto("group", "wash")))
        val off = rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-1")))

        assertEquals("removed", off.action)
        assertEquals(listOf(CueTargetDto("fixture", "hex-2")), rig.store.layers.single().targets)
        assertNull(rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"))
    }

    @Test
    fun `a record covered by two layers reads as on`() {
        // Coverage is the union over the record's layers, so two half-selections add up to an off
        // press rather than a third layer — which is what the pad's ring already showed.
        val rig = newRig()
        val red = rig.groupedSource("Red", 200)

        rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-1")))
        rig.stack.toggle(red, listOf(CueTargetDto("fixture", "hex-2")))
        assertEquals(2, rig.store.layers.size, "two presses on two selections are two layers")

        val off = rig.stack.toggle(
            red,
            listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2")),
        )

        assertEquals("removed", off.action)
        assertTrue(rig.store.layers.isEmpty(), "both layers give up the pressed heads")
    }

    @Test
    fun `a press naming no targets toggles its own rig-wide layer off`() {
        // A layer with no targets asserts its source's own bound rows, and a press with nothing
        // selected is that same gesture — there is no coverage to compare, so the literal twin is
        // the answer. Otherwise such a pad could be pressed on but never off.
        val rig = newRig()
        val red = rig.groupedSource("Red", 200)

        rig.stack.toggle(red, emptyList())
        assertEquals(200, rig.valueOf("hex-1"), "the rows landed on the whole rig")

        val off = rig.stack.toggle(red, emptyList())
        assertEquals("removed", off.action)
        assertTrue(rig.store.layers.isEmpty())
    }

    @Test
    fun `a target-less press releases a target-less sibling`() {
        // Both pads say "my own rows, wherever they land", so they are exclusive in the only terms
        // they have. Nothing can be subtracted from an empty target list, so the sibling's layer
        // goes outright — the whole-set rule's one correct case, kept.
        val rig = newRig()
        val amber = rig.groupedSource("Amber", 200)
        val blue = rig.groupedSource("Blue", 100)

        rig.stack.toggle(amber, emptyList(), releaseSiblings = setOf(blue.uuid))
        val press = rig.stack.toggle(blue, emptyList(), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, press.released, "the sibling's rig-wide layer is the one thing it could give up")
        assertEquals(listOf(blue.uuid), rig.store.layers.map { it.source.uuid })
        assertEquals(100, rig.valueOf("hex-1"))
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

    // ─── Applied state ──────────────────────────────────────────────────

    /** The record's targets as `type:key=extent` strings, sorted — order is not part of the answer. */
    private fun AppliedSource.described() =
        targets.map { "${it.target.type}:${it.target.key}=${it.extent.name.lowercase()}" }.sorted()

    @Test
    fun `applied state reports a covered fixture and its group as partial`() {
        // What a pad needs for a one-head press: hex-1 is lit outright, and the wash the head
        // belongs to is lit *partly* — which is the ring the operator sees with the wash selected.
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        val applied = rig.stack.appliedState().single()
        assertEquals("Warm", applied.source.name)
        assertEquals(listOf("fixture:hex-1=all", "group:wash=some"), applied.described())
    }

    @Test
    fun `a layer on a group reports the group and every head in it`() {
        // The other direction of the same rule: naming the wash covers its heads, so a pad with
        // either the wash or its members selected reads full.
        val rig = newRig()
        rig.stack.add(source = rig.groupedSource("Warm", 200), targets = listOf(CueTargetDto("group", "wash")))

        val applied = rig.stack.appliedState().single()
        assertEquals(
            listOf("fixture:hex-1=all", "fixture:hex-2=all", "group:wash=all"),
            applied.described(),
        )
    }

    @Test
    fun `two layers of one record fold into one entry`() {
        // A record applied to two selections is one pad, and its ring reads the union — otherwise
        // pressing the same template on hex-1 and then hex-2 would leave the wash reading partial.
        val rig = newRig()
        val warm = rig.groupedSource("Warm", 200)
        rig.stack.add(source = warm, targets = listOf(CueTargetDto("fixture", "hex-1")))
        rig.stack.add(source = warm, targets = listOf(CueTargetDto("fixture", "hex-2")))

        val applied = rig.stack.appliedState().single()
        assertEquals(
            listOf("fixture:hex-1=all", "fixture:hex-2=all", "group:wash=all"),
            applied.described(),
        )
    }

    @Test
    fun `a layer with no targets contributes no applied state`() {
        // Empty targets means "the source's own bound rows" — where those land is the cook's
        // answer, not a coverage comparison's, so the pad reports nothing rather than guessing.
        val rig = newRig()
        rig.stack.add(source = rig.groupedSource("Warm", 200), targets = emptyList())

        assertTrue(rig.stack.appliedState().isEmpty())
    }

    @Test
    fun `a disabled layer still reports as applied`() {
        // Disabled is a layer that is on the stack asserting nothing: the pad's next press still
        // takes it off, so its ring must stay lit or the press would look like it did nothing.
        val rig = newRig()
        val layer = rig.add("Warm", 200, "hex-1")
        rig.stack.patch(layer.layerId, enabled = false)

        assertNull(rig.valueOf("hex-1"), "a disabled layer asserts no value")
        assertEquals(listOf("fixture:hex-1=all", "group:wash=some"), rig.stack.appliedState().single().described())
    }

    @Test
    fun `applied state describes the layer list it is given`() {
        // The `layerState` broadcast resolves the frame it is sending rather than whatever the
        // store holds by the time it renders, so the two halves of a frame always agree.
        val rig = newRig()
        val frame = rig.store.layers
        rig.add("Warm", 200, "hex-1")

        assertTrue(rig.stack.appliedState(frame).isEmpty(), "the empty stack the caller captured")
        assertEquals(1, rig.stack.appliedState().size, "and the store's own, for comparison")
    }
}
