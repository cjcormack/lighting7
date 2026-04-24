package uk.me.cormack.lighting7.testsupport

import io.ktor.server.config.MapApplicationConfig

/**
 * Default project name used by [testAppConfig] and [seedMinimalProject]. Must match so
 * [uk.me.cormack.lighting7.state.ProjectManager.initialize]'s config-name fallback
 * finds the seeded row.
 */
const val DEFAULT_TEST_PROJECT_NAME = "TestProject"

fun testAppConfig(projectName: String = DEFAULT_TEST_PROJECT_NAME): MapApplicationConfig =
    MapApplicationConfig(
        "postgres.url" to EmbeddedTestPostgres.jdbcUrl,
        "postgres.username" to EmbeddedTestPostgres.username,
        "postgres.password" to EmbeddedTestPostgres.password,
        "lighting.projectName" to projectName,
    )
