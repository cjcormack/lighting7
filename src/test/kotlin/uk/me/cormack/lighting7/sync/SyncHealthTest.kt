package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.cloud.DeskHealth
import uk.me.cormack.lighting7.cloud.protocol.Timestamps
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Sync's health across projects, for the heartbeat (cloud-p3 plan D2). */
class SyncHealthTest {

    private var now = Instant.parse("2026-11-16T10:00:00Z")
    private val health = SyncHealth { now }

    private fun later(seconds: Long) {
        now = now.plusSeconds(seconds)
    }

    private fun failed(projectId: Int, code: String) = health.logged(projectId, SyncLogEvent.RUN_FAILED, "$code: it broke", now)

    private fun done(projectId: Int) = health.logged(projectId, SyncLogEvent.RUN_DONE, "PUSHED: ok", now)

    @Test
    fun `one project failing and another good reads failing since the first failure`() {
        val first = now
        failed(1, "AUTH_FAILED")
        later(60)
        done(2)
        later(60)
        failed(1, "NETWORK")

        val summary = health.state.value
        assertEquals(now, summary.lastRunAt)
        assertEquals(first.plusSeconds(60), summary.lastOkAt)
        assertEquals(SyncHealth.Failure(first, "NETWORK"), summary.failing)
        assertEquals(Timestamps.format(first), DeskHealth.syncOf(summary)?.failingSince)

        later(60)
        health.ran(1)
        assertNull(health.state.value.failing, "cleared by the failing project's next good run, a quiet one too")
        assertEquals(now, health.state.value.lastOkAt)
    }

    @Test
    fun `the oldest of two failing projects is reported, and a deleted one forgotten for good`() {
        val first = now
        failed(1, "AUTH_FAILED")
        later(60)
        failed(2, "NETWORK")
        assertEquals(SyncHealth.Failure(first, "AUTH_FAILED"), health.state.value.failing)
        health.deleted(1)
        assertEquals(SyncHealth.Failure(first.plusSeconds(60), "NETWORK"), health.state.value.failing)
        later(60)
        failed(1, "NETWORK")
        health.ran(1)
        assertEquals(SyncHealth.Failure(first.plusSeconds(60), "NETWORK"), health.state.value.failing, "a run still in flight for a deleted project")
    }

    @Test
    fun `conflicts fail until applied or aborted, and a disconnect clears a failure`() {
        health.logged(1, SyncLogEvent.CONFLICTS_PENDING, "2 conflict(s) need resolution (session #1).", now)
        assertEquals(SyncOutcome.CONFLICTS_PENDING.name, health.state.value.failing?.code)
        later(60)
        health.logged(1, SyncLogEvent.SESSION_ABORTED, "Conflict session #1 aborted; working tree reset.", now)
        assertNull(health.state.value.failing, "an abort ends the failure")
        assertEquals(now.minusSeconds(60), health.state.value.lastRunAt, "an abort is no run")
        assertNull(health.state.value.lastOkAt, "an abort is no good run")

        failed(2, "AUTH_FAILED")
        health.logged(2, SyncLogEvent.REPO_DISCONNECTED, "Disconnected from x", now)
        assertNull(health.state.value.failing)
    }

    @Test
    fun `a refusal that says the project is not set up to sync is no run`() {
        for (code in listOf(SyncErrorCode.REPO_URL_MISSING, SyncErrorCode.SYNC_DISABLED, SyncErrorCode.SYNC_OFF, SyncErrorCode.NO_REPO)) {
            failed(1, code.name)
        }
        assertNull(DeskHealth.syncOf(health.state.value))
    }

    @Test
    fun `nothing synced reports no sync at all`() {
        assertNull(DeskHealth.syncOf(health.state.value))
    }

    @Test
    fun `a failure's code is read from its log row`() {
        assertEquals("AUTH_FAILED", SyncHealth.codeOf("AUTH_FAILED: the token was refused"))
        assertEquals(SyncHealth.UNKNOWN_CODE, SyncHealth.codeOf("Unexpected error: boom"))
    }
}

/** [SyncHealth] seeded from the activity log and kept by the engine. */
class SyncHealthSeedTest {

    private lateinit var state: State

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    private fun project(name: String): Int = transaction(state.database) { DaoProject.new { this.name = name; description = null; isCurrent = false; inProduction = false }.id.value }

