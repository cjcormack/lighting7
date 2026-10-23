package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals

/**
 * A merge with a remote an older desk wrote (v37, [LegacyNames.FX_LIBRARY] 1): its bouncing FX naming
 * no `pingPong` ran with it on, this desk's mean their Direction, and the merged tree says this desk's
 * `formatVersion`, which implies the library.
 */
class FxLibraryMergeTest {

    private lateinit var state: State
    private lateinit var workingRoot: Path
    private lateinit var bareRepo: Path
    private lateinit var scratchRoot: Path
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var engine: RemoteSyncEngine

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        workingRoot = Files.createTempDirectory("lighting7-fxlib-")
        bareRepo = Files.createTempDirectory("lighting7-fxlib-bare-")
        scratchRoot = Files.createTempDirectory("lighting7-fxlib-scratch-")
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

    @Test
    fun `an older desk's Sweep merges as it played, and this desk's as it plays`() {
        val projectId = seedMinimalProject(state)
        configureSync(projectId)
        assertEquals(SyncOutcome.PUSHED, runSync(projectId).outcome)

        val theirs = UUID.randomUUID()
        pushFromPeer { tree ->
            val project = tree.resolve("project.json")
            val json = canonicalDecode(ProjectJson.serializer(), Files.readString(project))
            Files.writeString(project, canonicalEncode(ProjectJson.serializer(), json.copy(fxLibrary = 1)))
            write(tree, "formatVersion.json", canonicalEncode(FormatVersionJson.serializer(), FormatVersionJson(formatVersion = 37, minReader = 5)))
            // In the older desk's words: a v37 Look's FX under `effects`, each field by its old name.
            write(
                tree, "looks/$theirs.json",
                """{"effects":[{"beatDivision":4.0,"blendMode":"OVERRIDE","category":"position","distribution":"LINEAR",""" +
                    """"effectType":"Sweep","targetKey":"front","targetType":"group","uuid":"${UUID.randomUUID()}"}],""" +
                    """"name":"Theirs","uuid":"$theirs"}""",
            )
        }

        transaction(state.database) {
            val look = DaoLook.new { project = DaoProject[projectId]; name = "Ours" }
            DaoLookEffect.new {
                this.look = look; targetType = "group"; targetKey = "front"
                fxType = "Sweep"; family = "position"; cycle = 2.0; blendMode = "OVERRIDE"
                order = "NORMAL"; direction = "BOUNCE"; parameters = emptyMap(); sortOrder = 0
            }
        }

        assertEquals(SyncOutcome.MERGED, runSync(projectId).outcome)
        assertEquals(mapOf("Ours" to (2.0 to "BOUNCE"), "Theirs" to (2.0 to "BOUNCE")), sweeps(projectId))
        val pushed = cloneRemote()
        assertEquals(null, canonicalDecode(ProjectJson.serializer(), Files.readString(pushed.resolve("project.json"))).fxLibrary)
        assertEquals(FormatVersionJson().formatVersion, canonicalDecode(FormatVersionJson.serializer(), Files.readString(pushed.resolve("formatVersion.json"))).formatVersion)

        runSync(projectId)
        assertEquals(SyncOutcome.NO_OP, runSync(projectId).outcome)
        assertEquals(mapOf("Ours" to (2.0 to "BOUNCE"), "Theirs" to (2.0 to "BOUNCE")), sweeps(projectId), "nothing rewritten twice")
    }

    private fun sweeps(projectId: Int): Map<String, Pair<Double, String>> = transaction(state.database) {
        DaoLook.find { DaoLooks.project eq projectId }.associate { look ->
            val effect = look.effects.single()
            look.name to (effect.cycle to effect.direction)
        }
    }

    private fun write(dir: Path, rel: String, text: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
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
