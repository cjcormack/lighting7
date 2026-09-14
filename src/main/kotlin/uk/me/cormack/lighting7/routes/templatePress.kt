package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.models.nowUtc

private val logger = LoggerFactory.getLogger("templatePress")

/**
 * The one place a template press is recorded, so the programmer's row of recent chips is a fact
 * about the **desk** rather than about whichever tab happened to make the press.
 *
 * There are four doors into "apply this template", and they are four because the gestures are
 * genuinely different — a chip click writes literals, ⌥click adds a tracking layer, a busk pad
 * presses through its bank's solo plan, a MIDI button presses onto the desk selection. What they
 * share is the only thing recents cares about: the operator reached for this template. Recording it
 * once, here, is what stops the four drifting into four slightly different answers to "was that a
 * press?" — which is exactly what a per-surface `localStorage` history would have been.
 *
 * Three rules, each of which a caller could get wrong on its own:
 *
 *  - **A release is not a press.** Both toggle doors stamp only on the arm that puts the layer *on*;
 *    taking it off leaves the stamp where it was, because "what I reached for" is not undone by
 *    putting it down.
 *  - **A refusal is not a press.** A MIDI press with nothing selected, a busk pad refused for the
 *    same reason, a template that is not in this project — none reaches this function.
 *  - **The stamp is the server's clock, and the frame carries it.** Two clients reading their own
 *    clocks would order a burst of presses differently from each other and from the next refetch.
 */
internal object TemplatePressLog {

    /**
     * Stamp [templateId] as pressed now and tell every client.
     *
     * Scoped by [projectId] in the `WHERE`, so a template id that has wandered in from another
     * project updates nothing rather than stamping a stranger's row. A miss is logged and
     * *silently* tolerated: this is called after a press that has already succeeded, so failing the
     * press because its bookkeeping missed would be the tail wagging the dog.
     *
     * Runs its own transaction and must therefore **not** be called from inside one — every caller
     * is past its own transaction by the time it presses.
     */
    fun record(state: State, projectId: Int, templateId: Int) {
        // One stamp feeds both the column and the frame. `nowUtc()` is already truncated to the
        // column's millisecond resolution and `toIsoUtc()` always prints three fraction digits, so
        // the text on the wire is byte-identical to what the next read of the row renders. Before
        // those two existed this had to be done by hand, and getting it wrong put `…34.612360Z` on
        // the wire against `…34.612Z` from the next read — a stamp that "changed" for no reason.
        val stampedAt = nowUtc()
        val updated = try {
            transaction(state.database) {
                DaoTemplates.update({
                    (DaoTemplates.id eq templateId) and (DaoTemplates.project eq projectId)
                }) {
                    it[lastPressedAt] = stampedAt
                }
            }
        } catch (e: Exception) {
            logger.warn("template press of {} not recorded: {}", templateId, e.message)
            return
        }
        if (updated == 0) {
            logger.debug("template press of {} not recorded: no such template in project {}", templateId, projectId)
            return
        }
        state.show.fixtures.templatePressed(templateId, stampedAt.toIsoUtc())
    }
}
