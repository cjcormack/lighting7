package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoBuskPage

/**
 * The busk layout's hand-rolled cascades. SQLite enforces no FK cascade without a per-connection
 * pragma (see `DaoBuskPads`), so every path that removes a page or a record does the sweep itself,
 * inside its own transaction. Both helpers must be called inside one.
 */

/** Delete [page] and everything under it: pads → banks → columns → page. */
internal fun deleteBuskPage(page: DaoBuskPage) {
    page.columns.forEach { column ->
        column.banks.forEach { bank ->
            bank.pads.forEach { it.delete() }
            bank.delete()
        }
        column.delete()
    }
    page.delete()
}

/**
 * Delete every pad that presses the named record — a template, a Look or a cue — and return the
 * ids of the pages that lost one, so the caller can fire `buskLayoutChanged` for exactly those
 * after its transaction commits.
 *
 * Unconditional, and deliberately not a delete guard (busk-layout plan D3): a pad is an
 * enrichment of its record, so the record's delete takes its pads with it the way it takes its
 * rows. Pass exactly one id; the others are null.
 */
internal fun deleteBuskPadsReferencing(
    templateId: Int? = null,
    lookId: Int? = null,
    cueId: Int? = null,
): Set<Int> {
    require(listOfNotNull(templateId, lookId, cueId).size == 1) { "exactly one record id" }
    val pads = when {
        templateId != null -> DaoBuskPad.find { DaoBuskPads.template eq templateId }
        lookId != null -> DaoBuskPad.find { DaoBuskPads.look eq lookId }
        else -> DaoBuskPad.find { DaoBuskPads.cue eq cueId!! }
    }.toList()
    val pageIds = pads.mapTo(mutableSetOf()) { it.bank.column.page.id.value }
    pads.forEach { it.delete() }
    return pageIds
}
