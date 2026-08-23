package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.LayerSource
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.state.State

/**
 * Resolve "which library entity does this layer apply?" from the id pair every layer-writing caller
 * accepts, or null when the request names neither, both, or something that does not exist.
 *
 * **One lookup shared by every caller** — `programmer.addLayer`, the two toggle routes, and the cue
 * layer writers — because there are three things to get right and each of them is silent when got
 * wrong:
 *
 *  - the **uuid** is read here rather than accepted from the client. A layer resolves its source by
 *    uuid (int PKs are re-minted on sync import) while the desk addresses records by id everywhere
 *    else, so a client supplying both is a client that can make the two disagree.
 *  - the **name** is read at the same instant as the uuid, so a layer cannot be created naming a
 *    record it does not point at.
 *  - **exactly one** of the two ids must be set. Accepting both and preferring one would silently
 *    ignore half of a malformed request.
 */
internal fun resolveLayerSource(state: State, lookId: Int?, templateId: Int?): LayerSource? {
    if ((lookId == null) == (templateId == null)) return null
    return transaction(state.database) {
        if (lookId != null) {
            DaoLook.findById(lookId)?.let { LayerSource.look(it.id.value, it.uuid, it.name) }
        } else {
            DaoTemplate.findById(templateId!!)?.let { LayerSource.template(it.id.value, it.uuid, it.name) }
        }
    }
}
