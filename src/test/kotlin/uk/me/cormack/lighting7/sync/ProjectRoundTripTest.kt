package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.fixture.media.FittedSlot
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.sync.dto.CuePropertyAssignmentJson
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.BuskPageJson
import uk.me.cormack.lighting7.sync.dto.BuskRigRowJson
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.CueStackJson
import uk.me.cormack.lighting7.sync.dto.LookJson
import uk.me.cormack.lighting7.sync.dto.FixturePatchJson
import uk.me.cormack.lighting7.sync.dto.RiggingJson
import uk.me.cormack.lighting7.sync.dto.StageElementJson
import uk.me.cormack.lighting7.sync.dto.StageRegionJson
import uk.me.cormack.lighting7.sync.dto.StageViewpointJson
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.TemplateJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_NAME
import uk.me.cormack.lighting7.testsupport.assertExportsEqual
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 verification: build a project, export it, wipe the DB, import it, re-export, and
 * assert byte-for-byte identical output. This exercises FK-by-UUID rewriting, canonical
 * determinism, and topological insert order in one scenario.
 *
 * Note what this does and doesn't pin down: because it compares two *exports*, it catches
 * the importer dropping something the exporter wrote, but a field missing from both sides is
 * missing symmetrically and passes. `SyncCoverageTest` guards the exporter side.
 */
class ProjectRoundTripTest {

    private lateinit var state: State
    private lateinit var exportDirA: Path
    private lateinit var exportDirB: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        exportDirA = Files.createTempDirectory("sync-export-a-")
        exportDirB = Files.createTempDirectory("sync-export-b-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { exportDirA.toFile().deleteRecursively() }
        runCatching { exportDirB.toFile().deleteRecursively() }
    }

    @Test
    fun `round-trip preserves project byte-for-byte`() {
        val projectId = seedRichProject(state)

        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
    }

    /**
     * The byte-for-byte test above already proves the importer keeps every field the exporter
     * writes. What it cannot prove is that a *value* template stays free of the new key: a field
     * missing from both sides is missing symmetrically and passes. So this asserts on the export
     * text directly, the way the machine-local address test does.
     */
    @Test
    fun `templates carry an effect only when they hold one`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val templates = Files.list(exportDirA.resolve("templates")).use { stream ->
            stream.toList().map { canonicalDecode(TemplateJson.serializer(), Files.readString(it)) }
        }

        val effectTemplate = templates.single { it.effect != null }
        assertEquals("amber-breathe", effectTemplate.name)
        assertTrue(effectTemplate.rows.isEmpty(), "an effect template holds no rows (D1)")
        val effect = effectTemplate.effect!!
        assertEquals("ColourPulse", effect.effectType)
        assertEquals("colour", effect.category)
        // The off-default fields, which are the ones a copier can silently drop: canonical JSON
        // omits defaults, so a field left at its default is invisible to the round trip.
        assertEquals(0.125, effect.phaseOffset)
        assertEquals("CENTER_OUT", effect.distribution)
        assertEquals("MAX", effect.blendMode)
        assertEquals("EVEN", effect.elementFilter)
        assertEquals(false, effect.stepTiming)
        assertTrue(effect.parameters.getValue("colours").startsWith("tmpl:"))

