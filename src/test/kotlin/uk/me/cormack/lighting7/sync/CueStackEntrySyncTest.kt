package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBookAnchor
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.models.entriesOfCue
import uk.me.cormack.lighting7.models.planEntryOrdinals
import uk.me.cormack.lighting7.models.writeEntryOrder
import uk.me.cormack.lighting7.routes.cueEntryIds
import uk.me.cormack.lighting7.routes.moveEntryTo
import uk.me.cormack.lighting7.routes.renumberAutoEntries
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.dto.CueJson
import uk.me.cormack.lighting7.sync.dto.CueStackEntryJson
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.PromptBookAnchorJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.readExportFiles
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync `formatVersion` 32 (cue model plan session 3, §3.9): a stack's list as one record per entry —
 * `cueStackEntries/{uuid}.json` — with a double ordinal (P6), the number only when explicit (P5), the
 * pre-v32 reader, and the clash rule for two explicit numbers alike after a merge.
 *
 * The round trip over the extended `RichProjectFixture` (markers, an explicit number beside an auto
 * one, a follow, two entries sharing an ordinal) is `ProjectRoundTripTest`'s and `ProjectCloneTest`'s.
 */
class CueStackEntrySyncTest {

    private lateinit var state: State
    private lateinit var workingRoot: Path
    private lateinit var bareRepo: Path
    private lateinit var scratchRoot: Path
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var engine: RemoteSyncEngine

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-entries-")
        bareRepo = Files.createTempDirectory("lighting7-entries-bare-")
        scratchRoot = Files.createTempDirectory("lighting7-entries-scratch-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()
        state = State(testAppConfig("sync.workingTreeRoot" to workingRoot.toString()))
        credentialStore = InMemoryCredentialStore()
        engine = RemoteSyncEngine(state, AuthResolver(credentialStore, tokenStore = null, tokenProvider = null))
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        listOf(workingRoot, bareRepo, scratchRoot).forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    // ── The pre-v32 reader ─────────────────────────────────────────────────────

    /**
     * A v31 archive — each cue's place inside its own file, a marker as a MARKER cue, an anchor naming
     * a cue — imports to the entries §3.9 describes, and writes back as v32.
     */
    @Test
    fun `a pre-v32 archive imports each cue's place as its entry, a marker as a marker, a stackless cue loose`() {
        val dir = Files.createTempDirectory(scratchRoot, "legacy-")
        val project = UUID.randomUUID()
        val stack = UUID.randomUUID()
        val open = UUID.randomUUID()
        val interval = UUID.randomUUID()
        val build = UUID.randomUUID()
        val close = UUID.randomUUID()
        val loose = UUID.randomUUID()
        val book = UUID.randomUUID()
        val onBuild = UUID.randomUUID()
        val onInterval = UUID.randomUUID()
        val onNothing = UUID.randomUUID()
        val region = """[{"page":0,"x":0.1,"y":0.1,"w":0.5,"h":0.05}]"""
        write(dir, "formatVersion.json", """{"formatVersion":31,"minReader":5}""")
        write(dir, "project.json", """{"uuid":"$project","name":"Legacy show"}""")
        write(dir, "cueStacks/$stack.json", """{"uuid":"$stack","name":"Act 1"}""")
        write(dir, "cues/$open.json", """{"uuid":"$open","cueStackUuid":"$stack","name":"Open","sortOrder":0,"cueNumber":"1","autoAdvance":true,"autoAdvanceDelayMs":1500}""")
        write(dir, "cues/$interval.json", """{"uuid":"$interval","cueStackUuid":"$stack","name":"Interval","sortOrder":1,"cueType":"MARKER"}""")
        write(dir, "cues/$build.json", """{"uuid":"$build","cueStackUuid":"$stack","name":"Build","sortOrder":2,"cueNumber":"7","cueNumberAuto":true,"fadeDurationMs":3000}""")
        write(dir, "cues/$close.json", """{"uuid":"$close","cueStackUuid":"$stack","name":"Close","sortOrder":9,"cueNumber":"9"}""")
        write(dir, "cues/$loose.json", """{"uuid":"$loose","name":"Loose"}""")
        write(dir, "promptBooks/$book.json", """{"uuid":"$book","scriptHash":"${"a".repeat(64)}","pageCount":4}""")
        write(dir, "promptBookAnchors/$onBuild.json", """{"uuid":"$onBuild","cueUuid":"$build","region":$region,"label":"LX 2"}""")
        write(dir, "promptBookAnchors/$onInterval.json", """{"uuid":"$onInterval","cueUuid":"$interval","region":$region}""")
        // An anchor naming neither an entry nor a cue is left out, never the end of the import.
        write(dir, "promptBookAnchors/$onNothing.json", """{"uuid":"$onNothing","region":$region}""")

        val imported = ProjectImporter(state).import(dir, nameOverride = null)
        assertTrue(imported.numberClashes.isEmpty())

        transaction(state.database) {
            val p = DaoProject[imported.projectId]
            assertEquals(listOf("Act 1"), DaoCueStack.find { DaoCueStacks.project eq p.id }.map { it.name }, "no Unsorted stack")
            val entries = entriesOf(DaoCueStack.find { DaoCueStacks.project eq p.id }.single())
            // Each legacy cue's entry is named after its cue, a marker after its MARKER cue's file.
            assertEquals(listOf(open, interval, build, close), entries.map { it.uuid })
            // Each ordinal is the place's 0-based rank by sortOrder — the sortOrder itself for an
            // archive a desk with entries wrote, and a gap (Close's 9) closed the same way everywhere.
            assertEquals(listOf(0.0, 1.0, 2.0, 3.0), entries.map { it.ordinal })

            val (eOpen, eInterval, eBuild, eClose) = entries
            assertEquals("Open", eOpen.cue!!.name)
            assertEquals("1", eOpen.cueNumber)
            assertFalse(eOpen.cueNumberAuto)
            assertTrue(eOpen.autoAdvance)
            assertEquals(Duration.ofMillis(1500), eOpen.autoAdvanceDelay)

            assertTrue(eInterval.isMarker)
            assertNull(eInterval.cue)
            assertEquals("Interval", eInterval.label)
            assertNull(DaoCue.find { DaoCues.uuid eq interval }.firstOrNull(), "a marker is not a cue")

            // An auto number is derived again from position, never read ("7" was the old desk's label).
            assertEquals("Build", eBuild.cue!!.name)
            assertTrue(eBuild.cueNumberAuto)
            assertEquals("2", eBuild.cueNumber)
            assertEquals(Duration.ofMillis(3000), eBuild.cue!!.fadeDuration)
            assertEquals("9", eClose.cueNumber)
            assertFalse(eClose.cueNumberAuto)

            val looseCue = DaoCue.find { DaoCues.uuid eq loose }.single()
            assertTrue(entriesOfCue(looseCue).isEmpty(), "a cue with no stack is loose")

            val anchors = DaoPromptBookAnchor.all().associate { it.uuid to it.entry.uuid }
            assertEquals(mapOf(onBuild to build, onInterval to interval), anchors, "each anchor moved to its cue's one entry")
        }

        // Written back at this desk's version — the entries format, v32 or later (v33 added the
        // spread's shape), never the legacy cue-file one.
        val out = Files.createTempDirectory(scratchRoot, "legacy-out-")
        ProjectExporter(state).export(imported.projectId, out)
        val files = readExportFiles(out)
        val writtenVersion = canonicalDecode(FormatVersionJson.serializer(), files.getValue("formatVersion.json")).formatVersion
        assertEquals(SUPPORTED_FORMAT_VERSION, writtenVersion)
        assertTrue(writtenVersion >= CUE_ENTRIES_FORMAT_VERSION, "the entries format")
        files.filterKeys { it.startsWith("cues/") }.values.forEach { text ->
            listOf("cueStackUuid", "sortOrder", "cueNumber", "autoAdvance", "cueType").forEach { gone ->
                assertFalse("\"$gone\"" in text, "a v32 cue file carries no $gone: $text")
            }
        }
        val written = files.filterKeys { it.startsWith("cueStackEntries/") }.values
            .map { canonicalDecode(CueStackEntryJson.serializer(), it) }.associateBy { UUID.fromString(it.uuid) }
        assertEquals(setOf(open, interval, build, close), written.keys)
        assertEquals("1", written.getValue(open).cueNumber)
        assertNull(written.getValue(build).cueNumber, "an auto number is never written")
        assertEquals("MARKER", written.getValue(interval).kind)
        assertNull(written.getValue(open).kind, "a cue entry omits its kind")
        assertEquals(1500L, written.getValue(open).autoAdvanceDelayMs)
        assertTrue(written.values.none { it.cueUuid == loose.toString() })
        val anchorsOut = files.filterKeys { it.startsWith("promptBookAnchors/") }.values
            .map { canonicalDecode(PromptBookAnchorJson.serializer(), it) }
        assertEquals(setOf(build.toString(), interval.toString()), anchorsOut.map { it.entryUuid }.toSet())
        assertTrue(anchorsOut.all { it.cueUuid == null }, "an anchor names its entry, never a cue")
    }

    // ── One file per entry ─────────────────────────────────────────────────────

    @Test
    fun `a drag changes one file, and an explicit renumber only the entries whose written number changed`() {
        val projectId = seedMinimalProject(state)
        val entries = seedStack(projectId, "Act 1", listOf("1", null, null, "4", null, null))
        val a = export(projectId)

        // Drag the sixth entry up to second; auto numbers move on the desk, but none is in the archive.
        val autoBefore = numbersOf(projectId)
        transaction(state.database) {
            val entry = DaoCueStackEntry.find { DaoCueStackEntries.uuid eq entries[5] }.single()
            assertNull(moveEntryTo(entry.stack, entry, 1))
        }
        assertNotEquals(autoBefore, numbersOf(projectId), "test sanity: the drag relabelled the desk's auto numbers")
        val b = export(projectId)
        assertEquals(setOf("cueStackEntries/${entries[5]}.json"), changedFiles(a, b))

        // Type an explicit number on the third entry (1, 1.1, 1.2, 1.3, 4, 5 after the drag). Its file
        // changes, and so does the fourth's: D11 holds its 1.3, which a reader anchoring on the new
        // 1.25 would derive as 1.26, so the archive now writes it (P5, as amended 2026-10-16).
        transaction(state.database) {
            val stack = DaoCueStack.find { DaoCueStacks.project eq projectId }.single()
            val before = cueEntryIds(stack)
            val third = entriesOf(stack)[2]
            third.cueNumber = "1.25"
            third.cueNumberAuto = false
            renumberAutoEntries(stack, before)
        }
        val c = export(projectId)
        val (third, fourth) = transaction(state.database) {
            entriesOf(DaoCueStack.find { DaoCueStacks.project eq projectId }.single()).let { it[2].uuid to it[3].uuid }
        }
        assertEquals(setOf("cueStackEntries/$third.json", "cueStackEntries/$fourth.json"), changedFiles(b, c))
        assertEquals("1.3", canonicalDecode(CueStackEntryJson.serializer(), c.getValue("cueStackEntries/$fourth.json")).cueNumber)
    }

    /**
     * An auto number D11 held in place survives an import (P5, as amended 2026-10-16): an explicit
     * renumber waiting for *Fix Order* keeps its neighbours' labels on this desk, a reader deriving
     * from position alone would not, so the archive writes them — and a pull or a clone shows the
     * labels this desk does.
     */
    @Test
    fun `an auto number held by an explicit renumber survives an import`() {
        val projectId = seedMinimalProject(state)
        seedStack(projectId, "Act 1", listOf(null, null, null, null, null))
        transaction(state.database) {
            val stack = DaoCueStack.find { DaoCueStacks.project eq projectId }.single()
            val before = cueEntryIds(stack)
            val second = entriesOf(stack)[1]
            second.cueNumber = "4.5"
            second.cueNumberAuto = false
            renumberAutoEntries(stack, before)
        }
        fun labels(id: Int) = transaction(state.database) {
            entriesOf(DaoCueStack.find { DaoCueStacks.project eq id }.single()).map { it.cueNumber }
        }
        assertEquals(listOf("1", "4.5", "3", "4", "5"), labels(projectId), "test sanity: D11 held the neighbours")

        val dir = Files.createTempDirectory(scratchRoot, "held-")
        ProjectExporter(state).export(projectId, dir)
        val written = readExportFiles(dir).filterKeys { it.startsWith("cueStackEntries/") }
            .values.map { canonicalDecode(CueStackEntryJson.serializer(), it).cueNumber }
        assertEquals(setOf("1", "4.5", "3", "4", "5"), written.filterNotNull().toSet(), "a reader would not derive them")

        // A clone is that export imported again — what a pull does to this desk's own rows.
        val cloned = ProjectCloner(state).clone(projectId, "Held", description = null).projectId
        assertEquals(listOf("1", "4.5", "3", "4", "5"), labels(cloned))
    }

    @Test
    fun `an entry takes the room between its neighbours and never ties`() {
        // A drag: the moved entry takes the midpoint, nothing else moves.
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0, 5.0), planEntryOrdinals(listOf(1.0, 4.0, 2.0, 3.0, 5.0)))
        // An append counts on in whole numbers; an insert at the front keeps a lower ordinal.
        assertEquals(listOf(1.0, 2.5, 3.0), planEntryOrdinals(listOf(1.0, 2.5, 0.0)))
        assertEquals(listOf(0.0, 1.0, 2.0), planEntryOrdinals(listOf(0.0, 1.0, 2.0)))
        // Two entries a merge left on one ordinal: the next write moves one off it.
        assertEquals(listOf(2.0, 2.25, 2.5, 3.0), planEntryOrdinals(listOf(2.0, 2.5, 2.5, 3.0)))
        // A fresh entry (passed as NaN: just made, or moved in from another stack) is never kept, so
        // it takes the midpoint, the next whole number, or the one below — never moving a neighbour.
        assertEquals(listOf(1.0, 1.5, 2.0), planEntryOrdinals(listOf(1.0, Double.NaN, 2.0)))
        assertEquals(listOf(1.0), planEntryOrdinals(listOf(Double.NaN)))
        assertEquals(listOf(1.0, 2.0, 3.0), planEntryOrdinals(listOf(1.0, 2.0, Double.NaN)))
        assertEquals(listOf(0.0, 1.0), planEntryOrdinals(listOf(Double.NaN, 1.0)))
        // No room left between two doubles: the stack is renormalised, still in the order given.
        val tight = Math.nextUp(1.0)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), planEntryOrdinals(listOf(0.5, 1.0, 0.0, tight)))
        // An ordered list is left exactly as it is.
        assertEquals(listOf(1.0, 1.25, 7.0), planEntryOrdinals(listOf(1.0, 1.25, 7.0)))
        // Whatever goes in, what comes out ascends strictly.
        val random = java.util.Random(7)
        repeat(200) {
            val input = List(1 + random.nextInt(12)) { listOf(0.0, 1.0, 1.5, 2.0, 2.0, 3.0)[random.nextInt(6)] + random.nextInt(3) }
            val out = planEntryOrdinals(input)
            assertTrue(out.zipWithNext().all { (x, y) -> x < y }, "$input → $out")
        }
    }

    // ── Two desks, one gap ─────────────────────────────────────────────────────

    /**
     * Two desks insert a cue into the same gap — each takes the midpoint — and sync. The merge keeps
     * both entries, on one ordinal, and every desk plays them in uuid order: this one after the merge,
     * and the tree it pushed for the other to pull.
     */
    @Test
    fun `two desks inserting into one gap both keep their entries, in one stable order`() {
        val projectId = seedMinimalProject(state)
        val (first, second) = seedStack(projectId, "Act 1", listOf("1", "2"))
        configureSync(projectId)
        assertEquals(SyncOutcome.PUSHED, runSync(projectId).outcome)
        val stackUuid = transaction(state.database) { DaoCueStack.find { DaoCueStacks.project eq projectId }.single().uuid }

        // The other desk inserts X between the two and pushes.
        val theirs = UUID.randomUUID()
        val theirCue = UUID.randomUUID()
        pushFromPeer { tree ->
            write(tree, "cues/$theirCue.json", canonicalEncode(CueJson.serializer(), CueJson(uuid = theirCue.toString(), name = "Theirs")))
            write(
                tree, "cueStackEntries/$theirs.json",
                canonicalEncode(
                    CueStackEntryJson.serializer(),
                    CueStackEntryJson(uuid = theirs.toString(), cueStackUuid = stackUuid.toString(), cueUuid = theirCue.toString(), ordinal = 1.5),
                ),
            )
        }

        // This desk inserts Y into the same gap.
        val ours = transaction(state.database) {
            val stack = DaoCueStack.find { DaoCueStacks.project eq projectId }.single()
            val cue = DaoCue.new { project = DaoProject[projectId]; name = "Ours" }
            val entry = DaoCueStackEntry.new { this.stack = stack; this.cue = cue; ordinal = 0.0 }
            val (e1, e2) = entriesOf(stack).filter { it.id != entry.id }
            writeEntryOrder(listOf(e1, entry, e2), fresh = listOf(entry))
            assertEquals(1.5, entry.ordinal, "the midpoint")
            assertEquals(listOf(1.0, 2.0), listOf(e1.ordinal, e2.ordinal), "and nothing else moved")
            entry.uuid
        }

        assertEquals(SyncOutcome.MERGED, runSync(projectId).outcome)

        val expected = listOf(first) + listOf(ours, theirs).sortedBy { it.toString() } + second
        transaction(state.database) {
            val entries = entriesOf(DaoCueStack.find { DaoCueStacks.project eq projectId }.single())
            assertEquals(expected, entries.map { it.uuid }, "both entries kept, in uuid order")
            assertEquals(listOf(1.0, 1.5, 1.5, 2.0), entries.map { it.ordinal })
        }

        // What the other desk pulls orders the same way.
        val pulled = cloneRemote()
        val remoteOrder = Files.list(pulled.resolve("cueStackEntries")).use { it.toList() }
            .map { canonicalDecode(CueStackEntryJson.serializer(), Files.readString(it)) }
            .sortedWith(compareBy({ it.ordinal }, { it.uuid }))
            .map { UUID.fromString(it.uuid) }
        assertEquals(expected, remoteOrder)

        // And a second sync changes nothing: the order is stable.
        assertEquals(SyncOutcome.NO_OP, runSync(projectId).outcome)
        transaction(state.database) {
            assertEquals(expected, entriesOf(DaoCueStack.find { DaoCueStacks.project eq projectId }.single()).map { it.uuid })
        }
    }

    // ── Number clashes ─────────────────────────────────────────────────────────

    /**
     * After a merge two entries can hold one explicit number (two desks each typed it). The pull
     * keeps the number on the lower uuid, makes the other auto, never aborts on the unique index, and
     * names the clash in the sync result.
     */
    @Test
    fun `a number clash after a merge resolves on the lower uuid and is named in the sync result`() {
        val projectId = seedMinimalProject(state)
        val (first, second) = seedStack(projectId, "Act 1", listOf("5", "6"))
        configureSync(projectId)
        runSync(projectId)

        // The tree a merge of two desks that each typed "5" would leave.
        pushFromPeer { tree ->
            val file = tree.resolve("cueStackEntries/$second.json")
            val entry = canonicalDecode(CueStackEntryJson.serializer(), Files.readString(file))
            Files.writeString(file, canonicalEncode(CueStackEntryJson.serializer(), entry.copy(cueNumber = "5")))
        }

        val result = runSync(projectId)
        assertEquals(SyncOutcome.FAST_FORWARDED, result.outcome)
        val clash = result.numberClashes.single()
        val (keeper, loser) = listOf(first, second).sortedBy { it.toString() }
        assertEquals("5", clash.number)
        assertEquals("Act 1", clash.stack)
        assertEquals(keeper.toString(), clash.keptEntryUuid)
        assertEquals(loser.toString(), clash.entryUuid)
        assertTrue("5" in result.message && "Act 1" in result.message, result.message)

        transaction(state.database) {
            val entries = entriesOf(DaoCueStack.find { DaoCueStacks.project eq projectId }.single()).associateBy { it.uuid }
            assertEquals("5", entries.getValue(keeper).cueNumber)
            assertFalse(entries.getValue(keeper).cueNumberAuto)
            assertTrue(entries.getValue(loser).cueNumberAuto, "the other entry is made auto")
            assertNotEquals("5", entries.getValue(loser).cueNumber)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** A stack named [name] with one cue entry per number (null: auto), in order; answers the entries' uuids. */
    private fun seedStack(projectId: Int, name: String, numbers: List<String?>): List<UUID> = transaction(state.database) {
        val project = DaoProject[projectId]
        val stack = DaoCueStack.new { this.project = project; this.name = name }
        val entries = numbers.mapIndexed { index, number ->
            val cue = DaoCue.new { this.project = project; this.name = "Cue ${index + 1}" }
            DaoCueStackEntry.new {
                this.stack = stack
                this.cue = cue
                ordinal = (index + 1).toDouble()
                cueNumber = number
            }
        }
        renumberAutoEntries(stack, emptyList())
        entries.map { it.uuid }
    }

    /** Each entry's number on the desk, by entry uuid. */
    private fun numbersOf(projectId: Int): Map<UUID, String?> = transaction(state.database) {
        entriesOf(DaoCueStack.find { DaoCueStacks.project eq projectId }.single()).associate { it.uuid to it.cueNumber }
    }

    private fun export(projectId: Int): Map<String, String> {
        val dir = Files.createTempDirectory(scratchRoot, "export-")
        ProjectExporter(state).export(projectId, dir)
        return readExportFiles(dir)
    }

    /** The files that differ between two exports, the version stamp and the install registry aside. */
    private fun changedFiles(a: Map<String, String>, b: Map<String, String>): Set<String> =
        (a.keys + b.keys).filter { it != "installs.json" && a[it] != b[it] }.toSet()

    private fun write(dir: Path, rel: String, text: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, if (text.endsWith("\n")) text else text + "\n")
    }

    private fun configureSync(projectId: Int) {
        val url = bareRepo.toUri().toString()
        transaction(state.database) {
            val project = DaoProject[projectId]
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = url
        }
        credentialStore.set(url, "test-pat")
    }

    private fun runSync(projectId: Int): SyncRunResult {
        val (projectUuid, installUuid, installName) = transaction(state.database) {
            val install = DaoInstall.all().first()
            Triple(DaoProject[projectId].uuid, install.uuid, install.friendlyName)
        }
        return runBlocking { engine.runSync(projectId, projectUuid, installUuid, installName) }
    }

    /** Another desk: fetch the remote's tip, let [edit] change its tree, commit and push. */
    private fun pushFromPeer(edit: (Path) -> Unit) {
        val peer = Files.createTempDirectory(scratchRoot, "peer-")
        JGitClient.init(peer).use { repo ->
            JGitClient.setRemote(repo, "origin", bareRepo.toUri().toString())
            JGitClient.fetch(repo, "origin", "main", GitCredentials("", ""))
            val originSha = repo.resolve("refs/remotes/origin/main")!!.name
            Git(repo).branchCreate().setName("main").setStartPoint(originSha).setForce(true).call()
            JGitClient.resetHard(repo, originSha)
            edit(peer)
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "OtherDesk", "other@example.com", "from-the-other-desk")
            assertEquals(PushStatus.OK, JGitClient.push(repo, "origin", "main", GitCredentials("", "")).status)
        }
    }

    /** The remote's tip, checked out into a fresh folder: what another desk would pull. */
    private fun cloneRemote(): Path {
        val dir = Files.createTempDirectory(scratchRoot, "pull-")
        JGitClient.init(dir).use { repo ->
            JGitClient.setRemote(repo, "origin", bareRepo.toUri().toString())
            JGitClient.fetch(repo, "origin", "main", GitCredentials("", ""))
            JGitClient.resetHard(repo, repo.resolve("refs/remotes/origin/main")!!.name)
        }
        return dir
    }
}
