package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.buildCombinedCueLayerRows
import uk.me.cormack.lighting7.fx.buildCueApplyDataForCues
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.history.historyLabel
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * *Copy the values* (palette-references plan D8, D9): a palette reference **hardened** to the
 * literal each head it reaches last resolved to, so the row stops following the palette and nothing
 * on stage moves. A row on one head becomes one literal row. A group row stays one group row when
 * its members agree, else becomes a row per member, leaving out a member the owner already holds a
 * row of its own for.
 *
 * A row with a head that has nothing to keep — not patched now, or never resolved on this run — is
 * left as the reference by *Copy the values*, since its palette stays; a forced palette delete keeps
 * what did resolve and drops the rest, since nothing could resolve it again.
 *
 * Resolved against the live rig, so current project only.
 */
internal data class HardenedRow(val target: TargetRef, val elementKey: String?, val attribute: String, val value: String)

/** A row's literal replacements, and whether every head it reaches had one. */
internal class Hardening(val rows: List<HardenedRow>, val complete: Boolean)

/** What a harden did: rows made literal, and rows left as references for having a head with nothing to keep. */
internal data class HardenCount(val hardened: Int, val kept: Int)

/**
 * The literal rows [target]'s reference to [paletteUuid] on [attribute] hardens into. [ownKeys] is
 * every `(head key, attribute)` the owner holds a non-group row for, which a group row's split
 * leaves alone.
 */
internal fun hardenedRows(
    state: State,
    target: TargetRef,
    elementKey: String?,
    attribute: String,
    paletteUuid: UUID,
    ownKeys: Set<Pair<String, String>>,
): Hardening {
    val fixtures = state.show.fixtures
    val references = state.show.paletteReferences
    fun on(head: GroupableFixture) = references.resolve(head, attribute, paletteUuid).let { r -> r.value?.let { r.attribute to it.serialize() } }
    return when (target) {
        is TargetRef.Fixture -> {
            val head = runCatching { fixtures.untypedGroupableFixture(elementKey ?: target.key) }.getOrNull()
                ?: return Hardening(emptyList(), complete = false)
            // A parent that takes nothing of the palette resolved it on its cells: one row per cell.
            val landed = references.resolveFanned(head, attribute, paletteUuid).mapNotNull { (on, resolved) ->
                val literal = resolved.value?.serialize() ?: return@mapNotNull null
                HardenedRow(target, if (on === head) elementKey else on.targetKey, resolved.attribute, literal)
            }
            if (landed.isEmpty()) return Hardening(emptyList(), complete = false)
            Hardening(landed, complete = true)
        }
        is TargetRef.Group -> {
            val members = runCatching { fixtures.untypedGroup(target.key).fixtures }.getOrNull()?.filterIsInstance<Fixture>()
                ?: return Hardening(emptyList(), complete = false)
            val canonical = canonicalAttributeName(attribute)
            val reached = members.filter { (it.key to canonical) !in ownKeys }
            val landed = reached.mapNotNull { member ->
                on(member)?.takeIf { (member.key to it.first) !in ownKeys }?.let { member to it }
            }
            val complete = members.isNotEmpty() && landed.size == reached.size
            val distinct = landed.map { it.second }.toSet()
            val rows = if (distinct.size == 1 && landed.size == members.size) {
                val (resolvedAttribute, literal) = distinct.single()
                listOf(HardenedRow(target, null, resolvedAttribute, literal))
            } else {
                landed.map { (member, value) -> HardenedRow(TargetRef.Fixture(member.key), null, value.first, value.second) }
            }
            Hardening(rows, complete)
        }
    }
}

/** Harden [cue]'s references (all of them, or those [rowUuids] names); answers how many rows. Inside a transaction. */
internal fun hardenCueRows(
    state: State,
    cue: DaoCue,
    rowUuids: Set<UUID>? = null,
    /** The palette going, under a forced delete: only its rows, and a row with nothing to keep is dropped. */
    onlyPalette: UUID? = null,
): HardenCount {
    val rows = cue.attributeAssignments.toList()
    val ownKeys = rows.filter { it.targetType == TargetRef.Fixture.TYPE }.mapTo(HashSet()) { it.targetKey to canonicalAttributeName(it.attribute) }
    var hardened = 0
    var kept = 0
    for (row in rows.sortedBy { it.sortOrder }) {
        val paletteUuid = row.paletteUuid ?: continue
        if (onlyPalette != null && paletteUuid != onlyPalette) continue
        if (rowUuids != null && row.uuid !in rowUuids) continue
        val hardening = hardenedRows(state, row.target, null, row.attribute, paletteUuid, ownKeys)
        if (!hardening.complete && onlyPalette == null) { kept++; continue }
        val replacements = hardening.rows
        val fade = row.fadeDuration
        val sortOrder = row.sortOrder
        val first = replacements.firstOrNull()
        if (first == null) {
            row.delete()
        } else {
            row.targetType = first.target.discriminator
            row.targetKey = first.elementKey ?: first.target.key
            row.attribute = first.attribute
            row.value = first.value
            for (extra in replacements.drop(1)) {
                DaoCueAttributeAssignment.new {
                    this.cue = cue
                    targetType = extra.target.discriminator
                    targetKey = extra.elementKey ?: extra.target.key
                    attribute = extra.attribute
                    value = extra.value
                    fadeDuration = fade
                    this.sortOrder = sortOrder
                }
            }
        }
        hardened++
    }
    return HardenCount(hardened, kept)
}

