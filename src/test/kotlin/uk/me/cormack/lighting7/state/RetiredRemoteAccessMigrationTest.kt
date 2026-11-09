package uk.me.cormack.lighting7.state

import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import uk.me.cormack.lighting7.cloud.RemotePolicySettings
import uk.me.cormack.lighting7.sync.auth.CredentialStoreFactory
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Database step 13 (cloud-p1 plan D18), run the way a desk meets it — inside `State`'s
 * `initDatabase` — over `src/test/resources/db/pre-retired-remote-access.sql`: a database at step 12
 * holding MCP OAuth clients and grants and the tunnel's settings, beside the ngrok authtoken in the
 * credential store and a downloaded agent on disk.
 */
class RetiredRemoteAccessMigrationTest {

    private var state: State? = null
    private val ngrokDir: Path = appDataDir().resolve("ngrok")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
        ngrokDir.toFile().deleteRecursively()
    }

    private fun loadOldDatabase(after: (java.sql.Statement) -> Unit = {}) {
        val sql = javaClass.getResourceAsStream("/db/pre-retired-remote-access.sql")!!.readBytes().toString(Charsets.UTF_8)
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                sql.split(";\n")
                    .map { chunk -> chunk.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { statement.execute(it) }
                after(statement)
            }
            connection.commit()
        }
    }

    /** What a desk with the tunnel left outside its database: the authtoken and a downloaded agent. */
    private fun leaveTunnelBehind() {
        // The file store lists and deletes entries without decrypting them, so the install it is keyed to does not matter.
        CredentialStoreFactory.create("file", appDataDir().resolve("credentials.enc"), "pre-p1-install")
            .setBlob(RetiredRemoteAccessMigration.NGROK_AUTHTOKEN_KEY, "2abcSECRETtoken")
        Files.createDirectories(ngrokDir.resolve("3.24.0"))
        Files.writeString(ngrokDir.resolve("3.24.0").resolve("ngrok"), "#!/bin/sh\n")
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun <T> sql(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection -> connection.createStatement().use(block) }

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun tables(): Set<String> = sql { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun columns(table: String): Set<String> = sql { s ->
        s.executeQuery("PRAGMA table_info($table)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-retired-remote-access-") }.toList() }
    }

    @Test
    fun `the OAuth tables and the tunnel's settings go, the policy switches stay, backed up first`() {
        loadOldDatabase()
        leaveTunnelBehind()
        assertEquals(OWN_TEMPO_USER_VERSION, userVersion())
        assertTrue("mcp_oauth_grants" in tables())

        val (desk, logs) = captureLogs("RetiredRemoteAccessMigration") { startDesk() }

        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertFalse("mcp_oauth_clients" in tables())
        assertFalse("mcp_oauth_grants" in tables())
        assertEquals(setOf("id", "allow_scripts", "allow_effects", "allow_commands", "updated_at"), columns("remote_access_settings"))
        assertEquals(RemotePolicySettings(allowScripts = true, allowEffects = false, allowCommands = true), desk.remotePolicy.settings)

        val backup = backups().single()
        DriverManager.getConnection("jdbc:sqlite:$backup").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT COUNT(*) FROM mcp_oauth_grants").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
            }
        }
        assertFalse(desk.credentialStore.containsBlob(RetiredRemoteAccessMigration.NGROK_AUTHTOKEN_KEY))
        assertFalse(Files.exists(ngrokDir))
        assertTrue(logs.any { "2 MCP OAuth clients and 2 grants" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a database holding no clients or grants loses the tables unbacked, and still the tunnel's leftovers`() {
        loadOldDatabase { s ->
            s.execute("DELETE FROM mcp_oauth_grants")
            s.execute("DELETE FROM mcp_oauth_clients")
            s.execute("DELETE FROM remote_access_settings")
        }
        leaveTunnelBehind()

        val desk = startDesk()

        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertFalse("mcp_oauth_grants" in tables())
        assertFalse("domain" in columns("remote_access_settings"))
        assertTrue(backups().isEmpty())
        assertEquals(RemotePolicySettings(), desk.remotePolicy.settings)
        assertFalse(desk.credentialStore.containsBlob(RetiredRemoteAccessMigration.NGROK_AUTHTOKEN_KEY))
        assertFalse(Files.exists(ngrokDir))
    }

    @Test
    fun `an agent a killed desk left running is stopped, and nothing else the file names`() {
        assumeTrue(!System.getProperty("os.name").orEmpty().startsWith("Windows"))
        loadOldDatabase()
        val agentPath = ngrokDir.resolve("3.24.0").resolve("ngrok")
        Files.createDirectories(agentPath.parent)
        Files.copy(Path.of("/bin/sleep"), agentPath)
        agentPath.toFile().setExecutable(true)
        val agent = ProcessBuilder(agentPath.toString(), "60").start()
        val bystander = ProcessBuilder("sleep", "60").start()
        try {
            fun pidLine(process: Process, binary: Path) =
                "${process.pid()}\n$binary\n${process.toHandle().info().startInstant().map { it.toString() }.orElse("")}\n"
            Files.writeString(ngrokDir.resolve("ngrok.pid"), pidLine(agent, agentPath))
            startDesk()
            assertTrue(agent.waitFor(5, TimeUnit.SECONDS), "the left-over agent is stopped")
            assertFalse(Files.exists(ngrokDir))
        } finally {
            agent.destroyForcibly()
        }

        // A file naming a process that is not running the binary it records stops nothing.
        state?.shutdown()
        state = null
        IntegrationTestDb.reset()
        loadOldDatabase()
        Files.createDirectories(ngrokDir)
        Files.writeString(ngrokDir.resolve("ngrok.pid"), "${bystander.pid()}\n$agentPath\n\n")
        try {
            startDesk()
            assertTrue(bystander.isAlive, "a PID running anything else is left alone")
            assertFalse(Files.exists(ngrokDir))
        } finally {
            bystander.destroyForcibly()
        }
    }

    @Test
    fun `a fresh database is only marked, and the step never runs twice`() {
        startDesk().shutdown()
        state = null
        assertEquals(AUTO_NUMBER_TIDY_USER_VERSION, userVersion())
        assertFalse("mcp_oauth_grants" in tables())
        assertFalse("enabled" in columns("remote_access_settings"))

        // A start at 13 changes nothing, even with a table of the old name put back.
        sql { it.execute("CREATE TABLE mcp_oauth_grants (id INTEGER PRIMARY KEY)") }
        startDesk()
        assertTrue("mcp_oauth_grants" in tables())
        assertTrue(backups().isEmpty())
    }
}
