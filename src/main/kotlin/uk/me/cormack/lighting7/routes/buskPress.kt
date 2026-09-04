package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private const val PAD_NOT_FOUND = "Busk pad not found"

/** 400 code: a generic template's rows take their targets from the press, so with none it asserts nothing. */
internal const val CODE_TEMPLATE_NEEDS_SELECTION = "TEMPLATE_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/busk/pads/{padId}/press` — the busk view's one gesture.
 *
 * A press goes through the pad, and **the bank decides the siblings** (busk-layout plan D4): the
 * pad, its record and — when the bank is solo — the records on its sibling pads are read in one
 * transaction, exactly as the template toggle reads its group, so a page rewritten by another
 * client mid-press cannot release the wrong set. Then, by kind:
 *
 * - a **template** → `ProgrammerLayerStack.toggle` with its derived family mask and the siblings;
 * - a **Look** → the same `toggle`, unmasked, with the siblings — and the empty-targets rule the
 *   Look toggle route has (`resolveLookToggleTargets`);
 * - a **cue** → apply / stop through `CueStackManager`, exactly as a cue slot presses: a toggle,
 *   lit from the stack's `activeCueId`, live without being the playhead. Never the playhead's GO.
 *
 * **Solo has one meaning for every kind** (D6): pressing one *on* turns its siblings off. A layer
 * sibling under a layer press is narrowed on the pressed heads by `toggle`'s own `releaseSiblings`
 * rule; a cue sibling that is live is stopped; and a cue press takes its layer siblings off
 * *wholesale* through [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.release], because a cue has
 * no targets to narrow by. An **off** press releases nothing, whatever the kind. A stacking bank
 * (solo off) has no siblings at all.
 *
 * The engine's rules do not move here: `/templates/{id}/toggle` and `/looks/{id}/toggle` remain
 * for the programmer's ⌥click strip and the AI, always siblingless.
 */
