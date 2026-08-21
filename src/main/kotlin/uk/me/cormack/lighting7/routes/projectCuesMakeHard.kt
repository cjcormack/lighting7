package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.fx.parsePaletteRef
import uk.me.cormack.lighting7.fx.resolveAssignmentValueForFixture
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("cueMakeHard")

@Resource("/{cueId}/make-hard")
internal data class MakeCueHardResource(
    val parent: ProjectCuesResource,
    val cueId: Int,
    val force: Boolean = false,
)

@Serializable
internal data class CueMakeHardRequest(
    /** Restrict to rows referencing these palette uuids. Omitted = every ref in the cue. */
    val paletteUuids: List<String>? = null,
    val mask: List<String>? = null,
)

@Serializable
internal data class CueMakeHardResponse(
    val cue: CueDetails,
    /** Rows rewritten from a reference to a literal. */
    val converted: Int,
    /**
     * Group rows that had to be expanded to per-fixture rows because their members resolved to
     * different literals — the row count grows, and the operator should see that it did.
     */
    val groupRowsExpanded: Int,
    /** Rows left as references because they don't currently resolve. */
    val unresolved: Int,
    val republishedLive: Boolean,
)

/**
 * Replace a stored cue's palette references with the literals they currently resolve to — the
 * explicit opt-out from reference-preserving Update, per the proposal's §5.3 "Make Hard as the
 * hardening escape hatch".
 *
 * A **group** row can only harden to a group row when every member resolves to the same literal.
 * Otherwise it is replaced by one fixture row per resolving member: the group row asserted "all
 * these fixtures take this palette", and no single literal can say the same thing. Members that
 * don't resolve are dropped from the expansion, and the response reports the growth so the cue card
 * suddenly having more rows isn't a surprise.
 */
internal suspend fun RoutingContext.handleMakeCueHard(state: State, cueId: Int, force: Boolean) {
    val request = try {
        call.receive<CueMakeHardRequest>()
    } catch (_: Exception) {
        CueMakeHardRequest()
    }

    val mask = try {
        parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }
    val paletteFilter = request.paletteUuids
        ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        ?.toSet()
        ?.takeIf { it.isNotEmpty() }

    withCurrentProject(state, "current", { p ->
        "Cannot modify project '${p.name}' — only the current project can be modified"
    }) { project ->
        if (!force) {
            // Same rule as Record/Update: an open session's Discard would revert this.
            val open = state.cueEditSessionRegistry.activeSession(project.id.value)
            if (open?.session?.cueId == cueId) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ProgrammerConflictResponse(
                        "A cue-edit session is open on this cue — hardening underneath it would be " +
                            "reverted by Discard.",
                        CODE_CUE_EDIT_SESSION_OPEN,
                        cueId,
                    ),
                )
                return@withCurrentProject
            }
        }

        val outcome = transaction(state.database) {
            val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id }
                ?: return@transaction null
            hardenCueRows(state, cue, paletteFilter, mask)
        }
        if (outcome == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found in current project"))
            return@withCurrentProject
        }

        val republished = if (outcome.converted > 0) {
            republishCueIfLive(state, cueId, transaction(state.database) {
                DaoCue.findById(cueId)?.cueStack?.id?.value
            })
        } else {
            false
        }
        if (outcome.converted > 0) state.show.fixtures.cueListChanged()

        val details = transaction(state.database) {
            DaoCue.findById(cueId)!!.toCueDetails(true, state.show.fixtures, state.show.lookRegistry)
        }
        logger.info(
            "make-hard cue {}: {} converted, {} group row(s) expanded, {} unresolved",
            cueId, outcome.converted, outcome.groupRowsExpanded, outcome.unresolved,
        )
        call.respond(
            CueMakeHardResponse(
                cue = details,
                converted = outcome.converted,
                groupRowsExpanded = outcome.groupRowsExpanded,
                unresolved = outcome.unresolved,
                republishedLive = republished,
            ),
        )
    }
}

private data class HardenOutcome(
    val converted: Int,
    val groupRowsExpanded: Int,
    val unresolved: Int,
)

/** Must be called inside a transaction. */
private fun hardenCueRows(
    state: State,
    cue: DaoCue,
    paletteFilter: Set<UUID>?,
    mask: Set<PropertyMaskGroup>?,
): HardenOutcome {
    var converted = 0
    var groupRowsExpanded = 0
    var unresolved = 0
    val fixtures = state.show.fixtures
    val registry = state.show.lookRegistry

    // Snapshot first: the loop deletes and creates rows, and iterating a live referrersOn while
    // mutating it is asking for trouble.
    val rows = cue.propertyAssignments.sortedBy { it.sortOrder }.toList()

    for (row in rows) {
        val paletteUuid = parsePaletteRef(row.value) ?: continue
        if (paletteFilter != null && paletteUuid !in paletteFilter) continue

        val canonical = canonicalPropertyName(row.propertyName)
        val target = row.target
        val members: List<Fixture> = when (target) {
            is TargetRef.Fixture ->
                listOfNotNull(runCatching { fixtures.untypedFixture(target.key) }.getOrNull())
            is TargetRef.Group -> runCatching {
                fixtures.untypedGroup(target.key).fixtures.filterIsInstance<Fixture>()
            }.getOrNull().orEmpty()
        }
        if (members.isEmpty()) {
            unresolved++
            continue
        }
        if (!maskAllows(mask, maskGroupForProperty(members.first(), canonical))) continue

        // Resolve every member; a member that can't resolve contributes nothing.
        //
        // [fixtureCategoryFor], not a raw property lookup: `position` is a synthetic pan/tilt
        // pair with no `@FixtureProperty` of its own, so looking the name up directly answers
        // null and every POSITION palette ref would be reported as unresolvable.
        val resolvedByKey = LinkedHashMap<String, String>()
        for (member in members) {
            val category = fixtureCategoryFor(member, canonical)?.first ?: continue
            val resolution = resolveAssignmentValueForFixture(
                registry, member.key, canonical, category, row.value,
            )
            resolution.value?.let { resolvedByKey[member.key] = it.serialize() }
        }
        if (resolvedByKey.isEmpty()) {
            unresolved++
            continue
        }

        val literals = resolvedByKey.values.toSet()
        if (target is TargetRef.Fixture || literals.size == 1) {
            // One value covers every member, so the row keeps its shape.
            row.value = literals.first()
            converted++
        } else {
            // Members disagree: no single literal can say what the group row said.
            val sortOrder = row.sortOrder
            val fadeDurationMs = row.fadeDurationMs
            val moveInDark = row.moveInDark
            row.delete()
            resolvedByKey.forEach { (fixtureKey, literal) ->
                DaoCuePropertyAssignment.new {
                    this.cue = cue
                    targetType = TargetRef.Fixture.TYPE
                    targetKey = fixtureKey
                    propertyName = canonical
                    value = literal
                    this.sortOrder = sortOrder
                    this.fadeDurationMs = fadeDurationMs
                    this.moveInDark = moveInDark
                }
            }
            converted++
            groupRowsExpanded++
        }
    }

    return HardenOutcome(converted, groupRowsExpanded, unresolved)
}
