package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.LayerSource
import uk.me.cormack.lighting7.fx.TemplateRegistry
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
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

    /** Register a Look of deferred dimmer rows and return its uuid. */
    private fun Rig.look(name: String, dimmer: Int): UUID {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        looks[uuid] = LookSnapshot(
            lookId = name.hashCode(),
            lookUuid = uuid,
            name = name,
            rows = listOf(
                LookRowEntry(target = null, propertyName = "dimmer", value = dimmer.toString()),
            ),
            effects = emptyList(),
        )
        return uuid
    }

    /** An unsaved editor draft — deferred rows, no registry entry. */
    private fun draft(dimmer: Int) = LookSnapshot(
        lookId = 0,
        lookUuid = UUID(0L, 0L),
        name = "preview",
        rows = listOf(
            LookRowEntry(target = null, propertyName = "dimmer", value = dimmer.toString()),
        ),
        effects = emptyList(),
    )

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
            rows = listOf(LookRowEntry(target = null, propertyName = "dimmer", value = "77")),
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

    // ─── Preview ────────────────────────────────────────────────────────

    @Test
    fun `the preview layer sits on top of every real layer`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        rig.stack.installPreview(draft(12), targets = listOf(CueTargetDto("fixture", "hex-1")))

        assertEquals(12, rig.valueOf("hex-1"))
        assertTrue(rig.store.layers.last().isPreview)
    }

    @Test
    fun `the preview cooks from an inline draft the registry has never heard of`() {
        // The whole reason the preview carries its own snapshot: the editor previews contents that
        // have no row yet, so there is nothing for `LookRegistry` to load. Being a layer anyway is
        // what makes it compose above the stack under the same rules.
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")

        rig.stack.installPreview(draft(33), targets = listOf(CueTargetDto("fixture", "hex-1")))

        assertEquals(33, rig.valueOf("hex-1"))
        assertTrue(rig.looks.isEmpty() || rig.looks.keys.none { it == UUID(0L, 0L) })
    }

    @Test
    fun `an identical preview request is a no-op that preserves the layer's identity`() {
        // Not cosmetic: the editor debounces at 80 ms and its trailing tick re-sends the same
        // payload. Re-materialising would restart any fade in flight — the contract
        // `PresetPreviewSlotTest` pinned with assertSame, carried over from `swapPresetPreviewSlot`.
        val rig = newRig()
        val targets = listOf(CueTargetDto("fixture", "hex-1"))
        rig.stack.installPreview(draft(12), targets = targets)
        val first = rig.store.layers.single { it.isPreview }

        val outcome = rig.stack.installPreview(draft(12), targets = targets)

        assertEquals(ProgrammerLayerOutcome(0, 0, 0, 0), outcome, "nothing was republished")
        assertTrue(first === rig.store.layers.single { it.isPreview }, "same instance, not an equal one")
    }

    @Test
    fun `a changed preview replaces the old one rather than stacking`() {
        val rig = newRig()
        val targets = listOf(CueTargetDto("fixture", "hex-1"))
        rig.stack.installPreview(draft(12), targets = targets)
        rig.stack.installPreview(draft(99), targets = targets)

        assertEquals(1, rig.store.layers.count { it.isPreview })
        assertEquals(99, rig.valueOf("hex-1"))
    }

    @Test
    fun `clearing the preview removes the entry rather than storing an empty one`() {
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")
        rig.stack.installPreview(draft(12), targets = listOf(CueTargetDto("fixture", "hex-1")))

        rig.stack.installPreview(snapshot = null)

        assertTrue(rig.store.layers.none { it.isPreview })
        assertEquals(200, rig.valueOf("hex-1"), "the real layer underneath is what shows")
    }

    @Test
    fun `clearing a preview that was never installed is a no-op`() {
        val rig = newRig()
        val outcome = rig.stack.installPreview(snapshot = null)
        assertEquals(ProgrammerLayerOutcome(0, 0, 0, 0), outcome)
    }

    @Test
    fun `an empty draft reads as a clear`() {
        // The route collapses an empty request to a clear, and so does this: a draft with no rows
        // describes nothing to assert.
        val rig = newRig()
        rig.add("Warm", 200, "hex-1")
        rig.stack.installPreview(draft(12), targets = listOf(CueTargetDto("fixture", "hex-1")))

        rig.stack.installPreview(draft(12).copy(rows = emptyList()))

        assertTrue(rig.store.layers.none { it.isPreview })
        assertEquals(200, rig.valueOf("hex-1"))
    }

    // ─── The pad gesture ────────────────────────────────────────────────

    @Test
    fun `toggle adds the layer, then removes the one it added`() {
        val rig = newRig()
        val uuid = rig.look("Warm", 200)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))

        val applied = rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), targets)
        assertEquals("applied", applied.first)
        assertEquals(200, rig.valueOf("hex-1"))

        val removed = rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), targets)
        assertEquals("removed", removed.first)
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
    fun `toggle ignores the preview layer when deciding whether a Look is already on`() {
        val rig = newRig()
        val uuid = rig.look("Warm", 200)
        val targets = listOf(CueTargetDto("fixture", "hex-1"))
        rig.stack.installPreview(draft(12), targets = targets)

        assertEquals("applied", rig.stack.toggle(LayerSource.look("Warm".hashCode(), uuid, "Warm"), targets).first)
    }
}
