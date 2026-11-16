package uk.me.cormack.lighting7.backup

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.protocol.CloudJson
import uk.me.cormack.lighting7.models.CloudUnlinkReason
import uk.me.cormack.lighting7.models.clearCloudLinks
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.BACKUP_STAMP
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.name

/** One column's value as a saved row holds it: SQLite's storage class and its text. */
@Serializable
data class SavedValue(val kind: String, val text: String? = null)

/** This machine's own rows, read before the database is replaced and written back over the backup's. */
@Serializable
data class SavedRows(val installs: List<Map<String, SavedValue>>, val cloudConnection: List<Map<String, SavedValue>>)

/** One file move the apply made, so a failure can undo it. */
@Serializable
data class RestoreMove(val from: String, val to: String, val kind: String)

/** What the apply has done so far, written after every step so a start after a crash can undo it. */
@Serializable
data class RestoreJournal(val stamp: String, val keepLinks: Boolean, val saved: SavedRows, val moves: List<RestoreMove>)

/**
 * The restore applied at startup, before anything opens the database (cloud-p3 plan D10). [begin] saves
 * this machine's `installs` and `cloud_connection` rows, keeps the database and the three trees beside
 * themselves as `*.pre-restore-<utc>` and moves the backup's into their places; the caller then runs the
 * migrations over the restored database as over any, and [Applied.writeBack] puts this machine's rows
 * back. A failure at any step is [Applied.rollback]: the kept files go back and the desk starts on the
 * database it had. Each move is journalled before it is made, and a journal is deleted only once every
 * move it names is undone, so a crash or a failed undo is retried at the next start.
 */
