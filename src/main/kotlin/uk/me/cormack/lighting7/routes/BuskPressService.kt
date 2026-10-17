package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoBuskPad
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import uk.me.cormack.lighting7.fx.takeLooseCue

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
        /**
         * [kind] is `TEMPLATE`, `LOOK` or `CUE`; [action] is `"applied"` or `"removed"`.
         * [skippedFamilies] is a Look's families the selection's mask left out (declaration
         * order); empty for every other kind, an unmasked press, and the off arm.
         */
        data class Pressed(
            val kind: String,
            val action: String,
            val effectCount: Int,
            val released: Int,
            val skippedFamilies: List<String> = emptyList(),
        ) : Outcome

        /** The pad, its record or its page is gone — or the pad is malformed, which reads the same. */
        data object NotFound : Outcome

        /**
         * A press that could not be made: a generic template or a deferred-effect Look with
         * nothing selected, or a record the selection's mask leaves nothing of
         * (`PALETTE_OUTSIDE_MASK` / `LOOK_OUTSIDE_MASK`).
         */
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
                    family = t.familyOf(),
                    isGeneric = t.isGenericTemplate(),
                )
            }
            BuskPadKind.LOOK -> PressRecord.Look(pad.look!!.toggleSource(state.show.fixtures))
            // A cue pad names the cue and holds it loose (cue model plan D6): no stack, no entry.
            BuskPadKind.CUE -> PressRecord.Cue(pad.cue!!.id.value)
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
            projectId = projectId,
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
                .map { it.cue!!.id.value }
                .filter { it != ownCue }
                .distinct(),
        )
    }

    /**
     * Apply [plan] with [targets] as the press's selection and [families] as its attribute mask.
     *
     * Runs outside any transaction: it touches the engine, not the database. [beatDivision] is the
     * screen's only extra — hardware has no way to send one — and is null for a surface press.
     * [fadeMs] is the screen's programmer fade (effects plan D13), 0 for a surface press; a cue pad's
     * loose hold ignores it, as a loose hold has no fade.
     *
     * [families] null is every attribute. Under a mask (multi-screen plan D5, table in §3.3): a
     * **template** whose family is outside it is refused by name — a template's layer is already
     * masked to its own family, so the intersection is the family itself or nothing; a **Look**
     * lands masked to `mask ∩ look.families` and reports what it skipped, or is refused when
     * nothing is inside ([resolveLookMask]); a **cue** ignores it, having no targets to be masked
     * on. The mask rides the press rather than being read from the desk fact here (D4), because an
     * unlinked window's targets are not the desk's and its mask should not be either.
     */
    fun apply(
        state: State,
        plan: PressPlan,
        targets: List<CueTargetDto>,
        families: Set<PropertyMaskGroup>? = null,
        beatDivision: Double? = null,
        fadeMs: Long = 0,
    ): Outcome {
        val holds = state.show.cueStackManager.holds
        val stack = state.show.programmerLayerStack

        /**
         * Let go of every cue sibling's loose hold; returns how many. Only an *on* press calls this.
         * A sibling a stack holds stays on stage — the solo bank owns its pads' loose holds, not a
         * running stack (cue model plan D11).
         */
        fun stopCueSiblings(): Int = plan.cueSiblings.count { cueId ->
            holds.looseHeld(cueId).also { held -> if (held) holds.dropLoose(state, cueId) }
        }

        return when (val record = plan.record) {
            is PressRecord.Layer -> {
                var lookPress: LookPressResolution.Ready? = null
                val pressTargets = when (record) {
                    is PressRecord.Template -> {
                        // A generic template's rows take their targets from the press, so with
                        // none the layer would assert nothing while lighting the pad — the same
                        // dead-pad reading the Look arm refuses. A per-fixture template names
                        // its own heads and lands on them.
                        if (record.isGeneric && targets.isEmpty()) {
                            return Outcome.Refused(
                                "This palette needs a selection to press onto",
                                CODE_TEMPLATE_NEEDS_SELECTION,
                            )
                        }
                        targets.map { CueTargetDto(it.type, it.key) }
                    }
                    // A Look's targets and mask are decided arm first (`resolveLookPress`): an off
                    // press comes off under any mask, and whatever its targets.
                    is PressRecord.Look ->
                        when (val resolved = resolveLookPress(targets, record.look, families) { pressWouldRelease(state, record.source.uuid, it) }) {
                            is LookPressResolution.Refused -> return Outcome.Refused(resolved.message, resolved.code)
                            is LookPressResolution.Ready -> resolved.also { lookPress = it }.targets
                        }
                }
                // The same dead-pad reading under a mask: a layer the cook would skip whole is
                // refused rather than lit — on the *on* arm only (`pressWouldRelease`).
                if (record is PressRecord.Template && families != null && !maskAllows(families, record.family) &&
                    !pressWouldRelease(state, record.source.uuid, pressTargets)
                ) {
                    return Outcome.Refused(
                        templateOutsideMaskMessage(record.source.name, record.family, families),
                        CODE_TEMPLATE_OUTSIDE_MASK,
                    )
                }
                try {
                    val outcome = stack.toggle(
                        source = record.source,
                        targets = pressTargets,
                        propertyMask = (record as? PressRecord.Template)?.family?.name ?: lookPress?.propertyMask,
                        beatDivisionOverride = beatDivision,
                        releaseSiblings = plan.layerSiblings,
                        fadeMs = fadeMs,
                    )
                    val stopped = if (outcome.action == "applied") stopCueSiblings() else 0
                    // The busk-pad door of the press log. Templates only — a Look has no recents
                    // row — and only on the arm that put it on, since a release is not a press
                    // (`TemplatePressLog`). A refusal above has already returned.
                    if (record is PressRecord.Template && outcome.action == "applied") {
                        TemplatePressLog.record(state, plan.projectId, record.source.id)
                    }
                    Outcome.Pressed(
                        record.kind.name,
                        outcome.action,
                        outcome.effectCount,
                        outcome.released + stopped,
                        // Skips belong to the arm that put the layer on: an off press narrowed or
                        // dropped a layer, and there was nothing to skip.
                        skippedFamilies = if (outcome.action == "applied") lookPress?.skippedFamilies.orEmpty() else emptyList(),
                    )
                } catch (e: IllegalStateException) {
                    Outcome.TargetMissing(e.message ?: "Target not found")
                }
            }
            is PressRecord.Cue -> {
                // A press always toggles the cue's loose hold (D11): a cue live only through a stack
                // takes one, which puts it on top of every stack (D10); a second press lets it go,
                // leaving whatever stack also holds it on stage.
                if (!holds.looseHeld(record.cueId)) {
                    val released = stack.release(plan.layerSiblings)
                    val stopped = stopCueSiblings()
                    val press = holds.takeLooseCue(state, record.cueId)
                        ?: return Outcome.NotFound
                    Outcome.Pressed(BuskPadKind.CUE.name, "applied", press.acquired.effectCount, released + stopped)
                } else {
                    val removed = holds.dropLoose(state, record.cueId).effectsRemoved
                    Outcome.Pressed(BuskPadKind.CUE.name, "removed", removed, 0)
                }
            }
        }
    }

    /** [plan] then [apply], for a caller with nothing to say between the two. */
    fun pressByUuid(
        state: State,
        projectId: Int,
        padUuid: UUID,
        targets: List<CueTargetDto>,
        families: Set<PropertyMaskGroup>? = null,
    ): Outcome = planByUuid(state, projectId, padUuid)?.let { apply(state, it, targets, families) } ?: Outcome.NotFound
}

