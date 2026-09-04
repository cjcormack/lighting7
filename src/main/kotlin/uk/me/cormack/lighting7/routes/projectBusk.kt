package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.BUSK_WIDTHS
import uk.me.cormack.lighting7.models.BuskFlow
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.DaoBuskBank
import uk.me.cormack.lighting7.models.DaoBuskBanks
import uk.me.cormack.lighting7.models.DaoBuskColumn
import uk.me.cormack.lighting7.models.DaoBuskColumns
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoBuskPage
import uk.me.cormack.lighting7.models.DaoBuskPages
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.buskPadKind
import uk.me.cormack.lighting7.models.warnMalformedBuskPad
import uk.me.cormack.lighting7.state.State

/** 400 code: the layout document is malformed — a bad width or flow, an empty row or column, a pad naming no record or two. */
internal const val CODE_BUSK_LAYOUT_INVALID = "BUSK_LAYOUT_INVALID"

/** 400 code: the document names a column, bank or pad id that is not on this page, or names one twice. */
internal const val CODE_BUSK_LAYOUT_IDENTITY = "BUSK_LAYOUT_IDENTITY"

/** 400 code: a pad names a template, Look or cue that is not in this project. */
internal const val CODE_BUSK_LAYOUT_REF = "BUSK_LAYOUT_REF"

/** 409 code: a page of that name already exists in the project. */
internal const val CODE_BUSK_PAGE_NAME_TAKEN = "BUSK_PAGE_NAME_TAKEN"

/** 400 code: a reorder that does not name every page exactly once. */
internal const val CODE_BUSK_REORDER_INCOMPLETE = "BUSK_REORDER_INCOMPLETE"

private const val PAGE_NOT_FOUND = "Busk page not found"

/**
 * The busk layout's REST surface: pages, and the whole-page layout write.
 *
 * `GET /busk/pages` is the busk view's one read — every page nested to the pad, each pad carrying
 * its record's summary DTO so the page needs no second fetch and the client draws a pad with the
 * faces it already has for the library ([BuskPadDto]). The press is its own route family
 * (`routes/buskPress.kt`), because it touches the running show and this file only touches rows.
 *
 * **One write for a page, the whole page** (busk-layout plan D10). `PUT .../layout` takes rows of
 * columns of banks of pads, pads without an id being created, pads absent being deleted, and
 * renumbers densely — the `applyTemplateLayout` shape, and for the same reason: a partial document
 * cannot say "this column is now empty". It answers with the page as written, and that is
 * load-bearing rather than polite: the client's next gesture must carry the ids this one minted, or
 * every gesture would recreate every pad.
 *
 * Every write here is gated on the current project, like the cue-slot writes, and every one fires
 * `buskLayoutChanged` for the pages it touched.
 */
