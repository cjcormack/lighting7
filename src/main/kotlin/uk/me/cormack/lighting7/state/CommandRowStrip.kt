package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.stripCommandRows

private val logger = LoggerFactory.getLogger("CommandRowStrip")

/**
 * **One-off, to be deleted once it has run on the one install** (fixture optics plan session 7, §6).
 *
 * Until session 7 five types carried a reset as an ordinary setting option — the Varytec's and the
 * Shehds' as a two-option `reset` setting, the Fusion's, the Orbit's and the Slender's as an option of
 * a shared channel — so a Look row, a cue row or an effect could hold one and re-home the rig on every
 * recall. They are fixture commands now, refused at every write boundary; this clears what was stored
 * before, in every project, and says how much it cleared.
 *
 * Plugged in where `InstallBootstrap.kt` says a data pass goes, beside its trigger twin
 * (`TriggerRowStrip.kt`): `State.initDatabase`'s transaction, after the schema exists and before
 * anything reads it. A no-op on a database with nothing to strip, so running it again is harmless —
 * but it walks every project's rows on every boot, which is the reason to delete it rather than keep
 * it. `stripCommandRows` itself stays: every sync import uses it.
 *
 * A removed option still stored is a level, not a name (`"251"`), so nothing fails to load before
 * this runs: a row naming the Varytec's vanished `reset` property is an unhealthy row, and a level in
 * a shared channel's command band is sent as the channel's idle level by the output.
 */
internal fun JdbcTransaction.stripStoredCommandRows() {
    var total = 0
    for (project in DaoProject.all()) {
        val stripped = stripCommandRows(project)
        if (stripped > 0) {
            logger.warn("Stripped {} stored row(s) holding a fixture command from project '{}' ({})", stripped, project.name, project.id.value)
            total += stripped
        }
    }
    logger.info("Fixture command strip: {} stored row(s) removed", total)
}
