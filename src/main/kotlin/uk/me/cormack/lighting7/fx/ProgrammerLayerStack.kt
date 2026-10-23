package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.sameTargets
import uk.me.cormack.lighting7.models.withBeatDivisionOverride
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.models.layerTargets
import uk.me.cormack.lighting7.state.lookSceneryOf

private val logger = LoggerFactory.getLogger("ProgrammerLayerStack")

/**
 * One Look or template applied to the programmer, at a declared position in its stack.
 *
 * The in-memory twin of `DaoCueLayer`, minus the three timing columns: there is no trigger manager
 * for the programmer and a programmer layer is always immediate. Include drops a cue's timed layers
 * rather than pretending to hold them, and Update leaves them untouched.
 */
data class ProgrammerLayer(
    /** In-memory identity from [ProgrammerStore.mintLayerId]. **Not** a `DaoCueLayer` id. */
    val layerId: Int,
    /** What this layer applies — see [LayerSource]. */
    val source: LayerSource,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    val propertyMask: String? = null,
    val blendMode: String = "OVERRIDE",
    val amount: Double = 1.0,
    val stomp: Boolean = false,
    val speedMasterUuid: UUID? = null,
    val rateSpeedMasterUuid: UUID? = null,
    /**
     * The `DaoCueLayer` row this was minted from, when Include loaded it out of a cue. Update's
     * structural diff keys on this to tell an edited layer from a new one.
     */
    val sourceCueLayerId: Int? = null,
    /**
     * Overrides every effect's own beat division when this layer spawns them.
     *
     * Programmer-only, and deliberately not on `CookLayer`/`DaoCueLayer`: it exists because the
     * busking-pad toggle has always accepted one, and a cue layer has no equivalent gesture. No
     * shipping client sends it today; it is honoured rather than quietly dropped because the route
     * documents it.
     */
    val beatDivisionOverride: Double? = null,
) {
    /** What a cue row holds for this layer beside its source and place: the fields Update retunes. */
    fun cueFields() = CueLayerFields(enabled, targets, propertyMask, blendMode, amount, stomp, speedMasterUuid, rateSpeedMasterUuid)

    internal fun toCookLayer() = CookLayer(
        source = source,
        sortOrder = sortOrder,
        enabled = enabled,
        targets = targets,
        propertyMask = propertyMask,
        blendMode = blendMode,
        amount = amount,
        stomp = stomp,
        speedMasterUuid = speedMasterUuid,
        rateSpeedMasterUuid = rateSpeedMasterUuid,
        layerId = layerId,
    )
}

/**
 * A cue layer's fields beside its source and place — one list, so Update's writer
 * (`writeLayerStackIntoCue`) and the change count ([ProgrammerStore.layerChangesSinceInclude]) cannot
 * disagree about what a retune is.
 */
data class CueLayerFields(
    val enabled: Boolean,
    val targets: List<CueTargetDto>,
    val propertyMask: String?,
    val blendMode: String,
    val amount: Double,
    val stomp: Boolean,
    val speedMasterUuid: UUID?,
    val rateSpeedMasterUuid: UUID?,
)

/** What a recook moved, for the caller to report. */
data class ProgrammerLayerOutcome(
    val keysRepublished: Int,
    val effectsSpawned: Int,
    val effectsRetracted: Int,
    val effectsRepriorised: Int,
)

/**
 * What a pad press did — see [ProgrammerLayerStack.toggle].
 *
 * [action] is `"applied"` or `"removed"`, the two words the pads have always read. [effectCount] is
 * the effects that moved *for this layer* (spawned on apply, retracted on remove). [released] is
 * how many **other** records' layers an apply took the pressed targets off — a solo bank's
 * siblings, and the palettes of a pressed palette's family and kind — counting a layer narrowed to
 * the targets the press did not name as well as one dropped outright, since from the pressed
 * targets' point of view both are gone. Always 0 on a remove. A press rewriting one of its **own**
 * record's layers is not a release: that is the same pad extending or clearing itself.
 */
data class ToggleOutcome(
    val action: String,
    val effectCount: Int,
    val released: Int = 0,
)

/** What [ProgrammerLayerStack.detach] did: values written into Local, and effects that became Local. */
data class DetachOutcome(val written: Int, val effects: Int)

/** What [ProgrammerLayerStack.applyAsValues] did: values written into Local, and the Local effects it started. */
data class ApplyValuesOutcome(val written: Int, val effectIds: List<Long>)

/** What [ProgrammerLayerStack.resetEffectToTemplate] did. */
sealed interface ResetToTemplateOutcome {
    /** The instance now runs the template's current effect; [instance] is the one now live. */
    data class Reset(val instance: FxInstance) : ResetToTemplateOutcome

    /** No effect with that id is running. */
    data object NotFound : ResetToTemplateOutcome

    /** It is running but cannot be reset: [code] says why. */
    data class Refused(val code: String, val message: String) : ResetToTemplateOutcome

    companion object {
        /** No template layer in the programmer spawned this instance. */
        const val NOT_FROM_TEMPLATE = "FX_NOT_FROM_PALETTE"

        /** Its template no longer resolves — deleted, or emptied of its effect. */
        const val TEMPLATE_GONE = "FX_PALETTE_GONE"
    }
}

/** How much of one target a library record is applied to — see [ProgrammerLayerStack.appliedState]. */
enum class AppliedExtent {
    /** Every head the target names is covered. A fixture is only ever this or absent. */
    ALL,

    /** Some of a group's heads are covered and some are not. */
    SOME,
}

/** One target a record is applied to, and how much of it. */
data class AppliedTarget(val target: CueTargetDto, val extent: AppliedExtent)

/** One Look or template, and every target it is currently applied to. */
data class AppliedSource(val source: LayerSource, val targets: List<AppliedTarget>)

/**
 * The programmer's ordered Look-layer stack: the live, editable twin of a cue's layer list.
 *
 * ## Materialise, don't compose at read
 *
 * Every mutation re-cooks the whole stack and **materialises the result into
 * [ProgrammerOwner.LAYERS] slots**, rather than teaching the 50 Hz read path about layers.
 *
 * The reason is not the per-tick cost. `FxTarget.composeProgrammerOver` is only reached for keys a
 * running effect covers; everything *else* about the programmer is answered by cold-path coverage
 * oracles — `coversFixture` (the gate in `LayerResolver.fallbackFor`), `activePropertiesByFixture`
 * (effect suppression), `activeKeys` (provenance, blind republish, Clear), `entries` (Record and
 * the wire snapshot). Composing at read would make **every one of those** layer-aware, and each
 * needs the cooked key set — so it would mean cooking anyway, plus cooking per fixture per tick.
 * Materialising gets all of them unchanged, and keeps one composition implementation
 * ([CueComposer.cook]) rather than two.
 *
 * The cost worth knowing: a rig-wide layer makes `coversFixture` true for every fixture, defeating
 * the O(1) gate that exists to stop the colour path's bundled-slider scan taxing heads the
 * programmer isn't touching. Already reachable today via a rig-wide Locate, and it only bites for
 * keys under a running effect.
 *
 * ## Concurrency
 *
 * Three rules, in order of how expensive they are to get wrong:
 *
 * 1. **Cook outside every engine lock.** [LookRegistry.expanded] can fall through to
 *    `loadLookSnapshot`, which opens its own transaction and must never run under
 *    the Layer 4 publish lock (`CascadePublisher`'s, reached via `locked`). So a recook is always: cook → [ProgrammerStore.putLayerSlots]
 *    (lock-free) → [ProgrammerWriter.republishKeys] (which takes the lock, and is the only step
 *    that does).
 * 2. **Lock order is `layersLock` → the publish lock.** Nothing in `CascadePublisher` or
 *    `FxEngine` calls back into this class, so the reverse never arises today; it is stated so
 *    it stays that way. [coverage] adds one more inner lock — `Fixtures`' register read lock,
 *    taken inside [ProgrammerStore.mutateLayers] when a press expands a sibling's groups — and it
 *    is safe for the same reason: a group lookup is a map read that cannot call back here, and the
 *    one path holding the register's *write* lock (`Fixtures.register`) fires its listeners after
 *    releasing it.
 * 3. **One recook per mutation.** [ProgrammerStore.mutateLayers] serialises the list edit, then the
 *    cook happens outside it — two mutations racing therefore both cook, and the later publish
 *    wins. That is correct rather than merely tolerable: both cooked from a list that really
 *    existed, and the store's own `compute` makes each materialisation atomic per key.
 */
