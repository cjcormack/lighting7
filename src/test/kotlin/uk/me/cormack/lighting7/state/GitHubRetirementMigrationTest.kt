package uk.me.cormack.lighting7.state

import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.sync.auth.CredentialStoreFactory
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.captureLogs
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Database step 18 (cloud-p2 plan D8), run the way a desk meets it — inside `State`'s `initDatabase` —
 * over `src/test/resources/db/pre-github-retirement.sql`: a database at step 17 holding the install-wide
 * GitHub identity, a show moved to the cloud and a venue still on GitHub, beside the OAuth tokens and
 * the PATs in the credential store.
 */
class GitHubRetirementMigrationTest {

    private var state: State? = null

    private val showGitHub = "https://github.com/old-desk-owner/sleeping-beauty.git"
    private val venueGitHub = "https://github.com/old-desk-owner/hall-venue.git"
    private val unrelated = "cloud:install-key:somewhere"

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
    }

    @After
    fun tearDown() {
        state?.let { runCatching { it.shutdown() } }
        fileStore().deleteBlob(unrelated)
    }

    private fun loadOldDatabase(after: (java.sql.Statement) -> Unit = {}) {
        val sql = javaClass.getResourceAsStream("/db/pre-github-retirement.sql")!!.readBytes().toString(Charsets.UTF_8)
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

    // The file store lists and deletes entries without decrypting them, so the install it is keyed to does not matter.
    private fun fileStore() = CredentialStoreFactory.create("file", appDataDir().resolve("credentials.enc"), "pre-p2-install")

    /** What a desk with GitHub sync kept in its credential store: the OAuth tokens and a PAT per repo. */
    private fun leaveCredentialsBehind() {
        fileStore().apply {
            setBlob(GitHubRetirementMigration.OAUTH_KEY, "{\"accessToken\":\"ghu_old\"}")
            setBlob(GitHubRetirementMigration.patKey(showGitHub), "ghp_show")
            setBlob(GitHubRetirementMigration.patKey(venueGitHub), "ghp_venue")
            setBlob(unrelated, "kept")
        }
    }

    private fun startDesk(): State = State(testAppConfig()).also { state = it }

    private fun <T> sql(block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${IntegrationTestDb.path}").use { connection -> connection.createStatement().use(block) }

    private fun userVersion(): Int = sql { s -> s.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) } }

    private fun tables(): Set<String> = sql { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun backups(): List<Path> {
        val db = Path.of(IntegrationTestDb.path)
        return Files.list(db.parent).use { files -> files.filter { it.name.startsWith("${db.name}.pre-github-retirement-") }.toList() }
    }

    private fun repoUrls(): List<String> = sql { s ->
        s.executeQuery("SELECT repo_url FROM sync_configs ORDER BY project_id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    @Test
    fun `the identity table goes, backed up first, and every GitHub credential with it`() {
        loadOldDatabase()
        leaveCredentialsBehind()
        assertEquals(SURFACE_JOBS_USER_VERSION, userVersion())
        assertTrue("oauth_identities" in tables())

        val (desk, logs) = captureLogs("GitHubRetirementMigration") { startDesk() }

        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertFalse("oauth_identities" in tables())
        val backup = backups().single()
        DriverManager.getConnection("jdbc:sqlite:$backup").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT github_login FROM oauth_identities").use { rs -> rs.next(); assertEquals("old-desk-owner", rs.getString(1)) }
            }
        }
        val store = desk.credentialStore
        assertFalse(store.containsBlob(GitHubRetirementMigration.OAUTH_KEY))
        assertFalse(store.containsBlob(GitHubRetirementMigration.patKey(showGitHub)), "a moved project's old repo's PAT goes")
        assertFalse(store.containsBlob(GitHubRetirementMigration.patKey(venueGitHub)), "a straggler's PAT goes")
        assertTrue(store.containsBlob(unrelated), "nothing else in the store is touched")
        assertEquals(
            listOf("https://lighting7.net/git/org_01ABC/repo_01SHOW.git", venueGitHub),
            repoUrls(),
            "every project keeps its repo: the straggler is answered GITHUB_RETIRED and moves from its working tree",
        )
        assertTrue(logs.any { "1 GitHub identity dropped" in it }, logs.joinToString("\n"))
        assertTrue(logs.any { "deleted 3 GitHub credentials" in it }, logs.joinToString("\n"))
    }

    @Test
    fun `a database holding no identity loses the table unbacked, and still its credentials`() {
        loadOldDatabase { s -> s.execute("DELETE FROM oauth_identities") }
        leaveCredentialsBehind()

        val desk = startDesk()

        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertFalse("oauth_identities" in tables())
        assertTrue(backups().isEmpty())
        assertFalse(desk.credentialStore.containsBlob(GitHubRetirementMigration.OAUTH_KEY))
        assertFalse(desk.credentialStore.containsBlob(GitHubRetirementMigration.patKey(venueGitHub)))
        assertTrue(desk.credentialStore.containsBlob(unrelated))
    }

    @Test
    fun `a fresh database is only marked, and the step never runs twice`() {
        startDesk().shutdown()
        state = null
        assertEquals(GITHUB_RETIREMENT_USER_VERSION, userVersion())
        assertFalse("oauth_identities" in tables())

        // A start at 18 changes nothing, even with a table of the old name put back.
        sql { it.execute("CREATE TABLE oauth_identities (id INTEGER PRIMARY KEY)") }
        startDesk()
        assertTrue("oauth_identities" in tables())
        assertTrue(backups().isEmpty())
    }
}
