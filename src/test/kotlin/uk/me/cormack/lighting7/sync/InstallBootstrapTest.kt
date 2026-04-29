package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 2 bootstrap: the install singleton is created on first DB init and is idempotent across
 * subsequent reopens of the same DB file.
 */
class InstallBootstrapTest {

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
    fun `first init creates exactly one install row`() {
        state = State(testAppConfig())
        val (count, friendlyName) = transaction(state!!.database) {
            DaoInstall.all().count() to DaoInstall.all().first().friendlyName
        }
        assertEquals(1, count)
        assertTrue(friendlyName.isNotBlank(), "friendlyName must default to a non-blank value")
    }

    @Test
    fun `second init is idempotent — same uuid, no duplicate row`() {
        state = State(testAppConfig())
        val firstUuid = transaction(state!!.database) {
            DaoInstall.all().first().uuid
        }
        state!!.shutdown()

        state = State(testAppConfig())
        val (count, secondUuid) = transaction(state!!.database) {
            DaoInstall.all().count() to DaoInstall.all().first().uuid
        }
        assertEquals(1, count, "second init must not create another install row")
        assertEquals(firstUuid, secondUuid, "install uuid must be stable across restarts")
        assertNotNull(firstUuid)
    }
}
