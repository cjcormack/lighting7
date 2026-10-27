package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-shot step that deletes Look effects naming no target of their own (absolute-looks plan
 * P9), run the way a desk meets it: inside `State`'s `initDatabase`, over a database whose
 * `PRAGMA user_version` says it has not run yet.
 */
class DeferredLookEffectsMigrationTest {

    private var state: State? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun stopDesk() {
        state?.shutdown()
        state = null
    }

    private fun sql(statement: String) =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }

    private fun userVersion(): Int =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        }

    /** Every stored Look effect as (target type, effect type), in id order. */
    private fun storedEffects(): List<Pair<String, String>> =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT target_type, fx_type FROM look_fx ORDER BY id").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                }
            }
        }

    private fun DaoLook.effect(targetType: String, targetKey: String, fxType: String) = DaoLookFxEntity.new {
        look = this@effect
        this.targetType = targetType
        this.targetKey = targetKey
        this.fxType = fxType
        family = "intensity"
        attribute = "dimmer"
        cycle = 1.0
        blendMode = "OVERRIDE"
        order = "NORMAL"
        parameters = emptyMap()
    }

    /**
     * A desk on [IntegrationTestDb] holding Experiment's *S5 Deferred FX*: one deferred effect, one bound —
     * a Pulse, which step 7 stores as Wave.
     */
    private fun seedLook() {
        val desk = startDesk()
        val projectId = seedMinimalProject(desk, projectName = "Experiment")
        transaction(desk.database) {
            val look = DaoLook.new {
                project = DaoProject[projectId]
                name = "S5 Deferred FX"
            }
            look.effect(DEFERRED_TARGET_TYPE, "", "ColourCycle")
            look.effect("fixture", "hex-1", "Pulse")
        }
        stopDesk()
    }

    @Test
    fun `a fresh database is already past the step`() {
        startDesk()
        stopDesk()
        assertTrue(userVersion() >= DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION, "user_version ${userVersion()}")
    }

    @Test
    fun `a deferred effect is deleted and logged by its Look, and a bound one is kept`() {
        seedLook()
        sql("PRAGMA user_version = $CUE_ENTRIES_KEYED_USER_VERSION")

        val (_, logs) = captureLogs("DeferredLookEffectsMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(listOf("fixture" to "Wave"), storedEffects())
        assertTrue(userVersion() >= DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION, "user_version ${userVersion()}")
        assertEquals(1, logs.size, "one line per Look: $logs")
        assertTrue("'S5 Deferred FX' in 'Experiment'" in logs.single() && "ColourCycle" in logs.single(), logs.single())
    }

    @Test
    fun `a deferred effect whose Look has gone is deleted too`() {
        seedLook()
        val desk = startDesk()
        val goneId = transaction(desk.database) {
            DaoLook.new {
                project = DaoLook.find { DaoLooks.name eq "S5 Deferred FX" }.single().project
                name = "Gone"
            }.also { it.effect(DEFERRED_TARGET_TYPE, "", "Strobe") }.id.value
        }
        stopDesk()
        // Foreign keys are off, so the Look's row can go and leave its effect behind.
        sql("DELETE FROM looks WHERE id = $goneId")
        sql("PRAGMA user_version = $CUE_ENTRIES_KEYED_USER_VERSION")

        val (_, logs) = captureLogs("DeferredLookEffectsMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(listOf("fixture" to "Wave"), storedEffects())
        assertTrue(logs.any { "#$goneId, deleted" in it && "Strobe" in it }, "$logs")
    }

    @Test
    fun `it runs once, so a second start changes nothing`() {
        seedLook()
        sql("PRAGMA user_version = $CUE_ENTRIES_KEYED_USER_VERSION")
        startDesk()
        stopDesk()

        // A deferred row arriving afterwards is not this step's: it never runs again.
        val desk = startDesk()
        transaction(desk.database) { DaoLook.all().single().effect(DEFERRED_TARGET_TYPE, "", "Breathe") }
        stopDesk()

        val (_, logs) = captureLogs("DeferredLookEffectsMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(listOf("fixture" to "Wave", DEFERRED_TARGET_TYPE to "Breathe"), storedEffects())
        assertTrue(userVersion() >= DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION, "user_version ${userVersion()}")
        assertEquals(emptyList(), logs)
    }
}
