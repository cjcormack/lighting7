package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.BuskPageJson
import uk.me.cormack.lighting7.sync.dto.CueSlotJson
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.TemplateGroupJson
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
     * The v9 twin of the test above: `groupUuid` is a reference, so an ungrouped template must
     * carry no key at all rather than `"groupUuid": null`, and the grouped one must name the
     * group document the `templateGroups/` folder actually holds.
     */
    @Test
    fun `templates carry groupUuid only when grouped`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDirA)

        val groups = Files.list(exportDirA.resolve("templateGroups")).use { stream ->
            stream.toList().map { canonicalDecode(TemplateGroupJson.serializer(), Files.readString(it)) }
        }
        val warmKeys = groups.single()
        assertEquals("warm-keys", warmKeys.name)
        assertEquals(3, warmKeys.sortOrder, "the off-default position must survive the export")

        val docs = Files.list(exportDirA.resolve("templates")).use { stream ->
            stream.toList().map { Files.readString(it) }
        }
        val grouped = docs.filter { it.contains("\"groupUuid\"") }
        assertEquals(1, grouped.size, "exactly one template is grouped")
        val groupedTemplate = canonicalDecode(TemplateJson.serializer(), grouped.single())
        assertEquals("amber-breathe", groupedTemplate.name)
        assertEquals(warmKeys.uuid, groupedTemplate.groupUuid, "the reference names the exported group")
        assertTrue(
            docs.filterNot { it.contains("amber-breathe") }.none { it.contains("\"groupUuid\"") },
            "an ungrouped template must not carry the groupUuid key at all",
        )
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
