package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPromptBook
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that a prompt-book's script PDF — a binary, content-addressed blob living at
 * `promptScripts/{hash}.pdf` — travels through cloud sync (format v4) as raw bytes, without
 * being corrupted by the text-oriented diff machinery, and without being lost by the
 * wipe-then-export snapshot pipeline.
 *
 * Harness mirrors [RemoteSyncEngineTombstonePropagationTest]: two installs (A, C) sync
 * against one bare repo. Each install pins its own `promptBooks.scriptStoreRoot` so the
 * content stores stay isolated.
 */
class PromptScriptSyncTest {

    private lateinit var bareRepo: Path
    private lateinit var workingRootA: Path
    private lateinit var workingRootC: Path
    private lateinit var storeRootA: Path
    private lateinit var storeRootC: Path
    private lateinit var stateA: State
    private lateinit var stateC: State
    private lateinit var credsA: InMemoryCredentialStore
    private lateinit var credsC: InMemoryCredentialStore
    private lateinit var engineA: RemoteSyncEngine
    private lateinit var engineC: RemoteSyncEngine

    private val repoUrl: String get() = bareRepo.toUri().toString()

    // A tiny but valid-looking PDF payload; the sync layer treats it as opaque bytes.
    private val pdfV1 = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".toByteArray()
    private val pdfV2 = "%PDF-1.7\n1 0 obj<< /Kids [] >>endobj\ntrailer<<>>\n%%EOF v2\n".toByteArray()

    @Before
    fun setUp() {
        bareRepo = Files.createTempDirectory("lighting7-pdf-bare-")
        Git.init().setBare(true).setDirectory(bareRepo.toFile()).setInitialBranch("main").call().close()

        val tmpDir = Files.createTempDirectory("lighting7-pdf-dbs-")
        workingRootA = Files.createTempDirectory("lighting7-pdf-a-")
        workingRootC = Files.createTempDirectory("lighting7-pdf-c-")
        storeRootA = Files.createTempDirectory("lighting7-pdf-store-a-")
        storeRootC = Files.createTempDirectory("lighting7-pdf-store-c-")

        stateA = State(
            testAppConfig(
                "database.path" to tmpDir.resolve("a.db").toString(),
                "sync.workingTreeRoot" to workingRootA.toString(),
                "promptBooks.scriptStoreRoot" to storeRootA.toString(),
            ),
        )
        credsA = InMemoryCredentialStore()
        engineA = RemoteSyncEngine(stateA, AuthResolver(credsA, tokenStore = null, tokenProvider = null))

        stateC = State(
            testAppConfig(
                "database.path" to tmpDir.resolve("c.db").toString(),
                "sync.workingTreeRoot" to workingRootC.toString(),
                "promptBooks.scriptStoreRoot" to storeRootC.toString(),
            ),
        )
        credsC = InMemoryCredentialStore()
        engineC = RemoteSyncEngine(stateC, AuthResolver(credsC, tokenStore = null, tokenProvider = null))
    }

    @After
    fun tearDown() {
        runCatching { stateA.shutdown() }
        runCatching { stateC.shutdown() }
        for (dir in listOf(workingRootA, workingRootC, storeRootA, storeRootC, bareRepo)) {
            runCatching { dir.toFile().deleteRecursively() }
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun configureSync(state: State, projectId: Int, creds: InMemoryCredentialStore) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.firstOrNull()
                ?: DaoSyncConfig.new { this.project = project }
            cfg.repoUrl = repoUrl
        }
        creds.set(repoUrl, "test-pat")
    }