/** [hardenCueRows] for a Look's rows. Inside a transaction. */
internal fun hardenLookRows(state: State, look: DaoLook, rowUuids: Set<UUID>? = null, onlyPalette: UUID? = null): HardenCount {
    val rows = look.rows.toList()
    val ownKeys = rows.filter { it.targetType == TargetRef.Fixture.TYPE }
        .mapTo(HashSet()) { (it.elementKey ?: it.targetKey) to canonicalAttributeName(it.attribute) }
    var hardened = 0
    var kept = 0
    for (row in rows.sortedBy { it.sortOrder }) {
        val paletteUuid = row.paletteUuid ?: continue
        if (onlyPalette != null && paletteUuid != onlyPalette) continue
        if (rowUuids != null && row.uuid !in rowUuids) continue
        val target = row.target ?: continue
        val hardening = hardenedRows(state, target, row.elementKey, row.attribute, paletteUuid, ownKeys)
        if (!hardening.complete && onlyPalette == null) { kept++; continue }
        val replacements = hardening.rows
        val fade = row.fadeDuration
        val sortOrder = row.sortOrder
        val first = replacements.firstOrNull()
        if (first == null) {
            row.delete()
        } else {
            row.targetType = first.target.discriminator
            row.targetKey = first.target.key
            row.elementKey = first.elementKey
            row.attribute = first.attribute
            row.value = first.value
            for (extra in replacements.drop(1)) {
                DaoLookRow.new {
                    this.look = look
                    targetType = extra.target.discriminator
                    targetKey = extra.target.key
                    elementKey = extra.elementKey
                    attribute = extra.attribute
                    value = extra.value
                    fadeDuration = fade
                    this.sortOrder = sortOrder
                }
            }
        }
        hardened++
    }
    return HardenCount(hardened, kept)
}

/** The programmer's references to [paletteUuid] made literal in place, as a forced delete leaves them. */
internal fun hardenProgrammerReferences(state: State, paletteUuid: UUID): Int =
    state.show.programmerStore.hardenReferences(paletteUuid).size

/** `POST /looks/{id}/harden` and `/cues/{id}/harden`'s body: the rows by uuid, or absent for every reference. */
@Serializable
internal data class HardenRequest(val rowUuids: List<String>? = null)

/** Rows made literal, and rows left as references because a head they reach had nothing to keep. */
@Serializable
internal data class HardenResponse(val hardened: Int, val kept: Int = 0)

@Resource("/{lookId}/harden")
internal data class HardenLookResource(val parent: ProjectLooksResource, val lookId: Int)

@Resource("/{cueId}/harden")
internal data class HardenCueResource(val parent: ProjectCuesResource, val cueId: Int)

/** Parse [request]'s uuids, or null with the bad one named. */
private fun HardenRequest.uuids(): Result<Set<UUID>?> = runCatching {
    rowUuids?.mapTo(HashSet()) { raw -> runCatching { UUID.fromString(raw) }.getOrElse { throw IllegalArgumentException("'$raw' is not a row uuid") } }
}

internal fun Route.routeApiRestPaletteHarden(state: State) {
    post<HardenLookResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val uuids = call.receive<HardenRequest>().uuids().getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Bad row uuids"))
                return@withCurrentProject
            }
            historyLabel("Copy the values")
            val outcome = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId)?.takeIf { it.project.id == project.id } ?: return@transaction null
                look.uuid to hardenLookRows(state, look, uuids)
            }
            if (outcome == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Look not found"))
                return@withCurrentProject
            }
            if (outcome.second.hardened > 0) republishForLookEdit(state, outcome.first)
            call.respond(HardenResponse(outcome.second.hardened, outcome.second.kept))
        }
    }

    post<HardenCueResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val uuids = call.receive<HardenRequest>().uuids().getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Bad row uuids"))
                return@withCurrentProject
            }
            historyLabel("Copy the values")
            val hardened = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId)?.takeIf { it.project.id == project.id } ?: return@transaction null
                hardenCueRows(state, cue, uuids)
            }
            if (hardened == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                return@withCurrentProject
            }
            if (hardened.hardened > 0) republishCuesAfterHarden(state, setOf(resource.cueId))
            call.respond(HardenResponse(hardened.hardened, hardened.kept))
        }
    }
}

/**
 * Rebuild the live ones of [cueIds] from their hardened rows and tell the clients they changed. The
 * values are the ones on stage already, so nothing moves; the rebuild only stops the cue's rows
 * naming a palette.
 */
internal fun republishCuesAfterHarden(state: State, cueIds: Set<Int>) {
    if (cueIds.isEmpty()) return
    val engine = state.show.fxEngine
    val live = cueIds intersect engine.cueLayer.activeCueIds()
    if (live.isNotEmpty()) {
        val applyData = transaction(state.database) { buildCueApplyDataForCues(live) }
        val rebuilt = applyData.mapValues { (cueId, data) -> buildCombinedCueLayerRows(state, cueId, data) }
        engine.cueLayer.replaceAssignments(rebuilt.mapValues { it.value.rows }, rebuilt.mapValues { it.value.stompSuppression })
    }
    state.show.fixtures.cuesRecomposed(cueIds.toList())
}
