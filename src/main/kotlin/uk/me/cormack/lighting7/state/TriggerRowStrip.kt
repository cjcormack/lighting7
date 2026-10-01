package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.stripTriggerRows

private val logger = LoggerFactory.getLogger("TriggerRowStrip")

/**
 * **One-off, to be deleted once it has run on the one install** (stage-view plan session 9, §6).
 *
 * Until session 9 the Twin Shot's `output1`, `output2` and `master` were plain `OTHER` sliders, so a
 * Look row, a template row, a cue row or an effect could hold a raised one and fire a tube on every
 * recall. They are one-shot triggers and an arm now, refused at every write boundary; this clears
 * what was stored before, in every project, and says how much it cleared.
 *
 * Plugged in where `InstallBootstrap.kt` says a data pass goes: `State.initDatabase`'s transaction,
 * after the schema exists and before anything reads it. A no-op on a database with nothing to strip,
 * so running it again is harmless — but it walks every project's rows on every boot, which is the
 * reason to delete it rather than keep it. `stripTriggerRows` itself stays: every sync import uses it.
 */
internal fun JdbcTransaction.stripStoredTriggerRows() {
    var total = 0
    for (project in DaoProject.all()) {
        val stripped = stripTriggerRows(project)
        if (stripped > 0) {
            logger.warn("Stripped {} stored row(s) naming a one-shot trigger from project '{}' ({})", stripped, project.name, project.id.value)
            total += stripped
        }
    }
    logger.info("One-shot trigger strip: {} stored row(s) removed", total)
}