    private fun runSync(state: State, engine: RemoteSyncEngine, projectId: Int): SyncRunResult {
        val triple = transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            val install = DaoInstall.all().first()
            Triple(project.uuid, install.uuid, install.friendlyName)
        }
        return runBlocking { engine.runSync(projectId, triple.first, triple.second, triple.third) }
    }

    /** Write [bytes] into [state]'s content store for [projectUuid] and return the hash. */
    private fun putPdfInStore(state: State, projectUuid: UUID, bytes: ByteArray): String {
        val hash = RecordHasher.sha256Hex(bytes)
        val path = state.promptScriptPath(projectUuid.toString(), hash)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return hash
    }

    /** Attach a prompt book referencing [hash] to the project. */
    private fun attachBook(state: State, projectId: Int, hash: String, fileName: String) {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)!!
            DaoPromptBook.new {
                this.project = project
                this.scriptHash = hash
                this.scriptFileName = fileName
                this.pageCount = 4
                this.coverPages = 0
            }
        }
    }

    private fun repoPath(workingRoot: Path, projectUuid: UUID): Path =
        workingRoot.resolve(projectUuid.toString()).resolve("repo")

    private fun pdfInTree(workingRoot: Path, projectUuid: UUID, hash: String): Path =
        repoPath(workingRoot, projectUuid).resolve("promptScripts").resolve("$hash.pdf")

    // ─── tests ───────────────────────────────────────────────────────────

    @Test
    fun `PDF propagates A to C byte-identically and lands in C's store`() {
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val projectUuid = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }

        val hash = putPdfInStore(stateA, projectUuid, pdfV1)
        attachBook(stateA, projectIdA, hash, "act-one.pdf")

        val push = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.PUSHED, push.outcome)

        // A's working tree carries the binary blob, byte-identical to the source.
        val aTreePdf = pdfInTree(workingRootA, projectUuid, hash)
        assertTrue(Files.exists(aTreePdf), "A's working tree should hold promptScripts/$hash.pdf")
        assertTrue(pdfV1.contentEquals(Files.readAllBytes(aTreePdf)), "A's committed PDF must be byte-identical")

        // A's working tree gained the binary .gitattributes rule and a v4 formatVersion.
        val gitattributes = Files.readString(repoPath(workingRootA, projectUuid).resolve(".gitattributes"))
        assertTrue(gitattributes.contains("promptScripts/** binary"), "binary attribute must be present")
        val formatJson = Files.readString(repoPath(workingRootA, projectUuid).resolve("formatVersion.json"))
        assertTrue(formatJson.contains("\"formatVersion\": 4"), "writer must emit formatVersion 4; got: $formatJson")

        // C bootstraps with A's UUID and pulls.
        val projectIdC = transaction(stateC.database) {
            DaoProject.new { name = "C-placeholder"; description = ""; isCurrent = true; uuid = projectUuid }.id.value
        }
        configureSync(stateC, projectIdC, credsC)
        val pull = runSync(stateC, engineC, projectIdC)
        assertTrue(
            pull.outcome == SyncOutcome.FAST_FORWARDED || pull.outcome == SyncOutcome.MERGED,
            "C should pull A's history; got ${pull.outcome}",
        )

        // C's working tree AND its content store hold the byte-identical PDF — no
        // manual re-import needed.
        assertTrue(pdfV1.contentEquals(Files.readAllBytes(pdfInTree(workingRootC, projectUuid, hash))))
        val cStorePdf = stateC.promptScriptPath(projectUuid.toString(), hash)
        assertTrue(Files.exists(cStorePdf), "C's content store must be hydrated from the pull")
        assertTrue(pdfV1.contentEquals(Files.readAllBytes(cStorePdf)), "C's store PDF must be byte-identical")
    }

    @Test
    fun `changing scriptHash swaps the PDF and GCs the orphan`() {
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val projectUuid = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }

        val hash1 = putPdfInStore(stateA, projectUuid, pdfV1)
        attachBook(stateA, projectIdA, hash1, "act-one.pdf")
        runSync(stateA, engineA, projectIdA)
        assertTrue(Files.exists(pdfInTree(workingRootA, projectUuid, hash1)))

        // Re-import a different PDF: new hash on the book, new bytes in the store.
        val hash2 = putPdfInStore(stateA, projectUuid, pdfV2)
        transaction(stateA.database) {
            DaoProject.findById(projectIdA)!!.promptBook!!.scriptHash = hash2
        }
        val push = runSync(stateA, engineA, projectIdA)
        assertEquals(SyncOutcome.PUSHED, push.outcome)

        assertTrue(Files.exists(pdfInTree(workingRootA, projectUuid, hash2)), "new PDF must be present")
        assertFalse(Files.exists(pdfInTree(workingRootA, projectUuid, hash1)), "orphaned PDF must be GC'd")
    }

    @Test
    fun `store-less install does not drop the repo's PDF`() {
        // A publishes a book + PDF.
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val projectUuid = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }
        val hash = putPdfInStore(stateA, projectUuid, pdfV1)
        attachBook(stateA, projectIdA, hash, "act-one.pdf")
        runSync(stateA, engineA, projectIdA)

        // C pulls, then loses its local content store (simulates an install that holds the
        // book record + working-tree PDF but has no bytes in its store).
        val projectIdC = transaction(stateC.database) {
            DaoProject.new { name = "C-placeholder"; description = ""; isCurrent = true; uuid = projectUuid }.id.value
        }
        configureSync(stateC, projectIdC, credsC)
        runSync(stateC, engineC, projectIdC)
        storeRootC.toFile().deleteRecursively()

        // C makes an unrelated edit and syncs. The snapshot must NOT delete the PDF just
        // because the store lacks the bytes — that would revert it onto A.
        transaction(stateC.database) {
            val project = DaoProject.findById(projectIdC)!!
            DaoCueStack.new { this.project = project; this.name = "unrelated-on-C"; this.palette = emptyList() }
        }
        val cPush = runSync(stateC, engineC, projectIdC)
        assertTrue(
            cPush.outcome == SyncOutcome.PUSHED || cPush.outcome == SyncOutcome.MERGED,
            "C's push should succeed; got ${cPush.outcome}",
        )
        assertTrue(
            Files.exists(pdfInTree(workingRootC, projectUuid, hash)),
            "store-less install must preserve the repo's PDF, not drop it",
        )

        // A pulls C's push and still holds the PDF (not reverted).
        runSync(stateA, engineA, projectIdA)
        assertTrue(Files.exists(pdfInTree(workingRootA, projectUuid, hash)), "A must not lose the PDF")
    }

    @Test
    fun `divergent merge carries the local winner's PDF into the merge commit`() {
        // A publishes a book + baseline PDF; C pulls it so both share a base.
        val projectIdA = seedMinimalProject(stateA)
        configureSync(stateA, projectIdA, credsA)
        val projectUuid = transaction(stateA.database) { DaoProject.findById(projectIdA)!!.uuid }
        val baseHash = putPdfInStore(stateA, projectUuid, pdfV1)
        attachBook(stateA, projectIdA, baseHash, "act-one.pdf")
        runSync(stateA, engineA, projectIdA)

        val projectIdC = transaction(stateC.database) {
            DaoProject.new { name = "C-placeholder"; description = ""; isCurrent = true; uuid = projectUuid }.id.value
        }
        configureSync(stateC, projectIdC, credsC)
        runSync(stateC, engineC, projectIdC)
        assertTrue(Files.exists(pdfInTree(workingRootC, projectUuid, baseHash)), "C should have pulled the baseline PDF")

        // A edits an UNRELATED record (a cue stack) and pushes — remote moves ahead without
        // touching the book.
        transaction(stateA.database) {
            val project = DaoProject.findById(projectIdA)!!
            DaoCueStack.new { this.project = project; this.name = "on-A"; this.palette = emptyList() }
        }
        runSync(stateA, engineA, projectIdA)

        // C re-imports a DIFFERENT PDF (the book's scriptHash changes locally) and syncs. C
        // is now diverged: C changed the book, A changed a cue stack — a clean auto-merge in
        // which the book resolves TakeLocal. C's winning PDF must land in the merge commit,
        // and the superseded baseline PDF must be GC'd.
        val cHash = putPdfInStore(stateC, projectUuid, pdfV2)
        transaction(stateC.database) { DaoProject.findById(projectIdC)!!.promptBook!!.scriptHash = cHash }
        val cMerge = runSync(stateC, engineC, projectIdC)
        assertEquals(SyncOutcome.MERGED, cMerge.outcome)

        assertTrue(Files.exists(pdfInTree(workingRootC, projectUuid, cHash)), "merge commit must carry C's winning PDF")
        assertFalse(Files.exists(pdfInTree(workingRootC, projectUuid, baseHash)), "the superseded PDF must be GC'd from the merge")

        // A pulls the merge and must receive C's PDF (byte-identical) in both tree and store.
        runSync(stateA, engineA, projectIdA)
        assertTrue(pdfV2.contentEquals(Files.readAllBytes(pdfInTree(workingRootA, projectUuid, cHash))))
        val aStorePdf = stateA.promptScriptPath(projectUuid.toString(), cHash)
        assertTrue(Files.exists(aStorePdf) && pdfV2.contentEquals(Files.readAllBytes(aStorePdf)), "A's store must hold C's PDF")
    }
}
