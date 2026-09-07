package uk.me.cormack.lighting7.routes

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

/**
 * **A busk pad press, decided once.** The busk view's `POST /busk/pads/{padId}/press` and a
 * surface's `PressPad` binding are two ways of making one gesture, and the whole reason this is a
 * service rather than a route body is that the two must not diverge — the solo rules, the
 * empty-selection refusals and the cue toggle are the pad's behaviour, not the HTTP endpoint's.
 *
 * It answers an [Outcome] rather than responding, because only one of its two callers has a reply
 * channel: a MIDI press has nowhere to put a 400, so a refusal is a log line there and a coded
 * error here. That is also why the refusals carry their codes as data — the route maps them back to
 * the strings the client already branches on.
 *
 * The press itself is unchanged from the route it came out of, and its rules are documented at
 * [routeApiRestBuskPress].
 */
internal object BuskPressService {

    /** What a press did, or why it did nothing. */
    sealed interface Outcome {
        /** [kind] is `TEMPLATE`, `LOOK` or `CUE`; [action] is `"applied"` or `"removed"`. */
        data class Pressed(
            val kind: String,
            val action: String,
            val effectCount: Int,
            val released: Int,
        ) : Outcome

        /** The pad, its record or its page is gone — or the pad is malformed, which reads the same. */
        data object NotFound : Outcome

        /** A press that could not be made: a generic template or a deferred-effect Look with nothing selected. */
        data class Refused(val message: String, val code: String?) : Outcome

        /** A target the press named does not resolve in the live patch. */
        data class TargetMissing(val message: String) : Outcome
    }

    /**
     * Read a pad and everything its press depends on, in **one transaction** — the pad, its record
     * and, when its bank is solo, the records on its sibling pads. One read so a page rewritten by
     * another client mid-press cannot release the wrong set.
     *
     * Returns null for a pad that is not this project's, or whose row is malformed.
     */
    fun plan(state: State, projectId: Int, padId: Int): PressPlan? = transaction(state.database) {
        planInTransaction(state, projectId) { DaoBuskPad.findById(padId) }
    }

    /** [plan] addressed by uuid — what a binding carries, since an int id does not survive a clone. */
    fun planByUuid(state: State, projectId: Int, padUuid: UUID): PressPlan? = transaction(state.database) {
        planInTransaction(state, projectId) { DaoBuskPad.find { DaoBuskPads.uuid eq padUuid }.firstOrNull() }
    }

    private fun planInTransaction(state: State, projectId: Int, find: () -> DaoBuskPad?): PressPlan? {
        val pad = find() ?: return null
        val bank = pad.bank
        if (bank.column.page.project.id.value != projectId) return null
        val record = when (pad.kind) {
            BuskPadKind.TEMPLATE -> pad.template!!.let { t ->
                PressRecord.Template(
                    source = LayerSource.template(t.id.value, t.uuid, t.name),
                    family = t.familyOf()?.name,
                    isGeneric = t.isGenericTemplate(),
                )
            }
            BuskPadKind.LOOK -> PressRecord.Look(pad.look!!.toggleSource(state.show.fixtures))
            BuskPadKind.CUE -> pad.cue!!.let { c -> PressRecord.Cue(c.id.value, c.cueStack.id.value) }
            // A malformed pad is absent everywhere it is read, this route included.
            null -> return null
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
        return PressPlan(
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

    /**
     * Apply [plan] with [targets] as the press's selection.
     *
     * Runs outside any transaction: it touches the engine, not the database. [beatDivision] is the
     * screen's only extra — hardware has no way to send one — and is null for a surface press.
     */
    fun apply(
        state: State,
        plan: PressPlan,
        targets: List<CueTargetDto>,
        beatDivision: Double? = null,
    ): Outcome {
        val manager = state.show.cueStackManager
        val stack = state.show.programmerLayerStack

        /** Stop every cue sibling that is live; returns how many. Only an *on* press calls this. */
        fun stopCueSiblings(): Int = plan.cueSiblings.count { (cueId, stackId) ->
            (manager.getActiveCueId(stackId) == cueId).also { live -> if (live) manager.deactivateStack(stackId, state) }
        }

        return when (val record = plan.record) {
            is PressRecord.Layer -> {
                val pressTargets = when (record) {
                    is PressRecord.Template -> {
                        // A generic template's rows take their targets from the press, so with
                        // none the layer would assert nothing while lighting the pad — the same
                        // dead-pad reading the Look arm refuses. A per-fixture template names
                        // its own heads and lands on them.
                        if (record.isGeneric && targets.isEmpty()) {
                            return Outcome.Refused(
                                "This template needs a selection to press onto",
                                CODE_TEMPLATE_NEEDS_SELECTION,
                            )
                        }
                        targets.map { CueTargetDto(it.type, it.key) }
                    }
                    is PressRecord.Look -> when (val resolved = resolveLookToggleTargets(targets, record.look)) {
                        is LookTargetResolution.Refused -> return Outcome.Refused(resolved.message, resolved.code)
                        is LookTargetResolution.Targets -> resolved.targets
                    }
                }
                try {
                    val outcome = stack.toggle(
                        source = record.source,
                        targets = pressTargets,
                        propertyMask = (record as? PressRecord.Template)?.family,
                        beatDivisionOverride = beatDivision,
                        releaseSiblings = plan.layerSiblings,
                    )
                    val stopped = if (outcome.action == "applied") stopCueSiblings() else 0
                    Outcome.Pressed(record.kind.name, outcome.action, outcome.effectCount, outcome.released + stopped)
                } catch (e: IllegalStateException) {
                    Outcome.TargetMissing(e.message ?: "Target not found")
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
                        Outcome.Pressed(BuskPadKind.CUE.name, "applied", result.effectCount, released + stopped)
                    } catch (e: IllegalArgumentException) {
                        Outcome.Refused(e.message ?: "Failed to apply cue", null)
                    }
                } else {
                    // Live means the stack manager holds this stack with this cue as its live
                    // one, so the stop is the stack coming down whole — the branch the cue-slot
                    // stop takes for an active stack. (Its other branch, for a cue live outside
                    // the manager, cannot be reached from here: `isLive` already read the
                    // manager's own record.)
                    val removed = manager.deactivateStack(record.stackId, state)
                    Outcome.Pressed(BuskPadKind.CUE.name, "removed", removed, 0)
                }
            }
        }
    }

    /** [plan] then [apply], for a caller with nothing to say between the two. */
    fun pressByUuid(state: State, projectId: Int, padUuid: UUID, targets: List<CueTargetDto>): Outcome =
        planByUuid(state, projectId, padUuid)?.let { apply(state, it, targets) } ?: Outcome.NotFound
}

/** What the one transaction read about the pressed pad. */
internal data class PressPlan(
    val record: PressRecord,
    /** Template and Look uuids on the sibling pads — what `toggle` / `release` take off. */
    val layerSiblings: Set<UUID>,
    /** `(cueId, stackId)` for every cue on a sibling pad. */
    val cueSiblings: List<Pair<Int, Int>>,
)

internal sealed interface PressRecord {
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