internal fun Route.routeApiRestProjectBusk(state: State) {
    // GET /projects/{id}/busk/pages
    get<BuskPagesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val pages = transaction(state.database) {
                val records = BuskRecordCache(state)
                pagesOf(project).map { it.toDto(records) }
            }
            call.respond(pages)
        }
    }

    // GET /projects/{id}/busk/pages/{pageId}
    get<BuskPageResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val page = transaction(state.database) {
                pageIn(project, resource.pageId)?.toDto(BuskRecordCache(state))
            }
            if (page == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PAGE_NOT_FOUND))
            } else {
                call.respond(page)
            }
        }
    }

    // POST /projects/{id}/busk/pages — appends
    post<BuskPagesResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val name = call.receive<CreateBuskPageRequest>().name.trim()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Page name must not be blank"))
                return@withCurrentProject
            }
            val created = transaction(state.database) {
                if (nameTaken(project, name, excluding = null)) return@transaction null
                val next = (pagesOf(project).maxOfOrNull { it.sortOrder } ?: -1) + 1
                DaoBuskPage.new {
                    this.project = project
                    this.name = name
                    sortOrder = next
                }.toDto(BuskRecordCache(state))
            }
            if (created == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A busk page named '$name' already exists in this project", code = CODE_BUSK_PAGE_NAME_TAKEN),
                )
            } else {
                state.show.fixtures.buskLayoutChanged(listOf(created.id))
                call.respond(HttpStatusCode.Created, created)
            }
        }
    }

    // PUT /projects/{id}/busk/pages/{pageId} — rename
    put<BuskPageResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val name = call.receive<RenameBuskPageRequest>().name.trim()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Page name must not be blank"))
                return@withCurrentProject
            }
            val outcome = transaction(state.database) {
                val page = pageIn(project, resource.pageId) ?: return@transaction PageWriteOutcome.NotFound
                if (nameTaken(project, name, excluding = page.id.value)) return@transaction PageWriteOutcome.NameTaken
                page.name = name
                PageWriteOutcome.Written(page.toDto(BuskRecordCache(state)))
            }
            when (outcome) {
                PageWriteOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(PAGE_NOT_FOUND))
                PageWriteOutcome.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A busk page named '$name' already exists in this project", code = CODE_BUSK_PAGE_NAME_TAKEN),
                )
                is PageWriteOutcome.Written -> {
                    state.show.fixtures.buskLayoutChanged(listOf(outcome.dto.id))
                    call.respond(outcome.dto)
                }
            }
        }
    }

    // DELETE /projects/{id}/busk/pages/{pageId}
    delete<BuskPageResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val survivors = transaction(state.database) {
                val page = pageIn(project, resource.pageId) ?: return@transaction null
                deleteBuskPage(page)
                // The pages that followed close the gap, so a reorder never has to say so.
                pagesOf(project).mapIndexed { index, remaining -> remaining.sortOrder = index; remaining.id.value }
            }
            if (survivors != null) {
                // The deleted page *and* the survivors: their positions moved, and a client that
                // re-read only the deleted id would 404 and keep the old tab order.
                state.show.fixtures.buskLayoutChanged(listOf(resource.pageId) + survivors)
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PAGE_NOT_FOUND))
            }
        }
    }

    // POST /projects/{id}/busk/pages/reorder — every page named once
    post<BuskPagesReorderResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val request = call.receive<ReorderBuskPagesRequest>()
            val outcome = transaction(state.database) {
                val pages = pagesOf(project).associateBy { it.id.value }
                val named = request.pageIds
                if (named.toSet() != pages.keys || named.size != pages.size) {
                    return@transaction "Reorder must name every busk page in the project exactly once — " +
                        "have ${pages.keys.sorted()}, got $named"
                }
                named.forEachIndexed { index, id -> pages.getValue(id).sortOrder = index }
                null
            }
            if (outcome != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome, code = CODE_BUSK_REORDER_INCOMPLETE))
            } else {
                state.show.fixtures.buskLayoutChanged(request.pageIds)
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    // PUT /projects/{id}/busk/pages/{pageId}/layout — the whole page
    put<BuskPageLayoutResource> { resource ->
        withCurrentProject(state, resource.parent.parent.projectId) { project ->
            val request = call.receive<BuskLayoutRequest>()
            val outcome = transaction(state.database) {
                val page = pageIn(project, resource.parent.pageId) ?: return@transaction BuskLayoutOutcome.NotFound
                applyBuskLayout(project, page, request, BuskRecordCache(state))
            }
            when (outcome) {
                BuskLayoutOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(PAGE_NOT_FOUND))
                is BuskLayoutOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = CODE_BUSK_LAYOUT_INVALID))
                is BuskLayoutOutcome.Identity ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = CODE_BUSK_LAYOUT_IDENTITY))
                is BuskLayoutOutcome.Ref ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = CODE_BUSK_LAYOUT_REF))
                is BuskLayoutOutcome.Ok -> {
                    state.show.fixtures.buskLayoutChanged(listOf(outcome.page.id))
                    call.respond(outcome.page)
                }
            }
        }
    }
}

// ─── Resources ──────────────────────────────────────────────────────────

@Resource("/{projectId}/busk/pages")
internal data class BuskPagesResource(val projectId: String)

