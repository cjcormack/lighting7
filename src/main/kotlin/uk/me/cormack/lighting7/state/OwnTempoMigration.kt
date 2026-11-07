package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.SECONDS_OWN_BPM
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.timingSourceByName
import uk.me.cormack.lighting7.fx.wallClockFxTypes
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("OwnTempoMigration")

/** `PRAGMA user_version` once [OwnTempoMigration] has run, the step after [CUE_NUMBER_USER_VERSION]. */
internal const val OWN_TEMPO_USER_VERSION = 12

/**
 * Database step 12 (fx-speed plan D5, P3): *Seconds* rewritten as Own tempo, exactly. Every Look, cue
 * and palette FX with `seconds` set is rewritten: a beat FX to Own tempo [SECONDS_OWN_BPM] at twice its
 * cycle, which runs exactly as Seconds did (its accumulator at twice the rate, over twice the cycle),
 * and a wall-clock FX to its flag dropped, which is Natural — its rate master dropped too, as Seconds
 * dropped it on spawn. Both lose their masters, which an FX on its own clock does not read. Then the
 * three `seconds` columns go. With no registry to ask, a wall-clock FX is told by name: a built-in, or
 * one of its project's FX definitions on the wall clock (`fx_definitions.timing_source`), each
 * compared as the registry resolves a name ([wallClockFxTypes]). One a script registers is read as a
 * beat FX, and runs at the seconds it meant all the same (`FxTimingChoice.kt`'s `storedClock`).
 *
 * Runs after step 11, once the schema has given the three tables `own_bpm`. A database with no
 * `seconds` column (a fresh one) is only marked; one whose columns hold no flag loses them and is
 * marked; one that flags some is backed up first to `<db>.pre-own-tempo-<utc>.db`, then rewritten and
 * marked in one transaction, each FX named by its owner in the log. Never twice: below
 * [OWN_TEMPO_USER_VERSION] is the only way in.
 */
internal object OwnTempoMigration {
    /** One FX the step rewrote: its home, its owner's name, its type, and whether it became Own tempo. */
    data class Rewritten(val table: String, val owner: String, val fxType: String, val ownTempo: Boolean)

    /** A flagged row: its home, id, owner's name, type and the project it is in. */
    private data class Flagged(val table: String, val id: Int, val owner: String, val fxType: String, val projectId: Int)

    /** What the step did: the backup taken, if any, and every FX it rewrote. */
    data class Outcome(val backup: Path?, val rewritten: List<Rewritten>)

    /** Each FX home, with its owner's table and the column that names it. */
    private data class Home(val table: String, val ownerTable: String, val ownerColumn: String)

    private val HOMES = listOf(
        Home("look_fx", "looks", "look_id"),
        Home("cue_ad_hoc_fx", "cues", "cue_id"),
        Home("palette_fx", "palettes", "palette_id"),
    )

    /**
     * Run the step if the database is below [OWN_TEMPO_USER_VERSION]. [dbPath] is the database file,
     * the backup's directory and stem; null skips the backup. Answers null when the database was
     * already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val flagged = transaction(database) {
            val version = userVersion()
            if (version >= OWN_TEMPO_USER_VERSION) return@transaction null
            check(version >= CUE_NUMBER_USER_VERSION) { "Own tempo: step 11 has not run (user_version $version)" }
            flaggedRows()
        } ?: return null
        if (flagged.isEmpty()) {
            transaction(database) {
                dropFlagColumns()
                mark()
            }
            return Outcome(backup = null, rewritten = emptyList())
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-own-tempo", now) }
        val rewritten = transaction(database) {
            val rows = classified(flaggedRows())
            for ((table, inHome) in rows.groupBy({ it.first.table }, { it })) {
                val (ownTempo, natural) = inHome.partition { it.second }
                fun ids(list: List<Pair<Flagged, Boolean>>) = list.joinToString(",") { it.first.id.toString() }
                if (ownTempo.isNotEmpty()) {
                    exec(
                        "UPDATE $table SET own_bpm = $SECONDS_OWN_BPM, cycle = cycle * 2, " +
                            "speed_master_uuid = NULL, rate_speed_master_uuid = NULL WHERE id IN (${ids(ownTempo)})",
                    )
                }
                if (natural.isNotEmpty()) {
                    exec("UPDATE $table SET speed_master_uuid = NULL, rate_speed_master_uuid = NULL WHERE id IN (${ids(natural)})")
                }
            }
            dropFlagColumns()
            mark()
            rows.map { (row, ownTempo) -> Rewritten(row.table, row.owner, row.fxType, ownTempo) }
        }
        for (fx in rewritten) {
            if (fx.ownTempo) {
                logger.info("Own tempo: {} '{}': {} on Seconds now runs on Own tempo {} at twice its cycle", fx.table, fx.owner, fx.fxType, SECONDS_OWN_BPM)
            } else {
                logger.info("Own tempo: {} '{}': {} on Seconds now runs on Natural", fx.table, fx.owner, fx.fxType)
            }
        }
        logger.info("Own tempo: {} FX rewritten from Seconds and the column dropped; backup {}", rewritten.size, backup ?: "skipped")
        return Outcome(backup, rewritten)
    }

    private fun JdbcTransaction.hasFlagColumn(table: String): Boolean = tableExists(table) && "seconds" in columnsOf(table)

    /** Every FX with the flag set, home by home in id order, with its owner's name and project. */
    private fun JdbcTransaction.flaggedRows(): List<Flagged> {
        val rows = mutableListOf<Flagged>()
        for (home in HOMES) {
            if (!hasFlagColumn(home.table)) continue
            exec(
                "SELECT f.id, o.name, f.fx_type, o.project_id FROM ${home.table} f " +
                    "JOIN ${home.ownerTable} o ON o.id = f.${home.ownerColumn} WHERE f.seconds <> 0 ORDER BY f.id",
            ) { rs ->
                while (rs.next()) rows += Flagged(home.table, rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4))
            }
        }
        return rows
    }

    /** [rows], each with whether it becomes Own tempo: a beat FX does, a wall-clock one becomes Natural. */
    private fun JdbcTransaction.classified(rows: List<Flagged>): List<Pair<Flagged, Boolean>> {
        val definitions = HashMap<Int, MutableList<String>>()
        if (tableExists("fx_definitions")) {
            exec("SELECT project_id, fx_id FROM fx_definitions WHERE timing_source = ${sqlText(TimingSource.WALL_CLOCK.name)}") { rs ->
                while (rs.next()) definitions.getOrPut(rs.getInt(1)) { mutableListOf() } += rs.getString(2)
            }
        }
        val wallClock = HashMap<Int, Set<String>>()
        return rows.map { row ->
            val names = wallClock.getOrPut(row.projectId) { wallClockFxTypes(definitions[row.projectId].orEmpty()) }
            row to (timingSourceByName(row.fxType, names) == TimingSource.BEAT)
        }
    }

    private fun JdbcTransaction.dropFlagColumns() {
        for (home in HOMES) {
            if (hasFlagColumn(home.table)) exec("ALTER TABLE ${home.table} DROP COLUMN seconds")
        }
        currentDialectMetadata.resetCaches()
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $OWN_TEMPO_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
