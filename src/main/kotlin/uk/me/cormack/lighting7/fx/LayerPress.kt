package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.sameTargets
import java.util.UUID

/**
 * A press of a Look or a palette, as a pure function over an ordered layer list: what the list
 * becomes, and which arm the press was on. No store, no cook, no effects — the caller installs the
 * list and recooks it. [ProgrammerLayerStack.toggle] is the programmer's caller; a stored cue's
 * layer list is the other stack it is written for (apply-model plan §3.6), so the two cannot drift.
 *
 * A **Look** is pressed whole (absolute-looks plan P1, [Ops.whole]): on puts one layer of it on
 * top, off takes every layer of it off, and nothing is ever narrowed off it or widened onto it.
 *
 * A **palette**'s rules are all per **head** ([TargetCoverage]), and each documented where it bites
 * on [ProgrammerLayerStack.toggle]:
 *
 * - **Off.** The record already covers every pressed head (or, for a press naming no targets, has
 *   a layer naming none): the pressed heads come off every layer of the record.
 * - **On.** The pressed heads come off the record's own layers, off every [siblings] layer (a solo
 *   bank's) and off every [replaces] layer (the palettes of the pressed one's family and kind,
 *   apply-model D2/D6), and the arriving layer goes on top.
 * - **Widening** (apply-model P3). When the record's newest layer is the top of the list once the
 *   press's own replacements are made, and carries the arriving layer's settings
 *   ([Ops.sameSettings]), the press widens it — keeping its identity — rather than adding one. Only
 *   the top, so a widening never changes what a higher layer shows.
 *
 * A solo bank's siblings follow P3: a Look sibling cannot be narrowed, so any press takes it off
 * whole, and a Look press takes every sibling off whole, as a cue press does.
 *
 * A palette layer naming **no** targets is the palette's own bound rows. Where the caller knows
 * their heads ([Ops.boundHeads] — a per-fixture palette's rows) it is read as naming them, and a
 * subtraction rewrites it to the rest. Where it does not, nothing here subtracts from it — except a
 * solo sibling or the record itself under a press that also names none, which is the same gesture
 * and replaces it.
 */
internal object LayerPress {
    /** How a press reads and rewrites one kind of layer. */
    interface Ops<L> {
        fun sourceUuid(layer: L): UUID
        fun targets(layer: L): List<CueTargetDto>
        fun withTargets(layer: L, targets: List<CueTargetDto>): L

        /** Whether [layer] carries every setting a fresh press would give [arriving], targets aside. */
        fun sameSettings(layer: L, arriving: L): Boolean

        /** Whether [layer] is a Look's: pressed, released and taken off whole, never by head. */
        fun whole(layer: L): Boolean

        /** The heads a layer naming no targets lands on, when its rows name them; null when unknown. */
        fun boundHeads(layer: L): List<CueTargetDto>? = null
    }

    sealed interface Result<L> {
        /** The list after the press, in stack order (bottom first), not renumbered. */
        val layers: List<L>

        data class Removed<L>(override val layers: List<L>) : Result<L>

        /** [released] counts the **other** records' layers the press narrowed or dropped. */
        data class Applied<L>(override val layers: List<L>, val released: Int) : Result<L>
    }

    /**
     * [replacesOn] is the heads the [replaces] layers give up — the pressed heads the palette lands
     * on, which for a per-fixture palette is fewer than the press names. Null is every pressed head.
     */
    fun <L> press(
        layers: List<L>,
        arriving: L,
        ops: Ops<L>,
        coverage: TargetCoverage,
        siblings: Set<UUID> = emptySet(),
        replaces: Set<UUID> = emptySet(),
        replacesOn: Set<CueTargetDto>? = null,
    ): Result<L> {
        val uuid = ops.sourceUuid(arriving)
        if (ops.whole(arriving)) return pressWhole(layers, arriving, uuid, ops, siblings)
        val targets = ops.targets(arriving)
        val pressed = coverage.expand(targets).toSet()

        when (val arm = arm(layers, uuid, targets, pressed, ops, coverage)) {
            is Arm.Twin -> return Result.Removed(layers.filterNot { it === arm.layer })
            Arm.Covered -> return Result.Removed(
                layers.mapNotNull { if (ops.sourceUuid(it) == uuid) without(it, pressed, ops, coverage) else it },
            )
            Arm.On -> Unit
        }

        var released = 0
        val rewritten = layers.map { layer ->
            val layerUuid = ops.sourceUuid(layer)
            val mine = layerUuid == uuid
            val next = when {
                // A Look sibling cannot be narrowed, so it comes off whole (P3).
                layerUuid in siblings && ops.whole(layer) -> null
                mine || layerUuid in siblings ->
                    if (held(layer, ops).isEmpty()) layer.takeUnless { targets.isEmpty() }
                    else without(layer, pressed, ops, coverage)
                layerUuid in replaces -> without(layer, replacesOn ?: pressed, ops, coverage)
                else -> layer
            }
            // A rewrite of the record's own layer is the press tidying up after itself, not a release.
            if (next !== layer && !mine) released++
            next
        }

        // Widening reads the record's newest layer as it stood, not as the subtraction left it: a
        // press that grows the selection names heads that layer already holds, so the subtraction
        // empties it, and dropping it would put a new layer on and restart its effects on those
        // heads. The layer keeps its identity, taking what the subtraction left plus the press.
        val newest = layers.indexOfLast { ops.sourceUuid(it) == uuid }
        val top = layers.getOrNull(newest)
        if (top != null && targets.isNotEmpty() && held(top, ops).isNotEmpty() && ops.sameSettings(top, arriving) &&
            (newest + 1 until layers.size).all { rewritten[it] == null }
        ) {
            val kept = rewritten[newest]?.let { held(it, ops) }.orEmpty()
            val widened = ops.withTargets(top, (kept + targets).distinct())
            return Result.Applied(rewritten.take(newest).filterNotNull() + widened, released)
        }
        return Result.Applied(rewritten.filterNotNull() + arriving, released)
    }

