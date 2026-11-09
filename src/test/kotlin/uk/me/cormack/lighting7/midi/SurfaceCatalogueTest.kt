package uk.me.cormack.lighting7.midi

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The function catalogue and the served map (surface-setup plan D15, P5), each pinned to a fixture
 * the frontend's tests read too, so the two sides cannot drift apart unseen.
 *
 * The fixtures are generated from this build: run with `REGENERATE_SURFACE_FIXTURES=1` after a
 * deliberate change, and commit the result with it.
 */
class SurfaceCatalogueTest {
    private val json = Json { prettyPrint = true }
    private val fixtures = File("src/test/resources/surfaces")

    private fun pinned(name: String, encoded: String) {
        val file = File(fixtures, name)
        if (System.getenv("REGENERATE_SURFACE_FIXTURES") != null) {
            fixtures.mkdirs()
            file.writeText(encoded + "\n")
        }
        assertEquals(file.readText().trimEnd(), encoded, "$name is stale: regenerate it with REGENERATE_SURFACE_FIXTURES=1")
    }

    @Test
    fun `every function is an entry of the catalogue, and every entry builds the function it names (P3)`() {
        for (function in Does::class.sealedSubclasses) {
            if (function == Does.Unknown::class) continue
            val entries = SurfaceCatalogue.entries.filter { it.does == function.annotations.filterIsInstance<kotlinx.serialization.SerialName>().single().value }
            assertTrue(entries.isNotEmpty(), "${function.simpleName} has no catalogue entry")
        }
        for (entry in SurfaceCatalogue.entries) {
            assertTrue(entry.strip || entry.inputs.isNotEmpty(), "${entry.id} takes some input")
            assertTrue(entry.subjects.isNotEmpty(), "${entry.id} acts on something")
            entry.defaultHow?.let { assertTrue(it in entry.hows, "${entry.id}'s default How is one it takes") }
        }
        assertEquals(SurfaceCatalogue.entries.size, SurfaceCatalogue.entries.map { it.id }.toSet().size, "entry ids are unique")
    }

    @Test
    fun `a stored binding names the entry it was made from`() {
        assertEquals("grandMaster", SurfaceCatalogue.entryOf(Does.GrandMaster()))
        assertEquals("grandMasterLevel", SurfaceCatalogue.entryOf(Does.GrandMaster(40.0)))
        assertEquals("tempo", SurfaceCatalogue.entryOf(Does.Tempo()))
        assertEquals("tempoSet", SurfaceCatalogue.entryOf(Does.Tempo(bpm = 128.0)))
        assertEquals("level", SurfaceCatalogue.entryOf(Does.Level("pan")))
        assertEquals(null, SurfaceCatalogue.entryOf(Does.Unknown("future", "{}")))
        for (entry in SurfaceCatalogue.entries) assertNotNull(SurfaceCatalogue.entry(entry.id))
    }

    @Test
    fun `the catalogue fixture is the catalogue the desk serves`() {
        pinned("catalogue.fixture.json", json.encodeToString(ListSerializer(CatalogueEntry.serializer()), SurfaceCatalogue.entries))
    }

    @Test
    fun `the map fixture is what the desk serves for a sample of bindings`() {
        pinned("map.fixture.json", json.encodeToString(SurfaceMapFrame.serializer(), sampleFrame()))
    }

    @Test
    fun `a dead binding's part says why in words`() {
        val frame = sampleFrame()
        val dead = frame.layers.getValue("layer-a").getValue("btn-4:MAIN")
        assertEquals("missing cue stack $missingStack", dead.healthText)
        assertEquals("GO · A missing cue stack", dead.label)
    }

    @Test
    fun `the map has a no-layer entry beside each layer, holding only what fires on both`() {
        val frame = sampleFrame()
        assertEquals(setOf("layer-a", "layer-b", ""), frame.layers.keys)
        assertEquals(PartSource.STRIP, frame.layers.getValue("").getValue("fader-1:MAIN").from)
        assertEquals(PartSource.DIRECT, frame.layers.getValue("layer-b").getValue("fader-1:MAIN").from)
    }

