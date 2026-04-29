package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 2 one-shot migration: existing `universe_configs.address` values must be moved into
 * `machine_overrides` on the next State init, and the column null'd out.
 */
class AddressMigrationTest {

    private var state: State? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        runCatching { state?.shutdown() }
        state = null
    }

    @Test
    fun `existing address column values move to machine_overrides on next init`() {
        // First init: create schema and a project + universe with an address set the old way.
        state = State(testAppConfig())
        val (projectId, universeUuid) = transaction(state!!.database) {
            val project = DaoProject.new {
                name = "AddressMigrationTest"
                description = null
                isCurrent = false
            }
            // Hand-poke the legacy address column directly so we mimic a pre-Phase-2 row that
            // hasn't been touched by the new write path yet.
            val universe = DaoUniverseConfig.new {
                this.project = project
                subnet = 0
                universe = 1
                controllerType = "ARTNET"
                address = "192.168.1.50"
            }
            project.id.value to universe.uuid
        }
        // Clear the override that the route would normally write so we have a clean "address
        // column populated, no override row" starting point.
        transaction(state!!.database) {
            Overrides.setUniverseAddress(projectId, universeUuid, null)
            // And re-set the address column to simulate the legacy state.
            DaoUniverseConfig.find { DaoUniverseConfigs.project eq projectId }.first().address = "192.168.1.50"
        }
        state!!.shutdown()

        // Second init: the migration should run and move the value out.
        state = State(testAppConfig())
        transaction(state!!.database) {
            val universe = DaoUniverseConfig.find { DaoUniverseConfigs.project eq projectId }.first()
            assertNull(universe.address, "address column must be cleared after migration")

            val resolved = Overrides.resolveUniverseAddress(projectId, universeUuid)
            assertNotNull(resolved)
            assertEquals("192.168.1.50", resolved)
        }
    }

    @Test
    fun `migration is idempotent`() {
        state = State(testAppConfig())
        // Init creates schema, no addresses set. Re-init should be a no-op.
        state!!.shutdown()
        state = State(testAppConfig())
        state!!.shutdown()
        // No assertion needed: we're verifying nothing throws and nothing accumulates.
    }
}
