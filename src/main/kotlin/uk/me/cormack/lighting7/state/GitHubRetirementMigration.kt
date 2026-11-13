package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("GitHubRetirementMigration")

/** `PRAGMA user_version` once [GitHubRetirementMigration] has run, the step after [SURFACE_JOBS_USER_VERSION]. */
internal const val GITHUB_RETIREMENT_USER_VERSION = 18

/**
 * Database step 18 (cloud-p2 plan D8): the desk does not sync with GitHub, so `oauth_identities`,
 * the install-wide GitHub identity's mirror, is dropped.
 *
 * Runs after step 17. A database without the table is only marked. One with it has it dropped, unbacked
 * when it holds no identity, else after a backup to `<db>.pre-github-retirement-<utc>.db`. Never twice: below
 * [GITHUB_RETIREMENT_USER_VERSION] is the only way in. The credentials themselves — the OAuth tokens
 * and every PAT — live in the credential store, and go in [retireCredentials], which `State` runs once
 * the store exists, when this step met a database a desk with GitHub sync wrote ([Outcome.legacy]).
 */
internal object GitHubRetirementMigration {
    /** The credential the GitHub OAuth identity's tokens were kept under. */
    const val OAUTH_KEY = "oauth:github:default"

    /** The credential a repo's Personal Access Token was kept under. */
    fun patKey(repoUrl: String): String = "pat:$repoUrl"

    /**
     * What the step did: the backup taken, if any, how many identities it dropped, every repo a project
     * on the desk synced or once synced with, and whether it found the old table at all, which a
     * database created since never has.
     */
    data class Outcome(val backup: Path?, val identities: Int, val repoUrls: List<String>, val legacy: Boolean)

    private const val TABLE = "oauth_identities"

    /**
     * Run the step if the database is below [GITHUB_RETIREMENT_USER_VERSION]. [dbPath] is the database
     * file, the backup's directory and stem; null skips the backup. Answers null when the database was
     * already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val found = transaction(database) {
            val version = userVersion()
            if (version >= GITHUB_RETIREMENT_USER_VERSION) return@transaction null
            check(version >= SURFACE_JOBS_USER_VERSION) { "GitHub retirement: step 17 has not run (user_version $version)" }
            val table = tableExists(TABLE)
            Found(
                table = table,
                identities = rowCount(TABLE),
                repoUrls = (repoUrlsOf("sync_configs") + repoUrlsOf("sync_linked_repos")).distinct(),
            )
        } ?: return null
        val backup = if (found.identities > 0) dbPath?.let { backUpDatabase(dataSource, it, "pre-github-retirement", now) } else null
        transaction(database) {
            if (found.table) exec("DROP TABLE $TABLE")
            currentDialectMetadata.resetCaches()
            exec("PRAGMA user_version = $GITHUB_RETIREMENT_USER_VERSION", explicitStatementType = StatementType.OTHER)
        }
        if (found.table) {
            logger.info("GitHub retirement: {} GitHub identity dropped; backup {}", found.identities, backup ?: "skipped")
        }
        return Outcome(backup, found.identities, found.repoUrls, legacy = found.table)
    }

    /**
     * The step's half outside the database: the OAuth tokens and the PAT of each of [repoUrls] out of
     * [credentialStore]. The store cannot list its keys, so a PAT is found by the repo it was kept for.
     * Logged and left if it cannot go, since none is read again and none may stop the desk starting —
     * the store's opening included, which is why it is asked for here.
     */
    fun retireCredentials(credentialStore: () -> CredentialStore, repoUrls: List<String>) {
        runCatching {
            val store = credentialStore()
            val keys = (listOf(OAUTH_KEY) + repoUrls.map(::patKey)).filter { store.containsBlob(it) }
            keys.forEach { store.deleteBlob(it) }
            if (keys.isNotEmpty()) logger.info("GitHub retirement: deleted {} GitHub credentials", keys.size)
        }.onFailure { logger.warn("GitHub retirement: couldn't delete the GitHub credentials", it) }
    }

    private data class Found(val table: Boolean, val identities: Int, val repoUrls: List<String>)

    private fun JdbcTransaction.repoUrlsOf(table: String): List<String> {
        if (!tableExists(table)) return emptyList()
        val urls = mutableListOf<String>()
        exec("SELECT repo_url FROM $table WHERE repo_url IS NOT NULL") { rs -> while (rs.next()) urls += rs.getString(1) }
        return urls
    }
}
