package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.core.Table
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.ALL_TABLES
import uk.me.cormack.lighting7.models.DaoAiConversations
import uk.me.cormack.lighting7.models.DaoBuskBanks
import uk.me.cormack.lighting7.models.DaoBuskColumns
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoBuskPages
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoCueAdHocEffects
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.models.DaoCueSlots
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCueTriggers
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLookEffects
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoTemplateEffects
import uk.me.cormack.lighting7.models.DaoTemplateGroups
import uk.me.cormack.lighting7.models.DaoTemplateRows
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.DaoInstalls
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoOAuthIdentities
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoProjectScalerStates
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoPromptBookAnchors
import uk.me.cormack.lighting7.models.DaoPromptBookAnnotations
import uk.me.cormack.lighting7.models.DaoPromptBooks
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncLinkedRepos
import uk.me.cormack.lighting7.models.DaoSyncLogEntries
import uk.me.cormack.lighting7.models.DaoSyncSessionConflicts
import uk.me.cormack.lighting7.models.DaoSyncSessions
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.models.DaoPasswordResetTokens
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.DaoUserSessions
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Forces a sync decision for every table in the schema, and forces the exporter to honour it.
 *
 * The pre-existing round-trip test compares two *exports*, so it can only catch the importer
 * dropping something the exporter wrote — a column missing from both sides is missing
 * symmetrically and passes. That gap is what let the clone path (and, in principle, the export
 * path) fall behind the schema. This test closes it from the other end:
 *
 *  1. every table in [ALL_TABLES] must have a [Disposition] recorded here — adding a table
 *     without answering "portable, machine-local, or transient?" fails the build;
 *  2. every table declared [Disposition.Portable] must actually produce output when the rich
 *     fixture is exported, so declaring a table portable without wiring it into
 *     `ProjectExporter` fails too;
 *  3. the fixture itself must populate every portable table, which is what makes
 *     `ProjectRoundTripTest` and `ProjectCloneTest` meaningful.
 *
 * Its granularity is the table, not the column: a portable table whose *fields* are partly
 * missing from the exporter still satisfies (2). Field-level fidelity is the fixture's job —
 * see the note in `RichProjectFixture`.
 */
class SyncCoverageTest {

    /** What a table's rows are, as far as cloud sync and project cloning are concerned. */
    private sealed interface Disposition {
        /**
         * Portable show content: exported to [exportDir] as canonical JSON, imported back, and
         * carried by [ProjectCloner]. [embeddedField] names the JSON array this table's rows
         * are nested into when it has no folder of its own (group members, preset assignments).
         */
        data class Portable(val exportDir: String, val embeddedField: String? = null) : Disposition

        /** The project row itself — exported as `project.json`, not as a record folder. */
        data object ProjectRoot : Disposition

        /** Per-rig or per-install data, deliberately absent from the export. */
        data class MachineLocal(val why: String) : Disposition

        /** Transient or non-show data that no export, import or clone should carry. */
        data class Excluded(val why: String) : Disposition
    }

