package uk.me.cormack.lighting7.midi

import org.jetbrains.exposed.sql.Database

/**
 * Shared test-only [Database] instance for tests that exercise only the in-memory paths
 * of persistence services (e.g. the cache / resolver in [ControlSurfaceBindingService]).
 *
 * No actual JDBC connection is ever opened: Exposed defers connection acquisition until
 * a `transaction {}` runs. Tests must avoid calling code paths that hit the DB.
 */
internal object FakeDatabase {
    val instance: Database by lazy {
        Database.connect(
            url = "jdbc:postgresql://fake-database-for-unit-tests/doesnotmatter",
            driver = "org.postgresql.Driver",
            user = "fake",
            password = "fake",
        )
    }
}
