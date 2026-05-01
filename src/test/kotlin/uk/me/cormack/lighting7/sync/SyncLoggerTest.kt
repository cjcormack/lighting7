package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoSyncLogEntries
import uk.me.cormack.lighting7.models.DaoSyncLogEntry
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncLoggerTest {

    private lateinit var state: State
    private lateinit var logger: SyncLogger

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        logger = SyncLogger(state)
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    @Test
    fun `write persists entry and list returns it`() {
        val projectId = seedMinimalProject(state)

        logger.info(projectId, SyncLogEvent.RUN_DONE, "Pushed 2 commits")

        val entries = logger.list(projectId)
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("INFO", e.level)
        assertEquals(SyncLogEvent.RUN_DONE, e.event)
        assertEquals("Pushed 2 commits", e.message)
        assertTrue(e.tsMs > 0)
    }

    @Test
    fun `list returns newest-first and respects beforeId cursor`() {
        val projectId = seedMinimalProject(state)
        repeat(5) { i -> logger.info(projectId, "TICK", "tick $i") }

        val firstPage = logger.list(projectId, limit = 2)
        assertEquals(listOf("tick 4", "tick 3"), firstPage.map { it.message })

        val secondPage = logger.list(projectId, limit = 2, beforeId = firstPage.last().id)
        assertEquals(listOf("tick 2", "tick 1"), secondPage.map { it.message })
    }

    @Test
    fun `prunes oldest entries beyond MAX_ENTRIES_PER_PROJECT`() {
        val projectId = seedMinimalProject(state)
        val cap = SyncLogger.MAX_ENTRIES_PER_PROJECT
        // Write cap+5 entries; oldest 5 should be pruned.
        repeat(cap + 5) { i -> logger.info(projectId, "BULK", "row $i") }

        val total = transaction(state.database) {
            DaoSyncLogEntry.find { DaoSyncLogEntries.project eq projectId }.count()
        }
        assertEquals(cap.toLong(), total)
        // Oldest surviving row should be `row 5` (rows 0..4 pruned).
        val oldest = logger.list(projectId, limit = cap).last().message
        assertEquals("row 5", oldest)
    }

    @Test
    fun `entries from a different project are isolated`() {
        val projectAId = seedMinimalProject(state, projectName = "Alpha")
        val projectBId = transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoProject.new {
                name = "Beta"
                description = "second"
                isCurrent = false
            }.id.value
        }

        logger.info(projectAId, "EV", "alpha")
        logger.info(projectBId, "EV", "beta")

        assertEquals(listOf("alpha"), logger.list(projectAId).map { it.message })
        assertEquals(listOf("beta"), logger.list(projectBId).map { it.message })
    }

    @Test
    fun `write into nonexistent project is silently ignored`() {
        // No DaoProject for this id — write should noop, not throw.
        logger.info(projectId = 99_999, event = "EV", message = "ignored")
        // The list call still returns empty.
        val rows = transaction(state.database) {
            DaoSyncLogEntry.find { DaoSyncLogEntries.project eq 99_999 }.toList()
        }
        assertEquals(emptyList(), rows)
        // And the helper was a no-op.
        assertNull(rows.firstOrNull())
    }
}