class RestoreApply(
    private val staging: RestoreStaging,
    private val databasePath: Path,
    /** The three trees a backup holds, by their archive names. */
    private val trees: Map<String, Path>,
    private val clock: () -> Instant = ::nowUtc,
) {
    private val logger = LoggerFactory.getLogger(RestoreApply::class.java)
    private val journalPath: Path get() = staging.root.resolve("applying.json")

    /** A journal is still on disk: the kept trees may be the only copies, so none may be swept. */
    val undoPending: Boolean get() = journalPath.exists()

    /**
     * Swap the backup in, if one is ready; null when none is, or when the swap failed and was undone.
     * A journal a crashed apply, or a failed undo, left is undone first.
     */
    fun begin(): Applied? {
        if (journalPath.exists()) {
            logger.warn("A restore was interrupted while it was applied; putting this desk back as it was")
            val journal = readJournal()
            if (journal == null) {
                logger.error("The interrupted restore's journal can't be read; its kept files are left beside their places")
                return null
            }
            if (undo(journal)) failed("The restore was interrupted while it was applied, so this desk started as it was.")
            return null
        }
        val marker = staging.readyMarker() ?: return null
        val stamp = BACKUP_STAMP.format(clock())
        val saved = if (databasePath.exists()) readMachineRows(databasePath) else SavedRows(emptyList(), emptyList())
        var journal = RestoreJournal(stamp, marker.keepLinks, saved, emptyList())
        writeJournal(journal)
        fun move(from: Path, to: Path, kind: String) {
            journal = journal.copy(moves = journal.moves + RestoreMove(from.toString(), to.toString(), kind))
            writeJournal(journal)
            moveTree(from, to)
        }
        try {
            val keptDb = databasePath.resolveSibling("${databasePath.name}.pre-restore-$stamp.db")
            if (databasePath.exists()) move(databasePath, keptDb, KEPT)
            val journalFile = databasePath.resolveSibling("${databasePath.name}-journal")
            if (journalFile.exists()) move(journalFile, databasePath.resolveSibling("${keptDb.name}-journal"), KEPT)
            Files.createDirectories(databasePath.parent)
            move(staging.ready.resolve(InstallBackup.DATABASE_ENTRY), databasePath, PLACED)
            for ((name, dir) in trees) {
                if (dir.exists()) move(dir, dir.resolveSibling("${dir.name}$KEPT_SUFFIX$stamp"), KEPT)
                val from = staging.ready.resolve(name)
                if (from.exists()) {
                    Files.createDirectories(dir.parent)
                    move(from, dir, PLACED)
                } else {
                    journal = journal.copy(moves = journal.moves + RestoreMove(from.toString(), dir.toString(), PLACED))
                    writeJournal(journal)
                    Files.createDirectories(dir)
                }
            }
        } catch (e: Exception) {
            logger.error("Couldn't put the restored backup in place; this desk starts as it was", e)
            if (undo(journal)) failed("The backup couldn't be put in place, so this desk started as it was: ${e.message ?: e::class.simpleName}")
            return null
        }
        logger.info("Restoring backup {} of {}", marker.backupId, marker.manifest.friendlyName)
        return Applied(journal, marker)
    }

    inner class Applied internal constructor(private val journal: RestoreJournal, val marker: RestoreMarker) {
        /**
         * Over the migrated database: this machine's `installs` row; its `cloud_connection` row with the
         * backup's remote, MCP and approval switches and no adopting user; no sessions; and, for a backup
         * alone, no cloud links or invites, since they named the old install's identity (D9, D10).
         */
        fun writeBack(database: Database) {
            transaction(database) {
                val jdbc = connection.connection as Connection
                val backupInstall = readRows(jdbc, "installs").firstOrNull()
                val backupCloud = readRows(jdbc, "cloud_connection").firstOrNull()
                if (journal.saved.installs.isNotEmpty()) {
                    jdbc.createStatement().use { it.executeUpdate("DELETE FROM installs") }
                    journal.saved.installs.forEach { row ->
                        val values = row.toMutableMap()
                        backupInstall?.get("onboarding_pending")?.let { values["onboarding_pending"] = it }
                        insert(jdbc, "installs", values)
                    }
                }
                jdbc.createStatement().use { it.executeUpdate("DELETE FROM cloud_connection") }
                journal.saved.cloudConnection.forEach { row ->
                    val values = row.toMutableMap()
                    values["adopted_by_user_id"] = SavedValue(NULL)
                    for (switch in SWITCHES) values[switch] = backupCloud?.get(switch) ?: SavedValue(INTEGER, "0")
                    for (column in RESTORE_COLUMNS) values[column] = SavedValue(NULL)
                    insert(jdbc, "cloud_connection", values)
                }
                jdbc.createStatement().use { it.executeUpdate("DELETE FROM user_sessions") }
                if (!journal.keepLinks) clearCloudLinks(clock(), CloudUnlinkReason.RESTORED)
            }
        }

        /** The restore stands: staging goes, and the kept trees stay until the next start sweeps them. */
        fun commit() {
            Files.deleteIfExists(journalPath)
            runCatching { staging.clear() }.onFailure { logger.warn("Couldn't clear the restore's staging", it) }
            staging.clearFailure()
            logger.info("Restored backup {}", marker.backupId)
        }

        /** Undo every move and forget the restore; the caller opens the database it had. */
        fun rollback(cause: Throwable) {
            if (undo(journal)) failed("Restoring the backup failed, so this desk started as it was: ${cause.message ?: cause::class.simpleName}")
        }
    }

    /** At a start that applied nothing and left no journal: the trees an earlier restore kept beside their places. */
    fun sweepKept() {
        if (undoPending) return
        for (dir in trees.values) {
            val parent = dir.parent ?: continue
            if (!parent.exists()) continue
            Files.list(parent).use { files ->
                files.filter { it.name.startsWith("${dir.name}$KEPT_SUFFIX") && Files.isDirectory(it) }.toList()
            }.forEach { kept ->
                runCatching { kept.toFile().deleteRecursively() }.onFailure { logger.warn("Couldn't remove {}", kept, it) }
            }
        }
    }

    /**
     * Every move put back, newest first; a move journalled but never made is left as it is. Each move
     * leaves the journal once it is undone, so a retry never undoes one twice. True when all of them are,
     * and the journal and staging are gone; false leaves the rest in the journal for the next start.
     */
    private fun undo(journal: RestoreJournal): Boolean {
        val remaining = journal.moves.toMutableList()
        for (move in journal.moves.reversed()) {
            val from = Paths.get(move.from)
            val to = Paths.get(move.to)
            runCatching {
                when (move.kind) {
                    PLACED -> if (to.exists()) {
                        to.toFile().deleteRecursively()
                        check(!to.exists()) { "$to is still there" }
                    }
                    KEPT -> when {
                        to.exists() && !from.exists() -> moveTree(to, from)
                        to.exists() -> error("$from is in the way of putting $to back")
                    }
                }
                remaining.remove(move)
                writeJournal(journal.copy(moves = remaining.toList()))
            }.onFailure { logger.error("Couldn't undo the restore's move of {} to {}", from, to, it) }
        }
        if (remaining.isNotEmpty()) return false
        Files.deleteIfExists(journalPath)
        runCatching { staging.clear() }.onFailure { logger.warn("Couldn't clear the restore's staging", it) }
        return true
    }

    private fun failed(message: String) {
        runCatching { staging.recordFailure(RestoreFailure(clock().toIsoUtc(), message)) }
            .onFailure { logger.warn("Couldn't record the restore's failure", it) }
    }

    private fun readJournal(): RestoreJournal? =
        runCatching { CloudJson.decodeFromString(RestoreJournal.serializer(), Files.readString(journalPath)) }.getOrNull()

    private fun writeJournal(journal: RestoreJournal) {
        staging.prepare()
        val temp = staging.root.resolve("applying.json.tmp")
        Files.writeString(temp, CloudJson.encodeToString(RestoreJournal.serializer(), journal))
        RestoreStaging.ownerOnly(temp)
        Files.move(temp, journalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val KEPT_SUFFIX = ".pre-restore-"
        private const val KEPT = "KEPT"
        private const val PLACED = "PLACED"

        private const val NULL = "null"
        private const val INTEGER = "integer"
        private const val REAL = "real"
        private const val TEXT = "text"
        private const val BLOB = "blob"

        private val SWITCHES = listOf("remote_access", "mcp_access", "approve_sessions")
        private val RESTORE_COLUMNS = listOf("restore_replaces_install_id", "restore_replaces_name", "restore_backup_id")

        /** Read on a connection of its own, before the pool opens the file. */
        internal fun readMachineRows(database: Path): SavedRows =
            DriverManager.getConnection("jdbc:sqlite:$database").use { c ->
                SavedRows(readRows(c, "installs"), readRows(c, "cloud_connection"))
            }

        private fun readRows(c: Connection, table: String): List<Map<String, SavedValue>> {
            if (columnsOf(c, table).isEmpty()) return emptyList()
            return c.createStatement().use { s ->
                s.executeQuery("SELECT * FROM $table").use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).associate { i ->
                                val value = when (rs.getObject(i)) {
                                    null -> SavedValue(NULL)
                                    is ByteArray -> SavedValue(BLOB, Base64.getEncoder().encodeToString(rs.getBytes(i)))
                                    is Int, is Long, is Short, is Boolean -> SavedValue(INTEGER, rs.getLong(i).toString())
                                    is Float, is Double -> SavedValue(REAL, rs.getDouble(i).toString())
                                    else -> SavedValue(TEXT, rs.getString(i))
                                }
                                meta.getColumnName(i) to value
                            })
                        }
                    }
                }
            }
        }

        private fun columnsOf(c: Connection, table: String): Set<String> =
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info($table)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
            }

        /** [values] into [table], only the columns the table has now. */
        private fun insert(c: Connection, table: String, values: Map<String, SavedValue>) {
            val columns = columnsOf(c, table)
            val kept = values.filterKeys { it in columns }.entries.toList()
            if (kept.isEmpty()) return
            val sql = "INSERT INTO $table (${kept.joinToString { "\"${it.key}\"" }}) VALUES (${kept.joinToString { "?" }})"
            c.prepareStatement(sql).use { ps ->
                kept.forEachIndexed { index, (_, value) ->
                    val at = index + 1
                    when (value.kind) {
                        NULL -> ps.setNull(at, Types.NULL)
                        INTEGER -> ps.setLong(at, value.text!!.toLong())
                        REAL -> ps.setDouble(at, value.text!!.toDouble())
                        BLOB -> ps.setBytes(at, Base64.getDecoder().decode(value.text))
                        else -> ps.setString(at, value.text)
                    }
                }
                ps.executeUpdate()
            }
        }

        /** [from] to [to], copying and deleting where a rename cannot cross volumes. */
        internal fun moveTree(from: Path, to: Path) {
            val renamed = try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                true
            } catch (e: DirectoryNotEmptyException) {
                false
            } catch (e: AtomicMoveNotSupportedException) {
                false
            }
            if (renamed) return
            if (Files.isDirectory(from)) {
                Files.walk(from).use { walk ->
                    walk.forEach { source -> Files.copy(source, to.resolve(from.relativize(source).toString()), StandardCopyOption.COPY_ATTRIBUTES) }
                }
                from.toFile().deleteRecursively()
            } else {
                Files.move(from, to)
            }
        }
    }
}
