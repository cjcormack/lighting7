package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.state.SceneImageFormat
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_SCENE_IMAGES
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_SCRIPT_HASH
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.venue.VenueExtract
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Snapshot restore (sync-restore plan session 1): the review and the restore through
 * [RemoteSyncEngine], on [seedRichProject] and over bare repos for the sync rules, each refusal
 * holding the database, the branch, the working tree and the stores where they were (P3).
 */
class SnapshotRestoreTest {

    private val roots = mutableListOf<Path>()
    private val desks = mutableListOf<Desk>()

    private fun temp(prefix: String): Path = Files.createTempDirectory(prefix).also { roots.add(it) }

    private inner class Desk(name: String, push: Boolean = true) {
        val root = temp("lighting7-restore-$name-")
        val state = State(
            testAppConfig(
                "database.path" to root.resolve("desk.db").toString(),
                "sync.workingTreeRoot" to root.resolve("trees").toString(),
                "promptBooks.scriptStoreRoot" to root.resolve("pdfs").toString(),
                "stage.sceneImageStoreRoot" to root.resolve("images").toString(),
                "sync.push" to push.toString(),
            ),
        )
        val engine = RemoteSyncEngine(state, AuthResolver(cloud = null, localRepos = true))

        init {
            desks.add(this)
        }

        fun install(): Pair<UUID, String> = transaction(state.database) {
            DaoInstall.all().first().let { it.uuid to it.friendlyName }
        }

        fun uuidOf(projectId: Int): UUID = transaction(state.database) { DaoProject[projectId].uuid }

        fun snapshot(projectId: Int, message: String? = null): SnapshotResponse {
            val (installUuid, name) = install()
            return runBlocking { SnapshotEngine(state).snapshot(projectId, uuidOf(projectId), installUuid, name, message) }
        }

        fun sync(projectId: Int): SyncRunResult {
            val (installUuid, name) = install()
            return runBlocking { engine.runSync(projectId, uuidOf(projectId), installUuid, name) }
        }

        fun review(projectId: Int, sha: String): RestoreReviewDto = runBlocking { engine.reviewRestore(projectId, sha) }

        fun restore(projectId: Int, sha: String, token: String, note: String? = null): RestoreResultDto {
            val (installUuid, name) = install()
            return runBlocking { engine.restore(projectId, uuidOf(projectId), installUuid, name, sha, token, note) }
        }

        fun reviewAndRestore(projectId: Int, sha: String, note: String? = null): RestoreResultDto =
            restore(projectId, sha, review(projectId, sha).token, note)

        fun attach(projectId: Int, url: String) {
            transaction(state.database) {
                val project = DaoProject[projectId]
                val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                    ?: DaoSyncConfig.new { this.project = project }
                cfg.repoUrl = url
            }
        }

        fun repo(projectId: Int) = JGitClient.open(SyncWorkingTree(state).pathFor(uuidOf(projectId)))!!

        fun head(projectId: Int): CommitInfo = repo(projectId).use { JGitClient.head(it)!! }

        fun log(projectId: Int): List<CommitInfo> = repo(projectId).use { JGitClient.log(it, 100) }

        /** Every record of the project, by key and hash, and its project.json. */
        fun records(projectId: Int): Map<RecordKey, String> = transaction(state.database) {
            ProjectExporter(state).exportRecords(projectId).images.mapValues { it.value.hash }
        }

        /** The working tree's files, binaries included, by path. */
        fun tree(projectId: Int): Map<String, String> {
            val dir = SyncWorkingTree(state).pathFor(uuidOf(projectId))
            return Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && !dir.relativize(it).startsWith(".git") }
                    .toList()
                    .associate { dir.relativize(it).toString() to RecordHasher.sha256Hex(Files.readAllBytes(it)) }
            }
        }

        fun stores(): Map<String, String> {
            val files = listOf(root.resolve("pdfs"), root.resolve("images")).filter { Files.isDirectory(it) }
            return files.flatMap { dir ->
                Files.walk(dir).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.toList()
                        .map { dir.relativize(it).toString() to RecordHasher.sha256Hex(Files.readAllBytes(it)) }
                }
            }.toMap()
        }

        /** What a refusal must leave as it was (P3). */
        fun standing(projectId: Int) = listOf(records(projectId), head(projectId).sha, tree(projectId), stores())
    }

    /** An engine for a restore with no repo attached: neither call is made. */
    private val localEngine = object : SnapshotRestore.Engine {
        override suspend fun sync(): SyncRunResult = error("no repo")
        override suspend fun reload(projectId: Int) = Unit
    }

    private fun bareRepo(): String {
        val bare = temp("lighting7-restore-bare-")
        Git.init().setBare(true).setDirectory(bare.toFile()).setInitialBranch("main").call().close()
        return bare.toUri().toString()
    }

    @Before
    fun setUp() {
        roots.clear()
        desks.clear()
    }

    @After
    fun tearDown() {
        desks.forEach { runCatching { it.state.shutdown() } }
        roots.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    /** The rich fixture, not current, so no restore reloads a show this test never loaded. */
    private fun richShow(desk: Desk): Int {
        val id = seedRichProject(desk.state)
        transaction(desk.state.database) { DaoProject[id].isCurrent = false }
        return id
    }

    private class Edits(val renamedStack: UUID, val addedStack: UUID, val deletedViewpoint: UUID)

    /** One of each change a review lists: a stack renamed, a stack added, a viewpoint deleted. */
    private fun edit(desk: Desk, projectId: Int): Edits = transaction(desk.state.database) {
        val project = DaoProject[projectId]
        val renamed = DaoCueStack.find { DaoCueStacks.project eq projectId }.orderBy(DaoCueStacks.id to org.jetbrains.exposed.v1.core.SortOrder.ASC).first()
        renamed.name = "Renamed since"
        val added = DaoCueStack.new { this.project = project; name = "Added since" }
        val viewpoint = DaoStageViewpoint.find { DaoStageViewpoints.project eq projectId }.first()
        val deleted = viewpoint.uuid
        viewpoint.delete()
        Edits(renamed.uuid, added.uuid, deleted)
    }

    @Test
    fun `a restore brings the first snapshot back as a new commit on top`() {
        val desk = Desk("local")
        val id = richShow(desk)
        val first = desk.snapshot(id, "first").commit!!
        val firstRecords = desk.records(id)
        val edits = edit(desk, id)
        val second = desk.snapshot(id, "second").commit!!

        val result = desk.reviewAndRestore(id, first.sha, note = "back to the dress")

        assertEquals(firstRecords, desk.records(id), "the show exports exactly as the first snapshot")
        assertEquals(RestoreCounts(back = 1, gone = 1, changed = 1), result.counts)
        assertEquals(second.sha, result.before.sha, "nothing was pending, so the show as it stood is the second snapshot")
        val log = desk.log(id)
        assertEquals(result.commit.sha, log.first().sha, "the restore is the newest commit")
        assertTrue(log.any { it.sha == first.sha } && log.any { it.sha == second.sha }, "history is kept")
        assertEquals(first.sha, result.commit.restoredFrom)
        assertTrue(result.commit.message.lineSequence().first().endsWith(
            "Restored ${first.shortSha} from ${SnapshotRestore.formatWhen(first.whenMs)} UTC: back to the dress [install:${desk.install().first.toString().take(8)}]",
        ), result.commit.message)
        assertTrue("\n\n$RESTORED_FROM_TRAILER ${first.sha}" in result.commit.message)
        transaction(desk.state.database) {
            assertTrue(DaoStageViewpoint.find { DaoStageViewpoints.uuid eq edits.deletedViewpoint }.any(), "the deleted viewpoint is live again")
            assertTrue(DaoCueStack.find { DaoCueStacks.uuid eq edits.addedStack }.empty(), "the added stack is gone")
        }
        val restoredLog = desk.state.syncLogger.list(id).first()
        assertEquals(SyncLogEvent.RESTORED, restoredLog.event)
        assertEquals(
            "Restored ${first.shortSha} from ${SnapshotRestore.formatWhen(first.whenMs)} UTC: 1 back, 1 gone, 1 changed",
            restoredLog.message,
        )

        // Undo is restoring `before`.
        desk.reviewAndRestore(id, result.before.sha)
        transaction(desk.state.database) {
            assertFalse(DaoCueStack.find { DaoCueStacks.uuid eq edits.addedStack }.empty(), "restoring before brings the edit back")
        }
    }

    @Test
    fun `the restore commit carries a deletion marker for each synced record created since`() {
        val desk = Desk("tombstones")
        val id = richShow(desk)
        desk.attach(id, bareRepo())
        desk.sync(id)
        val first = desk.head(id)
        val edits = edit(desk, id)
        desk.sync(id)

        val result = desk.reviewAndRestore(id, first.sha)
        assertEquals(SyncOutcome.PUSHED, result.sync?.outcome)
        desk.repo(id).use { repo ->
            val at = RecordHasher.fromRef(repo, result.commit.sha)
            assertTrue(at.getValue(RecordKey("cueStacks", edits.addedStack)).isDeleted, "the stack created since is tombstoned")
            assertFalse(at.getValue(RecordKey("stageViewpoints", edits.deletedViewpoint)).isDeleted, "the deleted viewpoint is live")
        }
    }

    @Test
    fun `the review lists each kind's records by label, and its token follows what it lists`() {
        val desk = Desk("review")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        val edits = edit(desk, id)
        desk.snapshot(id)

        val review = desk.review(id, first.sha)
        assertFalse(review.noChanges)
        assertEquals(first.sha, review.target.sha)
        assertEquals(desk.head(id).sha, review.head?.sha)
        assertFalse(review.live)
        assertNull(review.sync.repo)
        assertEquals(listOf("cueStacks", "stageViewpoints"), review.kinds.map { it.folder }, "the importer's folder order")
        val stacks = review.kinds.first()
        assertEquals("stack" to "stacks", stacks.noun to stacks.plural)
        assertEquals(
            setOf(edits.renamedStack.toString() to RestoreChange.CHANGED, edits.addedStack.toString() to RestoreChange.GONE),
            stacks.records.map { it.uuid to it.change }.toSet(),
        )
        assertTrue(stacks.records.single { it.change == RestoreChange.GONE }.label == "Stack “Added since”")
        assertEquals(stacks.records.map { it.label }.sorted(), stacks.records.map { it.label })
        assertEquals(RestoreChange.BACK, review.kinds[1].records.single().change)

        // A snapshot that changes nothing listed keeps the token.
        desk.snapshot(id, "nothing new")
        assertEquals(review.token, desk.review(id, first.sha).token)
        // An edit to another record moves it.
        transaction(desk.state.database) { DaoUniverseConfig.find { DaoUniverseConfigs.project eq id }.first().controllerType = "SACN" }
        assertNotEquals(review.token, desk.review(id, first.sha).token)
    }

    @Test
    fun `the show as it is has nothing to restore`() {
        val desk = Desk("nochanges")
        val id = richShow(desk)
        val head = desk.snapshot(id).commit!!
        val review = desk.review(id, head.sha)
        assertTrue(review.noChanges)
        assertEquals(RestoreCounts(0, 0, 0), review.counts)
        val before = desk.standing(id)
        val e = assertFailsWith<SyncException> { desk.restore(id, head.sha, review.token) }
        assertEquals(SyncErrorCode.RESTORE_NO_CHANGES, e.code)
        assertEquals(before, desk.standing(id))
    }

    @Test
    fun `a snapshot that differs only in stage size is a change, and the review lists it`() {
        val desk = Desk("stage")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        transaction(desk.state.database) { DaoProject[id].stageWidthM = 20.0 }
        desk.snapshot(id)
        val review = desk.review(id, first.sha)
        assertFalse(review.noChanges)
        assertTrue(review.kinds.isEmpty())
        val size = assertNotNull(review.project?.stageSize)
        assertEquals(20.0, size.now?.widthM)
        assertEquals(12.0, size.then?.widthM)
        desk.restore(id, first.sha, review.token)
        assertEquals(12.0, transaction(desk.state.database) { DaoProject[id].stageWidthM })
    }

    @Test
    fun `a restore keeps In production, and keeps the current name when another project holds the old one`() {
        val desk = Desk("fields")
        val id = richShow(desk)
        transaction(desk.state.database) { DaoProject[id].inProduction = true; DaoProject[id].name = "Old name" }
        val first = desk.snapshot(id).commit!!
        transaction(desk.state.database) {
            DaoProject[id].inProduction = false
            DaoProject[id].name = "New name"
            DaoProject.new { name = "Old name"; description = "" }
        }
        desk.snapshot(id)
        val keptOnly = desk.review(id, first.sha)
        assertEquals("Old name", keptOnly.project?.nameKept)
        assertTrue(keptOnly.noChanges, "a kept name and a kept lock are no change")
        edit(desk, id)

        val review = desk.review(id, first.sha)
        assertEquals("Old name", review.project?.nameKept)
        assertNull(review.project?.name, "the name is not a change: it stays")

        // A write during the restore answers a review that still keeps the name, with the token a fresh review has.
        val racing = SnapshotRestore(desk.state)
        racing.afterBeforeSnapshot = {
            transaction(desk.state.database) { DaoUniverseConfig.find { DaoUniverseConfigs.project eq id }.first().controllerType = "SACN" }
        }
        val (installUuid, installName) = desk.install()
        val stale = assertFailsWith<RestoreStaleException> {
            runBlocking { racing.restore(localEngine, id, desk.uuidOf(id), installUuid, installName, first.sha, review.token, null) }
        }
        assertEquals("Old name", stale.review.project?.nameKept)
        val fresh = desk.review(id, first.sha)
        assertEquals(fresh.token, stale.review.token)
        desk.restore(id, first.sha, fresh.token)
        transaction(desk.state.database) {
            assertEquals("New name", DaoProject[id].name)
            assertFalse(DaoProject[id].inProduction, "a lock turned off stays off, as one turned on stays on")
        }
    }

    @Test
    fun `the prompt-book PDF and a scene image missing from the stores come back`() {
        val desk = Desk("stores")
        val id = richShow(desk)
        val uuid = desk.uuidOf(id).toString()
        val pdf = desk.state.promptScriptPath(uuid, RICH_PROJECT_SCRIPT_HASH)
        Files.createDirectories(pdf.parent)
        Files.writeString(pdf, "%PDF-1.4 the script")
        val first = desk.snapshot(id).commit!!
        val (type, bytes) = RICH_PROJECT_SCENE_IMAGES.first()
        val imageHash = RecordHasher.sha256Hex(bytes)
        val image = desk.state.sceneImagePath(uuid, imageHash, SceneImageFormat.ofMediaType(type)!!)
        assertTrue(Files.exists(image), "the fixture stored its image")

        Files.delete(pdf)
        Files.delete(image)
        edit(desk, id)
        desk.snapshot(id)
        desk.reviewAndRestore(id, first.sha)

        assertEquals("%PDF-1.4 the script", Files.readString(pdf))
        assertTrue(bytes.contentEquals(Files.readAllBytes(image)))
    }

    @Test
    fun `an older archive restores with no record changed for its format alone`() {
        val desk = Desk("legacy")
        val frozen = Path.of("src/test/resources/sync/legacy-v38")
        val id = ProjectImporter(desk.state).import(frozen, nameOverride = null).projectId
        transaction(desk.state.database) { DaoProject[id].isCurrent = false }
        val path = SyncWorkingTree(desk.state).pathFor(desk.uuidOf(id))
        val old = SyncWorkingTree(desk.state).ensureInitialised(path).use { repo ->
            Files.walk(frozen).use { files ->
                files.filter { Files.isRegularFile(it) }.toList().forEach { file ->
                    val target = path.resolve(frozen.relativize(file).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(file, target)
                }
            }
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Old desk", "old@lighting7.local", "Old desk: v38 [install:00000000]")
        }
        assertNotNull(desk.snapshot(id).commit, "today's export differs from the v38 archive")
        assertTrue(desk.review(id, old.sha).noChanges, "the same show in v38's words is no change")

        edit(desk, id)
        desk.snapshot(id)
        val review = desk.review(id, old.sha)
        assertEquals(RestoreCounts(back = 1, gone = 1, changed = 1), review.counts, "only the edits")
        desk.restore(id, old.sha, review.token)
    }

    @Test
    fun `an archive older than v5 is refused and nothing moves`() {
        val desk = Desk("tooold")
        val id = richShow(desk)
        desk.snapshot(id)
        val path = SyncWorkingTree(desk.state).pathFor(desk.uuidOf(id))
        val ancient = desk.repo(id).use { repo ->
            Files.writeString(path.resolve("formatVersion.json"), """{"formatVersion":4}""")
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Old desk", "old@lighting7.local", "v4")
        }
        edit(desk, id)
        desk.snapshot(id)
        val e = assertFailsWith<SyncException> { desk.review(id, ancient.sha) }
        assertEquals(SyncErrorCode.RESTORE_TOO_OLD, e.code)
        assertEquals(SyncErrorCode.RESTORE_TOO_OLD.toHttpStatus().value, 422)
        val before = desk.standing(id)
        val refused = assertFailsWith<SyncException> { desk.restore(id, ancient.sha, "any") }
        assertEquals(SyncErrorCode.RESTORE_TOO_OLD, refused.code)
        assertEquals(before, desk.standing(id))
    }

    @Test
    fun `each refusal before anything is written leaves the show where it was`() {
        val desk = Desk("refusals")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        edit(desk, id)
        desk.snapshot(id)
        val token = desk.review(id, first.sha).token
        val before = desk.standing(id)

        fun refused(code: SyncErrorCode, block: () -> Unit) {
            val e = assertFailsWith<SyncException>(code.name) { block() }
            assertEquals(code, e.code, e.message)
            assertEquals(before, desk.standing(id), "$code moved nothing")
        }
        refused(SyncErrorCode.RESTORE_COMMIT_UNKNOWN) { desk.review(id, "0".repeat(40)) }
        refused(SyncErrorCode.RESTORE_COMMIT_UNKNOWN) { desk.review(id, "HEAD") }
        refused(SyncErrorCode.RESTORE_COMMIT_UNKNOWN) { desk.restore(id, "deadbeef", token) }
        refused(SyncErrorCode.RESTORE_INVALID) { desk.restore(id, first.sha, token, note = "x".repeat(201)) }
        refused(SyncErrorCode.RESTORE_INVALID) { desk.restore(id, first.sha, token, note = "two\nlines") }
        refused(SyncErrorCode.RESTORE_INVALID) { desk.restore(id, first.sha, "") }
        refused(SyncErrorCode.RESTORE_INVALID) { desk.restore(id, " ", token) }
        val stale = assertFailsWith<RestoreStaleException> { desk.restore(id, first.sha, "not-the-token") }
        assertEquals(token, stale.review.token, "the stale answer carries the review as it is now")
        assertEquals(before, desk.standing(id))

        // A show with no history has none to restore from.
        val bare = seedMinimalProject(desk.state, projectName = "No history")
        val none = assertFailsWith<SyncException> { desk.review(bare, first.sha) }
        assertEquals(SyncErrorCode.RESTORE_COMMIT_UNKNOWN, none.code)
        // A venue's draft has no snapshots.
        transaction(desk.state.database) { DaoProject[bare].kind = ProjectKind.VENUE }
        assertEquals(SyncErrorCode.VENUE_DRAFT_NOT_SYNCED, assertFailsWith<SyncException> { desk.review(bare, first.sha) }.code)
    }

    @Test
    fun `a target pinned to a venue version this desk lacks is refused as VENUE_MISSING`() {
        val desk = Desk("pinmissing")
        val id = richShow(desk)
        desk.snapshot(id)
        val path = SyncWorkingTree(desk.state).pathFor(desk.uuidOf(id))
        val pinned = desk.repo(id).use { repo ->
            val json = canonicalDecode(uk.me.cormack.lighting7.sync.dto.ProjectJson.serializer(), Files.readString(path.resolve("project.json")))
            Files.writeString(
                path.resolve("project.json"),
                canonicalEncode(
                    uk.me.cormack.lighting7.sync.dto.ProjectJson.serializer(),
                    json.copy(venueUuid = UUID.randomUUID().toString(), venueVersion = 3, detachedFromVenueUuid = null, detachedFromVersion = null),
                ),
            )
            JGitClient.stageAll(repo)
            JGitClient.commit(repo, "Other desk", "other@lighting7.local", "pinned")
        }
        desk.snapshot(id)
        val before = desk.standing(id)
        assertEquals(SyncErrorCode.VENUE_MISSING, assertFailsWith<SyncException> { desk.review(id, pinned.sha) }.code)
        assertEquals(SyncErrorCode.VENUE_MISSING, assertFailsWith<SyncException> { desk.restore(id, pinned.sha, "t") }.code)
        assertEquals(before, desk.standing(id))
    }

    @Test
    fun `a write landing during the restore refuses it with a fresh review and keeps the show as it stood`() {
        val desk = Desk("racing")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        edit(desk, id)
        desk.snapshot(id)
        val review = desk.review(id, first.sha)
        val restore = SnapshotRestore(desk.state)
        restore.afterBeforeSnapshot = {
            transaction(desk.state.database) { DaoUniverseConfig.find { DaoUniverseConfigs.project eq id }.first().controllerType = "SACN" }
        }
        val (installUuid, name) = desk.install()
        val engine = object : SnapshotRestore.Engine {
            override suspend fun sync(): SyncRunResult = error("no repo")
            override suspend fun reload(projectId: Int) = Unit
        }
        val e = assertFailsWith<RestoreStaleException> {
            runBlocking { restore.restore(engine, id, desk.uuidOf(id), installUuid, name, first.sha, review.token, null) }
        }
        assertTrue(e.review.counts.changed > review.counts.changed, "the fresh review lists the write")
        transaction(desk.state.database) {
            assertEquals("SACN", DaoUniverseConfig.find { DaoUniverseConfigs.project eq id }.first().controllerType, "the write stands")
            assertTrue(DaoCueStack.find { DaoCueStacks.project eq id }.any { it.name == "Added since" }, "nothing was restored")
        }
    }

    @Test
    fun `a show restored to before an Extract takes its universes' addresses back from the venue`() {
        val desk = Desk("extract")
        val showId = seedVenueReadyShow(desk.state)
        val universes = transaction(desk.state.database) {
            DaoUniverseConfig.find { DaoUniverseConfigs.project eq showId }.map { it.uuid }
        }
        transaction(desk.state.database) {
            universes.forEachIndexed { i, uuid -> Overrides.setUniverseAddress(showId, uuid, "10.0.0.${i + 10}") }
        }
        val first = desk.snapshot(showId).commit!!
        val venueId = runBlocking {
            desk.state.venueExtract.extract(showId, "Commemoration Hall", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Chris")
        }.venueId!!
        transaction(desk.state.database) {
            assertTrue(universes.all { Overrides.resolveUniverseAddress(showId, it) == null }, "the addresses moved with the universes")
        }
        desk.snapshot(showId)

        val review = desk.review(showId, first.sha)
        assertEquals(universes.size, review.addresses?.copied)
        assertNull(review.venue?.then)
        assertEquals(1, review.venue?.now?.version)
        desk.restore(showId, first.sha, review.token)
        transaction(desk.state.database) {
            universes.forEachIndexed { i, uuid -> assertEquals("10.0.0.${i + 10}", Overrides.resolveUniverseAddress(showId, uuid)) }
            assertTrue(universes.all { Overrides.resolveUniverseAddress(venueId, it) != null }, "the venue keeps its own")
            assertNull(DaoProject[showId].venueUuid, "the pin comes back with the rows: none")
        }
    }

    @Test
    fun `a universe whose address went with it is named in the review`() {
        val desk = Desk("missing")
        val id = seedMinimalProject(desk.state, projectName = "Small")
        transaction(desk.state.database) { DaoProject[id].isCurrent = false }
        val second = transaction(desk.state.database) {
            DaoUniverseConfig.new { project = DaoProject[id]; subnet = 0; universe = 1; controllerType = "MOCK"; address = null }.uuid
        }
        transaction(desk.state.database) { Overrides.setUniverseAddress(id, second, "10.0.0.9") }
        val first = desk.snapshot(id).commit!!
        transaction(desk.state.database) {
            DaoMachineOverride.find { DaoMachineOverrides.recordUuid eq second }.forEach { it.delete() }
            DaoUniverseConfig.find { DaoUniverseConfigs.uuid eq second }.single().delete()
        }
        desk.snapshot(id)
        val review = desk.review(id, first.sha)
        assertEquals(RestoreAddressesDto(copied = 0, missing = listOf("Universe 1")), review.addresses)
    }

    // ─── Sync rules (D5) ──────────────────────────────────────────────────

    @Test
    fun `a peer fast-forwards to the restore, one with an unrelated edit merges, one that edited a restored record conflicts`() {
        val url = bareRepo()
        val a = Desk("a")
        val idA = richShow(a)
        a.attach(idA, url)
        a.sync(idA)
        val first = a.head(idA)
        val edits = edit(a, idA)
        a.sync(idA)
        val uuid = a.uuidOf(idA)

        fun peer(name: String): Pair<Desk, Int> {
            val desk = Desk(name)
            val id = transaction(desk.state.database) {
                DaoProject.new { this.name = "placeholder-$name"; description = ""; this.uuid = uuid }.id.value
            }
            desk.attach(id, url)
            desk.sync(id)
            return desk to id
        }
        val (b, idB) = peer("b")
        val (c, idC) = peer("c")
        val (d, idD) = peer("d")
        // C edits something the restore does not touch; D renames the stack the restore renames back.
        transaction(c.state.database) { DaoUniverseConfig.find { DaoUniverseConfigs.project eq idC }.first().controllerType = "SACN" }
        transaction(d.state.database) { DaoCueStack.find { DaoCueStacks.uuid eq edits.renamedStack }.single().name = "D's name" }

        val result = a.reviewAndRestore(idA, first.sha)
        assertEquals(SyncOutcome.PUSHED, result.sync?.outcome)

        assertEquals(SyncOutcome.FAST_FORWARDED, b.sync(idB).outcome)
        assertEquals(a.records(idA), b.records(idB), "the peer holds the restored show")

        assertEquals(SyncOutcome.MERGED, c.sync(idC).outcome)
        transaction(c.state.database) {
            assertEquals("SACN", DaoUniverseConfig.find { DaoUniverseConfigs.project eq idC }.first().controllerType)
            assertTrue(DaoCueStack.find { DaoCueStacks.uuid eq edits.addedStack }.empty(), "and the restore")
        }

        val conflicted = d.sync(idD)
        assertEquals(SyncOutcome.CONFLICTS_PENDING, conflicted.outcome)
        assertEquals(1, conflicted.conflictCount)
    }

    @Test
    fun `an unreachable remote restores locally and says so`() {
        val desk = Desk("offline")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        edit(desk, id)
        desk.snapshot(id)
        desk.attach(id, "http://127.0.0.1:1/nowhere.git")
        val review = desk.review(id, first.sha)
        assertEquals("http://127.0.0.1:1/nowhere.git", review.sync.repo)

        val result = desk.restore(id, first.sha, review.token)
        assertNull(result.sync)
        assertTrue(result.notes.any { it.startsWith("Couldn't reach the repository") }, "${result.notes}")
        assertEquals(first.sha, result.commit.restoredFrom)
    }

    @Test
    fun `a pull-only desk never pushes the restore`() {
        val url = bareRepo()
        val writer = Desk("writer")
        val idW = richShow(writer)
        writer.attach(idW, url)
        writer.sync(idW)
        val first = writer.head(idW)
        edit(writer, idW)
        writer.sync(idW)
        val remoteTip = writer.head(idW).sha

        val reader = Desk("reader", push = false)
        val uuid = writer.uuidOf(idW)
        val idR = transaction(reader.state.database) {
            DaoProject.new { name = "placeholder"; description = ""; this.uuid = uuid }.id.value
        }
        reader.attach(idR, url)
        reader.sync(idR)

        val result = reader.reviewAndRestore(idR, first.sha)
        assertNull(result.sync)
        assertTrue(result.notes.any { "doesn't push" in it }, "${result.notes}")
        Git.open(Path.of(java.net.URI(url)).toFile()).use { bare ->
            assertEquals(remoteTip, bare.repository.resolve("refs/heads/main").name, "the remote never moved")
        }
    }

    @Test
    fun `a first sync that opens a conflict session refuses the restore`() {
        val url = bareRepo()
        val a = Desk("ca")
        val idA = richShow(a)
        a.attach(idA, url)
        a.sync(idA)
        val first = a.head(idA)
        val uuid = a.uuidOf(idA)
        val b = Desk("cb")
        val idB = transaction(b.state.database) { DaoProject.new { name = "p"; description = ""; this.uuid = uuid }.id.value }
        b.attach(idB, url)
        b.sync(idB)
        val stack = transaction(a.state.database) { DaoCueStack.find { DaoCueStacks.project eq idA }.first().uuid }
        transaction(a.state.database) { DaoCueStack.find { DaoCueStacks.uuid eq stack }.single().name = "A's" }
        a.sync(idA)
        transaction(b.state.database) { DaoCueStack.find { DaoCueStacks.uuid eq stack }.single().name = "B's" }
        b.snapshot(idB)
        val review = b.review(idB, first.sha)
        val records = b.records(idB)

        val e = assertFailsWith<SyncException> { b.restore(idB, first.sha, review.token) }
        assertEquals(SyncErrorCode.SESSION_PENDING, e.code)
        assertEquals(records, b.records(idB), "the database is where it was")
    }

    @Test
    fun `a first sync whose import fails refuses the restore`() {
        val url = bareRepo()
        val a = Desk("ia")
        val idA = richShow(a)
        a.attach(idA, url)
        a.sync(idA)
        val first = a.head(idA)
        edit(a, idA)
        a.sync(idA)
        val review = a.review(idA, first.sha)
        val records = a.records(idA)
        val head = a.head(idA).sha

        // Another desk pushes a record this one cannot read.
        val other = temp("lighting7-restore-clone-")
        Git.cloneRepository().setURI(url).setDirectory(other.toFile()).call().use { git ->
            val dir = other.resolve("cueStacks")
            Files.writeString(dir.resolve("${UUID.randomUUID()}.json"), "{ not json")
            git.add().addFilepattern(".").call()
            git.commit().setAuthor("x", "x@x").setCommitter("x", "x@x").setSign(false).setMessage("broken").call()
            git.push().call()
        }

        val e = assertFailsWith<SyncException> { a.restore(idA, first.sha, review.token) }
        assertEquals(SyncErrorCode.RESTORE_UNREADABLE, e.code, e.message)
        assertEquals(records, a.records(idA), "the database is where it was")
        assertEquals(head, a.head(idA).sha, "the fast-forward imports before it moves HEAD")
    }

    @Test
    fun `a diverged push that takes the remote's name says so`() {
        val url = bareRepo()
        val a = Desk("ma")
        val idA = richShow(a)
        transaction(a.state.database) { DaoProject[idA].name = "One" }
        a.attach(idA, url)
        a.sync(idA)
        val first = a.head(idA)
        transaction(a.state.database) { DaoProject[idA].name = "Two" }
        edit(a, idA)
        a.sync(idA)
        val uuid = a.uuidOf(idA)
        val b = Desk("mb")
        val idB = transaction(b.state.database) { DaoProject.new { name = "p"; description = ""; this.uuid = uuid }.id.value }
        b.attach(idB, url)
        b.sync(idB)

        val review = a.review(idA, first.sha)
        assertEquals(RestoreText("Two", "One"), review.project?.name)
        var syncs = 0
        val engine = object : SnapshotRestore.Engine {
            override suspend fun sync(): SyncRunResult {
                if (syncs++ == 1) {
                    // B renames the show and pushes between A's first sync and its push.
                    transaction(b.state.database) { DaoProject[idB].name = "Three" }
                    b.sync(idB)
                }
                return a.sync(idA)
            }
            override suspend fun reload(projectId: Int) = Unit
        }
        val (installUuid, name) = a.install()
        val result = runBlocking {
            SnapshotRestore(a.state).restore(engine, idA, uuid, installUuid, name, first.sha, review.token, null)
        }
        assertEquals(SyncOutcome.MERGED, result.sync?.outcome)
        assertEquals("Three", transaction(a.state.database) { DaoProject[idA].name })
        assertTrue(result.notes.any { "“Three”" in it && "“One”" in it }, "${result.notes}")
    }

    @Test
    fun `GitTransportException names a transport failure and auth stays its own`() {
        val e = assertFailsWith<RuntimeException> {
            JGitClient.open(Desk("transport").let { desk ->
                val id = richShow(desk)
                desk.snapshot(id)
                SyncWorkingTree(desk.state).pathFor(desk.uuidOf(id))
            })!!.use { repo ->
                JGitClient.setRemote(repo, "origin", "http://127.0.0.1:1/nowhere.git")
                JGitClient.fetch(repo, "origin", "main", GitCredentials("local", "t"))
            }
        }
        assertIs<GitTransportException>(e)
        assertEquals(30, JGitClient.TRANSPORT_TIMEOUT_SECONDS)
    }

    @Test
    fun `restoredFrom is read only from a trailer line of its own`() {
        val sha = "a".repeat(40)
        assertEquals(sha, restoredFromTrailer("Desk: Restored aaaaaaa [install:12345678]\n\n$RESTORED_FROM_TRAILER $sha"))
        assertNull(restoredFromTrailer("Desk: mentions $RESTORED_FROM_TRAILER $sha in passing"))
        assertNull(restoredFromTrailer("Desk: Snapshot"))
    }

    @Test
    fun `a script whose body has CRLF line endings restores rather than reading as a write`() {
        val desk = Desk("crlf")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        edit(desk, id)
        transaction(desk.state.database) {
            uk.me.cormack.lighting7.models.DaoScript.find { uk.me.cormack.lighting7.models.DaoScripts.project eq id }.first().script = "// pasted\r\nfixture(\"hex-1\")\r\n"
        }
        desk.snapshot(id)
        val result = desk.reviewAndRestore(id, first.sha)
        assertEquals(first.sha, result.commit.restoredFrom)
    }

    @Test
    fun `refused credentials on the first sync restore locally, and a push that fails after the restore is a note`() {
        val desk = Desk("auth")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        edit(desk, id)
        desk.snapshot(id)
        desk.attach(id, "https://github.com/example/show.git")
        val review = desk.review(id, first.sha)
        val (installUuid, name) = desk.install()

        val refused = object : SnapshotRestore.Engine {
            override suspend fun sync(): SyncRunResult = throw GitAuthException("403 Forbidden")
            override suspend fun reload(projectId: Int) = Unit
        }
        val local = runBlocking { SnapshotRestore(desk.state).restore(refused, id, desk.uuidOf(id), installUuid, name, first.sha, review.token, null) }
        assertNull(local.sync)
        assertTrue(local.notes.any { it.startsWith("Couldn't reach the repository (403 Forbidden)") }, "${local.notes}")

        val back = desk.review(id, local.before.sha)
        var calls = 0
        val pushFails = object : SnapshotRestore.Engine {
            override suspend fun sync(): SyncRunResult {
                if (calls++ == 0) return SyncRunResult(SyncOutcome.NO_OP, 0, 0, 0, desk.head(id).sha, "Already in sync.")
                throw GitAuthException("403 push refused")
            }
            override suspend fun reload(projectId: Int) = Unit
        }
        val pushed = runBlocking { SnapshotRestore(desk.state).restore(pushFails, id, desk.uuidOf(id), installUuid, name, local.before.sha, back.token, null) }
        assertEquals(2, calls)
        assertNull(pushed.sync)
        assertTrue(pushed.notes.any { it.startsWith("The restore didn't go out (403 push refused)") }, "${pushed.notes}")
        assertEquals(local.before.sha, desk.head(id).restoredFrom, "the restore committed all the same")
    }

    @Test
    fun `a cloud that refuses the first sync restores locally, whatever it refused`() {
        val desk = Desk("cloud-refused")
        val id = richShow(desk)
        val first = desk.snapshot(id).commit!!
        edit(desk, id)
        desk.snapshot(id)
        desk.attach(id, "https://cloud.example/git/org-1/show.git")
        val (installUuid, name) = desk.install()
        val refusals = listOf<Throwable>(
            SyncException(SyncErrorCode.CLOUD_NOT_ADOPTED, "not adopted"),
            SyncException(SyncErrorCode.SYNC_OFF, "sync off"),
            SyncException(SyncErrorCode.REPO_NOT_FOUND, "no such repo"),
            SyncException(SyncErrorCode.SYNC_QUOTA_EXCEEDED, "over quota"),
            SyncException(SyncErrorCode.SYNC_STORAGE_UNAVAILABLE, "no storage"),
            GitPushRefusedException("SYNC_QUOTA_EXCEEDED", "over quota"),
        )
        var target = first.sha
        for (refusal in refusals) {
            val review = desk.review(id, target)
            val engine = object : SnapshotRestore.Engine {
                override suspend fun sync(): SyncRunResult = throw refusal
                override suspend fun reload(projectId: Int) = Unit
            }
            val local = runBlocking { SnapshotRestore(desk.state).restore(engine, id, desk.uuidOf(id), installUuid, name, target, review.token, null) }
            assertNull(local.sync)
            assertTrue(local.notes.any { it.startsWith("Couldn't reach the repository") }, "$refusal: ${local.notes}")
            target = local.before.sha
        }
    }

    @Test
    fun `an import error maps to the restore's codes without reading its message`() {
        assertEquals(SyncErrorCode.FORMAT_TOO_NEW, ImportError.unsupportedFormat("older or newer").toSyncException(pulled = false).code)
        assertEquals(SyncErrorCode.RESTORE_UNREADABLE, ImportError.unsupportedFormat("older").toSyncException(pulled = true).code)
        assertEquals(SyncErrorCode.PROJECT_MISMATCH, ImportError.conflict("uuid").toSyncException(pulled = false).code)
        assertEquals(SyncErrorCode.VENUE_MISSING, ImportError.conflict("pin", "VENUE_MISSING").toSyncException(pulled = true).code)
        assertEquals(SyncErrorCode.RESTORE_UNREADABLE, ImportError.invalidArchive("bad").toSyncException(pulled = false).code)
    }
}
