package uk.me.cormack.lighting7.sync

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * wheel-colour-chase plan P1: the importer passes Colour Cycle's `between` through as it is — a
 * `SPLIT` this desk runs, and a value it does not know, which runs as the default
 * (`TypedParams.choice`).
 */
class ColourCycleChoiceImportTest {

    private lateinit var state: State
    private lateinit var archive: Path

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        archive = Files.createTempDirectory("colour-cycle-choice-")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
        runCatching { archive.toFile().deleteRecursively() }
    }

    @Test
    fun `a Split and an unknown between import as they were stored`() {
        val projectId = seedMinimalProject(state, projectName = "Choices")
        transaction(state.database) {
            val look = DaoLook.new { project = DaoProject[projectId]; name = "Chases" }
            for ((sort, between) in listOf("SPLIT", "WOBBLE").withIndex()) {
                DaoLookFxEntity.new {
                    this.look = look; targetType = "fixture"; targetKey = "hex-1"
                    fxType = "ColourCycle"; family = "colour"; attribute = "rgbColour"
                    cycle = 4.0; blendMode = "OVERRIDE"; order = "NORMAL"
                    parameters = mapOf("colours" to "#ff0000,#0000ff", "between" to between, "fade" to "0.5")
                    sortOrder = sort
                }
            }
        }
        ProjectExporter(state).export(projectId, archive)

        state.shutdown()
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        val imported = ProjectImporter(state).import(archive, nameOverride = "Imported").projectId

        val betweens = transaction(state.database) {
            val look = DaoLook.all().single { it.project.id.value == imported && it.name == "Chases" }
            DaoLookFxEntity.all().filter { it.look.id == look.id }.sortedBy { it.sortOrder }.map { it.parameters["between"] }
        }
        assertEquals(listOf("SPLIT", "WOBBLE"), betweens)
    }
}