    /**
     * A Look's press (P1, P3): off when any layer of it is on, taking every one; else on top, with
     * every [siblings] layer taken off whole.
     */
    private fun <L> pressWhole(layers: List<L>, arriving: L, uuid: UUID, ops: Ops<L>, siblings: Set<UUID>): Result<L> {
        if (layers.any { ops.sourceUuid(it) == uuid }) return Result.Removed(layers.filterNot { ops.sourceUuid(it) == uuid })
        val kept = layers.filterNot { ops.sourceUuid(it) in siblings }
        return Result.Applied(kept + arriving, layers.size - kept.size)
    }

    /**
     * Whether a press of [sourceUuid] on [targets] would take the record **off** [layers] — asked
     * by a door before the press, to test the selection's mask on the on arm only
     * (`routes/pressArm.kt`). The same decision [press] makes, so the arm a door tests is the arm
     * the press takes. A Look's press is off whenever a layer of it is on.
     */
    fun <L> wouldRelease(layers: List<L>, sourceUuid: UUID, targets: List<CueTargetDto>, ops: Ops<L>, coverage: TargetCoverage): Boolean =
        layers.any { ops.sourceUuid(it) == sourceUuid && ops.whole(it) } ||
            arm(layers, sourceUuid, targets, coverage.expand(targets).toSet(), ops, coverage) != Arm.On

    private sealed interface Arm {
        data object On : Arm

        /** A press naming no targets, and a layer of the record naming none: the same gesture. */
        class Twin(val layer: Any?) : Arm

        /** The record already covers every pressed head. */
        data object Covered : Arm
    }

    /** [pressed] is [targets] expanded to heads. */
    private fun <L> arm(
        layers: List<L>,
        uuid: UUID,
        targets: List<CueTargetDto>,
        pressed: Set<CueTargetDto>,
        ops: Ops<L>,
        coverage: TargetCoverage,
    ): Arm {
        val own = layers.filter { ops.sourceUuid(it) == uuid }
        if (pressed.isEmpty()) {
            val twin = own.firstOrNull { sameTargets(ops.targets(it), targets) } ?: return Arm.On
            return Arm.Twin(twin)
        }
        // The union over every layer of the record: two presses that each covered half the
        // selection add up to a lit pad, so the press after them reads as "off".
        val covered = own.flatMapTo(HashSet()) { coverage.expand(held(it, ops)) }
        return if (pressed.all { coverage.covers(covered, it) }) Arm.Covered else Arm.On
    }

    /**
     * What [layer] names: its targets, else the heads its bound rows land on, else nothing. A Look's
     * names nothing, whatever an older write left on it, so no head is ever taken off it.
     */
    private fun <L> held(layer: L, ops: Ops<L>): List<CueTargetDto> =
        if (ops.whole(layer)) emptyList() else ops.targets(layer).ifEmpty { ops.boundHeads(layer).orEmpty() }

    /**
     * [layer] with [pressed] taken off: itself (by identity) when the press names none of its heads
     * or it names nothing [held] can read, a narrowed copy when it names some, null when it names
     * all of them.
     */
    private fun <L> without(layer: L, pressed: Set<CueTargetDto>, ops: Ops<L>, coverage: TargetCoverage): L? {
        val held = held(layer, ops)
        if (held.isEmpty() || pressed.isEmpty()) return layer
        val remaining = coverage.narrow(held, pressed)
        return when {
            remaining === held -> layer
            remaining.isEmpty() -> null
            else -> ops.withTargets(layer, remaining)
        }
    }
}
