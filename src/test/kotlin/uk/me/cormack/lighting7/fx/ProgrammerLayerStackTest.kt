package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
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
 * The programmer's layer stack, at the value level.
 *
 * Effects need a real `State` (the FX registry and target resolution both take one) and are covered
 * in `ProgrammerLayerStackEffectsTest`; everything here runs against map-backed [LookRegistry] and
 * [TemplateRegistry]s and no database, which is what keeps the composition rules cheap to assert.
 * A Look lands whole, so the per-target press rules are a palette's and are asserted on palettes.
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
        val palettes: MutableMap<UUID, TemplateSnapshot>,
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
        val palettes = HashMap<UUID, TemplateSnapshot>()
        val templateRegistry = TemplateRegistry(loader = { palettes[it] })
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = store,
            layerResolver = LayerResolver(CueAssignmentResolver(), store),
        )
        val stack = ProgrammerLayerStack(
            fixtures = { fixtures },
            lookRegistry = { registry },
            templateRegistry = { templateRegistry },
            engine = { engine },
            store = store,
            // No effects in these Looks or palettes, so nothing ever reaches the spawn path.
            state = { null },
        )
        return Rig(fixtures, store, engine, stack, looks, registry, palettes)
    }

    /** Register a Look holding a dimmer row on each of [fixtureKeys] (every head when none), and return its uuid. */
    private fun Rig.look(name: String, dimmer: Int, vararg fixtureKeys: String): UUID {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        looks[uuid] = LookSnapshot(
            lookId = name.hashCode(),
            lookUuid = uuid,
            name = name,
            rows = rowsFor(dimmer, *fixtureKeys),
            effects = emptyList(),
        )
        return uuid
    }

    private fun Rig.rowsFor(dimmer: Int, vararg fixtureKeys: String) =
        fixtureKeys.ifEmpty { fixtures.fixtures.map { it.key }.toTypedArray() }.map {
            LookRowEntry(target = TargetRef.Fixture(it), propertyName = "dimmer", value = dimmer.toString())
        }

    private fun Rig.lookSource(name: String, dimmer: Int, vararg fixtureKeys: String) =
        LayerSource.look(name.hashCode(), look(name, dimmer, *fixtureKeys), name)

    /** Add a layer of a Look whose rows are a dimmer on [fixtureKeys]. */
    private fun Rig.add(name: String, dimmer: Int, vararg fixtureKeys: String) =
        stack.add(source = lookSource(name, dimmer, *fixtureKeys), targets = emptyList()).first

    /** A generic palette of one row, `property` at `value` (a [TemplateIntent] string), and its source. */
    private fun Rig.palette(name: String, property: String, value: String): LayerSource {
        val uuid = UUID.nameUUIDFromBytes("palette:$name".toByteArray())
        palettes[uuid] = TemplateSnapshot(
            templateId = name.hashCode(),
            templateUuid = uuid,
            name = name,
            fadeDurationMs = null,
            rows = listOf(TemplateRowEntry(target = null, propertyName = property, value = value)),
        )
        return LayerSource.template(name.hashCode(), uuid, name)
    }

    /** A UV palette: `dmx:` is a literal level, so [valueOf] reads [level] back exactly. */
    private fun Rig.uv(name: String, level: Int) = palette(name, "uv", "dmx:$level")

    /** A dimmer palette, in another family from [uv]'s, so neither replaces the other on a press. */
    private fun Rig.dimmer(name: String, percent: Int) = palette(name, "dimmer", "pct:$percent")

    /** The programmer's winning value for a key, as an Int, or null when it holds nothing. */
    private fun Rig.valueOf(fixtureKey: String, propertyName: String = "dimmer"): Int? {
        val slot = store.get(fixtureKey, propertyName) ?: return null
        return (slot.value.resolved as CueAssignmentResolver.PropertyValue.Slider).value.toInt()
    }

    private fun on(vararg keys: String) = keys.map { CueTargetDto("fixture", it) }

    // ─── Materialising ──────────────────────────────────────────────────

    @Test
    fun `adding a layer materialises its values into the store`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        assertEquals(200, rig.valueOf("hex-1"))
        assertEquals(ProgrammerOwner.LAYERS, rig.store.get("hex-1", "dimmer")!!.owner)
        assertNull(rig.valueOf("hex-2"), "a Look asserts only where its rows land")
    }

    @Test
    fun `a later layer wins the keys it shares with an earlier one`() {
        val rig = newRig()
        rig.add("Warm", 200)
        rig.add("Cool", 40, "hex-1")

        assertEquals(40, rig.valueOf("hex-1"), "later layer wins")
        assertEquals(200, rig.valueOf("hex-2"), "and leaves the key it doesn't cover alone")
    }

    @Test
    fun `removing a layer releases the keys nothing else covers`() {
        val rig = newRig()
        val warm = rig.add("Warm", 200)
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

    @Test
    fun `a Look layer added with targets lands whole and names none`() {
        val rig = newRig()
        val (layer, _) = rig.stack.add(source = rig.lookSource("Warm", 200), targets = on("hex-1"))

        assertTrue(layer.targets.isEmpty())
        assertEquals(200, rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"), "the Look's rows land wherever they are bound")

        rig.stack.patch(layer.layerId, targets = on("hex-2"))
        assertTrue(rig.store.layers.single().targets.isEmpty(), "and a patch gives it none")
        assertEquals(200, rig.valueOf("hex-1"))
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
        rig.looks[warmUuid] = rig.looks.getValue(warmUuid).copy(rows = rig.rowsFor(77, "hex-1"))
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

    // ─── A Look's press is whole ────────────────────────────────────────

    @Test
    fun `a Look press puts one untargeted layer on, whatever is selected, and the next takes it off`() {
        val rig = newRig()
        val warm = rig.lookSource("Warm", 200)

        val applied = rig.stack.toggle(warm, on("hex-1"), propertyMask = "COLOUR")
        assertEquals("applied", applied.action)
        val layer = rig.store.layers.single()
        assertTrue(layer.targets.isEmpty(), "the selection is not the Look's")
        assertNull(layer.propertyMask, "and nor is the selection's mask")
        assertEquals(200, rig.valueOf("hex-1"))
        assertEquals(200, rig.valueOf("hex-2"))

        val removed = rig.stack.toggle(warm, on("hex-2"))
        assertEquals("removed", removed.action)
        assertTrue(rig.store.layers.isEmpty())
        assertNull(rig.valueOf("hex-1"))
    }

    @Test
    fun `a Look press with nothing selected is the same press`() {
        val rig = newRig()
        val warm = rig.lookSource("Warm", 200, "hex-1")

        assertEquals("applied", rig.stack.toggle(warm, emptyList()).action)
        assertEquals(200, rig.valueOf("hex-1"))
        assertEquals("removed", rig.stack.toggle(warm, on("hex-1", "hex-2")).action)
        assertTrue(rig.store.layers.isEmpty())
    }

    @Test
    fun `a mask set on a Look layer afterwards is kept, and filters its rows`() {
        val rig = newRig()
        val warm = rig.lookSource("Warm", 200)
        rig.stack.toggle(warm, on("hex-1"))
        val layer = rig.store.layers.single()

        rig.stack.patch(layer.layerId, propertyMask = "COLOUR")

        assertEquals("COLOUR", rig.store.layers.single().propertyMask)
        assertNull(rig.valueOf("hex-1"), "a dimmer row is outside a colour mask")
    }

    @Test
    fun `an off press takes every layer of the Look off`() {
        val rig = newRig()
        val warm = rig.lookSource("Warm", 200)
        rig.stack.add(source = warm, targets = emptyList())
        rig.add("Between", 10, "hex-1")
        rig.stack.add(source = warm, targets = emptyList())

        val off = rig.stack.toggle(warm, on("hex-1"))

        assertEquals("removed", off.action)
        assertEquals(listOf("Between"), rig.store.layers.map { it.source.name })
        assertEquals(10, rig.valueOf("hex-1"))
        assertNull(rig.valueOf("hex-2"))
    }

    @Test
    fun `a renamed record's pad still toggles its own layer off`() {
        // `LayerSource` is a data class, so its equality includes the mutable name: a pad matching on
        // the whole value stacked a second layer after a rename. The match is on `source.uuid`.
        val rig = newRig()
        val uuid = rig.look("Warm", 200)

        rig.stack.toggle(LayerSource.look(1, uuid, "Warm"), on("hex-1"))
        assertEquals(1, rig.store.layers.size)

        val renamed = rig.stack.toggle(LayerSource.look(1, uuid, "Warm Wash"), on("hex-1"))

        assertEquals("removed", renamed.action, "a rename must not make the pad stack a second layer")
        assertTrue(rig.store.layers.isEmpty())
        assertNull(rig.valueOf("hex-1"))
    }

    /**
     * The int PK is *not* enough on its own, which is why the match is not simply `source.id`: a
     * Look and a palette can share one, and their pads must not cancel each other.
     */
    @Test
    fun `a Look and a palette sharing an int PK toggle independently`() {
        val rig = newRig()
        val uuid = rig.look("Warm", 200)

        rig.stack.toggle(LayerSource.look(7, uuid, "Warm"), on("hex-1"))
        // Same id, different record. The palette resolves to null here (the map does not hold it),
        // so it contributes no values — but it must still take a layer of its own.
        rig.stack.toggle(LayerSource.template(7, UUID.nameUUIDFromBytes("t7".toByteArray()), "Amber Key"), on("hex-1"))

        assertEquals(2, rig.store.layers.size, "sharing an int PK must not collapse two pads into one")
        assertEquals(200, rig.valueOf("hex-1"), "the Look's pad is still on")
    }

    // ─── A palette's press is per target ────────────────────────────────

    @Test
    fun `toggle matches the same targets in a different order`() {
        // The same fixture set re-sent in a different order (e.g. re-derived from a Set on the
        // client) must still toggle the existing layer off, not stack a second one on top.
        val rig = newRig()
        val red = rig.uv("Red", 200)

        assertEquals("applied", rig.stack.toggle(red, on("hex-1", "hex-2")).action)
        assertEquals(1, rig.store.layers.size)

        assertEquals("removed", rig.stack.toggle(red, on("hex-2", "hex-1")).action)
        assertTrue(rig.store.layers.isEmpty())
    }

    @Test
    fun `the same palette pressed on two targets widens, and comes off per target`() {
        val rig = newRig()
        val red = rig.uv("Red", 200)

        rig.stack.toggle(red, on("hex-1"))
        rig.stack.toggle(red, on("hex-2"))
        // The top layer widens rather than stacking a second (apply-model P3).
        assertEquals(1, rig.store.layers.size)

        rig.stack.toggle(red, on("hex-1"))

        assertEquals(on("hex-2"), rig.store.layers.single().targets)
        assertNull(rig.valueOf("hex-1", "uv"))
        assertEquals(200, rig.valueOf("hex-2", "uv"), "the other head is still on")
    }

    @Test
    fun `a press extends its own layer to a wider selection`() {
        // Red on hex-1, then hex-2 added to the selection and the pad pressed: the press extends the
        // record rather than stacking a second layer over hex-1, or the next press could only take
        // one of them off.
        val rig = newRig()
        val red = rig.uv("Red", 200)

        rig.stack.toggle(red, on("hex-1"))
        val press = rig.stack.toggle(red, on("hex-1", "hex-2"))

        assertEquals("applied", press.action, "the record covered only part of the selection")
        assertEquals(0, press.released, "extending its own layer releases no other pad")
        assertEquals(1, rig.store.layers.size, "one layer per record per head, not two")
        assertEquals(200, rig.valueOf("hex-1", "uv"))
        assertEquals(200, rig.valueOf("hex-2", "uv"))
    }

    @Test
    fun `pressing off clears the record from every pressed target`() {
        val rig = newRig()
        val red = rig.uv("Red", 200)
        val both = on("hex-1", "hex-2")

        rig.stack.toggle(red, on("hex-1"))
        rig.stack.toggle(red, both)
        val off = rig.stack.toggle(red, both)

        assertEquals("removed", off.action)
        assertTrue(rig.store.layers.isEmpty(), "nothing of the record survives the press")
        assertNull(rig.valueOf("hex-1", "uv"))
        assertNull(rig.valueOf("hex-2", "uv"))
    }

    @Test
    fun `an off press narrows a layer to the heads it did not name`() {
        val rig = newRig()
        val red = rig.uv("Red", 200)

        rig.stack.toggle(red, on("hex-1", "hex-2"))
        val off = rig.stack.toggle(red, on("hex-1"))

        assertEquals("removed", off.action, "the record covered every target the press named")
        assertEquals(on("hex-2"), rig.store.layers.single().targets)
        assertNull(rig.valueOf("hex-1", "uv"))
        assertEquals(200, rig.valueOf("hex-2", "uv"))
    }

    @Test
    fun `a press on one head of a lit group turns that head off`() {
        // Coverage, not target lists: the wash is lit, so pressing with one of its heads selected
        // is an *off* press for that head — the same answer the pad's own ring gives.
        val rig = newRig()
        val red = rig.uv("Red", 200)

        rig.stack.toggle(red, listOf(CueTargetDto("group", "wash")))
        val off = rig.stack.toggle(red, on("hex-1"))

        assertEquals("removed", off.action)
        assertEquals(on("hex-2"), rig.store.layers.single().targets)
        assertNull(rig.valueOf("hex-1", "uv"))
        assertEquals(200, rig.valueOf("hex-2", "uv"))
    }

    @Test
    fun `a pad on a group is turned off by a press naming its members`() {
        val rig = newRig()
        val red = rig.uv("Red", 200)

        rig.stack.toggle(red, listOf(CueTargetDto("group", "wash")))
        val outcome = rig.stack.toggle(red, on("hex-1", "hex-2"))

        assertEquals("removed", outcome.action)
        assertTrue(rig.store.layers.isEmpty())
    }

    @Test
    fun `a record covered by two layers reads as on`() {
        // Coverage is the union over the record's layers, so two half-selections add up to an off
        // press rather than a third layer — which is what the pad's ring already showed.
        val rig = newRig()
        val red = rig.uv("Red", 200)

        rig.stack.toggle(red, on("hex-1"))
        // A layer between the two presses, so the second adds a layer rather than widening the first.
        rig.add("Between", 10, "hex-1")
        rig.stack.toggle(red, on("hex-2"))
        assertEquals(2, rig.store.layers.count { it.source == red }, "two presses on two selections are two layers")

        val off = rig.stack.toggle(red, on("hex-1", "hex-2"))

        assertEquals("removed", off.action)
        assertTrue(rig.store.layers.none { it.source == red }, "both layers give up the pressed heads")
    }

    @Test
    fun `a palette press naming no targets toggles its own untargeted layer off`() {
        // A press with nothing selected and the layer it made are the same gesture: there is no
        // coverage to compare, so the literal twin is the answer, or the pad could never go off.
        val rig = newRig()
        val red = rig.uv("Red", 200)

        assertEquals("applied", rig.stack.toggle(red, emptyList()).action)
        assertEquals("removed", rig.stack.toggle(red, emptyList()).action)
        assertTrue(rig.store.layers.isEmpty())
    }

    // ─── Solo banks ─────────────────────────────────────────────────────

    // A UV and a dimmer palette are in different families, so neither press replaces the other
    // through its family: what is released here is released by the bank alone.

    @Test
    fun `toggling with siblings releases a sibling on the same targets in one mutation`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, on("hex-1"), releaseSiblings = setOf(blue.uuid))
        assertEquals(1, rig.store.layers.size)

        // Every `layerState` frame the press produces. `Unconfined` so `tryEmit` delivers to the
        // collector synchronously, on the toggling thread — the flow replays one, which is the
        // Amber stack, so the count is taken after subscription has drained it.
        val frames = mutableListOf<List<ProgrammerLayer>>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            rig.store.layersFlow.collect { frames += it.layers }
        }
        frames.clear()

        val outcome = rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))
        collector.cancel()

        assertEquals("applied", outcome.action)
        assertEquals(1, outcome.released, "Amber's layer on the same targets is released")
        assertEquals(1, rig.store.layers.size, "release and add are one stack, not two")
        assertEquals(blue.uuid, rig.store.layers.single().source.uuid)
        assertNull(rig.valueOf("hex-1", "uv"), "Amber is off")
        assertEquals(102, rig.valueOf("hex-1"), "Blue is on")
        assertEquals(1, frames.size, "release and add must be one store mutation, so one layerState frame")
        assertEquals(listOf(blue.uuid), frames.single().map { it.source.uuid })
    }

    @Test
    fun `a sibling on a disjoint target set survives`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, on("hex-1"), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, on("hex-2"), releaseSiblings = setOf(amber.uuid))

        assertEquals(0, outcome.released)
        assertEquals(2, rig.store.layers.size)
        assertEquals(200, rig.valueOf("hex-1", "uv"), "Amber on hex-1 is untouched")
        assertEquals(102, rig.valueOf("hex-2"))
    }

    @Test
    fun `a press over a sibling's whole target set takes the sibling off`() {
        // Amber on hex-1, then Blue on hex-1 *and* hex-2: Amber's layer must go, or releasing Blue
        // would pop it back.
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, on("hex-1"), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, on("hex-1", "hex-2"), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released, "Amber held nothing the press did not name")
        assertEquals(listOf(blue.uuid), rig.store.layers.map { it.source.uuid })
        assertEquals(102, rig.valueOf("hex-1"))
        assertEquals(102, rig.valueOf("hex-2"))
    }

    @Test
    fun `a press narrows a sibling to the targets it did not name`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, on("hex-1", "hex-2"), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released, "a narrowed sibling counts as released from the pressed targets")
        assertEquals(2, rig.store.layers.size)
        assertEquals(on("hex-2"), rig.store.layers.single { it.source.uuid == amber.uuid }.targets)
        assertNull(rig.valueOf("hex-1", "uv"))
        assertEquals(200, rig.valueOf("hex-2", "uv"), "Amber keeps the head it never lost")
    }

    @Test
    fun `a sibling layer with no targets is left alone`() {
        // A generic palette's untargeted layer lands on nothing, so a press has nothing to
        // subtract from it and must not guess.
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.add(source = amber, targets = emptyList())
        val outcome = rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))

        assertEquals(0, outcome.released)
        assertEquals(2, rig.store.layers.size)
    }

    @Test
    fun `turning a grouped pad off leaves its siblings alone`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, on("hex-2"), releaseSiblings = setOf(blue.uuid))
        rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))
        assertEquals(2, rig.store.layers.size)

        // Press Blue again on hex-1: off. Amber on hex-2 is a sibling, but a remove never releases.
        val outcome = rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))

        assertEquals("removed", outcome.action)
        assertEquals(0, outcome.released)
        assertEquals(1, rig.store.layers.size)
        assertEquals(200, rig.valueOf("hex-2", "uv"), "the sibling is still on")
    }

    @Test
    fun `a press on a group releases a sibling held on one of its members`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, on("hex-1"), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, listOf(CueTargetDto("group", "wash")), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released)
        assertEquals(listOf(blue.uuid), rig.store.layers.map { it.source.uuid })
        assertEquals(102, rig.valueOf("hex-1"))
        assertEquals(102, rig.valueOf("hex-2"))
    }

    @Test
    fun `a press on a member narrows a sibling held on the group`() {
        // Amber keeps hex-2 as a fixture target, because "the wash minus hex-1" has no other way to
        // be written.
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, listOf(CueTargetDto("group", "wash")), releaseSiblings = setOf(blue.uuid))
        val outcome = rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, outcome.released)
        assertEquals(on("hex-2"), rig.store.layers.single { it.source.uuid == amber.uuid }.targets)
        assertNull(rig.valueOf("hex-1", "uv"))
        assertEquals(200, rig.valueOf("hex-2", "uv"))
    }

    @Test
    fun `a layer on an unknown group is still its own pad`() {
        // An unresolvable group stands for itself: the press that named it can still turn it off,
        // and a press on a real fixture leaves it alone rather than treating it as covering
        // everything or nothing.
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)
        val gone = listOf(CueTargetDto("group", "deleted-wash"))

        rig.stack.toggle(amber, gone, releaseSiblings = setOf(blue.uuid))
        val press = rig.stack.toggle(blue, on("hex-1"), releaseSiblings = setOf(amber.uuid))
        assertEquals(0, press.released, "a group nothing resolves covers no fixture")

        val off = rig.stack.toggle(amber, gone, releaseSiblings = setOf(blue.uuid))
        assertEquals("removed", off.action, "and it is still the same pad by name")
    }

    @Test
    fun `a target-less press releases a target-less sibling`() {
        // Both pads say "my own rows, wherever they land", so they are exclusive in the only terms
        // they have: nothing can be subtracted from an empty target list, so the sibling goes.
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val blue = rig.dimmer("Blue", 40)

        rig.stack.toggle(amber, emptyList(), releaseSiblings = setOf(blue.uuid))
        val press = rig.stack.toggle(blue, emptyList(), releaseSiblings = setOf(amber.uuid))

        assertEquals(1, press.released, "the sibling's untargeted layer is the one thing it could give up")
        assertEquals(listOf(blue.uuid), rig.store.layers.map { it.source.uuid })
    }

    @Test
    fun `siblings never release a layer whose uuid is not named`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val stranger = rig.dimmer("Stranger", 20)

        rig.stack.toggle(stranger, on("hex-1"))
        val outcome = rig.stack.toggle(amber, on("hex-1"), releaseSiblings = setOf(UUID.nameUUIDFromBytes("blue".toByteArray())))

        assertEquals(0, outcome.released)
        assertEquals(2, rig.store.layers.size, "an unnamed layer on the same targets is left alone")
    }

    @Test
    fun `a Look press in a solo bank takes every sibling layer off whole`() {
        val rig = newRig()
        val amber = rig.uv("Amber", 200)
        val cool = rig.lookSource("Cool", 40)
        val warm = rig.lookSource("Warm", 200, "hex-1")

        rig.stack.toggle(amber, on("hex-1", "hex-2"))
        rig.stack.toggle(cool, emptyList())
        val press = rig.stack.toggle(warm, on("hex-2"), releaseSiblings = setOf(amber.uuid, cool.uuid))

        assertEquals("applied", press.action)
        assertEquals(2, press.released, "the palette on heads the Look's rows never reach goes too")
        assertEquals(listOf(warm.uuid), rig.store.layers.map { it.source.uuid })
        assertEquals(200, rig.valueOf("hex-1"))
        assertNull(rig.valueOf("hex-2"))
        assertNull(rig.valueOf("hex-2", "uv"))
    }

    @Test
    fun `a palette press in a solo bank takes a Look sibling off whole`() {
        // A Look's layer cannot be narrowed to the heads the press did not name, so it goes.
        val rig = newRig()
        val warm = rig.lookSource("Warm", 200)
        val amber = rig.uv("Amber", 150)

        rig.stack.toggle(warm, emptyList())
        val press = rig.stack.toggle(amber, on("hex-1"), releaseSiblings = setOf(warm.uuid))

        assertEquals(1, press.released)
        assertEquals(listOf(amber.uuid), rig.store.layers.map { it.source.uuid })
        assertNull(rig.valueOf("hex-2"), "the Look is off its heads the press never named too")
    }

    @Test
    fun `a Look's off press in a solo bank releases nothing`() {
        val rig = newRig()
        val warm = rig.lookSource("Warm", 200)
        val amber = rig.uv("Amber", 150)

        rig.stack.toggle(warm, emptyList())
        rig.stack.add(source = amber, targets = on("hex-1"))
        val off = rig.stack.toggle(warm, on("hex-1"), releaseSiblings = setOf(amber.uuid))

        assertEquals("removed", off.action)
        assertEquals(0, off.released)
        assertEquals(listOf(amber.uuid), rig.store.layers.map { it.source.uuid })
    }

    // ─── Applied state ──────────────────────────────────────────────────

    /** The record's targets as `type:key=extent` strings, sorted — order is not part of the answer. */
    private fun AppliedSource.described() =
        targets.map { "${it.target.type}:${it.target.key}=${it.extent.name.lowercase()}" }.sorted()

    private val wholeRig = listOf("fixture:hex-1=all", "fixture:hex-2=all", "group:wash=all")

    @Test
    fun `applied state reports a covered fixture and its group as partial`() {
        // What a pad needs for a one-head press: hex-1 is lit outright, and the wash the head
        // belongs to is lit *partly* — which is the ring the operator sees with the wash selected.
        val rig = newRig()
        rig.stack.add(source = rig.uv("Warm", 200), targets = on("hex-1"))

        val applied = rig.stack.appliedState().single()
        assertEquals("Warm", applied.source.name)
        assertEquals(listOf("fixture:hex-1=all", "group:wash=some"), applied.described())
    }

    @Test
    fun `a layer on a group reports the group and every head in it`() {
        val rig = newRig()
        rig.stack.add(source = rig.uv("Warm", 200), targets = listOf(CueTargetDto("group", "wash")))

        assertEquals(wholeRig, rig.stack.appliedState().single().described())
    }

    @Test
    fun `two layers of one record fold into one entry`() {
        // A record applied to two selections is one pad, and its ring reads the union — otherwise
        // pressing the same palette on hex-1 and then hex-2 would leave the wash reading partial.
        val rig = newRig()
        val warm = rig.uv("Warm", 200)
        rig.stack.add(source = warm, targets = on("hex-1"))
        rig.stack.add(source = warm, targets = on("hex-2"))

        assertEquals(wholeRig, rig.stack.appliedState().single().described())
    }

    @Test
    fun `a generic palette's layer with no targets contributes no applied state`() {
        val rig = newRig()
        rig.stack.add(source = rig.uv("Warm", 200), targets = emptyList())

        assertTrue(rig.stack.appliedState().isEmpty())
    }

    @Test
    fun `a Look reads applied on the whole rig, whatever its rows reach`() {
        // A Look's ring is on or off: lit whatever is selected while a layer of it is on.
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        val applied = rig.stack.appliedState().single()
        assertEquals("Warm", applied.source.name)
        assertEquals(wholeRig, applied.described())
    }

    @Test
    fun `a Look and a palette read side by side`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")
        rig.stack.add(source = rig.uv("Amber", 150), targets = on("hex-2"))

        val bySource = rig.stack.appliedState().associate { it.source.name to it.described() }
        assertEquals(wholeRig, bySource["Warm"])
        assertEquals(listOf("fixture:hex-2=all", "group:wash=some"), bySource["Amber"])
    }

    @Test
    fun `a disabled layer still reports as applied`() {
        // Disabled is a layer that is on the stack asserting nothing: the pad's next press still
        // takes it off, so its ring must stay lit or the press would look like it did nothing.
        val rig = newRig()
        val layer = rig.add("Warm", 200, "hex-1")
        rig.stack.patch(layer.layerId, enabled = false)

        assertNull(rig.valueOf("hex-1"), "a disabled layer asserts no value")
        assertEquals(wholeRig, rig.stack.appliedState().single().described())
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