/** Unambiguous beside [BuskPageResource]: `pageId` is an `Int` that `"reorder"` cannot parse as. */
@Resource("/reorder")
internal data class BuskPagesReorderResource(val parent: BuskPagesResource)

@Resource("/{pageId}")
internal data class BuskPageResource(val parent: BuskPagesResource, val pageId: Int)

@Resource("/layout")
internal data class BuskPageLayoutResource(val parent: BuskPageResource)

// ─── Read DTOs ──────────────────────────────────────────────────────────

/** A page as the busk view draws it: rows of columns of banks of pads, positions implied by order. */
@Serializable
internal data class BuskPageDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val sortOrder: Int,
    val rows: List<BuskRowDto> = emptyList(),
)

@Serializable
internal data class BuskRowDto(val columns: List<BuskColumnDto> = emptyList())

@Serializable
internal data class BuskColumnDto(
    val id: Int,
    val uuid: String,
    /** Width share in twelfths — one of `BUSK_WIDTHS`. */
    val width: Int,
    val banks: List<BuskBankDto> = emptyList(),
)

@Serializable
internal data class BuskBankDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val solo: Boolean,
    /** `WRAP` or `COLUMN`. */
    val flow: String,
    val pads: List<BuskPadDto> = emptyList(),
)

/**
 * A pad: which kind it presses, and **the record's own summary DTO** — exactly one of [template] /
 * [look] / [cue] is set, matching [kind].
 *
 * Embedded rather than flattened to a name, a swatch and a detail line, because the client already
 * derives a pad's face from the summary it holds for the library row — the swatch from the rows,
 * and an effect template's detail line from its effect *plus a live speed-master label*, which a
 * server string would freeze. One record's DTO is built once per response however many pads it sits
 * on. A cue gets [BuskCueDto], because the cue list's document carries every layer and trigger and
 * a pad needs a number, a name and a stack.
 */
@Serializable
internal data class BuskPadDto(
    val id: Int,
    val uuid: String,
    /** `TEMPLATE`, `LOOK` or `CUE`. */
    val kind: String,
    val template: TemplateDto? = null,
    val look: LookDto? = null,
    val cue: BuskCueDto? = null,
)

/** What a cue pad draws: the mono number, the name, the stack. Lit state comes from the stack list. */
@Serializable
internal data class BuskCueDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val cueNumber: String? = null,
    val cueStackId: Int,
    val cueStackName: String,
)

// ─── Write DTOs ─────────────────────────────────────────────────────────

@Serializable
internal data class CreateBuskPageRequest(val name: String)

@Serializable
internal data class RenameBuskPageRequest(val name: String)

/** Every page of the project, once, in the order wanted. */
@Serializable
internal data class ReorderBuskPagesRequest(val pageIds: List<Int>)

/**
 * The whole page. Rows and their order are implied by list position; a column, bank or pad with an
 * id is moved and rewritten, one without is created, and one on the page but absent here is
 * deleted. `rows: []` is a legal empty page.
 */
@Serializable
internal data class BuskLayoutRequest(val rows: List<BuskLayoutRow> = emptyList())

@Serializable
internal data class BuskLayoutRow(val columns: List<BuskLayoutColumn> = emptyList())

@Serializable
internal data class BuskLayoutColumn(
    val columnId: Int? = null,
    val width: Int,
    val banks: List<BuskLayoutBank> = emptyList(),
)

@Serializable
internal data class BuskLayoutBank(
    val bankId: Int? = null,
    val name: String,
    val solo: Boolean = false,
    val flow: String = "WRAP",
    val pads: List<BuskLayoutPad> = emptyList(),
)

/** Exactly one of [templateId] / [lookId] / [cueId]. Int ids, like every REST body; uuids are sync's. */
@Serializable
internal data class BuskLayoutPad(
    val padId: Int? = null,
    val templateId: Int? = null,
    val lookId: Int? = null,
    val cueId: Int? = null,
)

