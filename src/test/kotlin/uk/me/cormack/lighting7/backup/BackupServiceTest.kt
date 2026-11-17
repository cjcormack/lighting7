package uk.me.cormack.lighting7.backup

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.CloudBackups
import uk.me.cormack.lighting7.cloud.CloudConfig
import uk.me.cormack.lighting7.cloud.CloudConnectionStatus
import uk.me.cormack.lighting7.cloud.CloudErrorCodes
import uk.me.cormack.lighting7.cloud.CloudService
import uk.me.cormack.lighting7.cloud.protocol.BackupCipher
import uk.me.cormack.lighting7.cloud.protocol.BackupKind
import uk.me.cormack.lighting7.cloud.protocol.BackupManifest
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Making and uploading a backup to [FakeCloud] (cloud-p3 plan D6–D8). */
class BackupServiceTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var dir: Path
    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private var cloud: CloudService? = null
    private var service: BackupService? = null
    private val now = AtomicReference(Instant.parse("2026-11-16T02:00:00Z"))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("backup-service-")
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud()
    }

    @After
    fun tearDown() {
        service?.close()
        cloud?.close()
        runCatching { fake.close() }
        runCatching { state.shutdown() }
        dir.toFile().deleteRecursively()
    }

    private fun adoptedCloud(backups: CloudBackups? = null): CloudService {
        val adminId = seedUser(state, "admin").userId
        val store = InMemoryCredentialStore()
        val s = CloudService(
            database = state.database,
            config = CloudConfig(fake.baseUrl, true),
            credentialStore = { store },
            apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
            backups = backups,
        ).also { cloud = it }
        s.pollDelay = { 20.milliseconds }
        s.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking {
            s.startAdoption(adminId)
            withTimeout(15.seconds) { s.state.first { it.connection == CloudConnectionStatus.ONLINE } }
        }
        return s
    }

    private fun sources(): BackupSources {
        val root = dir.resolve("data")
        Files.createDirectories(root.resolve("scene-images/p-1"))
        Files.writeString(root.resolve("scene-images/p-1/a.png"), "an image")
        return BackupSources(Path.of(IntegrationTestDb.path), root.resolve("prompt-scripts"), root.resolve("scene-images"), root.resolve("sync"), null)
    }

    /** A file big enough that the archive is two parts. */
    private fun bigFile() {
        Files.createDirectories(dir.resolve("data/scene-images/p-1"))
        RandomAccessFile(dir.resolve("data/scene-images/p-1/big.bin").toFile(), "rw").use { it.setLength(70L * 1024 * 1024) }
    }

    private fun backups(
        cloud: CloudService,
        busy: () -> Boolean = { false },
        sleep: suspend (Duration) -> Unit = {},
        sources: () -> BackupSources = ::sources,
    ): BackupService =
        BackupService(
            database = state.database,
            staging = InstallBackup(dir.resolve("staging")),
            sources = sources,
            origin = { cloud.installSigner()?.let { BackupOrigin("1.0.0", 56, "uuid", "Desk", it.installId) } },
            signer = { cloud.installSigner() },
            api = { cloud.api() },
            busy = busy,
            clock = { now.get() },
            zone = { ZoneId.of("UTC") },
            sleep = sleep,
        ).also { service = it }

    private fun staged(): List<String> =
        if (Files.exists(dir.resolve("staging"))) Files.list(dir.resolve("staging")).use { s -> s.map { it.name }.toList() } else emptyList()

    @Test
    fun `a backup uploaded to the cloud decrypts back to its archive`() {
        val c = adoptedCloud()
        val b = backups(c)
        val status = runBlocking { b.runOnce(BackupKind.MANUAL) }

        assertNull(status.lastCode, status.lastMessage)
        val stored = fake.backups.backups.values.single()
        assertTrue(stored.complete)
        assertEquals(BackupKind.MANUAL, stored.request.kind)
        assertEquals(stored.request.backupId, status.lastBackupId)
        assertEquals(stored.request.sizeBytes, status.lastSizeBytes)
        assertEquals(c.installSigner()!!.installId, stored.installId)

        val paths = mutableListOf<String>()
        var manifest: BackupManifest? = null
        val tar = TarReader(BackupCipher.decryptingStream(stored.bytes().inputStream(), stored.request.backupId) { fake.backups.keys.getValue(it) })
        while (true) {
            val entry = tar.next() ?: break
            paths += entry.path
            if (entry.path == InstallBackup.MANIFEST_ENTRY) manifest = CloudJson.decodeFromString(BackupManifest.serializer(), entry.content.readBytes().decodeToString())
        }
        assertEquals(listOf("lighting7.db", "scene-images/p-1/a.png", "manifest.json"), paths)
        assertEquals(stored.request.manifest, manifest)
        assertEquals(emptyList(), staged(), "an uploaded backup leaves nothing staged")
        assertTrue(b.health().lastOkAt != null)
        assertNull(b.health().code)
    }

    @Test
    fun `a failed part is retried, and once past its retries the next run resumes from the parts the cloud holds`() {
        val c = adoptedCloud()
        bigFile()
        val b = backups(c)
        // Part 1 lands; part 2 fails four times, past its three retries.
        fake.backups.failPart[2] = 4
        val first = runBlocking { b.runOnce(BackupKind.MANUAL) }
        assertEquals("UNAVAILABLE", first.lastCode, first.lastMessage)
        val stored = fake.backups.backups.values.single()
        assertFalse(stored.complete)
        assertEquals(setOf(1), stored.parts.keys)
        assertEquals(4, fake.backups.calls.count { it.endsWith("/parts/2") }, "part 2 was tried once and retried three times")
        assertTrue(staged().any { it.endsWith(".l7bk") }, "the archive stays staged for a resume")

        fake.backups.calls.clear()
        now.set(now.get().plus(Duration.ofHours(19)))
        val second = runBlocking { b.runOnce(BackupKind.SCHEDULED) }
        assertNull(second.lastCode, second.lastMessage)
        assertTrue(stored.complete)
        val id = stored.request.backupId
        assertEquals(listOf("GET /v1/backups/$id", "PUT /v1/backups/$id/parts/2", "POST /v1/backups/$id/complete"), fake.backups.calls)
        assertEquals(emptyList(), staged())
    }

    @Test
    fun `a staged backup older than twenty hours is dropped and the next run starts afresh`() {
        val c = adoptedCloud()
        val b = backups(c)
        fake.backups.failParts.set(4)
        val first = runBlocking { b.runOnce(BackupKind.SCHEDULED) }
        assertEquals("UNAVAILABLE", first.lastCode)
        val oldId = fake.backups.backups.keys.single()

        now.set(now.get().plus(Duration.ofHours(21)))
        val second = runBlocking { b.runOnce(BackupKind.SCHEDULED) }
        assertNull(second.lastCode, second.lastMessage)
        val newId = second.lastBackupId!!
        assertNotEquals(oldId, newId)
        assertTrue(fake.backups.backups.getValue(newId).complete)
        assertFalse(fake.backups.backups.getValue(oldId).complete)
        assertEquals(emptyList(), staged())
    }

    @Test
    fun `the quota, the size cap and BACKUPS_OFF are the last result's codes`() {
        val c = adoptedCloud()
        val b = backups(c)

        fake.backups.backupsOff = true
        assertEquals(ErrorCode.BACKUPS_OFF, runBlocking { b.runOnce(BackupKind.MANUAL) }.lastCode)
        assertEquals(ErrorCode.BACKUPS_OFF, b.health().code)
        fake.backups.backupsOff = false

        fake.backups.quotaBytes = 10
        assertEquals(ErrorCode.BACKUP_QUOTA_EXCEEDED, runBlocking { b.runOnce(BackupKind.MANUAL) }.lastCode)
        fake.backups.quotaBytes = Long.MAX_VALUE

        fake.backups.maxBytes = 10
        val tooLarge = runBlocking { b.runOnce(BackupKind.MANUAL) }
        assertEquals(ErrorCode.BACKUP_TOO_LARGE, tooLarge.lastCode)
        assertEquals(emptyList(), staged(), "an archive the cloud will never take is not kept")
        fake.backups.maxBytes = Long.MAX_VALUE

        val ok = runBlocking { b.runOnce(BackupKind.MANUAL) }
        assertNull(ok.lastCode)
        assertNull(b.health().code)
    }

    @Test
    fun `a desk that is not adopted records why`() {
        val b = BackupService(
            database = state.database,
            staging = InstallBackup(dir.resolve("staging")),
            sources = ::sources,
            origin = { null },
            signer = { null },
            api = { null },
        ).also { service = it }
        assertEquals(CloudErrorCodes.NOT_ADOPTED, runBlocking { b.runOnce(BackupKind.MANUAL) }.lastCode)
    }

    @Test
    fun `backup request is ignored with the switch off and runs with it on`() {
        val requested = AtomicInteger()
        val ref = AtomicReference<BackupService?>()
        val port = object : CloudBackups {
            override val enabled: Boolean get() = ref.get()?.enabled ?: false
            override fun requested() {
                requested.incrementAndGet()
                ref.get()?.request(BackupKind.REQUESTED)
            }
        }
        val c = adoptedCloud(port)
        val b = backups(c).also(ref::set)
        val installId = c.state.value.adopted!!.installId
        assertTrue(Capability.BACKUP_V1 in c.capabilities())
        assertTrue(Capability.BACKUP_OFF in c.capabilities())

        fake.send(installId, Message.BackupRequest)
        runBlocking { withTimeout(10.seconds) { while (requested.get() == 0) delay(10) } }
        Thread.sleep(300)
        assertTrue(fake.backups.backups.isEmpty(), "a request with the switch off makes nothing")
        assertFalse(b.request(BackupKind.REQUESTED))

        b.update(enabled = true)
        assertFalse(Capability.BACKUP_OFF in c.capabilities())
        fake.send(installId, Message.BackupRequest)
        runBlocking {
            withTimeout(30.seconds) { while (fake.backups.backups.values.none { it.complete }) delay(20) }
        }
        assertEquals(BackupKind.REQUESTED, fake.backups.backups.values.single().request.kind)
    }

    @Test
    fun `a scheduled backup waits while a stack runs, and runs after its cap`() {
        val c = adoptedCloud()
        val sleeps = mutableListOf<Duration>()
        val busy = AtomicReference(true)
        val b = backups(c, busy = { busy.get() }, sleep = { d -> sleeps += d; now.set(now.get().plus(d)) })
        b.update(enabled = true)
        val start = now.get()
        runBlocking { b.runScheduled() }
        assertEquals(36, sleeps.size, "checked every 10 minutes for 6 hours")
        assertTrue(sleeps.all { it == BackupSchedule.QUIET_CHECK })
        assertEquals(start.plus(BackupSchedule.QUIET_CAP), now.get())
        assertEquals(BackupKind.SCHEDULED, fake.backups.backups.values.single { it.complete }.request.kind, "it ran anyway")
    }

    @Test
    fun `Back up now answers with the run it started`() {
        val c = adoptedCloud()
        val b = backups(c)
        assertTrue(b.request(BackupKind.MANUAL))
        assertEquals(BackupKind.MANUAL, b.status.value.running?.kind)
        awaitComplete(b)
    }

    @Test
    fun `a backup asked for while the scheduled one waits for a quiet show runs at once, and ends the wait`() {
        val c = adoptedCloud()
        val b = backups(c, busy = { true }, sleep = { delay(20) })
        b.update(enabled = true)
        val scheduled = thread { runBlocking { b.runScheduled() } }
        runBlocking { withTimeout(10.seconds) { while (b.status.value.running?.phase != BackupService.PHASE_WAITING) delay(10) } }

        assertTrue(b.request(BackupKind.MANUAL))
        assertEquals(BackupKind.MANUAL, b.status.value.running?.kind)
        scheduled.join(10_000)
        assertFalse(scheduled.isAlive, "the scheduled wait ended")
        awaitComplete(b)
        assertEquals(listOf(BackupKind.MANUAL), fake.backups.backups.values.map { it.request.kind })
    }

    @Test
    fun `a settings change during a scheduled backup does not cancel it`() {
        val c = adoptedCloud()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sleeps = AtomicInteger()
        val b = backups(
            c,
            sleep = { d -> if (sleeps.getAndIncrement() == 0) now.set(now.get().plus(d)) else awaitCancellation() },
            sources = { entered.countDown(); release.await(); sources() },
        )
        b.update(enabled = true)
        b.start()
        assertTrue(entered.await(30, TimeUnit.SECONDS), "the scheduled backup started")
        b.update(dailyAt = "05:00")
        release.countDown()
        val status = awaitComplete(b)
        assertNull(status.lastCode, status.lastMessage)
        assertEquals(BackupKind.SCHEDULED, fake.backups.backups.values.single().request.kind)
    }

    @Test
    fun `a scheduled backup does nothing with the switch off`() {
        val c = adoptedCloud()
        val b = backups(c)
        runBlocking { b.runScheduled() }
        assertTrue(fake.backups.backups.isEmpty())
        assertNull(b.status.value.lastOkAt)
    }

    /** Wait for a completed upload and the run to end; answers the status then. */
    private fun awaitComplete(b: BackupService): BackupStatusDto = runBlocking {
        withTimeout(30.seconds) {
            while (fake.backups.backups.values.none { it.complete } || b.status.value.running != null) delay(20)
        }
        b.status.value
    }

    @Test
    fun `a scheduled backup runs once the show goes quiet`() {
        val clock = AtomicReference(Instant.parse("2026-11-16T04:00:00Z"))
        val checks = AtomicInteger()
        val quiet = runBlocking {
            BackupSchedule.awaitQuiet(busy = { checks.incrementAndGet() <= 3 }, clock = { clock.get() }, sleep = { clock.set(clock.get().plus(it)) })
        }
        assertTrue(quiet)
        assertEquals(Instant.parse("2026-11-16T04:30:00Z"), clock.get())
    }

    @Test
    fun `the next scheduled time is the next occurrence of the daily time`() {
        val zone = ZoneId.of("Europe/London")
        assertEquals(Instant.parse("2026-11-16T04:00:00Z"), BackupSchedule.nextDue(Instant.parse("2026-11-16T02:00:00Z"), "04:00", zone))
        assertEquals(Instant.parse("2026-11-17T04:00:00Z"), BackupSchedule.nextDue(Instant.parse("2026-11-16T04:00:00Z"), "04:00", zone))
        assertEquals(Instant.parse("2026-07-01T03:00:00Z"), BackupSchedule.nextDue(Instant.parse("2026-06-30T12:00:00Z"), "04:00", zone), "local time, summer time included")
        assertFalse(BackupSchedule.isValidTime("4:00"))
        assertFalse(BackupSchedule.isValidTime("24:00"))
        assertTrue(BackupSchedule.isValidTime("23:59"))
    }

    @Test
    fun `the settings are kept in the database and survive a restart`() {
        val c = adoptedCloud()
        backups(c).update(enabled = true, dailyAt = "02:15", nightly = false, onQuit = true)
        val again = BackupService(state.database, InstallBackup(dir.resolve("staging")), ::sources, { null }, { null }, { null })
        try {
            assertTrue(again.enabled)
            assertEquals("02:15", again.status.value.dailyAt)
            assertFalse(again.status.value.nightly)
            assertTrue(again.status.value.onQuit)
        } finally {
            again.close()
        }
    }

    @Test
    fun `a desk backs up nightly by default and on quit only when asked`() {
        val c = adoptedCloud()
        val b = backups(c)
        assertTrue(b.status.value.nightly)
        assertFalse(b.status.value.onQuit)
        assertNull(b.startQuitBackup(), "nothing on quit with the switch off")
        b.update(enabled = true)
        assertNull(b.startQuitBackup(), "nothing on quit until asked")
    }

    @Test
    fun `with nightly off a scheduled backup does nothing and none is due`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false)
        b.start()
        runBlocking { b.runScheduled() }
        assertNull(b.status.value.nextAt)
        assertTrue(fake.backups.backups.isEmpty())
    }

    @Test
    fun `a quit backup makes a fresh archive and is listed as QUIT`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        fake.backups.failParts.set(4)
        runBlocking { b.runOnce(BackupKind.MANUAL) }
        val oldId = fake.backups.backups.keys.single()

        val job = b.startQuitBackup()
        assertNotNull(job)
        assertEquals(BackupKind.QUIT, b.status.value.running?.kind, "claimed before it answers")
        runBlocking { withTimeout(30.seconds) { job.join() } }
        val status = b.status.value
        assertNull(status.lastCode, status.lastMessage)
        assertEquals(BackupKind.QUIT, status.lastKind)
        assertNotEquals(oldId, status.lastBackupId, "not the archive staged before")
        assertFalse(fake.backups.backups.getValue(oldId).complete)
        assertEquals(emptyList(), staged())
    }

    @Test
    fun `a quit backup waits for one already making, then makes its own`() {
        val c = adoptedCloud()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicInteger()
        val b = backups(c, sources = { if (first.getAndIncrement() == 0) { entered.countDown(); release.await() }; sources() })
        b.update(enabled = true, onQuit = true)
        assertTrue(b.request(BackupKind.MANUAL))
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        val job = b.startQuitBackup()
        assertNotNull(job)
        assertEquals(BackupKind.MANUAL, b.status.value.running?.kind)
        release.countDown()
        runBlocking { withTimeout(30.seconds) { job.join() } }
        assertEquals(setOf(BackupKind.MANUAL, BackupKind.QUIT), fake.backups.backups.values.map { it.request.kind }.toSet())
        assertEquals(2, fake.backups.backups.size)
        assertTrue(fake.backups.backups.values.all { it.complete })
        assertEquals(BackupKind.QUIT, b.status.value.lastKind)
        assertNull(b.status.value.running)
    }

    @Test
    fun `a start finishes a backup left staged while the switch is on`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        fake.backups.failParts.set(4)
        val failed = runBlocking { b.runOnce(BackupKind.QUIT) }
        assertEquals("UNAVAILABLE", failed.lastCode)
        b.close()

        val again = backups(c)
        again.start()
        val status = awaitComplete(again)
        assertNull(status.lastCode, status.lastMessage)
        assertEquals(BackupKind.QUIT, status.lastKind)
        assertEquals(1, fake.backups.backups.size, "the staged upload was finished, not made again")
        assertEquals(emptyList(), staged())
    }

    @Test
    fun `a start with on quit off leaves a staged backup of another kind for the next run`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = false)
        fake.backups.failParts.set(4)
        runBlocking { b.runOnce(BackupKind.MANUAL) }
        b.close()

        val again = backups(c)
        again.start()
        runBlocking { delay(500) }
        assertNull(again.status.value.running)
        assertFalse(fake.backups.backups.values.single().complete)
        assertTrue(staged().any { it.endsWith(".l7bk") }, "still staged")
    }

    @Test
    fun `a start with on quit on leaves a staged backup of another kind for the next run`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        fake.backups.failParts.set(4)
        runBlocking { b.runOnce(BackupKind.MANUAL) }
        b.close()

        val again = backups(c)
        again.start()
        runBlocking { delay(500) }
        assertNull(again.status.value.running)
        assertFalse(fake.backups.backups.values.single().complete)
        assertTrue(staged().any { it.endsWith(".l7bk") }, "still staged")
    }

    @Test
    fun `turning on quit off forgets a pending quit backup`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        fake.backups.failKey = true
        val job = assertNotNull(b.startQuitBackup())
        runBlocking { withTimeout(30.seconds) { job.join() } }
        fake.backups.failKey = false
        fake.backups.failParts.set(4)
        runBlocking { b.runOnce(BackupKind.MANUAL) }
        b.update(onQuit = false)
        b.close()

        val off = backups(c)
        off.start()
        runBlocking { delay(500) }
        assertNull(off.status.value.running)
        off.update(onQuit = true)
        off.close()

        val on = backups(c)
        on.start()
        runBlocking { delay(500) }
        assertNull(on.status.value.running)
        assertEquals(BackupKind.MANUAL, fake.backups.backups.values.single().request.kind)
        assertFalse(fake.backups.backups.values.single().complete)
        assertTrue(staged().any { it.endsWith(".l7bk") }, "still staged")
    }

    @Test
    fun `a start past the resume window makes the quit's backup afresh`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        fake.backups.failParts.set(4)
        runBlocking { b.runOnce(BackupKind.QUIT) }
        val oldId = fake.backups.backups.keys.single()
        b.close()

        now.set(now.get().plus(Duration.ofHours(21)))
        val again = backups(c)
        again.start()
        val status = awaitComplete(again)
        assertNull(status.lastCode, status.lastMessage)
        assertEquals(BackupKind.QUIT, status.lastKind)
        assertNotEquals(oldId, status.lastBackupId)
        assertEquals(BackupKind.QUIT, fake.backups.backups.getValue(status.lastBackupId!!).request.kind)
        assertEquals(emptyList(), staged())
    }

    @Test
    fun `a quit that could not reach the cloud is backed up at the next start`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        fake.backups.failKey = true
        val job = assertNotNull(b.startQuitBackup())
        runBlocking { withTimeout(30.seconds) { job.join() } }
        assertNotNull(b.status.value.lastCode)
        assertEquals(emptyList(), staged(), "nothing was staged")
        b.close()

        fake.backups.failKey = false
        val again = backups(c)
        again.start()
        val status = awaitComplete(again)
        assertNull(status.lastCode, status.lastMessage)
        assertEquals(BackupKind.QUIT, status.lastKind)
        assertEquals(BackupKind.QUIT, fake.backups.backups.values.single().request.kind)
    }

    @Test
    fun `a quit whose backup was made leaves nothing for the next start`() {
        val c = adoptedCloud()
        val b = backups(c)
        b.update(enabled = true, nightly = false, onQuit = true)
        val job = assertNotNull(b.startQuitBackup())
        runBlocking { withTimeout(30.seconds) { job.join() } }
        assertNull(b.status.value.lastCode, b.status.value.lastMessage)
        b.close()

        val again = backups(c)
        again.start()
        runBlocking { delay(500) }
        assertNull(again.status.value.running)
        assertEquals(1, fake.backups.backups.size, "no second backup at start")
    }
}
