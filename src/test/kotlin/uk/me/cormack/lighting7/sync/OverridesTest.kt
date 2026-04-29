package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverridesTest {

    private lateinit var state: State
    private var projectId: Int = -1

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        projectId = transaction(state.database) {
            DaoProject.new {
                name = "OverridesTest"
                description = null
                isCurrent = false
            }.id.value
        }
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    @Test
    fun `setUniverseAddress writes a row that resolveUniverseAddress reads back`() {
        val universeUuid = UUID.randomUUID()
        transaction(state.database) {
            Overrides.setUniverseAddress(projectId, universeUuid, "10.0.0.1")
        }
        val read = transaction(state.database) {
            Overrides.resolveUniverseAddress(projectId, universeUuid)
        }
        assertEquals("10.0.0.1", read)
    }

    @Test
    fun `setUniverseAddress upserts existing row`() {
        val universeUuid = UUID.randomUUID()
        transaction(state.database) {
            Overrides.setUniverseAddress(projectId, universeUuid, "10.0.0.1")
            Overrides.setUniverseAddress(projectId, universeUuid, "10.0.0.2")
        }
        val (read, count) = transaction(state.database) {
            val read = Overrides.resolveUniverseAddress(projectId, universeUuid)
            val count = DaoMachineOverride.find { DaoMachineOverrides.project eq projectId }.count()
            read to count
        }
        assertEquals("10.0.0.2", read, "second write must replace, not duplicate")
        assertEquals(1L, count)
    }

    @Test
    fun `setUniverseAddress with null deletes the row`() {
        val universeUuid = UUID.randomUUID()
        transaction(state.database) {
            Overrides.setUniverseAddress(projectId, universeUuid, "10.0.0.1")
            Overrides.setUniverseAddress(projectId, universeUuid, null)
        }
        transaction(state.database) {
            assertNull(Overrides.resolveUniverseAddress(projectId, universeUuid))
            assertEquals(0L, DaoMachineOverride.find { DaoMachineOverrides.project eq projectId }.count())
        }
    }

    @Test
    fun `resolveUniverseAddress returns null when no override exists`() {
        val read = transaction(state.database) {
            Overrides.resolveUniverseAddress(projectId, UUID.randomUUID())
        }
        assertNull(read)
    }

    @Test
    fun `string round-trips preserve quotes, slashes, and IPv6 brackets`() {
        val universeUuid = UUID.randomUUID()
        val tricky = "[fe80::1%en0]:6454 \"weird\\path\""
        transaction(state.database) {
            Overrides.setUniverseAddress(projectId, universeUuid, tricky)
        }
        val read = transaction(state.database) {
            Overrides.resolveUniverseAddress(projectId, universeUuid)
        }
        assertEquals(tricky, read)
    }

    @Test
    fun `listForProject returns one entry per override`() {
        val u1 = UUID.randomUUID()
        val u2 = UUID.randomUUID()
        transaction(state.database) {
            Overrides.setUniverseAddress(projectId, u1, "10.0.0.1")
            Overrides.setUniverseAddress(projectId, u2, "10.0.0.2")
        }
        val list = transaction(state.database) { Overrides.listForProject(projectId) }
        assertEquals(2, list.size)
        assertTrue(list.all { it.tableName == "universeConfigs" && it.fieldName == "address" })
    }
}