/** What the one transaction read about the pressed pad. */
internal data class PressPlan(
    /**
     * The project the pad was read under — already checked against the page's own, so it is the
     * pad's project and not merely the caller's belief about it.
     *
     * Carried so [BuskPressService.apply] can record a template press without a second read: the
     * press log scopes its update by project, and `apply` runs outside any transaction.
     */
    val projectId: Int,
    val record: PressRecord,
    /** Template and Look uuids on the sibling pads — what `toggle` / `release` take off. */
    val layerSiblings: Set<UUID>,
    /** The cue on every sibling pad. */
    val cueSiblings: List<Int>,
)

internal sealed interface PressRecord {
    sealed interface Layer : PressRecord {
        val source: LayerSource
        val kind: BuskPadKind
    }

    /** [family] is the template's own (`familyOf`), the mask its layer carries and the one a selection mask tests. */
    data class Template(override val source: LayerSource, val family: PropertyMaskGroup?, val isGeneric: Boolean) : Layer {
        override val kind get() = BuskPadKind.TEMPLATE
    }

    data class Look(val look: LookToggleSource) : Layer {
        override val source get() = look.source
        override val kind get() = BuskPadKind.LOOK
    }

    /** A cue pad's cue, held loose by a press (cue model plan D6) — it names no stack. */
    data class Cue(val cueId: Int) : PressRecord
}