class ProgrammerLayerStack(
    private val fixtures: () -> Fixtures,
    private val lookRegistry: () -> LookRegistry,
    private val templateRegistry: () -> TemplateRegistry,
    private val engine: () -> FxEngine,
    private val store: ProgrammerStore,
    /**
     * Supplied lazily because `Show` builds this before `State` finishes wiring itself, and only
     * effect *spawning* needs it — `resolveTargetForCue` and `createEffectInstance` both take
     * it. Values cook with no `State` at all, which is what lets the value half be tested without
     * a database.
     */
    private val state: () -> State?,
) {
    private val targetCoverage = TargetCoverage { fixtures() }
    private val pressRules = LayerPressRules(templateRegistry, targetCoverage)

    /**
     * Guards the classify-spawn-retract sequence in [syncEffects].
     *
     * The live band state itself lives on the engine — each instance carries its
     * [ProgrammerLayerEffectKey], and [FxEngine.programmerLayerEffects] is the only record of
     * which layer effects exist (sweep item E6) — so there is no map to corrupt here. The lock
     * remains because the sequence is read-then-write: two recooks racing between the snapshot
     * and the spawns would each decide to spawn the same missing effect. Same-key twins are worse
     * than a glitch — [FxEngine.programmerLayerEffects] keeps one instance per key, so the losing
     * twin could never be retracted by a recook and would sit on stage until a band sweep.
     *
     * Separate from `ProgrammerStore`'s layer lock, and taken after it everywhere but [detach],
     * which takes the layer lock inside this one so its removal and its effects' unhooking are one
     * step; nothing takes this one inside the layer lock. The cook stays outside both
     * (`loadLookSnapshot` opens a transaction), so two mutations can legitimately reach here
     * concurrently.
     */
    private val effectsLock = Any()

    /**
     * The pseudo cue id a programmer cook runs under.
     *
     * [CueComposer] takes one for its log lines and stamps it on the assignments it returns, but
     * the programmer keeps only the values — the assignments never reach `cueAssignments`, so this
     * cannot collide with a real cue. Negative so it is obvious in a log line.
     */
    private val programmerCookCueId = -1

    // ── Mutations ───────────────────────────────────────────────────────────

    /** Append a layer to the top of the stack. A Look's names no targets ([layerTargets]). */
    fun add(
        source: LayerSource,
        targets: List<CueTargetDto> = emptyList(),
        propertyMask: String? = null,
        blendMode: String = "OVERRIDE",
        amount: Double = 1.0,
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
        sourceCueLayerId: Int? = null,
        beatDivisionOverride: Double? = null,
        fadeMs: Long = 0,
    ): Pair<ProgrammerLayer, ProgrammerLayerOutcome> {
        val layer = ProgrammerLayer(
            layerId = store.mintLayerId(),
            source = source,
            sortOrder = 0, // renumbered below
            targets = layerTargets(source, targets),
            propertyMask = propertyMask,
            blendMode = blendMode,
            amount = amount,
            speedMasterUuid = speedMasterUuid,
            rateSpeedMasterUuid = rateSpeedMasterUuid,
            sourceCueLayerId = sourceCueLayerId,
            beatDivisionOverride = beatDivisionOverride,
        )
        val (next, _) = store.mutateLayers { current -> renumber(current + layer) to Unit }
        return layer to recook(next, fadeMs, arrival = true)
    }

    /**
     * The busking pad's gesture: put this Look or template on, or take it off again. The rule itself
     * is [LayerPress], pure over the layer list; this is its programmer caller, which installs the
     * list in one store mutation and recooks it once.
     *
     * **A Look is pressed whole** (absolute-looks plan D1, D3, P1): its layer names no targets and
     * no mask, whatever the door passes in [targets] and [propertyMask]; on puts one layer of it on
     * top, off takes every layer of it off. Everything below about targets and coverage is a
     * palette's.
     *
     * "Already on" is matched on `source.uuid`, which is neither of the two obvious things and
     * deliberately so. Not the int `id`, because a Look and a template can share an int PK and would
     * then cancel each other's pads. Not the whole [LayerSource] either, though that is what this did
     * first: it is a `data class`, so its equality includes `name` — and a name is *mutable*. Rename a
     * record while its pad is lit and the pad stopped being able to turn it off. A uuid is unique
     * across both tables and never changes, the same reason [recookIfReferences] matches on it alone.
     *
     * The comparison is of **coverage**, not of target lists ([coverage]), and it is the union of
     * every layer this record already has: "already on" means the record covers *each* pressed
     * target. That makes the press the exact inverse of the pad's own lit ring ([appliedState]) — a
     * full ring turns off, a partial or dark one fills in. Order-insensitivity comes free with it,
     * and so does the group blindness: `{group: wash}` and the `{fixture: …}` list of its members are
     * the same press. A press naming **no** targets has no coverage to compare, so it falls back to a
     * literal [sameTargets] twin — the one gesture where "the source's own bound rows" is the whole
     * of what a layer says.
     *
     * Returns `"applied"`/`"removed"` and how many effects moved. A rows-only Look reports `0` either
     * way, and so does a **value** template; an *effect* template reports one per target, which is
     * why the pads' ring cannot be driven from the effect list alone.
     *
     * [propertyMask] applies to the *arriving* palette layer only. It is deliberately **not** part of
     * the "already on" comparison: a pad that re-pressed with a different mask should still take its
     * layer off rather than stack a second one.
     *
     * **Both arms are per target**, and this is the whole of what a press means: *these targets are
     * now this record's, or they are now nobody's.* On the off arm the pressed targets come off
     * **every** layer of this record. On the on arm the same subtraction runs over the record's own
     * layers before it goes on, which keeps the invariant that **at most one layer of a record covers
     * any given head**.
     *
     * Three things take the pressed heads off *other* records on the on arm, all counted in
     * [ToggleOutcome.released], and none on the off arm — turning a pad off never lights anything:
     *
     * - [releaseSiblings], a **solo bank's** exclusivity: the records on the pressed pad's sibling
     *   pads, of any kind, resolved by `routes/buskPress.kt`. Empty for every other door. A Look
     *   sibling comes off whole, and a Look press takes every sibling off whole (P3).
     * - **The family replacement** (apply-model plan D2, D6), resolved here so every palette door gets
     *   it: a palette press takes its heads off every other palette on the stack of the **same family
     *   and kind** — a value palette off value palettes, an effect palette off effect palettes, so
     *   Amber Wave runs over Red. A Look replaces nothing and is replaced by nothing (D6).
     * - Neither drops a layer that also holds other heads: it is narrowed to them, so Amber on the
     *   front wash and Blue on the back wash are two layers on two rigs.
     *
     * **Widening** (apply-model P3): when the record's newest layer is then the top of the stack with
     * the press's settings, its targets grow rather than a second layer going on, so Red on Hex 1
     * and then on Hex 2 is one layer — and its effects on Hex 1 keep their phase. With anything
     * pressed on top in between it is two, so a widening never changes what a higher layer shows.
     *
     * A Look's layer, and a palette's naming **no** targets, are what no subtraction reaches — see
     * [LayerPress].
     *
     * [fadeMs] is the programmer fade (effects plan D13): above 0 the press's effects grow from
     * nothing over it and a release's shrink to nothing, and every value it moves goes at it too, as
     * [add] and [remove] take it. 0 is the press as it was: each row's own fade on the way on, a snap
     * off, effects at once.
     */
    fun toggle(
        source: LayerSource,
        targets: List<CueTargetDto>,
        propertyMask: String? = null,
        beatDivisionOverride: Double? = null,
        releaseSiblings: Set<UUID> = emptySet(),
        fadeMs: Long = 0,
    ): ToggleOutcome {
        val isLook = source.kind == LayerSourceKind.LOOK
        val pressTargets = if (isLook) emptyList() else pressRules.pressTargets(source.uuid, targets)
        val fade = fadeMs.coerceAtLeast(0)
        val arriving = ProgrammerLayer(
            layerId = store.mintLayerId(),
            source = source,
            sortOrder = 0, // renumbered below
            targets = pressTargets,
            propertyMask = propertyMask.takeUnless { isLook },
            beatDivisionOverride = beatDivisionOverride,
        )
        // Resolved before the mutation: a palette snapshot can open a transaction, which must never
        // run under the layer lock. A stale answer is harmless — it names records and their rows.
        val replaces = pressRules.familyReplacements(source.isTemplate, source.uuid, paletteUuids(store.layers))
        val replacesOn = if (replaces.isEmpty()) null else pressRules.paletteHeads(source.uuid, pressTargets)
        val ops = layerOps(store.layers)
        val (next, result) = store.mutateLayers { current ->
            val result = LayerPress.press(current, arriving, ops, targetCoverage, releaseSiblings, replaces, replacesOn)
            renumber(result.layers) to result
        }
        return when (result) {
            is LayerPress.Result.Removed -> ToggleOutcome("removed", recook(next, fade).effectsRetracted)
            is LayerPress.Result.Applied ->
                ToggleOutcome("applied", recook(next, fade, arrival = true).effectsSpawned, result.released)
        }
    }

    /**
     * Whether [toggle] of [sourceUuid] on [targets] would take the record *off* — the arm a door asks
     * before its mask test (`pressWouldRelease`), decided by [LayerPress.wouldRelease] over the
     * stack as it stands, so it is the arm [toggle] takes.
     */
    fun wouldRelease(sourceUuid: UUID, targets: List<CueTargetDto>): Boolean {
        val layers = store.layers
        return LayerPress.wouldRelease(layers, sourceUuid, pressRules.pressTargets(sourceUuid, targets), layerOps(layers), targetCoverage)
    }

    private fun paletteUuids(layers: List<ProgrammerLayer>): Set<UUID> =
        layers.filter { it.source.isTemplate }.mapTo(HashSet()) { it.source.uuid }

    private fun layerOps(layers: List<ProgrammerLayer>) = LayerOps(
        pressRules.boundHeads(layers.filter { it.source.isTemplate && it.targets.isEmpty() }.map { it.source.uuid }),
    )

    /**
     * How [LayerPress] reads a programmer layer. [boundHeads] is each per-fixture palette's row
     * targets by uuid, for its layers that name none, read before the press takes the layer lock.
     */
    private class LayerOps(private val boundHeads: Map<UUID, List<CueTargetDto>?>) : LayerPress.Ops<ProgrammerLayer> {
        override fun boundHeads(layer: ProgrammerLayer) = boundHeads[layer.source.uuid]
        override fun whole(layer: ProgrammerLayer) = layer.source.kind == LayerSourceKind.LOOK
        override fun sourceUuid(layer: ProgrammerLayer) = layer.source.uuid
        override fun targets(layer: ProgrammerLayer) = layer.targets
        override fun withTargets(layer: ProgrammerLayer, targets: List<CueTargetDto>) = layer.copy(targets = targets)

        // A fresh press carries no speed masters, no stomp and full amount; a layer the operator has
        // since retuned is theirs, and widening it would put the new heads under settings they chose
        // for the old ones.
        override fun sameSettings(layer: ProgrammerLayer, arriving: ProgrammerLayer) =
            layer.enabled == arriving.enabled &&
                layer.propertyMask == arriving.propertyMask &&
                layer.blendMode == arriving.blendMode &&
                layer.amount == arriving.amount &&
                layer.stomp == arriving.stomp &&
                layer.speedMasterUuid == arriving.speedMasterUuid &&
                layer.rateSpeedMasterUuid == arriving.rateSpeedMasterUuid &&
                layer.beatDivisionOverride == arriving.beatDivisionOverride
    }

    /**
     * [targets] with every group replaced by its member fixtures — how this class answers "do these
     * two selections mean the same heads?". The rule itself lives in [TargetCoverage], shared with
     * the desk selection so a select button and a pad press expand a group the same way; every
     * coverage question [appliedState] asks is asked of the expansion rather than of the written target.
     */
    private fun coverage(targets: List<CueTargetDto>): List<CueTargetDto> = targetCoverage.expand(targets)

    /**
     * Which library records are applied where — the answer a busk pad's ring is asking for,
     * resolved here rather than in the client.
     *
     * One entry per Look or template with a layer on the stack, listing **every target it covers**:
     * each covered fixture, and each group whose members it covers, marked [AppliedExtent.ALL] or
     * [AppliedExtent.SOME] according to how many of that group's heads it holds. A pad then reads
     * its own state straight off this — for one selected target it is a lookup, and for a
     * multi-selection it is "all of them say ALL" / "none of them appear" / anything else is
     * partial. Nothing about groups, layer targets or coverage has to be re-derived over the wire,
     * which is the point: the desk knows the layers, the groups and the fixtures, and two copies of
     * that rule would drift.
     *
     * Both directions of the group rule fall out of expanding once through [coverage]: a layer on
     * `{group: wash}` reports the wash *and* each of its heads, and a layer on one head reports
     * that head and the wash as partial. Cells follow the parent↔cell rule of [TargetCoverage.covers]
     * the same way: a layer on a bar reports the bar and each of its cells as ALL, a layer on some
     * cells reports those cells and the bar as SOME. Layers are folded by source, so two layers of
     * one template on two selections answer as one record applied to both.
     *
     * A palette layer with **empty** targets contributes the heads its per-fixture palette's rows
     * name, as [toggle] reads it. Enabled-ness is ignored on purpose — a disabled layer is still *on
     * the stack*, and a pad's next press still takes it off, so its ring stays lit.
     *
     * **A Look is on or off** (absolute-looks plan P4): coverage is a palette's question. A Look with
     * a layer on the stack answers every target of the rig as [AppliedExtent.ALL] ([wholeRig]), so a
     * ring folded over any selection reads it lit — "a layer of it is on", in the vocabulary a
     * palette's ring already reads.
     *
     * Takes the layer list so the `layerState` broadcast can describe the frame it is sending
     * rather than whatever the store holds by the time it renders.
     */
    fun appliedState(layers: List<ProgrammerLayer> = store.layers): List<AppliedSource> {
        // Each source once, in the order its first layer stands; only a palette's heads are folded.
        val sources = LinkedHashMap<UUID, LayerSource>()
        val heads = HashMap<UUID, MutableSet<CueTargetDto>>()
        for (layer in layers) {
            if (layer.source.kind == LayerSourceKind.LOOK) {
                sources.putIfAbsent(layer.source.uuid, layer.source)
                continue
            }
            val named = layer.targets.ifEmpty { pressRules.perFixtureRowTargets(layer.source.uuid).orEmpty() }
            if (named.isEmpty()) continue
            sources.putIfAbsent(layer.source.uuid, layer.source)
            heads.getOrPut(layer.source.uuid) { LinkedHashSet() } += coverage(named)
        }
        if (sources.isEmpty()) return emptyList()
        val rig = if (sources.size == heads.size) emptyList() else wholeRig()

        // Group membership in the same terms `coverage` produces, so the two can be compared
        // directly. Empty groups are dropped: "every one of no heads" is a claim nothing means.
        val groups = fixtures().groups.map { group ->
            group.name to group.fixtures.filterIsInstance<Fixture>().map { CueTargetDto("fixture", it.key) }
        }.filter { (_, members) -> members.isNotEmpty() }

        return sources.values.map { source ->
            val covered = heads[source.uuid] ?: return@map AppliedSource(source, rig)
            val groupTargets = groups.mapNotNull { (name, members) ->
                val hits = members.count { it in covered }
                // A member held only through some of its cells is partly covered: the group reads
                // SOME, as the parent itself does below, never ALL — cells do not add up to the bar.
                val partial = members.count { it !in covered && targetCoverage.cells(it).any { c -> c in covered } }
                when {
                    hits == members.size -> AppliedTarget(CueTargetDto("group", name), AppliedExtent.ALL)
                    hits + partial == 0 -> null
                    else -> AppliedTarget(CueTargetDto("group", name), AppliedExtent.SOME)
                }
            }
            // Cells, by the parent↔cell rule ([TargetCoverage.covers]): every cell of a covered
            // parent reads ALL, so a cell selection under a whole-bar layer lights the pad; a
            // parent not itself covered reads SOME while any of its cells is, and never ALL —
            // however many cells are held, they do not add up to the bar.
            val cellTargets = ArrayList<AppliedTarget>()
            val partialParents = LinkedHashSet<CueTargetDto>()
            for (head in covered) {
                val cells = targetCoverage.cells(head)
                if (cells.isNotEmpty()) {
                    for (cell in cells) if (cell !in covered) cellTargets += AppliedTarget(cell, AppliedExtent.ALL)
                    continue
                }
                // Not a parent: a cell (or a plain fixture). Its parent, if any, is partial.
                val parent = targetCoverage.parentOf(head) ?: continue
                if (parent !in covered) partialParents += parent
            }
            val parentTargets = partialParents.map { AppliedTarget(it, AppliedExtent.SOME) }
            AppliedSource(
                source,
                covered.map { AppliedTarget(it, AppliedExtent.ALL) } + cellTargets + parentTargets + groupTargets,
            )
        }
    }

    /**
     * Every target of the rig as [AppliedExtent.ALL] — each fixture, each of its cells and each group
     * with members — which is what a Look's [appliedState] entry answers: lit whatever is selected.
     */
    private fun wholeRig(): List<AppliedTarget> {
        val rig = fixtures()
        val out = ArrayList<AppliedTarget>()
        for (fixture in rig.fixtures) {
            val head = CueTargetDto(TargetRef.Fixture.TYPE, fixture.key)
            out += AppliedTarget(head, AppliedExtent.ALL)
            targetCoverage.cells(head).mapTo(out) { AppliedTarget(it, AppliedExtent.ALL) }
        }
        rig.groups.filter { it.fixtures.isNotEmpty() }
            .mapTo(out) { AppliedTarget(CueTargetDto(TargetRef.Group.TYPE, it.name), AppliedExtent.ALL) }
        return out
    }

    /**
     * The stack's change signal, forwarded from [ProgrammerStore.layersFlow].
     *
     * A pass-through because the store is the only place a layer list changes and so the only place
     * that can emit — but a caller wanting "the applied state, and a nudge when it moves" is asking
     * this object for both, and should not have to reach around it for one of them. The surface
     * feedback publisher, whose record LEDs are exactly that pair, is why this exists.
     */
    val layersFlow get() = store.layersFlow

    /** Remove a layer by id. A no-op when it is already gone. */
    fun remove(layerId: Int, fadeMs: Long = 0): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            renumber(current.filterNot { it.layerId == layerId }) to Unit
        }
        return recook(next, fadeMs)
    }

    /**
     * Take every layer of [sourceUuids] off the stack, in one mutation, without pressing anything.
     *
     * The busk press route's sibling sweep for a **cue** press in a solo bank: a cue has no targets,
     * so its layer siblings come off wholesale rather than being narrowed the way [toggle]'s
     * `releaseSiblings` narrows them under a layer press. It is the only way to release without a
     * press. The template delete route reaches the same end with one [remove] per layer, which is
     * one recook and one `layerState` frame each — fine for a delete, wrong for a pad press over a
     * bank of lit pads, which is why this does it as one `mutateLayers` and one recook.
     *
     * Returns how many layers came off. Nothing is recooked — and no `layerState` frame is emitted —
     * when nothing did: the transform hands `current` back by identity, which is what the store's
     * emit guard reads, rather than a renumbered copy of an unchanged list.
     */
    fun release(sourceUuids: Set<UUID>): Int {
        if (sourceUuids.isEmpty()) return 0
        val (next, dropped) = store.mutateLayers { current ->
            val kept = current.filterNot { it.source.uuid in sourceUuids }
            if (kept.size == current.size) current to 0 else renumber(kept) to (current.size - kept.size)
        }
        if (dropped > 0) recook(next)
        return dropped
    }

    /**
     * *Detach* (apply-model plan §3.5): turn layer [layerId] into Local without the stage moving.
     *
     * The stack is cooked with the layer and without it, and every key the two disagree on — the
     * keys it wins, and those a higher layer blends over it — is written as a touched `WEB` value at
     * the with-layer value, under the group its row names (else the group the layer names it through).
     * A key some other local owner shows (a Locate, a flash) takes it beneath
     * that owner, for when it lets go; a key the operator's own `WEB` value already holds, or a raw
     * channel slot overrides, is left as it is. A Look's scenery passes to the programmer's own
     * scenery ([ProgrammerScenery.holdUnder]) where no higher Look layer holds it. Its effects lose
     * their layer and keep running, phase kept, as Local effects (a spent N-shot is removed), and
     * the layer comes off; the recook moves nothing the `WEB` slots now hold.
     *
     * One thing still moves: a stomping layer's suppression of the effects under it ends with it.
     * Returns null when no such layer is on the stack.
     */
    fun detach(layerId: Int): DetachOutcome? {
        val layers = store.layers
        val layer = layers.firstOrNull { it.layerId == layerId } ?: return null
        val rest = layers.filterNot { it.layerId == layerId }
        val without = cookStack(rest, withEffects = false).values.rows
            .associate { (it.targetKey to it.propertyName) to it.value }
        val fixtures = fixtures()
        val hints = groupHintsForTargets(fixtures, layer.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })
        val onTop = ArrayList<ProgrammerWriter.PropertyWrite>()
        val beneath = ArrayList<ProgrammerWriter.PropertyWrite>()
        for (row in cookStack(layers, withEffects = false).values.rows) {
            if (without[row.targetKey to row.propertyName] == row.value) continue
            val slots = store.slotsFor(row.targetKey, row.propertyName)
            if (slots.any { it.owner == ProgrammerOwner.WEB }) continue
            val head = runCatching { fixtures.untypedGroupableFixture(row.targetKey) }.getOrNull() ?: continue
            if (PropertyChannelWriter.channelsFor(head, row.propertyName).any { store.getChannelSlot(it.universe.universe, it.channel) != null }) continue
            val parentKey = targetCoverage.parentOf(CueTargetDto(TargetRef.Fixture.TYPE, row.targetKey))?.key
            val write = ProgrammerWriter.PropertyWrite(
                head, row.propertyName, row.value, sourceGroup = row.sourceGroup() ?: hints[row.targetKey] ?: parentKey?.let { hints[it] },
            )
            if (slots.firstOrNull()?.owner == ProgrammerOwner.LAYERS) onTop += write else beneath += write
        }
        // Stored, not published: each key already shows the value written.
        val eng = engine()
        eng.programmer.storeUnpublished(ProgrammerOwner.WEB, onTop, beneathTop = false)
        eng.programmer.storeUnpublished(ProgrammerOwner.WEB, beneath, beneathTop = true)
        if (layer.source.kind == LayerSourceKind.LOOK) handLookSceneryOver(layer, rest)
        // One block, so no recook from another press can see the layer gone while its effects are
        // still keyed to it, and retract them. `mutateLayers` takes the layer lock inside this one;
        // nothing takes them the other way round.
        val (next, effects) = synchronized(effectsLock) {
            val (next, _) = store.mutateLayers { current ->
                val kept = current.filterNot { it.layerId == layerId }
                if (kept.size == current.size) current to Unit else renumber(kept) to Unit
            }
            next to eng.detachProgrammerLayerEffects(layerId)
        }
        recook(next)
        return DetachOutcome(onTop.size + beneath.size, effects)
    }

    /**
     * The scenery a detached Look layer shows, into the programmer's own: each state of its Look's
     * that no higher live Look layer also sets. Nothing when the layer showed none (disabled, at
     * zero) or another live layer of the same Look keeps showing it.
     */
    private fun handLookSceneryOver(layer: ProgrammerLayer, rest: List<ProgrammerLayer>) {
        val st = state() ?: return
        fun live(l: ProgrammerLayer) = l.source.kind == LayerSourceKind.LOOK && l.enabled && l.amount > 0.0
        if (!live(layer) || rest.any { live(it) && it.source.uuid == layer.source.uuid }) return
        val higher = rest.filter { live(it) && it.sortOrder > layer.sortOrder }.map { it.source.id }
        val scenery = lookSceneryOf(st.database, higher + layer.source.id)
        val own = scenery[layer.source.id].orEmpty()
        if (own.isEmpty()) return
        val above = higher.mapNotNull { scenery[it] }
        val rows = own.mapValues { (uuid, state) ->
            ElementStates(
                visible = state.visible.takeIf { above.none { it[uuid]?.visible != null } },
                open = state.open.takeIf { above.none { it[uuid]?.open != null } },
                trimM = state.trimM.takeIf { above.none { it[uuid]?.trimM != null } },
            )
        }
        val projectId = runCatching { st.projectManager.currentProject.id.value }.getOrNull() ?: return
        st.programmerScenery.holdUnder(projectId, rows)
    }

    /**
     * *Apply as values* for a Look (apply-model plan §3.5): the end of a press followed by [detach],
     * with no layer ever on the stack. The Look is cooked on top of the stack as the layer a press
     * would add; the keys it wins are written as `WEB` values (at [fadeMs] when given, else each
     * row's own fade, as a press's arrival is) under the group each row names, its effects start as
     * Local effects at the rank that layer would have had, and its scenery passes to the
     * programmer's own. A Look's [targets] are none (D1).
     */
    fun applyAsValues(
        source: LayerSource,
        targets: List<CueTargetDto>,
        propertyMask: String? = null,
        beatDivisionOverride: Double? = null,
        fadeMs: Long? = null,
    ): ApplyValuesOutcome {
        val layer = ProgrammerLayer(
            layerId = store.mintLayerId(),
            source = source,
            sortOrder = Int.MAX_VALUE,
            targets = layerTargets(source, targets),
            propertyMask = propertyMask,
            beatDivisionOverride = beatDivisionOverride,
        )
        val cooked = cookStack(store.layers + layer, withEffects = true)
        val fixtures = fixtures()
        val hints = groupHintsForTargets(fixtures, targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })
        val byFade = LinkedHashMap<Long, MutableList<ProgrammerWriter.PropertyWrite>>()
        for (row in cooked.values.rows) {
            if (row.layerWinner?.layerId != layer.layerId) continue
            val head = runCatching { fixtures.untypedGroupableFixture(row.targetKey) }.getOrNull() ?: continue
            val parentKey = targetCoverage.parentOf(CueTargetDto(TargetRef.Fixture.TYPE, row.targetKey))?.key
            byFade.getOrPut(fadeMs ?: row.fadeDurationMs ?: 0) { ArrayList() } += ProgrammerWriter.PropertyWrite(
                head, row.propertyName, row.value, sourceGroup = row.sourceGroup() ?: hints[row.targetKey] ?: parentKey?.let { hints[it] },
            )
        }
        val eng = engine()
        for ((fade, writes) in byFade) eng.programmer.writeProperties(ProgrammerOwner.WEB, writes, fadeMs = fade)
        val priority = priorityFor(cooked.ranks[layer.layerId] ?: 0)
        val instances = cooked.effects.mapNotNull { (cookLayer, effect, target) ->
            if (cookLayer.layerId != layer.layerId) return@mapNotNull null
            spawn(cookLayer, effect, target, beatDivisionOverride)?.also { it.priority = priority }
        }
        val effectIds = eng.addEffects(instances)
        if (source.kind == LayerSourceKind.LOOK) {
            state()?.let { st ->
                val rows = lookSceneryOf(st.database, listOf(source.id))[source.id].orEmpty()
                val projectId = runCatching { st.projectManager.currentProject.id.value }.getOrNull()
                if (rows.isNotEmpty() && projectId != null) st.programmerScenery.holdUnder(projectId, rows)
            }
        }
        return ApplyValuesOutcome(byFade.values.sumOf { it.size }, effectIds)
    }

    /** Move a layer to [toIndex], renumbering the whole list. */
    fun move(layerId: Int, toIndex: Int): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            val from = current.indexOfFirst { it.layerId == layerId }
            if (from < 0) return@mutateLayers current to Unit
            val moving = current[from]
            val rest = current.toMutableList().apply { removeAt(from) }
            rest.add(toIndex.coerceIn(0, rest.size), moving)
            renumber(rest) to Unit
        }
        return recook(next)
    }

    /**
     * Change one layer's presentation fields. Null means "leave alone". A Look's layer keeps naming
     * no targets ([layerTargets]); its mask is the operator's to set here (D3).
     */
    fun patch(
        layerId: Int,
        enabled: Boolean? = null,
        amount: Double? = null,
        propertyMask: String? = null,
        blendMode: String? = null,
        targets: List<CueTargetDto>? = null,
        stomp: Boolean? = null,
        fadeMs: Long = 0,
    ): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            current.map {
                if (it.layerId != layerId) it
                else it.copy(
                    enabled = enabled ?: it.enabled,
                    amount = amount?.coerceIn(0.0, 1.0) ?: it.amount,
                    propertyMask = propertyMask ?: it.propertyMask,
                    blendMode = blendMode ?: it.blendMode,
                    targets = layerTargets(it.source, targets ?: it.targets),
                    stomp = stomp ?: it.stomp,
                )
            } to Unit
        }
        return recook(next, fadeMs)
    }

    /**
     * Replace the stack with a cue's layers — Include.
     *
     * Each becomes a programmer layer carrying `sourceCueLayerId`, which is how Update's structural
     * diff later tells an edited layer from a newly added one.
     *
     * **Timed layers are dropped, not held.** A programmer layer is always immediate — there is no
     * trigger manager for the programmer — so a delayed layer has nothing to fire it here. Include
     * reports how many it skipped rather than silently flattening them to immediate, which would
     * put a chase's whole stack on stage at once; Update then leaves the cue's timed layers
     * untouched, so nothing is lost by not holding them.
     *
     * Returns the number of timed layers skipped, for the response.
     */
    internal fun installFromCue(
        layers: List<CookLayer>,
        fadeMs: Long = 0,
    ): Pair<Int, ProgrammerLayerOutcome> {
        val immediate = layers.filterNot { it.isTimed }
        val skipped = layers.size - immediate.size
        val (next, _) = store.mutateLayers { current ->
            val installed = immediate.sortedBy { it.sortOrder }.map { layer ->
                ProgrammerLayer(
                    layerId = store.mintLayerId(),
                    source = layer.source,
                    sortOrder = 0, // renumbered below
                    enabled = layer.enabled,
                    targets = layerTargets(layer.source, layer.targets),
                    propertyMask = layer.propertyMask,
                    // The one point a *stored* blend becomes programmer state, and so the one
                    // place the lenient policy belongs on this path: a row written by another
                    // build may name a blend this one does not know, and Record writes these
                    // layers straight back out again. Canonicalising here means the stack, the
                    // UI and every subsequent write agree on the blend the desk is actually
                    // playing — and that `writeCueChildren`'s strict check, which Record CREATE
                    // does not catch, can never see an unrecognised value from this direction.
                    blendMode = EffectSpecCoercion.Lenient.blendMode(layer.blendMode) {
                        "included cue layer ${layer.layerId} on '${layer.source.name}'"
                    }.name,
                    amount = layer.amount,
                    stomp = layer.stomp,
                    speedMasterUuid = layer.speedMasterUuid,
                    rateSpeedMasterUuid = layer.rateSpeedMasterUuid,
                    sourceCueLayerId = layer.layerId,
                )
            }
            renumber(installed) to Unit
        }
        return skipped to recook(next, fadeMs, arrival = true)
    }

    /**
     * Drop the whole stack without publishing.
     *
     * Called from `clearProgrammerCompletely` in the same position `resetPresetProgrammerBookkeeping`
     * held, and for the same reason: the sweep that follows releases every slot, so running this
     * class's own release path afterwards would double-release, while leaving the bookkeeping would
     * desync the stack from the slots. The band-effect sweep in that sequence takes the layers'
     * effects with it, which is why nothing is retracted here.
     */
    fun reset() {
        store.mutateLayers { emptyList<ProgrammerLayer>() to Unit }
        // No effect bookkeeping to drop: the band lives on the engine's instances, and the
        // band-effect sweep in `clearProgrammerCompletely` is what takes those.
        //
        // The suppression is cleared explicitly rather than left to the next recook, because this
        // is the one mutation that deliberately doesn't recook. It would be inert either way —
        // `mintLayerId` is monotonic for the life of the process, so no future layer can inherit
        // a stale entry — but relying on that makes a local invariant depend on a distant one.
        engine().cueLayer.setProgrammerStompSuppression(emptyMap())
    }

    /**
     * Cook the whole stack onto this show's engine, effects and all — for a stack that arrived
     * already built: a same-project reload hands the outgoing show's [ProgrammerStore] to its
     * successor, whose engine runs none of the layers' effects yet. Every layer's effects spawn
     * afresh (their phase restarts); the values republish with the rest of the programmer through
     * `ProgrammerWriter.republishAll`, which the caller runs after this.
     */
    fun reinstate(): ProgrammerLayerOutcome = recook(store.layers)

    /**
     * Re-cook if any layer names [lookUuid] — the programmer's half of a Look edit.
     *
     * Returns the keys whose value moved, for `republishForLookEdit` to fold into its single
     * publish. It deliberately does **not** publish here: that function composes the programmer
     * over the cue layer, so publishing before it has rebuilt the cues would transmit a
     * half-updated cascade and correct it a frame later.
     *
     * **Values only — the Look's *effects* are not re-spawned.** That matches what a Look edit does
     * to a live cue (`republishCueIfLive` says the same in as many words), and for the same reason:
     * re-spawning would restart the phase of every effect the edit did not touch, so an operator
     * nudging a colour would visibly re-trigger a chase. The consequence to know is that adding or
     * removing an *effect* on a Look does not reach layers already applied — value edits tour, effect
     * edits need the layer re-added.
     */
    fun recookIfReferences(sourceUuid: UUID): Set<CueAssignmentResolver.Key> {
        val layers = store.layers
        // Matched on the uuid alone rather than on (kind, uuid): uuids are random and unique across
        // both tables, so a caller republishing after an edit does not have to say which kind of
        // thing it edited. The template path uses this unchanged.
        if (layers.none { it.source.uuid == sourceUuid }) return emptySet()
        // Keys only. The per-key fades are deliberately dropped: `republishForLookEdit` folds these
        // into one publish across the programmer *and* every live cue, and a Look edit touring to
        // an already-applied layer is not the layer arriving — re-timing it would make every nudge
        // of a colour crossfade on stage.
        return materialise(cookStack(layers, withEffects = false).values).moved
    }

    /**
     * Put a template layer's **current** effect back on the instance it spawned — the busk Effects
     * tab's *Reset to template* (fixture-fx-sheets plan W5, D20). The instance is edited in place
     * through [FxEngine.updateEffect], so its id and its phase are kept: a running chase snaps back
     * to the template's settings without restarting.
     *
     * Everything the layer would spawn the effect with is restored — the effect and its
     * parameters, division, blend, phase offset, distribution, element mode and filter, step
     * timing, timing source and both masters — with the layer's own overrides (its speed masters,
     * its beat-division override) applied exactly as [build] applies them.
     *
     * **The spawn key moves to the template's current effect.** [syncEffects] matches an instance
     * by the [StoredEffect] it was spawned from, so an instance spawned before a template edit
     * carries the old entry and the next recook would retract it and spawn afresh. Reset is the
     * moment the instance becomes the template's current effect, so it takes that entry as its key
     * and the next recook keeps it. Done under [effectsLock], so no recook can classify the instance
     * between the update and the re-key.
     *
     * Refused with [ResetToTemplateOutcome.NOT_FROM_TEMPLATE] for an instance no programmer
     * template layer spawned — a Look layer's, a manual one, a cue's (whose template layer is
     * re-spawned by the next GO, not edited in place), Include's.
     */
    fun resetEffectToTemplate(effectId: Long): ResetToTemplateOutcome = synchronized(effectsLock) {
        val eng = engine()
        val instance = eng.getEffect(effectId) ?: return ResetToTemplateOutcome.NotFound
        val key = instance.programmerLayerEffectKey
        val source = instance.source
        val layer = key?.let { k -> store.layers.firstOrNull { it.layerId == k.layerId } }
        if (key == null || source == null || !source.isTemplate || layer == null) {
            return ResetToTemplateOutcome.Refused(
                ResetToTemplateOutcome.NOT_FROM_TEMPLATE,
                "Effect $effectId was not started by a palette layer in the programmer",
            )
        }
        val entry = templateRegistry().snapshot(source.uuid)?.effect
            ?: return ResetToTemplateOutcome.Refused(
                ResetToTemplateOutcome.TEMPLATE_GONE,
                "Palette '${source.name}' no longer holds an effect to reset to",
            )
        val st = state() ?: return ResetToTemplateOutcome.NotFound
        val spec = entry.toEffectSpec().withBeatDivisionOverride(layer.beatDivisionOverride)
        // Built the way [build] builds a fresh spawn, on the instance's own target, and read for its
        // settings only — this instance is never added to the engine.
        val fresh = try {
            EffectSpawner.createEffectInstance(
                spec, instance.target, state = st,
                overrideSpeedMasterUuid = layer.speedMasterUuid,
                overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
            )
        } catch (e: Exception) {
            return ResetToTemplateOutcome.Refused(
                ResetToTemplateOutcome.TEMPLATE_GONE,
                "Palette '${source.name}''s effect could not be created: ${e.message}",
            )
        }
        val updated = eng.updateEffect(
            effectId = effectId,
            newEffect = fresh.effect,
            newTiming = fresh.timing,
            newBlendMode = fresh.blendMode,
            newPhaseOffset = fresh.phaseOffset,
            newDistributionStrategy = fresh.distributionStrategy,
            newElementMode = fresh.elementMode,
            newElementFilter = fresh.elementFilter,
            newStepTiming = fresh.stepTiming,
            newRegistrationId = fresh.registrationId,
            newTimingSource = fresh.registrationTimingSource,
            replaceMasters = FxEngine.MasterAssignment(fresh.speedMasterUuid, fresh.rateSpeedMasterUuid),
            newSeconds = fresh.onSeconds,
            // Size, direction and runs are the palette's too (effects plan D2, D5, D9).
            newSize = fresh.dynamics.size,
            newDirection = fresh.dynamics.direction,
            newNShot = fresh.dynamics.nShot,
            clearNShot = fresh.dynamics.nShot == null,
            // And the spread's shape (D4).
            newSpread = fresh.dynamics.spread,
            newWings = fresh.dynamics.wings,
            newBlocks = fresh.dynamics.blocks,
            newGroups = fresh.dynamics.groups,
        ) ?: return ResetToTemplateOutcome.NotFound
        updated.programmerLayerEffectKey = ProgrammerLayerEffectKey.of(key.layerId, entry, key.target)
        ResetToTemplateOutcome.Reset(updated)
    }

    // ── The cook ────────────────────────────────────────────────────────────

    /**
     * @param arrival whether this mutation puts a source *on stage* — [add] (so [toggle]'s applied
     *   arm) and [installFromCue]. Only an arrival lets the cooked rows' own `fadeDurationMs` ramp
     *   the publish; [patch], [move] and [remove] must not, and the reason is the same one
     *   `FxEngine.republishCueAssignments` gates on: `amount` is folded into the cooked value, so
     *   an operator dragging a layer's Amount slider over a Look with a 2 s dimmer row would
     *   restart that ramp on every drag event and the rig would never track the slider.
     */
    private fun recook(
        layers: List<ProgrammerLayer>,
        fadeMs: Long = 0,
        arrival: Boolean = false,
    ): ProgrammerLayerOutcome {
        // One cook for the whole recook (sweep item C8): the rows and the effect triples come out
        // of a single pass over the stack, so the layers resolve once and both halves see the same
        // Look snapshots.
        val cooked = cookStack(layers, withEffects = true)
        val (moved, rowFades) = materialise(cooked.values)
        val fx = syncEffects(layers, cooked, fadeMs, moved)
        if (moved.isNotEmpty()) {
            val eng = engine()
            if (!arrival || rowFades.isEmpty() || fadeMs > 0) {
                // A caller-supplied `fadeMs` covers every key and wins outright: an explicit fade on
                // Include is the operator's instruction, and it overrides the source's stored
                // default exactly as `POST /palettes/{id}/apply` does with `request.fadeMs`.
                eng.programmer.republishKeys(moved, fadeMs)
            } else {
                eng.programmer.republishKeys(moved, rowFades)
            }
        }
        return ProgrammerLayerOutcome(moved.size, fx.spawned, fx.retracted, fx.repriorised)
    }

    /**
     * What one [materialise] moved: the keys to publish, and the per-key fade the winning row asked
     * for. Keys asking for no fade are absent from [rowFades] — see
     * [LayerResolver.CueLayerSnapshot.fadeDurations] for the same convention on the cue side.
     */
    private data class Materialised(
        val moved: Set<CueAssignmentResolver.Key>,
        val rowFades: Map<CueAssignmentResolver.Key, Long>,
    )

    /**
     * Cook the stack.
     *
     * [withEffects] asks for the effect triples alongside the rows, out of the same pass over the
     * layers — which is what [recook] wants. [recookIfReferences] passes false: it republishes
     * values and deliberately leaves the running effects alone, so collecting them would be work
     * thrown away.
     *
     * Called **outside** [effectsLock], always. `loadLookSnapshot` opens a transaction, and the
     * class doc's rule that the cook stays outside every lock is why — the effect half used to be
     * cooked from inside that lock, which is the violation this consolidation removes.
     */
    private fun cookStack(layers: List<ProgrammerLayer>, withEffects: Boolean): CueComposer.FullCook =
        CueComposer.cookAll(
            fixtures = fixtures(),
            cueId = programmerCookCueId,
            priority = 0,
            layers = layers.map { it.toCookLayer() },
            // The programmer's *local* layer is the store's other owners — WEB, SURFACE, LOCATE and
            // the rest — which already sit above these slots. Passing them here as well would cook
            // them into the layer contribution and lose that distinction: Record could no longer
            // tell the operator's own edits from the stack's output.
            localRows = emptyList(),
            resolveLook = lookRegistry()::snapshot,
            resolveTemplate = templateRegistry()::snapshot,
            withEffects = withEffects,
        )

    /**
     * Swap a cook's values into the store. No publish.
     *
     * Also republishes the stack's within-cue stomp suppression, which has to happen here rather
     * than in [syncEffects] beside the instances it affects: only the cook knows which properties
     * each layer asserted, and `syncEffects` deliberately does not rebuild on a mask, amount or
     * order change — all three of which move the suppression set.
     */
    private fun materialise(cooked: CookResult): Materialised {
        engine().cueLayer.setProgrammerStompSuppression(cooked.stompSuppression)
        val moved = store.putLayerSlots(
            cooked.rows.map { row ->
                ProgrammerStore.LayerSlotWrite(
                    fixtureKey = row.targetKey,
                    propertyName = row.propertyName,
                    value = row.value,
                    // Absent only when a local row won, which cannot happen here — nothing is
                    // passed as a local row. Index 0 is a safe floor either way.
                    layerIndex = row.layerWinner?.index ?: 0,
                )
            }
        )
        // Only the keys that actually moved need a fade: a slot the cook left where it was is not
        // republished, so timing it would be timing nothing.
        val rowFades = if (moved.isEmpty()) {
            emptyMap()
        } else {
            cooked.rows.mapNotNull { row ->
                val fade = row.fadeDurationMs?.takeIf { it > 0 } ?: return@mapNotNull null
                CueAssignmentResolver.Key.fixture(row.targetKey, row.propertyName)
                    .takeIf { it in moved }
                    ?.let { it to fade }
            }.toMap()
        }
        return Materialised(moved, rowFades)
    }

    private data class EffectSync(val spawned: Int, val retracted: Int, val repriorised: Int)

    /**
     * Bring the live programmer-band effects into line with the stack.
     *
     * Classifies rather than rebuilds: **only a layer that is newly present, or newly gone, moves
     * an instance.** An amount, mask, target or *order* change re-ranks what is already running.
     * Respawning on a reorder would restart every effect's phase, which is exactly the glitch the
     * layer-index priority scheme exists to avoid.
     *
     * At [fadeMs] above 0 an arriving instance grows from nothing and a retracted one shrinks to
     * nothing, removed at 0 (effects plan D13) — except one over a key whose value this recook moves
     * ([moved]), which lands or goes at once so that value can ramp ([FxEngine.releaseEffects]). At
     * 0 both snap.
     */
    private fun syncEffects(
        layers: List<ProgrammerLayer>,
        cooked: CueComposer.FullCook,
        fadeMs: Long,
        moved: Set<CueAssignmentResolver.Key>,
    ): EffectSync = synchronized(effectsLock) {
        val eng = engine()
        // The engine's record *is* the band: anything removed by another surface (the FX sheet's
        // own remove, a band sweep) is simply absent here, with no bookkeeping to reconcile.
        val live = eng.programmerLayerEffects()

        val desired = cooked.effects

        // Ranks come from the same cook as the rows, so an effect's band offset and its layer's
        // cooked `CookWinner.index` are one number rather than two computed apart.
        val rankOf = cooked.ranks

        val byLayerId = layers.associateBy { it.layerId }
        val wanted = HashSet<ProgrammerLayerEffectKey>(desired.size)
        val repriorities = HashMap<Long, Int>()
        // Built here, added in one `addEffects` below: a per-instance add rebuilt the engine's
        // sorted snapshots and re-broadcast the whole active-effect list once per effect, which
        // is O(N²) over a stack of any size (sweep item C7). Build order is add order, so the
        // layer-rank priorities still decide composition exactly as before.
        val spawning = mutableListOf<FxInstance>()

        for ((layer, effect, target) in desired) {
            val key = ProgrammerLayerEffectKey.of(layer.layerId, effect, target)
            // A Look holding the same effect twice on one target would collide here; the second is
            // dropped rather than spawning a second instance under the same identity — the retract
            // pass matches on the key, so only one of the twins could ever be seen.
            if (!wanted.add(key)) continue
            val priority = priorityFor(rankOf[layer.layerId] ?: 0)
            val existing = live[key]
            if (existing != null) {
                repriorities[existing.id] = priority
                continue
            }
            val override = byLayerId[layer.layerId]?.beatDivisionOverride
            spawning += build(layer, effect, target, key, priority, override) ?: continue
        }

        val valueMoves = { key: String, property: String -> CueAssignmentResolver.Key.fixture(key, property) in moved }
        // Set before the add, so no pass paints an arriving instance at full first.
        if (fadeMs > 0) {
            val nowMs = System.currentTimeMillis()
            for (instance in spawning) {
                if (eng.paintsMovingValue(instance, valueMoves)) continue
                instance.carryEnvelope(EffectEnvelope.ramp(0.0, 1.0, fadeMs, EasingCurve.LINEAR, nowMs))
            }
        }
        val spawned = eng.addEffects(spawning).size

        val retracted = eng.releaseEffects(live.filterKeys { it !in wanted }.values, fadeMs, valueMoves)

        val repriorised = eng.repriorityProgrammerLayerEffects(repriorities)
        EffectSync(spawned, retracted, repriorised)
    }

    /**
     * The programmer band, offset by layer rank.
     *
     * [rank] is a [CueComposer.contributingLayers] index — the same number [CueComposer.cook] puts
     * in `CookWinner.index` — so an effect's position in the band and its layer's cooked rank are
     * one value, not two that have to be kept equal.
     *
     * `sortedEffectsComparator` is `compareBy(priority, composeOrder, id)` with `composeOrder` the
     * creation counter, so without the offset relative order would be *spawn* order and a reorder would need
     * a respawn to express itself. Every consumer of the band tests
     * `isProgrammerFxPriority` (`>= BASE`) rather than exact equality, so an offset is invisible to
     * all of them. The band is a million wide; [FxEngine.PROGRAMMER_FX_RANK_CLAMP] is a backstop,
     * not an expected case.
     */
    private fun priorityFor(rank: Int): Int =
        FxEngine.PROGRAMMER_FX_PRIORITY_BASE + rank.coerceIn(0, FxEngine.PROGRAMMER_FX_RANK_CLAMP)

    /**
     * Build the instance a programmer layer's effect wants, or null if it can't be built.
     * The caller adds it — see the batched `addEffects` in [syncEffects].
     */
    private fun build(
        layer: CookLayer,
        effect: StoredEffect,
        target: EffectTargetRef,
        key: ProgrammerLayerEffectKey,
        priority: Int,
        beatDivisionOverride: Double?,
    ): FxInstance? {
        val instance = spawn(layer, effect, target, beatDivisionOverride) ?: return null
        // `source` and the band key are the honest fields for where this came from — a template
        // layer reaches here too now (D5), which is why this is the whole source rather than an
        // id that could only ever mean a Look.
        instance.source = layer.source
        // The key is the instance's identity in the band: [syncEffects] classifies the engine's
        // live instances by it on the next recook, so an unstamped instance would be retracted
        // as unrecognised and respawned every mutation.
        instance.programmerLayerEffectKey = key
        instance.priority = priority
        return instance
    }

    /** A layer's effect on one target as a bare instance — no source, no band key, no priority. */
    private fun spawn(
        layer: CookLayer,
        effect: StoredEffect,
        target: EffectTargetRef,
        beatDivisionOverride: Double?,
    ): FxInstance? {
        val st = state() ?: return null
        val spec = effect.toEffectSpec().withBeatDivisionOverride(beatDivisionOverride)
        val fxTarget = try {
            EffectSpawner.resolveTargetForCue(st, target, spec)
        } catch (e: Exception) {
            logger.warn(
                "programmer layer on '{}' — target '{}' unresolvable — skipping effect: {}",
                layer.source.name, target.key, e.message,
            )
            null
        } ?: return null

        // [createEffectInstance] gives this a **live** colour source: a `tmpl:` reference is a live
        // dependency by design, and a programmer layer is no more exempt from that than a cue's.
        return try {
            EffectSpawner.createEffectInstance(
                spec, fxTarget, state = st,
                overrideSpeedMasterUuid = layer.speedMasterUuid,
                overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
            )
        } catch (e: Exception) {
            logger.warn(
                "programmer layer '{}': effect '{}' could not be created — {}",
                layer.source.name, effect.effectType, e.message,
            )
            null
        }
    }

    private fun renumber(layers: List<ProgrammerLayer>): List<ProgrammerLayer> =
        layers.mapIndexed { index, layer ->
            if (layer.sortOrder == index) layer else layer.copy(sortOrder = index)
        }
}

/** The group a cooked row came through — a group row's own, or a palette layer's group target. */
private fun CueAssignmentResolver.Assignment.sourceGroup(): String? = (sourceTarget as? TargetRef.Group)?.key
