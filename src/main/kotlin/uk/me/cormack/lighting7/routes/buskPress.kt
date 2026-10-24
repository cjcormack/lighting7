package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.state.State

private const val PAD_NOT_FOUND = "Busk pad not found"

/** 400 code: a generic template's rows take their targets from the press, so with none it asserts nothing. */
internal const val CODE_PALETTE_NEEDS_SELECTION = "PALETTE_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/busk/pads/{padId}/press` — the busk view's one gesture.
 *
 * The press itself lives in [BuskPressService]; this is its HTTP door. It was one function until
 * session 4 of the midi-surface plan gave a control surface a `PressPad` binding: a hardware press
 * and a screen press of one pad have to be the same press, and a route body cannot be called from
 * the MIDI router. What moved out is the whole of the behaviour; what stayed is the mapping from
 * [BuskPressService.Outcome] to a status and a coded body.
 *
 * A press goes through the pad, and **the bank decides the siblings** (busk-layout plan D4): the
 * pad, its record and — when the bank is solo — the records on its sibling pads are read in one
 * transaction, exactly as the template toggle reads its group, so a page rewritten by another
 * client mid-press cannot release the wrong set. Then, by kind:
 *
 * - a **template** → `ProgrammerLayerStack.toggle` with its derived family mask and the siblings,
 *   refused by name when the selection's mask leaves that family out (`PALETTE_OUTSIDE_MASK`) —
 *   on the on arm only, an off press coming off under any mask (`pressWouldRelease`);
 * - a **Look** → the same `toggle`, whole (absolute-looks plan P1): no targets and no mask, whatever
 *   the press carries, on taking every sibling off whole and off taking every layer of it off;
 * - a **cue** → apply / stop through `CueStackManager`, exactly as a cue slot presses: a toggle,
 *   lit from the stack's `activeCueId`, live without being the playhead. Never the playhead's GO.
 *
 * **Solo has one meaning for every kind** (D6): pressing one *on* turns its siblings off. A palette
 * sibling under a palette press is narrowed on the pressed heads by `toggle`'s own
 * `releaseSiblings` rule; a Look sibling, which cannot be narrowed, comes off whole, and a Look
 * press takes every layer sibling off whole (absolute-looks plan P3); a cue sibling that is live is
 * stopped; and a cue press takes its layer siblings off *wholesale* through
 * [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.release], because a cue has no targets to narrow
 * by. An **off** press releases nothing, whatever the kind. A stacking bank
 * (solo off) has no siblings at all.
 *
 * The engine's rules do not move here: `/palettes/{id}/toggle` and `/looks/{id}/toggle` remain
 * for the programmer's palette strip and the AI, with no solo siblings. A palette's family
 * replacement (apply-model D2) is `toggle`'s own, so it runs through every door alike.
 */
internal fun Route.routeApiRestBuskPress(state: State) {
    post<BuskPadPressResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot press busk pads in project '${p.name}' - only the current project is live" },
        ) { project ->
            val request = call.receive<BuskPressRequest>()
            val plan = BuskPressService.plan(state, project.id.value, resource.padId)
            if (plan == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PAD_NOT_FOUND))
                return@withCurrentProject
            }
            val families = try {
                if (plan.record is PressRecord.Palette) parseMaskGroups(request.families) else null
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            val outcome = BuskPressService.apply(
                state, plan, request.targets, families = families, cycle = request.cycle,
                fadeMs = request.fadeMs ?: 0,
            )
            when (outcome) {
                is BuskPressService.Outcome.Pressed -> call.respond(
                    BuskPressResponse(outcome.kind, outcome.action, outcome.fxCount, outcome.released),
                )
                is BuskPressService.Outcome.Refused ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                // A target that vanished between the read and the press is a 404 about the target,
                // not about the pad — the shape `toggle`'s IllegalStateException already carried.
                is BuskPressService.Outcome.TargetMissing ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(outcome.message))
                BuskPressService.Outcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(PAD_NOT_FOUND))
            }
        }
    }
}

@Resource("/{projectId}/busk/pads/{padId}/press")
internal data class BuskPadPressResource(val projectId: String, val padId: Int)

/**
 * The selection and its attribute mask, a palette pad's: both ignored, unread, for a cue or a Look
 * pad. [families] (`PropertyMaskGroup` names) absent is every attribute and an unknown name is 400;
 * a palette outside it is `PALETTE_OUTSIDE_MASK` — multi-screen plan D5.
 */
@Serializable
internal data class BuskPressRequest(
    val targets: List<CueTargetDto> = emptyList(),
    val cycle: Double? = null,
    val families: List<String>? = null,
    /**
     * The programmer fade, over which a press grows its effects and a release shrinks them (effects
     * plan D13), and which then covers every value too. Absent or 0: values at each row's own fade
     * on the way on and a snap on the way off, effects at once — the press as it was.
     */
    val fadeMs: Long? = null,
)

@Serializable
internal data class BuskPressResponse(
    /** `PALETTE`, `LOOK` or `CUE`. */
    val kind: String,
    /** `"applied"` or `"removed"`. */
    val action: String,
    val fxCount: Int,
    /**
     * What an *on* press turned off: layer siblings narrowed or dropped and cue siblings stopped in a
     * solo bank, plus the palettes of a pressed palette's family and kind it replaced (apply-model
     * D2). Always 0 for an off press.
     */
    val released: Int = 0,
    /** Always empty: no press is masked by family any more (a Look's lands whole). Kept on the wire until the client stops reading it. */
    val skippedFamilies: List<String> = emptyList(),
)
