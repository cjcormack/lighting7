package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

// What the schema migrations that rebuild tables from the model share (`CueModelMigration`,
// `TerminologyMigration`): the backup taken before anything moves, and the rebuild itself.

private val BACKUP_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/**
 * `VACUUM INTO '<dbPath>.<label>-<utc>.db'`, on a raw connection in autocommit, because `VACUUM`
 * refuses to run inside a transaction and Exposed always opens one. Refuses to overwrite a backup.
 */
internal fun backUpDatabase(dataSource: DataSource, dbPath: String, label: String, now: Instant): Path {
    val target = Path.of("$dbPath.$label-${BACKUP_STAMP.format(now)}.db")
    check(!Files.exists(target)) { "Migration backup $target already exists" }
    dataSource.connection.use { connection ->
        val wasAutoCommit = connection.autoCommit
        if (!wasAutoCommit) {
            connection.commit()
            connection.autoCommit = true
        }
        try {
            connection.createStatement().use { it.execute("VACUUM INTO ${sqlText(target.toString())}") }
        } finally {
            if (!wasAutoCommit) connection.autoCommit = false
        }
    }
    return target
}

/**
 * Replace [old] with the model's shape of [table]: [old] renamed aside to `<old>_<asideSuffix>`, the
 * new table created from `SchemaUtils.createStatements`, [copy] given the aside name to fill it from,
 * and the aside one dropped. `legacy_alter_table` keeps every other table's references naming the
 * real table rather than following the rename. The old table's indexes are dropped first, or they
 * would travel with it and clash by name with the new ones.
 */
internal fun JdbcTransaction.rebuildFromModel(
    old: String,
    table: Table,
    asideSuffix: String,
    copy: JdbcTransaction.(aside: String) -> Unit,
) {
    val aside = "${old}_$asideSuffix"
    val indexes = mutableListOf<String>()
    exec("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ${sqlText(old)} AND sql IS NOT NULL") { rs ->
        while (rs.next()) indexes += rs.getString("name")
    }
    indexes.forEach { exec("DROP INDEX IF EXISTS \"$it\"") }
    exec("PRAGMA legacy_alter_table = ON", explicitStatementType = StatementType.OTHER)
    try {
        exec("ALTER TABLE $old RENAME TO $aside")
    } finally {
        exec("PRAGMA legacy_alter_table = OFF", explicitStatementType = StatementType.OTHER)
    }
    createFromModel(table)
    copy(aside)
    exec("DROP TABLE $aside")
}

/**
 * Create [table] from the model. `createStatements` also sorts in every table [table] references
 * and skips each one that exists — judged by Exposed's **cached** table list, which a migration's own
 * DDL makes stale. So the cache is refreshed first, and only [table] is ever missing.
 */
internal fun JdbcTransaction.createFromModel(table: Table) {
    currentDialectMetadata.resetCaches()
    SchemaUtils.createStatements(table).forEach { exec(it) }
}

internal fun JdbcTransaction.tableExists(table: String): Boolean {
    var exists = false
    exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ${sqlText(table)}") { rs -> exists = rs.next() }
    return exists
}

internal fun JdbcTransaction.columnsOf(table: String): Set<String> {
    val columns = mutableSetOf<String>()
    exec("PRAGMA table_info($table)") { rs -> while (rs.next()) columns += rs.getString("name") }
    return columns
}

internal fun JdbcTransaction.userVersion(): Int {
    var version = 0
    exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
    return version
}

internal fun sqlText(value: String?): String =
    if (value == null) "NULL" else "'" + value.replace("'", "''") + "'"