internal fun Route.routeApiRestBuskPress(state: State) {
    post<BuskPadPressResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot press busk pads in project '${p.name}' - only the current project is live" },
        ) { project ->
            val request = call.receive<BuskPressRequest>()
            val plan = transaction(state.database) {
                val pad = DaoBuskPad.findById(resource.padId) ?: return@transaction null
                val bank = pad.bank
                if (bank.column.page.project.id != project.id) return@transaction null
                val record = when (pad.kind) {
                    BuskPadKind.TEMPLATE -> pad.template!!.let { t ->
                        val rows = t.rows.toList()
                        PressRecord.Template(
                            source = LayerSource.template(t.id.value, t.uuid, t.name),
                            family = t.familyOf()?.name,
                            // The `TemplateDto.isGeneric` expression: an effect template is always
                            // generic; a value template is when every row defers its target.
                            isGeneric = t.effect != null || (rows.isNotEmpty() && rows.all { it.isDeferred }),
                        )
                    }
                    BuskPadKind.LOOK -> PressRecord.Look(pad.look!!.toggleSource(state.show.fixtures))
                    BuskPadKind.CUE -> pad.cue!!.let { c -> PressRecord.Cue(c.id.value, c.cueStack.id.value) }
                    // A malformed pad is absent everywhere it is read, this route included.
                    null -> return@transaction null
                }
                // The siblings, read beside the source, each through `kind` so a malformed sibling
                // is as absent here as on every other read. A record sitting on two pads of one
                // bank is not its own sibling, so the pressed record's identity is taken out of
                // both sets.
                val siblings = if (bank.solo) {
                    DaoBuskPad.find { (DaoBuskPads.bank eq bank.id) and (DaoBuskPads.id neq pad.id) }.toList()
                } else {
                    emptyList()
                }
                val ownUuid = (record as? PressRecord.Layer)?.source?.uuid
                val ownCue = (record as? PressRecord.Cue)?.cueId
                PressPlan(
                    record = record,
                    layerSiblings = siblings
                        .mapNotNullTo(HashSet()) { sibling ->
                            when (sibling.kind) {
                                BuskPadKind.TEMPLATE -> sibling.template!!.uuid
                                BuskPadKind.LOOK -> sibling.look!!.uuid
                                BuskPadKind.CUE, null -> null
                            }
                        }
                        .also { it.remove(ownUuid) },
                    cueSiblings = siblings
                        .filter { it.kind == BuskPadKind.CUE }
                        .map { it.cue!! }
                        .filter { it.id.value != ownCue }
                        .map { it.id.value to it.cueStack.id.value }
                        .distinct(),
                )
            }
            if (plan == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(PAD_NOT_FOUND))
                return@withCurrentProject
            }

            val manager = state.show.cueStackManager
            val stack = state.show.programmerLayerStack

            /** Stop every cue sibling that is live; returns how many. Only an *on* press calls this. */
            fun stopCueSiblings(): Int = plan.cueSiblings.count { (cueId, stackId) ->
                (manager.getActiveCueId(stackId) == cueId).also { live -> if (live) manager.deactivateStack(stackId, state) }
            }

            when (val record = plan.record) {
                is PressRecord.Layer -> {
                    val targets = when (record) {
                        is PressRecord.Template -> {
                            // A generic template's rows take their targets from the press, so with
                            // none the layer would assert nothing while lighting the pad — the same
                            // dead-pad reading the Look arm refuses. A per-fixture template names
                            // its own heads and lands on them.
                            if (record.isGeneric && request.targets.isEmpty()) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ErrorResponse("This template needs a selection to press onto", code = CODE_TEMPLATE_NEEDS_SELECTION),
                                )
                                return@withCurrentProject
                            }
                            request.targets.map { CueTargetDto(it.type, it.key) }
                        }
                        is PressRecord.Look -> when (val resolved = resolveLookToggleTargets(request.targets, record.look)) {
                            is LookTargetResolution.Refused -> {
                                call.respond(HttpStatusCode.BadRequest, ErrorResponse(resolved.message, code = resolved.code))
                                return@withCurrentProject
                            }
                            is LookTargetResolution.Targets -> resolved.targets
                        }
                    }
                    try {
                        val outcome = stack.toggle(
                            source = record.source,
                            targets = targets,
                            propertyMask = (record as? PressRecord.Template)?.family,
                            beatDivisionOverride = request.beatDivision,
                            releaseSiblings = plan.layerSiblings,
                        )
                        val stopped = if (outcome.action == "applied") stopCueSiblings() else 0
                        call.respond(BuskPressResponse(record.kind.name, outcome.action, outcome.effectCount, outcome.released + stopped))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
                    }
                }
                is PressRecord.Cue -> {
                    val isLive = manager.getActiveCueId(record.stackId) == record.cueId
                    if (!isLive) {
                        // Siblings first, then the cue: a sibling in the *same* stack is stopped by
                        // deactivating that stack, which must not take down the cue just applied.
                        val released = stack.release(plan.layerSiblings)
                        val stopped = stopCueSiblings()
                        try {
                            val result = manager.activateCueInStack(state, record.stackId, record.cueId)
                            call.respond(BuskPressResponse(BuskPadKind.CUE.name, "applied", result.effectCount, released + stopped))
                        } catch (e: IllegalArgumentException) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to apply cue"))
                        }
                    } else {
                        // Live means the stack manager holds this stack with this cue as its live
                        // one, so the stop is the stack coming down whole — the branch the cue-slot
                        // stop takes for an active stack. (Its other branch, for a cue live outside
                        // the manager, cannot be reached from here: `isLive` already read the
                        // manager's own record.)
                        val removed = manager.deactivateStack(record.stackId, state)
                        call.respond(BuskPressResponse(BuskPadKind.CUE.name, "removed", removed, 0))
                    }
                }
            }
        }
    }
}

@Resource("/{projectId}/busk/pads/{padId}/press")
internal data class BuskPadPressResource(val projectId: String, val padId: Int)

/** The selection. Ignored for a cue pad; may be empty for a Look with no deferred effect. */
@Serializable
internal data class BuskPressRequest(
    val targets: List<CueTargetDto> = emptyList(),
    val beatDivision: Double? = null,
)

@Serializable
internal data class BuskPressResponse(
    /** `TEMPLATE`, `LOOK` or `CUE`. */
    val kind: String,
    /** `"applied"` or `"removed"`. */
    val action: String,
    val effectCount: Int,
    /**
     * What an *on* press in a solo bank turned off: layer siblings narrowed or dropped, plus cue
     * siblings stopped. Always 0 for an off press and in a stacking bank.
     */
    val released: Int = 0,
)

/** What the one transaction read about the pressed pad. */
private data class PressPlan(
    val record: PressRecord,
    /** Template and Look uuids on the sibling pads — what `toggle` / `release` take off. */
    val layerSiblings: Set<UUID>,
    /** `(cueId, stackId)` for every cue on a sibling pad. */
    val cueSiblings: List<Pair<Int, Int>>,
)

private sealed interface PressRecord {
    sealed interface Layer : PressRecord {
        val source: LayerSource
        val kind: BuskPadKind
    }

    data class Template(override val source: LayerSource, val family: String?, val isGeneric: Boolean) : Layer {
        override val kind get() = BuskPadKind.TEMPLATE
    }

    data class Look(val look: LookToggleSource) : Layer {
        override val source get() = look.source
        override val kind get() = BuskPadKind.LOOK
    }

    data class Cue(val cueId: Int, val stackId: Int) : PressRecord
}
