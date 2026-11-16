package uk.me.cormack.lighting7.backup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.CloudConfig
import uk.me.cormack.lighting7.cloud.CloudConnectionStatus
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.CloudException
import uk.me.cormack.lighting7.cloud.CloudService
import uk.me.cormack.lighting7.cloud.protocol.AdoptionRestore
import uk.me.cormack.lighting7.cloud.protocol.BackupKind
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoInstalls
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoUserSessions
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.state.SCHEMA_USER_VERSION
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.FakeCloudBackups
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A backup made on one desk restored onto a second (cloud-p3 plan D9, D10): two data directories, one
 * [FakeCloud], the confirm, the restart that applies it, and every way it is refused or declined.
 */
class RestoreTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private lateinit var dir: Path
    private lateinit var fake: FakeCloud
    private val closing = mutableListOf<() -> Unit>()

    /** One desk: its state, cloud, credential store and the data directory it lives in. */
    private inner class Desk(val name: String, val store: InMemoryCredentialStore = InMemoryCredentialStore()) {
        val root: Path = dir.resolve(name)
        lateinit var state: State
        lateinit var cloud: CloudService
        lateinit var restores: RestoreService

        fun start(): Desk {
            Files.createDirectories(root)
            state = State(testAppConfig(
                "database.path" to root.resolve("lighting7.db").toString(),
                "sync.workingTreeRoot" to root.resolve("sync").toString(),
                "promptBooks.scriptStoreRoot" to root.resolve("prompt-scripts").toString(),
                "stage.sceneImageStoreRoot" to root.resolve("scene-images").toString(),
                "backups.root" to root.resolve("backups").toString(),
            ))
            cloud = CloudService(
                database = state.database,
                config = CloudConfig(fake.baseUrl, true),
                credentialStore = { store },
                apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
                onUnadopted = { restores.dropStaged() },
            ).also { it.pollDelay = { 20.milliseconds } }
            restores = RestoreService({ cloud }, state.restoreStaging, SCHEMA_USER_VERSION, FormatVersionJson().formatVersion)
            closing += { restores.close(); cloud.close(); runCatching { state.shutdown() } }
            cloud.start()
            restores.start()
            return this
        }

        fun stop() {
            restores.close()
            cloud.close()
            state.shutdown()
        }

        fun rename(friendlyName: String) = transaction(state.database) {
            DaoInstalls.update { it[DaoInstalls.friendlyName] = friendlyName }
        }

        fun adopt(adminId: Int) {
            fake.decision = FakeCloud.Decision.APPROVE
            runBlocking {
                cloud.startAdoption(adminId)
                withTimeout(15.seconds) { cloud.state.first { it.connection == CloudConnectionStatus.ONLINE } }
            }
        }

        val installId: String get() = cloud.current().adopted!!.installId
        val installUuid: String get() = transaction(state.database) { DaoInstall.all().first().uuid.toString() }
        val fingerprint: String get() = transaction(state.database) { DaoCloudConnections.selectAll().single()[DaoCloudConnections.fingerprint] }

        fun write(relative: String, text: String) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }

        fun backUp(): String {
            val service = BackupService(
                database = state.database,
                staging = InstallBackup(root.resolve("backups/staging")),
                sources = {
                    BackupSources(root.resolve("lighting7.db"), root.resolve("prompt-scripts"), root.resolve("scene-images"), root.resolve("sync"), null)
                },
                origin = { cloud.installSigner()?.let { BackupOrigin("1.0.0", FormatVersionJson().formatVersion, installUuid, "Desk A", it.installId) } },
                signer = { cloud.installSigner() },
                api = { cloud.api() },
            )
            try {
                val status = runBlocking { service.runOnce(BackupKind.MANUAL) }
                assertNull(status.lastCode, status.lastMessage)
                return assertNotNull(status.lastBackupId)
            } finally {
                service.close()
            }
        }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("restore-")
        fake = FakeCloud()
    }

    @After
    fun tearDown() {
        closing.asReversed().forEach { runCatching(it) }
        runCatching { fake.close() }
        dir.toFile().deleteRecursively()
    }

    /** Desk A, adopted with remote sessions on, its show, users, sync config and files, backed up. */
    private fun deskWithBackup(): Pair<Desk, String> {
        val a = Desk("a").start()
        a.rename("Desk A")
        val alice = seedUser(a.state, "alice")
        seedUser(a.state, "bob")
        a.state.authService.mintSession(alice, "test", "127.0.0.1")
        transaction(a.state.database) {
            val project = DaoProject.new { name = "Hamlet"; isCurrent = false }
            DaoSyncConfigs.insert {
                it[DaoSyncConfigs.project] = project.id
                it[repoUrl] = "https://cloud.example/git/hamlet.git"
            }
        }
        a.write("prompt-scripts/p-1/book.pdf", "%PDF a prompt book")
        a.write("scene-images/p-1/cloth.png", "png")
        a.write("sync/p-1/repo/project.json", """{"name":"Hamlet"}""")
        a.adopt(alice.userId)
        a.cloud.setSwitches(remoteAccess = true)
        val backupId = a.backUp()
        return a to backupId
    }

    /** Desk B, freshly installed and adopted with [restore], its own file standing where A's will go. */
    private fun freshDesk(restore: AdoptionRestore): Desk {
        val b = Desk("b").start()
        b.rename("Desk B")
        val carol = seedUser(b.state, "carol")
        b.write("prompt-scripts/p-9/fresh.pdf", "B's own")
        fake.restore = restore
        b.adopt(carol.userId)
        return b
    }

    private fun Desk.restart(): Desk {
        stop()
        // The same machine: its credential store keeps the install key.
        return Desk(name, store).start()
    }

    @Test
    fun `a backup made on one desk is restored on a second, which comes back as the old install`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        val oldKey = fake.installs.getValue(oldId).key
        a.stop()

        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        val bUuid = b.installUuid
        val bFingerprint = b.fingerprint
        val bId = b.installId
        assertNotEquals(oldId, bId)
        assertEquals(oldKey, fake.installs.getValue(oldId).key, "approval moves nothing about the old install")
        assertTrue(fake.replaced.isEmpty())
        assertEquals("Desk A", b.cloud.current().adopted?.restore?.replacesName)

        val status = runBlocking { b.restores.confirm("Desk A") }
        assertNull(status.lastError, status.lastError?.message)
        assertEquals(RestoreService.PHASE_READY, status.phase)
        assertTrue(b.cloud.state.value.restartToRestore)
        assertEquals(listOf(bId to oldId), fake.replaced.toList())
        assertEquals(oldId, b.cloud.current().adopted?.installId)
        assertNull(b.cloud.current().adopted?.restore, "the restore is no longer pending")
        runBlocking { withTimeout(15.seconds) { while (oldId !in fake.online) kotlinx.coroutines.delay(20) } }

        val restored = b.restart()
        val db = restored.state.database
        transaction(db) {
            assertEquals(setOf("alice", "bob"), DaoUsers.selectAll().map { it[DaoUsers.username] }.toSet())
            assertEquals(listOf("Hamlet"), DaoProjects.selectAll().map { it[DaoProjects.name] }.distinct())
            assertEquals("https://cloud.example/git/hamlet.git", DaoSyncConfigs.selectAll().single()[DaoSyncConfigs.repoUrl])
            assertEquals(1L, DaoCloudIdentityLinks.selectAll().count(), "a replacement keeps the old desk's links: they name this identity now")
            assertEquals(0L, DaoUserSessions.selectAll().count(), "everyone signs in again")
            val row = DaoCloudConnections.selectAll().single()
            assertEquals(oldId, row[DaoCloudConnections.installId])
            assertEquals(bFingerprint, row[DaoCloudConnections.fingerprint])
            assertNull(row[DaoCloudConnections.adoptedBy])
            assertTrue(row[DaoCloudConnections.remoteAccess], "the old desk's remote switches")
            assertFalse(row[DaoCloudConnections.mcpAccess])
        }
        assertEquals(bUuid, restored.installUuid, "this machine's install uuid, which keys its install key")
        assertEquals("Desk B", transaction(db) { DaoInstall.all().first().friendlyName })
        assertEquals("%PDF a prompt book", Files.readString(restored.root.resolve("prompt-scripts/p-1/book.pdf")))
        assertEquals("""{"name":"Hamlet"}""", Files.readString(restored.root.resolve("sync/p-1/repo/project.json")))
        assertFalse(restored.root.resolve("prompt-scripts/p-9").exists(), "the fresh desk's files are replaced")
        assertTrue(kept(restored.root, "prompt-scripts").isNotEmpty(), "and kept beside until the next start")
        assertNull(restored.state.restoreStaging.readyMarker(), "nothing is left to apply")
        runBlocking { withTimeout(15.seconds) { restored.cloud.state.first { it.connection == CloudConnectionStatus.ONLINE } } }
        assertEquals(oldId, restored.cloud.current().adopted?.installId)
        assertFalse(restored.cloud.state.value.restartToRestore)

        val again = restored.restart()
        assertTrue(kept(again.root, "prompt-scripts").isEmpty(), "the next start sweeps what the restore kept")
    }

    @Test
    fun `a replacement without a backup moves the identity and no data`() {
        val (a, _) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A"))
        val status = runBlocking { b.restores.confirm("Desk A") }
        assertNull(status.lastError, status.lastError?.message)
        assertEquals(RestoreService.PHASE_IDLE, status.phase)
        assertEquals(oldId, b.cloud.current().adopted?.installId)
        assertFalse(b.cloud.state.value.restartToRestore)
        assertNull(b.state.restoreStaging.readyMarker())
        val restarted = b.restart()
        assertEquals(setOf("carol"), transaction(restarted.state.database) { DaoUsers.selectAll().map { it[DaoUsers.username] }.toSet() })
    }

    @Test
    fun `a backup alone restores data under the desk's own install id and clears the old links`() {
        val (a, backupId) = deskWithBackup()
        a.stop()
        val b = freshDesk(AdoptionRestore(backupId = backupId))
        val bId = b.installId
        assertEquals("Desk A", runBlocking { b.restores.status() }.confirmName)
        val status = runBlocking { b.restores.confirm("Desk A") }
        assertNull(status.lastError, status.lastError?.message)
        assertEquals(RestoreService.PHASE_READY, status.phase)
        assertTrue(fake.replaced.isEmpty())
        assertEquals(bId, b.cloud.current().adopted?.installId)

        val restored = b.restart()
        transaction(restored.state.database) {
            assertEquals(setOf("alice", "bob"), DaoUsers.selectAll().map { it[DaoUsers.username] }.toSet())
            assertEquals(0L, DaoCloudIdentityLinks.selectAll().count(), "the links named the old install's identity")
            assertEquals(bId, DaoCloudConnections.selectAll().single()[DaoCloudConnections.installId])
        }
    }

    @Test
    fun `a decline leaves the old install untouched and this desk its own`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        val bId = b.installId
        runBlocking { b.restores.decline() }
        assertFalse(fake.replacements.containsKey(bId), "the cloud is told")
        assertTrue(fake.replaced.isEmpty())
        assertEquals(bId, b.cloud.current().adopted?.installId)
        assertNull(b.cloud.current().adopted?.restore)
        assertFailsWith<CloudException> { runBlocking { b.restores.confirm("Desk A") } }
    }

    @Test
    fun `an expired replacement leaves the desk its own install`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        fake.replacementExpiresIn = Duration.ofMillis(-1)
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        val bId = b.installId
        val status = runBlocking { b.restores.confirm("Desk A") }
        assertEquals(ErrorCode.REPLACEMENT_EXPIRED, status.lastError?.code)
        assertTrue(fake.replaced.isEmpty())
        assertEquals(bId, b.cloud.current().adopted?.installId)
        assertNull(b.cloud.current().adopted?.restore, "the restore is gone with its replacement")
        assertNull(b.state.restoreStaging.readyMarker(), "the downloaded backup is deleted")
        assertFalse(b.cloud.state.value.restartToRestore)
    }

    @Test
    fun `a backup from a newer desk is refused before anything is downloaded or replaced`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val stored = fake.backups.backups.getValue(backupId)
        val newer = stored.request.copy(manifest = stored.request.manifest.copy(schemaVersion = SCHEMA_USER_VERSION + 1, deskVersion = "9.0.0"))
        stored.request = newer
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        assertTrue(runBlocking { b.restores.status() }.backup!!.tooNew)
        val status = runBlocking { b.restores.confirm("Desk A") }
        assertEquals(ErrorCode.BACKUP_TOO_NEW, status.lastError?.code)
        assertTrue(fake.replaced.isEmpty())
        assertFalse(fake.backups.calls.any { it.endsWith("/file") }, "nothing was downloaded")
        assertEquals("Desk A", b.cloud.current().adopted?.restore?.replacesName, "the restore is still pending, to decline")
    }

    @Test
    fun `a corrupt chunk is refused before anything is replaced`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val part = fake.backups.backups.getValue(backupId).parts.getValue(1)
        part[part.size - 40] = (part[part.size - 40].toInt() xor 1).toByte()
        val stored = fake.backups.backups.getValue(backupId)
        stored.request = stored.request.copy(sha256 = FakeCloudBackups.sha256(stored.bytes()))
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        val status = runBlocking { b.restores.confirm("Desk A") }
        assertEquals(CloudErrorCodes.BACKUP_CORRUPT, status.lastError?.code, status.lastError?.message)
        assertTrue(fake.replaced.isEmpty())
        assertNull(b.state.restoreStaging.readyMarker())
        assertTrue(Files.list(b.state.restoreStaging.root).use { it.toList() }.isEmpty(), "no plaintext is left behind")
        assertEquals("B's own", Files.readString(b.root.resolve("prompt-scripts/p-9/fresh.pdf")))
    }

    @Test
    fun `a replacement whose answer was lost applies nothing at a restart, and the next confirm finds it happened`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        fake.loseReplaceAnswers.set(1)
        val status = runBlocking { b.restores.confirm("Desk A") }
        assertEquals(CloudErrorCodes.UNREACHABLE, status.lastError?.code, status.lastError?.message)
        assertEquals(RestoreService.PHASE_IDLE, status.phase)
        assertEquals(1, fake.replaced.size, "the cloud made the replacement")
        assertNull(b.state.restoreStaging.readyMarker(), "nothing is staged for a restart before the replacement is known")
        assertEquals("Desk A", b.cloud.current().adopted?.restore?.replacesName, "the restore is still pending")
        assertFalse(b.cloud.state.value.restartToRestore)

        val restarted = b.restart()
        assertEquals(setOf("carol"), users(restarted), "the restart applied nothing")
        assertFalse(restarted.state.restoreStaging.verified.exists(), "the unmarked tree is swept")

        val again = runBlocking { restarted.restores.confirm("Desk A") }
        assertNull(again.lastError, again.lastError?.message)
        assertEquals(RestoreService.PHASE_READY, again.phase)
        assertEquals(oldId, restarted.cloud.current().adopted?.installId, "the replacement had happened")
        assertEquals(1, fake.replaced.size)

        val restored = restarted.restart()
        assertEquals(setOf("alice", "bob"), users(restored))
        assertEquals(oldId, restored.cloud.current().adopted?.installId)
    }

    @Test
    fun `a confirm after a lost answer reuses the checked backup and finds the replacement made`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        fake.loseReplaceAnswers.set(1)
        assertEquals(CloudErrorCodes.UNREACHABLE, runBlocking { b.restores.confirm("Desk A") }.lastError?.code)
        assertNotNull(b.state.restoreStaging.verifiedMarker(), "the checked tree waits for the next confirm")
        val downloads = fake.backups.calls.count { it.endsWith("/file") }

        val status = runBlocking { b.restores.confirm("Desk A") }
        assertNull(status.lastError, status.lastError?.message)
        assertEquals(RestoreService.PHASE_READY, status.phase)
        assertEquals(downloads, fake.backups.calls.count { it.endsWith("/file") }, "nothing is downloaded again")
        assertEquals(oldId, b.cloud.current().adopted?.installId)
        assertNull(b.cloud.current().adopted?.restore)
        assertNotNull(b.state.restoreStaging.readyMarker())
    }

    @Test
    fun `a staged restore declined before the restart is dropped, and the replacement stands`() {
        val (a, backupId) = deskWithBackup()
        val oldId = a.installId
        a.stop()
        val b = freshDesk(AdoptionRestore(replacesInstallId = oldId, replacesName = "Desk A", backupId = backupId))
        assertEquals(RestoreService.PHASE_READY, runBlocking { b.restores.confirm("Desk A") }.phase)
        runBlocking { b.restores.decline() }
        assertNull(b.state.restoreStaging.readyMarker())
        assertFalse(b.cloud.state.value.restartToRestore)
        assertEquals(RestoreService.PHASE_IDLE, runBlocking { b.restores.status() }.phase)
        assertEquals(oldId, b.cloud.current().adopted?.installId, "the desk keeps the old install's id")
        assertEquals(setOf("carol"), users(b.restart()))
    }

    @Test
    fun `unadopting drops a staged restore`() {
        val (a, backupId) = deskWithBackup()
        a.stop()
        val b = freshDesk(AdoptionRestore(backupId = backupId))
        assertEquals(RestoreService.PHASE_READY, runBlocking { b.restores.confirm("Desk A") }.phase)
        runBlocking { b.cloud.unadopt(force = true) }
        assertNull(b.state.restoreStaging.readyMarker())
        assertFalse(b.cloud.state.value.restartToRestore)
        assertEquals(setOf("carol"), users(b.restart()))
    }

    private fun users(desk: Desk): Set<String> =
        transaction(desk.state.database) { DaoUsers.selectAll().map { it[DaoUsers.username] }.toSet() }

    private fun kept(root: Path, tree: String): List<Path> =
        Files.list(root).use { files -> files.filter { it.name.startsWith("$tree${RestoreApply.KEPT_SUFFIX}") }.toList() }
}