    @Test
    fun `seeding reads each project's latest run from the log`() {
        val good = project("good")
        val failing = project("failing")
        val conflicted = project("conflicted")
        listOf(good, failing, conflicted).forEach { attach(it) }
        state.syncLogger.error(failing, SyncLogEvent.RUN_FAILED, "AUTH_FAILED: refused")
        state.syncLogger.info(good, SyncLogEvent.RUN_DONE, "PUSHED: ok")
        state.syncLogger.error(failing, SyncLogEvent.RUN_FAILED, "NETWORK: down")
        state.syncLogger.warn(conflicted, SyncLogEvent.CONFLICTS_PENDING, "2 conflict(s) need resolution (session #1).")
        state.syncLogger.info(conflicted, SyncLogEvent.APPLY_DONE, "MERGED: resolved")

        val seeded = seeded()
        assertEquals("NETWORK", seeded.failing?.code)
        val firstFailure = state.syncLogger.list(failing).minBy { it.id }.ts
        assertEquals(firstFailure, seeded.failing?.since?.toIsoUtc())
    }

    private fun attach(projectId: Int, lastSyncedAt: Instant? = null) = transaction(state.database) {
        DaoSyncConfigs.insert {
            it[project] = projectId
            it[repoUrl] = "https://cloud.example/git/r$projectId.git"
            it[this.lastSyncedAt] = lastSyncedAt
        }
    }

    private fun seeded(): SyncHealth.Summary {
        val database = state.database
        return SyncHealth().apply { seed(database) }.state.value
    }

    @Test
    fun `a project not set up to sync is not failing`() {
        val id = project("unattached")
        val (projectUuid, installUuid, friendly) = transaction(state.database) {
            val install = DaoInstall.all().first()
            Triple(DaoProject.findById(id)!!.uuid, install.uuid, install.friendlyName)
        }
        assertFailsWith<SyncException> {
            runBlocking { state.remoteSyncEngine.runSync(id, projectUuid, installUuid, friendly) }
        }
        assertNull(state.syncHealth.state.value.failing)
        assertNull(seeded().failing)
    }

    @Test
    fun `a logged failure is failing live and after a restart alike`() {
        val id = project("broken")
        attach(id)
        state.syncLogger.error(id, SyncLogEvent.RUN_FAILED, "AUTH_FAILED: refused")
        assertEquals("AUTH_FAILED", state.syncHealth.state.value.failing?.code)
        assertEquals(state.syncHealth.state.value, seeded())
    }

    @Test
    fun `an abort clears a conflicted project live and seeded`() {
        val id = project("conflicted")
        attach(id)
        state.syncLogger.warn(id, SyncLogEvent.CONFLICTS_PENDING, "1 conflict(s) need resolution (session #1).")
        assertEquals(SyncOutcome.CONFLICTS_PENDING.name, state.syncHealth.state.value.failing?.code)
        state.syncLogger.info(id, SyncLogEvent.SESSION_ABORTED, "Conflict session #1 aborted; working tree reset.")
        assertNull(state.syncHealth.state.value.failing)
        assertNull(seeded().failing)
    }

    @Test
    fun `a disconnected project's failure does not come back after a restart`() {
        val id = project("disconnected")
        attach(id)
        state.syncLogger.error(id, SyncLogEvent.RUN_FAILED, "NETWORK: down")
        transaction(state.database) {
            DaoSyncConfigs.update({ DaoSyncConfigs.project eq id }) { it[repoUrl] = null }
        }
        state.syncLogger.info(id, SyncLogEvent.REPO_DISCONNECTED, "Disconnected")
        assertNull(state.syncHealth.state.value.failing)
        assertNull(seeded().failing, "the disconnect's row clears it")

        transaction(state.database) {
            DaoSyncConfigs.update({ DaoSyncConfigs.project eq id }) { it[repoUrl] = "https://cloud.example/git/again.git" }
        }
        assertNull(seeded().failing, "and reconnecting brings nothing back")
    }

    @Test
    fun `a quiet good run after a failure clears it after a restart too`() {
        val id = project("recovered")
        attach(id)
        state.syncLogger.error(id, SyncLogEvent.RUN_FAILED, "NETWORK: down")
        val quietRun = Instant.now().plusSeconds(5)
        transaction(state.database) {
            DaoSyncConfigs.update({ DaoSyncConfigs.project eq id }) { it[lastSyncedAt] = quietRun }
        }
        val summary = seeded()
        assertNull(summary.failing)
        assertEquals(quietRun.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), summary.lastOkAt)
        assertEquals(summary.lastOkAt, summary.lastRunAt)
    }

    @Test
    fun `an unexpected error is logged and failing with the generic code`() {
        val id = project("crashing")
        attach(id)
        state.syncLogger.error(id, SyncLogEvent.RUN_FAILED, "${SyncHealth.UNKNOWN_CODE}: boom")
        assertEquals(SyncHealth.UNKNOWN_CODE, state.syncHealth.state.value.failing?.code)
    }
}