// ─── Outcomes ───────────────────────────────────────────────────────────

private sealed interface PageWriteOutcome {
    data object NotFound : PageWriteOutcome
    data object NameTaken : PageWriteOutcome
    data class Written(val dto: BuskPageDto) : PageWriteOutcome
}

internal sealed interface BuskLayoutOutcome {
    data object NotFound : BuskLayoutOutcome

    /** Malformed shape — a 400 carrying [CODE_BUSK_LAYOUT_INVALID]. */
    data class Invalid(val message: String) : BuskLayoutOutcome

    /** An id not on this page, or named twice — a 400 carrying [CODE_BUSK_LAYOUT_IDENTITY]. */
    data class Identity(val message: String) : BuskLayoutOutcome

    /** A record not in this project — a 400 carrying [CODE_BUSK_LAYOUT_REF]. */
    data class Ref(val message: String) : BuskLayoutOutcome

    data class Ok(val page: BuskPageDto) : BuskLayoutOutcome
}

// ─── The layout write ───────────────────────────────────────────────────

/**
 * Validate [request] against [page] and, if it is a well-formed document of things on this page
 * and records in this project, write it — every position dense from zero, rows renumbered, pads
 * without an id created, pads absent deleted. **Returns before touching a row on any refusal.**
 *
 * Validation runs shape → identity → references, so the message names the first thing wrong in
 * the order an author would fix it. Must be called inside a transaction.
 */
