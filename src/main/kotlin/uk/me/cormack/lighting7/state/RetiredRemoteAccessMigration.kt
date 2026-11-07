package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.process.PidFile
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("RetiredRemoteAccessMigration")

/** `PRAGMA user_version` once [RetiredRemoteAccessMigration] has run, the step after [OWN_TEMPO_USER_VERSION]. */
internal const val RETIRED_REMOTE_ACCESS_USER_VERSION = 13

/**
 * Database step 13 (cloud-p1 plan D18): the ngrok tunnel and the desk's own OAuth server are gone, so
 * their state goes too. Drops `mcp_oauth_clients` and `mcp_oauth_grants`, every Claude connector
 * added straight to the desk with them, and `remote_access_settings`' `enabled` and `domain`, leaving
 * the scripts, effects and commands switches where `cloud/RemotePolicy.kt` reads them.
 *
 * Runs after step 12. A database with neither table holding a row is only converted and marked; one
 * that holds some is backed up first to `<db>.pre-retired-remote-access-<utc>.db`. Never twice: below
 * [RETIRED_REMOTE_ACCESS_USER_VERSION] is the only way in. What lives outside the database — the ngrok
 * authtoken and the downloaded agents — goes in [retireLeftovers], which `State` runs once the
 * credential store exists, when this step met a database a desk with the tunnel wrote ([Outcome.legacy]).
 */
internal object RetiredRemoteAccessMigration {
    /** The credential the ngrok authtoken was kept under. */
    const val NGROK_AUTHTOKEN_KEY = "ngrok:authtoken"

    /**
     * What the step did: the backup taken, if any, how many clients and grants it dropped, and whether
     * it found any of the old shape at all, which a database created since never has.
     */
    data class Outcome(val backup: Path?, val clients: Int, val grants: Int, val legacy: Boolean)

    private val TABLES = listOf("mcp_oauth_grants", "mcp_oauth_clients")
    private val COLUMNS = listOf("enabled", "domain")

    /**
     * Run the step if the database is below [RETIRED_REMOTE_ACCESS_USER_VERSION]. [dbPath] is the
     * database file, the backup's directory and stem; null skips the backup. Answers null when the
     * database was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val found = transaction(database) {
            val version = userVersion()
            if (version >= RETIRED_REMOTE_ACCESS_USER_VERSION) return@transaction null
            check(version >= OWN_TEMPO_USER_VERSION) { "Retired remote access: step 12 has not run (user_version $version)" }
            Found(
                tables = TABLES.filter { tableExists(it) },
                columns = if (tableExists("remote_access_settings")) COLUMNS.filter { it in columnsOf("remote_access_settings") } else emptyList(),
                clients = rowCount("mcp_oauth_clients"),
                grants = rowCount("mcp_oauth_grants"),
            )
        } ?: return null
        val backup = if (found.clients + found.grants > 0) {
            dbPath?.let { backUpDatabase(dataSource, it, "pre-retired-remote-access", now) }
        } else {
            null
        }
        transaction(database) {
            found.tables.forEach { exec("DROP TABLE $it") }
            found.columns.forEach { exec("ALTER TABLE remote_access_settings DROP COLUMN \"$it\"") }
            currentDialectMetadata.resetCaches()
            exec("PRAGMA user_version = $RETIRED_REMOTE_ACCESS_USER_VERSION", explicitStatementType = StatementType.OTHER)
        }
        val legacy = found.tables.isNotEmpty() || found.columns.isNotEmpty()
        if (legacy) {
            logger.info(
                "Retired remote access: {} MCP OAuth clients and {} grants dropped with the tunnel's settings; backup {}",
                found.clients, found.grants, backup ?: "skipped",
            )
        }
        return Outcome(backup, found.clients, found.grants, legacy)
    }

    /**
     * The step's half outside the database: an agent a killed desk left running stopped, the ngrok
     * authtoken out of [credentialStore], and the agents the desk downloaded under [ngrokDir]. Each is
     * logged and left if it cannot go, since none is read again and none may stop the desk starting —
     * the store's opening included, which is why it is asked for here.
     */
    fun retireLeftovers(credentialStore: () -> CredentialStore, ngrokDir: Path) {
        runCatching { sweepOrphanAgent(ngrokDir) }
            .onFailure { logger.warn("Retired remote access: couldn't check for a left-over ngrok agent", it) }
        runCatching {
            val store = credentialStore()
            if (store.containsBlob(NGROK_AUTHTOKEN_KEY)) {
                store.deleteBlob(NGROK_AUTHTOKEN_KEY)
                logger.info("Retired remote access: deleted the ngrok authtoken")
            }
        }.onFailure { logger.warn("Retired remote access: couldn't delete the ngrok authtoken", it) }
        if (Files.exists(ngrokDir)) {
            runCatching { check(ngrokDir.toFile().deleteRecursively()) { "some files remain" } }
                .onSuccess { logger.info("Retired remote access: deleted {}", ngrokDir) }
                .onFailure { logger.warn("Retired remote access: couldn't delete {}", ngrokDir, it) }
        }
    }

    /**
     * Stop an agent the old tunnel's PID file names, as the tunnel's own start did: only while that PID
     * still runs the binary the file records, which the desk itself launched (the downloaded one or
     * `mcp.tunnel.ngrokPath`'s). Before the directory goes, since the file is the only way to find one,
     * and one still running would go on forwarding the internet to the public listener.
     */
    private fun sweepOrphanAgent(ngrokDir: Path) {
        val file = ngrokDir.resolve(NGROK_PID_FILE)
        if (!Files.isRegularFile(file)) return
        val binary = Files.readAllLines(file).getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: return
        PidFile(file, "an ngrok agent").sweepOrphan(binary)
    }

    private const val NGROK_PID_FILE = "ngrok.pid"

    private data class Found(val tables: List<String>, val columns: List<String>, val clients: Int, val grants: Int)

    private fun JdbcTransaction.rowCount(table: String): Int {
        if (!tableExists(table)) return 0
        var count = 0
        exec("SELECT COUNT(*) FROM $table") { rs -> if (rs.next()) count = rs.getInt(1) }
        return count
    }
}
