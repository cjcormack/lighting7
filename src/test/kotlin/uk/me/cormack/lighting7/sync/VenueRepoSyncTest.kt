package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueDiff
import uk.me.cormack.lighting7.venue.VenueExtract
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenuePins
import uk.me.cormack.lighting7.venue.VenueVersionDto
import uk.me.cormack.lighting7.venue.VenueVersions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Venue plan session 4 (§3.4, P7): a venue's repo of tagged versions between two desks on one bare
 * remote — publish and its refusal when the number is taken, a pull's materialisation and what it does
 * to the draft, the clash of two desks' versions, a pull-only desk, a show's pull that would pin a
 * version the desk lacks, and what a show with a missing version does at load.
 */
class VenueRepoSyncTest {

    private lateinit var tmp: Path
    private lateinit var venueBare: Path
    private lateinit var showBare: Path
    private val states = mutableListOf<State>()
    private val venueUrl get() = venueBare.toUri().toString()
    private val showUrl get() = showBare.toUri().toString()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("venue-repo-sync-")
        venueBare = tmp.resolve("hall.git").also { bare(it) }
        showBare = tmp.resolve("panto.git").also { bare(it) }
    }

    @After
    fun tearDown() {
        states.forEach { runCatching { it.shutdown() } }
        runCatching { tmp.toFile().deleteRecursively() }
    }

    private fun bare(path: Path) =
        Git.init().setBare(true).setDirectory(path.toFile()).setInitialBranch("main").call().close()

    /** A desk: its own database and working trees, and an engine with an in-memory PAT for both repos. */
    private inner class Desk(name: String, push: Boolean = true) {
        val state = State(
            testAppConfig(
                "database.path" to tmp.resolve("$name.db").toString(),
                "sync.workingTreeRoot" to tmp.resolve("$name-sync").toString(),
                "sync.push" to push.toString(),
            ),
        ).also { states += it }
        val creds = InMemoryCredentialStore().also {
            it.set(venueUrl, "test-pat")
            it.set(showUrl, "test-pat")
        }
        val auth = AuthResolver(creds, tokenStore = null, tokenProvider = null)
        val engine = RemoteSyncEngine(state, auth)

        fun attach(projectId: Int, url: String) = transaction(state.database) {
            DaoSyncConfig.new {
                project = DaoProject[projectId]
                repoUrl = url
            }
        }

        fun detach(projectId: Int) = transaction(state.database) {
            DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.forEach { it.delete() }
        }

        fun sync(projectId: Int): SyncRunResult {
            val (uuid, install) = transaction(state.database) { DaoProject[projectId].uuid to DaoInstall.all().first() }
            return runBlocking { engine.runSync(projectId, uuid, install.uuid, install.friendlyName) }
        }

        /** Publish through the venue's repo, under the venue lock, as `VenueVersions.publish` does. */
        fun publish(venueId: Int, note: String): VenueVersionDto =
            runBlocking { state.venueVersions.locked { engine.publishVenue(venueId, note, "Tester") } }

        fun importRepo(url: String): Int = runBlocking { CloudSyncImporter(state, auth).import(url, null, null) }.projectId

        /** Move the venue draft's first truss, a change the diff counts. */
        fun moveTruss(venueId: Int, dx: Double) = transaction(state.database) {
            DaoRigging.find { DaoRiggings.project eq venueId }.orderBy(DaoRiggings.sortOrder to SortOrder.ASC)
                .first().apply { positionX = (positionX ?: 0.0) + dx }
        }

        fun trussX(projectId: Int): Double? = transaction(state.database) {
            DaoRigging.find { DaoRiggings.project eq projectId }.orderBy(DaoRiggings.sortOrder to SortOrder.ASC).first().positionX
        }

        fun versions(venueId: Int): List<Pair<Int, String>> = transaction(state.database) {
            VenueVersions.versionsOf(venueId).map { it.number to it.note }.reversed()
        }

        fun installedSnapshot(venueId: Int): Int = transaction(state.database) {
            VenueVersions.versionsOf(venueId).first().snapshotProject.id.value
        }
    }

    private fun remoteTags(): Map<String, String> = Git.open(venueBare.toFile()).use { git ->
        git.tagList().call().associate { ref ->
            val id = git.repository.refDatabase.peel(ref).peeledObjectId ?: ref.objectId
            ref.name.removePrefix("refs/tags/") to git.repository.parseCommit(id).fullMessage.trim()
        }
    }

    /** Desk A with the show on a venue (v1, v2), the venue's repo attached and both versions pushed. */
    private fun deskWithVenueOnRepo(): Pair<Desk, Int> {
        val a = Desk("a")
        val f = seedShowOnVenue(a.state)
        a.attach(f.venueId, venueUrl)
        val pushed = a.sync(f.venueId)
        assertEquals(SyncOutcome.PUSHED, pushed.outcome, pushed.message)
        assertEquals(setOf("v1", "v2"), remoteTags().keys)
        return a to f.showId
    }

    private fun venueOf(desk: Desk, showId: Int): Int =
        transaction(desk.state.database) { VenuePins.venueOf(DaoProject[showId].venueUuid!!)!!.id.value }

    @Test
    fun `publishing through the repo tags the version, and a number another desk took is VENUE_VERSION_TAKEN`() {
        val (a, showId) = deskWithVenueOnRepo()
        val venueA = venueOf(a, showId)
        val b = Desk("b")
        val venueB = b.importRepo(venueUrl)
        assertEquals(listOf(1, 2), b.versions(venueB).map { it.first })

        a.moveTruss(venueA, 1.0)
        val v3 = a.publish(venueA, "A's v3")
        assertEquals(3, v3.number)
        assertEquals("v3: A's v3", remoteTags()["v3"])

        // B edits a draft based on v2 and publishes: the pull brings in A's v3, so B's was going to be
        // a number already taken.
        b.moveTruss(venueB, -2.0)
        val refused = assertFailsWith<SyncException> { b.publish(venueB, "B's v3") }
        assertEquals(SyncErrorCode.VENUE_VERSION_TAKEN, refused.code)
        assertTrue("A's v3" in refused.message!!, refused.message)
        assertEquals(listOf(1, 2, 3), b.versions(venueB).map { it.first }, "the pull materialised A's v3 here")
        assertEquals("A's v3", b.versions(venueB).last().second)
        assertTrue(b.state.venueVersions.draftChanges(venueB) > 0, "B's draft is kept as it was")
        assertEquals("v3: A's v3", remoteTags()["v3"], "the remote's v3 is still A's")

        // Having seen it, B publishes again: its draft is v4.
        val v4 = b.publish(venueB, "B's v4")
        assertEquals(4, v4.number)
        assertEquals("v4: B's v4", remoteTags()["v4"])
        assertEquals(0, b.state.venueVersions.draftChanges(venueB))
    }

    @Test
    fun `a pull brings a clean draft forward and keeps a draft with changes, and the current project stays current`() {
        val (a, showId) = deskWithVenueOnRepo()
        val venueA = venueOf(a, showId)
        val b = Desk("b")
        val venueB = b.importRepo(venueUrl)
        val current = transaction(b.state.database) {
            DaoProject.new { name = "On the desk"; isCurrent = true }.id.value
        }

        a.moveTruss(venueA, 1.0)
        a.publish(venueA, "v3 from A")
        val clean = b.sync(venueB)
        assertEquals(SyncOutcome.FAST_FORWARDED, clean.outcome, clean.message)
        assertEquals(1, clean.pulled)
        assertEquals(0, b.state.venueVersions.draftChanges(venueB), "the clean draft follows v3")
        assertEquals(a.trussX(venueA), b.trussX(venueB))
        transaction(b.state.database) {
            assertEquals(listOf(current), DaoProject.find { DaoProjects.isCurrent eq true }.map { it.id.value })
        }

        a.moveTruss(venueA, 1.0)
        a.publish(venueA, "v4 from A")
        b.moveTruss(venueB, -5.0)
        val dirty = b.sync(venueB)
        assertEquals(SyncOutcome.FAST_FORWARDED, dirty.outcome)
        assertTrue("kept" in dirty.message, dirty.message)
        assertEquals(listOf(1, 2, 3, 4), b.versions(venueB).map { it.first })
        assertNotEquals(a.trussX(venueA), b.trussX(venueB), "a draft with changes of its own is not replaced")
        assertEquals(a.trussX(a.installedSnapshot(venueA)), b.trussX(b.installedSnapshot(venueB)), "v4 is the same on both desks")

        val idle = b.sync(venueB)
        assertEquals(SyncOutcome.NO_OP, idle.outcome, idle.message)
    }

    @Test
    fun `two desks that published one number apart are refused, never merged`() {
        val (a, showId) = deskWithVenueOnRepo()
        val venueA = venueOf(a, showId)
        val b = Desk("b")
        val venueB = b.importRepo(venueUrl)

        // B publishes v3 on its own (its repo detached), A publishes its v3 to the remote.
        b.detach(venueB)
        b.moveTruss(venueB, 3.0)
        runBlocking { b.state.venueVersions.publish(venueB, "B's own v3", "B") }
        b.attach(venueB, venueUrl)
        a.moveTruss(venueA, 1.0)
        a.publish(venueA, "A's v3")

        val refused = assertFailsWith<SyncException> { b.sync(venueB) }
        assertEquals(SyncErrorCode.VENUE_VERSION_TAKEN, refused.code)
        assertEquals("B's own v3", b.versions(venueB).last().second, "venue_versions rows are never replaced")
        assertEquals("v3: A's v3", remoteTags()["v3"])
    }

    @Test
    fun `a pull-only desk pulls versions and refuses to publish to the repo`() {
        val (a, showId) = deskWithVenueOnRepo()
        val venueA = venueOf(a, showId)
        val b = Desk("b", push = false)
        val venueB = b.importRepo(venueUrl)

        a.moveTruss(venueA, 1.0)
        a.publish(venueA, "v3 from A")
        assertEquals(SyncOutcome.FAST_FORWARDED, b.sync(venueB).outcome)
        assertEquals(listOf(1, 2, 3), b.versions(venueB).map { it.first })

        b.moveTruss(venueB, 1.0)
        val refused = assertFailsWith<SyncException> { b.publish(venueB, "never") }
        assertEquals(SyncErrorCode.VENUE_PUBLISH_PULL_ONLY, refused.code)
        assertEquals(setOf("v1", "v2", "v3"), remoteTags().keys)
    }

    @Test
    fun `a show's pull that would pin a version this desk lacks is VENUE_MISSING, until the venue is synced`() {
        val (a, showId) = deskWithVenueOnRepo()
        val venueA = venueOf(a, showId)
        a.attach(showId, showUrl)
        assertEquals(SyncOutcome.PUSHED, a.sync(showId).outcome)

        val b = Desk("b")
        val venueB = b.importRepo(venueUrl)
        val showB = b.importRepo(showUrl)
        val headBefore = transaction(b.state.database) { DaoSyncConfig.find { DaoSyncConfigs.project eq showB }.single().lastSyncedSha }

        // A publishes v3 and moves the show onto it (what session 6's Update will do).
        a.moveTruss(venueA, 1.0)
        a.publish(venueA, "v3 from A")
        transaction(a.state.database) { DaoProject[showId].venueVersion = 3 }
        a.sync(showId)

        val refused = assertFailsWith<SyncException> { b.sync(showB) }
        assertEquals(SyncErrorCode.VENUE_MISSING, refused.code)
        assertTrue("v3" in refused.message!! && venueUrl in refused.message!!, refused.message)
        transaction(b.state.database) {
            assertEquals(1, DaoProject[showB].venueVersion, "nothing was pulled")
            assertEquals(headBefore, DaoSyncConfig.find { DaoSyncConfigs.project eq showB }.single().lastSyncedSha)
        }

        b.sync(venueB)
        assertEquals(SyncOutcome.FAST_FORWARDED, b.sync(showB).outcome)
        transaction(b.state.database) { assertEquals(3, DaoProject[showB].venueVersion) }
    }

    @Test
    fun `a detached show syncs as a standalone show, and a peer pulls it without the venue`() {
        val (a, showId) = deskWithVenueOnRepo()
        a.attach(showId, showUrl)
        assertEquals(SyncOutcome.PUSHED, a.sync(showId).outcome)
        val b = Desk("b")
        b.importRepo(venueUrl)
        val showB = b.importRepo(showUrl)
        fun rig(desk: Desk, id: Int) = Fixtures().also { DbFixtureLoader.loadFixtures(id, it, desk.state.database) }.getChannelMappings()
        val rigBefore = rig(a, showId)
        assertEquals(rigBefore, rig(b, showB), "the peer loads the same rig through its own copy of the venue")

        // A detaches and pushes: project.json drops the pin, and the show's hides go with it.
        runBlocking { a.state.venueDetach.detach(showId, dryRun = false) }
        assertEquals(SyncOutcome.PUSHED, a.sync(showId).outcome)
        val peek = tmp.resolve("peek")
        Git.cloneRepository().setURI(showUrl).setDirectory(peek.toFile()).call().close()
        val pushed = Files.readString(peek.resolve("project.json"))
        assertFalse("\"venueUuid\"" in pushed || "\"venueVersion\"" in pushed, "no pin: $pushed")
        assertTrue("detachedFromVenueUuid" in pushed && "detachedFromVersion" in pushed, "the origin travels: $pushed")
        assertFalse(Files.exists(peek.resolve("showHides")) || Files.exists(peek.resolve("showOverrides")), "no show layer")

        // The peer fast-forwards onto the standalone show: its rig is its own rows now, the same fixtures.
        assertEquals(SyncOutcome.FAST_FORWARDED, b.sync(showB).outcome)
        transaction(b.state.database) {
            val show = DaoProject[showB]
            assertEquals(null, show.venueUuid)
            assertEquals(null, show.venueVersion)
            assertTrue(DaoShowHide.find { DaoShowHides.project eq showB }.empty())
            assertNotNull(show.detachedFromVenueUuid, "the peer takes the origin with the pin's clearing")
        }
        assertEquals(rigBefore, rig(b, showB))

        // And a desk with no copy of the venue at all imports it, as any standalone show.
        val c = Desk("c")
        val showC = c.importRepo(showUrl)
        assertEquals(rigBefore, rig(c, showC))
    }

    @Test
    fun `a show whose pinned version is missing loads no rig and cannot be made current`() {
        val a = Desk("a")
        val f = seedShowOnVenue(a.state)
        val loaded = Fixtures().also { DbFixtureLoader.loadFixtures(f.showId, it, a.state.database) }
        assertTrue(loaded.getChannelMappings().isNotEmpty(), "the show's rig loads while its version is here")

        transaction(a.state.database) { DaoProject[f.showId].venueVersion = 9 }
        val broken = Fixtures().also { DbFixtureLoader.loadFixtures(f.showId, it, a.state.database) }
        assertTrue(broken.getChannelMappings().isEmpty(), "no fixture loads")
        assertTrue(broken.controllers.isEmpty(), "and no controller")

        val refused = assertFailsWith<VenueException> { runBlocking { a.state.projectManager.switchProject(f.showId) } }
        assertEquals(VenuePins.CODE_VENUE_MISSING, refused.code)
        assertTrue("v9" in refused.message!!, refused.message)
    }

    @Test
    fun `an extract on a show that already syncs survives a merge with a peer's edit`() {
        val a = Desk("a")
        val showId = seedVenueReadyShow(a.state)
        a.attach(showId, showUrl)
        assertEquals(SyncOutcome.PUSHED, a.sync(showId).outcome)
        val b = Desk("b")
        val showB = b.importRepo(showUrl)
        transaction(b.state.database) { DaoCueStack.new { project = DaoProject[showB]; name = "Peer's stack" } }
        assertEquals(SyncOutcome.PUSHED, b.sync(showB).outcome)

        // A extracts the hall while B's edit is on the remote: the next sync diverges and merges.
        val rigBefore = Fixtures().also { DbFixtureLoader.loadFixtures(showId, it, a.state.database) }.getChannelMappings()
        runBlocking {
            a.state.venueExtract.extract(showId, "Commemoration Hall", VenueExtract.ExtractKind.entries.toSet(), dryRun = false, publishedBy = "A")
        }
        val merged = a.sync(showId)
        assertEquals(SyncOutcome.MERGED, merged.outcome, merged.message)
        transaction(a.state.database) {
            val show = DaoProject[showId]
            assertEquals(1, show.venueVersion, "the merge kept this desk's pin, not the remote's none")
            assertTrue(show.cueStacks.any { it.name == "Peer's stack" }, "and took the peer's edit")
        }
        assertEquals(rigBefore, Fixtures().also { DbFixtureLoader.loadFixtures(showId, it, a.state.database) }.getChannelMappings())
        val pushed = Git.open(showBare.toFile()).use { JGitClient.readBlob(it.repository, "refs/heads/main", "project.json")!! }
        assertTrue("\"venueVersion\": 1" in pushed, "the merge commit carries the pin: $pushed")
    }

    @Test
    fun `a detach on a show that already syncs keeps its origin through a merge with a peer's edit`() {
        val (a, showId) = deskWithVenueOnRepo()
        a.attach(showId, showUrl)
        assertEquals(SyncOutcome.PUSHED, a.sync(showId).outcome)
        val b = Desk("b")
        b.importRepo(venueUrl)
        val showB = b.importRepo(showUrl)
        transaction(b.state.database) { DaoCueStack.new { project = DaoProject[showB]; name = "Peer's stack" } }
        assertEquals(SyncOutcome.PUSHED, b.sync(showB).outcome)

        // A detaches while B's edit is on the remote: the pin and the origin move together in the merge.
        val pin = transaction(a.state.database) { DaoProject[showId].let { it.venueUuid!! to it.venueVersion!! } }
        runBlocking { a.state.venueDetach.detach(showId, dryRun = false) }
        val merged = a.sync(showId)
        assertEquals(SyncOutcome.MERGED, merged.outcome, merged.message)
        transaction(a.state.database) {
            val show = DaoProject[showId]
            assertEquals(null, show.venueUuid, "the merge kept this desk's cleared pin")
            assertEquals(pin, show.detachedFromVenueUuid to show.detachedFromVersion, "and its origin")
            assertTrue(show.cueStacks.any { it.name == "Peer's stack" }, "and took the peer's edit")
        }
        val pushed = Git.open(showBare.toFile()).use { JGitClient.readBlob(it.repository, "refs/heads/main", "project.json")!! }
        assertTrue("\"detachedFromVersion\": ${pin.second}" in pushed, "the merge commit carries the origin: $pushed")
        assertFalse("\"venueUuid\"" in pushed, pushed)
    }

    @Test
    fun `a remote tag that is not a published version is left alone`() {
        val (a, showId) = deskWithVenueOnRepo()
        val venueA = venueOf(a, showId)
        // Someone tags a commit v7 by hand, without a venueVersion.json.
        val work = tmp.resolve("hand")
        Git.cloneRepository().setURI(venueUrl).setDirectory(work.toFile()).call().use { git ->
            Files.delete(work.resolve("venueVersion.json"))
            git.rm().addFilepattern("venueVersion.json").call()
            git.commit().setMessage("not a version").setAuthor("x", "x@x").setCommitter("x", "x@x").setSign(false).call()
            git.tag().setName("v7").setMessage("v7").setAnnotated(true).setSigned(false).call()
            git.push().setPushTags().add("main").call()
        }
        val result = a.sync(venueA)
        assertTrue("Left v7" in result.message, result.message)
        assertEquals(listOf(1, 2), a.versions(venueA).map { it.first }, "nothing was materialised from it")
        assertTrue("Left v7" in a.sync(venueA).message, "and the next exchange is not wedged")
    }

    @Test
    fun `a pull does not replace a draft that changed after it was read`() {
        val a = Desk("a")
        val f = seedShowOnVenue(a.state)
        a.moveTruss(f.venueId, 1.0)
        runBlocking { a.state.venueVersions.publish(f.venueId, "v3", "A") }
        val read = VenueDiff.recordsOf(a.state, f.venueId)
        a.moveTruss(f.venueId, 2.0)
        val edited = a.trussX(f.venueId)
        assertFalse(a.state.venueVersions.replaceDraftWithInstalled(f.venueId, ifDraftIs = read))
        assertEquals(edited, a.trussX(f.venueId), "the edit made after the read is kept")
        assertTrue(a.state.venueVersions.replaceDraftWithInstalled(f.venueId, ifDraftIs = VenueDiff.recordsOf(a.state, f.venueId)))
        assertEquals(0, a.state.venueVersions.draftChanges(f.venueId))
    }

    @Test
    fun `a clone of a show on a venue keeps its pin and hides, the hides re-identified`() {
        val a = Desk("a")
        val f = seedShowOnVenue(a.state)
        val clone = ProjectCloner(a.state).clone(f.showId, "Panto copy", null)
        transaction(a.state.database) {
            val source = DaoProject[f.showId]
            val copy = DaoProject[clone.projectId]
            assertEquals(source.venueUuid, copy.venueUuid)
            assertEquals(source.venueVersion, copy.venueVersion)
            val hides = { id: Int -> DaoShowHide.find { DaoShowHides.project eq id }.map { it.uuid to (it.recordKind to it.recordUuid) } }
            val (sourceHide, copyHide) = hides(f.showId).single() to hides(clone.projectId).single()
            assertEquals(f.hideKind to f.hiddenUuid, copyHide.second, "the hide names the same venue record")
            assertNotEquals(sourceHide.first, copyHide.first, "its own identity is fresh")
        }
        val rig = { id: Int -> Fixtures().also { DbFixtureLoader.loadFixtures(id, it, a.state.database) }.getChannelMappings() }
        assertEquals(rig(f.showId), rig(clone.projectId))
    }
}