internal fun applyBuskLayout(
    project: DaoProject,
    page: DaoBuskPage,
    request: BuskLayoutRequest,
    records: BuskRecordCache,
): BuskLayoutOutcome {
    // Shape.
    request.rows.forEachIndexed { rowIndex, row ->
        if (row.columns.isEmpty()) return BuskLayoutOutcome.Invalid("Row ${rowIndex + 1} has no columns")
        row.columns.forEachIndexed { columnIndex, column ->
            val where = "row ${rowIndex + 1}, column ${columnIndex + 1}"
            if (column.width !in BUSK_WIDTHS) {
                return BuskLayoutOutcome.Invalid("Width ${column.width} at $where is not one of ${BUSK_WIDTHS.sorted()} twelfths")
            }
            if (column.banks.isEmpty()) return BuskLayoutOutcome.Invalid("Column at $where has no banks")
            column.banks.forEach { bank ->
                if (bank.name.isBlank()) return BuskLayoutOutcome.Invalid("A bank at $where has a blank name")
                if (BuskFlow.entries.none { it.name == bank.flow }) {
                    return BuskLayoutOutcome.Invalid("Flow '${bank.flow}' on bank '${bank.name}' is not one of ${BuskFlow.entries.map { it.name }}")
                }
                bank.pads.forEach { pad ->
                    if (buskPadKind(pad.templateId, pad.lookId, pad.cueId) == null) {
                        return BuskLayoutOutcome.Invalid("A pad in bank '${bank.name}' must name exactly one of templateId, lookId or cueId")
                    }
                }
            }
        }
    }

    // Identity: every named id is on this page, none twice. Read through `find`, not the entity's
    // referrers, so the write below can re-read the page without a stale referrer cache.
    val columns = DaoBuskColumn.find { DaoBuskColumns.page eq page.id }.associateBy { it.id.value }
    val banks = DaoBuskBank.find { DaoBuskBanks.column inList columns.keys.toList() }.associateBy { it.id.value }
    val pads = DaoBuskPad.find { DaoBuskPads.bank inList banks.keys.toList() }.associateBy { it.id.value }
    val allColumns = request.rows.flatMap { it.columns }
    val allBanks = allColumns.flatMap { it.banks }
    val allPads = allBanks.flatMap { it.pads }
    identityProblem("columns", allColumns.mapNotNull { it.columnId }, columns.keys)?.let { return it }
    identityProblem("banks", allBanks.mapNotNull { it.bankId }, banks.keys)?.let { return it }
    identityProblem("pads", allPads.mapNotNull { it.padId }, pads.keys)?.let { return it }

    // References: every record named is in this project.
    val templates = allPads.mapNotNull { it.templateId }.toSet().associateWith { records.template(project, it) }
    val looks = allPads.mapNotNull { it.lookId }.toSet().associateWith { records.look(project, it) }
    val cues = allPads.mapNotNull { it.cueId }.toSet().associateWith { records.cue(project, it) }
    val missing = listOf("templates" to templates, "looks" to looks, "cues" to cues)
        .map { (what, found) -> what to found.filterValues { it == null }.keys }
        .filter { it.second.isNotEmpty() }
    if (missing.isNotEmpty()) {
        return BuskLayoutOutcome.Ref(
            "Layout names records not in this project — " + missing.joinToString("; ") { "${it.first} ${it.second.sorted()}" },
        )
    }

    // Write: upsert everything named, then sweep what was not. Moves first, so a bank whose pads
    // have all moved elsewhere is empty by the time it is swept rather than taking them with it.
    val keptColumns = HashSet<Int>()
    val keptBanks = HashSet<Int>()
    val keptPads = HashSet<Int>()
    request.rows.forEachIndexed { rowIndex, row ->
        row.columns.forEachIndexed { columnIndex, c ->
            val column = c.columnId?.let { columns.getValue(it) } ?: DaoBuskColumn.new {
                this.page = page
                this.row = rowIndex
                sortOrder = columnIndex
                width = c.width
            }
            column.row = rowIndex
            column.sortOrder = columnIndex
            column.width = c.width
            keptColumns += column.id.value
            c.banks.forEachIndexed { bankIndex, b ->
                val bank = b.bankId?.let { banks.getValue(it) } ?: DaoBuskBank.new {
                    this.column = column
                    sortOrder = bankIndex
                    name = b.name.trim()
                    solo = b.solo
                    flow = b.flow
                }
                bank.column = column
                bank.sortOrder = bankIndex
                bank.name = b.name.trim()
                bank.solo = b.solo
                bank.flow = b.flow
                keptBanks += bank.id.value
                b.pads.forEachIndexed { padIndex, d ->
                    val pad = d.padId?.let { pads.getValue(it) } ?: DaoBuskPad.new {
                        this.bank = bank
                        sortOrder = padIndex
                    }
                    pad.bank = bank
                    pad.sortOrder = padIndex
                    // Refs rewritten from the document, not merged: a pad is what the page says it is.
                    pad.template = d.templateId?.let { templates.getValue(it) }
                    pad.look = d.lookId?.let { looks.getValue(it) }
                    pad.cue = d.cueId?.let { cues.getValue(it) }
                    keptPads += pad.id.value
                }
            }
        }
    }
    pads.values.filter { it.id.value !in keptPads }.forEach { it.delete() }
    banks.values.filter { it.id.value !in keptBanks }.forEach { it.delete() }
    columns.values.filter { it.id.value !in keptColumns }.forEach { it.delete() }

    return BuskLayoutOutcome.Ok(page.toDto(records))
}

private fun identityProblem(what: String, named: List<Int>, onPage: Set<Int>): BuskLayoutOutcome.Identity? {
    val unknown = named.filter { it !in onPage }
    if (unknown.isNotEmpty()) return BuskLayoutOutcome.Identity("Layout names $what not on this page — $unknown")
    val duplicates = named.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) return BuskLayoutOutcome.Identity("Layout names $what more than once — ${duplicates.sorted()}")
    return null
}

// ─── Reads ──────────────────────────────────────────────────────────────

/** The project's pages in operator order. Must be called inside a transaction. */
internal fun pagesOf(project: DaoProject): List<DaoBuskPage> =
    DaoBuskPage.find { DaoBuskPages.project eq project.id }
        .sortedWith(compareBy({ it.sortOrder }, { it.name }))

private fun pageIn(project: DaoProject, pageId: Int): DaoBuskPage? =
    DaoBuskPage.findById(pageId)?.takeIf { it.project.id == project.id }

