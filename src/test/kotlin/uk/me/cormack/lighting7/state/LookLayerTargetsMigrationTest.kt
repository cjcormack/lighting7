package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-shot step that clears the targets of every Look layer (absolute-looks plan P9, step 3),
 * run the way a desk meets it: inside `State`'s `initDatabase`, over a database whose `PRAGMA
 * user_version` says it has not run yet.
 */
class LookLayerTargetsMigrationTest {

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

    /** Every stored cue layer as (cue name, its targets' JSON), in id order. */
    private fun storedLayers(): List<Pair<String, String>> =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT c.name, l.targets FROM cue_layers l LEFT JOIN cues c ON c.id = l.cue_id ORDER BY l.id").use { rs ->
                    buildList { while (rs.next()) add((rs.getString(1) ?: "?") to rs.getString(2)) }
                }
            }
        }

    private val house = listOf(CueTargetDto("fixture", "house-1"), CueTargetDto("fixture", "house-2"))

    /**
     * A desk on [IntegrationTestDb] holding Sleeping Beauty's Pre-show: a layer of the Set Look
     * naming the house lights, and a palette layer naming one of them, which keeps its targets.
     */
    private fun seedShow() {
        val desk = startDesk()
        val projectId = seedMinimalProject(desk, projectName = "Sleeping Beauty")
        transaction(desk.database) {
            val project = DaoProject[projectId]
            val set = DaoLook.new {
                this.project = project
                name = "Act 1, Scene 1 — Set"
            }
            val amber = DaoPalette.new {
                this.project = project
                name = "Amber"
            }
            val preShow = DaoCue.new {
                this.project = project
                name = "Pre-show"
            }
            DaoCueLayer.new {
                cue = preShow
                look = set
                targets = house
            }
            DaoCueLayer.new {
                cue = preShow
                palette = amber
                targets = house.take(1)
                sortOrder = 1
            }
        }
        stopDesk()
    }

    @Test
    fun `a fresh database is already past the step`() {
        startDesk()
        stopDesk()
        assertTrue(userVersion() >= LOOK_LAYER_TARGETS_CLEARED_USER_VERSION, "user_version ${userVersion()}")
    }

    @Test
    fun `a Look layer's targets are cleared and logged by its cue, and a palette layer's are kept`() {
        seedShow()
        sql("PRAGMA user_version = $DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION")

        val (_, logs) = captureLogs("LookLayerTargetsMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals(
            listOf("Pre-show" to "[]", "Pre-show" to """[{"type":"fixture","key":"house-1"}]"""),
            storedLayers(),
        )
        assertTrue(userVersion() >= LOOK_LAYER_TARGETS_CLEARED_USER_VERSION, "user_version ${userVersion()}")
        assertEquals(1, logs.size, "one line per cue: $logs")
        val line = logs.single()
        assertTrue("'Pre-show' in 'Sleeping Beauty'" in line && "Act 1, Scene 1 — Set" in line && "house-2" in line, line)
    }

    @Test
    fun `a Look layer whose cue has gone is cleared and logged too`() {
        seedShow()
        val goneId = transaction(startDesk().database) {
            DaoCue.find { DaoCues.name eq "Pre-show" }.single().id.value
        }
        stopDesk()
        // Foreign keys are off, so the cue's row can go and leave its layers behind.
        sql("DELETE FROM cues WHERE id = $goneId")
        sql("PRAGMA user_version = $DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION")

        val (_, logs) = captureLogs("LookLayerTargetsMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals("[]", storedLayers().first().second)
        assertTrue(logs.any { "#$goneId, deleted" in it }, "$logs")
    }

    @Test
    fun `it runs once, so a second start changes nothing`() {
        seedShow()
        sql("PRAGMA user_version = $DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION")
        startDesk()
        stopDesk()
        // A targeted Look layer arriving afterwards (straight into the table) is not this step's.
        sql("""UPDATE cue_layers SET targets = '[{"type":"fixture","key":"house-1"}]' WHERE look_id IS NOT NULL""")

        val (_, logs) = captureLogs("LookLayerTargetsMigration") {
            startDesk()
            stopDesk()
        }

        assertEquals("""[{"type":"fixture","key":"house-1"}]""", storedLayers().first().second)
        assertTrue(userVersion() >= LOOK_LAYER_TARGETS_CLEARED_USER_VERSION, "user_version ${userVersion()}")
        assertEquals(emptyList(), logs)
    }
}
