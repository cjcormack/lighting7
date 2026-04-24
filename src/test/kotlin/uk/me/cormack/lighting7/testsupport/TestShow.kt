package uk.me.cormack.lighting7.testsupport

import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.state.State

/**
 * Seed the minimum DB state for an integration test: one current project + one MOCK
 * universe config for universe 0.
 *
 * Pre-seeding MOCK is load-bearing: the first `POST /patches` reuses the existing
 * config, so [uk.me.cormack.lighting7.show.DbFixtureLoader] instantiates
 * [uk.me.cormack.lighting7.dmx.MockDmxController] instead of
 * [uk.me.cormack.lighting7.dmx.ArtNetController] (which would bind UDP + spawn
 * GlobalScope coroutines in its constructor).
 */
fun seedMinimalProject(
    state: State,
    projectName: String = DEFAULT_TEST_PROJECT_NAME,
    universe: Int = 0,
): Int = transaction(state.database) {
    val project = DaoProject.new {
        name = projectName
        description = "integration test"
        isCurrent = true
    }
    DaoUniverseConfig.new {
        this.project = project
        subnet = 0
        this.universe = universe
        controllerType = "MOCK"
        address = null
    }
    project.id.value
}