    @Test
    fun `only the types in use are mapped, and one that drops out keeps the frame that empties it`() {
        val service = ControlSurfaceBindingService(FakeDatabase.instance)
        var attached = emptySet<String>()
        val maps = SurfaceMapService(
            bindingService = service,
            projectIdProvider = { 7 },
            namesFor = { SurfaceNames.EMPTY },
            encoderPageFor = { EncoderBankState.DEFAULT },
            attachedTypes = { attached },
        )
        service.seedCacheForTest(7, emptyList())
        maps.rebuild()
        assertEquals(emptySet(), maps.maps.value!!.frames.keys)

        attached = setOf("x-touch-compact-standard")
        maps.rebuild()
        assertEquals(setOf("x-touch-compact-standard"), maps.maps.value!!.frames.keys)

        attached = emptySet()
        maps.rebuild()
        assertEquals(setOf("x-touch-compact-standard"), maps.maps.value!!.frames.keys, "built before, so still built")
    }

    private val stack = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val missingStack = UUID.fromString("99999999-1111-4111-8111-111111111111")
    private val master = UUID.fromString("22222222-2222-4222-8222-222222222222")

    /**
     * One of each source and a spread of functions: a strip, a control bound on its own over its
     * strip on one layer, an encoder's push, a selection level, a tempo on a named master, a dead GO.
     */
    private fun sampleFrame(): SurfaceMapFrame {
        val typeKey = "x-touch-compact-standard"
        val profile = ControlSurfaceRegistry.typeFor(typeKey)!!
        val service = ControlSurfaceBindingService(FakeDatabase.instance)
        var id = 0
        fun row(controlId: String, binding: Binding, bank: String? = null, part: ControlPart = ControlPart.MAIN, health: AssignmentHealth = AssignmentHealth.Ok) =
            ControlSurfaceBindingService.ResolvedBinding(
                id = ++id, projectId = 7, deviceTypeKey = typeKey, controlId = controlId, bank = bank, binding = binding,
                takeoverPolicy = if (controlId.startsWith("enc")) BindingTakeoverPolicy.PICKUP else null,
                sortOrder = 0, health = health, part = part,
            )
        service.seedCacheForTest(
            7,
            listOf(
                row("strip-1", Binding(Does.Strip, Subject.Group("front-wash"))),
                row("fader-1", Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")), bank = "layer-b"),
                row("enc-9", Binding(Does.Level("pan"), Subject.Selection)),
                row("enc-9", Binding(Does.Locate, Subject.Selection), part = ControlPart.PUSH),
                row("btn-3", Binding(Does.Go, Subject.Record(RecordKind.STACK, stack))),
                row("btn-4", Binding(Does.Go, Subject.Record(RecordKind.STACK, missingStack)), health = AssignmentHealth.MissingStack(0, missingStack.toString())),
                row("fader-9", Binding(Does.Tempo(), Subject.Record(RecordKind.MASTER, master))),
                row("btn-33", Binding(Does.Blackout, how = How.ON)),
            ),
        )
        val names = SurfaceNames(
            fixtures = mapOf("hex-1" to "Hex 1"),
            groups = mapOf("front-wash" to "Front wash"),
            groupColours = mapOf("front-wash" to "blue"),
            records = mapOf(
                RecordKind.STACK to mapOf(stack to "Act 1"),
                RecordKind.MASTER to mapOf(master to "M2 · Chase"),
            ),
            masterOne = "M1 · Main",
        )
        val full = SurfaceMaps.build(7, profile, service, names, EncoderBankSelection("dimmer"))
        // Only the parts the sample binds, and the strip's other controls, so the fixture stays readable.
        val keep = setOf("strip-1:MAIN", "fader-1:MAIN", "btn-25:MAIN", "enc-1:MAIN", "btn-1:MAIN", "enc-1:PUSH",
            "enc-9:MAIN", "enc-9:PUSH", "btn-3:MAIN", "btn-4:MAIN", "fader-9:MAIN", "btn-33:MAIN", "btn-5:MAIN")
        return full.copy(layers = full.layers.mapValues { (_, parts) -> parts.filterKeys { it in keep } })
    }
}
