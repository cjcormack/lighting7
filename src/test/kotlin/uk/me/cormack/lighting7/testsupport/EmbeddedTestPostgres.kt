package uk.me.cormack.lighting7.testsupport

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.DriverManager

/**
 * Shared embedded Postgres for route-level integration tests.
 *
 * First access starts one [EmbeddedPostgres] — downloads the binary on first run,
 * extracts it under `java.io.tmpdir`, launches a child process. A JVM shutdown hook
 * stops it. Each test class calls [resetSchema] in `@Before` to drop and recreate the
 * `public` schema; `State.initDatabase` then re-runs migrations from scratch.
 *
 * Picked over Testcontainers because Testcontainers 1.21 hardcodes Docker API 1.32,
 * which Docker Engine 25+ and OrbStack both reject. Embedded Postgres runs anywhere a
 * JDK runs — no daemon.
 */
object EmbeddedTestPostgres {

    private val postgres: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder()
            .setCleanDataDirectory(true)
            .start()
            .also { pg ->
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { pg.close() } })
            }
    }

    val jdbcUrl: String get() = postgres.getJdbcUrl("postgres", "postgres")
    val username: String get() = "postgres"
    val password: String get() = "postgres"

    fun resetSchema() {
        postgres // force start
        DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP SCHEMA IF EXISTS public CASCADE")
                st.execute("CREATE SCHEMA public")
            }
        }
    }

    /** Self-test. Kept so tests can skip gracefully if the binary can't start. */
    fun isAvailable(): Boolean = runCatching { postgres; true }.getOrDefault(false)
}