private fun nameTaken(project: DaoProject, name: String, excluding: Int?): Boolean =
    DaoBuskPage.find { (DaoBuskPages.project eq project.id) and (DaoBuskPages.name eq name) }
        .any { it.id.value != excluding }

/**
 * One response's worth of record DTOs, built once per distinct record however many pads it sits
 * on, and the project-scoped lookups the layout write validates through. Lives for one transaction.
 */
internal class BuskRecordCache(private val state: State) {
    private val templates = HashMap<Int, DaoTemplate?>()
    private val looks = HashMap<Int, DaoLook?>()
    private val cues = HashMap<Int, DaoCue?>()
    private val templateDtos = HashMap<Int, TemplateDto>()
    private val lookDtos = HashMap<Int, LookDto>()
    private val cueDtos = HashMap<Int, BuskCueDto>()

    fun template(project: DaoProject, id: Int): DaoTemplate? =
        templates.getOrPut(id) { DaoTemplate.findById(id)?.takeIf { it.project.id == project.id } }

    fun look(project: DaoProject, id: Int): DaoLook? =
        looks.getOrPut(id) { DaoLook.findById(id)?.takeIf { it.project.id == project.id } }

    fun cue(project: DaoProject, id: Int): DaoCue? =
        cues.getOrPut(id) { DaoCue.findById(id)?.takeIf { it.project.id == project.id } }

    fun dto(template: DaoTemplate): TemplateDto =
        templateDtos.getOrPut(template.id.value) { template.toDto(state.show.fxRegistry) }

    fun dto(look: DaoLook): LookDto =
        lookDtos.getOrPut(look.id.value) { look.toSummaryDto(state, null) }

    fun dto(cue: DaoCue): BuskCueDto = cueDtos.getOrPut(cue.id.value) {
        val stack = cue.cueStack
        BuskCueDto(
            id = cue.id.value,
            uuid = cue.uuid.toString(),
            name = cue.name,
            cueNumber = cue.cueNumber,
            cueStackId = stack.id.value,
            cueStackName = stack.name,
        )
    }
}

/** Must be called inside a transaction. Reads through `find` so a just-written page reads back fresh. */
internal fun DaoBuskPage.toDto(records: BuskRecordCache): BuskPageDto {
    val columns = DaoBuskColumn.find { DaoBuskColumns.page eq id }
        .sortedWith(compareBy({ it.row }, { it.sortOrder }, { it.uuid }))
    val rows = columns.groupBy { it.row }.toSortedMap().values.map { rowColumns ->
        BuskRowDto(
            columns = rowColumns.map { column ->
                BuskColumnDto(
                    id = column.id.value,
                    uuid = column.uuid.toString(),
                    width = column.width,
                    banks = DaoBuskBank.find { DaoBuskBanks.column eq column.id }
                        .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                        .map { bank ->
                            BuskBankDto(
                                id = bank.id.value,
                                uuid = bank.uuid.toString(),
                                name = bank.name,
                                solo = bank.solo,
                                flow = bank.flow,
                                pads = DaoBuskPad.find { DaoBuskPads.bank eq bank.id }
                                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                                    .mapNotNull { pad -> pad.toDto(records) },
                            )
                        },
                )
            },
        )
    }
    return BuskPageDto(id = id.value, uuid = uuid.toString(), name = name, sortOrder = sortOrder, rows = rows)
}

/** Null for a malformed pad, which every reader treats as absent (`buskPadKind`). */
private fun DaoBuskPad.toDto(records: BuskRecordCache): BuskPadDto? {
    val kind = kind ?: run { warnMalformedBuskPad { uuid.toString() }; return null }
    return when (kind) {
        BuskPadKind.TEMPLATE -> BuskPadDto(id.value, uuid.toString(), kind.name, template = records.dto(template!!))
        BuskPadKind.LOOK -> BuskPadDto(id.value, uuid.toString(), kind.name, look = records.dto(look!!))
        BuskPadKind.CUE -> BuskPadDto(id.value, uuid.toString(), kind.name, cue = records.dto(cue!!))
    }
}
