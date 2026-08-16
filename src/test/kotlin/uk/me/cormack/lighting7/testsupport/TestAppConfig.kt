package uk.me.cormack.lighting7.testsupport

import io.ktor.server.config.MapApplicationConfig

/** Default project name used by [seedMinimalProject]. */
const val DEFAULT_TEST_PROJECT_NAME = "TestProject"

/**
 * Build a Ktor [MapApplicationConfig] for an integration test. `database.path` is
 * always pinned to [IntegrationTestDb.path]; [extra] lets a test add or override
 * any other knob (e.g. `sync.workingTreeRoot` to redirect the cloud-sync working
 * tree away from the real `appDataDir`).
 */
fun testAppConfig(vararg extra: Pair<String, String>): MapApplicationConfig {
    val pairs = mutableListOf("database.path" to IntegrationTestDb.path)
    // bcrypt cost 4 (~2 ms) instead of the production 12 (~250 ms): every seeded user and
    // login in the suite pays a hash/verify, and at cost 12 that adds tens of seconds.
    pairs.add("auth.bcryptCost" to "4")
    pairs.addAll(extra)
    return MapApplicationConfig(*pairs.toTypedArray())
}
