package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE

private val logger = LoggerFactory.getLogger("DeferredLookEffectsMigration")

/** `PRAGMA user_version` once [dropDeferredLookEffects] has run, the step after [CUE_ENTRIES_KEYED_USER_VERSION]. */
internal const val DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION = 2

/**
 * Delete every Look effect that names no target of its own, once per database (absolute-looks plan
 * D2, P9). Such an effect ran on whatever applied its Look; a Look effect now always names a
 * fixture, a group or a selection, the write boundary refuses one that does not, and the importer
 * drops one from any archive. What this database still holds is deleted here, each Look named in
 * the log.
 *
 * Runs once, after [keyCueEntriesByCue], inside `State.initDatabase`'s transaction: `PRAGMA
 * user_version` below [DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION] means it has not.
 */
internal fun JdbcTransaction.dropDeferredLookEffects() {
    var version = 0
    exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
    if (version >= DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION) return

    // Left joins, so an effect whose Look or project has gone (foreign keys are off) is logged and
    // deleted too.
    data class Dropped(val lookId: Int, val project: String?, val look: String?, val fxType: String)
    val dropped = ArrayList<Dropped>()
    exec(
        """
        SELECT e.look_id AS look_id, p.name AS project, l.name AS look, e.fx_type AS effect
          FROM look_fx e LEFT JOIN looks l ON l.id = e.look_id LEFT JOIN projects p ON p.id = l.project_id
         WHERE e.target_type = '$DEFERRED_TARGET_TYPE'
         ORDER BY p.name, l.name, e.sort_order, e.id
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) dropped += Dropped(rs.getInt("look_id"), rs.getString("project"), rs.getString("look"), rs.getString("effect"))
    }
    exec("DELETE FROM look_fx WHERE target_type = '$DEFERRED_TARGET_TYPE'")
    dropped.groupBy { it.lookId }.forEach { (lookId, effects) ->
        logger.warn(
            "Look '{}' in '{}': dropped {} that named no target of its own ({})",
            effects.first().look ?: "#$lookId, deleted", effects.first().project ?: "no project",
            if (effects.size == 1) "an effect" else "${effects.size} effects", effects.joinToString { it.fxType },
        )
    }
    exec("PRAGMA user_version = $DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION", explicitStatementType = StatementType.OTHER)
}
