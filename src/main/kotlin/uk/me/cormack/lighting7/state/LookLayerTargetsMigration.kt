package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("LookLayerTargetsMigration")

/** `PRAGMA user_version` once [clearLookLayerTargets] has run, the step after [DEFERRED_LOOK_EFFECTS_DROPPED_USER_VERSION]. */
internal const val LOOK_LAYER_TARGETS_CLEARED_USER_VERSION = 3

/**
 * Clear the targets of every cue layer that applies a Look, once per database (absolute-looks plan
 * D1, P9). A Look lands whole, so its layer names no targets: the cook ignores any a layer still
 * carries, every stored write drops them and the importer drops them from any archive. What this
 * database still holds is cleared here, each cue named in the log.
 *
 * Runs once, after [dropDeferredLookEffects], inside `State.initDatabase`'s transaction: `PRAGMA
 * user_version` below [LOOK_LAYER_TARGETS_CLEARED_USER_VERSION] means it has not.
 */
internal fun JdbcTransaction.clearLookLayerTargets() {
    var version = 0
    exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
    if (version >= LOOK_LAYER_TARGETS_CLEARED_USER_VERSION) return

    // Left joins, so a layer whose cue, project or Look has gone (foreign keys are off) is logged
    // and cleared too.
    data class Cleared(val cueId: Int, val project: String?, val cue: String?, val look: String?, val targets: String)
    val cleared = ArrayList<Cleared>()
    exec(
        """
        SELECT l.cue_id AS cue_id, p.name AS project, c.name AS cue, k.name AS look, l.targets AS targets
          FROM cue_layers l
          LEFT JOIN cues c ON c.id = l.cue_id
          LEFT JOIN projects p ON p.id = c.project_id
          LEFT JOIN looks k ON k.id = l.look_id
         WHERE l.look_id IS NOT NULL AND l.targets <> '[]'
         ORDER BY p.name, c.name, l.sort_order, l.id
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            cleared += Cleared(rs.getInt("cue_id"), rs.getString("project"), rs.getString("cue"), rs.getString("look"), rs.getString("targets"))
        }
    }
    exec("UPDATE cue_layers SET targets = '[]' WHERE look_id IS NOT NULL")
    cleared.groupBy { it.cueId }.forEach { (cueId, layers) ->
        logger.warn(
            "Cue '{}' in '{}': cleared the targets of {} — a Look lands whole ({})",
            layers.first().cue ?: "#$cueId, deleted", layers.first().project ?: "no project",
            if (layers.size == 1) "a Look layer" else "${layers.size} Look layers",
            layers.joinToString { "${it.look ?: "a deleted Look"}: ${it.targets}" },
        )
    }
    exec("PRAGMA user_version = $LOOK_LAYER_TARGETS_CLEARED_USER_VERSION", explicitStatementType = StatementType.OTHER)
}
