package uk.me.cormack.lighting7.testsupport

import io.ktor.server.config.MapApplicationConfig

/** Default project name used by [seedMinimalProject]. */
const val DEFAULT_TEST_PROJECT_NAME = "TestProject"

fun testAppConfig(): MapApplicationConfig =
    MapApplicationConfig(
        "database.path" to IntegrationTestDb.path,
    )
