package uk.me.cormack.lighting7.testsupport

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-test SQLite database used by route-level integration tests. Each call to
 * [reset] rotates [path] to a freshly-created empty SQLite file in a temp
 * directory, so the next [State][uk.me.cormack.lighting7.state.State] re-runs
 * `createMissingTablesAndColumns` against a clean database.
 */
object IntegrationTestDb {

    private val testDir: Path by lazy {
        Files.createTempDirectory("lighting7-test-").also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { dir.toFile().deleteRecursively() }
            })
        }
    }

    private val counter = AtomicLong(0)

    @Volatile
    private var currentPath: Path = freshPath()

    private fun freshPath(): Path = testDir.resolve("lighting7-${counter.incrementAndGet()}.db")

    /** Absolute path of the SQLite file the next [testAppConfig] will point at. */
    val path: String get() = currentPath.toString()

    /** Drop the previous DB file and rotate [path] to a fresh one. */
    fun reset() {
        runCatching { Files.deleteIfExists(currentPath) }
        currentPath = freshPath()
    }
}
