package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CueEntryUuidMigration")

/**
 * `PRAGMA user_version` once [keyCueEntriesByCue] has run. The database's own header counter, which
 * nothing else on this desk sets: this is its first use, and a later one-shot step takes the next
 * number.
 */
internal const val CUE_ENTRIES_KEYED_USER_VERSION = 1

/**
 * Give every cue's one stack entry **its cue's uuid**, once per database (cue model plan session 3).
 *
 * Sync `formatVersion` 32 makes each entry its own record, `cueStackEntries/{uuid}.json`, and an
 * entry's identity across desks is its uuid. The entries a desk already holds were minted at random:
 * by the startup migration (`CueModelMigration.kt`, session 1), and by every v31 import and pull
 * since, which made a fresh entry per cue each time. Two desks holding one show would each write
 * their own uuid for the same place in a stack, and the first sync after the bump would merge both —
 * every cue twice in its stack.
 *
 * So the place a cue had before entries were records is named after the cue, on every desk: here for
 * the entries already in the database, and in `ProjectImporter` for a v5–v31 archive read from now
 * on. A marker entry already carries the uuid of the MARKER cue it was, the same everywhere, and is
 * left alone; so is any entry of a cue that has several (none exist before the plan's session 4).
 *
 * The same step sets every entry's ordinal to its 0-based position in its stack
 * ([renumberOrdinalsAsPositions]), the other half of writing the same file for the same place.
 *
 * Runs once, after the schema exists, inside `State.initDatabase`'s transaction: `PRAGMA
 * user_version` below [CUE_ENTRIES_KEYED_USER_VERSION] means it has not. It must never run again —
 * from session 4 a cue's remaining entry after a *Remove from stack* is not the place it had before
 * the bump, and renaming it would resurrect the uuid the removed entry's tombstone retired.
 */
internal fun JdbcTransaction.keyCueEntriesByCue() {
    var version = 0
    exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
    if (version >= CUE_ENTRIES_KEYED_USER_VERSION) return

    var keyed = 0
    exec(
        """
        SELECT count(*) AS n FROM cue_stack_entries e JOIN cues c ON c.id = e.cue_id
        WHERE e.kind = 'CUE' AND e.uuid IS NOT c.uuid
          AND e.cue_id IN (SELECT cue_id FROM cue_stack_entries WHERE cue_id IS NOT NULL GROUP BY cue_id HAVING count(*) = 1)
        """.trimIndent(),
    ) { rs -> if (rs.next()) keyed = rs.getInt("n") }
    if (keyed > 0) {
        // The uuid is copied column to column, so its blob is never decoded and re-encoded here.
        exec(
            """
            UPDATE cue_stack_entries
               SET uuid = (SELECT c.uuid FROM cues c WHERE c.id = cue_stack_entries.cue_id)
             WHERE kind = 'CUE'
               AND cue_id IN (SELECT cue_id FROM cue_stack_entries WHERE cue_id IS NOT NULL GROUP BY cue_id HAVING count(*) = 1)
            """.trimIndent(),
        )
        logger.info("Keyed {} cue stack entr{} by their cues' uuids (sync formatVersion 32)", keyed, if (keyed == 1) "y" else "ies")
    }
    renumberOrdinalsAsPositions()
    exec("PRAGMA user_version = $CUE_ENTRIES_KEYED_USER_VERSION", explicitStatementType = StatementType.OTHER)
}

/**
 * Give every entry its 0-based position in its stack as its ordinal — what a v31 archive's
 * `sortOrder` was (the exporter wrote each cue's index in its list) and so what the pre-v32 reader
 * makes of it. The startup migration renormalised a desk's own rows to `1.0, 2.0, …`, while a desk
 * that has pulled since holds the archive's `0.0, 1.0, …`; without this the two would write
 * different bytes for the same `cueStackEntries/` file and the first v32 sync would conflict on
 * every entry.
 *
 * The order is the one this desk played until now — ordinal, then **id** — so nothing on stage
 * moves. Ordering by uuid, as the desk does from here on, would swap any two entries an older
 * archive left on one ordinal (agreed 2026-10-16). Two desks that played such a tie differently
 * then write different positions for the same files, which their first sync settles as an ordinary
 * change; every stack the migration renormalised has no ties to play differently.
 */
private fun JdbcTransaction.renumberOrdinalsAsPositions() {
    data class Row(val id: Int, val stackId: Int, val ordinal: Double)
    val rows = ArrayList<Row>()
    exec("SELECT id, stack_id, ordinal FROM cue_stack_entries") { rs ->
        while (rs.next()) rows += Row(rs.getInt("id"), rs.getInt("stack_id"), rs.getDouble("ordinal"))
    }
    var moved = 0
    rows.groupBy { it.stackId }.values.forEach { stack ->
        stack.sortedWith(compareBy({ it.ordinal }, { it.id })).forEachIndexed { position, row ->
            if (row.ordinal != position.toDouble()) {
                exec("UPDATE cue_stack_entries SET ordinal = ${position.toDouble()} WHERE id = ${row.id}")
                moved++
            }
        }
    }
    if (moved > 0) logger.info("Set {} cue stack entr{} ordinal to its position (sync formatVersion 32)", moved, if (moved == 1) "y's" else "ies'")
}
