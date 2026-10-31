package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.CueEntryRef
import uk.me.cormack.lighting7.fx.HeadIntensity
import uk.me.cormack.lighting7.fx.MoveInDarkPlanner
import uk.me.cormack.lighting7.fx.buildCueApplyDataForCues
import uk.me.cormack.lighting7.fx.cueContent
import uk.me.cormack.lighting7.fx.cueMoveInDarkSettingsOf
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.entriesOf
import uk.me.cormack.lighting7.models.moveInDarkSettings
import uk.me.cormack.lighting7.state.State

/**
 * A stack's static Move in dark plan (move-in-dark plan §3): what its stored list does, entry by
 * entry, with no other stack running — the heads moved in the dark for each cue, and those that move
 * lit and why — and the stack sheet's summary. Stored data, so any project and both roles; the plan
 * reads the running rig, so a project that is not current answers every entry empty with
 * `rigLoaded: false`.
 */
internal fun Route.routeApiRestCueStackMoveInDark(state: State) {
    get<CueStackMoveInDarkResource> { resource ->
        withProject(state, resource.parent.parent.projectId) { project ->
            val current = state.isCurrentProject(project)
            val plan = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.parent.stackId) ?: return@transaction null
                if (stack.project.id != project.id) return@transaction null
                stackMoveInDarkPlan(state, stack, rigLoaded = current)
            }
            if (plan == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue stack not found"))
            } else {
                call.respond(plan)
            }
        }
    }
}

/**
 * [stack]'s static plan; [rigLoaded] is whether `state.show`'s rig is [stack]'s project's. Must run
 * inside a transaction.
 */
internal fun stackMoveInDarkPlan(state: State, stack: DaoCueStack, rigLoaded: Boolean): StackMoveInDarkPlanDto {
    val entries = entriesOf(stack).filter { !it.isMarker && it.cueId != null }
    val ordered = entries.map { CueEntryRef(it.id.value, it.cueId!!) }
    val cueIds = ordered.map { it.cueId }.toSet()
    val settings = cueMoveInDarkSettingsOf(cueIds)
    val planned = if (!rigLoaded || ordered.isEmpty()) {
        ordered.map { MoveInDarkPlanner.StaticEntry(it.entryId, it.cueId, emptyList(), emptyList()) }
    } else {
        val heads = HeadIntensity.classify(state.show.fixtures)
        val applyData = buildCueApplyDataForCues(cueIds)
        val contents = HashMap<Int, MoveInDarkPlanner.CueContent>()
        val follows = entries.associate { entry ->
            entry.id.value to entry.autoAdvanceDelay?.toMillis()?.takeIf { entry.autoAdvance }
        }
        MoveInDarkPlanner.staticPlan(
            MoveInDarkPlanner.StaticInput(
                settings = stack.moveInDarkSettings,
                ordered = ordered,
                loop = stack.loop,
                heads = heads,
                content = { cueId ->
                    contents.getOrPut(cueId) {
                        val data = applyData[cueId] ?: return@getOrPut MoveInDarkPlanner.CueContent(emptyList())
                        cueContent(state, cueId, data, heads, settings[cueId] ?: CueMoveInDarkSettings.DEFAULT)
                    }
                },
                followMs = { follows[it] },
                fadeMs = { applyData[it]?.fadeDurationMs ?: 0L },
            ),
        )
    }
    val dtos = planned.map { entry ->
        StackMoveInDarkEntryDto(
            entryId = entry.entryId,
            cueId = entry.cueId,
            heads = entry.moves.map { MoveInDarkHeadPlanDto(it.head, it.fromEntryId, it.families.sortedBy { f -> f.ordinal }.map { f -> f.name }) },
            live = entry.live.map { MoveInDarkLiveHeadDto(it.head, it.reason.name) },
        )
    }
    return StackMoveInDarkPlanDto(
        stackId = stack.id.value,
        rigLoaded = rigLoaded,
        entries = dtos,
        summary = MoveInDarkSummaryDto(
            heads = dtos.sumOf { it.heads.size },
            cues = dtos.count { it.heads.isNotEmpty() },
            live = dtos.sumOf { it.live.size },
            off = ordered.count { settings[it.cueId]?.mode == CueMoveInDarkMode.OFF },
        ),
    )
}

@Resource("/move-in-dark")
data class CueStackMoveInDarkResource(val parent: ProjectCueStackResource)

/** `GET /cue-stacks/{id}/move-in-dark`. */
@Serializable
data class StackMoveInDarkPlanDto(
    val stackId: Int,
    /** False when the project is not current: the plan reads the running rig, so every entry is empty. */
    val rigLoaded: Boolean,
    /** Every cue entry in play order, markers left out. */
    val entries: List<StackMoveInDarkEntryDto>,
    val summary: MoveInDarkSummaryDto,
)

/** One entry's cue: the heads moved in the dark for it, and the heads that move lit into it. */
@Serializable
data class StackMoveInDarkEntryDto(
    val entryId: Int,
    val cueId: Int,
    val heads: List<MoveInDarkHeadPlanDto>,
    val live: List<MoveInDarkLiveHeadDto>,
)

/** A head moved in the dark: the entry its move happens in, and the families it moves. */
@Serializable
data class MoveInDarkHeadPlanDto(val target: String, val fromEntryId: Int, val families: List<String>)

/** A head that moves lit, and why: `LIT_BEFORE | TOO_QUICK | KEPT_LIVE | CUE_OFF`. */
@Serializable
data class MoveInDarkLiveHeadDto(val target: String, val reason: String)

/**
 * The stack sheet's read-out: [heads] moves in the dark across [cues] cues, [live] heads that move
 * lit, and [off] cue entries set *Off*.
 */
@Serializable
data class MoveInDarkSummaryDto(val heads: Int, val cues: Int, val live: Int, val off: Int)