        // The other two templates hold values, and `explicitNulls = false` must keep the key out
        // of their documents entirely rather than writing `"effect": null`.
        val valueDocs = Files.list(exportDirA.resolve("templates")).use { stream ->
            stream.toList()
                .map { Files.readString(it) }
                .filter { !it.contains("amber-breathe") }
        }
        assertEquals(2, valueDocs.size)
        assertTrue(
            valueDocs.none { it.contains("\"effect\"") },
            "a value template must not carry the effect key at all",
        )
    }

    /**
     * A paired dimmer's other lanterns travel inside their patch's document, in order, with the
     * rigging named by uuid. The byte-for-byte test proves the importer keeps what the exporter
     * writes; this pins what the exporter writes — and that a patch with none carries no key.
     */
    @Test
    fun `extra placements export embedded in their patch in order`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val patches = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }
        val hex2 = patches.single { it.key == "hex-2" }
        assertEquals(listOf("SR", null), hex2.extraPlacements.map { it.label })
        val sr = hex2.extraPlacements.first()
        assertEquals(3.5, sr.stageX)
        assertEquals(180.0, sr.baseYawDeg)
        assertEquals(-15.0, sr.basePitchDeg)
        val foh = Files.list(exportDirA.resolve("riggings")).use { stream ->
            stream.toList().map { canonicalDecode(RiggingJson.serializer(), Files.readString(it)) }
        }.single { it.name == "FOH Truss" }
        assertEquals(foh.uuid, sr.riggingUuid, "a placement names its rigging by uuid")
        assertEquals(null, hex2.extraPlacements[1].riggingUuid)

        val paired = setOf("hex-2", "ring-1", "adv2-1", "rev-1")
        val unpaired = docs.filter { doc -> paired.none { doc.contains("\"$it\"") } }
        assertEquals(patches.size - paired.size, unpaired.size)
        assertTrue(
            unpaired.none { it.contains("\"extraPlacements\"") },
            "a patch with no extra placements must not carry the key at all",
        )
    }

    /**
     * v16: a head number travels on its patch, and an unnumbered patch carries no key — so an
     * export of a rig with no numbers is v15's byte for byte.
     */
    @Test
    fun `a patch exports its head number, and an unnumbered one carries no key`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        fun heads(dir: Path): Map<String, Int?> =
            Files.list(dir.resolve("fixturePatches")).use { stream ->
                stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
            }.associate { it.key to it.headNumber }
        val exported = heads(exportDirA)
        assertEquals(mapOf("hex-1" to 104, "hex-2" to 103, "hex-3" to 102, "hex-4" to null), exported.filterKeys { it.startsWith("hex-") })
        val unnumbered = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }.filter { it.contains("\"hex-4\"") }
        assertFalse(unnumbered.single().contains("\"headNumber\""), "an unnumbered patch must not carry the key at all")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertEquals(exported, heads(exportDirB), "the importer keeps every head number")
    }

    /**
     * v15: a lightstrip's installed length travels on its patch, and a segment's own length on its
     * placement. A patch without one carries no key, so a length-less export is v14's byte for byte.
     */
    @Test
    fun `a variable-length fixture exports its length and its segments' lengths`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val ring = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "ring-1" }
        assertEquals(8.5, ring.lengthM)
        assertEquals(12.25, ring.extraPlacements.single().lengthM)
        assertTrue(
            docs.filter { !it.contains("\"ring-1\"") }.none { it.contains("\"lengthM\"") },
            "a patch with no length must not carry the key at all",
        )

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "ring-1" }
        assertEquals(8.5, back.lengthM, "the importer keeps the patch's length")
        assertEquals(12.25, back.extraPlacements.single().lengthM, "and the segment's")
    }

    /** v17: a body's roll, on the patch and on a placement, and absent where it is unset. */
    @Test
    fun `a rolled fixture exports its roll and its segments' rolls`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val ring = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "ring-1" }
        assertEquals(-12.5, ring.baseRollDeg)
        assertEquals(90.0, ring.extraPlacements.single().baseRollDeg)
        assertTrue(
            docs.filter { !it.contains("\"ring-1\"") }.none { it.contains("\"baseRollDeg\"") },
            "a patch with no roll must not carry the key at all",
        )

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "ring-1" }
        assertEquals(-12.5, back.baseRollDeg, "the importer keeps the patch's roll")
        assertEquals(90.0, back.extraPlacements.single().baseRollDeg, "and the segment's")
    }

    /**
     * v19: a lantern and its focus, on the patch and on a placement, and absent where unset — a
     * patch with no lantern carries none of the keys, so a lantern-less export is v18's byte for byte.
     */
    @Test
    fun `a lantern and its focus export on the patch and its placement`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val adv2 = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "adv2-1" }
        assertEquals("s4-zoom-25-50", adv2.lanternType)
        assertEquals(31.5, adv2.zoomDeg)
        assertEquals(listOf(0.28, 0.05, 0.35, 0.0), adv2.shutters!!.map { it.depth })
        assertEquals(-12.5, adv2.shutters[2].angleDeg)
        assertEquals(7.5, adv2.gateRotationDeg)
        assertEquals(0.8, adv2.iris)
        assertEquals(0.25, adv2.focusSoftness)
        val sr = adv2.extraPlacements.single()
        assertEquals("par64-cp62", sr.lanternType)
        assertEquals(45.0, sr.lampRotationDeg)
        assertEquals(0.9, sr.focusSoftness)
        for (key in listOf("lanternType", "zoomDeg", "lampRotationDeg", "shutters", "gateRotationDeg", "iris", "focusSoftness")) {
            assertTrue(
                docs.filter { !it.contains("\"adv2-1\"") }.none { it.contains("\"$key\"") },
                "a patch with no lantern must not carry $key",
            )
        }

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "adv2-1" }
        assertEquals(adv2, back, "the importer keeps the lantern and every focus field, on the patch and the placement")
    }

    /**
     * v22: fitted media, on the patch and on a placement, and absent where unset — a patch with
     * nothing fitted carries no key, so an export of a rig with nothing fitted is v21's byte for byte.
     */
    @Test
    fun `fitted media exports on the patch and its placement`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        assertTrue(
            Files.readString(exportDirA.resolve("formatVersion.json")).contains("\"formatVersion\": 22"),
            "the writer stamps v22",
        )
        assertEquals(22, SUPPORTED_FORMAT_VERSION)
        val docs = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val rev = docs.map { canonicalDecode(FixturePatchJson.serializer(), it) }.single { it.key == "rev-1" }
        val media = assertNotNull(rev.media)
        assertEquals(FittedSlot(gel = "R26"), media.slot("gelScroller", "L201_FULL_CT_BLUE"))
        assertEquals(FittedSlot(), media.slot("gelScroller", "R02_BASTARD_AMBER"), "an empty frame travels as fitted")
        assertEquals(FittedSlot(gobo = "breakup"), media.slot("fbWheelPos", "SLOT_1"))
        val sl = rev.extraPlacements.single()
        assertEquals(FittedSlot(gel = "R80"), sl.media?.slot("gelScroller", "L201_FULL_CT_BLUE"))
        assertEquals(FittedSlot(gel = "L202"), sl.media?.slot("mediaFrame", "IN"))
        assertTrue(
            docs.filter { !it.contains("\"rev-1\"") }.none { it.contains("\"media\"") },
            "a patch with nothing fitted must not carry media",
        )

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "rev-1" }
        assertEquals(rev, back, "the importer keeps the fitted media, on the patch and the placement")
    }

    /**
     * v10: a busk page travels as one document with columns, banks and pads nested, and every
     * structural field written even at zero. The byte-for-byte test proves the importer keeps what
     * the exporter writes; this pins what the exporter writes.
     */
    @Test
    fun `busk pages export nested with every position written`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val docs = Files.list(exportDirA.resolve("buskPages")).use { stream -> stream.toList().map { Files.readString(it) } }
        val doc = docs.single()
        val page = canonicalDecode(BuskPageJson.serializer(), doc)
        assertEquals("act-one", page.name)
        assertEquals(2, page.sortOrder, "the off-default position must survive the export")
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), page.columns.map { it.row to it.sortOrder })
        assertTrue(doc.contains("\"row\": 0"), "a zero position is written, not omitted")
        assertTrue(doc.contains("\"solo\": false"), "a false solo is written, not omitted")
        val banks = page.columns.flatMap { it.banks }
        assertEquals(listOf("keys", "moves", "cues", "fx"), banks.map { it.name })
        assertEquals(listOf("WRAP", "COLUMN", "WRAP", "WRAP"), banks.map { it.flow })
        assertEquals(7, banks.sumOf { it.pads.size })
        val keys = banks.first()
        assertTrue(keys.solo)
        assertEquals(listOf("templateUuid", "lookUuid", "cueUuid"), keys.pads.map { pad ->
            listOfNotNull(pad.templateUuid?.let { "templateUuid" }, pad.lookUuid?.let { "lookUuid" }, pad.cueUuid?.let { "cueUuid" }).single()
        })

        val slots = Files.list(exportDirA.resolve("cueSlots")).use { stream ->
            stream.toList().map { canonicalDecode(CueSlotJson.serializer(), Files.readString(it)) }
        }
        assertEquals(1, slots.count { it.lookUuid != null }, "the Look slot travels")
    }

    /**
     * v12: the rig travels as one document per row with its tiles nested, naming groups and patches
     * by uuid and a cell by its key, every structural field written. The byte-for-byte test proves
     * the importer keeps what the exporter writes; this pins what the exporter writes.
     */
    @Test
    fun `the busk rig exports one document per row with every tile field written`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val rows = Files.list(exportDirA.resolve("buskRig")).use { stream ->
            stream.toList().map { canonicalDecode(BuskRigRowJson.serializer(), Files.readString(it)) }
        }.sortedBy { it.sortOrder }
        assertEquals(listOf("Bars", "Wash"), rows.map { it.name })
        assertEquals(listOf(0, 1), rows.map { it.sortOrder })
        val bars = rows[0]
        assertEquals("WRAP" to 6, bars.flow to bars.width, "the row's layout travels")
        assertEquals("SCROLL" to 12, rows[1].flow to rows[1].width, "a row with no layout of its own is the defaults")
        assertEquals(listOf("HALVES", "PER_CELL", "WHOLE"), bars.tiles.map { it.cellMode })
        assertEquals(listOf(3, null, null), bars.tiles.map { it.cellSplit })
        assertEquals(listOf(null, null, "bar-1.pixel-3"), bars.tiles.map { it.elementKey })
        assertEquals(listOf(null, null, "Pixel 4"), bars.tiles.map { it.label })
        assertTrue(bars.tiles.all { it.patchUuid != null && it.groupUuid == null })
        val wash = rows[1]
        assertEquals("Front", wash.tiles[0].label)
        assertTrue(wash.tiles[0].groupUuid != null && wash.tiles[0].patchUuid == null, "a group tile names its group")
        assertEquals("WHOLE", wash.tiles[1].cellMode)
        val text = Files.readString(Files.list(exportDirA.resolve("buskRig")).use { it.findFirst().get() })
        assertTrue(text.contains("\"sortOrder\": 0"), "a zero position is written, not omitted")
    }

    /**
     * The scene document (v18): each element's params export as a **nested object**, keys sorted,
     * so the clone remapper reaches a platform's `regionUuid` and a diff reads per field; a seat
     * view names its seating by uuid. Pins what the exporter writes, as the rig test above does.
     */
    @Test
    fun `the scene exports its elements with params nested and a seat view naming its seating`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val elements = Files.list(exportDirA.resolve("stageElements")).use { stream ->
            stream.toList().map { canonicalDecode(StageElementJson.serializer(), Files.readString(it)) }
        }.associateBy { it.name }
        assertEquals(setOf("Stalls", "Thrust deck", "House tabs"), elements.keys)
        val stalls = elements.getValue("Stalls")
        assertEquals("SEATING" to "VENUE", stalls.kind to stalls.layer)
        assertEquals("B", stalls.params!!["firstRow"]!!.jsonPrimitive.content)
        assertTrue(stalls.hidden && stalls.emissive)
        val tabs = elements.getValue("House tabs")
        assertEquals(1.0, tabs.params!!["states"]!!.jsonObject["open"]!!.jsonPrimitive.double)

        val regions = Files.list(exportDirA.resolve("stageRegions")).use { stream ->
            stream.toList().map { canonicalDecode(StageRegionJson.serializer(), Files.readString(it)) }
        }
        val thrust = regions.single { it.name == "thrust" }
        assertEquals(thrust.uuid, elements.getValue("Thrust deck").params!!["regionUuid"]!!.jsonPrimitive.content)

        val text = Files.readString(exportDirA.resolve("stageElements/${stalls.uuid}.json"))
        assertTrue(text.contains("\"params\": {"), "params travel as an object, not a string: $text")
        assertTrue(text.indexOf("\"firstRow\"") < text.indexOf("\"rows\""), "params keys are sorted")

        val views = Files.list(exportDirA.resolve("stageViewpoints")).use { stream ->
            stream.toList().map { canonicalDecode(StageViewpointJson.serializer(), Files.readString(it)) }
        }.associateBy { it.name }
        val seat = views.getValue("Row F centre")
        assertEquals("SEAT", seat.kind)
        assertEquals(stalls.uuid to "F5", seat.seatElementUuid to seat.seatId)
        assertEquals(null, seat.eyeX, "a seat view carries no eye of its own")
        assertEquals(1.25, views.getValue("Balcony desk").eyeX)
    }

    /**
     * v20: scenery travels embedded in its owner — a cue, a stack, a Look — each change naming its
     * element by uuid with its states as an object, and only a cue's carrying a transition.
     */
    @Test
    fun `scenery exports embedded in its cue, stack and Look, the element by uuid`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val elements = Files.list(exportDirA.resolve("stageElements")).use { stream ->
            stream.toList().map { canonicalDecode(StageElementJson.serializer(), Files.readString(it)) }
        }.associate { it.name to it.uuid }
        val cue = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(listOf(elements["House tabs"], elements["Thrust deck"]), cue.scenery.map { it.elementUuid })
        assertEquals(listOf(4000L, 1500L), cue.scenery.map { it.transitionMs })
        assertEquals(0.0, cue.scenery[0].state["open"]!!.jsonPrimitive.double)
        assertEquals(listOf(0, 1), cue.scenery.map { it.sortOrder })

        val stack = Files.list(exportDirA.resolve("cueStacks")).use { stream ->
            stream.toList().map { canonicalDecode(CueStackJson.serializer(), Files.readString(it)) }
        }.single { it.name == "show-1" }
        assertEquals(listOf(elements["Stalls"], elements["House tabs"]), stack.scenery.map { it.elementUuid })
        assertTrue(stack.scenery.all { it.transitionMs == null }, "a stack's set has no clock")

        val look = Files.list(exportDirA.resolve("looks")).use { stream ->
            stream.toList().map { canonicalDecode(LookJson.serializer(), Files.readString(it)) }
        }.single { it.name == "Warm Amber" }
        assertEquals(0.75, look.scenery.single { it.elementUuid == elements["House tabs"] }.state["open"]!!.jsonPrimitive.double)

        val text = Files.readString(exportDirA.resolve("cues/${cue.uuid}.json"))
        assertTrue(text.contains("\"state\": {"), "states travel as an object, not a string: $text")
    }

    /**
     * A scenery change naming an element the archive does not carry has lost its element, which
     * its delete would have swept on the writing desk: the pull drops it and keeps the rest.
     */
    @Test
    fun `a scenery change naming an element the archive lacks is dropped and its cue survives`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val tabs = Files.list(exportDirA.resolve("stageElements")).use { stream ->
            stream.toList().first { Files.readString(it).contains("\"name\": \"House tabs\"") }
        }
        Files.delete(tabs)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val cue = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(1, cue.scenery.size, "the tabs' change went, the deck's stayed")
        assertEquals(1500L, cue.scenery.single().transitionMs)
    }

    /**
     * An owner says one thing about each element, and the tables hold it to that with a unique
     * index. An archive that says it twice — a hand edit, a merge that kept both sides — keeps the
     * first by order rather than failing the whole import.
     */
    @Test
    fun `a scenery change naming its element a second time is dropped and the first kept`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val cueFile = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().first { canonicalDecode(CueJson.serializer(), Files.readString(it)).name == "open" }
        }
        val written = canonicalDecode(CueJson.serializer(), Files.readString(cueFile))
        val first = written.scenery.first()
        val again = first.copy(uuid = java.util.UUID.randomUUID().toString(), transitionMs = 9000, sortOrder = written.scenery.size)
        Files.writeString(cueFile, canonicalEncode(CueJson.serializer(), written.copy(scenery = written.scenery + again)))

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val cue = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(written.scenery.map { it.elementUuid to it.transitionMs }, cue.scenery.map { it.elementUuid to it.transitionMs })
    }

    /**
     * v21: a cue's events travel embedded in the cue, the patch by uuid, and come back as they went;
     * a spent tube is this machine's and travels nowhere.
     */
    @Test
    fun `cue events export embedded in their cue and round-trip, and tube state stays home`() {
        val projectId = seedRichProject(state)
        transaction(state.database) {
            val cannon = uk.me.cormack.lighting7.models.DaoFixturePatch.find {
                uk.me.cormack.lighting7.models.DaoFixturePatches.key eq "cannon-1"
            }.single()
            uk.me.cormack.lighting7.models.DaoEffectTubeStates.insert {
                it[patchUuid] = cannon.uuid
                it[trigger] = "output1"
                it[spentAt] = uk.me.cormack.lighting7.models.nowUtc()
            }
        }
        ProjectExporter(state).export(projectId, exportDirA)

        val cannonUuid = Files.list(exportDirA.resolve("fixturePatches")).use { stream ->
            stream.toList().map { canonicalDecode(FixturePatchJson.serializer(), Files.readString(it)) }
        }.single { it.key == "cannon-1" }.uuid
        val cue = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(listOf("output1" to 600L, "output2" to 750L), cue.events.map { it.trigger to it.offsetMs })
        assertTrue(cue.events.all { it.patchUuid == cannonUuid })
        assertEquals(listOf(0, 1), cue.events.map { it.sortOrder })
        val everything = Files.walk(exportDirA).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            .joinToString("\n") { Files.readString(it) }
        assertTrue("spentAt" !in everything && "spent_at" !in everything, "tube state never travels")

        wipeDatabase()
        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(cue.events, back.events)
    }

    /**
     * An event naming a patch the archive lacks, a trigger its type does not have, or a tube a second
     * time is dropped with the cue kept; and a pre-v21 archive's row naming a trigger is stripped.
     */
    @Test
    fun `an event that no longer makes sense is dropped, and a stored trigger row is stripped on import`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val cueFile = Files.list(exportDirA.resolve("cues")).use { stream ->
            stream.toList().first { canonicalDecode(CueJson.serializer(), Files.readString(it)).name == "open" }
        }
        val written = canonicalDecode(CueJson.serializer(), Files.readString(cueFile))
        val first = written.events.first()
        val bogus = listOf(
            first.copy(uuid = java.util.UUID.randomUUID().toString(), sortOrder = 5),
            first.copy(uuid = java.util.UUID.randomUUID().toString(), trigger = "output9", sortOrder = 6),
            first.copy(uuid = java.util.UUID.randomUUID().toString(), patchUuid = java.util.UUID.randomUUID().toString(), sortOrder = 7),
        )
        Files.writeString(cueFile, canonicalEncode(CueJson.serializer(), written.copy(events = written.events + bogus)))
        // A pre-v21 cue row raising a tube, as the Twin Shot's sliders once allowed.
        val rowDir = exportDirA.resolve("cuePropertyAssignments")
        Files.createDirectories(rowDir)
        val raised = CuePropertyAssignmentJson(
            uuid = java.util.UUID.randomUUID().toString(), cueUuid = written.uuid,
            targetType = "fixture", targetKey = "cannon-1", propertyName = "output1", value = "255",
        )
        Files.writeString(rowDir.resolve("${raised.uuid}.json"), canonicalEncode(CuePropertyAssignmentJson.serializer(), raised))

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val back = Files.list(exportDirB.resolve("cues")).use { stream ->
            stream.toList().map { canonicalDecode(CueJson.serializer(), Files.readString(it)) }
        }.single { it.name == "open" }
        assertEquals(written.events.map { it.trigger }, back.events.map { it.trigger })
        val rows = Files.list(exportDirB.resolve("cuePropertyAssignments")).use { stream ->
            stream.toList().map { canonicalDecode(CuePropertyAssignmentJson.serializer(), Files.readString(it)) }
        }
        assertTrue(rows.none { it.propertyName == "output1" }, "the raised tube was stripped")
        assertTrue(rows.isNotEmpty(), "the cue's other rows came through")
    }

    /**
     * An archive written before v18 has neither folder: it imports with an empty scene.
     */
    @Test
    fun `an archive without a scene imports with an empty scene`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        exportDirA.resolve("stageElements").toFile().deleteRecursively()
        exportDirA.resolve("stageViewpoints").toFile().deleteRecursively()
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            val project = DaoProject.findById(imported.projectId)!!
            assertTrue(project.stageElements.empty() && project.stageViewpoints.empty())
            assertEquals(2, project.stageRegions.count().toInt(), "the rest of the stage imports")
        }
    }

    /**
     * The importer's wipe order (busk-further plan §3.2): a rig tile is a plain FK onto a group or a
     * patch with no cascade, so replacing a project must sweep the rig **before** it deletes the
     * groups and patches — otherwise the FK blocks them. Proved by replacing a project that holds
     * a rig with an archive that also holds one: the replace succeeds and the rig is the archive's.
     */
    @Test
    fun `replacing a project sweeps its rig before its groups and patches, then imports the archive's`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        ProjectImporter(state).replaceFromWorkingTree(projectId, exportDirA)
        ProjectExporter(state).export(projectId, exportDirB)
        assertExportsEqual(exportDirA, exportDirB, installsShapeOnly = true)
        val rows = Files.list(exportDirB.resolve("buskRig")).use { it.toList() }
        assertEquals(2, rows.size, "the rig came back from the archive, once")
    }

    /** A v11 archive has no `buskRig/`, and imports as an empty rig — today's band (D1). */
    @Test
    fun `an archive without a rig folder imports as an empty rig`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        exportDirA.resolve("buskRig").toFile().deleteRecursively()
        Files.writeString(
            exportDirA.resolve("formatVersion.json"),
            Files.readString(exportDirA.resolve("formatVersion.json")).replace("\"formatVersion\": $SUPPORTED_FORMAT_VERSION", "\"formatVersion\": 11"),
        )

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        assertTrue(!Files.exists(exportDirB.resolve("buskRig")) || Files.list(exportDirB.resolve("buskRig")).use { it.count() } == 0L)
    }

    /**
     * A tile naming a group or patch the archive does not carry is an enrichment that has lost its
     * record, so the pull continues without it — the pad's posture — and a row left empty goes too.
     */
    @Test
    fun `a rig tile naming a record the archive lacks is dropped and the rig survives`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val washFile = Files.list(exportDirA.resolve("buskRig")).use { stream ->
            stream.toList().first { Files.readString(it).contains("\"name\": \"Wash\"") }
        }
        val original = Files.readString(washFile)
        val corrupt = original.replaceFirst(
            Regex("\"groupUuid\": \"[0-9a-f-]+\""),
            "\"groupUuid\": \"00000000-0000-0000-0000-000000000000\"",
        )
        assertTrue(corrupt != original, "test sanity: the group tile was rewritten")
        Files.writeString(washFile, corrupt)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)
        val rows = Files.list(exportDirB.resolve("buskRig")).use { stream ->
            stream.toList().map { canonicalDecode(BuskRigRowJson.serializer(), Files.readString(it)) }
        }
        val wash = rows.single { it.name == "Wash" }
        assertEquals(1, wash.tiles.size, "one tile fewer, nothing else lost")
        assertEquals(3, rows.single { it.name == "Bars" }.tiles.size)
    }

    /**
     * A pad naming a record the archive does not carry is an enrichment that has lost its record,
     * so the pull continues without it — the v9 template-group posture, and the opposite of the cue
     * stack case below.
     */
    @Test
    fun `a busk pad naming a record the archive lacks is dropped and the page survives`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val pageFile = Files.list(exportDirA.resolve("buskPages")).use { it.findFirst().get() }
        val original = Files.readString(pageFile)
        val corrupt = original.replaceFirst(
            Regex("\"lookUuid\": \"[0-9a-f-]+\""),
            "\"lookUuid\": \"00000000-0000-0000-0000-000000000000\"",
        )
        assertTrue(corrupt != original, "test sanity: a Look pad was rewritten")
        Files.writeString(pageFile, corrupt)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        val page = Files.list(exportDirB.resolve("buskPages")).use { stream ->
            canonicalDecode(BuskPageJson.serializer(), Files.readString(stream.findFirst().get()))
        }
        val banks = page.columns.flatMap { it.banks }
        assertEquals(6, banks.sumOf { it.pads.size }, "one pad fewer, nothing else lost")
        assertEquals(listOf("keys", "moves", "cues", "fx"), banks.map { it.name })
        assertEquals(0, banks.first { it.name == "keys" }.pads.count { it.lookUuid != null }, "the dropped pad was the keys bank's Look")
        assertEquals(2, banks.first { it.name == "keys" }.pads.size)
    }

    /**
     * A v10 archive written *before* the cue-stack arm was removed carries slots whose only
     * reference is `cueStackUuid` — an unknown key now, so the slot decodes naming nothing. That
     * one case warns and drops rather than failing the pull: a slot, like a busk pad, is an
     * enrichment of the record it names. Two arms still aborts, which the assign route's own test
     * pins on the write side.
     */
    @Test
    fun `a cue slot naming nothing is dropped and the rest of the archive imports`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        // Stand in for the old shape: strip the one arm this slot has and put the retired key back.
        val slotFiles = Files.list(exportDirA.resolve("cueSlots")).use { it.toList() }
        val lookSlot = slotFiles.single { Files.readString(it).contains("lookUuid") }
        val rewritten = Files.readString(lookSlot).replaceFirst(
            Regex("\"lookUuid\": \"[0-9a-f-]+\""),
            "\"cueStackUuid\": \"00000000-0000-0000-0000-000000000000\"",
        )
        assertTrue(rewritten.contains("cueStackUuid"), "test sanity: the slot was rewritten")
        Files.writeString(lookSlot, rewritten)

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        ProjectExporter(state).export(imported.projectId, exportDirB)

        val slots = Files.list(exportDirB.resolve("cueSlots")).use { stream ->
            stream.toList().map { canonicalDecode(CueSlotJson.serializer(), Files.readString(it)) }
        }
        assertEquals(slotFiles.size - 1, slots.size, "one slot fewer, nothing else lost")
        assertEquals(0, slots.count { it.lookUuid != null }, "the dropped slot was the Look's")
        assertTrue(slots.all { it.cueUuid != null }, "every surviving slot still names a cue")
    }

    @Test
    fun `import refuses duplicate project UUID`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        // Project still exists in the DB; importing the same export must refuse.
        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = "different-name")
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
        assertTrue(ex.message?.contains("UUID") == true)
    }

    @Test
    fun `import refuses on name collision`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()
        // Re-create a project with the same name but a different UUID — name collision.
        transaction(state.database) {
            DaoProject.new {
                name = RICH_PROJECT_NAME
                description = "blocker"
                isCurrent = false
            }
        }
        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.Conflict, ex.status)
    }

    @Test
    fun `installs json contains the local install identity on export`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val installsFile = exportDirA.resolve("installs.json")
        val installs = canonicalDecode(InstallsJson.serializer(), Files.readString(installsFile))
        val (localUuid, localFriendlyName) = transaction(state.database) {
            val row = DaoInstall.all().first()
            row.uuid.toString() to row.friendlyName
        }
        assertEquals(1, installs.installs.size, "exactly one local install entry expected")
        assertEquals(localFriendlyName, installs.installs[localUuid])
    }

    @Test
    fun `universe config exporter strips machine-local address field`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val universeDir = exportDirA.resolve("universeConfigs")
        val first = Files.list(universeDir).use { it.findFirst().get() }
        val text = Files.readString(first)
        // address is the machine-local IP per docs/plans/cloud-sync.md — must never be in JSON.
        assertFalse(text.contains("\"address\""), "address field leaked into export: $text")
        // Round-trips without trouble — the DTO doesn't have an address field at all.
        canonicalDecode(UniverseConfigJson.serializer(), text)
    }

    @Test
    fun `import forces isCurrent false even if source project was current`() {
        // The seeded project is current. Export it, wipe, import — imported project must NOT
        // be marked current; otherwise importing would silently reassign which project the user
        // is operating on.
        val projectId = seedRichProject(state)
        transaction(state.database) {
            DaoProject.findById(projectId)!!.isCurrent = true
        }
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(exportDirA, nameOverride = null)
        transaction(state.database) {
            assertEquals(false, DaoProject.findById(imported.projectId)!!.isCurrent)
        }
    }

    @Test
    fun `import applies description override`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        val imported = ProjectImporter(state).import(
            exportDirA,
            nameOverride = "described",
            descriptionOverride = "a new description",
        )
        transaction(state.database) {
            assertEquals("a new description", DaoProject.findById(imported.projectId)!!.description)
        }
    }

    @Test
    fun `import with bad cue stack reference rolls back atomically`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)
        wipeDatabase()

        // Corrupt one cue's cueStackUuid so FK resolution fails mid-import.
        val cueDir = exportDirA.resolve("cues")
        val firstCue = Files.list(cueDir).use { it.findFirst().get() }
        val original = Files.readString(firstCue)
        val corrupt = original.replace(
            Regex("\"cueStackUuid\": \"[0-9a-f-]+\""),
            "\"cueStackUuid\": \"00000000-0000-0000-0000-000000000000\""
        )
        Files.writeString(firstCue, corrupt)

        val ex = assertFailsWith<ImportError> {
            ProjectImporter(state).import(exportDirA, nameOverride = null)
        }
        assertEquals(io.ktor.http.HttpStatusCode.BadRequest, ex.status)
        // No partial state — the project that would have been inserted must be absent.
        transaction(state.database) {
            assertEquals(0, DaoProject.all().count(),
                "import that errored mid-flight should leave DB empty")
        }
    }

    private fun wipeDatabase() {
        // Reset to a fresh SQLite file and rebuild the State / schema. Simpler than DELETE
        // cascade because the FK graph is wide; the test just needs an empty DB.
        runCatching { state.shutdown() }
        IntegrationTestDb.reset()
        state = State(testAppConfig())
    }
}
