package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskBanks
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskColumns
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoBuskPage

/**
 * The busk layout's hand-rolled cascades. SQLite enforces no FK cascade without a per-connection
 * pragma (see `DaoBuskPads`), so every path that removes a page or a record does the sweep itself,
 * inside its own transaction. Both helpers must be called inside one.
 */

/**
 * Everything under [pageIds], read as three batched queries rather than by walking referrers.
 *
 * `find` and not the entity's referrer collections, because a caller may be **mid-write**: the
 * layout route reads a page it is about to rewrite, and a referrer cache loaded earlier in the same
 * transaction would hand it a stale picture. It is one function because the descent has to stay
 * consistent — two hand-rolled copies would have to be got right twice if the chain ever gained a
 * level. Must be called inside a transaction.
 */
internal fun buskPageContents(pageIds: List<Int>): BuskPageContents {
    if (pageIds.isEmpty()) return BuskPageContents(emptyMap(), emptyMap(), emptyMap())
    val columns = DaoBuskColumn.find { DaoBuskColumns.page inList pageIds }.associateBy { it.id.value }
    if (columns.isEmpty()) return BuskPageContents(columns, emptyMap(), emptyMap())
    val banks = DaoBuskBank.find { DaoBuskBanks.column inList columns.keys.toList() }.associateBy { it.id.value }
    if (banks.isEmpty()) return BuskPageContents(columns, banks, emptyMap())
    val pads = DaoBuskPad.find { DaoBuskPads.bank inList banks.keys.toList() }.associateBy { it.id.value }
    return BuskPageContents(columns, banks, pads)
}

/** The three levels under a page, each keyed by id. */
internal data class BuskPageContents(
    val columns: Map<Int, DaoBuskColumn>,
    val banks: Map<Int, DaoBuskBank>,
    val pads: Map<Int, DaoBuskPad>,
)

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

/**
 * How many busk **pages** hold a pad for each of [ids] — the "on *n* pages" hint the library rows
 * and the delete confirms show.
 *
 * A **hint, never a guard** (busk-layout plan D3): a pad is an enrichment of its record, so this
 * count never joins a delete guard, never produces a 409, and never appears in a `UsageDescribe`
 * string. Deleting a record still takes its pads with it silently as far as the API is concerned;
 * this only lets a client say so first.
 *
 * **No project parameter, and none is possible to need.** A pad naming a record can only sit on a
 * page in that record's own project: both pad write boundaries — `applyBuskLayout` and the append
 * route — resolve the record project-scoped before writing, and a record's delete sweeps its pads.
 * So counting by record id is already counting within one project, and a project argument could
 * only ever agree with the ids it was handed.
 *
 * Four queries whatever the size of [ids], not four per record: the pads in one `inList`, then
 * bank → column → page resolved as two more batched reads. The obvious `pad.bank.column.page` walk
 * (which [deleteBuskPadsReferencing] above uses, correctly, for one record) is three lazy loads per
 * pad and would be N+1 across a library list. Must be called inside a transaction.
 */
internal fun buskPageCountsFor(kind: BuskPadKind, ids: Collection<Int>): Map<Int, Int> {
    if (ids.isEmpty()) return emptyMap()
    val list = ids.toList()
    val recordColumn = when (kind) {
        BuskPadKind.TEMPLATE -> DaoBuskPads.template
        BuskPadKind.LOOK -> DaoBuskPads.look
        BuskPadKind.CUE -> DaoBuskPads.cue
    }
    val pads = DaoBuskPad.find { recordColumn inList list }.toList()
    if (pads.isEmpty()) return emptyMap()

    val bankOf = { pad: DaoBuskPad -> pad.readValues[DaoBuskPads.bank].value }
    val bankToColumn = DaoBuskBank.find { DaoBuskBanks.id inList pads.map(bankOf).distinct() }
        .associate { it.id.value to it.readValues[DaoBuskBanks.column].value }
    val columnToPage = DaoBuskColumn.find { DaoBuskColumns.id inList bankToColumn.values.distinct() }
        .associate { it.id.value to it.readValues[DaoBuskColumns.page].value }

    // Distinct pages, not pads: two pads for one record on one page is one page.
    return pads.groupBy { it.readValues[recordColumn]!!.value }
        .mapValues { (_, forRecord) ->
            forRecord.mapNotNull { columnToPage[bankToColumn[bankOf(it)]] }.distinct().size
        }
}
