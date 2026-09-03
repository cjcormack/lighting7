package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.sameTargets
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import uk.me.cormack.lighting7.models.LayerSource

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
 * how many **sibling** layers an apply took the pressed targets off (template groups) — counting a
 * layer narrowed to the targets the press did not name as well as one dropped outright, since from
 * the pressed targets' point of view both are gone. Always 0 on a remove, and for any press with no
 * siblings to name. A press rewriting one of its **own** record's layers is not a release: that is
 * the same pad extending or clearing itself, and no other pad changed.
 */
data class ToggleOutcome(
    val action: String,
    val effectCount: Int,
    val released: Int = 0,
)

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
     * Separate from `ProgrammerStore`'s layer lock and taken *after* it, never around it: the cook
     * must stay outside every lock (`loadLookSnapshot` opens a transaction), so two mutations can
     * legitimately reach here concurrently.
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

    /** Append a layer to the top of the stack. */
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
        val (layer, _, outcome) = addReleasing(
            source = source,
            targets = targets,
            propertyMask = propertyMask,
            blendMode = blendMode,
            amount = amount,
            speedMasterUuid = speedMasterUuid,
            rateSpeedMasterUuid = rateSpeedMasterUuid,
            sourceCueLayerId = sourceCueLayerId,
            beatDivisionOverride = beatDivisionOverride,
            fadeMs = fadeMs,
        )
        return layer to outcome
    }

    /**
     * [add], but rewriting the existing layers through [release] **in the same store mutation** as
     * the append — so a pad press that replaces its siblings is one `layerState` frame and one
     * recook, with the released effects retracting in the same `syncEffects` pass the new one
     * spawns in. [release] returns its argument to leave a layer alone, a copy to narrow it, or
     * null to drop it; either kind of rewrite counts towards the returned release count.
     *
     * A mapper rather than the drop-predicate this took first, because exclusivity is per
     * **target**, not per layer: a press on `{hex-1, hex-2}` has to take hex-1 off a sibling that
     * also holds hex-3, rather than either dropping hex-3 with it or leaving hex-1 lit underneath.
     * See [toggle] and [withoutTargets].
     *
     * Private because the only reason to release-and-add atomically is [toggle]'s exclusivity, and
     * a caller with a mapper of its own would be a second exclusivity rule.
     */
    private fun addReleasing(
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
        release: (ProgrammerLayer) -> ProgrammerLayer? = { it },
    ): Triple<ProgrammerLayer, Int, ProgrammerLayerOutcome> {
        val layer = ProgrammerLayer(
            layerId = store.mintLayerId(),
            source = source,
            sortOrder = 0, // renumbered below
            targets = targets,
            propertyMask = propertyMask,
            blendMode = blendMode,
            amount = amount,
            speedMasterUuid = speedMasterUuid,
            rateSpeedMasterUuid = rateSpeedMasterUuid,
            sourceCueLayerId = sourceCueLayerId,
            beatDivisionOverride = beatDivisionOverride,
        )
        val (next, released) = store.mutateLayers { current ->
            var releasedCount = 0
            val kept = buildList {
                for (existing in current) {
                    val rewritten = release(existing)
                    // A rewrite of the arriving record's *own* layer is the press tidying up after
                    // itself — the same pad, so nothing was released. Only another record going
                    // dark counts, which is what the route reports.
                    if (rewritten !== existing && existing.source.uuid != source.uuid) releasedCount++
                    if (rewritten != null) add(rewritten)
                }
            }
            renumber(kept + layer) to releasedCount
        }
        return Triple(layer, released, recook(next, fadeMs, arrival = true))
    }

    /**
     * The busking pad's gesture: put this Look or template on these targets, or take it off again.
     *
     * "Already on" means **a layer with the same source *identity* and the same target set** — the
     * pad's own reading, and the one that lets the same Look sit on two different target sets as two
     * independently-toggleable pads.
     *
     * Matched on `source.uuid`, which is neither of the two obvious things and deliberately so.
     * Not the int `id`, because a Look and a template can share an int PK and would then cancel
     * each other's pads. Not the whole [LayerSource] either, though that is what this did first:
     * it is a `data class`, so its equality includes `name` — and a name is *mutable*. Rename a
     * record while its pad is lit and the pad stopped being able to turn it off, because the
     * layer held the old name and the press carried the new one; it stacked a second layer
     * instead, and the first could then only be removed by id from the FX sheet. A uuid is unique
     * across both tables and never changes, which is exactly the identity "already on" means —
     * the same reason [recookIfReferences] matches on it alone.
     *
     * The comparison is of **coverage**, not of target lists ([coverage]), and it is the union of
     * every layer this record already has: "already on" means the record covers *each* pressed
     * target. That makes the press the exact inverse of the pad's own lit ring
     * ([appliedState]) — a full ring turns off, a partial or dark one fills in — which is the rule
     * a whole-set comparison could not express. Order-insensitivity comes free with it (a pad
     * re-deriving its target list from a `Set` still turns its layer off), and so does the group
     * blindness: `{group: wash}` and the `{fixture: …}` list of its members are the same press. A
     * press naming **no** targets has no coverage to compare, so it falls back to a literal
     * [sameTargets] twin — the one gesture where "the source's own bound rows" is the whole of what
     * a layer says.
     * Returns `"applied"`/`"removed"` and how many effects moved, which is the contract
     * `togglePresetOnTargets` had and the pads still read.
     *
     * Note a rows-only Look reports `0` either way, and so does a **value** template. An *effect*
     * template reports one per target since the fx-templates plan, which is why the pads' active
     * ring still cannot be driven from the effect list alone: the number is zero for one kind of
     * template and non-zero for the other.
     *
     * [propertyMask] applies to the *arriving* layer only. It is deliberately **not** part of the
     * "already on" comparison: a pad that re-pressed with a different mask should still take its
     * layer off rather than stack a second one, and the mask a template layer wants is a function of
     * the template, not of the press. A Look passes null — a Look spans families by construction.
     *
     * **Both arms are per target** ([withoutTargets]), and this is the whole of what a press
     * means: *these targets are now this record's, or they are now nobody's.* On the off arm the
     * pressed targets come off **every** layer of this record — one holding exactly them is
     * dropped, one that also holds others is narrowed to those. On the on arm the same subtraction
     * runs over the record's own layers before the new one goes on, which is what keeps the
     * invariant that **at most one layer of a record covers any given head**.
     *
     * Whole-set equality was the first rule, on both arms, and it broke the pad in the ordinary
     * busking case: Red on hex-1, then hex-2 added to the selection and the pad pressed — the press
     * stacked a second Red layer over hex-1 rather than extending the first, so the *next* press
     * removed only that one and left the pad partially lit with Red still on hex-1. Turning a pad
     * off has to clear the record from everything the press names, or "off" means something the
     * ring cannot show.
     *
     * [releaseSiblings] is a template group's exclusivity: the uuids of the *other* templates in
     * the pressed one's group, which give up the pressed targets in the same mutation the new layer
     * goes on — one `layerState` frame, one recook. A sibling that only *overlaps* is narrowed
     * rather than dropped, so Amber on the front wash and Blue on the back wash are still two pads
     * on two rigs. The off arm ignores it entirely: turning a pad off never lights a sibling.
     * Matched on uuid like the pad itself, so a Look's layer can never be released by a template
     * press even where the two share an int PK. Empty (the default) is "no group", which is every
     * Look and every ungrouped template; the route resolves the set, this class only applies it.
     *
     * A layer with **empty** targets is the one thing no subtraction reaches — see
     * [withoutTargets]. The exception is a press that itself names no targets: that is the same
     * gesture as the layer, so it toggles its own such layer off, and takes a sibling's off
     * outright since there is nothing to narrow it to.
     */
    fun toggle(
        source: LayerSource,
        targets: List<CueTargetDto>,
        propertyMask: String? = null,
        beatDivisionOverride: Double? = null,
        releaseSiblings: Set<UUID> = emptySet(),
    ): ToggleOutcome {
        val pressed = coverage(targets).toSet()
        val own = store.layers.filter { it.source.uuid == source.uuid }
        // The union of what this record already covers — one layer answering for the whole record
        // is the point: two presses that each covered half the selection add up to a lit pad, so
        // the press that follows them has to read as "off".
        val covered = own.flatMapTo(HashSet()) { coverage(it.targets) }
        // A press naming no targets has no coverage to compare; its own such layer is the answer.
        val twin = if (pressed.isEmpty()) own.firstOrNull { sameTargets(it.targets, targets) } else null

        return if (twin != null || (pressed.isNotEmpty() && covered.containsAll(pressed))) {
            if (twin != null) {
                ToggleOutcome("removed", remove(twin.layerId).effectsRetracted)
            } else {
                // One mutation for however many layers the press clears, exactly as the apply arm
                // does it: one `layerState` frame, one recook, one `syncEffects` pass.
                val (next, _) = store.mutateLayers { current ->
                    renumber(
                        current.mapNotNull { layer ->
                            if (layer.source.uuid == source.uuid) withoutTargets(layer, pressed) else layer
                        },
                    ) to Unit
                }
                ToggleOutcome("removed", recook(next).effectsRetracted)
            }
        } else {
            val (_, released, outcome) = addReleasing(
                source = source,
                targets = targets,
                propertyMask = propertyMask,
                beatDivisionOverride = beatDivisionOverride,
                release = { layer ->
                    val mine = layer.source.uuid == source.uuid
                    when {
                        !mine && layer.source.uuid !in releaseSiblings -> layer
                        // Nothing to subtract from a layer that names no targets — but a press
                        // that names none either is the same gesture, so it replaces it.
                        layer.targets.isEmpty() -> if (targets.isEmpty()) null else layer
                        else -> withoutTargets(layer, pressed)
                    }
                },
            )
            ToggleOutcome("applied", outcome.effectsSpawned, released)
        }
    }

    /**
     * [layer] with [pressed] taken off it: itself when the press names none of its heads, a copy
     * narrowed to the rest when it names some, and null when it names all of them.
     *
     * The unit is the **head**, not the target: a press on `{hex-1}` has to take hex-1 off a layer
     * holding `{hex-1, hex-3}` rather than either dropping hex-3 with it or leaving hex-1 asserted
     * underneath. Returning the argument *by identity* for an untouched layer is load-bearing —
     * [addReleasing] counts a release by identity, so an untouched sibling is not reported as one.
     *
     * A group the press only *partly* covers is rewritten as the members it did not name, which
     * costs that layer the group spelling (and an effect on it the group's distribution strategy).
     * That is the same thing the operator would have had by picking those fixtures by hand, which
     * is the point of it; a group the press does not touch keeps its own spelling, so the ordinary
     * case never splits. A group that no longer resolves stands for itself ([coverage]), so a press
     * naming it still clears it and a press on a real fixture leaves it alone.
     *
     * A layer with **empty** targets is returned untouched. Empty means "the source's own bound
     * rows", whose fixtures are not in the target list at all — there is nothing here to expand,
     * and guessing at where the rows land is the cook's job rather than this one's.
     */
    private fun withoutTargets(layer: ProgrammerLayer, pressed: Set<CueTargetDto>): ProgrammerLayer? {
        if (layer.targets.isEmpty() || pressed.isEmpty()) return layer
        val remaining = layer.targets.flatMap { held ->
            val expanded = coverage(listOf(held))
            val kept = expanded.filterNot { it in pressed }
            // Untouched targets keep their own spelling — only a group the press partly covers is
            // replaced by the members it left behind.
            if (kept.size == expanded.size) listOf(held) else kept
        }
        return when {
            remaining == layer.targets -> layer
            remaining.isEmpty() -> null
            else -> layer.copy(targets = remaining.distinct())
        }
    }

    /**
     * [targets] with every group replaced by its member fixtures — how this class answers "do these
     * two selections mean the same heads?".
     *
     * A group and the list of its members are two spellings of one selection, so every coverage
     * question [toggle] asks (is this pad already on, and what does a sibling press take away) is
     * asked of the expansion rather than of the written target. The same expansion `CueComposer`
     * does for a cook, minus the logging: a cook has a cue to name in a warning and a value to drop,
     * where a selection comparison has neither.
     *
     * A group that cannot be resolved, or that holds no `Fixture` members, expands to **itself**.
     * That keeps a stale target comparable — two layers naming a since-deleted group still match,
     * and neither matches a fixture — rather than collapsing to the empty set, which would make
     * every such layer look like every other.
     */
    private fun coverage(targets: List<CueTargetDto>): List<CueTargetDto> =
        targets.flatMap { target ->
            // `ofOrNull`, not `of`: a target type this build does not know stands for itself like
            // an unresolvable group, rather than throwing out of a pad press. `CueTargetDto.target`
            // is the strict reading, and the cook is where it belongs.
            when (TargetRef.ofOrNull(target.type, target.key)) {
                is TargetRef.Group -> {
                    val members = runCatching { fixtures().untypedGroup(target.key) }.getOrNull()
                        ?.fixtures.orEmpty()
                        .filterIsInstance<Fixture>()
                        .map { CueTargetDto("fixture", it.key) }
                    members.ifEmpty { listOf(target) }
                }
                else -> listOf(target)
            }
        }

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
     * that head and the wash as partial. Layers are folded by source, so two layers of one
     * template on two selections answer as one record applied to both.
     *
     * A layer with **empty** targets contributes nothing, the same blind spot [toggle] documents:
     * its source's own bound rows decide where it lands, and that is the cook's answer to give.
     * Enabled-ness is ignored on purpose — a disabled layer is still *on the stack*, and a pad's
     * next press still takes it off, so its ring stays lit.
     *
     * Takes the layer list so the `layerState` broadcast can describe the frame it is sending
     * rather than whatever the store holds by the time it renders.
     */
    fun appliedState(layers: List<ProgrammerLayer> = store.layers): List<AppliedSource> {
        val heads = LinkedHashMap<UUID, Pair<LayerSource, MutableSet<CueTargetDto>>>()
        for (layer in layers) {
            if (layer.targets.isEmpty()) continue
            heads.getOrPut(layer.source.uuid) { layer.source to LinkedHashSet() }
                .second += coverage(layer.targets)
        }
        if (heads.isEmpty()) return emptyList()

        // Group membership in the same terms `coverage` produces, so the two can be compared
        // directly. Empty groups are dropped: "every one of no heads" is a claim nothing means.
        val groups = fixtures().groups.map { group ->
            group.name to group.fixtures.filterIsInstance<Fixture>().map { CueTargetDto("fixture", it.key) }
        }.filter { (_, members) -> members.isNotEmpty() }

        return heads.values.map { (source, covered) ->
            val groupTargets = groups.mapNotNull { (name, members) ->
                val hits = members.count { it in covered }
                when (hits) {
                    0 -> null
                    members.size -> AppliedTarget(CueTargetDto("group", name), AppliedExtent.ALL)
                    else -> AppliedTarget(CueTargetDto("group", name), AppliedExtent.SOME)
                }
            }
            AppliedSource(source, covered.map { AppliedTarget(it, AppliedExtent.ALL) } + groupTargets)
        }
    }

    /** Remove a layer by id. A no-op when it is already gone. */
    fun remove(layerId: Int, fadeMs: Long = 0): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            renumber(current.filterNot { it.layerId == layerId }) to Unit
        }
        return recook(next, fadeMs)
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

    /** Change one layer's presentation fields. Null means "leave alone". */
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
                    targets = targets ?: it.targets,
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
                    targets = layer.targets,
                    propertyMask = layer.propertyMask,
                    // The one point a *stored* blend becomes programmer state, and so the one
                    // place the lenient policy belongs on this path: a row written by another
                    // build may name a blend this one does not know, and Record writes these
                    // layers straight back out again. Canonicalising here means the stack, the
                    // UI and every subsequent write agree on the blend the desk is actually
                    // playing — and that `createCueChildren`'s strict check, which Record CREATE
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
        val fx = syncEffects(layers, cooked)
        if (moved.isNotEmpty()) {
            val eng = engine()
            if (!arrival || rowFades.isEmpty() || fadeMs > 0) {
                // A caller-supplied `fadeMs` covers every key and wins outright: an explicit fade on
                // Include is the operator's instruction, and it overrides the source's stored
                // default exactly as `POST /templates/{id}/apply` does with `request.fadeMs`.
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
     */
    private fun syncEffects(
        layers: List<ProgrammerLayer>,
        cooked: CueComposer.FullCook,
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
            val key = ProgrammerLayerEffectKey(layer.layerId, effect, target.key)
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

        val spawned = eng.addEffects(spawning).size

        var retracted = 0
        for ((key, instance) in live) {
            if (key in wanted) continue
            if (eng.removeEffect(instance.id)) retracted++
        }

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
     * `sortedEffectsComparator` is `compareBy(priority, id)` with `id` a monotonic creation
     * counter, so without the offset relative order would be *spawn* order and a reorder would need
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
        effect: EffectEntry,
        target: TargetRef,
        key: ProgrammerLayerEffectKey,
        priority: Int,
        beatDivisionOverride: Double?,
    ): FxInstance? {
        val st = state() ?: return null
        val spec = effect.toEffectSpec()
            .let { if (beatDivisionOverride == null) it else it.copy(beatDivision = beatDivisionOverride) }
        val fxTarget = try {
            EffectSpawner.resolveTargetForCue(st, CueTargetDto(target), spec)
        } catch (e: Exception) {
            logger.warn(
                "programmer layer on '{}' — target '{}' unresolvable — skipping effect: {}",
                layer.source.name, target.key, e.message,
            )
            null
        } ?: return null

        // [createEffectInstance] gives this a **live** colour source, unlike the frozen
        // snapshot this path used to build for itself.
        //
        // The old positional colour list was captured at include time — version pinned to `0L` — so
        // an effect on a programmer layer kept the colours it was born with. A `tmpl:` reference is
        // a live dependency by design: the whole point of naming a template rather than stating a
        // colour is that retuning it moves everything that follows it, and a programmer layer is no
        // more exempt from that than a cue's.
        val instance = try {
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
            return null
        }
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

    private fun renumber(layers: List<ProgrammerLayer>): List<ProgrammerLayer> =
        layers.mapIndexed { index, layer ->
            if (layer.sortOrder == index) layer else layer.copy(sortOrder = index)
        }
}