    /**
     * The recorded decision per table. Mirrors the decision tree in `CLAUDE.md`
     * §"Database changes and cloud sync"; keep the two in step.
     */
    private val dispositions: Map<Table, Disposition> = mapOf(
        DaoProjects to Disposition.ProjectRoot,

        DaoScripts to Disposition.Portable("scripts"),
        DaoFxDefinitions to Disposition.Portable("fxDefinitions"),
        DaoLooks to Disposition.Portable("looks"),
        DaoLookRows to Disposition.Portable("looks", "rows"),
        DaoTemplateGroups to Disposition.Portable("templateGroups"),
        DaoTemplates to Disposition.Portable("templates"),
        DaoTemplateRows to Disposition.Portable("templates", "rows"),
        DaoTemplateEffects to Disposition.Portable("templates", "effect"),
        DaoLookEffects to Disposition.Portable("looks", "effects"),
        // Five `Excluded` dispositions stood here and just below — `DaoFxPresets`,
        // `DaoFxPresetPropertyAssignments`, `DaoPalettes`, `DaoPaletteEntries` and
        // `DaoCuePresetApplications`. They were excluded rather than portable at formatVersion 5,
        // because exporting both representations would put two copies of one entity in the repo and
        // materialise both on import. Session 4 deleted the tables, so they are out of `ALL_TABLES`
        // and there is nothing left to have an opinion about. Their pre-v5 shape lived on in
        // `testsupport/LegacySchema.kt` for the migration tests; both went with the migrations on
        // 2026-08-24 (see state/InstallBootstrap.kt).
        // Portable rather than excluded-live-state: look/cue effects reference a master by
        // speedMasterUuid, and that reference only survives clone/import if the masters
        // travel with the show. The exported bpm is a starting default, not a live knob.
        DaoSpeedMasters to Disposition.Portable("speedMasters"),
        DaoUniverseConfigs to Disposition.Portable("universeConfigs"),
        DaoRiggings to Disposition.Portable("riggings"),
        DaoStageRegions to Disposition.Portable("stageRegions"),
        DaoFixturePatches to Disposition.Portable("fixturePatches"),
        DaoFixtureGroups to Disposition.Portable("fixtureGroups"),
        DaoFixtureGroupMembers to Disposition.Portable("fixtureGroups", "members"),
        DaoCueStacks to Disposition.Portable("cueStacks"),
        DaoCues to Disposition.Portable("cues"),
        DaoCuePropertyAssignments to Disposition.Portable("cuePropertyAssignments"),
        DaoCueLayers to Disposition.Portable("cueLayers"),
        DaoCueAdHocEffects to Disposition.Portable("cueAdHocEffects"),
        DaoCueTriggers to Disposition.Portable("cueTriggers"),
        DaoCueSlots to Disposition.Portable("cueSlots"),
        DaoBuskPages to Disposition.Portable("buskPages"),
        DaoBuskColumns to Disposition.Portable("buskPages", "columns"),
        DaoBuskBanks to Disposition.Portable("buskPages", "banks"),
        DaoBuskPads to Disposition.Portable("buskPages", "pads"),
        DaoParkedChannels to Disposition.Portable("parkedChannels"),
        DaoPromptBooks to Disposition.Portable("promptBooks"),
        DaoPromptBookAnchors to Disposition.Portable("promptBookAnchors"),
        DaoPromptBookAnnotations to Disposition.Portable("promptBookAnnotations"),
        DaoControlSurfaceBindings to Disposition.Portable("controlSurfaceBindings"),

        DaoMachineOverrides to Disposition.MachineLocal(
            "per-rig field overrides (controller IPs); cloned same-machine, never exported"
        ),
        DaoInstalls to Disposition.MachineLocal("this install's identity"),
        DaoOAuthIdentities to Disposition.MachineLocal("this install's git credentials"),
        DaoSyncConfigs to Disposition.MachineLocal("per-project sync settings for this install"),
        DaoSyncLinkedRepos to Disposition.MachineLocal("which remote this install is attached to"),
        DaoSyncStates to Disposition.MachineLocal("last-synced record hashes for this install"),
        DaoSyncSessions to Disposition.MachineLocal("in-flight conflict sessions"),
        DaoSyncSessionConflicts to Disposition.MachineLocal("in-flight conflict rows"),
        DaoSyncLogEntries to Disposition.MachineLocal("local sync activity log"),
        DaoUsers to Disposition.MachineLocal("desk-local user accounts; never leave this machine"),
        DaoUserSessions to Disposition.MachineLocal("live login sessions for this desk"),
        DaoPasswordResetTokens to Disposition.MachineLocal("short-lived local password reset tokens"),

        DaoProjectScalerStates to Disposition.Excluded("live blackout / grand-master state"),
        DaoAiConversations to Disposition.Excluded("AI chat scratch history, not show content"),
    )

    private lateinit var state: State
    private lateinit var exportDir: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        exportDir = Files.createTempDirectory("sync-coverage-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { exportDir.toFile().deleteRecursively() }
    }

    @Test
    fun `every table has a recorded sync disposition`() {
        val undeclared = ALL_TABLES.filter { it !in dispositions }.map { it.tableName }
        assertEquals(
            emptyList(), undeclared,
            "New table(s) with no sync disposition. Work through the decision tree in " +
                "CLAUDE.md §\"Database changes and cloud sync\", then record the answer in " +
                "SyncCoverageTest.dispositions. If portable: wire it through ProjectExporter " +
                "and ProjectImporter, add rows to RichProjectFixture, and it is cloned for free.",
        )
    }

    @Test
    fun `disposition map has no entries for tables that no longer exist`() {
        val stale = dispositions.keys.filter { it !in ALL_TABLES }.map { it.tableName }
        assertEquals(emptyList(), stale, "disposition recorded for table(s) not in the schema")
    }

    @Test
    fun `every portable table is written by the exporter`() {
        val projectId = seedRichProject(state)
        ProjectExporter(state).export(projectId, exportDir)

        val missing = mutableListOf<String>()
        val emptyEmbeds = mutableListOf<String>()
        for ((table, disposition) in dispositions) {
            if (disposition !is Disposition.Portable) continue
            val dir = exportDir.resolve(disposition.exportDir)
            val files = if (Files.isDirectory(dir)) {
                Files.list(dir).use { it.toList() }
            } else {
                emptyList()
            }
            if (files.isEmpty()) {
                missing += "${table.tableName} -> ${disposition.exportDir}/"
                continue
            }
            val field = disposition.embeddedField ?: continue
            // Embedded child tables have no folder of their own; assert the parent documents
            // actually carry the array, so a forgotten inline child is caught too.
            val carried = files.any { Files.readString(it).contains("\"$field\"") }
            if (!carried) emptyEmbeds += "${table.tableName} -> ${disposition.exportDir}/*.json[$field]"
        }

        assertEquals(
            emptyList(), missing,
            "portable table(s) produced no export output. Either ProjectExporter does not " +
                "write them, or RichProjectFixture does not seed any rows — both must be fixed.",
        )
        assertEquals(
            emptyList(), emptyEmbeds,
            "embedded portable table(s) missing from their parent's JSON",
        )
    }

    @Test
    fun `machine-local and excluded tables have a documented reason`() {
        val undocumented = dispositions.entries.filter { (_, d) ->
            when (d) {
                is Disposition.MachineLocal -> d.why.isBlank()
                is Disposition.Excluded -> d.why.isBlank()
                else -> false
            }
        }.map { it.key.tableName }
        assertTrue(undocumented.isEmpty(), "non-portable table(s) with no stated reason: $undocumented")
    }
}
